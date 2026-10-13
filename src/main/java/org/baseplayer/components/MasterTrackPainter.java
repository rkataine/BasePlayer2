package org.baseplayer.components;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.draw.CanvasColorLegend;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.samples.alignment.draw.CoverageDrawer;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.DrawColors;
import org.baseplayer.variant.VariantComparisonClusters;
import org.baseplayer.variant.VariantDensityPyramid;
import org.baseplayer.variant.VariantDrawSeek;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

public class MasterTrackPainter {

  private final SampleRegistry sampleRegistry;
  private final CoverageDrawer coverageDrawer;
  private final Runnable redrawCallback;

  private volatile VariantList variantList;

  /** Chromosome-scoped multi-resolution density; rebuilt on load / filter / track set. */
  private volatile VariantDensityPyramid pyramid;
  private volatile boolean pyramidRebuildPending;
  private volatile boolean pyramidBusy = false;
  private volatile int pyramidGeneration = 0;

  private final CanvasColorLegend colorLegend = new CanvasColorLegend();

  /** Zoomed-in precise bars — reuse across paints while view/filter/chain stay put. */
  private record PreciseBar(double x, double w, int count, VcfVariantType type) {}
  private List<PreciseBar> preciseBarsCache = List.of();
  private int preciseBarsMaxC = 1;
  private VariantList preciseBarsList;
  private double preciseBarsViewStart = Double.NaN;
  private double preciseBarsViewEnd = Double.NaN;
  private double preciseBarsPixelSize = Double.NaN;
  private double preciseBarsWidth = Double.NaN;
  private int preciseBarsFilterGen = -1;
  private int preciseBarsChainGen = -1;

  /** Clickable comparison-cluster highlights painted on the density band. */
  private record ClusterHit(
      double x, double y, double w, double h,
      VariantComparisonClusters.ComparisonCluster cluster) {}
  private List<ClusterHit> clusterHits = List.of();

  public MasterTrackPainter(CoverageDrawer coverageDrawer, Runnable redrawCallback) {
    this.sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    this.coverageDrawer = coverageDrawer;
    this.redrawCallback = redrawCallback;
  }

  public void setVariantList(VariantList variantList) {
    this.variantList = variantList;
    pyramid = null;
    clearPreciseBarsCache();
  }

  public void clearVariantList() {
    this.variantList = null;
    pyramidGeneration++;
    pyramid = null;
    pyramidBusy = false;
    pyramidRebuildPending = false;
    clearPreciseBarsCache();
  }

  private void clearPreciseBarsCache() {
    preciseBarsCache = List.of();
    preciseBarsMaxC = 1;
    preciseBarsList = null;
    preciseBarsViewStart = Double.NaN;
    preciseBarsViewEnd = Double.NaN;
    preciseBarsPixelSize = Double.NaN;
    preciseBarsWidth = Double.NaN;
    preciseBarsFilterGen = -1;
    preciseBarsChainGen = -1;
  }

  public VariantList getVariantList() {
    return variantList;
  }

  /** Kept for callers after sample removal; legends read session + LOH types. */
  public void refreshPresentTypesFromList() {
    // no-op: legendTypeUniverse uses VcfManager.getSessionAvailableTypes()
  }

  /**
   * Types for legends: open-VCF observed types, plus both LOH AA/BB when LOH mode
   * (or regions) is active — see {@link VcfManager#getSessionAvailableTypes()}.
   */
  private Set<VcfVariantType> legendTypeUniverse() {
    return VcfManager.getInstance().getSessionAvailableTypes();
  }

  public void forceCalculateDensity(DrawStack drawStack) {
    if (!sampleRegistry.shouldPaintMasterAggregates()) {
      pyramid = null;
      pyramidGeneration++;
      pyramidBusy = false;
      pyramidRebuildPending = false;
      return;
    }
    pyramid = null;
    ensurePyramidAsync(drawStack, true);
  }

  /**
   * Legend click: toggle canvas visibility for types already in the cache;
   * for types not materialized under the loaded filter, prompt to reload and
   * select them in Variant Manager.
   */
  public boolean handleLegendClick(double x, double y) {
    Object hitId = colorLegend.hitTest(x, y);
    if (hitId == null) {
      return false;
    }
    if (CanvasColorLegend.isSelectAll(hitId)) {
      handleSelectAllLegendClick();
      return true;
    }
    if (!(hitId instanceof VcfVariantType displayType)) {
      return false;
    }

    Set<VcfVariantType> present = legendTypeUniverse();
    Set<VcfVariantType> linked = VariantTypeVisuals.linkedTypes(displayType, present);
    VcfManager vcfManager = VcfManager.getInstance();

    // Synthetic LOH regions are not VCF-loaded — never prompt a reload for them.
    boolean lohOnly = linked.stream().allMatch(VariantTypeVisuals::isLohRegion);
    boolean anyNotMaterialized = linked.stream().anyMatch(t -> !vcfManager.isTypeMaterializedInCache(t));
    boolean enabling = linked.stream().anyMatch(t ->
        !vcfManager.isCanvasTypeVisible(t) || !vcfManager.isTypeMaterializedInCache(t));

    if (enabling && anyNotMaterialized && !lohOnly) {
      promptReloadForTypes(linked);
      return true;
    }

    vcfManager.toggleCanvasTypeVisibility(linked);
    return true;
  }

  /**
   * Legend “All” checkbox: if every swatch is on, hide all; otherwise show all
   * (canvas-only — does not prompt reload for types missing from the cache).
   */
  private void handleSelectAllLegendClick() {
    Set<VcfVariantType> present = legendTypeUniverse();
    Set<VcfVariantType> legendTypes = VariantTypeVisuals.typesForUi(present);
    if (legendTypes.isEmpty()) {
      return;
    }

    java.util.EnumSet<VcfVariantType> all = java.util.EnumSet.noneOf(VcfVariantType.class);
    for (VcfVariantType type : legendTypes) {
      all.addAll(VariantTypeVisuals.linkedTypes(type, present));
    }

    VcfManager vcfManager = VcfManager.getInstance();
    boolean allOn = true;
    for (VcfVariantType type : legendTypes) {
      Set<VcfVariantType> linked = VariantTypeVisuals.linkedTypes(type, present);
      boolean materialized = linked.stream().anyMatch(vcfManager::isTypeMaterializedInCache);
      boolean canvasOn = linked.stream().anyMatch(vcfManager::isCanvasTypeVisible);
      if (!(materialized && canvasOn)) {
        allOn = false;
        break;
      }
    }
    vcfManager.setCanvasTypesVisible(all, !allOn);
  }

  private void promptReloadForTypes(Set<VcfVariantType> types) {
    String labels = types.stream()
        .map(VariantTypeVisuals::shortLabel)
        .distinct()
        .reduce((a, b) -> a + ", " + b)
        .orElse("selected");

    boolean ok = AppDialog.confirm(
        org.baseplayer.MainApp.stage,
        "Reload required",
        "Load " + labels + " variants?",
        "These variant types are not in the current cache because they were filtered out "
            + "when variants were loaded. Reloading will also select them in Variant Manager.",
        "Reload",
        "Cancel");
    if (ok) {
      org.baseplayer.variant.ui.VariantManagerController.enableTypesAndReload(types);
    }
  }

  /** Cursor hint when hovering a clickable density legend. */
  public boolean isOverLegend(double x, double y) {
    return colorLegend.isOver(x, y);
  }

  /** Cursor hint when hovering a comparison-cluster highlight/tag. */
  public boolean isOverCluster(double x, double y) {
    return hitCluster(x, y) != null;
  }

  /**
   * Click a cluster highlight: filter samples to contributors and show cluster-focus banner.
   * @return true if a cluster was hit and focus applied
   */
  public boolean handleClusterClick(double x, double y) {
    ClusterHit hit = hitCluster(x, y);
    if (hit == null || hit.cluster() == null) {
      return false;
    }
    VariantComparisonClusters.ComparisonCluster cluster = hit.cluster();
    List<SampleTrack> allTracks = sampleRegistry.getSampleTracks();
    List<SampleTrack> contributors = new ArrayList<>(cluster.sampleCount());
    for (int idx : cluster.sampleTracks) {
      if (idx >= 0 && idx < allTracks.size()) {
        SampleTrack track = allTracks.get(idx);
        if (track != null) {
          contributors.add(track);
        }
      }
    }
    if (contributors.isEmpty()) {
      return false;
    }
    String detail = cluster.size
        + (cluster.size == 1 ? " mutation" : " mutations")
        + " · "
        + cluster.sampleCount()
        + (cluster.sampleCount() == 1 ? " sample" : " samples");
    sampleRegistry.applyClusterSubset(contributors, detail);

    int displayedCount = sampleRegistry.getDisplayedTrackCount();
    if (displayedCount > 0) {
      sampleRegistry.setVisibleSamples(0, displayedCount - 1, estimateSampleViewportHeight());
    }
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
    return true;
  }

  private ClusterHit hitCluster(double x, double y) {
    // Prefer later (top-most / tag) hits when clusters overlap.
    for (int i = clusterHits.size() - 1; i >= 0; i--) {
      ClusterHit hit = clusterHits.get(i);
      if (x >= hit.x() && x <= hit.x() + hit.w()
          && y >= hit.y() && y <= hit.y() + hit.h()) {
        return hit;
      }
    }
    return null;
  }

  private double estimateSampleViewportHeight() {
    org.baseplayer.services.DrawStackManager stacks =
        ServiceRegistry.getInstance().getDrawStackManager();
    if (!stacks.isEmpty() && stacks.getFirst().sampleTrackCanvas != null) {
      double h = stacks.getFirst().sampleTrackCanvas.getHeight();
      if (h > 0) {
        return h;
      }
    }
    return Math.max(0, sampleRegistry.getSampleHeight()
        * Math.max(1, sampleRegistry.getVisibleSampleCount()));
  }

  public void drawMasterAggregates(
      GraphicsContext gc,
      DrawStack drawStack,
      double canvasWidth,
      double masterTrackHeight) {
    drawVariantDensityOverview(gc, drawStack, canvasWidth, masterTrackHeight);
    renderMasterMethylation(gc, canvasWidth, masterTrackHeight);

    if (!sampleRegistry.getDisplayedTrackIndices().isEmpty() && masterTrackHeight > 1) {
      // Inside the band (y == height is clipped); separates aggregate from track body.
      gc.setStroke(org.baseplayer.ui.theme.AppTheme.chrome().border());
      gc.setLineWidth(1.0);
      double y = Math.floor(masterTrackHeight) - 0.5;
      gc.strokeLine(0, y, canvasWidth, y);
    }
  }

  private void renderMasterMethylation(GraphicsContext gc, double canvasWidth, double masterTrackHeight) {
    CoverageDrawer.SampleRow[] currentRows = coverageDrawer.getRows();
    if (currentRows == null || currentRows.length == 0 || masterTrackHeight < 10) {
      return;
    }

    List<CoverageDrawer.SampleRow> methylRows = new ArrayList<>();
    for (CoverageDrawer.SampleRow row : currentRows) {
      if (row.sample.isMethylationData() && row.smoothedMethylRatio != null) {
        methylRows.add(row);
      }
    }
    if (methylRows.isEmpty()) {
      return;
    }

    double margin = 2;
    double plotH = masterTrackHeight - 2 * margin;
    for (CoverageDrawer.SampleRow row : methylRows) {
      Color color = DrawColors.SAMPLE_METHYL_COLORS[row.methylColorIndex % DrawColors.SAMPLE_METHYL_COLORS.length];
      gc.setStroke(color);
      gc.setLineWidth(1.5);
      gc.beginPath();
      boolean started = false;

      for (int px = 0; px < row.smoothedMethylRatio.length; px++) {
        double val = row.smoothedMethylRatio[px];
        if (val < 0) {
          started = false;
          continue;
        }
        double y = margin + plotH * (1.0 - val);
        if (!started) {
          gc.moveTo(px, y);
          started = true;
        } else {
          gc.lineTo(px, y);
        }
      }
      gc.stroke();
    }

    gc.setLineWidth(1.0);
    gc.setFont(AppFonts.getFont("Segoe UI", 8));
    double legendX = 4;
    for (CoverageDrawer.SampleRow row : methylRows) {
      Color color = DrawColors.SAMPLE_METHYL_COLORS[row.methylColorIndex % DrawColors.SAMPLE_METHYL_COLORS.length];
      String label = row.sample.getName();
      if (label.length() > 20) {
        label = label.substring(0, 18) + "..";
      }
      gc.setFill(color);
      gc.fillRect(legendX, masterTrackHeight - 10, 8, 6);
      gc.setFill(Color.rgb(200, 200, 200, 0.9));
      gc.fillText(label, legendX + 10, masterTrackHeight - 4);
      legendX += 14 + label.length() * 5;
    }
  }

  private void drawVariantDensityOverview(
      GraphicsContext gc,
      DrawStack drawStack,
      double canvasWidth,
      double masterTrackHeight) {
    if (masterTrackHeight < 14) {
      return;
    }
    if (!org.baseplayer.io.VcfManager.getInstance().hasLoadedVcf()) {
      return;
    }

    VariantList variants = this.variantList;
    if (variants == null || variants.isEmpty()) {
      pyramid = null;
      colorLegend.clear();
      clusterHits = List.of();
      return;
    }

    // Live per-allele bars once the finest pyramid bins (~1kb) would stretch
    // wider than ~2px — otherwise close zoom stays as large blocks. Pyramid
    // remains for chromosome / gene-far zoom. Precise path floors scale to
    // cohort size so overlapping per-sample SVs don't collapse the axis.
    double viewLength = Math.max(1.0, drawStack.getViewLength());
    double pxPerBp = canvasWidth / viewLength;
    long finestBinBp = VariantDensityPyramid.BIN_SIZES_BP[
        VariantDensityPyramid.BIN_SIZES_BP.length - 1];
    boolean zoomedInPrecise = pxPerBp * finestBinBp > 2.0;
    if (!zoomedInPrecise) {
      ensurePyramidAsync(drawStack, false);
    }

    colorLegend.clear();
    clusterHits = List.of();

    double areaH = masterTrackHeight - 4;
    VariantDensityPyramid pyr = this.pyramid;
    boolean hasCachedSpans = pyr != null && !pyr.svSpans().isEmpty();
    boolean hasLiveLohSpans = hasVisibleLohRegions(variants);
    double svH = (hasCachedSpans || hasLiveLohSpans) ? Math.min(10, areaH * 0.22) : 0;
    double densH = areaH - svH;

    VariantFilter activeFilter = org.baseplayer.io.VcfManager.getInstance().getCurrentFilter();
    // Cluster bands only at close zoom (same gate as precise density bars) —
    // at chrom / gene-far zoom they clutter the pyramid overview.
    List<ClusterHit> pendingClusterTags = zoomedInPrecise
        ? drawClusterBackgrounds(
            gc, drawStack, canvasWidth, 2, densH, variants, activeFilter)
        : List.of();

    int scaleMax = 1;
    if (zoomedInPrecise) {
      scaleMax = drawPreciseDensityBars(
          gc, drawStack, canvasWidth, 2, densH, variants, activeFilter);
    } else if (pyr != null) {
      scaleMax = drawPyramidBars(gc, drawStack, canvasWidth, 2, densH, pyr);
    }
    if (svH >= 4) {
      drawSvSpanBars(gc, drawStack, canvasWidth, 2 + densH, svH, pyr);
      drawLohRegionBars(gc, drawStack, canvasWidth, 2 + densH, svH, variants);
    }

    // Tags above bars so labels stay readable; hit targets cover highlight + tag.
    clusterHits = paintClusterTags(gc, pendingClusterTags);

    drawDensityChrome(gc, canvasWidth, 2, densH > 0 ? densH : areaH, scaleMax);
  }

  /**
   * Faint genomic bands for comparison-window clusters overlapping the view.
   * Caller should only invoke at close zoom ({@code zoomedInPrecise}).
   * Returns placeholder hits (full highlight rects) for tag painting / click targets.
   */
  private List<ClusterHit> drawClusterBackgrounds(
      GraphicsContext gc,
      DrawStack drawStack,
      double canvasWidth,
      double top,
      double h,
      VariantList variants,
      VariantFilter filter) {
    if (variants == null || filter == null || !filter.hasComparisonWindow() || h < 8) {
      return List.of();
    }
    List<VariantComparisonClusters.ComparisonCluster> clusters =
        variants.listUniqueComparisonClusters(filter);
    if (clusters.isEmpty()) {
      return List.of();
    }

    double viewStart = drawStack.getViewStart();
    double viewEnd = drawStack.getViewEnd();
    double viewLen = Math.max(1.0, viewEnd - viewStart);
    double pxPerBp = canvasWidth / viewLen;

    boolean dark = org.baseplayer.ui.theme.AppTheme.isDark();
    Color fill = Color.rgb(255, 196, 72, dark ? 0.14 : 0.18);
    Color stroke = Color.rgb(255, 176, 48, dark ? 0.35 : 0.45);

    List<ClusterHit> hits = new ArrayList<>();
    for (VariantComparisonClusters.ComparisonCluster cluster : clusters) {
      long g0 = cluster.genomicStart();
      long g1 = cluster.genomicEnd();
      if (g1 < viewStart || g0 > viewEnd) {
        continue;
      }
      double x1 = (g0 - viewStart) * pxPerBp;
      double x2 = (g1 - viewStart) * pxPerBp;
      double x = Math.max(0, Math.min(x1, x2));
      double w = Math.min(canvasWidth, Math.max(x1, x2)) - x;
      if (w < 3) {
        w = 3;
      }
      gc.setFill(fill);
      gc.fillRect(x, top, w, h);
      gc.setStroke(stroke);
      gc.setLineWidth(1);
      gc.strokeRect(x + 0.5, top + 0.5, Math.max(1, w - 1), Math.max(1, h - 1));
      hits.add(new ClusterHit(x, top, w, h, cluster));
    }
    return hits;
  }

  /** Draw “Cluster · N mut · M samples” tags; expand hit boxes to include tags. */
  private List<ClusterHit> paintClusterTags(GraphicsContext gc, List<ClusterHit> backgrounds) {
    if (backgrounds == null || backgrounds.isEmpty()) {
      return List.of();
    }
    Font tagFont = AppFonts.getFont("Segoe UI", 10);
    gc.setFont(tagFont);
    Color tagBg = Color.rgb(40, 32, 16, org.baseplayer.ui.theme.AppTheme.isDark() ? 0.82 : 0.88);
    Color tagFg = Color.rgb(255, 220, 150);

    List<ClusterHit> hits = new ArrayList<>(backgrounds.size());
    for (ClusterHit bg : backgrounds) {
      VariantComparisonClusters.ComparisonCluster cluster = bg.cluster();
      String label = "Cluster · "
          + cluster.size + (cluster.size == 1 ? " mut" : " muts")
          + " · "
          + cluster.sampleCount()
          + (cluster.sampleCount() == 1 ? " sample" : " samples");
      double textW = Math.max(40, label.length() * 6.2);
      double tagH = 14;
      double tagX = Math.min(bg.x() + 2, Math.max(0, bg.x() + bg.w() - textW - 4));
      if (tagX + textW + 6 > bg.x() + bg.w() && bg.w() > 20) {
        tagX = bg.x() + 2;
        textW = Math.max(24, bg.w() - 4);
      }
      double tagY = bg.y() + 2;
      double tagW = textW + 8;

      gc.setFill(tagBg);
      gc.fillRoundRect(tagX, tagY, tagW, tagH, 4, 4);
      gc.setFill(tagFg);
      gc.setTextBaseline(javafx.geometry.VPos.CENTER);
      gc.fillText(label, tagX + 4, tagY + tagH / 2.0, Math.max(10, tagW - 6));

      // Hit covers highlight band and tag (tag may extend slightly).
      double hx = Math.min(bg.x(), tagX);
      double hy = Math.min(bg.y(), tagY);
      double hx2 = Math.max(bg.x() + bg.w(), tagX + tagW);
      double hy2 = Math.max(bg.y() + bg.h(), tagY + tagH);
      hits.add(new ClusterHit(hx, hy, hx2 - hx, hy2 - hy, cluster));
    }
    return hits;
  }

  private void ensurePyramidAsync(DrawStack drawStack, boolean force) {
    if (!sampleRegistry.shouldPaintMasterAggregates()) {
      return;
    }
    VariantList variants = this.variantList;
    if (variants == null || variants.isEmpty() || drawStack == null) {
      return;
    }
    VcfManager vcfMgr = VcfManager.getInstance();
    int filterGen = vcfMgr.getFilterGeneration();
    List<Integer> displayed = sampleRegistry.getDisplayedTrackIndices();
    int displayedFp = VariantDensityPyramid.fingerprintDisplayedTracks(displayed);
    variants.ensureVisibleChain(vcfMgr.getCurrentFilter());
    int chainGen = variants.getVisibleChainGeneration();

    VariantDensityPyramid current = this.pyramid;
    if (!force && current != null && current.matches(variants, filterGen, chainGen, displayedFp)) {
      return;
    }
    // Never stack concurrent builds — each allocates multi-level BitSets for the
    // whole chromosome. Only force/filter requests coalesce into a follow-up build.
    if (pyramidBusy) {
      if (force) {
        pyramidRebuildPending = true;
      }
      return;
    }

    pyramidRebuildPending = false;
    final int myGeneration = ++pyramidGeneration;
    pyramidBusy = true;
    final VariantFilter filter = vcfMgr.getCurrentFilter();
    final Map<SampleTrack, Integer> displayedMap = VariantDensityPyramid.displayedTrackMap(
        displayed, sampleRegistry.getSampleTracks());
    final long chromLen = Math.max(2L, (long) Math.ceil(drawStack.chromSize));
    final int buildFilterGen = filterGen;
    final int buildChainGen = chainGen;
    final int buildDisplayedFp = displayedFp;

    Thread t = new Thread(() -> {
      try {
        VariantDensityPyramid built = VariantDensityPyramid.build(
            variants, filter, displayedMap, chromLen,
            buildFilterGen, buildChainGen, buildDisplayedFp);
        Platform.runLater(() -> {
          if (myGeneration != pyramidGeneration) {
            return;
          }
          pyramid = built;
          pyramidBusy = false;
          redrawCallback.run();
          if (pyramidRebuildPending) {
            pyramidRebuildPending = false;
            ensurePyramidAsync(drawStack, true);
          }
        });
      } catch (Exception e) {
        System.err.println("Density pyramid build failed: " + e.getMessage());
        e.printStackTrace();
        Platform.runLater(() -> {
          if (myGeneration != pyramidGeneration) {
            return;
          }
          pyramidBusy = false;
          if (pyramidRebuildPending) {
            pyramidRebuildPending = false;
            ensurePyramidAsync(drawStack, true);
          }
        });
      }
    }, "density-pyramid");
    t.setDaemon(true);
    t.start();
  }

  private static boolean hasVisibleLohRegions(VariantList variants) {
    if (variants == null) {
      return false;
    }
    VcfManager vcf = VcfManager.getInstance();
    Set<VcfVariantType> legendTypes = vcf.getSessionAvailableTypes();
    for (VariantNode node : variants.getLohRegions()) {
      if (node != null
          && VariantTypeVisuals.isLohRegion(node.type)
          && vcf.isCanvasTypeDrawn(node.type, legendTypes)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Zoomed-in density: one bar per allele at exact genomic width (matches sample-track
   * variant rectangles). Avoids stretched 600-bin sampling which looks glitchy/laggy.
   * @return scale max (sample count) used for the left axis
   */
  private int drawPreciseDensityBars(
      GraphicsContext gc,
      DrawStack drawStack,
      double canvasWidth,
      double top,
      double h,
      VariantList variants,
      VariantFilter activeFilter) {
    if (variants == null || variants.isEmpty() || h <= 0 || canvasWidth <= 0) {
      return 1;
    }

    List<Integer> displayed = sampleRegistry.getDisplayedTrackIndices();
    if (displayed.isEmpty()) {
      return 1;
    }

    Map<SampleTrack, Integer> displayedTrackToIndex = new IdentityHashMap<>(displayed.size() * 2);
    List<SampleTrack> allTracks = sampleRegistry.getSampleTracks();
    for (int idx : displayed) {
      if (idx < 0 || idx >= allTracks.size()) {
        continue;
      }
      SampleTrack track = allTracks.get(idx);
      if (track != null) {
        displayedTrackToIndex.put(track, idx);
      }
    }

    double viewStart = drawStack.getViewStart();
    double viewEnd = drawStack.getViewEnd();
    double pixelSize = drawStack.getPixelSize();
    double maxBarH = h - 1;

    variants.ensureVisibleChain(activeFilter);
    int filterGen = VcfManager.getInstance().getFilterGeneration();
    int chainGen = variants.getVisibleChainGeneration();
    boolean cacheHit = preciseBarsList == variants
        && preciseBarsFilterGen == filterGen
        && preciseBarsChainGen == chainGen
        && preciseBarsWidth == canvasWidth
        && preciseBarsPixelSize == pixelSize
        && preciseBarsViewStart == viewStart
        && preciseBarsViewEnd == viewEnd
        && preciseBarsCache != null;

    List<PreciseBar> bars;
    int maxC;
    if (cacheHit) {
      bars = preciseBarsCache;
      maxC = Math.max(1, preciseBarsMaxC);
    } else {
      bars = new ArrayList<>();
      final int[] windowMax = { 1 };
      int trackBits = Math.max(64, allTracks.size() + 1);
      BitSet passing = new BitSet(trackBits);
      BitSet windowSamples = new BitSet(trackBits);
      VcfManager vcfManager = VcfManager.getInstance();
      Set<VcfVariantType> legendTypes = vcfManager.getSessionAvailableTypes();
      java.util.function.Consumer<VariantNode> collect = node -> {
        if (!vcfManager.isCanvasTypeDrawn(node.type, legendTypes)) {
          return;
        }
        passing.clear();
        int count = variants.collectDisplayTrackIndices(
            node, activeFilter, displayedTrackToIndex, passing);
        if (count <= 0) {
          return;
        }

        long g0 = node.position;
        long g1 = org.baseplayer.variant.draw.VariantDrawer.alleleEndExclusive(node);
        if (node.svEnd > node.position
            && (node.type == VcfVariantType.SV_DELETION
                || node.type == VcfVariantType.SV_INSERTION
                || node.type == VcfVariantType.SV_DUPLICATION
                || node.type == VcfVariantType.SV_INVERSION
                || VariantTypeVisuals.isCnv(node.type)
                || VariantTypeVisuals.isLohRegion(node.type))) {
          g1 = node.svEnd;
        }
        if (g1 <= viewStart || g0 > viewEnd) {
          return;
        }

        double x1 = (g0 - viewStart) * pixelSize;
        double x2 = (g1 - viewStart) * pixelSize;
        double x = Math.max(0, Math.min(x1, x2));
        double w = Math.min(canvasWidth, Math.max(x1, x2)) - x;
        if (w < 1) {
          w = 1;
        }

        windowSamples.or(passing);
        windowMax[0] = Math.max(windowMax[0], count);
        bars.add(new PreciseBar(x, w, count, node.type));
      };

      variants.withVisibleChain(activeFilter, () -> {
        for (VariantNode node : variants.getVisibleSvByPosition()) {
          if (node.position >= viewStart) {
            break;
          }
          if (node.svEnd < viewStart || node.position > viewEnd) {
            continue;
          }
          collect.accept(node);
        }

        for (VariantNode region : variants.getLohRegions()) {
          if (region == null || !VariantTypeVisuals.isLohRegion(region.type)) {
            continue;
          }
          if (region.svEnd < viewStart || region.position > viewEnd) {
            continue;
          }
          collect.accept(region);
        }

        VariantDrawSeek seek = new VariantDrawSeek();
        VariantNode node = seek.seek(variants, (long) viewStart);
        while (node != null && node.position <= viewEnd) {
          collect.accept(node);
          node = node.nextVisible;
        }
      });

      // Cohort-relative scale: floor on displayed samples so overlapping
      // per-sample SVs don't collapse the axis to 1–2.
      int cohort = Math.max(1, displayedTrackToIndex.size());
      int uniqueInView = Math.max(windowMax[0], windowSamples.cardinality());
      maxC = Math.max(cohort, uniqueInView);

      preciseBarsCache = List.copyOf(bars);
      preciseBarsMaxC = maxC;
      preciseBarsList = variants;
      preciseBarsViewStart = viewStart;
      preciseBarsViewEnd = viewEnd;
      preciseBarsPixelSize = pixelSize;
      preciseBarsWidth = canvasWidth;
      preciseBarsFilterGen = filterGen;
      preciseBarsChainGen = variants.getVisibleChainGeneration();
    }

    double bottom = top + h;
    for (PreciseBar bar : bars) {
      double bh = maxBarH * (double) bar.count() / maxC;
      if (bh < 1) {
        bh = 1;
      }
      Color c = VariantTypeVisuals.color(bar.type());
      gc.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.6));
      gc.fillRect(bar.x(), bottom - bh, bar.w(), bh);
    }
    return maxC;
  }

  private int drawPyramidBars(
      GraphicsContext gc,
      DrawStack drawStack,
      double canvasWidth,
      double top,
      double h,
      VariantDensityPyramid pyr) {
    if (pyr == null || drawStack.getViewLength() <= 0 || h <= 0 || canvasWidth <= 0) {
      return 1;
    }

    VariantDensityPyramid.Level level = pyr.pickLevel(drawStack.getViewLength(), canvasWidth);
    if (level == null) {
      return 1;
    }

    VcfManager vcfManager = VcfManager.getInstance();
    boolean showSnv = vcfManager.isCanvasTypeDrawn(VcfVariantType.SNV);
    boolean showIndel = vcfManager.isCanvasTypeDrawn(VcfVariantType.INSERTION)
        || vcfManager.isCanvasTypeDrawn(VcfVariantType.DELETION)
        || vcfManager.isCanvasTypeDrawn(VcfVariantType.MNV)
        || vcfManager.isCanvasTypeDrawn(VcfVariantType.COMPLEX);
    boolean showDel = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_DELETION)
        || vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_CNV_LOSS);
    boolean showInv = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_INVERSION);
    boolean showDup = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_DUPLICATION)
        || vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_CNV_GAIN);
    boolean showIns = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_INSERTION);
    boolean showTra = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_TRANSLOCATION);
    boolean showBnd = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_BREAKEND);
    boolean showLohAa = vcfManager.isCanvasTypeDrawn(VcfVariantType.LOH_AA);
    boolean showLohBb = vcfManager.isCanvasTypeDrawn(VcfVariantType.LOH_BB);
    boolean delColorIsCnv = !vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_DELETION)
        && vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_CNV_LOSS);
    boolean dupColorIsCnv = !vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_DUPLICATION)
        && vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_CNV_GAIN);

    boolean[] visible = new boolean[VariantDensityPyramid.Channel.values().length];
    visible[VariantDensityPyramid.Channel.SNV.ordinal()] = showSnv;
    visible[VariantDensityPyramid.Channel.INDEL.ordinal()] = showIndel;
    visible[VariantDensityPyramid.Channel.DEL.ordinal()] = showDel;
    visible[VariantDensityPyramid.Channel.INV.ordinal()] = showInv;
    visible[VariantDensityPyramid.Channel.DUP.ordinal()] = showDup;
    visible[VariantDensityPyramid.Channel.INS.ordinal()] = showIns;
    visible[VariantDensityPyramid.Channel.TRA.ordinal()] = showTra;
    visible[VariantDensityPyramid.Channel.BND.ordinal()] = showBnd;
    visible[VariantDensityPyramid.Channel.LOH_AA.ordinal()] = showLohAa;
    visible[VariantDensityPyramid.Channel.LOH_BB.ordinal()] = showLohBb;

    double viewStart = drawStack.getViewStart();
    double viewEnd = drawStack.getViewEnd();
    double viewLen = Math.max(1.0, viewEnd - viewStart);
    int maxC = Math.max(1, pyr.maxInView(level, viewStart, viewEnd, visible));
    double maxBarH = h - 1;

    int[] snv = level.channel(VariantDensityPyramid.Channel.SNV);
    int[] indel = level.channel(VariantDensityPyramid.Channel.INDEL);
    int[] del = level.channel(VariantDensityPyramid.Channel.DEL);
    int[] inv = level.channel(VariantDensityPyramid.Channel.INV);
    int[] dup = level.channel(VariantDensityPyramid.Channel.DUP);
    int[] ins = level.channel(VariantDensityPyramid.Channel.INS);
    int[] tra = level.channel(VariantDensityPyramid.Channel.TRA);
    int[] bnd = level.channel(VariantDensityPyramid.Channel.BND);
    int[] lohAa = level.channel(VariantDensityPyramid.Channel.LOH_AA);
    int[] lohBb = level.channel(VariantDensityPyramid.Channel.LOH_BB);

    for (int px = 0; px < (int) canvasWidth; px++) {
      double g0 = viewStart + px * viewLen / canvasWidth;
      double g1 = viewStart + (px + 1) * viewLen / canvasWidth;
      int b0 = pyr.genomicToBin(g0, level);
      int b1 = pyr.genomicToBin(g1, level);
      if (b1 < b0) {
        int tmp = b0;
        b0 = b1;
        b1 = tmp;
      }

      int snvVal = 0, indelVal = 0, delVal = 0, invVal = 0, dupVal = 0;
      int insVal = 0, traVal = 0, bndVal = 0, lohAaVal = 0, lohBbVal = 0;
      for (int b = b0; b <= b1; b++) {
        if (showSnv) snvVal = Math.max(snvVal, snv[b]);
        if (showIndel) indelVal = Math.max(indelVal, indel[b]);
        if (showDel) delVal = Math.max(delVal, del[b]);
        if (showInv) invVal = Math.max(invVal, inv[b]);
        if (showDup) dupVal = Math.max(dupVal, dup[b]);
        if (showIns) insVal = Math.max(insVal, ins[b]);
        if (showTra) traVal = Math.max(traVal, tra[b]);
        if (showBnd) bndVal = Math.max(bndVal, bnd[b]);
        if (showLohAa) lohAaVal = Math.max(lohAaVal, lohAa[b]);
        if (showLohBb) lohBbVal = Math.max(lohBbVal, lohBb[b]);
      }

      record BarData(int value, VcfVariantType type) {}
      List<BarData> bars = new ArrayList<>();
      if (snvVal > 0) bars.add(new BarData(snvVal, VcfVariantType.SNV));
      if (indelVal > 0) bars.add(new BarData(indelVal, VcfVariantType.DELETION));
      if (delVal > 0) {
        bars.add(new BarData(delVal,
            delColorIsCnv ? VcfVariantType.SV_CNV_LOSS : VcfVariantType.SV_DELETION));
      }
      if (invVal > 0) bars.add(new BarData(invVal, VcfVariantType.SV_INVERSION));
      if (dupVal > 0) {
        bars.add(new BarData(dupVal,
            dupColorIsCnv ? VcfVariantType.SV_CNV_GAIN : VcfVariantType.SV_DUPLICATION));
      }
      if (insVal > 0) bars.add(new BarData(insVal, VcfVariantType.SV_INSERTION));
      if (traVal > 0) bars.add(new BarData(traVal, VcfVariantType.SV_TRANSLOCATION));
      if (bndVal > 0) bars.add(new BarData(bndVal, VcfVariantType.SV_BREAKEND));
      if (lohAaVal > 0) bars.add(new BarData(lohAaVal, VcfVariantType.LOH_AA));
      if (lohBbVal > 0) bars.add(new BarData(lohBbVal, VcfVariantType.LOH_BB));

      bars.sort((a, b) -> Integer.compare(b.value, a.value));

      double bottom = top + h;
      for (BarData bar : bars) {
        double bh = maxBarH * (double) bar.value / maxC;
        Color c = VariantTypeVisuals.color(bar.type());
        gc.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.55));
        gc.fillRect(px, bottom - bh, 1, bh);
      }
    }
    return maxC;
  }

  private void drawDensityChrome(
      GraphicsContext gc,
      double canvasWidth,
      double top,
      double h,
      int maxCount) {
    if (h < 8 || canvasWidth < 40) {
      return;
    }

    Set<VcfVariantType> present = legendTypeUniverse();
    Set<VcfVariantType> legendTypes = VariantTypeVisuals.typesForUi(present);
    if (legendTypes.isEmpty()) {
      return;
    }

    VcfManager vcfManager = VcfManager.getInstance();

    // Left scale
    double scaleX = 3;
    double scaleW = 28;
    var chrome = org.baseplayer.ui.theme.AppTheme.chrome();
    Color scaleBg = Color.color(
        chrome.elevated().getRed(),
        chrome.elevated().getGreen(),
        chrome.elevated().getBlue(),
        org.baseplayer.ui.theme.AppTheme.isDark() ? 0.55 : 0.92);
    gc.setFill(scaleBg);
    gc.fillRoundRect(scaleX - 1, top - 1, scaleW + 2, h + 2, 4, 4);

    // Soft light-gray scale (readable on dark chrome without stark white).
    Color scaleInk = Color.rgb(186, 186, 186);
    gc.setFont(AppFonts.getFont("Segoe UI", 10));
    gc.setFill(scaleInk);
    gc.setTextBaseline(javafx.geometry.VPos.TOP);
    gc.fillText(String.valueOf(Math.max(1, maxCount)), scaleX + 3, top + 1);
    gc.setTextBaseline(javafx.geometry.VPos.BOTTOM);
    gc.fillText("0", scaleX + 3, top + h - 1);

    gc.setStroke(scaleInk);
    gc.setGlobalAlpha(0.9);
    gc.setLineWidth(1.5);
    double axisX = scaleX + scaleW - 4;
    gc.strokeLine(axisX, top + 1, axisX, top + h - 1);
    gc.setLineWidth(1.3);
    for (int i = 0; i <= 4; i++) {
      double y = top + (i * h / 4.0);
      double tick = (i == 0 || i == 4) ? 6 : 4;
      gc.strokeLine(axisX - tick, y, axisX, y);
    }
    gc.setGlobalAlpha(1.0);

    // Color legends to the right of the scale
    List<CanvasColorLegend.Item> items = new ArrayList<>(legendTypes.size());
    for (VcfVariantType type : legendTypes) {
      Set<VcfVariantType> linked = VariantTypeVisuals.linkedTypes(type, present);
      boolean materialized = linked.stream().anyMatch(vcfManager::isTypeMaterializedInCache);
      boolean canvasOn = linked.stream().anyMatch(vcfManager::isCanvasTypeVisible);
      // Loaded + canvas-visible = full swatch; not loaded or canvas-hidden = muted.
      boolean enabled = materialized && canvasOn;
      items.add(new CanvasColorLegend.Item(
          type, VariantTypeVisuals.shortLabel(type), VariantTypeVisuals.color(type), enabled));
    }
    colorLegend.draw(
        gc, items, scaleX + scaleW + 8, top + 2, canvasWidth - 4, top + h);
  }

  private void drawSvSpanBars(
      GraphicsContext gc,
      DrawStack drawStack,
      double canvasWidth,
      double top,
      double h,
      VariantDensityPyramid pyr) {
    if (pyr == null) {
      return;
    }
    List<VariantDensityPyramid.SvSpan> spans = pyr.svSpans();
    double viewStart = drawStack.getViewStart();
    double viewLen = drawStack.getViewLength();
    if (viewLen <= 0 || spans.isEmpty()) return;

    double barY = top + 1;
    double barH = Math.max(2, h - 3);
    VcfManager vcfManager = VcfManager.getInstance();
    Set<VcfVariantType> legendTypes = vcfManager.getSessionAvailableTypes();
    for (VariantDensityPyramid.SvSpan span : spans) {
      // LOH spans are painted live via {@link #drawLohRegionBars}.
      if (VariantTypeVisuals.isLohRegion(span.type())) {
        continue;
      }
      if (!vcfManager.isCanvasTypeDrawn(span.type(), legendTypes)) {
        continue;
      }
      if (span.end() < viewStart || span.start() > viewStart + viewLen) continue;
      double x1 = Math.max(0, (span.start() - viewStart) / viewLen * canvasWidth);
      double x2 = Math.min(canvasWidth, (span.end() - viewStart) / viewLen * canvasWidth);
      if (x2 - x1 < 1.5) x2 = x1 + 1.5;
      double alpha = Math.min(0.70, 0.20 + span.sampleCount() * 0.12);

      Color base = VariantTypeVisuals.color(span.type());
      if (span.type() == VcfVariantType.SV_TRANSLOCATION
          || span.type() == VcfVariantType.SV_BREAKEND) {
        gc.setStroke(Color.color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
        gc.setLineWidth(2.0);
        gc.strokeLine(x1, barY + barH / 2, x2, barY + barH / 2);
      } else {
        gc.setFill(Color.color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
        gc.fillRect(x1, barY, x2 - x1, barH);
      }
    }
  }

  /**
   * Live LOH AA/BB region bars in the aggregate span strip (same row as SV spans).
   * Reads {@link VariantList#getLohRegions()} so bars appear as soon as LOH mode
   * builds regions and respond immediately to legend show/hide.
   */
  private void drawLohRegionBars(
      GraphicsContext gc,
      DrawStack drawStack,
      double canvasWidth,
      double top,
      double h,
      VariantList variants) {
    if (variants == null || h < 2 || canvasWidth <= 0) {
      return;
    }
    double viewStart = drawStack.getViewStart();
    double viewLen = drawStack.getViewLength();
    if (viewLen <= 0) {
      return;
    }

    List<Integer> displayed = sampleRegistry.getDisplayedTrackIndices();
    if (displayed.isEmpty()) {
      return;
    }
    Map<SampleTrack, Integer> displayedTracks = new IdentityHashMap<>(displayed.size() * 2);
    List<SampleTrack> allTracks = sampleRegistry.getSampleTracks();
    for (int idx : displayed) {
      if (idx >= 0 && idx < allTracks.size() && allTracks.get(idx) != null) {
        displayedTracks.put(allTracks.get(idx), idx);
      }
    }

    VcfManager vcf = VcfManager.getInstance();
    VariantFilter filter = vcf.getCurrentFilter();
    Set<VcfVariantType> legendTypes = vcf.getSessionAvailableTypes();
    BitSet passing = new BitSet(Math.max(64, allTracks.size() + 1));
    double barY = top + 1;
    double barH = Math.max(2, h - 3);

    for (VariantNode node : variants.getLohRegions()) {
      if (node == null || !VariantTypeVisuals.isLohRegion(node.type)) {
        continue;
      }
      if (!vcf.isCanvasTypeDrawn(node.type, legendTypes)) {
        continue;
      }
      long end = node.svEnd >= node.position ? node.svEnd : node.position;
      if (end < viewStart || node.position > viewStart + viewLen) {
        continue;
      }

      passing.clear();
      int sampleCount = variants.collectDisplayTrackIndices(
          node, filter, displayedTracks, passing);
      if (sampleCount <= 0) {
        continue;
      }

      double x1 = Math.max(0, (node.position - viewStart) / viewLen * canvasWidth);
      double x2 = Math.min(canvasWidth, (end - viewStart) / viewLen * canvasWidth);
      if (x2 - x1 < 1.5) {
        x2 = x1 + 1.5;
      }
      double alpha = Math.min(0.75, 0.28 + sampleCount * 0.14);
      Color base = VariantTypeVisuals.color(node.type);
      gc.setFill(Color.color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
      gc.fillRect(x1, barY, x2 - x1, barH);
    }
  }
}
