package org.baseplayer.components;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.samples.alignment.draw.CoverageDrawer;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.DrawColors;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfVariantType;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

public class MasterTrackCanvas {

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
  private volatile int densityMax = 1;
  private volatile boolean densityBusy = false;
  private volatile double densityCachedStart = -1;
  private volatile double densityCachedEnd = -1;
  private volatile int densityGeneration = 0;
  private volatile int densityFilterGeneration = -1;

  private record SvSpan(long start, long end, VcfVariantType type, int sampleCount) {
  }

  private volatile List<SvSpan> densitySvSpans = java.util.List.of();

  private volatile boolean wasZoomingLastFrame = false;

  public MasterTrackCanvas(CoverageDrawer coverageDrawer, Runnable redrawCallback) {
    this.sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    this.coverageDrawer = coverageDrawer;
    this.redrawCallback = redrawCallback;
  }

  public void setVariantList(VariantList variantList) {
    this.variantList = variantList;
  }

  public void clearVariantList() {
    this.variantList = null;
    densityCached = null;
    densitySnv = null;
    densityIndel = null;
    densityDel = null;
    densityInv = null;
    densityDup = null;
    densityIns = null;
    densityTra = null;
    densityBnd = null;
    densitySvSpans = java.util.List.of();
    densityCachedStart = -1;
    densityCachedEnd = -1;
    densityBusy = false;
  }

  public void forceCalculateDensity(DrawStack drawStack) {
    VariantList variants = this.variantList;
    if (variants != null && !variants.isEmpty()) {
      densityCached = null;
      densitySnv = null;
      densityIndel = null;
      densityDel = null;
      densityInv = null;
      densityDup = null;
      densityIns = null;
      densityTra = null;
      densityBnd = null;
      densitySvSpans = java.util.List.of();
      densityCachedStart = -1;
      densityCachedEnd = -1;
      densityBusy = false;
      triggerVariantDensityCompute(variants, drawStack);
    }
  }

  public void drawMasterAggregates(
      GraphicsContext gc,
      DrawStack drawStack,
      double canvasWidth,
      double masterTrackHeight) {
    drawVariantDensityOverview(gc, drawStack, canvasWidth, masterTrackHeight);
    renderMasterMethylation(gc, canvasWidth, masterTrackHeight);
    
    // Single separator line at the bottom
    gc.setStroke(DrawColors.COVERAGE_SEPARATOR);
    gc.setLineWidth(1.0);
    gc.strokeLine(0, masterTrackHeight, canvasWidth, masterTrackHeight);
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
        densitySnv = null;
        densityIndel = null;
        densityDel = null;
        densityInv = null;
        densityDup = null;
        densityIns = null;
        densityTra = null;
        densityBnd = null;
        densityCachedStart = -1;
        densityCachedEnd = -1;
        densityBusy = false;
      }
      return;
    }

    if (variants != densityCached) {
      densityBusy = false;
      densitySnv = null;
      densityIndel = null;
      densityDel = null;
      densityInv = null;
      densityDup = null;
      densityIns = null;
      densityTra = null;
      densityBnd = null;
      densitySvSpans = java.util.List.of();
      densityCachedStart = -1;
      densityCachedEnd = -1;
    }

    org.baseplayer.io.VcfManager vcfMgr = org.baseplayer.io.VcfManager.getInstance();
    int currentFilterGen = vcfMgr.getFilterGeneration();
    
    // Detect zoom state: line zoom (right-drag) or animation zoom (mouse wheel)
    boolean isZoomingNow = drawStack.nav.lineZoomerActive || drawStack.nav.animationRunning;
    
    // If zoom just ended, force cache invalidation for fresh recalculation
    boolean zoomJustEnded = wasZoomingLastFrame && !isZoomingNow;
    if (zoomJustEnded) {
      densityCached = null;
      densitySnv = null;
      densityIndel = null;
      densityDel = null;
      densityInv = null;
      densityDup = null;
      densityIns = null;
      densityTra = null;
      densityBnd = null;
      densitySvSpans = java.util.List.of();
      densityCachedStart = -1;
      densityCachedEnd = -1;
      densityBusy = false;
    }
    
    // Skip recalculation while zooming - just display existing graphics (they're visually stretched by zoom)
    // Only do cache checks when NOT actively zooming
    if (!isZoomingNow) {
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
    
    // Update zoom state for next frame
    wasZoomingLastFrame = isZoomingNow;

    double areaH = masterTrackHeight - 4;
    double svH = densitySvSpans.isEmpty() ? 0 : Math.min(10, areaH * 0.22);
    double densH = areaH - svH;

    boolean hasDensityData = densitySnv != null
        || densityIndel != null
        || densityDel != null
        || densityInv != null
        || densityDup != null
        || densityIns != null
        || densityTra != null
        || densityBnd != null;
    if (hasDensityData) {
      drawDensityBars(gc, drawStack, canvasWidth, 2, densH);
    }
    if (svH >= 4) {
      drawSvSpanBars(gc, drawStack, canvasWidth, 2 + densH, svH);
    }
  }

  private void triggerVariantDensityCompute(VariantList variants, DrawStack drawStack) {
    densityCached = variants;
    densityBusy = true;
    final int myGeneration = ++densityGeneration;

    if (variants == null) {
      densityDel = null;
      densityInv = null;
      densityDup = null;
      densityIns = null;
      densityTra = null;
      densityBnd = null;
      densitySvSpans = java.util.List.of();
      densityBusy = false;
      return;
    }

    final double viewStart = drawStack.getViewStart();
    final double viewEnd = drawStack.getViewEnd();
    final List<Integer> visibleTrackIndices = sampleRegistry.getDisplayedTrackIndices();
    final VariantFilter activeFilter = org.baseplayer.io.VcfManager.getInstance().getCurrentFilter();

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

        List<SvSpan> spans = new ArrayList<>();
        double viewLen = Math.max(1, viewEnd - viewStart);

        VariantNode node = variants.getFirst();
        while (node != null) {
          List<Integer> passingIndices = new ArrayList<>();
          for (int idx : visibleTrackIndices) {
            if (node.hasSample(idx) && activeFilter.passes(node, idx)) {
              passingIndices.add(idx);
            }
          }

          if (!passingIndices.isEmpty()) {
            boolean isSvWithSpan = node.svEnd > node.position
                && (node.type == VcfVariantType.SV_DELETION
                    || node.type == VcfVariantType.SV_INSERTION
                    || node.type == VcfVariantType.SV_DUPLICATION
                    || node.type == VcfVariantType.SV_INVERSION);

            if (isSvWithSpan && node.svEnd >= viewStart && node.position <= viewEnd) {
              long s = Math.max((long) viewStart, node.position);
              long e = Math.min((long) viewEnd, node.svEnd);
              int b0 = (int) Math.max(0,
                  Math.min(DENSITY_BINS - 1, (s - viewStart) * DENSITY_BINS / viewLen));
              int b1 = (int) Math.max(0,
                  Math.min(DENSITY_BINS - 1, (e - viewStart) * DENSITY_BINS / viewLen));

              java.util.Set<Integer>[] targetArray = switch (node.type) {
                case SV_DELETION -> delBySample;
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
          node = node.next;
        }

        int[] snv = new int[DENSITY_BINS];
        int[] indel = new int[DENSITY_BINS];
        int[] del = new int[DENSITY_BINS];
        int[] inv = new int[DENSITY_BINS];
        int[] dup = new int[DENSITY_BINS];
        int[] ins = new int[DENSITY_BINS];
        int[] tra = new int[DENSITY_BINS];
        int[] bnd = new int[DENSITY_BINS];

        for (int i = 0; i < DENSITY_BINS; i++) {
          if (snvBySample[i] != null) snv[i] = snvBySample[i].size();
          if (indelBySample[i] != null) indel[i] = indelBySample[i].size();
          if (delBySample[i] != null) del[i] = delBySample[i].size();
          if (invBySample[i] != null) inv[i] = invBySample[i].size();
          if (dupBySample[i] != null) dup[i] = dupBySample[i].size();
          if (insBySample[i] != null) ins[i] = insBySample[i].size();
          if (traBySample[i] != null) tra[i] = traBySample[i].size();
          if (bndBySample[i] != null) bnd[i] = bndBySample[i].size();
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
        }

        final int[] fSnv = snv;
        final int[] fIndel = indel;
        final int[] fDel = del;
        final int[] fInv = inv;
        final int[] fDup = dup;
        final int[] fIns = ins;
        final int[] fTra = tra;
        final int[] fBnd = bnd;
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
          densityMax = fMax;
          densitySvSpans = fSpans;
          densityBusy = false;
          densityCachedStart = viewStart;
          densityCachedEnd = viewEnd;

          double currentViewLen = Math.max(1.0, drawStack.getViewEnd() - drawStack.getViewStart());
          boolean viewportShifted = Math.abs(drawStack.getViewStart() - viewStart) > currentViewLen * 0.05
              || Math.abs(drawStack.getViewEnd() - viewEnd) > currentViewLen * 0.05;
          if (viewportShifted) {
            densitySnv = null;
            densityIndel = null;
            densityDel = null;
            densityInv = null;
            densityDup = null;
            densityIns = null;
            densityTra = null;
            densityBnd = null;
            densitySvSpans = java.util.List.of();
            triggerVariantDensityCompute(variantList, drawStack);
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

  private void drawDensityBars(
      GraphicsContext gc,
      DrawStack drawStack,
      double canvasWidth,
      double top,
      double h) {
    if (drawStack.getViewLength() <= 0) return;
    if (densityDel == null && densitySnv == null && densityIndel == null) return;

    double maxBarH = h - 1;
    int maxC = Math.max(1, densityMax);

    double cachedViewLength = densityCachedEnd - densityCachedStart;
    double currentViewLength = drawStack.getViewLength();
    
    // Apply zoom-aware scaling and translation (same as GenomicCanvas zoom preview)
    double scaleX = cachedViewLength / Math.max(1, currentViewLength);
    double translateX = (densityCachedStart - drawStack.getViewStart()) * (canvasWidth / Math.max(1, currentViewLength));

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

      record BarData(int value, String color, double alpha) {
      }
      List<BarData> bars = new ArrayList<>();
      if (snvVal > 0) bars.add(new BarData(snvVal, "#ff6666", 0.5));
      if (indelVal > 0) bars.add(new BarData(indelVal, "#ffaa44", 0.5));
      if (delVal > 0) bars.add(new BarData(delVal, "#00cc44", 0.6));
      if (invVal > 0) bars.add(new BarData(invVal, "#4488ff", 0.6));
      if (dupVal > 0) bars.add(new BarData(dupVal, "#c0c0d0", 0.6));
      if (insVal > 0) bars.add(new BarData(insVal, "#33cc66", 0.6));
      if (traVal > 0) bars.add(new BarData(traVal, "#ffdd00", 0.6));
      if (bndVal > 0) bars.add(new BarData(bndVal, "#c0c0c0", 0.6));

      bars.sort((a, b) -> Integer.compare(b.value, a.value));

      double bottom = top + h;
      for (BarData bar : bars) {
        double bh = maxBarH * (double) bar.value / maxC;
        gc.setFill(Color.web(bar.color, bar.alpha));
        gc.fillRect(px, bottom - bh, 1, bh);
      }
    }

    drawDensityScale(gc, canvasWidth - 38, top, h, maxC);

    gc.setFont(AppFonts.getFont("Segoe UI", 7));
    double legendX = 3;
    double legendY = top + 3;

    if (densitySnv != null) {
      gc.setFill(Color.web("#ff6666", 0.7));
      gc.fillRect(legendX, legendY, 5, 3);
      gc.setFill(Color.web("#444"));
      gc.fillText("SNV", legendX + 7, legendY + 5);
      legendX += 32;
    }
    if (densityIndel != null) {
      gc.setFill(Color.web("#ffaa44", 0.7));
      gc.fillRect(legendX, legendY, 5, 3);
      gc.setFill(Color.web("#444"));
      gc.fillText("indel", legendX + 7, legendY + 5);
      legendX += 36;
    }
    if (densityDel != null) {
      gc.setFill(Color.web("#00cc44", 0.7));
      gc.fillRect(legendX, legendY, 5, 3);
      gc.setFill(Color.web("#444"));
      gc.fillText("DEL", legendX + 7, legendY + 5);
    }
  }

  private void drawDensityScale(GraphicsContext gc, double x, double top, double h, int maxCount) {
    gc.setFont(AppFonts.getFont("Segoe UI", 7));
    gc.setFill(Color.web("#555"));
    gc.setTextBaseline(javafx.geometry.VPos.CENTER);

    gc.fillText(String.valueOf(maxCount), x + 2, top + 4);
    gc.fillText("0", x + 2, top + h - 2);

    gc.setStroke(Color.web("#777"));
    gc.setLineWidth(0.5);
    for (int i = 0; i <= 4; i++) {
      double y = top + (i * h / 4.0);
      gc.strokeLine(x, y, x + 3, y);
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
    for (SvSpan span : spans) {
      if (span.end() < viewStart || span.start() > viewStart + viewLen) continue;
      double x1 = Math.max(0, (span.start() - viewStart) / viewLen * canvasWidth);
      double x2 = Math.min(canvasWidth, (span.end() - viewStart) / viewLen * canvasWidth);
      if (x2 - x1 < 1.5) x2 = x1 + 1.5;
      double alpha = Math.min(0.70, 0.20 + span.sampleCount() * 0.12);

      if (span.type() == VcfVariantType.SV_TRANSLOCATION) {
        gc.setStroke(Color.web("#ffdd00", alpha));
        gc.setLineWidth(2.0);
        gc.strokeLine(x1, barY + barH / 2, x2, barY + barH / 2);
      } else if (span.type() == VcfVariantType.SV_BREAKEND) {
        gc.setStroke(Color.web("#c0c0c0", alpha));
        gc.setLineWidth(2.0);
        gc.strokeLine(x1, barY + barH / 2, x2, barY + barH / 2);
      } else {
        Color c = switch (span.type()) {
          case SV_DELETION -> Color.web("#00cc44", alpha);
          case SV_INVERSION -> Color.web("#4488ff", alpha);
          case SV_DUPLICATION -> Color.web("#c0c0d0", alpha);
          case SV_INSERTION -> Color.web("#33cc66", alpha);
          default -> Color.web("#aaaaaa", alpha);
        };
        gc.setFill(c);
        gc.fillRect(x1, barY, x2 - x1, barH);
      }
    }

    gc.setFont(AppFonts.getFont("Segoe UI", 7));
    double legendX = canvasWidth - 200;
    double legendY = top + h - 2;

    if (legendX > 100) {
      gc.setFill(Color.web("#00cc44", 0.6));
      gc.fillRect(legendX, legendY - 5, 8, 4);
      gc.setFill(Color.web("#777"));
      gc.fillText("DEL", legendX + 10, legendY);

      gc.setFill(Color.web("#4488ff", 0.6));
      gc.fillRect(legendX + 35, legendY - 5, 8, 4);
      gc.setFill(Color.web("#777"));
      gc.fillText("INV", legendX + 45, legendY);

      gc.setFill(Color.web("#c0c0d0", 0.7));
      gc.fillRect(legendX + 70, legendY - 5, 8, 4);
      gc.setFill(Color.web("#777"));
      gc.fillText("DUP", legendX + 80, legendY);

      gc.setFill(Color.web("#33cc66", 0.6));
      gc.fillRect(legendX + 110, legendY - 5, 8, 4);
      gc.setFill(Color.web("#777"));
      gc.fillText("INS", legendX + 120, legendY);

      gc.setStroke(Color.web("#ffdd00", 0.7));
      gc.setLineWidth(2.0);
      gc.strokeLine(legendX + 150, legendY - 3, legendX + 158, legendY - 3);
      gc.setFill(Color.web("#777"));
      gc.fillText("TRA", legendX + 160, legendY);
    }
  }
}
