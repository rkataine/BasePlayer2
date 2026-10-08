package org.baseplayer.components;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.samples.alignment.draw.CoverageDrawer;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.DrawColors;
import org.baseplayer.variant.VariantDrawSeek;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

public class MasterTrackPainter {

  private static final int DENSITY_BINS = 600;

  private final SampleRegistry sampleRegistry;
  private final CoverageDrawer coverageDrawer;
  private final Runnable redrawCallback;

  private volatile VariantList variantList;

  private volatile VariantList densityCached = null;
  private volatile int[] densitySnv;
  private volatile int[] densityIndel;
  private volatile int[] densityDel;
  private volatile int[] densityInv;
  private volatile int[] densityDup;
  private volatile int[] densityIns;
  private volatile int[] densityTra;
  private volatile int[] densityBnd;
  private volatile int[] densityLohAa;
  private volatile int[] densityLohBb;
  private volatile int densityMax = 1;
  private volatile boolean densityBusy = false;
  private volatile double densityCachedStart = -1;
  private volatile double densityCachedEnd = -1;
  private volatile int densityGeneration = 0;
  private volatile int densityFilterGeneration = -1;

  private record SvSpan(long start, long end, VcfVariantType type, int sampleCount) {
  }

  private volatile List<SvSpan> densitySvSpans = java.util.List.of();

  private record LegendHit(double x, double y, double w, double h, VcfVariantType displayType) {
    boolean contains(double px, double py) {
      return px >= x && px <= x + w && py >= y && py <= y + h;
    }
  }

  private final List<LegendHit> legendHits = new ArrayList<>();

  /** True while zoom or pan was deferring density last paint frame. */
  private volatile boolean wasDeferringDensityLastFrame = false;

  public MasterTrackPainter(CoverageDrawer coverageDrawer, Runnable redrawCallback) {
    this.sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    this.coverageDrawer = coverageDrawer;
    this.redrawCallback = redrawCallback;
  }

  public void setVariantList(VariantList variantList) {
    this.variantList = variantList;
  }

  public void clearVariantList() {
    this.variantList = null;
    clearDensityArrays();
    densityCached = null;
    densityCachedStart = -1;
    densityCachedEnd = -1;
    densityBusy = false;
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

  private void clearDensityArrays() {
    densitySnv = null;
    densityIndel = null;
    densityDel = null;
    densityInv = null;
    densityDup = null;
    densityIns = null;
    densityTra = null;
    densityBnd = null;
    densityLohAa = null;
    densityLohBb = null;
    densitySvSpans = java.util.List.of();
  }

  public void forceCalculateDensity(DrawStack drawStack) {
    VariantList variants = this.variantList;
    if (variants != null && !variants.isEmpty()) {
      densityCached = null;
      clearDensityArrays();
      densityCachedStart = -1;
      densityCachedEnd = -1;
      densityBusy = false;
      triggerVariantDensityCompute(variants, drawStack);
    }
  }

  /**
   * Legend click: toggle canvas visibility for types already in the cache;
   * for types not materialized under the loaded filter, prompt to reload and
   * select them in Variant Manager.
   */
  public boolean handleLegendClick(double x, double y) {
    LegendHit hit = null;
    for (LegendHit candidate : legendHits) {
      if (candidate.contains(x, y)) {
        hit = candidate;
        break;
      }
    }
    if (hit == null) {
      return false;
    }

    Set<VcfVariantType> present = legendTypeUniverse();
    Set<VcfVariantType> linked = VariantTypeVisuals.linkedTypes(hit.displayType(), present);
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
    for (LegendHit hit : legendHits) {
      if (hit.contains(x, y)) {
        return true;
      }
    }
    return false;
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
      if (densityCached != null) {
        densityCached = null;
        clearDensityArrays();
        densityCachedStart = -1;
        densityCachedEnd = -1;
        densityBusy = false;
      }
      legendHits.clear();
      return;
    }

    if (variants != densityCached) {
      densityBusy = false;
      clearDensityArrays();
      densityCachedStart = -1;
      densityCachedEnd = -1;
    }

    org.baseplayer.io.VcfManager vcfMgr = org.baseplayer.io.VcfManager.getInstance();
    int currentFilterGen = vcfMgr.getFilterGeneration();
    
    // Defer bin recompute while zooming/panning, or when zoomed in (precise path
    // paints live alleles and does not need the coarse bin cache).
    boolean isZoomingNow = drawStack.nav.lineZoomerActive || drawStack.nav.animationRunning;
    boolean zoomedInPrecise = drawStack.getPixelSize() > 1.0;
    boolean deferDensity = isZoomingNow || drawStack.nav.navigating || zoomedInPrecise;
    
    // When interaction ends, invalidate cache coords so the next frame recomputes,
    // but keep last density arrays so the master band does not flash blank.
    boolean interactionJustEnded = wasDeferringDensityLastFrame && !deferDensity;
    if (interactionJustEnded) {
      densityCachedStart = -1;
      densityCachedEnd = -1;
      densityBusy = false;
    }
    
    if (!deferDensity) {
      boolean filterChanged = currentFilterGen != densityFilterGeneration;
      boolean viewChanged = densityCachedStart < 0
          || densityCachedEnd < 0
          || Math.abs(drawStack.getViewStart() - densityCachedStart) > drawStack.getViewLength() * 0.1
          || Math.abs(drawStack.getViewEnd() - densityCachedEnd) > drawStack.getViewLength() * 0.1;
      
      if ((variants != densityCached || viewChanged || filterChanged) && !densityBusy) {
        densityFilterGeneration = currentFilterGen;
        triggerVariantDensityCompute(variants, drawStack);
      }
    }
    
    wasDeferringDensityLastFrame = deferDensity;

    legendHits.clear();

    double areaH = masterTrackHeight - 4;
    boolean hasCachedSpans = !densitySvSpans.isEmpty();
    boolean hasLiveLohSpans = hasVisibleLohRegions(variants);
    double svH = (hasCachedSpans || hasLiveLohSpans) ? Math.min(10, areaH * 0.22) : 0;
    double densH = areaH - svH;

    int scaleMax = Math.max(1, densityMax);
    double pixelSize = drawStack.getPixelSize();
    if (pixelSize > 1.0) {
      // Zoomed in: paint exact allele-aligned bars from the live filter chain.
      scaleMax = drawPreciseDensityBars(
          gc, drawStack, canvasWidth, 2, densH, variants,
          org.baseplayer.io.VcfManager.getInstance().getCurrentFilter());
    } else {
      boolean hasDensityData = densitySnv != null
          || densityIndel != null
          || densityDel != null
          || densityInv != null
          || densityDup != null
          || densityIns != null
          || densityTra != null
          || densityBnd != null
          || densityLohAa != null
          || densityLohBb != null;
      if (hasDensityData) {
        drawDensityBars(gc, drawStack, canvasWidth, 2, densH);
      }
    }
    if (svH >= 4) {
      // Cached SV spans + live LOH region bars (LOH is rebuilt with the filter chain
      // and must not wait on deferred density while zoomed in).
      drawSvSpanBars(gc, drawStack, canvasWidth, 2 + densH, svH);
      drawLohRegionBars(gc, drawStack, canvasWidth, 2 + densH, svH, variants);
    }

    drawDensityChrome(gc, canvasWidth, 2, densH > 0 ? densH : areaH, scaleMax);
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

  private void triggerVariantDensityCompute(VariantList variants, DrawStack drawStack) {
    densityCached = variants;
    densityBusy = true;
    final int myGeneration = ++densityGeneration;

    if (variants == null) {
      clearDensityArrays();
      densityBusy = false;
      return;
    }

    final double viewStart = drawStack.getViewStart();
    final double viewEnd = drawStack.getViewEnd();
    final List<Integer> aggregateTrackIndices = sampleRegistry.getDisplayedTrackIndices();
    final VariantFilter activeFilter = org.baseplayer.io.VcfManager.getInstance().getCurrentFilter();

    // Snapshot displayed track identity → live index once (no indexOf in the per-node loop).
    final Map<SampleTrack, Integer> displayedTrackToIndex = new IdentityHashMap<>(
        Math.max(16, aggregateTrackIndices.size() * 2));
    List<SampleTrack> allTracks = sampleRegistry.getSampleTracks();
    for (int idx : aggregateTrackIndices) {
      if (idx < 0 || idx >= allTracks.size()) {
        continue;
      }
      SampleTrack track = allTracks.get(idx);
      if (track != null) {
        displayedTrackToIndex.put(track, idx);
      }
    }

    Thread t = new Thread(() -> {
      try {
        @SuppressWarnings("unchecked")
        java.util.Set<Integer>[] snvBySample = new java.util.HashSet[DENSITY_BINS];
        @SuppressWarnings("unchecked")
        java.util.Set<Integer>[] indelBySample = new java.util.HashSet[DENSITY_BINS];
        @SuppressWarnings("unchecked")
        java.util.Set<Integer>[] delBySample = new java.util.HashSet[DENSITY_BINS];
        @SuppressWarnings("unchecked")
        java.util.Set<Integer>[] invBySample = new java.util.HashSet[DENSITY_BINS];
        @SuppressWarnings("unchecked")
        java.util.Set<Integer>[] dupBySample = new java.util.HashSet[DENSITY_BINS];
        @SuppressWarnings("unchecked")
        java.util.Set<Integer>[] insBySample = new java.util.HashSet[DENSITY_BINS];
        @SuppressWarnings("unchecked")
        java.util.Set<Integer>[] traBySample = new java.util.HashSet[DENSITY_BINS];
        @SuppressWarnings("unchecked")
        java.util.Set<Integer>[] bndBySample = new java.util.HashSet[DENSITY_BINS];
        @SuppressWarnings("unchecked")
        java.util.Set<Integer>[] lohAaBySample = new java.util.HashSet[DENSITY_BINS];
        @SuppressWarnings("unchecked")
        java.util.Set<Integer>[] lohBbBySample = new java.util.HashSet[DENSITY_BINS];

        List<SvSpan> spans = new ArrayList<>();
        double viewLen = Math.max(1, viewEnd - viewStart);

        variants.ensureVisibleChain(activeFilter);

        // Upstream SV spans that overlap the view.
        for (VariantNode node : variants.getVisibleSvByPosition()) {
          if (node.position >= viewStart) {
            break;
          }
          if (node.svEnd < viewStart || node.position > viewEnd) {
            continue;
          }
          accumulateDensityNode(
              variants, node, activeFilter, displayedTrackToIndex, viewStart, viewEnd, viewLen,
              snvBySample, indelBySample, delBySample, invBySample, dupBySample,
              insBySample, traBySample, bndBySample, lohAaBySample, lohBbBySample, spans, true);
        }

        // Synthetic LOH AA/BB region spans (outside the VCF linked list).
        for (VariantNode node : variants.getLohRegions()) {
          if (node == null || !VariantTypeVisuals.isLohRegion(node.type)) {
            continue;
          }
          if (node.svEnd < viewStart || node.position > viewEnd) {
            continue;
          }
          accumulateDensityNode(
              variants, node, activeFilter, displayedTrackToIndex, viewStart, viewEnd, viewLen,
              snvBySample, indelBySample, delBySample, invBySample, dupBySample,
              insBySample, traBySample, bndBySample, lohAaBySample, lohBbBySample, spans, true);
        }

        // Thread-local seek: density runs off the FX thread and must not share drawer state.
        VariantDrawSeek densitySeek = new VariantDrawSeek();
        VariantNode node = densitySeek.seek(variants, (long) viewStart);
        while (node != null && node.position <= viewEnd) {
          accumulateDensityNode(
              variants, node, activeFilter, displayedTrackToIndex, viewStart, viewEnd, viewLen,
              snvBySample, indelBySample, delBySample, invBySample, dupBySample,
              insBySample, traBySample, bndBySample, lohAaBySample, lohBbBySample, spans, false);
          node = node.nextVisible;
        }

        int[] snv = new int[DENSITY_BINS];
        int[] indel = new int[DENSITY_BINS];
        int[] del = new int[DENSITY_BINS];
        int[] inv = new int[DENSITY_BINS];
        int[] dup = new int[DENSITY_BINS];
        int[] ins = new int[DENSITY_BINS];
        int[] tra = new int[DENSITY_BINS];
        int[] bnd = new int[DENSITY_BINS];
        int[] lohAa = new int[DENSITY_BINS];
        int[] lohBb = new int[DENSITY_BINS];

        for (int i = 0; i < DENSITY_BINS; i++) {
          if (snvBySample[i] != null) snv[i] = snvBySample[i].size();
          if (indelBySample[i] != null) indel[i] = indelBySample[i].size();
          if (delBySample[i] != null) del[i] = delBySample[i].size();
          if (invBySample[i] != null) inv[i] = invBySample[i].size();
          if (dupBySample[i] != null) dup[i] = dupBySample[i].size();
          if (insBySample[i] != null) ins[i] = insBySample[i].size();
          if (traBySample[i] != null) tra[i] = traBySample[i].size();
          if (bndBySample[i] != null) bnd[i] = bndBySample[i].size();
          if (lohAaBySample[i] != null) lohAa[i] = lohAaBySample[i].size();
          if (lohBbBySample[i] != null) lohBb[i] = lohBbBySample[i].size();
        }

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
          maxC = Math.max(maxC, lohAa[i]);
          maxC = Math.max(maxC, lohBb[i]);
        }

        final int[] fSnv = snv;
        final int[] fIndel = indel;
        final int[] fDel = del;
        final int[] fInv = inv;
        final int[] fDup = dup;
        final int[] fIns = ins;
        final int[] fTra = tra;
        final int[] fBnd = bnd;
        final int[] fLohAa = lohAa;
        final int[] fLohBb = lohBb;
        final int fMax = maxC;
        final List<SvSpan> fSpans = spans;

        Platform.runLater(() -> {
          if (myGeneration != densityGeneration) {
            return;
          }
          densitySnv = fSnv;
          densityIndel = fIndel;
          densityDel = fDel;
          densityInv = fInv;
          densityDup = fDup;
          densityIns = fIns;
          densityTra = fTra;
          densityBnd = fBnd;
          densityLohAa = fLohAa;
          densityLohBb = fLohBb;
          densityMax = fMax;
          densitySvSpans = fSpans;
          densityBusy = false;
          densityCachedStart = viewStart;
          densityCachedEnd = viewEnd;

          double currentViewLen = Math.max(1.0, drawStack.getViewEnd() - drawStack.getViewStart());
          boolean viewportShifted = Math.abs(drawStack.getViewStart() - viewStart) > currentViewLen * 0.05
              || Math.abs(drawStack.getViewEnd() - viewEnd) > currentViewLen * 0.05;
          if (viewportShifted) {
            // Keep the just-published bars (stretch-rendered) and schedule one recompute
            // unless the user is still navigating/zooming.
            boolean stillDeferring = drawStack.nav.navigating
                || drawStack.nav.lineZoomerActive
                || drawStack.nav.animationRunning;
            if (!stillDeferring && variantList != null && !variantList.isEmpty()) {
              densityCachedStart = -1;
              densityCachedEnd = -1;
              triggerVariantDensityCompute(variantList, drawStack);
            }
            redrawCallback.run();
            return;
          }

          redrawCallback.run();
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

  private void accumulateDensityNode(
      VariantList variants,
      VariantNode node,
      VariantFilter activeFilter,
      Map<SampleTrack, Integer> displayedTrackToIndex,
      double viewStart,
      double viewEnd,
      double viewLen,
      java.util.Set<Integer>[] snvBySample,
      java.util.Set<Integer>[] indelBySample,
      java.util.Set<Integer>[] delBySample,
      java.util.Set<Integer>[] invBySample,
      java.util.Set<Integer>[] dupBySample,
      java.util.Set<Integer>[] insBySample,
      java.util.Set<Integer>[] traBySample,
      java.util.Set<Integer>[] bndBySample,
      java.util.Set<Integer>[] lohAaBySample,
      java.util.Set<Integer>[] lohBbBySample,
      List<SvSpan> spans,
      boolean treatAsSvSpan) {
    List<Integer> passingIndices = new ArrayList<>();
    int chainGen = variants.getVisibleChainGeneration();
    boolean useCache = node.hasDisplayCache(chainGen);
    for (Map.Entry<SampleTrack, Integer> entry : displayedTrackToIndex.entrySet()) {
      SampleTrack track = entry.getKey();
      VariantNode.SampleCall call = useCache
          ? node.getDisplayCall(track, chainGen)
          : variants.getDisplayCall(node, track, activeFilter);
      // LOH AA regions are homozygous REF by definition — still count them.
      if (call == null
          || (node.isHomozygousRef(call) && !VariantTypeVisuals.isLohRegion(node.type))) {
        continue;
      }
      passingIndices.add(entry.getValue());
    }
    if (passingIndices.isEmpty()) {
      return;
    }

    boolean isSvWithSpan = treatAsSvSpan
        || (node.svEnd > node.position
            && (node.type == VcfVariantType.SV_DELETION
                || node.type == VcfVariantType.SV_INSERTION
                || node.type == VcfVariantType.SV_DUPLICATION
                || node.type == VcfVariantType.SV_INVERSION
                || VariantTypeVisuals.isLohRegion(node.type)));

    if (isSvWithSpan && node.svEnd >= viewStart && node.position <= viewEnd) {
      long s = Math.max((long) viewStart, node.position);
      long e = Math.min((long) viewEnd, node.svEnd);
      int b0 = (int) Math.max(0,
          Math.min(DENSITY_BINS - 1, (s - viewStart) * DENSITY_BINS / viewLen));
      int b1 = (int) Math.max(0,
          Math.min(DENSITY_BINS - 1, (e - viewStart) * DENSITY_BINS / viewLen));

      java.util.Set<Integer>[] targetArray = switch (node.type) {
        case SV_DELETION -> delBySample;
        case LOH_AA -> lohAaBySample;
        case LOH_BB -> lohBbBySample;
        case SV_INVERSION -> invBySample;
        case SV_DUPLICATION -> dupBySample;
        case SV_INSERTION -> insBySample;
        default -> delBySample;
      };

      for (int b = b0; b <= b1; b++) {
        if (targetArray[b] == null) {
          targetArray[b] = new java.util.HashSet<>();
        }
        targetArray[b].addAll(passingIndices);
      }
      spans.add(new SvSpan(node.position, node.svEnd, node.type, passingIndices.size()));
    } else if (node.position >= viewStart && node.position <= viewEnd) {
      int bin = (int) Math.max(0,
          Math.min(DENSITY_BINS - 1, (node.position - viewStart) * DENSITY_BINS / viewLen));

      switch (node.type) {
        case SNV -> {
          if (snvBySample[bin] == null) {
            snvBySample[bin] = new java.util.HashSet<>();
          }
          snvBySample[bin].addAll(passingIndices);
        }
        case INSERTION, DELETION, MNV -> {
          if (indelBySample[bin] == null) {
            indelBySample[bin] = new java.util.HashSet<>();
          }
          indelBySample[bin].addAll(passingIndices);
        }
        case SV_TRANSLOCATION -> {
          if (traBySample[bin] == null) {
            traBySample[bin] = new java.util.HashSet<>();
          }
          traBySample[bin].addAll(passingIndices);
        }
        case SV_BREAKEND -> {
          if (bndBySample[bin] == null) {
            bndBySample[bin] = new java.util.HashSet<>();
          }
          bndBySample[bin].addAll(passingIndices);
        }
        default -> {
        }
      }
    }
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
    int maxC = Math.max(1, displayedTrackToIndex.size());

    variants.ensureVisibleChain(activeFilter);

    record PreciseBar(double x, double w, int count, VcfVariantType type) {}
    List<PreciseBar> bars = new ArrayList<>();

    int chainGen = variants.getVisibleChainGeneration();
    VcfManager vcfManager = VcfManager.getInstance();
    Set<VcfVariantType> legendTypes = vcfManager.getSessionAvailableTypes();
    java.util.function.Consumer<VariantNode> collect = node -> {
      if (!vcfManager.isCanvasTypeDrawn(node.type, legendTypes)) {
        return;
      }
      int count = 0;
      boolean useCache = node.hasDisplayCache(chainGen);
      for (SampleTrack track : displayedTrackToIndex.keySet()) {
        VariantNode.SampleCall call = useCache
            ? node.getDisplayCall(track, chainGen)
            : variants.getDisplayCall(node, track, activeFilter);
        if (call == null
            || (node.isHomozygousRef(call) && !VariantTypeVisuals.isLohRegion(node.type))) {
          continue;
        }
        count++;
      }
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

      bars.add(new PreciseBar(x, w, count, node.type));
    };

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

  private void drawDensityBars(
      GraphicsContext gc,
      DrawStack drawStack,
      double canvasWidth,
      double top,
      double h) {
    if (drawStack.getViewLength() <= 0) return;
    if (densityDel == null && densitySnv == null && densityIndel == null
        && densityInv == null && densityDup == null && densityIns == null
        && densityTra == null && densityBnd == null
        && densityLohAa == null && densityLohBb == null) {
      return;
    }

    double maxBarH = h - 1;
    int maxC = Math.max(1, densityMax);

    double cachedViewLength = densityCachedEnd - densityCachedStart;
    double currentViewLength = drawStack.getViewLength();

    // Apply zoom-aware scaling and translation (same as GenomicCanvas zoom preview)
    double scaleX = cachedViewLength / Math.max(1, currentViewLength);
    double translateX = (densityCachedStart - drawStack.getViewStart()) * (canvasWidth / Math.max(1, currentViewLength));

    VcfManager vcfManager = VcfManager.getInstance();
    // Use isCanvasTypeDrawn (legend membership + not hidden). The indel density bin is
    // shared across INS/DEL/MNV/COMPLEX — default-visible types not in the legend must
    // not keep the bucket painting after every legend swatch is deselected.
    boolean showSnv = vcfManager.isCanvasTypeDrawn(VcfVariantType.SNV);
    boolean showIndel = vcfManager.isCanvasTypeDrawn(VcfVariantType.INSERTION)
        || vcfManager.isCanvasTypeDrawn(VcfVariantType.DELETION)
        || vcfManager.isCanvasTypeDrawn(VcfVariantType.MNV)
        || vcfManager.isCanvasTypeDrawn(VcfVariantType.COMPLEX);
    boolean showDel = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_DELETION);
    boolean showInv = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_INVERSION);
    boolean showDup = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_DUPLICATION);
    boolean showIns = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_INSERTION);
    boolean showTra = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_TRANSLOCATION);
    boolean showBnd = vcfManager.isCanvasTypeDrawn(VcfVariantType.SV_BREAKEND);
    boolean showLohAa = vcfManager.isCanvasTypeDrawn(VcfVariantType.LOH_AA);
    boolean showLohBb = vcfManager.isCanvasTypeDrawn(VcfVariantType.LOH_BB);

    for (int px = 0; px < (int) canvasWidth; px++) {
      // Map screen pixel to cached coordinate space, accounting for both scale and translation
      double cachedPx = (px - translateX) / scaleX;

      int b0 = (int) (cachedPx * DENSITY_BINS / canvasWidth);
      int b1 = (int) ((cachedPx + 1) * DENSITY_BINS / canvasWidth);

      if (b0 < 0 || b1 >= DENSITY_BINS) continue;
      b0 = Math.max(0, Math.min(DENSITY_BINS - 1, b0));
      b1 = Math.max(0, Math.min(DENSITY_BINS - 1, b1));

      int snvVal = 0;
      int indelVal = 0;
      int delVal = 0;
      int invVal = 0;
      int dupVal = 0;
      int insVal = 0;
      int traVal = 0;
      int bndVal = 0;
      int lohAaVal = 0;
      int lohBbVal = 0;

      for (int b = b0; b <= b1; b++) {
        if (showSnv && densitySnv != null) snvVal = Math.max(snvVal, densitySnv[b]);
        if (showIndel && densityIndel != null) indelVal = Math.max(indelVal, densityIndel[b]);
        if (showDel && densityDel != null) delVal = Math.max(delVal, densityDel[b]);
        if (showInv && densityInv != null) invVal = Math.max(invVal, densityInv[b]);
        if (showDup && densityDup != null) dupVal = Math.max(dupVal, densityDup[b]);
        if (showIns && densityIns != null) insVal = Math.max(insVal, densityIns[b]);
        if (showTra && densityTra != null) traVal = Math.max(traVal, densityTra[b]);
        if (showBnd && densityBnd != null) bndVal = Math.max(bndVal, densityBnd[b]);
        if (showLohAa && densityLohAa != null) lohAaVal = Math.max(lohAaVal, densityLohAa[b]);
        if (showLohBb && densityLohBb != null) lohBbVal = Math.max(lohBbVal, densityLohBb[b]);
      }

      record BarData(int value, VcfVariantType type) {
      }
      List<BarData> bars = new ArrayList<>();
      if (snvVal > 0) bars.add(new BarData(snvVal, VcfVariantType.SNV));
      if (indelVal > 0) bars.add(new BarData(indelVal, VcfVariantType.DELETION));
      if (delVal > 0) bars.add(new BarData(delVal, VcfVariantType.SV_DELETION));
      if (invVal > 0) bars.add(new BarData(invVal, VcfVariantType.SV_INVERSION));
      if (dupVal > 0) bars.add(new BarData(dupVal, VcfVariantType.SV_DUPLICATION));
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
    var canvas = org.baseplayer.ui.theme.AppTheme.canvas();
    Color scaleBg = Color.color(
        chrome.elevated().getRed(),
        chrome.elevated().getGreen(),
        chrome.elevated().getBlue(),
        org.baseplayer.ui.theme.AppTheme.isDark() ? 0.55 : 0.92);
    gc.setFill(scaleBg);
    gc.fillRoundRect(scaleX - 1, top - 1, scaleW + 2, h + 2, 4, 4);

    gc.setFont(AppFonts.getFont("Segoe UI", 10));
    gc.setFill(canvas.axisInk());
    gc.setTextBaseline(javafx.geometry.VPos.TOP);
    gc.fillText(String.valueOf(Math.max(1, maxCount)), scaleX + 3, top + 1);
    gc.setTextBaseline(javafx.geometry.VPos.BOTTOM);
    gc.fillText("0", scaleX + 3, top + h - 1);

    gc.setStroke(chrome.stroke());
    gc.setLineWidth(1.0);
    double axisX = scaleX + scaleW - 4;
    gc.strokeLine(axisX, top + 1, axisX, top + h - 1);
    for (int i = 0; i <= 4; i++) {
      double y = top + (i * h / 4.0);
      gc.strokeLine(axisX - 4, y, axisX, y);
    }

    // Color legends to the right of the scale
    gc.setFont(AppFonts.getFont("Segoe UI", 11));
    gc.setTextBaseline(javafx.geometry.VPos.TOP);
    double legendX = scaleX + scaleW + 8;
    double legendY = top + 2;
    double swatchW = 12;
    double swatchH = 10;
    double gap = 10;
    double rowH = 16;

    for (VcfVariantType type : legendTypes) {
      Set<VcfVariantType> linked = VariantTypeVisuals.linkedTypes(type, present);
      boolean materialized = linked.stream().anyMatch(vcfManager::isTypeMaterializedInCache);
      boolean canvasOn = linked.stream().anyMatch(vcfManager::isCanvasTypeVisible);
      // Loaded + canvas-visible = full swatch; not loaded or canvas-hidden = muted.
      boolean enabled = materialized && canvasOn;
      String label = VariantTypeVisuals.shortLabel(type);

      double textW = Math.max(22, label.length() * 7.2);
      double itemW = swatchW + 5 + textW;

      if (legendX + itemW > canvasWidth - 4) {
        legendX = scaleX + scaleW + 8;
        legendY += rowH;
        if (legendY + swatchH > top + h) {
          break;
        }
      }

      Color typeColor = VariantTypeVisuals.color(type);
      double alpha = enabled ? 0.95 : 0.28;
      gc.setFill(Color.color(typeColor.getRed(), typeColor.getGreen(), typeColor.getBlue(), alpha));
      gc.fillRoundRect(legendX, legendY, swatchW, swatchH, 2, 2);
      if (!enabled) {
        gc.setStroke(chrome.muted());
        gc.setLineWidth(1.2);
        gc.strokeLine(legendX + 1, legendY + swatchH - 1, legendX + swatchW - 1, legendY + 1);
      }

      gc.setFill(enabled ? canvas.axisInk() : chrome.muted());
      gc.fillText(label, legendX + swatchW + 4, legendY - 1);

      legendHits.add(new LegendHit(legendX - 2, legendY - 2, itemW + 4, swatchH + 4, type));
      legendX += itemW + gap;
    }
  }

  private void drawSvSpanBars(
      GraphicsContext gc,
      DrawStack drawStack,
      double canvasWidth,
      double top,
      double h) {
    List<SvSpan> spans = densitySvSpans;
    double viewStart = drawStack.getViewStart();
    double viewLen = drawStack.getViewLength();
    if (viewLen <= 0 || spans.isEmpty()) return;

    double barY = top + 1;
    double barH = Math.max(2, h - 3);
    VcfManager vcfManager = VcfManager.getInstance();
    Set<VcfVariantType> legendTypes = vcfManager.getSessionAvailableTypes();
    for (SvSpan span : spans) {
      // LOH spans are painted live via {@link #drawLohRegionBars} so legend toggles
      // and zoomed-in views stay in sync without waiting on density recompute.
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
    int chainGen = variants.getVisibleChainGeneration();
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

      int sampleCount = 0;
      boolean useCache = node.hasDisplayCache(chainGen);
      for (SampleTrack track : displayedTracks.keySet()) {
        VariantNode.SampleCall call = useCache
            ? node.getDisplayCall(track, chainGen)
            : variants.getDisplayCall(node, track, filter);
        if (call == null) {
          continue;
        }
        sampleCount++;
      }
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
