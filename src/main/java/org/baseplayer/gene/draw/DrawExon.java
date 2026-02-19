package org.baseplayer.gene.draw;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.baseplayer.alignment.FetchManager;
import org.baseplayer.chromosome.draw.CytobandCanvas;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.gene.Gene;
import org.baseplayer.services.ReferenceGenomeService;
import org.baseplayer.utils.AminoAcids;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.BaseColors;
import org.baseplayer.utils.BaseUtils;
import org.baseplayer.utils.GeneColors;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

/**
 * Handles rendering of exon bars, property-colored codon stripes, individual
 * amino-acid ovals, and the reference-base track at the bottom of the gene canvas.
 *
 * <p>An instance is created by {@link ChromosomeCanvas} and reused across draw cycles.
 * The {@link #hitBoxes} list is owned here and rebuilt each frame; callers read it
 * after {@code drawExonRegion} calls complete to support click / hover detection.
 */
class DrawExon {

  // ── Shared geometry constants (also used by DrawGene and ChromosomeCanvas) ──
  static final double GENE_HEIGHT = 12;
  static final double GENE_LABEL_HEIGHT = 10;

  // ── Reference-base display thresholds ──
  static final int BASE_DISPLAY_THRESHOLD = 100_000;
  private static final int REFERENCE_BUFFER = 50_000;

  // ── Reference-base cache ──
  String cachedBases = "";
  int cachedStart = 0;
  int cachedEnd = 0;
  String cachedChromosome = "";

  // ── Async loading state ──
  private final AtomicBoolean isLoadingBases = new AtomicBoolean(false);
  private volatile int pendingFetchStart = -1;
  private volatile int pendingFetchEnd = -1;
  private volatile String pendingFetchChrom = "";

  // ── Owned state ──
  private final GraphicsContext gc;
  final DrawStack drawStack;
  private final ReferenceGenomeService referenceGenomeService;

  /** Amino-acid hit boxes rebuilt each frame. Read by ChromosomeCanvas for mouse events. */
  final List<AminoAcidHitBox> hitBoxes = new ArrayList<>();

  // ────────────────────────────────────────────────────────────────────────────

  DrawExon(GraphicsContext gc, DrawStack drawStack, ReferenceGenomeService referenceGenomeService) {
    this.gc = gc;
    this.drawStack = drawStack;
    this.referenceGenomeService = referenceGenomeService;
  }

  void clearHitBoxes() {
    hitBoxes.clear();
  }

  // ── Exon bar rendering ───────────────────────────────────────────────────────

  /**
   * Draws one contiguous exon (or UTR) region.
   * Delegates to amino-acid, property-color, or solid-gradient rendering depending on zoom.
   */
  void drawExonRegion(long regionStart, long regionEnd, double viewStart, double viewLength,
                      double canvasWidth, double rowY, Color color,
                      boolean showAminoAcids, boolean showPropertyColors,
                      Gene gene, boolean isReverse, long cdsOffset) {
    double ex1 = ((regionStart - viewStart) / viewLength) * canvasWidth;
    double ex2 = ((regionEnd   - viewStart) / viewLength) * canvasWidth;
    ex1 = Math.max(0, ex1);
    ex2 = Math.min(canvasWidth, ex2);

    if (ex2 < ex1) return;

    double exonX     = Math.round(ex1);
    double exonY     = Math.round(rowY + GENE_LABEL_HEIGHT);
    double exonWidth = Math.round(ex2) - exonX;
    if (exonWidth < 1) exonWidth = 1;

    if (showAminoAcids && gene != null && referenceGenomeService.hasGenome()) {
      drawAminoAcidsInRegion(regionStart, regionEnd, viewStart, viewLength, canvasWidth,
                              rowY, gene, isReverse, cdsOffset);

    } else if (showPropertyColors && gene != null && referenceGenomeService.hasGenome()) {
      drawPropertyColoredExon(regionStart, regionEnd, viewStart, viewLength, canvasWidth,
                               rowY, isReverse, cdsOffset);
      // Subtle 3D highlight/shadow overlay
      javafx.scene.paint.LinearGradient overlay = new javafx.scene.paint.LinearGradient(
          0, exonY, 0, exonY + GENE_HEIGHT, false, javafx.scene.paint.CycleMethod.NO_CYCLE,
          new javafx.scene.paint.Stop(0.0, Color.rgb(255, 255, 255, 0.20)),
          new javafx.scene.paint.Stop(0.4, Color.TRANSPARENT),
          new javafx.scene.paint.Stop(1.0, Color.rgb(0, 0, 0, 0.18))
      );
      gc.setFill(overlay);
      gc.fillRect(exonX, exonY, exonWidth, GENE_HEIGHT);

    } else {
      // Standard 3D gradient (UTRs, non-coding genes, or genome not loaded)
      Color lighter = color.interpolate(Color.WHITE, 0.3);
      Color darker  = color.interpolate(Color.BLACK, 0.2);
      javafx.scene.paint.LinearGradient gradient = new javafx.scene.paint.LinearGradient(
          0, exonY, 0, exonY + GENE_HEIGHT, false, javafx.scene.paint.CycleMethod.NO_CYCLE,
          new javafx.scene.paint.Stop(0,   lighter),
          new javafx.scene.paint.Stop(0.3, color),
          new javafx.scene.paint.Stop(0.7, color),
          new javafx.scene.paint.Stop(1,   darker)
      );
      gc.setFill(gradient);
      gc.fillRect(exonX, exonY, exonWidth, GENE_HEIGHT);
    }
  }

  /**
   * Paints each codon in a CDS region as a thin horizontal strip coloured by its amino-acid
   * property group (hydrophobic / polar / positive / negative). Used when zoomed in enough
   * that the reference sequence is cached but not so close that individual ovals are drawn.
   * The caller is responsible for adding the 3D overlay on top.
   */
  private void drawPropertyColoredExon(long regionStart, long regionEnd, double viewStart,
                                        double viewLength, double canvasWidth, double rowY,
                                        boolean isReverse, long cdsOffset) {
    if (cachedBases.isEmpty() || !drawStack.chromosome.equals(cachedChromosome)
        || regionStart < cachedStart || regionEnd > cachedEnd) return;

    int startIdx = (int) (regionStart - cachedStart);
    int endIdx   = (int) (regionEnd   - cachedStart + 1);
    if (startIdx < 0 || endIdx > cachedBases.length()) return;

    String bases = cachedBases.substring(startIdx, endIdx);
    if (bases.isEmpty()) return;

    if (isReverse) bases = BaseUtils.reverseComplement(bases);

    double exonY      = Math.round(rowY + GENE_LABEL_HEIGHT);
    int    frameOffset = (int) ((3 - (cdsOffset % 3)) % 3);

    for (int i = frameOffset; i + 2 < bases.length(); i += 3) {
      char aminoAcid = AminoAcids.translateCodon(bases.substring(i, i + 3));

      long codonGenomicStart, codonGenomicEnd;
      if (isReverse) {
        codonGenomicStart = regionEnd - i - 2;
        codonGenomicEnd   = regionEnd - i + 1;
      } else {
        codonGenomicStart = regionStart + i;
        codonGenomicEnd   = regionStart + i + 3;
      }

      double cx1 = Math.max(0,           ((codonGenomicStart - viewStart) / viewLength) * canvasWidth);
      double cx2 = Math.min(canvasWidth, ((codonGenomicEnd   - viewStart) / viewLength) * canvasWidth);
      if (cx2 <= cx1) continue;

      gc.setFill(AminoAcids.getPropertyColor(aminoAcid));
      gc.fillRect(cx1, exonY, Math.max(1, cx2 - cx1), GENE_HEIGHT);
    }
  }

  /**
   * Draws individual amino-acid ovals with contrasting letter labels at the highest zoom level.
   */
  private void drawAminoAcidsInRegion(long regionStart, long regionEnd, double viewStart,
                                       double viewLength, double canvasWidth, double rowY,
                                       Gene gene, boolean isReverse, long cdsOffset) {
    if (cachedBases.isEmpty() || regionStart < cachedStart || regionEnd > cachedEnd
        || !drawStack.chromosome.equals(cachedChromosome)) return;

    int startIndex = (int) (regionStart - cachedStart);
    int endIndex   = (int) (regionEnd   - cachedStart + 1);
    if (startIndex < 0 || endIndex > cachedBases.length()) return;

    String bases = cachedBases.substring(startIndex, endIndex);
    if (bases.isEmpty()) return;

    if (isReverse) bases = BaseUtils.reverseComplement(bases);

    double exonY      = Math.round(rowY + GENE_LABEL_HEIGHT);
    int    frameOffset = (int) ((3 - (cdsOffset % 3)) % 3);

    gc.setTextAlign(TextAlignment.CENTER);

    for (int i = frameOffset; i + 2 < bases.length(); i += 3) {
      String codon      = bases.substring(i, i + 3);
      char   aminoAcid  = AminoAcids.translateCodon(codon);
      String threeLetter = GeneColors.getAminoAcidThreeLetter(aminoAcid);

      long codonStart, codonEnd;
      if (isReverse) {
        codonStart = regionEnd - i - 2;
        codonEnd   = regionEnd - i + 1;
      } else {
        codonStart = regionStart + i;
        codonEnd   = regionStart + i + 3;
      }

      long cdsPosition   = cdsOffset + i + 1;
      int  aminoAcidNum  = (int) ((cdsPosition - 1) / 3) + 1;

      double cx1 = Math.max(0,           ((codonStart - viewStart) / viewLength) * canvasWidth);
      double cx2 = Math.min(canvasWidth, ((codonEnd   - viewStart) / viewLength) * canvasWidth);

      if (cx2 > cx1) {
        double codonWidth = cx2 - cx1;
        double codonX     = cx1 + codonWidth / 2;
        double ovalHeight = GENE_HEIGHT - 2;

        Color aaColor = AminoAcids.getPropertyColor(aminoAcid);
        gc.setFill(aaColor);
        gc.fillOval(cx1, exonY + 1, codonWidth, ovalHeight);

        hitBoxes.add(new AminoAcidHitBox(
            aminoAcid, codon, aminoAcidNum, codonStart, cdsPosition,
            gene.name(), isReverse, cx1, exonY, cx2, exonY + GENE_HEIGHT
        ));

        if (codonWidth > 15) {
          gc.setFont(AppFonts.getMonoFont(Math.min(9, GENE_HEIGHT * 0.6)));
          gc.setFill(GeneColors.getContrastingTextColor(aaColor));
          gc.fillText(threeLetter, codonX, exonY + GENE_HEIGHT - 3);
        }
      }
    }

    gc.setTextAlign(TextAlignment.LEFT);
  }

  // ── Reference-base track ─────────────────────────────────────────────────────

  /**
   * Draws (or asynchronously loads) the reference-sequence colour track.
   *
   * @param canvasHeight height of the gene canvas, used to position the track
   */
  void drawReferenceBases(double canvasWidth, double canvasHeight) {
    if (drawStack.viewLength > BASE_DISPLAY_THRESHOLD) return;
    if (!referenceGenomeService.hasGenome()) return;

    if (GenomicCanvas.animationRunning || CytobandCanvas.isDragging) return;

    int    viewStart    = (int) drawStack.start;
    int    viewEnd      = (int) drawStack.end;
    String currentChrom = drawStack.chromosome;

    boolean needsFetch = cachedBases.isEmpty()
        || !currentChrom.equals(cachedChromosome)
        || viewStart < cachedStart
        || viewEnd   > cachedEnd;

    if (needsFetch) {
      int fetchStart = Math.max(1, viewStart - REFERENCE_BUFFER);
      int fetchEnd   = viewEnd + REFERENCE_BUFFER;

      boolean alreadyLoading = isLoadingBases.get()
          && fetchStart == pendingFetchStart
          && fetchEnd   == pendingFetchEnd
          && currentChrom.equals(pendingFetchChrom);

      if (!alreadyLoading && isLoadingBases.compareAndSet(false, true)) {
        FetchManager fm = FetchManager.get();
        if (!fm.canFetch(FetchManager.FetchType.REFERENCE, fetchEnd - fetchStart)) {
          isLoadingBases.set(false);
          return;
        }

        pendingFetchStart = fetchStart;
        pendingFetchEnd   = fetchEnd;
        pendingFetchChrom = currentChrom;

        FetchManager.FetchTicket ticket = fm.acquire(
            FetchManager.FetchType.REFERENCE, this, drawStack, currentChrom, fetchStart, fetchEnd);

        CompletableFuture.runAsync(() -> {
          String bases = referenceGenomeService.getBases(currentChrom, fetchStart, fetchEnd);
          Platform.runLater(() -> {
            if (!ticket.isCancelled()
                && fetchStart == pendingFetchStart && fetchEnd == pendingFetchEnd
                && currentChrom.equals(pendingFetchChrom)) {
              cachedBases       = bases;
              cachedStart       = fetchStart;
              cachedEnd         = fetchEnd;
              cachedChromosome  = currentChrom;
            }
            isLoadingBases.set(false);
            fm.release(ticket);
            GenomicCanvas.resizing = true;
            GenomicCanvas.update.set(!GenomicCanvas.update.get());
            GenomicCanvas.resizing = false;
          });
        });
      }
    }

    if (cachedBases.isEmpty()) return;

    double baseHeight = 8;
    double yPos       = canvasHeight - baseHeight - 2;

    boolean drawLetters = drawStack.viewLength < 200;

    if (drawLetters) {
      gc.setFont(AppFonts.getMonoFont(Math.min(12, drawStack.pixelSize * 0.8)));
      gc.setTextAlign(TextAlignment.CENTER);
    }

    int    step        = Math.max(1, (int) Math.ceil(1.0 / drawStack.pixelSize));
    double lastDrawnX  = -1;

    for (int chromPos = viewStart; chromPos <= viewEnd; chromPos += step) {
      int cacheIndex = chromPos - cachedStart;
      if (cacheIndex < 0 || cacheIndex >= cachedBases.length()) continue;

      char   base    = cachedBases.charAt(cacheIndex);
      double xPos    = ((chromPos - drawStack.start) / drawStack.viewLength) * canvasWidth;

      if (!drawLetters && Math.floor(xPos) == Math.floor(lastDrawnX)) continue;
      lastDrawnX = xPos;

      gc.setFill(BaseColors.getBaseColor(base));

      if (drawLetters) {
        gc.fillText(String.valueOf(base), xPos + drawStack.pixelSize / 2, yPos + baseHeight - 3);
      } else {
        gc.fillRect(Math.floor(xPos), yPos, 1, baseHeight);
      }
    }

    gc.setTextAlign(TextAlignment.LEFT);
  }

  // ── Hit-box record ───────────────────────────────────────────────────────────

  record AminoAcidHitBox(
      char   aminoAcid,
      String codon,
      int    aminoAcidNumber,
      long   genomicStart,
      long   cdsPosition,
      String geneName,
      boolean isReverse,
      double x1, double y1, double x2, double y2
  ) {
    boolean contains(double x, double y) {
      return x >= x1 && x <= x2 && y >= y1 && y <= y2;
    }
  }
}
