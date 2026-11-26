package org.baseplayer.components;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.allelecn.AlleleCnSegment;
import org.baseplayer.analysis.allelecn.LohFromAlleleCn;
import org.baseplayer.draw.CanvasColorLegend;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.VcfManager;
import org.baseplayer.io.table.MappedTableData;
import org.baseplayer.io.table.MappedTableRow;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.samples.alignment.MethylSiteContext;
import org.baseplayer.samples.alignment.MethylationCanvasFilter;
import org.baseplayer.samples.alignment.MethylationVisuals;
import org.baseplayer.samples.alignment.draw.CoverageDrawer;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.AppFonts;
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
  /** Site-context + mod-type toggles for methylation aggregates / marks. */
  private final CanvasColorLegend methylColorLegend = new CanvasColorLegend();
  /** Allele-CN / mapped-VALUE aggregate series (same class as VCF type legends). */
  private final CanvasColorLegend mappedTableColorLegend = new CanvasColorLegend();
  private static final Object LEGEND_GAIN = new Object() {
    @Override public String toString() { return "GAIN"; }
  };
  private static final Object LEGEND_LOH = new Object() {
    @Override public String toString() { return "LOH"; }
  };
  private static final Object LEGEND_MEAN = new Object() {
    @Override public String toString() { return "MEAN"; }
  };

  /**
   * Zoomed-in precise bars — reuse across paints while view/filter/chain stay put.
   * {@code position} groups alleles at the same locus for vertical stacking;
   * {@code fill}/{@code label} carry ALT-base paint (type color / null when N/A).
   */
  private record PreciseBar(
      long position,
      double x,
      double w,
      int count,
      VcfVariantType type,
      Color fill,
      String label) {}
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

  /** Clickable group stack row labels (toggle aggregate group visibility). */
  private record GroupLabelHit(double x, double y, double w, double h, int groupId) {
    boolean contains(double px, double py) {
      return px >= x && px <= x + w && py >= y && py <= y + h;
    }
  }
  private List<GroupLabelHit> groupLabelHits = List.of();

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
   * select them in Analysis.
   */
  public boolean handleLegendClick(double x, double y) {
    GroupLabelHit groupHit = hitGroupLabel(x, y);
    if (groupHit != null) {
      org.baseplayer.analysis.AnalysisSession.get()
          .toggleAggregateGroupVisible(groupHit.groupId());
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
      if (redrawCallback != null) {
        redrawCallback.run();
      }
      return true;
    }
    if (handleMappedTableLegendClick(x, y)) {
      return true;
    }
    if (handleMethylLegendClick(x, y)) {
      return true;
    }
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

  private boolean handleMethylLegendClick(double x, double y) {
    Object hitId = methylColorLegend.hitTest(x, y);
    if (hitId == null) {
      return false;
    }
    MethylationCanvasFilter filter = MethylationCanvasFilter.get();
    if (CanvasColorLegend.isSelectAll(hitId)) {
      boolean showContexts = methylRowsPresent();
      Set<Character> mods = filter.collectPresentModTypes();
      boolean allOn = (!showContexts || filter.allContextsVisible())
          && filter.allModTypesVisible(mods);
      if (showContexts) {
        filter.setAllContextsVisible(!allOn);
      }
      filter.setAllModTypesVisible(mods, !allOn);
      return true;
    }
    if (hitId instanceof MethylSiteContext context) {
      filter.toggleContext(context);
      return true;
    }
    if (hitId instanceof Character modCode) {
      filter.toggleModType(modCode);
      return true;
    }
    return false;
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
            + "when variants were loaded. Reloading will also select them in Analysis.",
        "Reload",
        "Cancel");
    if (ok) {
      org.baseplayer.variant.ui.VariantManagerController.enableTypesAndReload(types);
    }
  }

  /** Cursor hint when hovering a clickable density legend or group stack label. */
  public boolean isOverLegend(double x, double y) {
    return hitGroupLabel(x, y) != null
        || colorLegend.isOver(x, y)
        || methylColorLegend.isOver(x, y)
        || mappedTableColorLegend.isOver(x, y);
  }

  /** Group swatches on allele-CN / VALUE aggregate legends. */
  private boolean handleMappedTableLegendClick(double x, double y) {
    Object hitId = mappedTableColorLegend.hitTest(x, y);
    if (hitId == null) {
      return false;
    }
    AnalysisSession session = AnalysisSession.get();
    if (CanvasColorLegend.isSelectAll(hitId)) {
      List<Integer> groupIds = ComparisonContext.analysisGroupIds(sampleRegistry);
      if (groupIds.isEmpty()) {
        return true;
      }
      boolean allVisible = true;
      for (int groupId : groupIds) {
        if (!session.isAggregateGroupVisible(groupId)) {
          allVisible = false;
          break;
        }
      }
      for (int groupId : groupIds) {
        session.setAggregateGroupVisible(groupId, !allVisible);
      }
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
      if (redrawCallback != null) {
        redrawCallback.run();
      }
      return true;
    }
    if (hitId instanceof Integer groupId) {
      session.toggleAggregateGroupVisible(groupId);
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
      if (redrawCallback != null) {
        redrawCallback.run();
      }
      return true;
    }
    // Gain / LOH / Mean chrome items are informational only.
    return hitId == LEGEND_GAIN || hitId == LEGEND_LOH || hitId == LEGEND_MEAN;
  }

  private GroupLabelHit hitGroupLabel(double x, double y) {
    for (int i = groupLabelHits.size() - 1; i >= 0; i--) {
      GroupLabelHit hit = groupLabelHits.get(i);
      if (hit != null && hit.contains(x, y)) {
        return hit;
      }
    }
    return null;
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
    drawMethylFilterLegend(gc, canvasWidth, masterTrackHeight);
    renderMasterMappedTable(gc, drawStack, canvasWidth, masterTrackHeight);
    // Significance ticks (renderAnalysisGroupDiff) disabled for now — confusing next to
    // group density stacks; method kept for a later opt-in.

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
      if (row.sample != null
          && row.sample.isMethylationData()
          && row.smoothedMethylRatio != null) {
        methylRows.add(row);
      }
    }
    if (methylRows.isEmpty()) {
      return;
    }

    Map<Sample, SampleTrack> sampleToTrack = methylSampleTrackIndex();
    AnalysisSession session = AnalysisSession.get();
    LinkedHashMap<Integer, List<CoverageDrawer.SampleRow>> groupRows = new LinkedHashMap<>();
    List<Integer> analysisIds = ComparisonContext.analysisGroupIds(sampleRegistry);
    for (int groupId : analysisIds) {
      if (!session.isAggregateGroupVisible(groupId)) {
        continue;
      }
      List<CoverageDrawer.SampleRow> members = new ArrayList<>();
      for (CoverageDrawer.SampleRow row : methylRows) {
        SampleTrack track = sampleToTrack.get(row.sample);
        if (trackInAnalysisGroup(track, groupId)) {
          members.add(row);
        }
      }
      if (!members.isEmpty()) {
        groupRows.put(groupId, members);
      }
    }
    // Aggregate band: group means when ≥2 cohorts have methyl data; else one cohort mean.
    // Never draw per-sample traces here.
    boolean showGroupMeans = groupRows.size() >= 2
        || (groupRows.size() == 1 && analysisIds.size() >= 2);

    double margin = 2;
    double plotH = masterTrackHeight - 2 * margin;

    if (showGroupMeans) {
      drawMethylGroupRows(gc, canvasWidth, margin, plotH, groupRows);
    } else {
      double[] cohortMean = meanMethylSeries(methylRows);
      if (cohortMean != null) {
        strokeMethylSeries(gc, cohortMean, Color.rgb(40, 180, 180, 0.95), 2.8, margin, plotH);
      }
    }
  }

  /**
   * One equal-height row per analysis group (CNV-style stacks): full-width mean
   * methylation with the group label overlaid in the row's top-left corner.
   */
  private void drawMethylGroupRows(
      GraphicsContext gc,
      double canvasWidth,
      double top,
      double h,
      LinkedHashMap<Integer, List<CoverageDrawer.SampleRow>> groupRows) {
    if (groupRows == null || groupRows.isEmpty() || h < 8 || canvasWidth < 40) {
      return;
    }
    List<Map.Entry<Integer, List<CoverageDrawer.SampleRow>>> rows =
        new ArrayList<>(groupRows.entrySet());
    int n = rows.size();
    double rowH = h / n;
    double plotLeft = 0;
    double plotW = Math.max(8, canvasWidth);

    gc.setFont(AppFonts.getFont("Segoe UI", 10));
    gc.setTextBaseline(javafx.geometry.VPos.TOP);
    Color axis = org.baseplayer.ui.theme.AppTheme.canvas().axisInk();

    for (int ri = 0; ri < n; ri++) {
      Map.Entry<Integer, List<CoverageDrawer.SampleRow>> entry = rows.get(ri);
      int groupId = entry.getKey();
      List<CoverageDrawer.SampleRow> members = entry.getValue();
      double rowTop = top + ri * rowH;
      Color gColor = ComparisonContext.groupColor(sampleRegistry, groupId);

      if (ri > 0) {
        gc.setStroke(Color.rgb(120, 120, 120, 0.35));
        gc.setLineWidth(1.0);
        gc.strokeLine(0, rowTop + 0.5, canvasWidth - 2, rowTop + 0.5);
      }

      if (rowH >= 6 && members != null && !members.isEmpty()) {
        double seriesTop = rowTop + 2;
        double seriesH = Math.max(4, rowH - 4);
        if (rowH >= 18) {
          double guideY = seriesTop + seriesH * 0.5;
          gc.setStroke(Color.color(axis.getRed(), axis.getGreen(), axis.getBlue(), 0.28));
          gc.setLineDashes(3, 3);
          gc.setLineWidth(1);
          gc.strokeLine(plotLeft, guideY, canvasWidth - 2, guideY);
          gc.setLineDashes();
        }

        double[] mean = meanMethylSeries(members);
        if (mean != null) {
          gc.save();
          try {
            gc.beginPath();
            gc.rect(plotLeft, rowTop, plotW, rowH);
            gc.clip();
            Color stroke = Color.color(gColor.getRed(), gColor.getGreen(), gColor.getBlue(), 0.95);
            strokeMethylSeriesInPlot(
                gc, mean, stroke, 2.4, plotLeft, seriesTop, plotW, seriesH);
          } finally {
            gc.restore();
          }
        }
      }

      String name = truncateLegendLabel(ComparisonContext.groupName(sampleRegistry, groupId), 12);
      int memberCount = members != null ? members.size() : 0;
      paintGroupRowOverlayLabel(
          gc, 4, rowTop + 2, gColor, true, name + " · n=" + memberCount, axis);
    }
  }

  /**
   * Like {@link #strokeMethylSeries} but maps series x into {@code [plotLeft, plotLeft+plotW]}
   * and y into {@code [seriesTop, seriesTop+seriesH]} (methyl fraction 0 bottom → 1 top).
   */
  private static void strokeMethylSeriesInPlot(
      GraphicsContext gc,
      double[] series,
      Color color,
      double lineWidth,
      double plotLeft,
      double seriesTop,
      double plotW,
      double seriesH) {
    if (series == null || series.length == 0 || !(seriesH > 0) || !(plotW > 0)) {
      return;
    }
    gc.setStroke(color);
    gc.setFill(color);
    gc.setLineWidth(lineWidth);
    gc.beginPath();
    boolean started = false;
    boolean hasStroke = false;
    double lastX = 0;
    double lastY = 0;
    int n = series.length;
    for (int i = 0; i < n; i++) {
      double val = series[i];
      if (!(val >= 0.0) || !Double.isFinite(val)) {
        if (started && !hasStroke) {
          double r = Math.max(1.5, lineWidth);
          gc.fillOval(lastX - r, lastY - r, r * 2, r * 2);
          gc.beginPath();
        } else if (hasStroke) {
          gc.stroke();
          gc.beginPath();
        }
        started = false;
        hasStroke = false;
        continue;
      }
      double x = plotLeft + (i + 0.5) * plotW / n;
      double y = seriesTop + seriesH * (1.0 - Math.min(1.0, val));
      if (!Double.isFinite(x) || !Double.isFinite(y)) {
        started = false;
        hasStroke = false;
        continue;
      }
      if (!started) {
        gc.moveTo(x, y);
        started = true;
        hasStroke = false;
      } else {
        gc.lineTo(x, y);
        hasStroke = true;
      }
      lastX = x;
      lastY = y;
    }
    if (hasStroke) {
      gc.stroke();
    } else if (started) {
      double r = Math.max(1.5, lineWidth);
      gc.fillOval(lastX - r, lastY - r, r * 2, r * 2);
    }
  }

  private Map<Sample, SampleTrack> methylSampleTrackIndex() {
    Map<Sample, SampleTrack> index = new IdentityHashMap<>();
    for (SampleTrack track : sampleRegistry.getSampleTracks()) {
      if (track == null) {
        continue;
      }
      for (Sample sample : track.getSamples()) {
        if (sample != null) {
          index.put(sample, track);
        }
      }
    }
    return index;
  }

  private static final Color TABLE_GAIN_COLOR = Color.rgb(45, 95, 210, 0.88);
  private static final Color TABLE_LOH_COLOR = Color.rgb(210, 55, 55, 0.88);
  private static final LohFromAlleleCn TABLE_LOH_POLICY = LohFromAlleleCn.binary();
  /** Total CN above this counts as gain in the allele-CN recurrence aggregate. */
  private static final double TABLE_GAIN_CN_THRESHOLD = 2.5;

  /**
   * Aggregate mapped tables on the master band.
   * Allele-CN: CNV-style recurrence — gain counts up, LOH counts down
   * (peaks = shared / minimal regions). VALUE tables: single cohort/group mean.
   */
  private void renderMasterMappedTable(
      GraphicsContext gc,
      DrawStack drawStack,
      double canvasWidth,
      double masterTrackHeight) {
    if (drawStack == null || canvasWidth < 8 || masterTrackHeight < 12) {
      return;
    }
    String chrom = drawStack.getChromosome();
    if (chrom == null || chrom.isBlank()) {
      return;
    }
    int width = Math.max(1, (int) Math.floor(canvasWidth));
    double viewStart = drawStack.getViewStart();
    double viewEnd = drawStack.getViewEnd();
    long vStart = (long) viewStart;
    long vEnd = (long) viewEnd;

    List<AlleleCnMask> alleleMasks = new ArrayList<>();
    List<TableSeries> valueSeries = new ArrayList<>();
    Map<Sample, SampleTrack> sampleToTrack = new IdentityHashMap<>();
    int colorIdx = 0;
    List<Integer> displayed = sampleRegistry.getDisplayedTrackIndices();
    List<SampleTrack> allTracks = sampleRegistry.getSampleTracks();
    for (int idx : displayed) {
      if (idx < 0 || idx >= allTracks.size()) {
        continue;
      }
      SampleTrack track = allTracks.get(idx);
      if (track == null || !track.isVisible()) {
        continue;
      }
      for (Sample sample : track.getSamples()) {
        if (sample == null || !sample.visible || sample.getDataType() != Sample.DataType.TABLE) {
          continue;
        }
        MappedTableData table = sample.getMappedTable();
        if (table == null) {
          continue;
        }
        sampleToTrack.put(sample, track);
        if (table.hasAlleleCn()) {
          AlleleCnMask mask = rasterizeAlleleCnMasks(
              table, chrom, vStart, vEnd, width, viewStart, viewEnd);
          if (mask != null) {
            alleleMasks.add(new AlleleCnMask(sample, mask.loh(), mask.gain()));
          }
        } else if (table.hasValueColumn() && !table.isIntervalBars()) {
          // Gene/region matrices are sparse bars — skip continuous master raster
          // (would paint through intergenic gaps).
          double[] series = rasterizeMappedTableValueSeries(
              table, chrom, vStart, vEnd, width, viewStart, viewEnd);
          if (series != null) {
            valueSeries.add(new TableSeries(sample, series, colorIdx++));
          }
        }
      }
    }

    if (!alleleMasks.isEmpty()) {
      renderAlleleCnRecurrence(
          gc, canvasWidth, masterTrackHeight, width, alleleMasks, sampleToTrack);
    } else if (!valueSeries.isEmpty()) {
      renderMappedValueMean(
          gc, canvasWidth, masterTrackHeight, valueSeries, sampleToTrack);
    } else {
      mappedTableColorLegend.clear();
    }
  }

  private record TableSeries(Sample sample, double[] series, int colorIndex) {}

  private record AlleleCnMask(Sample sample, boolean[] loh, boolean[] gain) {}

  /**
   * CNV-style summary: per-pixel sample counts for gain (up) and LOH (down).
   * Shared peaks highlight minimal recurrent regions.
   */
  private void renderAlleleCnRecurrence(
      GraphicsContext gc,
      double canvasWidth,
      double masterTrackHeight,
      int width,
      List<AlleleCnMask> masks,
      Map<Sample, SampleTrack> sampleToTrack) {
    AnalysisSession session = AnalysisSession.get();
    List<Integer> groupIds = new ArrayList<>();
    for (int groupId : ComparisonContext.analysisGroupIds(sampleRegistry)) {
      if (session.isAggregateGroupVisible(groupId)) {
        groupIds.add(groupId);
      }
    }
    boolean byGroup = !groupIds.isEmpty() && session.isShowOnAggregate();

    double margin = 2;
    double plotH = masterTrackHeight - 2 * margin;
    double midY = margin + plotH * 0.5;
    double halfH = plotH * 0.5;
    boolean drawScale = masterTrackHeight >= 28;

    int maxCount = 1;
    if (byGroup) {
      // Per-group count arrays, then stack within gain/LOH halves.
      List<int[]> groupLoh = new ArrayList<>(groupIds.size());
      List<int[]> groupGain = new ArrayList<>(groupIds.size());
      for (int groupId : groupIds) {
        int[] loh = new int[width];
        int[] gain = new int[width];
        for (AlleleCnMask mask : masks) {
          SampleTrack track = sampleToTrack.get(mask.sample());
          if (!trackInAnalysisGroup(track, groupId)) {
            continue;
          }
          addMaskCounts(loh, mask.loh());
          addMaskCounts(gain, mask.gain());
        }
        groupLoh.add(loh);
        groupGain.add(gain);
        maxCount = Math.max(maxCount, maxArray(loh));
        maxCount = Math.max(maxCount, maxArray(gain));
      }
      maxCount = Math.max(1, maxCount);

      if (drawScale) {
        drawRecurrenceScale(gc, canvasWidth, margin, midY, halfH, maxCount);
      }

      for (int px = 0; px < width; px++) {
        double yUp = midY;
        double yDown = midY;
        for (int g = 0; g < groupIds.size(); g++) {
          Color base = ComparisonContext.groupColor(sampleRegistry, groupIds.get(g));
          int gainC = groupGain.get(g)[px];
          int lohC = groupLoh.get(g)[px];
          if (gainC > 0) {
            double h = (gainC / (double) maxCount) * halfH;
            gc.setFill(Color.color(base.getRed(), base.getGreen(), base.getBlue(), 0.9));
            gc.fillRect(px, yUp - h, 1, Math.max(1, h));
            yUp -= h;
          }
          if (lohC > 0) {
            double h = (lohC / (double) maxCount) * halfH;
            // Slightly darker for LOH half so group stacks stay distinguishable.
            gc.setFill(Color.color(
                base.getRed() * 0.85, base.getGreen() * 0.55, base.getBlue() * 0.55, 0.9));
            gc.fillRect(px, yDown, 1, Math.max(1, h));
            yDown += h;
          }
        }
      }
    } else {
      int[] lohCount = new int[width];
      int[] gainCount = new int[width];
      for (AlleleCnMask mask : masks) {
        addMaskCounts(lohCount, mask.loh());
        addMaskCounts(gainCount, mask.gain());
      }
      maxCount = Math.max(1, Math.max(maxArray(lohCount), maxArray(gainCount)));

      if (drawScale) {
        drawRecurrenceScale(gc, canvasWidth, margin, midY, halfH, maxCount);
      }

      for (int px = 0; px < width; px++) {
        int gainC = gainCount[px];
        int lohC = lohCount[px];
        if (gainC > 0) {
          double h = (gainC / (double) maxCount) * halfH;
          gc.setFill(TABLE_GAIN_COLOR);
          gc.fillRect(px, midY - h, 1, Math.max(1, h));
        }
        if (lohC > 0) {
          double h = (lohC / (double) maxCount) * halfH;
          gc.setFill(TABLE_LOH_COLOR);
          gc.fillRect(px, midY, 1, Math.max(1, h));
        }
      }
    }

    // Midline
    Color axis = org.baseplayer.ui.theme.AppTheme.canvas().axisInk();
    gc.setStroke(Color.color(axis.getRed(), axis.getGreen(), axis.getBlue(), 0.55));
    gc.setLineWidth(1);
    gc.setLineDashes(3, 3);
    gc.strokeLine(0, midY, canvasWidth, midY);
    gc.setLineDashes();

    drawAlleleCnRecurrenceLegend(
        gc, canvasWidth, masterTrackHeight, groupIds, byGroup);
  }

  private void renderMappedValueMean(
      GraphicsContext gc,
      double canvasWidth,
      double masterTrackHeight,
      List<TableSeries> valueSeries,
      Map<Sample, SampleTrack> sampleToTrack) {
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    for (TableSeries ts : valueSeries) {
      for (double v : ts.series()) {
        if (!Double.isFinite(v)) {
          continue;
        }
        min = Math.min(min, v);
        max = Math.max(max, v);
      }
    }
    if (!(max > min) || !Double.isFinite(min)) {
      min = -1.5;
      max = 1.5;
    } else {
      double pad = Math.max(0.1, (max - min) * 0.08);
      min -= pad;
      max += pad;
    }
    double midDisplay = (min <= 0 && max >= 0) ? 0 : (min + max) * 0.5;

    AnalysisSession session = AnalysisSession.get();
    LinkedHashMap<Integer, List<TableSeries>> groupSeries = new LinkedHashMap<>();
    for (int groupId : ComparisonContext.analysisGroupIds(sampleRegistry)) {
      if (!session.isAggregateGroupVisible(groupId)) {
        continue;
      }
      List<TableSeries> members = new ArrayList<>();
      for (TableSeries ts : valueSeries) {
        if (trackInAnalysisGroup(sampleToTrack.get(ts.sample()), groupId)) {
          members.add(ts);
        }
      }
      if (!members.isEmpty()) {
        groupSeries.put(groupId, members);
      }
    }
    boolean showGroupMeans = groupSeries.size() >= 1 && session.isShowOnAggregate();

    double margin = 2;
    double plotH = masterTrackHeight - 2 * margin;
    if (masterTrackHeight >= 28) {
      drawMappedValueAggregateScale(gc, canvasWidth, margin, plotH, min, max, midDisplay);
    }

    if (showGroupMeans) {
      for (Map.Entry<Integer, List<TableSeries>> entry : groupSeries.entrySet()) {
        double[] mean = averageFiniteSeries(entry.getValue());
        if (mean == null) {
          continue;
        }
        Color groupColor = ComparisonContext.groupColor(sampleRegistry, entry.getKey());
        strokeScaledSeries(
            gc, mean,
            Color.color(groupColor.getRed(), groupColor.getGreen(), groupColor.getBlue(), 0.95),
            2.8, margin, plotH, min, max);
      }
    } else {
      double[] cohortMean = averageFiniteSeries(valueSeries);
      if (cohortMean != null) {
        strokeScaledSeries(
            gc, cohortMean, Color.rgb(55, 120, 200, 0.95), 2.8, margin, plotH, min, max);
      }
    }

    drawMappedValueAggregateLegend(
        gc, canvasWidth, masterTrackHeight, valueSeries.size(), groupSeries.keySet(), showGroupMeans);
  }

  /** Per-pixel LOH / gain masks for one allele-CN table (last segment wins). */
  private static AlleleCnMask rasterizeAlleleCnMasks(
      MappedTableData table,
      String chrom,
      long viewStart,
      long viewEnd,
      int width,
      double viewStartD,
      double viewEndD) {
    List<MappedTableRow> rows = table.rowsOverlapping(chrom, viewStart, viewEnd);
    if (rows.isEmpty() || width <= 0) {
      return null;
    }
    double viewLen = Math.max(1e-9, viewEndD - viewStartD);
    boolean[] loh = new boolean[width];
    boolean[] gain = new boolean[width];
    boolean any = false;
    for (MappedTableRow row : rows) {
      if (!row.hasAlleleCn()) {
        continue;
      }
      AlleleCnSegment seg = new AlleleCnSegment(
          row.chromosome(),
          row.start1Based(),
          row.end1Based(),
          row.sampleId(),
          row.minorCn(),
          row.majorCn(),
          row.totalCn(),
          row.score());
      boolean isLoh = TABLE_LOH_POLICY.isLoh(seg);
      double total = row.totalCn() != null && Double.isFinite(row.totalCn())
          ? row.totalCn()
          : row.minorCn() + row.majorCn();
      boolean isGain = total > TABLE_GAIN_CN_THRESHOLD;

      double x1d = ((Math.max(row.start1Based(), viewStartD) - viewStartD) / viewLen) * width;
      double x2d = ((Math.min(row.end1Based(), viewEndD) - viewStartD) / viewLen) * width;
      if (x2d < x1d) {
        double tmp = x1d;
        x1d = x2d;
        x2d = tmp;
      }
      int x1 = Math.max(0, (int) Math.floor(x1d));
      int x2 = Math.min(width - 1, (int) Math.floor(x2d));
      if (x2 < x1) {
        x2 = x1;
      }
      for (int px = x1; px <= x2; px++) {
        loh[px] = isLoh;
        gain[px] = isGain;
        any = true;
      }
    }
    return any ? new AlleleCnMask(null, loh, gain) : null;
  }

  private static double[] rasterizeMappedTableValueSeries(
      MappedTableData table,
      String chrom,
      long viewStart,
      long viewEnd,
      int width,
      double viewStartD,
      double viewEndD) {
    List<MappedTableRow> rows = table.rowsOverlapping(chrom, viewStart, viewEnd);
    if (rows.isEmpty() || width <= 0) {
      return null;
    }
    double viewLen = Math.max(1e-9, viewEndD - viewStartD);
    double[] series = new double[width];
    Arrays.fill(series, Double.NaN);
    boolean any = false;
    for (MappedTableRow row : rows) {
      double value = row.analysisValue();
      if (!Double.isFinite(value)) {
        continue;
      }
      double x1d = ((Math.max(row.start1Based(), viewStartD) - viewStartD) / viewLen) * width;
      double x2d = ((Math.min(row.end1Based(), viewEndD) - viewStartD) / viewLen) * width;
      if (x2d < x1d) {
        double tmp = x1d;
        x1d = x2d;
        x2d = tmp;
      }
      int x1 = Math.max(0, (int) Math.floor(x1d));
      int x2 = Math.min(width - 1, (int) Math.floor(x2d));
      if (x2 < x1) {
        x2 = x1;
      }
      for (int px = x1; px <= x2; px++) {
        series[px] = value;
        any = true;
      }
    }
    return any ? series : null;
  }

  private static void addMaskCounts(int[] counts, boolean[] mask) {
    if (counts == null || mask == null) {
      return;
    }
    int n = Math.min(counts.length, mask.length);
    for (int i = 0; i < n; i++) {
      if (mask[i]) {
        counts[i]++;
      }
    }
  }

  private static int maxArray(int[] a) {
    int m = 0;
    if (a == null) {
      return 0;
    }
    for (int v : a) {
      if (v > m) {
        m = v;
      }
    }
    return m;
  }

  private static double[] averageFiniteSeries(List<TableSeries> source) {
    if (source == null || source.isEmpty()) {
      return null;
    }
    int width = 0;
    for (TableSeries ts : source) {
      if (ts != null && ts.series() != null) {
        width = Math.max(width, ts.series().length);
      }
    }
    if (width <= 0) {
      return null;
    }
    double[] sum = new double[width];
    int[] count = new int[width];
    for (TableSeries ts : source) {
      if (ts == null || ts.series() == null) {
        continue;
      }
      double[] series = ts.series();
      int n = Math.min(width, series.length);
      for (int px = 0; px < n; px++) {
        double val = series[px];
        if (Double.isFinite(val)) {
          sum[px] += val;
          count[px]++;
        }
      }
    }
    double[] mean = new double[width];
    boolean any = false;
    for (int px = 0; px < width; px++) {
      if (count[px] > 0) {
        mean[px] = sum[px] / count[px];
        any = true;
      } else {
        mean[px] = Double.NaN;
      }
    }
    return any ? mean : null;
  }

  private static void strokeScaledSeries(
      GraphicsContext gc,
      double[] series,
      Color color,
      double lineWidth,
      double margin,
      double plotH,
      double dispMin,
      double dispMax) {
    if (series == null || series.length == 0 || !(plotH > 0) || !(dispMax > dispMin)) {
      return;
    }
    gc.setStroke(color);
    gc.setFill(color);
    gc.setLineWidth(lineWidth);
    gc.beginPath();
    boolean started = false;
    boolean hasStroke = false;
    double lastX = 0;
    double lastY = 0;
    for (int px = 0; px < series.length; px++) {
      double val = series[px];
      if (!Double.isFinite(val)) {
        if (started && !hasStroke) {
          double r = Math.max(1.5, lineWidth);
          gc.fillOval(lastX - r, lastY - r, r * 2, r * 2);
          gc.beginPath();
        } else if (hasStroke) {
          gc.stroke();
          gc.beginPath();
        }
        started = false;
        hasStroke = false;
        continue;
      }
      double frac = (val - dispMin) / (dispMax - dispMin);
      frac = Math.max(0, Math.min(1, frac));
      double y = margin + plotH * (1.0 - frac);
      if (!Double.isFinite(y)) {
        started = false;
        hasStroke = false;
        continue;
      }
      if (!started) {
        gc.moveTo(px, y);
        started = true;
        hasStroke = false;
      } else {
        gc.lineTo(px, y);
        hasStroke = true;
      }
      lastX = px;
      lastY = y;
    }
    if (hasStroke) {
      gc.stroke();
    } else if (started) {
      double r = Math.max(1.5, lineWidth);
      gc.fillOval(lastX - r, lastY - r, r * 2, r * 2);
    }
  }

  private void drawRecurrenceScale(
      GraphicsContext gc,
      double canvasWidth,
      double margin,
      double midY,
      double halfH,
      int maxCount) {
    Color axis = org.baseplayer.ui.theme.AppTheme.canvas().axisInk();
    Color faint = Color.color(axis.getRed(), axis.getGreen(), axis.getBlue(), 0.2);
    gc.setLineWidth(1);
    gc.setFont(AppFonts.getFont("Segoe UI", 9));
    gc.setFill(axis);
    gc.fillText("Gain", 3, margin + 9);
    gc.fillText("LOH", 3, margin + halfH * 2 - 2);
    // Half-count guides
    if (maxCount >= 2) {
      double halfFrac = 0.5;
      gc.setStroke(faint);
      gc.strokeLine(0, midY - halfH * halfFrac, canvasWidth, midY - halfH * halfFrac);
      gc.strokeLine(0, midY + halfH * halfFrac, canvasWidth, midY + halfH * halfFrac);
      gc.setFill(Color.color(axis.getRed(), axis.getGreen(), axis.getBlue(), 0.7));
      int half = Math.max(1, maxCount / 2);
      gc.fillText(String.valueOf(half), 3, midY - halfH * halfFrac - 1);
      gc.fillText(String.valueOf(half), 3, midY + halfH * halfFrac + 9);
    }
  }

  private void drawAlleleCnRecurrenceLegend(
      GraphicsContext gc,
      double canvasWidth,
      double masterTrackHeight,
      List<Integer> groupIds,
      boolean byGroup) {
    List<CanvasColorLegend.Item> items = new ArrayList<>();
    items.add(new CanvasColorLegend.Item(
        LEGEND_GAIN, "Gain", TABLE_GAIN_COLOR, true));
    items.add(new CanvasColorLegend.Item(
        LEGEND_LOH, "LOH", TABLE_LOH_COLOR, true));
    if (byGroup && groupIds != null) {
      AnalysisSession session = AnalysisSession.get();
      for (int groupId : groupIds) {
        items.add(new CanvasColorLegend.Item(
            Integer.valueOf(groupId),
            truncateLegendLabel(ComparisonContext.groupName(sampleRegistry, groupId), 14),
            ComparisonContext.groupColor(sampleRegistry, groupId),
            session.isAggregateGroupVisible(groupId)));
      }
    }
    mappedTableColorLegend.draw(
        gc, items, 39, 4, canvasWidth - 4, Math.max(8, masterTrackHeight - 4), "All");
  }

  private void drawMappedValueAggregateScale(
      GraphicsContext gc,
      double canvasWidth,
      double margin,
      double plotH,
      double dispMin,
      double dispMax,
      double midDisplay) {
    Color axis = org.baseplayer.ui.theme.AppTheme.canvas().axisInk();
    Color faint = Color.color(axis.getRed(), axis.getGreen(), axis.getBlue(), 0.22);
    gc.setLineWidth(1);
    gc.setFont(AppFonts.getFont("Segoe UI", 9));
    double midY = margin + plotH * (1.0 - ((midDisplay - dispMin) / (dispMax - dispMin)));
    midY = Math.max(margin, Math.min(margin + plotH, midY));
    gc.setStroke(Color.color(axis.getRed(), axis.getGreen(), axis.getBlue(), 0.5));
    gc.setLineDashes(3, 3);
    gc.strokeLine(0, midY, canvasWidth, midY);
    gc.setLineDashes();
    gc.setStroke(faint);
    gc.strokeLine(0, margin, canvasWidth, margin);
    gc.strokeLine(0, margin + plotH, canvasWidth, margin + plotH);
    gc.setFill(axis);
    gc.fillText(formatAggregateTick(dispMax), 3, margin + 9);
    gc.fillText(formatAggregateTick(midDisplay), 3, midY - 1);
    gc.fillText(formatAggregateTick(dispMin), 3, margin + plotH - 1);
  }

  private void drawMappedValueAggregateLegend(
      GraphicsContext gc,
      double canvasWidth,
      double masterTrackHeight,
      int sampleCount,
      Set<Integer> groupIds,
      boolean showGroupMeans) {
    List<CanvasColorLegend.Item> items = new ArrayList<>();
    if (showGroupMeans && groupIds != null && !groupIds.isEmpty()) {
      AnalysisSession session = AnalysisSession.get();
      for (int groupId : groupIds) {
        items.add(new CanvasColorLegend.Item(
            Integer.valueOf(groupId),
            truncateLegendLabel(ComparisonContext.groupName(sampleRegistry, groupId), 14),
            ComparisonContext.groupColor(sampleRegistry, groupId),
            session.isAggregateGroupVisible(groupId)));
      }
    } else {
      items.add(new CanvasColorLegend.Item(
          LEGEND_MEAN,
          "Mean (" + sampleCount + ")",
          Color.rgb(55, 120, 200),
          true));
    }
    mappedTableColorLegend.draw(
        gc, items, 39, 4, canvasWidth - 4, Math.max(8, masterTrackHeight - 4), "All");
  }

  private static String formatAggregateTick(double v) {
    if (!Double.isFinite(v)) {
      return "";
    }
    if (Math.abs(v - Math.rint(v)) < 1e-6) {
      return String.valueOf((long) Math.rint(v));
    }
    return String.format(Locale.ROOT, "%.1f", v);
  }

  private static boolean trackInAnalysisGroup(SampleTrack track, int groupId) {
    if (track == null) {
      return false;
    }
    if (ComparisonContext.isOtherGroupId(groupId)) {
      return !track.hasGroup();
    }
    return track.isInGroup(groupId);
  }

  /** Per-pixel mean of valid smoothed ratios; {@code null} when nothing usable. */
  private static double[] meanMethylSeries(List<CoverageDrawer.SampleRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return null;
    }
    // Average every member so groups with still-streaming caches are not dropped
    // relative to a sibling group that finished first.
    return averageMethylSeries(rows);
  }

  private static double[] averageMethylSeries(List<CoverageDrawer.SampleRow> source) {
    if (source == null || source.isEmpty()) {
      return null;
    }
    int width = 0;
    for (CoverageDrawer.SampleRow row : source) {
      if (row != null && row.smoothedMethylRatio != null) {
        width = Math.max(width, row.smoothedMethylRatio.length);
      }
    }
    if (width <= 0) {
      return null;
    }
    double[] sum = new double[width];
    int[] count = new int[width];
    for (CoverageDrawer.SampleRow row : source) {
      if (row == null) {
        continue;
      }
      double[] series = row.smoothedMethylRatio;
      if (series == null) {
        continue;
      }
      int n = Math.min(width, series.length);
      for (int px = 0; px < n; px++) {
        double val = series[px];
        if (val >= 0.0 && Double.isFinite(val)) {
          sum[px] += val;
          count[px]++;
        }
      }
    }
    double[] mean = new double[width];
    boolean any = false;
    for (int px = 0; px < width; px++) {
      if (count[px] > 0) {
        mean[px] = sum[px] / count[px];
        any = true;
      } else {
        mean[px] = Double.NaN;
      }
    }
    return any ? mean : null;
  }

  private static void strokeMethylSeries(
      GraphicsContext gc,
      double[] series,
      Color color,
      double lineWidth,
      double margin,
      double plotH) {
    if (series == null || series.length == 0 || !(plotH > 0)) {
      return;
    }
    gc.setStroke(color);
    gc.setFill(color);
    gc.setLineWidth(lineWidth);
    gc.beginPath();
    boolean started = false;
    boolean hasStroke = false;
    double lastX = 0;
    double lastY = 0;
    for (int px = 0; px < series.length; px++) {
      double val = series[px];
      // NaN / negative = no data; NaN geometry corrupts NGCanvas buffers.
      if (!(val >= 0.0) || !Double.isFinite(val)) {
        if (started && !hasStroke) {
          // Sparse CpG / single-pixel mean — path stroke alone draws nothing.
          double r = Math.max(1.5, lineWidth);
          gc.fillOval(lastX - r, lastY - r, r * 2, r * 2);
          gc.beginPath();
        } else if (hasStroke) {
          gc.stroke();
          gc.beginPath();
        }
        started = false;
        hasStroke = false;
        continue;
      }
      double y = margin + plotH * (1.0 - Math.min(1.0, val));
      if (!Double.isFinite(y)) {
        started = false;
        hasStroke = false;
        continue;
      }
      if (!started) {
        gc.moveTo(px, y);
        started = true;
        hasStroke = false;
      } else {
        gc.lineTo(px, y);
        hasStroke = true;
      }
      lastX = px;
      lastY = y;
    }
    if (hasStroke) {
      gc.stroke();
    } else if (started) {
      double r = Math.max(1.5, lineWidth);
      gc.fillOval(lastX - r, lastY - r, r * 2, r * 2);
    }
  }

  /**
   * Clickable methylation filter legend (CpG/CHG/CHH + MM mod types), same
   * interaction model as variant-type density swatches.
   */
  private void drawMethylFilterLegend(
      GraphicsContext gc, double canvasWidth, double masterTrackHeight) {
    methylColorLegend.clear();
    List<CanvasColorLegend.Item> items = buildMethylLegendItems();
    if (items.isEmpty() || masterTrackHeight < 16 || canvasWidth < 80) {
      return;
    }
    // Bottom of the aggregate band so it stays clear of variant density chrome.
    double legendTop = Math.max(2, masterTrackHeight - 20);
    methylColorLegend.draw(
        gc, items, 8, legendTop, canvasWidth - 8, masterTrackHeight - 2, "All methyl");
  }

  private List<CanvasColorLegend.Item> buildMethylLegendItems() {
    List<CanvasColorLegend.Item> items = new ArrayList<>();
    MethylationCanvasFilter filter = MethylationCanvasFilter.get();
    if (methylRowsPresent()) {
      for (MethylSiteContext context : EnumSet.allOf(MethylSiteContext.class)) {
        items.add(new CanvasColorLegend.Item(
            context,
            MethylationVisuals.contextLabel(context),
            MethylationVisuals.contextColor(context),
            filter.isContextVisible(context)));
      }
    }
    Set<Character> mods = filter.collectPresentModTypes();
    // Also include mods seen in the current coverage matrix (viewport).
    CoverageDrawer.SampleRow[] rows = coverageDrawer.getRows();
    if (rows != null) {
      for (CoverageDrawer.SampleRow row : rows) {
        if (row == null || row.modTypeCounts == null) {
          continue;
        }
        mods.addAll(row.modTypeCounts.keySet());
      }
    }
    Set<Character> ordered = new LinkedHashSet<>();
    for (char c : new char[] {'m', 'h', 'f', 'c', 'a', 'g', 'e', 'b', 'o', 'n'}) {
      if (mods.contains(c) || mods.contains(Character.toUpperCase(c))) {
        ordered.add(Character.toLowerCase(c));
      }
    }
    for (Character c : mods) {
      if (c != null) {
        ordered.add(Character.toLowerCase(c));
      }
    }
    for (Character modCode : ordered) {
      items.add(new CanvasColorLegend.Item(
          modCode,
          MethylationVisuals.modLabel(modCode),
          MethylationVisuals.modColor(modCode),
          filter.isModTypeVisible(modCode)));
    }
    return items;
  }

  private boolean methylRowsPresent() {
    CoverageDrawer.SampleRow[] rows = coverageDrawer.getRows();
    if (rows == null) {
      return false;
    }
    for (CoverageDrawer.SampleRow row : rows) {
      if (row != null && row.sample != null && row.sample.isMethylationData()
          && row.smoothedMethylRatio != null) {
        return true;
      }
    }
    return false;
  }

  private static String truncateLegendLabel(String label, int maxChars) {
    if (label == null || label.isEmpty()) {
      return "?";
    }
    if (label.length() <= maxChars) {
      return label;
    }
    return label.substring(0, Math.max(1, maxChars - 2)) + "..";
  }

  /** True when sample groups exist and aggregate should show per-group stacks. */
  private boolean groupAggregateModeActive() {
    return org.baseplayer.analysis.AnalysisSession.get().isShowOnAggregate()
        && !ComparisonContext.analysisGroupIds(sampleRegistry).isEmpty();
  }

  private static int indexOfLevel(VariantDensityPyramid pyr, VariantDensityPyramid.Level level) {
    if (pyr == null || level == null || pyr.levels() == null) {
      return -1;
    }
    VariantDensityPyramid.Level[] levels = pyr.levels();
    for (int i = 0; i < levels.length; i++) {
      if (levels[i] == level) {
        return i;
      }
    }
    return -1;
  }

  private static VariantDensityPyramid.GroupDensity groupDensityForId(
      VariantDensityPyramid pyr, int groupId) {
    if (pyr == null) {
      return null;
    }
    for (VariantDensityPyramid.GroupDensity gd : pyr.groupDensities()) {
      if (gd.groupId() == groupId) {
        return gd;
      }
    }
    return null;
  }

  private static VcfVariantType typeForPyramidChannel(VariantDensityPyramid.Channel ch) {
    if (ch == null) {
      return null;
    }
    return switch (ch) {
      case SNV -> VcfVariantType.SNV;
      case INDEL -> VcfVariantType.DELETION;
      case DEL -> VcfVariantType.SV_DELETION;
      case CNV_LOSS -> VcfVariantType.SV_CNV_LOSS;
      case INV -> VcfVariantType.SV_INVERSION;
      case DUP -> VcfVariantType.SV_DUPLICATION;
      case CNV_GAIN -> VcfVariantType.SV_CNV_GAIN;
      case INS -> VcfVariantType.SV_INSERTION;
      case TRA -> VcfVariantType.SV_TRANSLOCATION;
      case BND -> VcfVariantType.SV_BREAKEND;
      case LOH_AA -> VcfVariantType.LOH_AA;
      case LOH_BB -> VcfVariantType.LOH_BB;
    };
  }

  /**
   * Equal-height group stack rows with a shared Y scale (sum of visible type counts).
   * Bars use the full canvas width (aligned with non-group density); group identity is
   * a clickable label overlaid in each row's top-left corner.
   */
  private int drawGroupStackRows(
      GraphicsContext gc,
      DrawStack drawStack,
      double canvasWidth,
      double top,
      double h,
      VariantDensityPyramid pyr) {
    groupLabelHits = List.of();
    if (pyr == null || h < 10 || canvasWidth < 40 || drawStack.getViewLength() <= 0) {
      return 1;
    }
    VariantDensityPyramid.Level level = pyr.pickLevel(drawStack.getViewLength(), canvasWidth);
    if (level == null) {
      return 1;
    }
    int levelIndex = indexOfLevel(pyr, level);
    if (levelIndex < 0) {
      return 1;
    }

    org.baseplayer.analysis.AnalysisSession session =
        org.baseplayer.analysis.AnalysisSession.get();
    List<Integer> groupIds = ComparisonContext.analysisGroupIds(sampleRegistry);
    List<VariantDensityPyramid.GroupDensity> rows = new ArrayList<>();
    for (Integer gid : groupIds) {
      if (gid == null) {
        continue;
      }
      VariantDensityPyramid.GroupDensity gd = groupDensityForId(pyr, gid);
      if (gd != null) {
        rows.add(gd);
      }
    }
    if (rows.isEmpty()) {
      return 1;
    }

    VcfManager vcf = VcfManager.getInstance();
    boolean[] visible = visiblePyramidChannels(vcf);
    double viewStart = drawStack.getViewStart();
    double viewEnd = drawStack.getViewEnd();
    double viewLen = Math.max(1.0, viewEnd - viewStart);
    int maxC = Math.max(1, maxStackedGroupDensityInView(
        pyr, level, levelIndex, rows, session, visible, viewStart, viewEnd));

    int n = rows.size();
    double rowH = h / n;
    double plotLeft = 0;
    double plotW = Math.max(8, canvasWidth);
    List<GroupLabelHit> hits = new ArrayList<>(n);
    gc.setFont(AppFonts.getFont("Segoe UI", 10));
    gc.setTextBaseline(javafx.geometry.VPos.TOP);

    for (int ri = 0; ri < n; ri++) {
      VariantDensityPyramid.GroupDensity gd = rows.get(ri);
      int groupId = gd.groupId();
      boolean shown = session.isAggregateGroupVisible(groupId);
      double rowTop = top + ri * rowH;
      double rowBottom = rowTop + rowH;

      if (ri > 0) {
        gc.setStroke(Color.rgb(120, 120, 120, 0.35));
        gc.setLineWidth(1.0);
        gc.strokeLine(0, rowTop + 0.5, canvasWidth - 2, rowTop + 0.5);
      }

      if (shown && rowH >= 4) {
        double maxBarH = Math.max(1, rowH - 2);
        int width = (int) Math.floor(plotW);
        for (int px = 0; px < width; px++) {
          double g0 = viewStart + px * viewLen / plotW;
          double g1 = viewStart + (px + 1) * viewLen / plotW;
          int b0 = pyr.genomicToBin(g0, level);
          int b1 = pyr.genomicToBin(g1, level);
          if (b1 < b0) {
            int tmp = b0;
            b0 = b1;
            b1 = tmp;
          }
          int stack = 0;
          double yCursor = rowBottom - 1;
          for (VariantDensityPyramid.Channel ch : VariantDensityPyramid.Channel.values()) {
            if (!visible[ch.ordinal()]) {
              continue;
            }
            VcfVariantType type = typeForPyramidChannel(ch);
            if (type == null) {
              continue;
            }
            int[] counts = gd.channelCounts(levelIndex, ch);
            int val = channelMaxInBins(counts, b0, b1);
            if (val <= 0) {
              continue;
            }
            double bh = maxBarH * (val / (double) maxC);
            if (bh < 0.5) {
              bh = 0.5;
            }
            Color c = VariantTypeVisuals.color(type);
            gc.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.55));
            gc.fillRect(plotLeft + px, yCursor - bh, 1, bh);
            yCursor -= bh;
            stack += val;
            if (stack >= maxC && yCursor <= rowTop + 1) {
              break;
            }
          }
        }
      }

      String name = truncateLegendLabel(
          ComparisonContext.groupName(sampleRegistry, groupId), 12);
      Color gColor = ComparisonContext.groupColor(sampleRegistry, groupId);
      double labelX = 4;
      double labelPad = 2;
      GroupLabelHit hit = paintGroupRowOverlayLabel(
          gc, labelX, rowTop + labelPad, gColor, shown, name, Color.rgb(220, 220, 220));
      hits.add(new GroupLabelHit(
          hit.x(), rowTop, hit.w(), Math.max(hit.h(), Math.min(rowH, 18)), groupId));
    }
    groupLabelHits = hits;
    return maxC;
  }

  /**
   * Semi-transparent pill with color swatch + name in the top-left of a group row.
   * Returns the pill bounds for hit-testing.
   */
  private static GroupLabelHit paintGroupRowOverlayLabel(
      GraphicsContext gc,
      double x,
      double y,
      Color groupColor,
      boolean emphasized,
      String text,
      Color textColor) {
    double sw = 8;
    double sh = 7;
    double padX = 5;
    double padY = 3;
    double gap = 4;
    javafx.scene.text.Font font = gc.getFont();
    javafx.scene.text.Text measure = new javafx.scene.text.Text(text != null ? text : "");
    measure.setFont(font);
    double textW = Math.max(8, measure.getLayoutBounds().getWidth());
    double textH = Math.max(10, measure.getLayoutBounds().getHeight());
    double pillW = padX + sw + gap + textW + padX;
    double pillH = Math.max(sh, textH) + padY * 2;

    gc.setFill(Color.rgb(20, 20, 20, emphasized ? 0.55 : 0.4));
    gc.fillRoundRect(x, y, pillW, pillH, 4, 4);

    double contentY = y + padY;
    double swatchY = contentY + (Math.max(sh, textH) - sh) * 0.5;
    gc.setFill(Color.color(
        groupColor.getRed(),
        groupColor.getGreen(),
        groupColor.getBlue(),
        emphasized ? 0.95 : 0.4));
    gc.fillRoundRect(x + padX, swatchY, sw, sh, 2, 2);

    gc.setFill(emphasized
        ? Color.color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(), 0.95)
        : Color.rgb(160, 160, 160, 0.75));
    gc.setTextBaseline(javafx.geometry.VPos.TOP);
    gc.fillText(text != null ? text : "", x + padX + sw + gap, contentY);

    return new GroupLabelHit(x, y, pillW, pillH, -1);
  }

  private static boolean[] visiblePyramidChannels(VcfManager vcfManager) {
    boolean[] visible = new boolean[VariantDensityPyramid.Channel.values().length];
    visible[VariantDensityPyramid.Channel.SNV.ordinal()] =
        vcfManager.isCanvasTypeDrawn(VcfVariantType.SNV);
    visible[VariantDensityPyramid.Channel.INDEL.ordinal()] =
        vcfManager.isCanvasTypeDrawn(VcfVariantType.INSERTION)
            || vcfManager.isCanvasTypeDrawn(VcfVariantType.DELETION)
            || vcfManager.isCanvasTypeDrawn(VcfVariantType.MNV)
            || vcfManager.isCanvasTypeDrawn(VcfVariantType.COMPLEX);
    visible[VariantDensityPyramid.Channel.DEL.ordinal()] =
        vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_DELETION);
    visible[VariantDensityPyramid.Channel.CNV_LOSS.ordinal()] =
        vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_CNV_LOSS);
    visible[VariantDensityPyramid.Channel.INV.ordinal()] =
        vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_INVERSION);
    visible[VariantDensityPyramid.Channel.DUP.ordinal()] =
        vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_DUPLICATION);
    visible[VariantDensityPyramid.Channel.CNV_GAIN.ordinal()] =
        vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_CNV_GAIN);
    visible[VariantDensityPyramid.Channel.INS.ordinal()] =
        vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_INSERTION);
    visible[VariantDensityPyramid.Channel.TRA.ordinal()] =
        vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_TRANSLOCATION);
    visible[VariantDensityPyramid.Channel.BND.ordinal()] =
        vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_BREAKEND);
    visible[VariantDensityPyramid.Channel.LOH_AA.ordinal()] =
        vcfManager.isCanvasTypeDrawn(VcfVariantType.LOH_AA);
    visible[VariantDensityPyramid.Channel.LOH_BB.ordinal()] =
        vcfManager.isCanvasTypeDrawn(VcfVariantType.LOH_BB);
    return visible;
  }

  private static int channelMaxInBins(int[] counts, int b0, int b1) {
    if (counts == null) {
      return 0;
    }
    int val = 0;
    for (int b = Math.max(0, b0); b <= Math.min(counts.length - 1, b1); b++) {
      val = Math.max(val, counts[b]);
    }
    return val;
  }

  /** Shared scale: max over visible groups of per-bin sum of visible channel counts. */
  private static int maxStackedGroupDensityInView(
      VariantDensityPyramid pyr,
      VariantDensityPyramid.Level level,
      int levelIndex,
      List<VariantDensityPyramid.GroupDensity> rows,
      org.baseplayer.analysis.AnalysisSession session,
      boolean[] visible,
      double viewStart,
      double viewEnd) {
    int max = 1;
    int b0 = pyr.genomicToBin(viewStart, level);
    int b1 = pyr.genomicToBin(viewEnd, level);
    if (b1 < b0) {
      int tmp = b0;
      b0 = b1;
      b1 = tmp;
    }
    for (VariantDensityPyramid.GroupDensity gd : rows) {
      if (!session.isAggregateGroupVisible(gd.groupId())) {
        continue;
      }
      for (int b = Math.max(0, b0); b <= b1; b++) {
        int sum = 0;
        for (VariantDensityPyramid.Channel ch : VariantDensityPyramid.Channel.values()) {
          if (!visible[ch.ordinal()]) {
            continue;
          }
          int[] counts = gd.channelCounts(levelIndex, ch);
          if (counts != null && b < counts.length) {
            sum += counts[b];
          }
        }
        max = Math.max(max, sum);
      }
    }
    return max;
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
      clusterHits = List.of();
      groupLabelHits = List.of();
      // Keep session type legends clickable even before any variants are cached.
      colorLegend.clear();
      drawDensityChrome(gc, canvasWidth, 2, Math.max(8, masterTrackHeight - 4), 1);
      return;
    }

    // Live per-allele bars once the finest pyramid bins (~1kb) would stretch
    // wider than ~2px — otherwise close zoom stays as large blocks. Pyramid
    // remains for chromosome / gene-far zoom. Precise path scales to the
    // peak per-allele sample count in the current view.
    double viewLength = Math.max(1.0, drawStack.getViewLength());
    double pxPerBp = canvasWidth / viewLength;
    long finestBinBp = VariantDensityPyramid.BIN_SIZES_BP[
        VariantDensityPyramid.BIN_SIZES_BP.length - 1];
    boolean zoomedInPrecise = pxPerBp * finestBinBp > 2.0;
    // Pyramid also feeds whole-chromosome group density curves — keep it warm
    // even at close zoom when comparison groups exist.
    if (!zoomedInPrecise || !ComparisonContext.analysisGroupIds(sampleRegistry).isEmpty()) {
      ensurePyramidAsync(drawStack, false);
    }

    colorLegend.clear();
    clusterHits = List.of();
    groupLabelHits = List.of();

    double areaH = masterTrackHeight - 4;
    VariantDensityPyramid pyr = this.pyramid;
    boolean hasCachedSpans = pyr != null && !pyr.svSpans().isEmpty();
    boolean hasLiveLohSpans = hasVisibleLohRegions(variants);
    double svH = (hasCachedSpans || hasLiveLohSpans) ? Math.min(10, areaH * 0.22) : 0;
    double densH = areaH - svH;
    boolean groupStacks = groupAggregateModeActive()
        && pyr != null
        && !pyr.groupDensities().isEmpty();

    VariantFilter activeFilter = org.baseplayer.io.VcfManager.getInstance().getCurrentFilter();
    // Cluster bands only at close zoom (same gate as precise density bars) —
    // at chrom / gene-far zoom they clutter the pyramid overview.
    List<ClusterHit> pendingClusterTags = zoomedInPrecise && !groupStacks
        ? drawClusterBackgrounds(
            gc, drawStack, canvasWidth, 2, densH, variants, activeFilter)
        : List.of();

    int scaleMax = 1;
    if (groupStacks) {
      // Leave a strip for the type legend above the equal-height group rows.
      double legendReserve = Math.min(18, densH * 0.22);
      double stacksTop = 2 + legendReserve;
      double stacksH = densH - legendReserve;
      scaleMax = drawGroupStackRows(gc, drawStack, canvasWidth, stacksTop, stacksH, pyr);
    } else if (zoomedInPrecise) {
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
    int groupsRev = sampleRegistry.sampleGroupsRevisionProperty().get();
    int displayedFp = 31 * VariantDensityPyramid.fingerprintDisplayedTracks(displayed) + groupsRev;
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
            buildFilterGen, buildChainGen, buildDisplayedFp, sampleRegistry);
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
   * @return scale max from the current view (peak per-allele sample count)
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

        windowMax[0] = Math.max(windowMax[0], count);
        // Mid precise zoom keeps type colors; ALT palette only when bases are wide enough.
        boolean byAlt = VariantTypeVisuals.paintByAltBase(pixelSize);
        Color fill = byAlt
            ? VariantTypeVisuals.allelePaintColor(node)
            : VariantTypeVisuals.color(node.type);
        String label = byAlt ? VariantTypeVisuals.allelePaintLabel(node) : null;
        bars.add(new PreciseBar(g0, x, w, count, node.type, fill, label));
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

      // Scale to the tallest bar in this view (same units as bar.count).
      // Do not use unique samples across all alleles — that inflates the
      // axis and flattens every individual allele bar.
      maxC = Math.max(1, windowMax[0]);

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

    // Stack alleles that share a genomic start so multi-allelic / multi-type
    // sites remain separable. Fill/label are type-colored until ALT close zoom.
    Map<Long, List<PreciseBar>> byPosition = new LinkedHashMap<>();
    for (PreciseBar bar : bars) {
      byPosition.computeIfAbsent(bar.position(), k -> new ArrayList<>()).add(bar);
    }

    double bottom = top + h;
    for (List<PreciseBar> stack : byPosition.values()) {
      double stackNeeded = 0;
      for (PreciseBar bar : stack) {
        double bh = maxBarH * (double) bar.count() / maxC;
        if (bh < 1) {
          bh = 1;
        }
        stackNeeded += bh;
      }
      double scale = stackNeeded > maxBarH ? maxBarH / stackNeeded : 1.0;
      double y = bottom;
      for (PreciseBar bar : stack) {
        double bh = maxBarH * (double) bar.count() / maxC;
        if (bh < 1) {
          bh = 1;
        }
        bh *= scale;
        y -= bh;
        Color c = bar.fill() != null ? bar.fill() : VariantTypeVisuals.color(bar.type());
        gc.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.72));
        gc.fillRect(bar.x(), y, bar.w(), bh);

        if (bar.label() != null
            && bar.w() >= 6
            && bh >= 8) {
          Font font = bar.w() >= 10 && bar.label().length() == 1
              ? AppFonts.getMonoBoldFont(11)
              : AppFonts.getMonoBoldFont(9);
          gc.setFont(font);
          gc.setFill(Color.WHITE);
          double textW = bar.label().length() == 1
              ? (bar.w() >= 10 ? 7 : 5.5)
              : Math.min(bar.w() - 2, 12);
          double textX = bar.x() + (bar.w() - textW) / 2;
          double textY = y + bh - (bh - (bar.w() >= 10 ? 10 : 8)) / 2;
          gc.fillText(bar.label(), textX, textY, Math.max(4, bar.w() - 2));
        }
      }
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
    boolean showDel = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_DELETION);
    boolean showCnvLoss = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_CNV_LOSS);
    boolean showInv = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_INVERSION);
    boolean showDup = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_DUPLICATION);
    boolean showCnvGain = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_CNV_GAIN);
    boolean showIns = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_INSERTION);
    boolean showTra = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_TRANSLOCATION);
    boolean showBnd = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_BREAKEND);
    boolean showLohAa = vcfManager.isCanvasTypeDrawn(VcfVariantType.LOH_AA);
    boolean showLohBb = vcfManager.isCanvasTypeDrawn(VcfVariantType.LOH_BB);

    boolean[] visible = new boolean[VariantDensityPyramid.Channel.values().length];
    visible[VariantDensityPyramid.Channel.SNV.ordinal()] = showSnv;
    visible[VariantDensityPyramid.Channel.INDEL.ordinal()] = showIndel;
    visible[VariantDensityPyramid.Channel.DEL.ordinal()] = showDel;
    visible[VariantDensityPyramid.Channel.CNV_LOSS.ordinal()] = showCnvLoss;
    visible[VariantDensityPyramid.Channel.INV.ordinal()] = showInv;
    visible[VariantDensityPyramid.Channel.DUP.ordinal()] = showDup;
    visible[VariantDensityPyramid.Channel.CNV_GAIN.ordinal()] = showCnvGain;
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
    int[] cnvLoss = level.channel(VariantDensityPyramid.Channel.CNV_LOSS);
    int[] inv = level.channel(VariantDensityPyramid.Channel.INV);
    int[] dup = level.channel(VariantDensityPyramid.Channel.DUP);
    int[] cnvGain = level.channel(VariantDensityPyramid.Channel.CNV_GAIN);
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

      int snvVal = 0, indelVal = 0, delVal = 0, cnvLossVal = 0, invVal = 0;
      int dupVal = 0, cnvGainVal = 0, insVal = 0, traVal = 0, bndVal = 0;
      int lohAaVal = 0, lohBbVal = 0;
      for (int b = b0; b <= b1; b++) {
        if (showSnv) snvVal = Math.max(snvVal, snv[b]);
        if (showIndel) indelVal = Math.max(indelVal, indel[b]);
        if (showDel) delVal = Math.max(delVal, del[b]);
        if (showCnvLoss) cnvLossVal = Math.max(cnvLossVal, cnvLoss[b]);
        if (showInv) invVal = Math.max(invVal, inv[b]);
        if (showDup) dupVal = Math.max(dupVal, dup[b]);
        if (showCnvGain) cnvGainVal = Math.max(cnvGainVal, cnvGain[b]);
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
      if (delVal > 0) bars.add(new BarData(delVal, VcfVariantType.SV_DELETION));
      if (cnvLossVal > 0) bars.add(new BarData(cnvLossVal, VcfVariantType.SV_CNV_LOSS));
      if (invVal > 0) bars.add(new BarData(invVal, VcfVariantType.SV_INVERSION));
      if (dupVal > 0) bars.add(new BarData(dupVal, VcfVariantType.SV_DUPLICATION));
      if (cnvGainVal > 0) bars.add(new BarData(cnvGainVal, VcfVariantType.SV_CNV_GAIN));
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

    // Color legends to the right of the scale (all-sample types; also filter group series).
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
