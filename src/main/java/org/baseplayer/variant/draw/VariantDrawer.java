package org.baseplayer.variant.draw;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.VariantDrawSeek;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VisibleVariantIndex;

import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.SampleTrack;

import org.baseplayer.utils.AppFonts;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Draws variants as vertical lines across sample tracks.
 * At {@linkplain #isRealtimeAlleleZoom(double) realtime allele zoom}
 * ({@code pixelSize > 1}), point alleles are drawn as filled rectangles and
 * become clickable/hoverable hit targets for {@link VariantInfoPopup}.
 */
public class VariantDrawer {

    /**
     * Pixels-per-base above which sample-row alleles are drawn as filled rects
     * with hover/click hit targets (close / “realtime” allele view).
     */
    public static final double REALTIME_ALLELE_MIN_PIXEL_SIZE = 1.0;

    /** True when the view is zoomed in enough for interactive sample-row alleles. */
    public static boolean isRealtimeAlleleZoom(double pixelSize) {
        return pixelSize > REALTIME_ALLELE_MIN_PIXEL_SIZE;
    }

    /** Clickable region for a drawn sample allele (canvas-local coordinates). */
    public record VariantHit(
        VariantNode node,
        VariantNode.SampleCall call,
        double x1,
        double y1,
        double x2,
        double y2) {
      public boolean contains(double x, double y) {
        return x >= x1 && x <= x2 && y >= y1 && y <= y2;
      }
    }

    private final SampleRegistry sampleRegistry;
    private final VisibleVariantIndex visibleIndex;
    private final VariantDrawSeek drawSeek = new VariantDrawSeek();
    private final List<VariantHit> hitRegions = new ArrayList<>();

    /**
     * When true, point variants are drawn as BAF dots (y = alleleFraction on 0–1),
     * with a 0.5 midline. SV/CNV/LOH keep their usual glyphs.
     */
    private boolean bafDisplayMode = false;

    /** Set for the duration of {@link #draw}: ALT-base colors/letters vs type colors. */
    private boolean paintByAltBase;

    private static final double BAF_DOT_SIZE = 4.0;

    // Quality thresholds
    private static final double MIN_QUALITY_FULL_OPACITY = 30.0;
    /** |CNLR_MEDIAN| that maps to full half-height. */
    private static final double CNV_LOGR_FULL_SCALE = 1.0;
    /** |TCN−2| that maps to full half-height when logR is absent. */
    private static final double CNV_TCN_FULL_SCALE = 4.0;
    private static final double CNV_SCALE_W = 28;
    /** Soft gray for CNV midlines / left-scale ticks (less stark than pure white). */
    private static final Color CNV_SCALE_INK = Color.rgb(186, 186, 186);

    public VariantDrawer() {
        this.sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
        this.visibleIndex = new VisibleVariantIndex();
    }

    public boolean isBafDisplayMode() {
        return bafDisplayMode;
    }

    public void setBafDisplayMode(boolean bafDisplayMode) {
        this.bafDisplayMode = bafDisplayMode;
    }

    public void draw(GraphicsContext gc,
                    VariantList variantList,
                    DrawStack drawStack,
                    Function<Double, Double> chromPosToScreenPos,
                    double canvasWidth,
                    VariantFilter filter) {
        hitRegions.clear();

        if (variantList == null || variantList.isEmpty()) {
            return;
        }

        List<Integer> displayedTrackIndices = sampleRegistry.getDisplayedTrackIndices();
        if (displayedTrackIndices.isEmpty()) {
            return;
        }

        double sampleHeight = sampleRegistry.getSampleHeight();
        if (sampleHeight <= 0) {
            return;
        }

        // Rebuild index if needed
        if (visibleIndex.needsRebuild(
                displayedTrackIndices,
                sampleRegistry.getFirstVisibleSample(),
                sampleRegistry.getLastVisibleSample(),
                sampleRegistry.getScrollBarPosition(),
                sampleHeight)) {

            visibleIndex.rebuild(
                displayedTrackIndices,
                sampleRegistry.getFirstVisibleSample(),
                sampleRegistry.getLastVisibleSample(),
                sampleHeight,
                sampleRegistry.getScrollBarPosition()
            );
        }

        if (visibleIndex.getVisibleCount() == 0) {
            return;
        }

        long screenStart = Math.max(0, (long) drawStack.getViewStart());
        long screenEnd = (long) drawStack.getViewEnd();
        double pixelSize = drawStack.getPixelSize();
        boolean drawClickableRects = isRealtimeAlleleZoom(pixelSize);
        paintByAltBase = VariantTypeVisuals.paintByAltBase(pixelSize);

        int[] visibleTrackIndices = visibleIndex.getSampleTrackIndices();
        double[] yPositions = visibleIndex.getYPositions();
        List<SampleTrack> allTracks = sampleRegistry.getSampleTracks();
        // trackIndex → visible slot for inverted sample walks (samples ≪ displayed tracks).
        int[] trackIndexToSlot = new int[allTracks.size()];
        java.util.Arrays.fill(trackIndexToSlot, -1);
        for (int slot = 0; slot < visibleTrackIndices.length; slot++) {
            int ti = visibleTrackIndices[slot];
            if (ti >= 0 && ti < trackIndexToSlot.length) {
                trackIndexToSlot[ti] = slot;
            }
        }

        // Zoomed-out point ticks: one ink per sample X column.
        // BAF mode instead uses (slot,x,y) so 0 / 0.5 / 1 at the same X all stay visible.
        int[] lastDrawnPixelX = new int[visibleTrackIndices.length];
        for (int i = 0; i < lastDrawnPixelX.length; i++) {
            lastDrawnPixelX[i] = -1;
        }
        java.util.HashSet<Long> bafDrawnPixels = bafDisplayMode ? new java.util.HashSet<>() : null;

        VcfManager vcfManager = VcfManager.getInstance();
        java.util.Set<VcfVariantType> legendTypes = vcfManager.getSessionAvailableTypes();

        // Mid-line + left scale for CNV tracks (gains up / losses down).
        if (sessionHasCanvasCnv(vcfManager, legendTypes)) {
            drawCnvSampleGuides(gc, visibleTrackIndices.length, yPositions, sampleHeight);
        }
        if (bafDisplayMode) {
            drawBafGuidelines(gc, visibleTrackIndices, yPositions, sampleHeight, canvasWidth);
        }

        // Hold the list lock for ensure + walk so a concurrent slider/filter rebuild
        // cannot clear nextVisible mid-paint (left tracks stale until the next hover redraw).
        final long viewStart = screenStart;
        final long viewEnd = screenEnd;
        variantList.withVisibleChain(filter, () -> {
            // Spanning SVs that start upstream of the view (no chromosome-start scan).
            for (VariantNode node : variantList.getVisibleSvByPosition()) {
                if (node.position >= viewStart) {
                    break;
                }
                if (node.svEnd < viewStart || node.position > viewEnd) {
                    continue;
                }
                if (!isSvWithSpan(node)) {
                    continue;
                }
                if (!vcfManager.isCanvasTypeDrawn(node.type, legendTypes)) {
                    continue;
                }
                drawNodeForVisibleSamples(
                    gc, variantList, node, filter, true, drawClickableRects, chromPosToScreenPos,
                    canvasWidth, chromPosToScreenPos.apply((double) node.position),
                    visibleTrackIndices, trackIndexToSlot, yPositions, allTracks, sampleHeight,
                    lastDrawnPixelX, bafDrawnPixels);
            }

            // Synthetic LOH AA/BB regions: arc from first→last marker (not a solid bar).
            for (VariantNode node : variantList.getLohRegions()) {
                if (node == null || !VariantTypeVisuals.isLohRegion(node.type)) {
                    continue;
                }
                if (node.svEnd < viewStart || node.position > viewEnd) {
                    continue;
                }
                if (!vcfManager.isCanvasTypeDrawn(node.type, legendTypes)) {
                    continue;
                }
                drawLohRegionForVisibleSamples(
                    gc, variantList, node, filter, drawClickableRects, chromPosToScreenPos,
                    canvasWidth, visibleTrackIndices, trackIndexToSlot, yPositions, allTracks,
                    sampleHeight);
            }

            // Point variants and in-window SV starts via nextVisible skip chain.
            VariantNode node = drawSeek.seek(variantList, viewStart);
            while (node != null && node.position <= viewEnd) {
                double x = chromPosToScreenPos.apply((double) node.position);
                boolean isSvSpan = isSvWithSpan(node);
                boolean isVisible = (x >= 0 && x <= canvasWidth)
                    || (isSvSpan && node.svEnd >= viewStart && node.position <= viewEnd);

                if (isVisible) {
                    if (vcfManager.isCanvasTypeDrawn(node.type, legendTypes)) {
                        drawNodeForVisibleSamples(
                            gc, variantList, node, filter, isSvSpan, drawClickableRects,
                            chromPosToScreenPos, canvasWidth, x,
                            visibleTrackIndices, trackIndexToSlot, yPositions, allTracks,
                            sampleHeight, lastDrawnPixelX, bafDrawnPixels);
                    }
                }

                node = node.nextVisible;
            }
        });
    }

    /** Topmost variant under canvas-local (x, y), or null. */
    public VariantHit findHit(double x, double y) {
        for (int i = hitRegions.size() - 1; i >= 0; i--) {
            VariantHit hit = hitRegions.get(i);
            if (hit.contains(x, y)) {
                return hit;
            }
        }
        return null;
    }

    public void clearHits() {
        hitRegions.clear();
    }

    private void drawNodeForVisibleSamples(
            GraphicsContext gc,
            VariantList variantList,
            VariantNode node,
            VariantFilter filter,
            boolean isSvSpan,
            boolean drawClickableRects,
            Function<Double, Double> chromPosToScreenPos,
            double canvasWidth,
            double x,
            int[] visibleTrackIndices,
            int[] trackIndexToSlot,
            double[] yPositions,
            List<SampleTrack> allTracks,
            double sampleHeight,
            int[] lastDrawnPixelX,
            java.util.Set<Long> bafDrawnPixels) {
        int xPixel = (int) x;
        // Zoomed-out point ticks: if every visible slot already has ink on this pixel,
        // skip the per-node sample walk entirely (avoids huge alloc churn on dense VCFs).
        // BAF keeps multiple Y values per X, so this fast-path must not apply.
        if (!bafDisplayMode && !isSvSpan && !drawClickableRects
            && allSlotsHavePixel(lastDrawnPixelX, xPixel)) {
            return;
        }
        int chainGen = variantList.getVisibleChainGeneration();
        boolean useCache = node.hasDisplayCache(chainGen);

        if (useCache) {
            // Display map is keyed by track — walk visible slots.
            for (int slot = 0; slot < visibleTrackIndices.length; slot++) {
                if (!bafDisplayMode && !isSvSpan && !drawClickableRects
                    && lastDrawnPixelX[slot] == xPixel) {
                    continue;
                }
                int trackIndex = visibleTrackIndices[slot];
                if (trackIndex < 0 || trackIndex >= allTracks.size()) {
                    continue;
                }
                SampleTrack track = allTracks.get(trackIndex);
                if (track == null) {
                    continue;
                }
                VariantNode.SampleCall call = node.getDisplayCall(track, chainGen);
                paintSampleSlot(
                    gc, node, call, isSvSpan, drawClickableRects, chromPosToScreenPos,
                    canvasWidth, x, xPixel, slot, yPositions, sampleHeight,
                    lastDrawnPixelX, bafDrawnPixels);
            }
            return;
        }

        // Inverted: one sample walk, paint only visible slots (samples ≪ displayed tracks).
        // Prefer solid over overlay when several VCFs share a track.
        VariantNode.SampleCall[] slotCall = new VariantNode.SampleCall[visibleTrackIndices.length];
        boolean[] slotSolid = new boolean[visibleTrackIndices.length];
        java.util.Set<Integer> failed = variantList.displayFailedLineages(node, filter);
        node.forEachSample(candidate -> {
            if (candidate == null || candidate.getTrack() == null) {
                return;
            }
            int trackIndex = candidate.getTrackIndex();
            if (trackIndex < 0 || trackIndex >= trackIndexToSlot.length) {
                return;
            }
            int slot = trackIndexToSlot[trackIndex];
            if (slot < 0 || slotSolid[slot]) {
                return;
            }
            // Already painted a 1px tick for this sample at this screen column.
            if (!bafDisplayMode && !isSvSpan && !drawClickableRects
                && lastDrawnPixelX[slot] == xPixel) {
                return;
            }
            if (!VariantList.isDisplayEligible(node, candidate, filter, failed)) {
                return;
            }
            if (!candidate.isUiOverlay()) {
                slotCall[slot] = candidate;
                slotSolid[slot] = true;
            } else if (slotCall[slot] == null) {
                slotCall[slot] = candidate;
            }
        });
        for (int slot = 0; slot < slotCall.length; slot++) {
            paintSampleSlot(
                gc, node, slotCall[slot], isSvSpan, drawClickableRects, chromPosToScreenPos,
                canvasWidth, x, xPixel, slot, yPositions, sampleHeight,
                lastDrawnPixelX, bafDrawnPixels);
        }
    }

    private static boolean allSlotsHavePixel(int[] lastDrawnPixelX, int xPixel) {
        if (lastDrawnPixelX == null || lastDrawnPixelX.length == 0) {
            return false;
        }
        for (int px : lastDrawnPixelX) {
            if (px != xPixel) {
                return false;
            }
        }
        return true;
    }

    /** Pack slot + screen x/y into one key for BAF overdraw suppression. */
    private static long bafPixelKey(int slot, int xPixel, int yPixel) {
        return (((long) (slot & 0x3FF)) << 42)
            | (((long) (xPixel & 0x1FFFFF)) << 21)
            | ((long) (yPixel & 0x1FFFFF));
    }

    private static int bafValueYPixel(double rowTop, double sampleHeight, double alleleFraction) {
        double v = Math.max(0, Math.min(1, alleleFraction));
        double top = rowTop + 1;
        double usable = Math.max(2, sampleHeight - 2);
        double valueY = top + usable * (1.0 - v);
        return (int) Math.round(valueY);
    }

    private void paintSampleSlot(
            GraphicsContext gc,
            VariantNode node,
            VariantNode.SampleCall call,
            boolean isSvSpan,
            boolean drawClickableRects,
            Function<Double, Double> chromPosToScreenPos,
            double canvasWidth,
            double x,
            int xPixel,
            int slot,
            double[] yPositions,
            double sampleHeight,
            int[] lastDrawnPixelX,
            java.util.Set<Long> bafDrawnPixels) {
        if (call == null || !call.isUiVisible()) {
            return;
        }
        double y = yPositions[slot];
        if (isSvSpan) {
            drawSvSpan(gc, node, call, chromPosToScreenPos, canvasWidth, x, y, sampleHeight,
                drawClickableRects);
            return;
        }
        if (bafDisplayMode) {
            if (call.alleleFraction < 0) {
                return;
            }
            int yPixel = bafValueYPixel(y, sampleHeight, call.alleleFraction);
            if (bafDrawnPixels != null
                && !bafDrawnPixels.add(bafPixelKey(slot, xPixel, yPixel))) {
                return;
            }
            drawVariantBafDot(gc, node, call, x, y, sampleHeight, true);
            return;
        }
        if (!drawClickableRects && xPixel == lastDrawnPixelX[slot]) {
            return;
        }
        if (drawClickableRects) {
            drawVariantRect(gc, node, call, chromPosToScreenPos, canvasWidth, x, y, sampleHeight);
        } else {
            drawVariantLine(gc, node, call, x, y, sampleHeight);
            lastDrawnPixelX[slot] = xPixel;
        }
    }

    /**
     * Point allele as a BAF dot: 0 at bottom, 0.5 mid, 1 at top.
     * Uses {@link VariantNode.SampleCall#alleleFraction} when available.
     */
    private void drawVariantBafDot(
            GraphicsContext gc,
            VariantNode variant,
            VariantNode.SampleCall call,
            double x,
            double y,
            double sampleHeight,
            boolean recordHit) {
        if (call == null || call.alleleFraction < 0) {
            return;
        }
        double v = Math.max(0, Math.min(1, call.alleleFraction));
        double top = y + 1;
        double usable = Math.max(2, sampleHeight - 2);
        double valueY = top + usable * (1.0 - v);

        // Midline once per slot is expensive in a per-variant loop; stroke a short tick
        // only — full midline is drawn in {@link #drawBafGuidelines}.
        Color baseColor = colorForCall(variant, call);
        double opacity = callOpacity(call, 0.95, variant.isHomozygousRef(call));
        if (opacity != 1.0) {
            gc.setGlobalAlpha(opacity);
        }
        gc.setFill(baseColor);
        gc.fillOval(x - BAF_DOT_SIZE * 0.5, valueY - BAF_DOT_SIZE * 0.5,
            BAF_DOT_SIZE, BAF_DOT_SIZE);
        if (opacity != 1.0) {
            gc.setGlobalAlpha(1.0);
        }

        if (recordHit) {
            hitRegions.add(new VariantHit(
                variant, call,
                x - BAF_DOT_SIZE, valueY - BAF_DOT_SIZE,
                x + BAF_DOT_SIZE, valueY + BAF_DOT_SIZE));
        }
    }

    /** Draw 0.5 guidelines across visible sample rows when in BAF mode. */
    public void drawBafGuidelines(
            GraphicsContext gc,
            int[] visibleTrackIndices,
            double[] yPositions,
            double sampleHeight,
            double canvasWidth) {
        if (!bafDisplayMode || visibleTrackIndices == null || yPositions == null) {
            return;
        }
        gc.setStroke(Color.rgb(110, 110, 110));
        gc.setLineWidth(1);
        gc.setLineDashes(2, 3);
        for (int slot = 0; slot < visibleTrackIndices.length && slot < yPositions.length; slot++) {
            double midY = yPositions[slot] + sampleHeight * 0.5;
            gc.strokeLine(0, midY, canvasWidth, midY);
        }
        gc.setLineDashes();
    }

    /**
     * Draw LOH region as an arc spanning first→last contributing marker on each
     * homozygous sample track. Contributing SNP markers still paint via the visible chain.
     */
    private void drawLohRegionForVisibleSamples(
            GraphicsContext gc,
            VariantList variantList,
            VariantNode node,
            VariantFilter filter,
            boolean recordHit,
            Function<Double, Double> chromPosToScreenPos,
            double canvasWidth,
            int[] visibleTrackIndices,
            int[] trackIndexToSlot,
            double[] yPositions,
            List<SampleTrack> allTracks,
            double sampleHeight) {
        int chainGen = variantList.getVisibleChainGeneration();
        boolean useCache = node.hasDisplayCache(chainGen);
        double startX = chromPosToScreenPos.apply((double) node.position);
        double endPos = node.svEnd >= node.position ? node.svEnd : node.position;
        double endX = chromPosToScreenPos.apply(endPos);

        if (useCache) {
            for (int slot = 0; slot < visibleTrackIndices.length; slot++) {
                int trackIndex = visibleTrackIndices[slot];
                if (trackIndex < 0 || trackIndex >= allTracks.size()) {
                    continue;
                }
                SampleTrack track = allTracks.get(trackIndex);
                if (track == null) {
                    continue;
                }
                VariantNode.SampleCall call = node.getDisplayCall(track, chainGen);
                if (call == null || !call.isUiVisible()) {
                    continue;
                }
                drawLohArc(gc, node, call, startX, endX, canvasWidth, yPositions[slot], sampleHeight,
                    recordHit);
            }
            return;
        }

        VariantNode.SampleCall[] slotCall = new VariantNode.SampleCall[visibleTrackIndices.length];
        boolean[] slotSolid = new boolean[visibleTrackIndices.length];
        java.util.Set<Integer> failed = variantList.displayFailedLineages(node, filter);
        node.forEachSample(candidate -> {
            if (candidate == null || candidate.getTrack() == null) {
                return;
            }
            int trackIndex = candidate.getTrackIndex();
            if (trackIndex < 0 || trackIndex >= trackIndexToSlot.length) {
                return;
            }
            int slot = trackIndexToSlot[trackIndex];
            if (slot < 0 || slotSolid[slot]) {
                return;
            }
            if (!VariantList.isDisplayEligible(node, candidate, filter, failed)) {
                return;
            }
            if (!candidate.isUiOverlay()) {
                slotCall[slot] = candidate;
                slotSolid[slot] = true;
            } else if (slotCall[slot] == null) {
                slotCall[slot] = candidate;
            }
        });
        for (int slot = 0; slot < slotCall.length; slot++) {
            VariantNode.SampleCall call = slotCall[slot];
            if (call == null || !call.isUiVisible()) {
                continue;
            }
            drawLohArc(gc, node, call, startX, endX, canvasWidth, yPositions[slot], sampleHeight,
                recordHit);
        }
    }

    /** Thin arc between first and last LOH marker, with endpoint ticks. */
    private void drawLohArc(
            GraphicsContext gc,
            VariantNode variant,
            VariantNode.SampleCall call,
            double startX,
            double endX,
            double canvasWidth,
            double y,
            double sampleHeight,
            boolean recordHit) {
        Color baseColor = colorForCall(variant, call);
        double opacity = callOpacity(call, 0.85, false);

        boolean startInView = startX >= 0 && startX <= canvasWidth;
        boolean endInView = endX >= 0 && endX <= canvasWidth;
        double x1 = Math.max(0, Math.min(startX, endX));
        double x2 = Math.min(canvasWidth, Math.max(startX, endX));
        if (x2 - x1 < 1) {
            x2 = x1 + 1;
        }

        double yBase = y + Math.max(1.0, sampleHeight * 0.72);
        double span = x2 - x1;
        double archHeight = Math.min(Math.max(3.0, sampleHeight * 0.7), Math.max(5.0, span * 0.15));
        double ctrlY = Math.max(y + 1.0, yBase - archHeight);
        double midX = (x1 + x2) * 0.5;

        if (opacity != 1.0) gc.setGlobalAlpha(opacity);
        gc.setStroke(baseColor);
        gc.setLineWidth(sampleHeight >= 8 ? 2.0 : 1.4);
        gc.setLineDashes(0);
        gc.beginPath();
        gc.moveTo(x1, yBase);
        gc.quadraticCurveTo(midX, ctrlY, x2, yBase);
        gc.stroke();

        // Endpoint ticks mark the first and last contributing markers when in view.
        gc.setLineWidth(1.8);
        if (startInView) {
            gc.strokeLine(startX, y + 1, startX, y + Math.max(2.0, sampleHeight - 1));
        }
        if (endInView) {
            gc.strokeLine(endX, y + 1, endX, y + Math.max(2.0, sampleHeight - 1));
        }
        if (opacity != 1.0) gc.setGlobalAlpha(1.0);

        if (recordHit) {
            double hitTop = Math.min(ctrlY, y);
            double hitBottom = y + sampleHeight;
            hitRegions.add(new VariantHit(variant, call, x1, hitTop, x2, hitBottom));
        }
    }

    private void drawVariantLine(GraphicsContext gc, VariantNode variant, VariantNode.SampleCall call,
                                 double x, double y, double sampleHeight) {
        Color baseColor = colorForCall(variant, call);
        double opacity = callOpacity(call, 1.0, variant.isHomozygousRef(call));

        gc.setStroke(baseColor);
        gc.setLineWidth(1.0);
        if (opacity != 1.0) gc.setGlobalAlpha(opacity);

        double lineHeight = sampleHeight >= 3 ? sampleHeight : 1;
        gc.strokeLine(x, y, x, y + lineHeight);

        if (call != null && call.gt != null && sampleHeight >= 6 && !variant.isHomozygousRef(call)) {
            String[] a = call.gt.split("[/|]");
            if (a.length >= 2 && a[0].equals(variant.alt) && a[1].equals(variant.alt)) {
                gc.setFill(baseColor);
                gc.fillRect(x - 1, y, 3, 2);
            }
        }

        if (opacity != 1.0) gc.setGlobalAlpha(1.0);
    }

    /**
     * Draw a point / short allele as a filled rectangle spanning its genomic width.
     */
    private void drawVariantRect(GraphicsContext gc, VariantNode variant, VariantNode.SampleCall call,
                                 Function<Double, Double> chromPosToScreenPos, double canvasWidth,
                                 double startX, double y, double sampleHeight) {
        Color baseColor = colorForCall(variant, call);
        double opacity = callOpacity(call, 0.85, variant.isHomozygousRef(call));

        double endX = chromPosToScreenPos.apply((double) alleleEndExclusive(variant));
        double x1 = Math.max(0, Math.min(startX, endX));
        double x2 = Math.min(canvasWidth, Math.max(startX, endX));
        double width = Math.max(1.0, x2 - x1);

        double rectHeight = sampleHeight >= 4 ? sampleHeight - 1 : Math.max(1, sampleHeight);
        gc.setFill(baseColor);
        if (opacity != 1.0) gc.setGlobalAlpha(opacity);
        gc.fillRect(x1, y, width, rectHeight);
        if (opacity != 1.0) gc.setGlobalAlpha(1.0);

        // Subtle outline so adjacent alleles stay separable when zoomed in.
        gc.setStroke(baseColor.darker());
        gc.setLineWidth(1.0);
        gc.strokeRect(x1 + 0.5, y + 0.5, Math.max(0, width - 1), Math.max(0, rectHeight - 1));

        // ALT letter only in close zoom (same gate as ALT-base fill colors).
        if (paintByAltBase
            && width >= 6
            && rectHeight >= 8
            && !variant.isHomozygousRef(call)) {
            String label = VariantTypeVisuals.allelePaintLabel(variant);
            if (label != null && !label.isEmpty()) {
                Font font = width >= 10 && label.length() == 1
                    ? AppFonts.getMonoBoldFont(11)
                    : AppFonts.getMonoBoldFont(9);
                gc.setFont(font);
                gc.setFill(Color.WHITE);
                if (opacity != 1.0) gc.setGlobalAlpha(opacity);
                double textW = label.length() == 1 ? (width >= 10 ? 7 : 5.5) : Math.min(width - 2, 12);
                double textX = x1 + (width - textW) / 2;
                double textY = y + rectHeight - (rectHeight - (width >= 10 ? 10 : 8)) / 2;
                gc.fillText(label, textX, textY, Math.max(4, width - 2));
                if (opacity != 1.0) gc.setGlobalAlpha(1.0);
            }
        }

        hitRegions.add(new VariantHit(variant, call, x1, y, x1 + width, y + rectHeight));
    }

    private boolean isSvWithSpan(VariantNode variant) {
        if (variant == null || VariantTypeVisuals.isLohRegion(variant.type)) {
            // LOH regions use {@link #drawLohArc}, not solid SV bars.
            return false;
        }
        return variant.svEnd > variant.position
            && (variant.type == VcfVariantType.SV_DELETION
                || variant.type == VcfVariantType.SV_DUPLICATION
                || variant.type == VcfVariantType.SV_INVERSION
                || variant.type == VcfVariantType.SV_INSERTION
                || VariantTypeVisuals.isCnv(variant.type));
    }

    private void drawSvSpan(GraphicsContext gc, VariantNode variant, VariantNode.SampleCall call,
                           Function<Double, Double> chromPosToScreenPos, double canvasWidth,
                           double startX, double y, double sampleHeight,
                           boolean recordHit) {
        if (VariantTypeVisuals.isCnv(variant.type)) {
            drawCnvSpan(gc, variant, call, chromPosToScreenPos, canvasWidth, startX, y, sampleHeight,
                recordHit);
            return;
        }

        Color baseColor = colorForCall(variant, call);
        double opacity = callOpacity(call, 0.7, variant.isHomozygousRef(call));

        double endX = chromPosToScreenPos.apply((double) variant.svEnd);
        boolean startInView = startX >= 0 && startX <= canvasWidth;
        boolean endInView = endX >= 0 && endX <= canvasWidth;

        if (startInView && endInView) {
            double x1 = Math.min(startX, endX);
            double x2 = Math.max(startX, endX);
            double width = Math.max(1.0, x2 - x1);
            double rectHeight = sampleHeight >= 4 ? sampleHeight - 1 : sampleHeight;
            gc.setFill(baseColor);
            if (opacity != 1.0) gc.setGlobalAlpha(opacity);
            gc.fillRect(x1, y, width, rectHeight);
            if (opacity != 1.0) gc.setGlobalAlpha(1.0);
            if (recordHit) {
                hitRegions.add(new VariantHit(variant, call, x1, y, x1 + width, y + rectHeight));
            }
            return;
        }

        double x1 = Math.max(0, Math.min(startX, endX));
        double x2 = Math.min(canvasWidth, Math.max(startX, endX));
        if (x2 - x1 < 1) {
            x2 = x1 + 1;
        }
        drawDashedSvArch(gc, variant, call, baseColor, opacity, startX, endX,
            startInView, endInView, x1, x2, y, sampleHeight, recordHit);
    }

    /**
     * CNV segments: midline through the sample row; gains paint upward and losses downward
     * with height proportional to |CNLR_MEDIAN| (else |TCN−2|).
     */
    private void drawCnvSpan(
            GraphicsContext gc,
            VariantNode variant,
            VariantNode.SampleCall call,
            Function<Double, Double> chromPosToScreenPos,
            double canvasWidth,
            double startX,
            double y,
            double sampleHeight,
            boolean recordHit) {
        Color baseColor = colorForCall(variant, call);
        double opacity = callOpacity(call, 0.78, variant.isHomozygousRef(call));

        double endX = chromPosToScreenPos.apply((double) variant.svEnd);
        double x1 = Math.max(0, Math.min(startX, endX));
        double x2 = Math.min(canvasWidth, Math.max(startX, endX));
        if (x2 - x1 < 1) {
            x2 = x1 + 1;
        }
        double width = x2 - x1;

        double midY = y + sampleHeight * 0.5;
        double halfH = Math.max(2.0, sampleHeight * 0.5 - 1.5);
        double signed = cnvSignedMagnitude(variant);
        double unit = cnvDisplayUnit(variant);
        double amp = Math.min(1.0, Math.abs(signed) / unit);
        // Keep a visible stub even for near-neutral called gain/loss.
        if (amp < 0.08 && variant.type != VcfVariantType.SV_CNV_NEUTRAL && signed != 0) {
            amp = 0.08;
        }
        double barH = Math.max(1.0, halfH * amp);

        boolean gainSide = signed > 0
            || (signed == 0 && variant.type == VcfVariantType.SV_CNV_GAIN);
        boolean lossSide = signed < 0
            || (signed == 0 && variant.type == VcfVariantType.SV_CNV_LOSS);

        if (opacity != 1.0) {
            gc.setGlobalAlpha(opacity);
        }
        gc.setFill(baseColor);
        double hitTop = midY - 1;
        double hitBottom = midY + 1;
        if (gainSide) {
            double top = midY - barH;
            gc.fillRect(x1, top, width, barH);
            hitTop = top;
            hitBottom = midY;
        } else if (lossSide) {
            gc.fillRect(x1, midY, width, barH);
            hitTop = midY;
            hitBottom = midY + barH;
        } else {
            // Copy-neutral: thin band on the midline.
            gc.fillRect(x1, midY - 1.5, width, 3);
            hitTop = midY - 2;
            hitBottom = midY + 2;
        }

        if (opacity != 1.0) {
            gc.setGlobalAlpha(1.0);
        }

        if (recordHit) {
            hitRegions.add(new VariantHit(variant, call, x1, hitTop, x2, hitBottom));
        }
    }

    /** Signed amplitude: logR when present, else TCN relative to diploid (2). */
    private static double cnvSignedMagnitude(VariantNode node) {
        if (node == null) {
            return 0;
        }
        Double logr = parseInfoDouble(node, "CNLR_MEDIAN");
        if (logr == null) {
            logr = parseInfoDouble(node, "CNLR_MEDIAN_CLUST");
        }
        if (logr != null) {
            return logr;
        }
        Double tcn = parseInfoDouble(node, "TCN_EM");
        if (tcn == null) {
            tcn = parseInfoDouble(node, "TCN");
        }
        if (tcn == null) {
            tcn = parseInfoDouble(node, "CN");
        }
        if (tcn != null) {
            return tcn - 2.0;
        }
        if (node.type == VcfVariantType.SV_CNV_GAIN) {
            return 1.0;
        }
        if (node.type == VcfVariantType.SV_CNV_LOSS) {
            return -1.0;
        }
        return 0;
    }

    /** Full half-height maps to this |magnitude| (logR ±1 or |TCN−2| = 4). */
    private static double cnvDisplayUnit(VariantNode node) {
        if (node != null
            && (node.getInfoValue("CNLR_MEDIAN") != null
                || node.getInfoValue("CNLR_MEDIAN_CLUST") != null)) {
            return CNV_LOGR_FULL_SCALE;
        }
        return CNV_TCN_FULL_SCALE;
    }

    private static Double parseInfoDouble(VariantNode node, String key) {
        if (node == null || key == null) {
            return null;
        }
        String raw = node.getInfoValue(key);
        if (raw == null || raw.isBlank() || ".".equals(raw) || "NA".equalsIgnoreCase(raw)) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean sessionHasCanvasCnv(
            VcfManager vcfManager,
            java.util.Set<VcfVariantType> legendTypes) {
        for (VcfVariantType type : legendTypes) {
            if (VariantTypeVisuals.isCnv(type) && vcfManager.isCanvasTypeDrawn(type, legendTypes)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Per-sample CNV guide: faint white midline across the row and a left scale
     * (+unit / 0 / −unit) matching the aggregate density chrome layout.
     */
    private void drawCnvSampleGuides(
            GraphicsContext gc,
            int sampleCount,
            double[] yPositions,
            double sampleHeight) {
        if (sampleCount <= 0 || yPositions == null || sampleHeight < 10) {
            return;
        }
        boolean useLogr = sessionPrefersLogrScale();
        String topLabel = useLogr ? "+1" : "+4";
        String botLabel = useLogr ? "−1" : "−4";

        for (int i = 0; i < sampleCount; i++) {
            double y = yPositions[i];
            double midY = y + sampleHeight * 0.5;
            drawCnvLeftScale(gc, y, sampleHeight, midY, topLabel, botLabel);
        }
    }

    private static boolean sessionPrefersLogrScale() {
        for (var field : VcfManager.getInstance().getSessionInfoHeaderFields()) {
            if (field != null
                && ("CNLR_MEDIAN".equals(field.id())
                    || "CNLR_MEDIAN_CLUST".equals(field.id()))) {
                return true;
            }
        }
        // No TCN-only header → still treat as logR-style ±1 scale by default.
        for (var field : VcfManager.getInstance().getSessionInfoHeaderFields()) {
            if (field != null
                && ("TCN_EM".equals(field.id())
                    || "TCN".equals(field.id()))) {
                return false;
            }
        }
        return true;
    }

    private void drawCnvLeftScale(
            GraphicsContext gc,
            double y,
            double sampleHeight,
            double midY,
            String topLabel,
            String botLabel) {
        double scaleX = 3;
        double scaleW = CNV_SCALE_W;
        var chrome = org.baseplayer.ui.theme.AppTheme.chrome();
        Color scaleBg = Color.color(
            chrome.elevated().getRed(),
            chrome.elevated().getGreen(),
            chrome.elevated().getBlue(),
            org.baseplayer.ui.theme.AppTheme.isDark() ? 0.62 : 0.92);
        gc.setFill(scaleBg);
        gc.fillRoundRect(scaleX - 1, y + 1, scaleW + 2, sampleHeight - 2, 4, 4);

        gc.setFont(org.baseplayer.utils.AppFonts.getFont("Segoe UI", 9));
        gc.setFill(CNV_SCALE_INK);
        gc.setTextBaseline(javafx.geometry.VPos.TOP);
        gc.fillText(topLabel, scaleX + 2, y + 2);
        gc.setTextBaseline(javafx.geometry.VPos.CENTER);
        gc.fillText("0", scaleX + 2, midY);
        gc.setTextBaseline(javafx.geometry.VPos.BOTTOM);
        gc.fillText(botLabel, scaleX + 2, y + sampleHeight - 2);

        double axisX = scaleX + scaleW - 4;
        gc.setStroke(CNV_SCALE_INK);
        gc.setGlobalAlpha(0.9);
        gc.setLineWidth(1.4);
        gc.strokeLine(axisX, y + 3, axisX, y + sampleHeight - 3);
        gc.setLineWidth(1.2);
        for (int t = 0; t <= 4; t++) {
            double ty = y + 3 + t * (sampleHeight - 6) / 4.0;
            gc.strokeLine(axisX - 5, ty, axisX, ty);
        }
        // Explicit mid tick slightly longer.
        gc.setLineWidth(1.5);
        gc.strokeLine(axisX - 7, midY + 0.5, axisX, midY + 0.5);
        gc.setGlobalAlpha(1.0);
    }

    /**
     * Thin dashed arch for SVs whose other (or both) breakpoints sit outside the view,
     * so a large span does not paint a solid bar across the whole track.
     */
    private void drawDashedSvArch(
            GraphicsContext gc,
            VariantNode variant,
            VariantNode.SampleCall call,
            Color baseColor,
            double opacity,
            double startX,
            double endX,
            boolean startInView,
            boolean endInView,
            double x1,
            double x2,
            double y,
            double sampleHeight,
            boolean recordHit) {
        double yBase = y + Math.max(1.0, sampleHeight * 0.72);
        double span = x2 - x1;
        double archHeight = Math.min(Math.max(3.0, sampleHeight * 0.62), Math.max(4.0, span * 0.12));
        double ctrlY = Math.max(y + 1.0, yBase - archHeight);
        double midX = (x1 + x2) * 0.5;

        if (opacity != 1.0) gc.setGlobalAlpha(opacity);
        gc.setStroke(baseColor);
        gc.setLineWidth(sampleHeight >= 8 ? 1.6 : 1.2);
        gc.setLineDashes(6, 4);
        gc.beginPath();
        gc.moveTo(x1, yBase);
        gc.quadraticCurveTo(midX, ctrlY, x2, yBase);
        gc.stroke();
        gc.setLineDashes(0);

        gc.setLineWidth(1.5);
        if (startInView) {
            gc.strokeLine(startX, y + 1, startX, y + Math.max(2.0, sampleHeight - 1));
        }
        if (endInView) {
            gc.strokeLine(endX, y + 1, endX, y + Math.max(2.0, sampleHeight - 1));
        }
        if (opacity != 1.0) gc.setGlobalAlpha(1.0);

        if (recordHit) {
            double hitTop = Math.min(ctrlY, y);
            double hitBottom = y + sampleHeight;
            hitRegions.add(new VariantHit(variant, call, x1, hitTop, x2, hitBottom));
        }
    }

    /**
     * Exclusive genomic end used for rectangle width (SNV = 1 bp, DEL/MNV = REF length).
     */
    public static long alleleEndExclusive(VariantNode node) {
        if (node == null) {
            return 1;
        }
        if (node.svEnd > node.position) {
            return node.svEnd;
        }
        if (node.type == VcfVariantType.DELETION
            || node.type == VcfVariantType.MNV
            || node.type == VcfVariantType.COMPLEX) {
            int refLen = node.ref != null ? node.ref.length() : 1;
            return node.position + Math.max(1, refLen);
        }
        return node.position + 1;
    }

    private static double callOpacity(VariantNode.SampleCall call, double base, boolean lohAa) {
        double opacity = base;
        if (lohAa) {
            // Keep AA marks distinctly faint vs ALT carriers.
            opacity *= 0.4;
        }
        if (call != null) {
            if (!lohAa && call.gt != null && VariantNode.isHetGt(call.gt)) {
                opacity *= 0.75;
            }
            if (call.quality >= 0 && call.quality < MIN_QUALITY_FULL_OPACITY) {
                opacity *= (call.quality / MIN_QUALITY_FULL_OPACITY);
            }
            if (isOverlayCall(call)) {
                opacity *= 0.35;
            }
        }
        return opacity;
    }

    private static boolean isOverlayCall(VariantNode.SampleCall call) {
        return call != null && call.isUiOverlay();
    }

    private Color colorForCall(VariantNode variant, VariantNode.SampleCall call) {
        if (variant != null && VariantTypeVisuals.isLohRegion(variant.type)) {
            return VariantTypeVisuals.color(variant.type);
        }
        if (variant != null && variant.isHomozygousRef(call)) {
            return VariantTypeVisuals.lohAaColor();
        }
        if (variant == null) {
            return VariantTypeVisuals.color(null);
        }
        // Far / mid zoom: SNV / INS / DEL type colors. Close zoom: ALT-base palette.
        return paintByAltBase
            ? VariantTypeVisuals.allelePaintColor(variant)
            : VariantTypeVisuals.color(variant.type);
    }

    public void markIndexDirty() {
        visibleIndex.markDirty();
    }

    public VisibleVariantIndex getVisibleIndex() {
        return visibleIndex;
    }
}
