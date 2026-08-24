package org.baseplayer.samples.alignment.draw;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.genome.gene.Transcript;
import org.baseplayer.io.Settings;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.samples.alignment.AlignmentFile;
import org.baseplayer.samples.alignment.BAMRecord;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.AminoAcids;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.DrawColors;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.draw.VariantDrawer;

import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.FontWeight;
import javafx.stage.Window;

public class AlignmentCanvas extends GenomicCanvas {

  public Image snapshot;
  private final GraphicsContext gc;

  /** Unified coverage data computation and rendering. */
  private final CoverageDrawer coverageDrawer = new CoverageDrawer();

  /** Unified read rendering, hit-testing, and reactive highlighting. */
  private final DrawReads drawReads;
  
  /** Variant drawing and index management. */
  private final VariantDrawer variantDrawer = new VariantDrawer();
  
  /** Variant list for the current genomic region. */
  private VariantList variantList;

  // ── Variant density cache (computed off the FX thread) ────────────────────
  private static final int   DENSITY_BINS   = 600;
  private volatile VariantList densityCached = null;
  private volatile int[]     densitySnv;
  private volatile int[]     densityIndel;
  private volatile int[]     densityDel;
  private volatile int[]     densityInv;
  private volatile int[]     densityDup;
  private volatile int[]     densityIns;
  private volatile int[]     densityTra;
  private volatile int[]     densityBnd;
  private volatile int       densityMax     = 1;
  private volatile boolean   densityBusy    = false;
  private volatile double    densityCachedStart = -1, densityCachedEnd = -1;
  private volatile int       densityGeneration  = 0;  // Incremented on each new compute; stale threads check and discard
  private volatile int       densityFilterGeneration = -1;  // Track filter changes
  private record SvSpan(long start, long end, VcfVariantType type, int sampleCount) {}
  private volatile java.util.List<SvSpan> densitySvSpans = java.util.List.of();

  private static final double MIN_COVERAGE_HEIGHT = 30;
  private static final double MAX_COVERAGE_HEIGHT = 60;

  /**
   * Pre-computed per-sample layout geometry used by rendering, hit-testing,
   * and hover highlighting.  Avoids the previous 3× duplication.
   */
  record ReadLayout(double readsY, double readsH, double readHeight,
                    double gap, int maxRow, int hp2Start, double scrollOffset,
                    double scrollOffsetTop, double scrollOffsetBottom,
                    boolean butterfly, boolean strandSplit, boolean discordantSplit, boolean methylation) {

    static ReadLayout compute(double sampleY, double sampleH, double coverageH,
                              AlignmentFile bamFile, Sample sample, DrawStack drawStack) {
      double readsY     = sampleY + coverageH;
      double readsH     = sampleH - coverageH;
      double gap        = Settings.get().getReadGap();
      int    maxRow     = bamFile.getMaxRow(drawStack) + 1;
      int    hp2Start   = bamFile.getHP2StartRow(drawStack);
      int    strandStart = bamFile.getStrandSplitStartRow(drawStack);
      int    discStart   = bamFile.getDiscordantSplitStartRow(drawStack);
      double scrollOff  = bamFile.getReadScrollOffset(drawStack);
      double scrollTop  = bamFile.getReadScrollOffsetTop(drawStack);
      double scrollBot  = bamFile.getReadScrollOffsetBottom(drawStack);
      boolean methyl    = sample.isMethylationData();

      // Strand split and haplotype split both use butterfly layout
      // Discordant split takes priority, then strand split, then haplotype
      boolean discordantSplit = discStart >= 0 && bamFile.isSplitByDiscordant();
      boolean strandSplit = strandStart >= 0 && bamFile.isSplitByStrand();
      boolean butterfly;
      int splitRow;
      if (discordantSplit) {
        butterfly = true;
        splitRow = discStart;
      } else if (strandSplit) {
        butterfly = true;
        splitRow = strandStart;
      } else {
        butterfly = hp2Start >= 0 && sample.isHaplotypeData();
        splitRow = hp2Start;
      }

      double readHeight = Settings.get().getReadHeight();

      return new ReadLayout(readsY, readsH, readHeight, gap, maxRow,
                            splitRow, scrollOff, scrollTop, scrollBot,
                            butterfly, strandSplit, discordantSplit, methyl);
    }
  }

  private BAMRecord hoveredRead  = null;
  private BAMRecord selectedRead = null;  // currently selected read (kept after popup closes)
  // Read-name highlight propagated from another stack when a cross-stack connector is active.
  private String externalLinkedReadName = null;
  private AlignmentCanvas externalLinkedOwner = null;
  private boolean drawingReadHighlight = false;
  // Last target stack this canvas linked to (used to clear propagated highlight when link ends).
  private AlignmentCanvas lastCrossStackTargetCanvas = null;
  private double lastMouseX = -1, lastMouseY = -1;
  private final ReadScrollbarComponent readScrollbarComponent = new ReadScrollbarComponent();
  private final ReadInfoPopup readInfoPopup = new ReadInfoPopup();
  private final DrawStackManager drawStackManager = ServiceRegistry.getInstance().getDrawStackManager();

  private record CoverageHoverInfo(CoverageDrawer.SampleRow row,
                                   double sampleY,
                                   double coverageHeight,
                                   double coverageBottom,
                                   int genomicPos,
                                   int pixelColumn,
                                   double coverage,
                                   double mmA,
                                   double mmC,
                                   double mmG,
                                   double mmT,
                                   double signalAvg,
                                   double signalMin,
                                   double signalMax,
                                   int signalCount,
                                   java.util.Map<Character, Double> modTypeCounts,
                                   java.util.Map<Character, Double> modTypeAvgProbs) {}

  private record AltCount(char altBase, double count) {}

  public AlignmentCanvas(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack) {
    super(reactiveCanvas, parent, drawStack);
    widthProperty().addListener((obs, o, n) -> setStartEnd(drawStack.start, drawStack.end));
    gc = getGraphicsContext2D();
    gc.setLineWidth(1);
    drawReads = new DrawReads(gc, drawStack);
    setupReadMouseHandlers(reactiveCanvas);
    Platform.runLater(this::draw);
  }

  // ── Mouse handlers ────────────────────────────────────────────────────────────

  private void setupReadMouseHandlers(Canvas reactiveCanvas) {
    reactiveCanvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
      if (event.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
      if (!readScrollbarComponent.handlePress(event.getX(), event.getY())) return;

      hoveredRead = null;
      update.set(!update.get());
      reactiveCanvas.setCursor(Cursor.V_RESIZE);
      event.consume();
    });

    reactiveCanvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, event -> {
      if (!readScrollbarComponent.handleDrag(event.getY())) return;
      hoveredRead = null;
      drawStack.nav.scrollbarDragging = true; // Signal to skip expensive recomputation
      update.set(!update.get());
      reactiveCanvas.setCursor(Cursor.V_RESIZE);
      event.consume();
    });

    reactiveCanvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, event -> {
      if (!readScrollbarComponent.handleRelease(event.getX(), event.getY())) return;
      drawStack.nav.scrollbarDragging = false; // Re-enable full rendering
      reactiveCanvas.setCursor(readScrollbarComponent.cursorFor(event.getX(), event.getY()));
      update.set(!update.get());
      event.consume();
    });

    reactiveCanvas.setOnMouseMoved(event -> {
      if (isDragging() || drawStack.nav.animationRunning) return;
      lastMouseX = event.getX();
      lastMouseY = event.getY();

      boolean scrollbarHoverChanged = readScrollbarComponent.handleMove(lastMouseX, lastMouseY);
      if (readScrollbarComponent.isOver(lastMouseX, lastMouseY)) {
        reactiveCanvas.setCursor(readScrollbarComponent.cursorFor(lastMouseX, lastMouseY));
        hoveredRead = null;
        drawReadHighlight();
        if (scrollbarHoverChanged) {
          update.set(!update.get());
        }
        return;
      }

      if (scrollbarHoverChanged) {
        update.set(!update.get());
      }

      // Update sidebar highlighting based on which sample track is under the cursor
      double masterOffset = sampleRegistry.getMasterTrackHeight();
      double sampleH = sampleRegistry.getSampleHeight();
      if (sampleH > 0 && lastMouseY > masterOffset) {
        int sampleIdx = (int)((lastMouseY - masterOffset + sampleRegistry.getScrollBarPosition()) / sampleH);
        if (sampleIdx >= 0 && sampleIdx < sampleRegistry.getSampleTracks().size()) {
          sampleRegistry.hoverSampleProperty().set(sampleIdx);
        }
      }

      BAMRecord hit = findReadAt(lastMouseX, lastMouseY);
      boolean hoveringSelected = selectedRead != null && hit == selectedRead;
      if (hit != hoveredRead) {
        hoveredRead = hit;
        reactiveCanvas.setCursor(hit != null ? Cursor.HAND : Cursor.DEFAULT);
        drawReadHighlight();
      } else if (hoveringSelected || (hit != null && drawStack.pixelSize >= 6)) {
        // refresh tooltip as cursor moves along the read
        drawReadHighlight();
      } else if (hit == null) {
        // Refresh coverage hover overlay/tooltip even when no read is under cursor.
        drawReadHighlight();
      }
    });

    reactiveCanvas.setOnMouseClicked(event -> {
      if (readScrollbarComponent.isOver(event.getX(), event.getY())) {
        return;
      }
      if (event.getClickCount() == 1 && event.isStillSincePress()) {
        BAMRecord hit = findReadAt(event.getX(), event.getY());
        if (hit != null) {
          Window owner = reactiveCanvas.getScene() != null ? reactiveCanvas.getScene().getWindow() : null;
          if (owner != null) {
            selectedRead = hit;
            String chrom = drawStack.chromosome != null ? drawStack.chromosome : "";
            String mateChrName = resolveMateChromName(hit);

            double popupX = event.getScreenX() + 30;
            double popupY = event.getScreenY() + 10;

            readInfoPopup.show(hit, chrom, mateChrName, owner,
                popupX, popupY,
                (mateChr, matePos) -> Platform.runLater(() -> MainController.addStackAtPosition(mateChr, matePos)));
            draw();
            drawReadHighlight();
          }
        } else {
          selectedRead = null;
          readInfoPopup.hide();
          draw();
        }
      }
    });

    // Keep lastMouseX/Y current during panning drags so draw() can re-evaluate
    // hover and update the per-base tooltip as the view scrolls. Use addEventHandler
    // (not setOnMouseDragged) to avoid replacing the parent GenomicCanvas drag handler.
    reactiveCanvas.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, event -> {
      lastMouseX = event.getX();
      lastMouseY = event.getY();
    });

    reactiveCanvas.setOnMouseExited(event -> {
      lastMouseX = -1;
      lastMouseY = -1;
      if (readScrollbarComponent.clearHover()) {
        update.set(!update.get());
      }
      if (hoveredRead != null) {
        hoveredRead = null;
        reactiveCanvas.setCursor(Cursor.DEFAULT);
        drawReadHighlight(); // keeps selectedRead highlighted if popup is still open
      }
      // Clear sidebar highlighting when mouse leaves the sample tracks
      sampleRegistry.hoverSampleProperty().set(-1);
    });
  }

  // ── Main draw ─────────────────────────────────────────────────────────────────

  @Override
  public void draw() {
    gc.setFill(DrawColors.BACKGROUND);
    gc.fillRect(0, 0, getWidth() + 1, getHeight() + 1);

    double masterOffset = sampleRegistry.getMasterTrackHeight();
    double available    = getHeight() - masterOffset;
    if (!sampleRegistry.isSampleHeightLocked()) {
      int visibleCount = sampleRegistry.getVisibleSampleCount();
      double rawHeight = available / Math.max(1, visibleCount);
      // Only auto-resize when height is 0 (initial load).
      // Once set, preserve user's manual adjustments (squeezed samples, etc.)
      if (sampleRegistry.getSampleHeight() == 0) {
        if (rawHeight < 20) {
          // Window is too large; shrink it to what fits at the minimum height
          int tracksFit = Math.max(1, (int) (available / 20));
          int firstVis = sampleRegistry.getFirstVisibleSample();
          int totalTracks = sampleRegistry.getDisplayedTrackCount();
          sampleRegistry.setLastVisibleSample(Math.min(firstVis + tracksFit - 1, totalTracks - 1));
          sampleRegistry.setSampleHeight(20);
        } else {
          sampleRegistry.setSampleHeight(rawHeight);
        }
      }
      // else: height already set, don't auto-resize (preserves manual squeeze)
    }
    sampleRegistry.clampScrollBarPositionInPlace(available);

    drawVariantDensityOverview(masterOffset);
    drawBamReads();
    drawSampleTrackDividers(masterOffset);
    super.draw();

    // Re-evaluate hover at last known mouse position (reads may have shifted due to scroll)
    if (lastMouseX >= 0) {
      if (isScrollbarOverrideActive()) {
        if (hoveredRead != null) {
          hoveredRead = null;
        }
      } else {
        BAMRecord hit = findReadAt(lastMouseX, lastMouseY);
        if (hit != hoveredRead) {
          hoveredRead = hit;
        }
      }
    }
    // Keep read highlight synced during panning/scrolling. Only avoid touching
    // the reactive canvas while GenomicCanvas is using it for zoom visuals.
    if (!isReactiveOverlayReserved()) {
      clearReactive();
      if (isScrollbarOverrideActive()
          || hoveredRead != null || selectedRead != null || externalLinkedReadName != null
          || hasCoverageHoverTarget(lastMouseX, lastMouseY)) {
        drawReadHighlight();
      }
    }
  }

  // ── Variant density overview (drawn in masterOffset area) ─────────────────

  private void drawVariantDensityOverview(double masterOffset) {
    if (masterOffset < 14) return;
    if (!org.baseplayer.io.VcfManager.getInstance().hasLoadedVcf()) return;
    // Use the canvas's own variantList field — it is cleared to null on chromosome change
    // and set to the new list by setVariantList() after loading, so reference comparison works.
    VariantList variants = this.variantList;
    if (variants == null || variants.isEmpty()) {
      // Reset cache so we detect when data arrives into the (same-reference) list
      if (densityCached != null) {
        densityCached = null;
        densitySnv = null; densityIndel = null;
        densityDel = null; densityInv = null; densityDup = null;
        densityIns = null; densityTra = null; densityBnd = null;
        densityCachedStart = -1; densityCachedEnd = -1;  // Reset coordinates
        densityBusy = false;  // Ensure not stuck in busy state
      }
      return;
    }
    // When the variant list changes (e.g. chromosome switch), forcefully reset busy state
    // so the new chromosome's computation is not blocked by an in-flight old-thread.
    if (variants != densityCached) {
      densityBusy = false;
      densitySnv = null; densityIndel = null;
      densityDel = null; densityInv = null; densityDup = null;
      densityIns = null; densityTra = null; densityBnd = null;
      densitySvSpans = java.util.List.of();
      densityCachedStart = -1; densityCachedEnd = -1;
    }
    // Recompute if variant list changed, or if zoom level changed significantly (10% threshold),
    // or if the filter has changed
    org.baseplayer.io.VcfManager vcfMgr = org.baseplayer.io.VcfManager.getInstance();
    int currentFilterGen = vcfMgr.getFilterGeneration();
    boolean filterChanged = currentFilterGen != densityFilterGeneration;
    boolean viewChanged = densityCachedStart < 0 || densityCachedEnd < 0
                       || Math.abs(drawStack.start - densityCachedStart) > drawStack.viewLength * 0.1
                       || Math.abs(drawStack.end - densityCachedEnd) > drawStack.viewLength * 0.1;
    if ((variants != densityCached || viewChanged || filterChanged) && !densityBusy) {
      densityFilterGeneration = currentFilterGen;
      triggerVariantDensityCompute(variants);
    }

    double areaH = masterOffset - 4;
    double svH   = densitySvSpans.isEmpty() ? 0 : Math.min(10, areaH * 0.22);
    double densH = areaH - svH;

    // Draw density bars if any density array has data
    boolean hasDensityData = densitySnv != null || densityIndel != null || densityDel != null ||
                             densityInv != null || densityDup != null || densityIns != null ||
                             densityTra != null || densityBnd != null;
    if (hasDensityData) drawDensityBars(2, densH);
    if (svH >= 4) drawSvSpanBars(2 + densH, svH);

    // Separator between master track and sample tracks (matches methylation style)
    gc.setStroke(DrawColors.COVERAGE_SEPARATOR);
    gc.setLineWidth(1.0);
    gc.strokeLine(0, masterOffset, getWidth(), masterOffset);
  }

  private void triggerVariantDensityCompute(VariantList variants) {
    densityCached = variants;
    densityBusy   = true;
    final int myGeneration = ++densityGeneration;  // Capture generation; stale threads will discard
    if (variants == null) {
      densityDel = null; densitySvSpans = java.util.List.of();
      densityBusy = false;
      return;
    }
    final double viewStart = drawStack.start;
    final double viewEnd   = drawStack.end;
    final java.util.List<Integer> visibleTrackIndices = sampleRegistry.getDisplayedTrackIndices();
    final org.baseplayer.variant.VariantFilter activeFilter = org.baseplayer.io.VcfManager.getInstance().getCurrentFilter();
    Thread t = new Thread(() -> {
      try {
        int[] snv = new int[DENSITY_BINS];
        int[] indel = new int[DENSITY_BINS];
        int[] del = new int[DENSITY_BINS];
        int[] inv = new int[DENSITY_BINS];
        int[] dup = new int[DENSITY_BINS];
        int[] ins = new int[DENSITY_BINS];
        int[] tra = new int[DENSITY_BINS];
        int[] bnd = new int[DENSITY_BINS];
        java.util.List<SvSpan> spans = new java.util.ArrayList<>();
        double viewLen = Math.max(1, viewEnd - viewStart);
        VariantNode node = variants.getFirst();
        while (node != null) {
          // Count only visible samples that pass the active variant filter.
          int sampleCount = 0;
          for (int idx : visibleTrackIndices) {
            if (node.hasSample(idx) && activeFilter.passes(node, idx)) {
              sampleCount++;
            }
          }
          
          if (sampleCount > 0) {
            // Structural variants with spans
            boolean isSvWithSpan = node.svEnd > node.position && 
                                   (node.type == VcfVariantType.SV_DELETION ||
                                    node.type == VcfVariantType.SV_INSERTION ||
                                    node.type == VcfVariantType.SV_DUPLICATION ||
                                    node.type == VcfVariantType.SV_INVERSION ||
                                    node.type == VcfVariantType.SV_TRANSLOCATION ||
                                    node.type == VcfVariantType.SV_BREAKEND);
            
            if (isSvWithSpan && node.svEnd >= viewStart && node.position <= viewEnd) {
              // SV spans: paint all bins covered by the span
              long s = Math.max((long)viewStart, node.position);
              long e = Math.min((long)viewEnd,   node.svEnd);
              int b0 = (int) Math.max(0, Math.min(DENSITY_BINS - 1, (s - viewStart) * DENSITY_BINS / viewLen));
              int b1 = (int) Math.max(0, Math.min(DENSITY_BINS - 1, (e - viewStart) * DENSITY_BINS / viewLen));
              
              int[] targetArray = switch (node.type) {
                case SV_DELETION -> del;
                case SV_INVERSION -> inv;
                case SV_DUPLICATION -> dup;
                case SV_INSERTION -> ins;
                case SV_TRANSLOCATION -> tra;
                case SV_BREAKEND -> bnd;
                default -> del;
              };
              
              for (int b = b0; b <= b1; b++) targetArray[b] += sampleCount;
              spans.add(new SvSpan(node.position, node.svEnd, node.type, sampleCount));
            } else if (node.position >= viewStart && node.position <= viewEnd) {
              // Point variants: increment single bin
              int bin = (int) Math.max(0, Math.min(DENSITY_BINS - 1, (node.position - viewStart) * DENSITY_BINS / viewLen));
              
              switch (node.type) {
                case SNV -> snv[bin] += sampleCount;
                case INSERTION, DELETION, MNV -> indel[bin] += sampleCount;
                default -> {} // Other types already handled as SVs
              }
            }
          }
          node = node.next;
        }
        // Scale to highest peak across all types
        int maxC = 1;
        for (int i = 0; i < DENSITY_BINS; i++) {
          maxC = Math.max(maxC, snv[i]);
          maxC = Math.max(maxC, indel[i]);
          maxC = Math.max(maxC, del[i]);
          maxC = Math.max(maxC, inv[i]);
          maxC = Math.max(maxC, dup[i]);
          maxC = Math.max(maxC, ins[i]);
          maxC = Math.max(maxC, tra[i]);
          maxC = Math.max(maxC, bnd[i]);
        }
        final int[] fSnv = snv, fIndel = indel, fDel = del;
        final int[] fInv = inv, fDup = dup, fIns = ins, fTra = tra, fBnd = bnd;
        final int fMax = maxC;
        final java.util.List<SvSpan> fSpans = spans;
        Platform.runLater(() -> {
          if (myGeneration != densityGeneration) return;  // Stale — newer compute already started
          densitySnv = fSnv; densityIndel = fIndel;
          densityDel = fDel; densityInv = fInv; densityDup = fDup;
          densityIns = fIns; densityTra = fTra; densityBnd = fBnd;
          densityMax = fMax;
          densitySvSpans = fSpans; densityBusy = false;
          densityCachedStart = viewStart; densityCachedEnd = viewEnd;
          draw();
        });
      } catch (Exception e) {
        System.err.println("Density computation failed: " + e.getMessage());
        e.printStackTrace();
        densityBusy = false;
      }
    }, "density-compute");
    t.setDaemon(true);
    t.start();
  }

  /**
   * Force immediate density calculation for the current variant list.
   * Called by VcfManager after variants are loaded to ensure density
   * appears immediately without requiring a zoom/pan event.
   */
  public void forceCalculateDensity() {
    VariantList variants = this.variantList;
    if (variants != null && !variants.isEmpty()) {
      // Reset cache to force recomputation
      densityCached = null;
      densityDel = null;
      densitySvSpans = java.util.List.of();
      densityCachedStart = -1;
      densityCachedEnd = -1;
      densityBusy = false;
      // Trigger compute immediately
      triggerVariantDensityCompute(variants);
    }
  }

  private void drawDensityBars(double top, double h) {
    if (drawStack.viewLength <= 0) return;
    if (densityDel == null && densitySnv == null && densityIndel == null) return;
    
    double canvasWidth = getWidth();
    double maxBarH = h - 1;
    int maxC = Math.max(1, densityMax);
    
    // Calculate coordinate transform: density was computed for densityCached range,
    // but we're drawing for current view. Shift density bars to match current view.
    double cachedViewLength = densityCachedEnd - densityCachedStart;
    double pixelOffset = 0;
    if (cachedViewLength > 0) {
      // How many genomic bases did we shift?
      double genomicShift = drawStack.start - densityCachedStart;
      // Convert to pixel shift
      pixelOffset = -genomicShift * canvasWidth / drawStack.viewLength;
    }
    
    // Draw each variant type as overlaid bars
    for (int px = 0; px < (int) canvasWidth; px++) {
      // Apply coordinate transform: shift back to cached view coordinates for bin lookup
      double cachedPx = px - pixelOffset;
      
      // Map transformed pixel to bin(s)
      int b0 = (int)(cachedPx       * DENSITY_BINS / canvasWidth);
      int b1 = (int)((cachedPx + 1) * DENSITY_BINS / canvasWidth);
      
      // Skip if bins are out of range (density not computed for this view yet)
      if (b0 < 0 || b1 >= DENSITY_BINS) continue;
      b0 = Math.max(0, Math.min(DENSITY_BINS - 1, b0));
      b1 = Math.max(0, Math.min(DENSITY_BINS - 1, b1));
      
      // Get max value for each type in this pixel
      int snvVal = 0, indelVal = 0;
      int delVal = 0, invVal = 0, dupVal = 0, insVal = 0, traVal = 0, bndVal = 0;
      
      for (int b = b0; b <= b1; b++) {
        if (densitySnv != null) snvVal = Math.max(snvVal, densitySnv[b]);
        if (densityIndel != null) indelVal = Math.max(indelVal, densityIndel[b]);
        if (densityDel != null) delVal = Math.max(delVal, densityDel[b]);
        if (densityInv != null) invVal = Math.max(invVal, densityInv[b]);
        if (densityDup != null) dupVal = Math.max(dupVal, densityDup[b]);
        if (densityIns != null) insVal = Math.max(insVal, densityIns[b]);
        if (densityTra != null) traVal = Math.max(traVal, densityTra[b]);
        if (densityBnd != null) bndVal = Math.max(bndVal, densityBnd[b]);
      }
      
      // Create list of (value, color, alpha) tuples and sort by value (largest first)
      // This ensures smaller bars remain visible on top of larger ones
      record BarData(int value, String color, double alpha) {}
      java.util.List<BarData> bars = new java.util.ArrayList<>();
      if (snvVal > 0) bars.add(new BarData(snvVal, "#ff6666", 0.5));
      if (indelVal > 0) bars.add(new BarData(indelVal, "#ffaa44", 0.5));
      if (delVal > 0) bars.add(new BarData(delVal, "#00cc44", 0.6));
      if (invVal > 0) bars.add(new BarData(invVal, "#4488ff", 0.6));
      if (dupVal > 0) bars.add(new BarData(dupVal, "#c0c0d0", 0.6));
      if (insVal > 0) bars.add(new BarData(insVal, "#33cc66", 0.6));
      if (traVal > 0) bars.add(new BarData(traVal, "#ffdd00", 0.6));
      if (bndVal > 0) bars.add(new BarData(bndVal, "#dd9900", 0.6));
      
      // Sort largest to smallest so we draw big bars first, small bars on top
      bars.sort((a, b) -> Integer.compare(b.value, a.value));
      
      // Draw all bars overlaid from the bottom (not stacked)
      double bottom = top + h;
      for (BarData bar : bars) {
        double bh = maxBarH * (double) bar.value / maxC;
        gc.setFill(Color.web(bar.color, bar.alpha));
        gc.fillRect(px, bottom - bh, 1, bh);
      }
    }
    
    // Draw scale on the right edge using full canvas width
    drawDensityScale(canvasWidth - 38, top, h, maxC);
    
    // Legend showing all variant types
    gc.setFont(AppFonts.getFont("Segoe UI", 7));
    double legendX = 3;
    double legendY = top + 3;
    
    // SNV
    if (densitySnv != null) {
      gc.setFill(Color.web("#ff6666", 0.7));
      gc.fillRect(legendX, legendY, 5, 3);
      gc.setFill(Color.web("#444"));
      gc.fillText("SNV", legendX + 7, legendY + 5);
      legendX += 32;
    }
    // Indel
    if (densityIndel != null) {
      gc.setFill(Color.web("#ffaa44", 0.7));
      gc.fillRect(legendX, legendY, 5, 3);
      gc.setFill(Color.web("#444"));
      gc.fillText("indel", legendX + 7, legendY + 5);
      legendX += 36;
    }
    // SV types
    if (densityDel != null) {
      gc.setFill(Color.web("#00cc44", 0.7));
      gc.fillRect(legendX, legendY, 5, 3);
      gc.setFill(Color.web("#444"));
      gc.fillText("DEL", legendX + 7, legendY + 5);
    }
  }
  
  private void drawDensityScale(double x, double top, double h, int maxCount) {
    gc.setFont(AppFonts.getFont("Segoe UI", 7));
    gc.setFill(Color.web("#555"));
    gc.setTextBaseline(javafx.geometry.VPos.CENTER);
    
    // Draw max value at top
    gc.fillText(String.valueOf(maxCount), x + 2, top + 4);
    
    // Draw 0 at bottom
    gc.fillText("0", x + 2, top + h - 2);
    
    // Draw tick marks
    gc.setStroke(Color.web("#777"));
    gc.setLineWidth(0.5);
    for (int i = 0; i <= 4; i++) {
      double y = top + (i * h / 4.0);
      gc.strokeLine(x, y, x + 3, y);
    }
  }

  private void drawSvSpanBars(double top, double h) {
    java.util.List<SvSpan> spans = densitySvSpans;
    double viewStart = drawStack.start, viewLen = drawStack.viewLength;
    if (viewLen <= 0 || spans.isEmpty()) return;
    double barY = top + 1, barH = Math.max(2, h - 3), w = getWidth();
    for (SvSpan span : spans) {
      if (span.end() < viewStart || span.start() > viewStart + viewLen) continue;
      double x1 = Math.max(0, (span.start() - viewStart) / viewLen * w);
      double x2 = Math.min(w, (span.end()   - viewStart) / viewLen * w);
      if (x2 - x1 < 1.5) x2 = x1 + 1.5;
      // More transparent colors (reduced from 0.28-0.88 to 0.20-0.70)
      double alpha = Math.min(0.70, 0.20 + span.sampleCount() * 0.12);
      
      // User-requested colors:
      // DEL: green, INV: blue, DUP: grayish white, TRA: yellow
      if (span.type() == VcfVariantType.SV_TRANSLOCATION || span.type() == VcfVariantType.SV_BREAKEND) {
        // Translocations and breakends as yellow lines
        gc.setStroke(Color.web("#ffdd00", alpha));
        gc.setLineWidth(2.0);
        gc.strokeLine(x1, barY + barH / 2, x2, barY + barH / 2);
      } else {
        Color c = switch (span.type()) {
          case SV_DELETION    -> Color.web("#00cc44", alpha);  // Green
          case SV_INVERSION   -> Color.web("#4488ff", alpha);  // Blue
          case SV_DUPLICATION -> Color.web("#c0c0d0", alpha);  // Grayish white
          case SV_INSERTION   -> Color.web("#33cc66", alpha);  // Light green
          default             -> Color.web("#aaaaaa", alpha);   // Gray
        };
        gc.setFill(c);
        gc.fillRect(x1, barY, x2 - x1, barH);
      }
    }
    
    // Add SV type legend
    gc.setFont(AppFonts.getFont("Segoe UI", 7));
    double legendX = getWidth() - 200;
    double legendY = top + h - 2;
    
    if (legendX > 100) {  // Only show if enough space
      // DEL - green
      gc.setFill(Color.web("#00cc44", 0.6));
      gc.fillRect(legendX, legendY - 5, 8, 4);
      gc.setFill(Color.web("#777"));
      gc.fillText("DEL", legendX + 10, legendY);
      
      // INV - blue
      gc.setFill(Color.web("#4488ff", 0.6));
      gc.fillRect(legendX + 35, legendY - 5, 8, 4);
      gc.setFill(Color.web("#777"));
      gc.fillText("INV", legendX + 45, legendY);
      
      // DUP - grayish
      gc.setFill(Color.web("#c0c0d0", 0.7));
      gc.fillRect(legendX + 70, legendY - 5, 8, 4);
      gc.setFill(Color.web("#777"));
      gc.fillText("DUP", legendX + 80, legendY);
      
      // INS - light green
      gc.setFill(Color.web("#33cc66", 0.6));
      gc.fillRect(legendX + 110, legendY - 5, 8, 4);
      gc.setFill(Color.web("#777"));
      gc.fillText("INS", legendX + 120, legendY);
      
      // TRA - yellow line
      gc.setStroke(Color.web("#ffdd00", 0.7));
      gc.setLineWidth(2.0);
      gc.strokeLine(legendX + 150, legendY - 3, legendX + 158, legendY - 3);
      gc.setFill(Color.web("#777"));
      gc.fillText("TRA", legendX + 160, legendY);
    }
  }

  /**
   * Draw horizontal divider lines between sample tracks.
   * Called from draw() so dividers are always present even after canvas clears.
   */
  private void drawSampleTrackDividers(double masterOffset) {
    double sampleH = sampleRegistry.getSampleHeight();
    double scrollPos = sampleRegistry.getScrollBarPosition();
    List<Integer> displayedIndices = sampleRegistry.getDisplayedTrackIndices();
    
    gc.setStroke(DrawColors.BORDER);
    gc.setLineWidth(1.0);
    
    for (int slot = 0; slot < displayedIndices.size(); slot++) {
      double sampleY = masterOffset + slot * sampleH - scrollPos;
      
      // Skip if scrolled above visible area
      if (sampleY + sampleH < masterOffset) continue;
      // Stop if scrolled below visible area
      if (sampleY > getHeight()) break;
      
      // Draw divider at top of this track (snap to integer Y for crisp rendering)
      if (sampleY >= masterOffset) {
        double snappedY = Math.round(sampleY);
        gc.strokeLine(0, snappedY, getWidth(), snappedY);
      }
    }
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
    readScrollbarComponent.beginFrame();

    if (sampleRegistry.getSampleList().isEmpty()) return;

    double masterOffset = sampleRegistry.getMasterTrackHeight();
    double sampleH      = sampleRegistry.getSampleHeight();
    String chrom        = drawStack.chromosome;
    int    start        = Math.max(0, (int) drawStack.start);
    int    end          = (int) drawStack.end;

    // ── Draw variants at all zoom levels (before zoom checks) ──
    if (variantList != null && !variantList.isEmpty()) {
      VariantFilter activeFilter = org.baseplayer.io.VcfManager.getInstance().getCurrentFilter();
      variantDrawer.draw(gc, variantList, drawStack, chromPosToScreenPos, getWidth(), masterOffset, activeFilter);
    }

    // ── Beyond coverage threshold: show zoom message or sampled coverage ──
    if (drawStack.viewLength > Settings.get().getMaxCoverageViewLength()) {
      if (Settings.get().isEnableSampledCoverage()) {
        forEachVisibleSample(masterOffset, sampleH, (sampleY, sample) ->
            coverageDrawer.drawSampled(gc, sample, chrom, start, end,
                sampleY, sampleH, getWidth(), chromPosToScreenPos, drawStack));
      } else {
        forEachVisibleSample(masterOffset, sampleH, (sampleY, sample) ->
            DrawReads.drawZoomMessage(gc, sampleY, sampleH, "Zoom in closer to view BAM/CRAM data"));
      }
      return;
    }

    // ── Normal zoom: per-base coverage + optional reads ──
    boolean coverageOnly = drawStack.viewLength > Settings.get().getMaxReadViewLength();
    double coverageFractionH = Math.max(MIN_COVERAGE_HEIGHT,
        Math.min(MAX_COVERAGE_HEIGHT, sampleH * Settings.get().getCoverageFraction()));
    boolean freezeDuringNavigation = drawStack.nav.navigating
        || drawStack.nav.animationRunning
        || drawStack.nav.lineZoomerActive;
    try {
      // Recompute coverage positions during genomic navigation, but skip during
      // scrollbar drag (vertical-only scroll) to avoid allocating large arrays on every mouse move.
      if (!drawStack.nav.scrollbarDragging) {
        coverageDrawer.compute(drawStack, chromPosToScreenPos, (int) getWidth());
      }
      coverageDrawer.render(gc, getWidth(), masterOffset, sampleH,
          sampleRegistry.getScrollBarPosition(), coverageOnly, coverageFractionH);

      if (!coverageOnly) {
        forEachVisibleSample(masterOffset, sampleH, (sampleY, sample) ->
            drawSampleReads(sample, chrom, start, end, sampleY, sampleH, coverageFractionH,
                !freezeDuringNavigation));
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
                                double sampleY, double sampleH, double coverageH,
                                boolean allowFetch) {
    if (sample.getDataType() != Sample.DataType.BAM) return;

    AlignmentFile bamFile = sample.getBamFile();
    if (bamFile == null) return;

    boolean isHoverStack      = (drawStack == ServiceRegistry.getInstance().getDrawStackManager().getHoverStack());
    boolean shouldShowLoading = bamFile.isLoading(drawStack) && (isHoverStack || !drawStack.nav.navigating);
    if (shouldShowLoading) drawReads.drawLoadingIndicator(sampleY, sampleH);

    String status = sample.getStatusMessage();
    if (status != null && !status.isEmpty()) {
      gc.setFill(Color.rgb(200, 200, 200, 0.9));
      gc.setFont(org.baseplayer.utils.AppFonts.getFont("System", javafx.scene.text.FontWeight.NORMAL, 11));
      gc.fillText(status, 10, sampleY + 15);
    }

    List<BAMRecord> reads = bamFile.getCachedReads(drawStack);
    if (reads == null || reads.isEmpty()) {
      if (!allowFetch) return;
      if (drawStack.nav.navigating && !isHoverStack) return;
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
      gc.setFont(org.baseplayer.utils.AppFonts.getFont("System", javafx.scene.text.FontWeight.BOLD, 9));
      if (layout.discordantSplit()) {
        gc.setFill(javafx.scene.paint.Color.web("#ee8888"));
        gc.fillText("Discordant/Split \u25B8", 4, middleY - 4);
        gc.setFill(javafx.scene.paint.Color.web("#88bbee"));
        gc.fillText("\u25C2 Concordant", 4, middleY + 11);
      } else if (layout.strandSplit()) {
        gc.setFill(DrawColors.READ_FORWARD);
        gc.fillText("Forward \u25B8", 4, middleY - 4);
        gc.setFill(DrawColors.READ_REVERSE);
        gc.fillText("\u25C2 Reverse", 4, middleY + 11);
      } else {
        gc.setFill(DrawColors.ALLELE_HP1_LABEL);
        gc.fillText("HP1", 4, middleY - 4);
        gc.setFill(DrawColors.ALLELE_HP2_LABEL);
        gc.fillText("HP2", 4, middleY + 11);
      }
    }

    Color fwdFill, revFill, fwdStroke, revStroke;
    ReadColorMode readColorMode = bamFile.getReadColorMode();
    if (sample.overlay) {
      fwdFill = DrawColors.OVERLAY_FORWARD;          revFill   = DrawColors.OVERLAY_REVERSE;
      fwdStroke = DrawColors.OVERLAY_FORWARD_STROKE; revStroke = DrawColors.OVERLAY_REVERSE_STROKE;
    } else {
      fwdFill = DrawColors.READ_FORWARD;             revFill   = DrawColors.READ_REVERSE;
      fwdStroke = DrawColors.READ_FORWARD_STROKE;    revStroke = DrawColors.READ_REVERSE_STROKE;
    }

    if (layout.butterfly()) {
      double middleY    = layout.readsY() + layout.readsH() / 2;
      double topClip    = sampleY + coverageH;
      double bottomClip = sampleY + sampleH;
      double w          = getWidth();
      int    hp2Start   = layout.hp2Start();

      // Pre-split reads into top/bottom halves in a single pass so each draw call
      // only iterates its own half (no double-work).
      List<BAMRecord> topReads    = new java.util.ArrayList<>(reads.size() / 2 + 1);
      List<BAMRecord> bottomReads = new java.util.ArrayList<>(reads.size() / 2 + 1);
      for (BAMRecord r : reads) {
        if (r.row < hp2Start) topReads.add(r);
        else                  bottomReads.add(r);
      }

      // Top half: clipTop/clipBottom bound it to [topClip, middleY].
      // DrawReads clamps each read's rect to these bounds (no gc.clip() — gc.clip is
      // very slow in JavaFX Canvas, especially combined with many fillRect calls).
      drawReads.draw(topReads, layout.readsY(), layout.readsH(), layout.readHeight(),
          layout.gap(), true, hp2Start, layout.scrollOffset(),
          layout.scrollOffsetTop(), layout.scrollOffsetBottom(),
          fwdFill, revFill, fwdStroke, revStroke, readColorMode, layout.methylation(), selectedRead,
          topClip, middleY, w);

      // Bottom half: clipped to [middleY, bottomClip].
      drawReads.draw(bottomReads, layout.readsY(), layout.readsH(), layout.readHeight(),
          layout.gap(), true, hp2Start, layout.scrollOffset(),
          layout.scrollOffsetTop(), layout.scrollOffsetBottom(),
          fwdFill, revFill, fwdStroke, revStroke, readColorMode, layout.methylation(), selectedRead,
          middleY, bottomClip, w);
    } else {
      drawReads.draw(reads, layout.readsY(), layout.readsH(), layout.readHeight(),
          layout.gap(), false, layout.hp2Start(), layout.scrollOffset(),
          layout.scrollOffsetTop(), layout.scrollOffsetBottom(),
          fwdFill, revFill, fwdStroke, revStroke, readColorMode, layout.methylation(), selectedRead,
          sampleY + coverageH, sampleY + sampleH, getWidth());
    }

    if (!layout.butterfly()) {
      Map<String, Integer> rgs = bamFile.getReadGroupStartRows(drawStack);
      if (rgs.size() > 1) drawReadGroupSeparators(rgs, layout.readsY(), layout.readHeight(),
                                                    layout.gap(), sampleY, sampleH);
    }

    drawReadScrollbars(layout, bamFile);
  }

  private void drawReadScrollbars(ReadLayout layout, AlignmentFile bamFile) {
    double rowPitch = Math.max(1.0, layout.readHeight() + layout.gap());
    double rightX = getWidth() - 2;

    if (!layout.butterfly()) {
      int rowCount = Math.max(0, layout.maxRow());
      double contentH = rowCount * rowPitch;
      drawReadScrollbar(bamFile, ReadScrollbarComponent.Section.NORMAL,
          rightX, layout.readsY(), layout.readsH(), contentH, layout.scrollOffset(), false);
      return;
    }

    int splitRow = Math.max(0, Math.min(layout.maxRow(), layout.hp2Start()));
    int topRows = splitRow;
    int bottomRows = Math.max(0, layout.maxRow() - splitRow);
    double halfH = layout.readsH() / 2.0;

    drawReadScrollbar(bamFile, ReadScrollbarComponent.Section.TOP,
        rightX, layout.readsY(), halfH, topRows * rowPitch, layout.scrollOffsetTop(), true);
    drawReadScrollbar(bamFile, ReadScrollbarComponent.Section.BOTTOM,
        rightX, layout.readsY() + halfH, halfH, bottomRows * rowPitch, layout.scrollOffsetBottom(), false);
  }

  private void drawReadScrollbar(AlignmentFile bamFile,
                                 ReadScrollbarComponent.Section section,
                                 double rightX,
                                 double y, double viewportH,
                                 double contentH, double scrollOffset, boolean inverted) {
    readScrollbarComponent.drawScrollbar(
        gc,
        bamFile,
        section,
        rightX,
        y,
        viewportH,
        contentH,
        scrollOffset,
        inverted,
        offset -> {
          switch (section) {
            case NORMAL -> bamFile.setReadScrollOffset(drawStack, offset);
            case TOP -> bamFile.setReadScrollOffsetTop(drawStack, offset);
            case BOTTOM -> bamFile.setReadScrollOffsetBottom(drawStack, offset);
          }
        });
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
        gc.setFont(org.baseplayer.utils.AppFonts.getFont("System", javafx.scene.text.FontWeight.BOLD, 9));
        gc.fillText(label, 4, sepY + 10);
      }
    }
    Map.Entry<String, Integer> firstEntry = rgStartRows.entrySet().iterator().next();
    String firstLabel = firstEntry.getKey().equals("__none__") ? "(no RG)" : firstEntry.getKey();
    gc.setFill(Color.rgb(150, 200, 240, 0.9));
    gc.setFont(org.baseplayer.utils.AppFonts.getFont("System", javafx.scene.text.FontWeight.BOLD, 9));
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

    List<Integer> displayedTrackIndices = sampleRegistry.getDisplayedTrackIndices();
    int firstVisibleSlot = Math.max(0, sampleRegistry.getFirstVisibleSample());
    int lastVisibleSlot = sampleRegistry.getLastVisibleSample();
    for (int slot = firstVisibleSlot;
       slot <= lastVisibleSlot && slot < displayedTrackIndices.size(); slot++) {
      int i = displayedTrackIndices.get(slot);
      SampleTrack track = sampleRegistry.getSampleTracks().get(i);
      if (!track.isVisible()) continue;
      double sampleY = masterOffset + slot * sampleH - sampleRegistry.getScrollBarPosition();

      // Skip this track entirely if the mouse is outside its vertical bounds.
      if (my < sampleY || my >= sampleY + sampleH) continue;

      for (Sample sample : track.getSamples()) {
        if (!sample.visible || sample.getDataType() != Sample.DataType.BAM) continue;
        AlignmentFile bamFile = sample.getBamFile();
        if (bamFile == null) continue;
        List<BAMRecord> reads = bamFile.getCachedReads(drawStack);
        if (reads == null || reads.isEmpty()) continue;

        ReadLayout layout = ReadLayout.compute(sampleY, sampleH, coverageFractionH, bamFile, sample, drawStack);

        BAMRecord hit = drawReads.findAt(reads, mx, my, layout.readsY(), layout.readsH(),
            layout.readHeight(), layout.gap(), layout.butterfly(), layout.hp2Start(), layout.scrollOffset(),
            layout.scrollOffsetTop(), layout.scrollOffsetBottom());
        if (hit != null) return hit;
      }
    }
    return null;
  }

  private void drawReadHighlight() {
    if (drawingReadHighlight) return;
    drawingReadHighlight = true;
    try {
    clearReactive();
    if (isScrollbarOverrideActive()) {
      clearCrossStackTargetHighlight();
      MainController.releaseCrossStackMateArc(this);
      return;
    }

    CoverageHoverInfo coverageHover = findCoverageHoverInfo(lastMouseX, lastMouseY);
    boolean hasExternalLinkedRead = externalLinkedReadName != null && externalLinkedOwner != null;

    if (drawStack.viewLength > Settings.get().getMaxReadViewLength()) {
      clearCrossStackTargetHighlight();
      MainController.releaseCrossStackMateArc(this);
      if (coverageHover != null) {
        drawCoverageHover(coverageHover, lastMouseX, lastMouseY);
      }
      return;
    }
    if (selectedRead == null && hoveredRead == null && !hasExternalLinkedRead && coverageHover == null) {
      clearCrossStackTargetHighlight();
      MainController.releaseCrossStackMateArc(this);
      return;
    }

    boolean selectedCrossStackLinkDrawn = false;

    double masterOffset      = sampleRegistry.getMasterTrackHeight();
    double sampleH           = sampleRegistry.getSampleHeight();
    double coverageFractionH = Math.max(MIN_COVERAGE_HEIGHT,
        Math.min(MAX_COVERAGE_HEIGHT, sampleH * Settings.get().getCoverageFraction()));

    List<Integer> displayedTrackIndices = sampleRegistry.getDisplayedTrackIndices();
    int firstVisibleSlot = Math.max(0, sampleRegistry.getFirstVisibleSample());
    int lastVisibleSlot = sampleRegistry.getLastVisibleSample();
    for (int slot = firstVisibleSlot;
       slot <= lastVisibleSlot && slot < displayedTrackIndices.size(); slot++) {
      int i = displayedTrackIndices.get(slot);
      SampleTrack track = sampleRegistry.getSampleTracks().get(i);
      if (!track.isVisible()) continue;
      double sampleY = masterOffset + slot * sampleH - sampleRegistry.getScrollBarPosition();

      for (Sample sample : track.getSamples()) {
        if (!sample.visible || sample.getDataType() != Sample.DataType.BAM) continue;
        AlignmentFile bamFile = sample.getBamFile();
        if (bamFile == null) continue;
        List<BAMRecord> reads = bamFile.getCachedReads(drawStack);
        if (reads == null || reads.isEmpty()) continue;

        ReadLayout layout = ReadLayout.compute(sampleY, sampleH, coverageFractionH, bamFile, sample, drawStack);
        double clipTop = sampleY + coverageFractionH;
        double clipBottom = sampleY + sampleH;
        double canvasW = getWidth();

        // ── Selected read: dashed mate link + split-read arcs ─────────────────
        if (selectedRead != null && reads.contains(selectedRead) && selectedRead.readName != null) {
          double selectedY = drawReads.calcReadScreenY(selectedRead, layout.readsY(), layout.readsH(),
              layout.readHeight(), layout.gap(), layout.butterfly(), layout.hp2Start(),
              layout.scrollOffset(), layout.scrollOffsetTop(), layout.scrollOffsetBottom());
          double selectedH = layout.readHeight() >= 3 ? layout.readHeight() - layout.gap() : layout.readHeight();
          double selectedAnchorX = drawReads.calcReadArcAnchorX(selectedRead);
          double selectedMidY    = selectedY + selectedH * 0.5;

          // First try cross-stack connector for the paired-end mate (uses matePos/mateRefID).
          // Prefer split-read cross-stack links (SA tag, blue) over matepair links (orange).
          if (!selectedCrossStackLinkDrawn && drawSplitConnectorToOtherSplit(selectedRead, selectedY, selectedH)) {
            selectedCrossStackLinkDrawn = true;
          }
          // Fall back to paired-end mate cross-stack connector.
          if (!selectedCrossStackLinkDrawn && drawMateConnectorToOtherSplit(selectedRead, selectedY, selectedH)) {
            selectedCrossStackLinkDrawn = true;
          }

          // On-screen arcs to every visible related read.
          // Blue if either endpoint is a supplementary (split-read connection).
          // Yellow if both ends are non-supplementary (paired-end mate).
          for (BAMRecord r : reads) {
            if (r == selectedRead || !selectedRead.readName.equals(r.readName)) continue;
            Color arcColor = (r.isSupplementary() || selectedRead.isSupplementary())
                ? Color.rgb(80, 160, 255, 0.95)
                : Color.rgb(255, 210, 80, 0.95);
            double rY = drawReads.calcReadScreenY(r, layout.readsY(), layout.readsH(),
                layout.readHeight(), layout.gap(), layout.butterfly(), layout.hp2Start(),
                layout.scrollOffset(), layout.scrollOffsetTop(), layout.scrollOffsetBottom());
            double rH = layout.readHeight() >= 3 ? layout.readHeight() - layout.gap() : layout.readHeight();
            drawDashedArc(selectedAnchorX, selectedMidY,
                drawReads.calcReadArcAnchorX(r), rY + rH * 0.5, arcColor);
          }

          // Intra-read arcs: between consecutive parts of the same read.
          // For spliced reads (CIGAR N ops): arcs over each intron gap.
          // For any read with an SA tag: arcs to each split-part position on the same
          // chromosome that lies outside the current viewport (on-screen parts are
          // covered by the readName loop above).
          Color splitArcColor = Color.rgb(80, 160, 255, 0.95);

          double[] exonMidXs = drawReads.getExonMidXs(selectedRead);
          if (exonMidXs.length > 1) {
            for (int ei = 0; ei < exonMidXs.length - 1; ei++) {
              drawDashedArc(exonMidXs[ei], selectedMidY, exonMidXs[ei + 1], selectedMidY, splitArcColor);
            }
          }

          // SA-tag arcs: off-screen split parts on the same chromosome.
          if (selectedRead.saTag != null && !selectedRead.saTag.isEmpty()) {
            String viewChrom = normalizeChrom(drawStack.chromosome != null ? drawStack.chromosome : "");
            for (String saEntry : selectedRead.saTag.split(";")) {
              if (saEntry.isEmpty()) continue;
              String[] f = saEntry.split(",");
              if (f.length < 2) continue;
              if (!normalizeChrom(f[0]).equals(viewChrom)) continue;
              int saPos;
              try { saPos = Integer.parseInt(f[1]); } catch (NumberFormatException e) { continue; }
              // Skip positions inside the current viewport — on-screen supplementary reads
              // are handled by the readName loop above with the correct row Y.
              if (saPos >= drawStack.start && saPos <= drawStack.end) continue;
              double saScreenX = (saPos - drawStack.start) * drawStack.pixelSize;
              saScreenX = Math.max(4, Math.min(getWidth() - 4, saScreenX));
              drawDashedArc(selectedAnchorX, selectedMidY, saScreenX, selectedMidY, splitArcColor);
            }
          }
        }

        // ── Hovered read: standard white outline + mate highlights ────────────
        if (hoveredRead != null && reads.contains(hoveredRead)) {
          drawReads.drawHighlight(reactiveGc, hoveredRead, layout.readsY(), layout.readsH(),
              layout.readHeight(), layout.gap(), layout.butterfly(), layout.hp2Start(),
              layout.scrollOffset(), layout.scrollOffsetTop(), layout.scrollOffsetBottom(),
              clipTop, clipBottom, canvasW);

          if (hoveredRead.readName != null) {
            for (BAMRecord related : reads) {
              if (related == hoveredRead) continue;
              if (!hoveredRead.readName.equals(related.readName)) continue;
              // Yellow outline for paired-end mate; blue for supplementary split parts
              Color outlineColor = related.isSupplementary()
                  ? Color.rgb(80, 160, 255, 0.95)
                  : Color.color(1.0, 0.85, 0.0);
              drawReads.drawHighlightColored(reactiveGc, related, layout.readsY(), layout.readsH(),
                  layout.readHeight(), layout.gap(), layout.butterfly(), layout.hp2Start(),
                  layout.scrollOffset(), layout.scrollOffsetTop(), layout.scrollOffsetBottom(),
                  clipTop, clipBottom, canvasW, outlineColor);
            }
          }

          // Per-base tooltip for whichever read is under the cursor
          if (lastMouseX >= 0 && drawStack.pixelSize >= 6) {
            // Find screen Y of this read for the base highlight
            double readY = drawReads.calcReadScreenY(hoveredRead, layout.readsY(), layout.readsH(),
                layout.readHeight(), layout.gap(), layout.butterfly(), layout.hp2Start(),
                layout.scrollOffset(), layout.scrollOffsetTop(), layout.scrollOffsetBottom());
            double readH = layout.readHeight() >= 3 ? layout.readHeight() - layout.gap() : layout.readHeight();
            drawBaseHighlight(hoveredRead, lastMouseX, readY, readH);
            drawBaseTooltip(hoveredRead, lastMouseX, lastMouseY, bamFile.getReadColorMode());
          }
        }

        // ── Cross-stack linked read propagated from another stack ─────────────
        if (hasExternalLinkedRead) {
          for (BAMRecord related : reads) {
            if (externalLinkedReadName.equals(related.readName)) {
              drawReads.drawHighlightColored(reactiveGc, related, layout.readsY(), layout.readsH(),
                  layout.readHeight(), layout.gap(), layout.butterfly(), layout.hp2Start(),
                  layout.scrollOffset(), layout.scrollOffsetTop(), layout.scrollOffsetBottom(),
                  clipTop, clipBottom, canvasW, Color.rgb(255, 190, 90, 0.95));
            }
          }
        }
      }
    }

    if (!selectedCrossStackLinkDrawn) {
      clearCrossStackTargetHighlight();
      MainController.releaseCrossStackMateArc(this);
    }

    if (coverageHover != null) {
      drawCoverageHover(coverageHover, lastMouseX, lastMouseY);
    }
    } finally {
      drawingReadHighlight = false;
    }
  }

  private boolean hasCoverageHoverTarget(double mx, double my) {
    if (isScrollbarOverrideActive()) return false;
    return findCoverageHoverInfo(mx, my) != null;
  }

  private CoverageHoverInfo findCoverageHoverInfo(double mx, double my) {
    if (isScrollbarOverrideActive()) return null;
    if (mx < 0 || my < 0) return null;
    if (!coverageDrawer.hasData()) return null;
    if (drawStack.viewLength > Settings.get().getMaxCoverageViewLength()) return null;

    int genomicPos = (int) Math.floor(drawStack.start + mx * drawStack.scale);
    int px = (int) Math.floor(chromPosToScreenPos.apply((double) genomicPos));
    if (px < 0 || px >= (int) Math.ceil(getWidth())) return null;

    double masterOffset = sampleRegistry.getMasterTrackHeight();
    double sampleH = sampleRegistry.getSampleHeight();
    boolean coverageOnly = drawStack.viewLength > Settings.get().getMaxReadViewLength();
    double coverageFractionH = Math.max(MIN_COVERAGE_HEIGHT,
        Math.min(MAX_COVERAGE_HEIGHT, sampleH * Settings.get().getCoverageFraction()));
    double covH = coverageOnly ? sampleH : coverageFractionH;

    CoverageHoverInfo best = null;
    double bestCov = -1;
     List<Integer> displayedTrackIndices = sampleRegistry.getDisplayedTrackIndices();
      int firstVisibleSlot = Math.max(0, sampleRegistry.getFirstVisibleSample());
      int lastVisibleSlot = sampleRegistry.getLastVisibleSample();
      for (int slot = firstVisibleSlot;
        slot <= lastVisibleSlot && slot < displayedTrackIndices.size(); slot++) {
      int i = displayedTrackIndices.get(slot);
      SampleTrack track = sampleRegistry.getSampleTracks().get(i);
      if (!track.isVisible()) continue;

      double sampleY = masterOffset + slot * sampleH - sampleRegistry.getScrollBarPosition();
      if (my < sampleY || my >= sampleY + covH) continue;

      for (Sample sample : track.getSamples()) {
        if (!sample.visible || sample.getDataType() != Sample.DataType.BAM) continue;
        CoverageDrawer.SampleRow row = coverageDrawer.getRow(sample);
        if (row == null || px >= row.coverage.length) continue;

        double cov = row.coverage[px];
        if (cov < 0.5) continue;
        double scale = row.maxCoverage > 0 ? (covH - 14) / row.maxCoverage : 0;
        if (scale <= 0) continue;
        double covBarH = Math.max(1.5, cov * scale);
        double covBottom = sampleY + covH - 1;
        double covBarTop = covBottom - covBarH;
        if (my < covBarTop || my > covBottom) continue;

        // Collect signal data if available
        double signalAvg = 0, signalMin = 0, signalMax = 0;
        int signalCount = row.signalCount != null ? row.signalCount[px] : 0;
        if (signalCount > 0) {
          signalAvg = row.signalSum[px] / signalCount;
          signalMin = row.signalMin[px];
          signalMax = row.signalMax[px];
        }

        // Collect modification data if available
        java.util.Map<Character, Double> modCounts = new java.util.HashMap<>();
        java.util.Map<Character, Double> modAvgProbs = new java.util.HashMap<>();
        if (row.modTypeCounts != null) {
          for (java.util.Map.Entry<Character, double[]> entry : row.modTypeCounts.entrySet()) {
            char modKey = entry.getKey();
            double count = entry.getValue()[px];
            if (count > 0) {
              modCounts.put(modKey, count);
              double[] probSums = row.modTypeProbSums.get(modKey);
              double avgProb = probSums[px] / count;
              modAvgProbs.put(modKey, avgProb);
            }
          }
        }

        CoverageHoverInfo info = new CoverageHoverInfo(
            row,
            sampleY,
            covH,
            covBottom,
            genomicPos,
            px,
            cov,
            row.mmA[px],
            row.mmC[px],
            row.mmG[px],
            row.mmT[px],
            signalAvg,
            signalMin,
            signalMax,
            signalCount,
            modCounts,
            modAvgProbs);
        if (best == null || cov > bestCov) {
          best = info;
          bestCov = cov;
        }
      }
    }
    return best;
  }

  private boolean isScrollbarOverrideActive() {
    return readScrollbarComponent.hasHoverOrDrag() || readScrollbarComponent.isOver(lastMouseX, lastMouseY);
  }

  private void drawCoverageHover(CoverageHoverInfo hover, double mx, double my) {
    if (hover == null) return;

    double scale = hover.row().maxCoverage > 0
        ? (hover.coverageHeight() - 14) / hover.row().maxCoverage
        : 0;
    double covBarH = Math.max(1.5, hover.coverage() * scale);
    double covBarTop = hover.coverageBottom() - covBarH;
    double bw = Math.max(1.0, drawStack.pixelSize);
    double bx = (hover.genomicPos() - drawStack.start) * drawStack.pixelSize;

    reactiveGc.setStroke(Color.WHITE);
    reactiveGc.setLineWidth(1.3);
    reactiveGc.strokeRect(bx + 0.5, covBarTop - 1, Math.max(1.0, bw) - 1, covBarH + 2);
    reactiveGc.setLineWidth(1.0);

    drawCoverageTooltip(hover, mx, my);
  }

  private void drawCoverageTooltip(CoverageHoverInfo hover, double mx, double my) {
    List<String> lines = new ArrayList<>();
    String chrom = drawStack.chromosome != null ? drawStack.chromosome : "";
    lines.add(chrom + ":" + hover.genomicPos() + " cov=" + (int) Math.round(hover.coverage()));

    // Determine active color mode and data type
    ReadColorMode colorMode = hover.row().sample.getBamFile() != null 
        ? hover.row().sample.getBamFile().getReadColorMode() 
        : ReadColorMode.STRAND;
    CoverageDataType dataType = colorMode.getDataType();

    char refBase = '?';
    ReferenceGenomeService refSvc = ServiceRegistry.getInstance().getReferenceGenomeService();
    if (refSvc.hasGenome()) {
      String ref = refSvc.getBases(drawStack.chromosome, hover.genomicPos(), hover.genomicPos());
      if (ref != null && ref.length() == 1) {
        refBase = Character.toUpperCase(ref.charAt(0));
        lines.add("ref=" + refBase);
      }
    }

    if ((dataType == CoverageDataType.SIGNAL || dataType == CoverageDataType.QUALITY) 
        && hover.signalCount() > 0) {
      // Display numeric data statistics (signal tags or quality)
      String modeLabel = colorMode.getLabel();
      lines.add(String.format(Locale.ROOT, "%s avg: %.1f", modeLabel, hover.signalAvg()));
      lines.add(String.format(Locale.ROOT, "min: %.1f, max: %.1f", hover.signalMin(), hover.signalMax()));
      lines.add(String.format(Locale.ROOT, "samples: %d", hover.signalCount()));
    } else if (dataType == CoverageDataType.MODIFICATION && !hover.modTypeCounts().isEmpty()) {
      // Display base modification statistics
      lines.add("Base modifications:");
      List<java.util.Map.Entry<Character, Double>> modEntries = new ArrayList<>(hover.modTypeCounts().entrySet());
      modEntries.sort(Comparator.comparingDouble(java.util.Map.Entry<Character, Double>::getValue).reversed());
      
      for (java.util.Map.Entry<Character, Double> entry : modEntries) {
        char modKey = entry.getKey();
        double count = entry.getValue();
        double avgProb = hover.modTypeAvgProbs().getOrDefault(modKey, 0.0);
        String modName = getModificationName(modKey);
        double af = hover.coverage() > 0 ? count / hover.coverage() : 0;
        lines.add(String.format(Locale.ROOT, "%s: %.0f (%.1f%%, p=%.2f)", 
            modName, count, af * 100.0, avgProb));
      }
    } else {
      // Display mismatches (default)
      double cov = Math.max(0.0, hover.coverage());
      List<AltCount> alts = new ArrayList<>(4);
      if (hover.mmA() > 0) alts.add(new AltCount('A', hover.mmA()));
      if (hover.mmC() > 0) alts.add(new AltCount('C', hover.mmC()));
      if (hover.mmG() > 0) alts.add(new AltCount('G', hover.mmG()));
      if (hover.mmT() > 0) alts.add(new AltCount('T', hover.mmT()));
      alts.sort(Comparator.comparingDouble(AltCount::count).reversed());

      if (alts.isEmpty()) {
        lines.add("No mismatches");
      } else {
        char dominantAlt = alts.get(0).altBase();
        for (AltCount alt : alts) {
          double af = cov > 0 ? alt.count() / cov : 0;
          String change = (refBase == '?')
              ? ("alt " + alt.altBase())
              : (refBase + ">" + alt.altBase());
          lines.add(String.format(Locale.ROOT, "%s %.0f (%.1f%%)",
              change, alt.count(), af * 100.0));
        }
        String aaChange = computeAaChangeAtPosition(hover.genomicPos(), dominantAlt);
        if (aaChange != null) {
          lines.add("AA: " + aaChange);
        }
      }
    }

    reactiveGc.setFont(AppFonts.getFont("System", FontWeight.BOLD, 11));
    double padX = 5;
    double padY = 3;
    double lineH = 14;
    int maxLen = 0;
    for (String line : lines) {
      maxLen = Math.max(maxLen, line.length());
    }
    double boxW = maxLen * 7.1 + 2 * padX;
    double boxH = lines.size() * lineH + 2 * padY;
    double bx = mx + 12;
    double by = my + 12;
    if (bx + boxW > getWidth()) bx = mx - boxW - 8;
    if (by + boxH > getHeight()) by = my - boxH - 8;

    reactiveGc.setFill(Color.rgb(20, 20, 20, 0.88));
    reactiveGc.fillRect(bx, by, boxW, boxH);
    reactiveGc.setStroke(Color.rgb(230, 230, 230, 0.95));
    reactiveGc.strokeRect(bx + 0.5, by + 0.5, boxW - 1, boxH - 1);

    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      reactiveGc.setFill(line.startsWith("AA:")
          ? Color.rgb(255, 220, 120)
          : Color.WHITE);
      reactiveGc.fillText(line, bx + padX, by + padY + (i + 1) * lineH - 3);
    }
  }

  private boolean setExternalMateHighlightFrom(AlignmentCanvas owner, String readName) {
    if (owner == null || readName == null || readName.isEmpty()) return false;
    boolean changed = owner != externalLinkedOwner || !readName.equals(externalLinkedReadName);
    externalLinkedOwner = owner;
    externalLinkedReadName = readName;
    return changed;
  }

  private boolean clearExternalMateHighlightFrom(AlignmentCanvas owner) {
    if (owner != null && owner == externalLinkedOwner) {
      externalLinkedOwner = null;
      externalLinkedReadName = null;
      return true;
    }
    return false;
  }

  private void clearCrossStackTargetHighlight() {
    if (lastCrossStackTargetCanvas != null) {
      boolean changed = lastCrossStackTargetCanvas.clearExternalMateHighlightFrom(this);
      lastCrossStackTargetCanvas = null;
      if (changed) {
        update.set(!update.get());
      }
    }
  }

  /** Returns target-stack anchor point (x,y in target canvas coordinates) for selected readName. */
  private Point2D findCrossStackReadAnchor(AlignmentCanvas targetCanvas, BAMRecord selected, int preferredPos) {
    if (selected.readName == null || selected.readName.isEmpty()) return null;

    double masterOffset = targetCanvas.sampleRegistry.getMasterTrackHeight();
    double sampleH = targetCanvas.sampleRegistry.getSampleHeight();
    double coverageFractionH = Math.max(MIN_COVERAGE_HEIGHT,
        Math.min(MAX_COVERAGE_HEIGHT, sampleH * Settings.get().getCoverageFraction()));

    BAMRecord best = null;
    ReadLayout bestLayout = null;
    double bestSampleY = 0;
    double bestDist = Double.MAX_VALUE;

    List<Integer> targetDisplayedTrackIndices = targetCanvas.sampleRegistry.getDisplayedTrackIndices();
     int firstVisibleSlot = Math.max(0, targetCanvas.sampleRegistry.getFirstVisibleSample());
     int lastVisibleSlot = targetCanvas.sampleRegistry.getLastVisibleSample();
     for (int slot = firstVisibleSlot;
       slot <= lastVisibleSlot
         && slot < targetDisplayedTrackIndices.size(); slot++) {
      int i = targetDisplayedTrackIndices.get(slot);
      SampleTrack track = targetCanvas.sampleRegistry.getSampleTracks().get(i);
      if (!track.isVisible()) continue;
      double sampleY = masterOffset + slot * sampleH - targetCanvas.sampleRegistry.getScrollBarPosition();

      for (Sample sample : track.getSamples()) {
        if (!sample.visible || sample.getDataType() != Sample.DataType.BAM) continue;
        AlignmentFile bamFile = sample.getBamFile();
        if (bamFile == null) continue;
        List<BAMRecord> reads = bamFile.getCachedReads(targetCanvas.drawStack);
        if (reads == null || reads.isEmpty()) continue;

        ReadLayout layout = ReadLayout.compute(sampleY, sampleH, coverageFractionH,
            bamFile, sample, targetCanvas.drawStack);

        for (BAMRecord r : reads) {
          if (!selected.readName.equals(r.readName)) continue;
          double mid = (r.pos + r.end) * 0.5;
          double dist = Math.abs(mid - preferredPos);
          if (dist < bestDist) {
            bestDist = dist;
            best = r;
            bestLayout = layout;
            bestSampleY = sampleY;
          }
        }
      }
    }

    if (best == null || bestLayout == null) return null;

    double x = targetCanvas.drawReads.calcReadArcAnchorX(best);
    x = Math.max(2.0, Math.min(targetCanvas.getWidth() - 2.0, x));

    double y = targetCanvas.drawReads.calcReadScreenY(best, bestLayout.readsY(), bestLayout.readsH(),
        bestLayout.readHeight(), bestLayout.gap(), bestLayout.butterfly(), bestLayout.hp2Start(),
        bestLayout.scrollOffset(), bestLayout.scrollOffsetTop(), bestLayout.scrollOffsetBottom());
    double h = bestLayout.readHeight() >= 3 ? bestLayout.readHeight() - bestLayout.gap() : bestLayout.readHeight();
    double cy = y + h * 0.5;
    cy = Math.max(bestSampleY + coverageFractionH + 2.0, Math.min(bestSampleY + sampleH - 2.0, cy));

    return new Point2D(x, cy);
  }

  /**
   * Draw dashed blue connector from selected read to a supplementary split part
   * that is visible in another stack panel (resolved from SA tag).
   */
  private boolean drawSplitConnectorToOtherSplit(BAMRecord selected, double selectedY, double selectedH) {
    if (selected.readName == null || selected.readName.isEmpty()) return false;
    if (selected.saTag == null || selected.saTag.isEmpty()) return false;

    List<DrawStack> stacks = drawStackManager.getStacks();
    int thisIdx = stacks.indexOf(drawStack);
    if (thisIdx < 0) return false;

    DrawStack targetStack = null;
    int targetPos = -1;

    outer:
    for (String saEntry : selected.saTag.split(";")) {
      if (saEntry == null || saEntry.isEmpty()) continue;
      String[] f = saEntry.split(",");
      if (f.length < 2) continue;

      String saChrom = normalizeChrom(f[0]);
      int saPos;
      try {
        saPos = Integer.parseInt(f[1]);
      } catch (NumberFormatException e) {
        continue;
      }

      for (int i = 0; i < stacks.size(); i++) {
        if (i == thisIdx) continue;
        DrawStack ds = stacks.get(i);
        if (ds == null || ds.alignmentCanvas == null) continue;
        if (!normalizeChrom(ds.chromosome).equals(saChrom)) continue;
        if (saPos < ds.start || saPos > ds.end) continue;
        targetStack = ds;
        targetPos = saPos;
        break outer;
      }
    }

    if (targetStack == null || targetPos < 0 || targetStack.alignmentCanvas == null) return false;

    AlignmentCanvas targetCanvas = targetStack.alignmentCanvas;
    if (lastCrossStackTargetCanvas != null && lastCrossStackTargetCanvas != targetCanvas) {
      boolean cleared = lastCrossStackTargetCanvas.clearExternalMateHighlightFrom(this);
      if (cleared) update.set(!update.get());
    }
    boolean changed = targetCanvas.setExternalMateHighlightFrom(this, selected.readName);
    lastCrossStackTargetCanvas = targetCanvas;
    if (changed) update.set(!update.get());

    double x1 = drawReads.calcReadArcAnchorX(selected);
    double y1 = selectedY + selectedH * 0.5;

    Point2D targetAnchor = findCrossStackReadAnchor(targetCanvas, selected, targetPos);
    double targetX;
    double targetY;
    if (targetAnchor != null) {
      targetX = targetAnchor.getX();
      targetY = targetAnchor.getY();
    } else {
      targetX = (targetPos - targetStack.start) * targetStack.pixelSize;
      targetX = Math.max(2.0, Math.min(targetCanvas.getWidth() - 2.0, targetX));
      Point2D sourceScene = localToScene(x1, y1);
      if (sourceScene == null) return false;
      Point2D targetLocalYPoint = targetCanvas.sceneToLocal(sourceScene);
      if (targetLocalYPoint == null || !Double.isFinite(targetLocalYPoint.getY())) return false;
      targetY = Math.max(2.0, Math.min(targetCanvas.getHeight() - 2.0, targetLocalYPoint.getY()));
    }

    return MainController.drawCrossStackMateArc(this,
        this, x1, y1,
        targetCanvas, targetX, targetY,
        Color.rgb(80, 160, 255, 0.95));
  }

  /** Draw dashed arc between two screen points with the given color. */
  private void drawDashedArc(double x1, double y1, double x2, double y2, Color color) {
    double midX = (x1 + x2) * 0.5;
    double archHeight = Math.max(18.0, Math.abs(x2 - x1) * 0.18);
    double ctrlY = Math.min(y1, y2) - archHeight;

    reactiveGc.setStroke(color);
    reactiveGc.setLineWidth(1.3);
    reactiveGc.setLineDashes(6, 4);
    reactiveGc.beginPath();
    reactiveGc.moveTo(x1, y1);
    reactiveGc.quadraticCurveTo(midX, ctrlY, x2, y2);
    reactiveGc.stroke();
    reactiveGc.setLineDashes(0);
  }

  /**
   * Draw dashed connector from selected read to split edge when mate is visible
   * in another stack (e.g. translocation view in side-by-side split).
   */
  private boolean drawMateConnectorToOtherSplit(BAMRecord selected, double selectedY, double selectedH) {
    if (selected.matePos < 0) return false;

    String mateChrom = resolveMateChromName(selected);
    if (mateChrom == null || mateChrom.isEmpty()) return false;

    List<DrawStack> stacks = drawStackManager.getStacks();
    int thisIdx = stacks.indexOf(drawStack);
    if (thisIdx < 0) return false;

    int targetIdx = -1;
    DrawStack targetStack = null;
    for (int i = 0; i < stacks.size(); i++) {
      if (i == thisIdx) continue;
      DrawStack ds = stacks.get(i);
      String dsChrom = normalizeChrom(ds.chromosome);
      if (!dsChrom.equals(normalizeChrom(mateChrom))) continue;
      if (selected.matePos >= ds.start && selected.matePos <= ds.end) {
        targetIdx = i;
        targetStack = ds;
        break;
      }
    }
    if (targetIdx < 0 || targetStack == null || targetStack.alignmentCanvas == null) return false;

    AlignmentCanvas targetCanvas = targetStack.alignmentCanvas;
    if (lastCrossStackTargetCanvas != null && lastCrossStackTargetCanvas != targetCanvas) {
      boolean cleared = lastCrossStackTargetCanvas.clearExternalMateHighlightFrom(this);
      if (cleared) update.set(!update.get());
    }
    boolean changed = targetCanvas.setExternalMateHighlightFrom(this, selected.readName);
    lastCrossStackTargetCanvas = targetCanvas;
    if (changed) update.set(!update.get());

    double x1 = drawReads.calcReadArcAnchorX(selected);
    double y1 = selectedY + selectedH * 0.5;

    Point2D targetAnchor = findCrossStackReadAnchor(targetCanvas, selected, selected.matePos);
    double targetX;
    double targetY;
    if (targetAnchor != null) {
      targetX = targetAnchor.getX();
      targetY = targetAnchor.getY();
    } else {
      targetX = (selected.matePos - targetStack.start) * targetStack.pixelSize;
      targetX = Math.max(2.0, Math.min(targetCanvas.getWidth() - 2.0, targetX));
      Point2D sourceScene = localToScene(x1, y1);
      if (sourceScene == null) return false;
      Point2D targetLocalYPoint = targetCanvas.sceneToLocal(sourceScene);
      if (targetLocalYPoint == null || !Double.isFinite(targetLocalYPoint.getY())) return false;
      targetY = Math.max(2.0, Math.min(targetCanvas.getHeight() - 2.0, targetLocalYPoint.getY()));
    }

    return MainController.drawCrossStackMateArc(this,
        this, x1, y1,
        targetCanvas, targetX, targetY);
  }

  private static String normalizeChrom(String chrom) {
    if (chrom == null) return "";
    return chrom.startsWith("chr") ? chrom.substring(3) : chrom;
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  @FunctionalInterface
  private interface SampleConsumer { void accept(double sampleY, Sample sample); }

  private void forEachVisibleSample(double masterOffset, double sampleH, SampleConsumer consumer) {
    List<Integer> displayedTrackIndices = sampleRegistry.getDisplayedTrackIndices();
      int firstVisibleSlot = Math.max(0, sampleRegistry.getFirstVisibleSample());
      int lastVisibleSlot = sampleRegistry.getLastVisibleSample();
      for (int slot = firstVisibleSlot;
        slot <= lastVisibleSlot && slot < displayedTrackIndices.size(); slot++) {
      int i = displayedTrackIndices.get(slot);
      // Skip invalid track indices (can be -1 or out of bounds during concurrent updates)
      if (i < 0 || i >= sampleRegistry.getSampleTracks().size()) continue;
      SampleTrack track = sampleRegistry.getSampleTracks().get(i);
      if (!track.isVisible()) continue;
      double sampleY = masterOffset + slot * sampleH - sampleRegistry.getScrollBarPosition();
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

  /**
   * Draw a bright highlight rectangle over the single base under the cursor.
   */
  private void drawBaseHighlight(BAMRecord read, double mx, double readY, double readH) {
    int genomicPos = (int) (drawStack.start + mx * drawStack.scale);
    if (genomicPos < read.pos || genomicPos >= read.end) return;
    double bx = (genomicPos - drawStack.start) * drawStack.pixelSize;
    double bw = Math.max(1, drawStack.pixelSize);
    reactiveGc.setStroke(Color.WHITE);
    reactiveGc.setLineWidth(1.5);
    reactiveGc.strokeRect(bx, readY - 1, bw, readH + 2);
    reactiveGc.setLineWidth(1);
  }

  /**
   * Draw a small tooltip near the cursor showing the per-base value for the
   * given read at the genomic position under the mouse. The value depends on
   * the active {@link ReadColorMode}: signal-tag value for UC/UD/UL modes,
   * or Phred quality score for STRAND.
   */
  private void drawBaseTooltip(BAMRecord read, double mx, double my, ReadColorMode mode) {
    int genomicPos = (int) (drawStack.start + mx * drawStack.scale);
    if (genomicPos < read.pos || genomicPos >= read.end) return;

    String label;
    switch (mode) {
      case UC_TAG, UD_TAG, UL_TAG -> {
        short[] sig = read.signalTag;
        if (sig == null) return;
        int idx;
        if (read.urTag != null && read.urTag.length >= 2) {
          idx = read.isReverseStrand() ? (read.urTag[1] - genomicPos)
                                       : (genomicPos - read.urTag[0]);
        } else {
          idx = read.genomicPosToReadIndex(genomicPos);
        }
        if (idx < 0 || idx >= sig.length) return;
        String tagName = mode == ReadColorMode.UC_TAG ? "uc"
                       : mode == ReadColorMode.UD_TAG ? "ud" : "ul";
        label = tagName + "=" + sig[idx];
      }
      default -> {
        // STRAND (and other modes): show base quality if available
        byte[] qual = read.qualities;
        int idx = read.genomicPosToReadIndex(genomicPos);
        if (idx < 0) return;
        if (qual != null && idx < qual.length) {
          int q = qual[idx] & 0xFF;
          label = "Q=" + q;
        } else {
          label = "Base=" + Character.toUpperCase(read.getBaseAtReadIndex(idx));
        }
      }
    }

    // Optional second line: amino-acid change if the base is a mismatch and is
    // inside a coding region.
    String aaChange = computeAaChange(read, genomicPos);

    reactiveGc.setFont(AppFonts.getFont("System", FontWeight.BOLD, 11));
    double padX = 4, padY = 3;
    int    maxLen = aaChange != null ? Math.max(label.length(), aaChange.length()) : label.length();
    double textW  = maxLen * 7.0; // rough width estimate
    double boxW   = textW + 2 * padX;
    double lineH  = 14;
    int    lines  = aaChange != null ? 2 : 1;
    double boxH   = lineH * lines + 2 * padY;
    double bx = mx + 12;
    double by = my + 12;
    if (bx + boxW > getWidth())  bx = mx - boxW - 8;
    if (by + boxH > getHeight()) by = my - boxH - 8;
    reactiveGc.setFill(Color.rgb(20, 20, 20, 0.85));
    reactiveGc.fillRect(bx, by, boxW, boxH);
    reactiveGc.setStroke(Color.rgb(220, 220, 220, 0.9));
    reactiveGc.setLineWidth(1);
    reactiveGc.strokeRect(bx + 0.5, by + 0.5, boxW - 1, boxH - 1);
    reactiveGc.setFill(Color.WHITE);
    reactiveGc.fillText(label, bx + padX, by + padY + lineH - 3);
    if (aaChange != null) {
      reactiveGc.setFill(Color.rgb(255, 220, 120));
      reactiveGc.fillText(aaChange, bx + padX, by + padY + 2 * lineH - 3);
    }
  }

  /**
   * If the base at {@code genomicPos} on {@code read} is a mismatch and falls inside
   * a coding (CDS) exon of some transcript, returns a short HGVS-like protein change
   * string (e.g. {@code "p.Arg273His"}). Returns {@code null} otherwise.
   *
   * Only handles single-base substitutions whose codon is fully contained within
   * a single CDS-portion of an exon (no codon spanning exon junctions).
   */
  private String computeAaChange(BAMRecord read, int genomicPos) {
    int[] mm = read.mismatches;
    if (mm == null || mm.length < 3) return null;

    // Find a mismatch entry at this genomic position.
    char readBase = 0;
    for (int i = 0; i + 2 < mm.length; i += 3) {
      if (mm[i] == genomicPos) {
        readBase = (char) mm[i + 1];
        break;
      }
    }
    if (readBase == 0) return null; // not a mismatch

    return computeAaChangeAtPosition(genomicPos, readBase);
  }

  private String computeAaChangeAtPosition(int genomicPos, char altBase) {
    if (altBase == 0) return null;

    if (!AnnotationData.isGenesLoaded()) return null;
    List<Gene> genes = AnnotationData.getGenesByChrom().get(drawStack.chromosome);
    if (genes == null) return null;

    ReferenceGenomeService refSvc = ServiceRegistry.getInstance().getReferenceGenomeService();
    if (!refSvc.hasGenome()) return null;

    for (Gene gene : genes) {
      if (genomicPos < gene.start() || genomicPos > gene.end()) continue;
      for (Transcript tx : gene.transcripts()) {
        if (!tx.hasCDS()) continue;
        if (genomicPos < tx.cdsStart() || genomicPos > tx.cdsEnd()) continue;
        String change = aaChangeForTranscript(tx, gene, genomicPos, altBase, refSvc);
        if (change != null) return change;
      }
    }
    return null;
  }

  private String aaChangeForTranscript(Transcript tx, Gene gene, int genomicPos,
                                       char readBase, ReferenceGenomeService refSvc) {
    boolean isReverse = "-".equals(gene.strand());
    List<long[]> exons = tx.exons();
    if (exons == null || exons.isEmpty()) return null;

    // Find the exon containing genomicPos (must overlap CDS).
    long exonStart = -1, exonEnd = -1;
    for (long[] ex : exons) {
      if (genomicPos >= ex[0] && genomicPos <= ex[1]) {
        exonStart = ex[0];
        exonEnd   = ex[1];
        break;
      }
    }
    if (exonStart < 0) return null;

    long cdsStart = tx.cdsStart(), cdsEnd = tx.cdsEnd();
    long regionStart = Math.max(exonStart, cdsStart);
    long regionEnd   = Math.min(exonEnd,   cdsEnd);
    if (genomicPos < regionStart || genomicPos > regionEnd) return null;

    // Cumulative CDS length up to (not including) this exon, in 5'→3' transcript order.
    long cdsOffset = 0;
    if (isReverse) {
      for (long[] ex : exons) {
        if (ex[0] > exonEnd) {
          long s = Math.max(ex[0], cdsStart), e = Math.min(ex[1], cdsEnd);
          if (e >= s) cdsOffset += e - s + 1;
        }
      }
    } else {
      for (long[] ex : exons) {
        if (ex[1] < exonStart) {
          long s = Math.max(ex[0], cdsStart), e = Math.min(ex[1], cdsEnd);
          if (e >= s) cdsOffset += e - s + 1;
        }
      }
    }

    // 1-based position within the full CDS.
    long cdsPosition = isReverse
        ? cdsOffset + (regionEnd - genomicPos + 1)
        : cdsOffset + (genomicPos - regionStart + 1);
    int aaNum         = (int) ((cdsPosition - 1) / 3) + 1;
    int posInCodon    = (int) ((cdsPosition - 1) % 3); // 0..2 in mRNA order

    // Genomic span covering the full codon. If it doesn't fit within this exon's
    // CDS portion, skip (codon crosses an exon junction — too complex to handle here).
    long codonGenomicLow, codonGenomicHigh;
    if (isReverse) {
      codonGenomicLow  = genomicPos - (2 - posInCodon);
      codonGenomicHigh = genomicPos + posInCodon;
    } else {
      codonGenomicLow  = genomicPos - posInCodon;
      codonGenomicHigh = genomicPos + (2 - posInCodon);
    }
    if (codonGenomicLow < regionStart || codonGenomicHigh > regionEnd) return null;

    String refGenomic = refSvc.getBases(drawStack.chromosome, (int) codonGenomicLow, (int) codonGenomicHigh);
    if (refGenomic == null || refGenomic.length() != 3) return null;

    String refCodon, altCodon;
    if (isReverse) {
      refCodon = reverseComplement(refGenomic);
      altCodon = replaceCharAt(refCodon, posInCodon, complementBase(readBase));
    } else {
      refCodon = refGenomic.toUpperCase();
      altCodon = replaceCharAt(refCodon, posInCodon, Character.toUpperCase(readBase));
    }

    char refAA = AminoAcids.translateCodon(refCodon);
    char altAA = AminoAcids.translateCodon(altCodon);
    if (refAA == '?' || altAA == '?') return null;

    String refThree = AminoAcids.getThreeLetter(refAA);
    String altThree = AminoAcids.getThreeLetter(altAA);
    String prefix   = gene.name() != null ? gene.name() + " " : "";
    String suffix   = (refAA == altAA) ? " (=)" : "";
    return prefix + "p." + refThree + aaNum + altThree + suffix;
  }

  private static String replaceCharAt(String s, int idx, char c) {
    char[] arr = s.toCharArray();
    arr[idx] = c;
    return new String(arr);
  }

  private static char complementBase(char b) {
    return switch (Character.toUpperCase(b)) {
      case 'A' -> 'T';
      case 'T' -> 'A';
      case 'G' -> 'C';
      case 'C' -> 'G';
      default  -> 'N';
    };
  }

  private static String reverseComplement(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = s.length() - 1; i >= 0; i--) sb.append(complementBase(s.charAt(i)));
    return sb.toString();
  }

  /**
   * Get human-readable name for a modification code.
   */
  private String getModificationName(char modCode) {
    return switch (modCode) {
      case 'm' -> "5mC";
      case 'h' -> "5hmC";
      case 'f' -> "5fC";
      case 'c' -> "5caC";
      case 'g' -> "5hmU";
      case 'e' -> "5fU";
      case 'b' -> "5caU";
      case 'a' -> "6mA";
      case 'o' -> "8oxoG";
      case 'n' -> "Xanthosine";
      default -> String.valueOf(modCode);
    };
  }
  
  // ── Variant Management ────────────────────────────────────────────────────────
  
  /**
   * Set the variant list for this canvas.
   * This should be called when VCF data is loaded for the current genomic region.
   */
  public void setVariantList(VariantList variantList) {
    this.variantList = variantList;
    variantDrawer.markIndexDirty();
  }
  
  /**
   * Get the current variant list.
   */
  public VariantList getVariantList() {
    return variantList;
  }
  
  /**
   * Clear the variant list (e.g., when navigating to a new region).
   */
  public void clearVariantList() {
    if (variantList != null) {
      variantList.clear();
    }
    variantList = null;
  }
  
  /**
   * Mark the variant drawing index as dirty.
   * Call this when sample visibility changes (filtering, scrolling).
   */
  public void invalidateVariantIndex() {
    variantDrawer.markIndexDirty();
  }
  
  /**
   * Get the variant drawer (for testing/debugging).
   */
  public VariantDrawer getVariantDrawer() {
    return variantDrawer;
  }
}
