package org.baseplayer.alignment.draw;

import java.util.List;
import java.util.Map;

import org.baseplayer.alignment.AlignmentFile;
import org.baseplayer.alignment.BAMRecord;
import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.Settings;
import org.baseplayer.sample.Sample;
import org.baseplayer.sample.SampleTrack;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.DrawColors;
import org.baseplayer.variant.Variant;

import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Window;

public class AlignmentCanvas extends GenomicCanvas {

  public Image snapshot;
  private final GraphicsContext gc;

  /** Unified coverage data computation and rendering. */
  private final CoverageDrawer coverageDrawer = new CoverageDrawer();

  /** Unified read rendering, hit-testing, and reactive highlighting. */
  private final DrawReads drawReads;

  private static final double MIN_COVERAGE_HEIGHT = 30;
  private static final double MAX_COVERAGE_HEIGHT = 60;

  /**
   * Pre-computed per-sample layout geometry used by rendering, hit-testing,
   * and hover highlighting.  Avoids the previous 3× duplication.
   */
  record ReadLayout(double readsY, double readsH, double readHeight,
                    double gap, int maxRow, int hp2Start, double scrollOffset,
                    boolean butterfly, boolean methylation) {

    static ReadLayout compute(double sampleY, double sampleH, double coverageH,
                              AlignmentFile bamFile, Sample sample, DrawStack drawStack) {
      double readsY     = sampleY + coverageH;
      double readsH     = sampleH - coverageH;
      double gap        = Settings.get().getReadGap();
      int    maxRow     = bamFile.getMaxRow(drawStack) + 1;
      int    hp2Start   = bamFile.getHP2StartRow(drawStack);
      double scrollOff  = bamFile.readScrollOffset;
      boolean butterfly = hp2Start >= 0 && sample.isHaplotypeData();
      boolean methyl    = sample.isMethylationData();

      double readHeight;
      if (butterfly) {
        int maxPerHalf = Math.max(hp2Start, maxRow - hp2Start);
        readHeight = Math.max(Settings.get().getMinReadHeight(),
            Math.min(8, (readsH / 2 - 4) / Math.max(1, maxPerHalf)));
      } else {
        readHeight = Math.max(Settings.get().getMinReadHeight(),
            Math.min(8, (readsH - 2) / Math.max(1, maxRow)));
      }

      return new ReadLayout(readsY, readsH, readHeight, gap, maxRow,
                            hp2Start, scrollOff, butterfly, methyl);
    }
  }

  private BAMRecord hoveredRead = null;
  private final ReadInfoPopup readInfoPopup = new ReadInfoPopup();

  public AlignmentCanvas(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack) {
    super(reactiveCanvas, parent, drawStack);
    widthProperty().addListener((obs, o, n) -> { resizing = true; setStartEnd(drawStack.start, drawStack.end); resizing = false; });
    gc = getGraphicsContext2D();
    gc.setLineWidth(1);
    drawReads = new DrawReads(gc, chromPosToScreenPos);
    setupReadMouseHandlers(reactiveCanvas);
    Platform.runLater(this::draw);
  }

  // ── Mouse handlers ────────────────────────────────────────────────────────────

  private void setupReadMouseHandlers(Canvas reactiveCanvas) {
    reactiveCanvas.setOnMouseMoved(event -> {
      if (isDragging() || animationRunning) return;
      BAMRecord hit = findReadAt(event.getX(), event.getY());
      if (hit != hoveredRead) {
        hoveredRead = hit;
        reactiveCanvas.setCursor(hit != null ? Cursor.HAND : Cursor.DEFAULT);
        drawReadHighlight();
      }
    });

    reactiveCanvas.setOnMouseClicked(event -> {
      if (event.getClickCount() == 1 && event.isStillSincePress()) {
        BAMRecord hit = findReadAt(event.getX(), event.getY());
        if (hit != null) {
          Window owner = reactiveCanvas.getScene() != null ? reactiveCanvas.getScene().getWindow() : null;
          if (owner != null) {
            String chrom = drawStack.chromosome != null ? drawStack.chromosome : "";
            String mateChrName = resolveMateChromName(hit);
            readInfoPopup.show(hit, chrom, mateChrName, owner,
                event.getScreenX() + 10, event.getScreenY() + 10,
                (mateChr, matePos) -> Platform.runLater(() -> MainController.addStackAtPosition(mateChr, matePos)));
          }
        } else {
          readInfoPopup.hide();
        }
      }
    });

    reactiveCanvas.setOnMouseExited(event -> {
      if (hoveredRead != null) {
        hoveredRead = null;
        reactiveCanvas.setCursor(Cursor.DEFAULT);
        drawReadHighlight();
      }
    });
  }

  // ── Main draw ─────────────────────────────────────────────────────────────────

  @Override
  public void draw() {
    gc.setFill(DrawColors.BACKGROUND);
    gc.fillRect(0, 0, getWidth() + 1, getHeight() + 1);

    double masterOffset = sampleRegistry.getMasterTrackHeight();
    double available    = getHeight() - masterOffset;
    sampleRegistry.setSampleHeight(available / Math.max(1, sampleRegistry.getVisibleSampleCount()));

    drawBamReads();
    if (drawStack.variants != null) drawVariants();
    super.draw();
  }

  // ── Variant drawing ───────────────────────────────────────────────────────────

  void drawVariants() {
    for (Variant variant : drawStack.variants) {
      if (variant.line.getEndX() < drawStack.start - 1) continue;
      if (variant.line.getStartX() > drawStack.end) break;
      drawVariantLine(variant, DrawColors.lineColor, gc);
    }
  }

  void drawVariantLine(Variant variant, Color color, GraphicsContext gc) {
    if (variant.index < sampleRegistry.getFirstVisibleSample()
        || variant.index > sampleRegistry.getLastVisibleSample() + 1) return;
    gc.setFill(color);
    double screenPos = chromPosToScreenPos.apply(variant.line.getStartX());
    double ypos = sampleRegistry.getMasterTrackHeight()
        + sampleRegistry.getSampleHeight() * variant.index
        - sampleRegistry.getScrollBarPosition();
    double height = heightToScreen.apply(variant.line.getEndY());
    double w = Math.max(1, drawStack.pixelSize);
    gc.fillRect(Math.floor(screenPos), ypos - height, w, height);
  }

  // ── BAM reads / coverage ──────────────────────────────────────────────────────

  /**
   * Top-level dispatcher for all alignment-related drawing.
   * Three zoom levels:
   * <ol>
   *   <li>Beyond sampled-coverage range → zoom-in message</li>
   *   <li>Sampled-coverage range → sparse profile via {@link CoverageDrawer#drawSampled}</li>
   *   <li>Full range → per-base coverage + optional individual reads</li>
   * </ol>
   */
  void drawBamReads() {
    if (sampleRegistry.getSampleList().isEmpty()) return;

    double masterOffset = sampleRegistry.getMasterTrackHeight();
    double sampleH      = sampleRegistry.getSampleHeight();
    String chrom        = drawStack.chromosome;
    int    start        = Math.max(0, (int) drawStack.start);
    int    end          = (int) drawStack.end;

    // ── Very zoomed out: sampled coverage ──
    if (drawStack.viewLength > Settings.get().getMaxCoverageViewLength()) {
      if (!Settings.get().isEnableSampledCoverage()) {
        forEachVisibleSample(masterOffset, sampleH, (sampleY, sample) ->
            DrawReads.drawZoomMessage(gc, sampleY, sampleH, "Zoom in closer to view BAM/CRAM data"));
        return;
      }
      forEachVisibleSample(masterOffset, sampleH, (sampleY, sample) ->
          coverageDrawer.drawSampled(gc, sample, chrom, start, end,
              sampleY, sampleH, getWidth(), chromPosToScreenPos, drawStack));
      return;
    }

    // ── Normal zoom: per-base coverage + optional reads ──
    boolean coverageOnly = drawStack.viewLength > Settings.get().getMaxReadViewLength();
    double coverageFractionH = Math.max(MIN_COVERAGE_HEIGHT,
        Math.min(MAX_COVERAGE_HEIGHT, sampleH * Settings.get().getCoverageFraction()));
    try {
      coverageDrawer.compute(drawStack, chromPosToScreenPos, (int) getWidth());
      coverageDrawer.render(gc, getWidth(), masterOffset, sampleH,
          sampleRegistry.getScrollBarPosition(), coverageOnly, coverageFractionH);

      if (!coverageOnly) {
        forEachVisibleSample(masterOffset, sampleH, (sampleY, sample) ->
            drawSampleReads(sample, chrom, start, end, sampleY, sampleH, coverageFractionH));
      }
    } catch (Exception e) {
      System.err.println("Error drawing BAM reads: " + e.getMessage());
    }
  }

  /**
   * Draws reads for one BAM/CRAM sample below its coverage strip.
   * Coverage is already drawn by {@link CoverageDrawer}; this only handles
   * read rows, scroll, and read-group separators.
   */
  private void drawSampleReads(Sample sample, String chrom, int start, int end,
                                double sampleY, double sampleH, double coverageH) {
    if (sample.getDataType() != Sample.DataType.BAM) return;

    AlignmentFile bamFile = sample.getBamFile();
    if (bamFile == null) return;

    boolean isHoverStack      = (drawStack == ServiceRegistry.getInstance().getDrawStackManager().getHoverStack());
    boolean shouldShowLoading = bamFile.isLoading(drawStack) && (isHoverStack || !GenomicCanvas.navigating);
    if (shouldShowLoading) drawReads.drawLoadingIndicator(sampleY, sampleH);

    String status = sample.getStatusMessage();
    if (status != null && !status.isEmpty()) {
      gc.setFill(Color.rgb(200, 200, 200, 0.9));
      gc.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.NORMAL, 11));
      gc.fillText(status, 10, sampleY + 15);
    }

    List<BAMRecord> reads = bamFile.getCachedReads(drawStack);
    if (reads == null || reads.isEmpty()) {
      if (GenomicCanvas.navigating && !isHoverStack) return;
      reads = bamFile.getReads(chrom, start, end, drawStack, true);
    }
    if (reads == null || reads.isEmpty()) return;

    ReadLayout layout = ReadLayout.compute(sampleY, sampleH, coverageH, bamFile, sample, drawStack);

    gc.setStroke(DrawColors.COVERAGE_SEPARATOR);
    gc.strokeLine(0, layout.readsY(), getWidth(), layout.readsY());

    if (layout.butterfly()) {
      double middleY = layout.readsY() + layout.readsH() / 2;
      gc.setStroke(DrawColors.ALLELE_SEPARATOR);
      gc.setLineWidth(1.5);
      gc.strokeLine(0, middleY, getWidth(), middleY);
      gc.setLineWidth(1.0);
      gc.setFill(DrawColors.ALLELE_HP1_LABEL);
      gc.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 9));
      gc.fillText("HP1", 4, middleY - 4);
      gc.setFill(DrawColors.ALLELE_HP2_LABEL);
      gc.fillText("HP2", 4, middleY + 11);
    }

    Color fwdFill, revFill, fwdStroke, revStroke;
    if (sample.overlay) {
      fwdFill = DrawColors.OVERLAY_FORWARD;          revFill   = DrawColors.OVERLAY_REVERSE;
      fwdStroke = DrawColors.OVERLAY_FORWARD_STROKE; revStroke = DrawColors.OVERLAY_REVERSE_STROKE;
    } else {
      fwdFill = DrawColors.READ_FORWARD;             revFill   = DrawColors.READ_REVERSE;
      fwdStroke = DrawColors.READ_FORWARD_STROKE;    revStroke = DrawColors.READ_REVERSE_STROKE;
    }

    drawReads.draw(reads, layout.readsY(), layout.readsH(), layout.readHeight(),
        layout.gap(), layout.butterfly(), layout.hp2Start(), layout.scrollOffset(),
        fwdFill, revFill, fwdStroke, revStroke, layout.methylation(),
        sampleY + coverageH, sampleY + sampleH, getWidth());

    if (!layout.butterfly()) {
      Map<String, Integer> rgs = bamFile.getReadGroupStartRows(drawStack);
      if (rgs.size() > 1) drawReadGroupSeparators(rgs, layout.readsY(), layout.readHeight(),
                                                    layout.gap(), sampleY, sampleH);
    }
  }

  /** Draws dashed separator lines and labels between consecutive read groups. */
  private void drawReadGroupSeparators(Map<String, Integer> rgStartRows,
                                        double readsY, double readHeight, double gap,
                                        double sampleY, double sampleH) {
    boolean first = true;
    for (Map.Entry<String, Integer> entry : rgStartRows.entrySet()) {
      if (first) { first = false; continue; }
      double sepY = readsY + entry.getValue() * (readHeight + gap) - gap - 1;
      if (sepY > readsY && sepY < sampleY + sampleH) {
        gc.setStroke(Color.rgb(100, 160, 200, 0.6));
        gc.setLineDashes(6, 3);
        gc.strokeLine(0, sepY, getWidth(), sepY);
        gc.setLineDashes();
        String label = entry.getKey().equals("__none__") ? "(no RG)" : entry.getKey();
        gc.setFill(Color.rgb(150, 200, 240, 0.9));
        gc.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 9));
        gc.fillText(label, 4, sepY + 10);
      }
    }
    Map.Entry<String, Integer> firstEntry = rgStartRows.entrySet().iterator().next();
    String firstLabel = firstEntry.getKey().equals("__none__") ? "(no RG)" : firstEntry.getKey();
    gc.setFill(Color.rgb(150, 200, 240, 0.9));
    gc.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 9));
    gc.fillText(firstLabel, 4, readsY + firstEntry.getValue() * (readHeight + gap) + 10);
  }

  // ── Hover hit-test and highlight ──────────────────────────────────────────────

  private BAMRecord findReadAt(double mx, double my) {
    if (sampleRegistry.getSampleTracks().isEmpty()) return null;
    if (drawStack.viewLength > Settings.get().getMaxReadViewLength()) return null;

    double masterOffset      = sampleRegistry.getMasterTrackHeight();
    double sampleH           = sampleRegistry.getSampleHeight();
    double coverageFractionH = Math.max(MIN_COVERAGE_HEIGHT,
        Math.min(MAX_COVERAGE_HEIGHT, sampleH * Settings.get().getCoverageFraction()));

    for (int i = sampleRegistry.getFirstVisibleSample();
         i <= sampleRegistry.getLastVisibleSample() && i < sampleRegistry.getSampleTracks().size(); i++) {
      SampleTrack track = sampleRegistry.getSampleTracks().get(i);
      if (!track.isVisible()) continue;
      double sampleY = masterOffset + i * sampleH - sampleRegistry.getScrollBarPosition();

      for (Sample sample : track.getSamples()) {
        if (!sample.visible || sample.getDataType() != Sample.DataType.BAM) continue;
        AlignmentFile bamFile = sample.getBamFile();
        if (bamFile == null) continue;
        List<BAMRecord> reads = bamFile.getCachedReads(drawStack);
        if (reads == null || reads.isEmpty()) continue;

        ReadLayout layout = ReadLayout.compute(sampleY, sampleH, coverageFractionH, bamFile, sample, drawStack);

        BAMRecord hit = drawReads.findAt(reads, mx, my, layout.readsY(), layout.readsH(),
            layout.readHeight(), layout.gap(), layout.butterfly(), layout.hp2Start(), layout.scrollOffset());
        if (hit != null) return hit;
      }
    }
    return null;
  }

  private void drawReadHighlight() {
    clearReactive();
    if (hoveredRead == null) return;
    if (drawStack.viewLength > Settings.get().getMaxReadViewLength()) return;

    double masterOffset      = sampleRegistry.getMasterTrackHeight();
    double sampleH           = sampleRegistry.getSampleHeight();
    double coverageFractionH = Math.max(MIN_COVERAGE_HEIGHT,
        Math.min(MAX_COVERAGE_HEIGHT, sampleH * Settings.get().getCoverageFraction()));

    for (int i = sampleRegistry.getFirstVisibleSample();
         i <= sampleRegistry.getLastVisibleSample() && i < sampleRegistry.getSampleTracks().size(); i++) {
      SampleTrack track = sampleRegistry.getSampleTracks().get(i);
      if (!track.isVisible()) continue;
      double sampleY = masterOffset + i * sampleH - sampleRegistry.getScrollBarPosition();

      for (Sample sample : track.getSamples()) {
        if (!sample.visible || sample.getDataType() != Sample.DataType.BAM) continue;
        AlignmentFile bamFile = sample.getBamFile();
        if (bamFile == null) continue;
        List<BAMRecord> reads = bamFile.getCachedReads(drawStack);
        if (reads == null || !reads.contains(hoveredRead)) continue;

        ReadLayout layout = ReadLayout.compute(sampleY, sampleH, coverageFractionH, bamFile, sample, drawStack);

        drawReads.drawHighlight(reactiveGc, hoveredRead, layout.readsY(), layout.readsH(),
            layout.readHeight(), layout.gap(), layout.butterfly(), layout.hp2Start(), layout.scrollOffset());
        return;
      }
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  @FunctionalInterface
  private interface SampleConsumer { void accept(double sampleY, Sample sample); }

  private void forEachVisibleSample(double masterOffset, double sampleH, SampleConsumer consumer) {
    for (int i = sampleRegistry.getFirstVisibleSample();
         i <= sampleRegistry.getLastVisibleSample() && i < sampleRegistry.getSampleTracks().size(); i++) {
      SampleTrack track = sampleRegistry.getSampleTracks().get(i);
      if (!track.isVisible()) continue;
      double sampleY = masterOffset + i * sampleH - sampleRegistry.getScrollBarPosition();
      for (Sample sample : track.getSamples()) {
        if (sample.visible) consumer.accept(sampleY, sample);
      }
    }
  }

  /**
   * Resolves the chromosome name of a read's mate from the BAM reference list.
   * Returns the name without "chr" prefix, or {@code null}.
   */
  private String resolveMateChromName(BAMRecord read) {
    if (read.mateRefID < 0) return null;
    if (read.mateRefID == read.refID) return drawStack.chromosome;
    for (SampleTrack track : sampleRegistry.getSampleTracks()) {
      for (Sample sample : track.getSamples()) {
        if (sample.getBamFile() == null) continue;
        String[] refNames = sample.getBamFile().getReader().getRefNames();
        if (read.mateRefID < refNames.length) {
          String name = refNames[read.mateRefID];
          if (name.startsWith("chr")) name = name.substring(3);
          return name;
        }
      }
    }
    return null;
  }
}
