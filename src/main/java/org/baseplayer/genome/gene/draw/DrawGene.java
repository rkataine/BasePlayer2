package org.baseplayer.genome.gene.draw;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.annotation.CosmicGenes;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.genome.gene.Transcript;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.GeneColors;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Renders a single {@link Gene} row — body line, exon bars, exon-number labels,
 * and gene-name labels.  Exon bar pixels are delegated to {@link DrawExon}.
 *
 * <p>An instance is created by {@link ChromosomeCanvas} and reused across draw cycles.
 * The {@link #hitBoxes} list is owned here and rebuilt each frame; callers read it after
 * {@code drawGene} calls to support click / hover detection.
 */
class DrawGene {

  // ── Layout constants ─────────────────────────────────────────────────────────
  static final double GENE_AREA_TOP    = 8;
  static final double GENE_ROW_HEIGHT  = 22;
  static final double GENE_CHAR_WIDTH  = 6.0;
  static final double GENE_PADDING     = 15.0;
  static final int    MAX_GENE_ROWS    = 500;
  static final int    GENE_FONT_SIZE   = 10;

  // ── Owned state ──────────────────────────────────────────────────────────────
  private final GraphicsContext gc;
  private final DrawExon drawExon;

  /** Gene hit boxes rebuilt each frame. Read by ChromosomeCanvas for mouse events. */
  final List<GeneHitBox> hitBoxes = new ArrayList<>();

  // ────────────────────────────────────────────────────────────────────────────

  DrawGene(GraphicsContext gc, DrawExon drawExon) {
    this.gc        = gc;
    this.drawExon  = drawExon;
  }

  void clearHitBoxes() {
    hitBoxes.clear();
  }

  // ── Gene rendering ───────────────────────────────────────────────────────────

  /**
   * Draws a single gene at {@code rowY} into {@code gc}.
   *
   * @param gene         the gene to render
   * @param rowY         top-left Y of the row (includes space for the name label)
   * @param viewStart    leftmost genomic coordinate on screen
   * @param viewLength   number of bases across the full canvas width
   * @param canvasWidth  pixel width of the canvas
   * @param showManeOnly if {@code true}, only MANE-select exons are shown
   */
  void drawGene(Gene gene, double rowY, double viewStart, double viewLength,
                double canvasWidth, boolean showManeOnly) {
    Color geneColor = GeneColors.getGeneColor(gene.name(), gene.biotype());

    List<long[]> exonsToShow = gene.getDisplayExons(showManeOnly);

    Transcript maneTranscript = gene.getManeSelectTranscript();
    if (maneTranscript == null && gene.transcripts() != null && !gene.transcripts().isEmpty()) {
      maneTranscript = gene.transcripts().get(0);
    }
    long    cdsStart = maneTranscript != null ? maneTranscript.cdsStart() : 0;
    long    cdsEnd   = maneTranscript != null ? maneTranscript.cdsEnd()   : 0;
    boolean hasCDS   = cdsStart > 0 && cdsEnd > 0;

    long bodyStart = gene.start();
    long bodyEnd   = gene.end();
    if (!exonsToShow.isEmpty()) {
      bodyStart = exonsToShow.get(0)[0];
      bodyEnd   = exonsToShow.get(exonsToShow.size() - 1)[1];
    }

    double x1 = ((bodyStart - viewStart) / viewLength) * canvasWidth;
    double x2 = ((bodyEnd   - viewStart) / viewLength) * canvasWidth;

    double clippedX1 = Math.max(0, x1);
    double clippedX2 = Math.min(canvasWidth, x2);
    if (clippedX2 <= clippedX1) return;

    // Ensure cancer genes are always at least 1 pixel wide
    boolean isCancerGene = CosmicGenes.isCosmicGene(gene.name());
    if (isCancerGene && clippedX2 - clippedX1 < 1) {
      clippedX2 = clippedX1 + 1;
    }

    // Gene body line (first to last visible exon)
    if (clippedX2 - clippedX1 > 3) {
      gc.setStroke(geneColor);
      gc.setLineWidth(1);
      double bodyY = Math.round(rowY + DrawExon.GENE_LABEL_HEIGHT + DrawExon.GENE_HEIGHT / 2) + 0.5;
      gc.strokeLine(Math.round(clippedX1) + 1, bodyY, Math.round(clippedX2) - 1, bodyY);
    }

    boolean showExonNumbers  = viewLength < 100_000;
    boolean showAminoAcids   = viewLength < 500;
    boolean showPropertyColors = viewLength < DrawExon.BASE_DISPLAY_THRESHOLD && !showAminoAcids;
    boolean isReverse        = "-".equals(gene.strand());

    // Per-exon CDS sizes for cumulative reading-frame offset
    long[] cdsExonSizes = new long[exonsToShow.size()];
    for (int i = 0; i < exonsToShow.size(); i++) {
      long[] exon = exonsToShow.get(i);
      if (hasCDS && exon[1] >= cdsStart && exon[0] <= cdsEnd) {
        long regionStart = Math.max(exon[0], cdsStart);
        long regionEnd   = Math.min(exon[1], cdsEnd);
        cdsExonSizes[i]  = regionEnd - regionStart + 1;
      }
    }

    long[] cdsOffsets = new long[exonsToShow.size()];
    if (isReverse) {
      long cumulative = 0;
      for (int i = exonsToShow.size() - 1; i >= 0; i--) {
        cdsOffsets[i] = cumulative;
        cumulative   += cdsExonSizes[i];
      }
    } else {
      long cumulative = 0;
      for (int i = 0; i < exonsToShow.size(); i++) {
        cdsOffsets[i] = cumulative;
        cumulative   += cdsExonSizes[i];
      }
    }

    for (int i = 0; i < exonsToShow.size(); i++) {
      long[] exon       = exonsToShow.get(i);
      int    exonNumber = isReverse ? (exonsToShow.size() - i) : (i + 1);

      if (hasCDS && (exon[0] < cdsStart || exon[1] > cdsEnd)) {
        // 5′ UTR
        if (exon[0] < cdsStart) {
          long utrEnd = Math.min(exon[1], cdsStart - 1);
          if (utrEnd >= exon[0]) {
            drawExon.drawExonRegion(exon[0], utrEnd, viewStart, viewLength, canvasWidth, rowY,
                                    GeneColors.UTR_COLOR, false, false, null, isReverse, 0);
          }
        }
        // CDS portion
        if (exon[1] >= cdsStart && exon[0] <= cdsEnd) {
          long regionStart = Math.max(exon[0], cdsStart);
          long regionEnd   = Math.min(exon[1], cdsEnd);
          drawExon.drawExonRegion(regionStart, regionEnd, viewStart, viewLength, canvasWidth, rowY,
                                   geneColor, showAminoAcids, showPropertyColors, gene, isReverse, cdsOffsets[i]);
        }
        // 3′ UTR
        if (exon[1] > cdsEnd) {
          long utrStart = Math.max(exon[0], cdsEnd + 1);
          if (utrStart <= exon[1]) {
            drawExon.drawExonRegion(utrStart, exon[1], viewStart, viewLength, canvasWidth, rowY,
                                    GeneColors.UTR_COLOR, false, false, null, isReverse, 0);
          }
        }
      } else if (hasCDS) {
        // Entire exon is CDS
        drawExon.drawExonRegion(exon[0], exon[1], viewStart, viewLength, canvasWidth, rowY,
                                 geneColor, showAminoAcids, showPropertyColors, gene, isReverse, cdsOffsets[i]);
      } else {
        // Non-coding gene
        drawExon.drawExonRegion(exon[0], exon[1], viewStart, viewLength, canvasWidth, rowY,
                                 geneColor, false, false, null, isReverse, 0);
      }

      // Exon number label
      if (showExonNumbers) {
        double ex1 = ((exon[0] - viewStart) / viewLength) * canvasWidth;
        double ex2 = ((exon[1] - viewStart) / viewLength) * canvasWidth;
        double exonWidth = Math.max(0, Math.min(canvasWidth, ex2) - Math.max(0, ex1));
        double exonX     = Math.max(0, ex1);
        double exonY     = Math.round(rowY + DrawExon.GENE_LABEL_HEIGHT);

        if (exonWidth > 10) {
          // Skip exon number if it would overlap the gene name label above it
          double labelX   = Math.max(2, x1);
          double labelEnd = labelX + gene.name().length() * GENE_CHAR_WIDTH;
          String exonLabel = String.valueOf(exonNumber);
          double textWidth = exonLabel.length() * 5;
          double textX     = exonX + (exonWidth - textWidth) / 2;
          boolean overlapsLabel = textX < labelEnd && (textX + textWidth) > labelX;

          if (!overlapsLabel) {
            gc.setFont(AppFonts.getUIFont(9));
            gc.setFill(Color.rgb(220, 220, 220, 0.9));
            gc.fillText(exonLabel, textX, exonY - 2);
            gc.setFont(AppFonts.getUIFont());
          }
        }
      }
    }

    // Gene name label (already computed above for overlap check)
    boolean showLabel     = isCancerGene || viewLength < 10_000_000 || "protein_coding".equals(gene.biotype());
    double  labelX        = Math.max(2, x1);
    double  labelWidth    = 0;
    if (showLabel) {
      gc.setFill(geneColor);
      gc.fillText(gene.name(), labelX, rowY + DrawExon.GENE_LABEL_HEIGHT - 2);
      labelWidth = gene.name().length() * GENE_CHAR_WIDTH;
    }

    double hitX1 = Math.max(0, Math.min(labelX, clippedX1));
    double hitX2 = Math.max(clippedX2, labelX + labelWidth);
    hitBoxes.add(new GeneHitBox(gene, hitX1, rowY, hitX2,
                                rowY + DrawExon.GENE_LABEL_HEIGHT + DrawExon.GENE_HEIGHT));
  }

  // ── Hit-box record ───────────────────────────────────────────────────────────

  record GeneHitBox(Gene gene, double x1, double y1, double x2, double y2) {
    boolean contains(double x, double y) {
      return x >= x1 && x <= x2 && y >= y1 && y <= y2;
    }
  }
}
