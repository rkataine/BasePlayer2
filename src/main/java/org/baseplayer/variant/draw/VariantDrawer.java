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

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Draws variants as vertical lines across sample tracks.
 * When {@code pixelSize > 1}, point alleles are drawn as filled rectangles and
 * become clickable hit targets for {@link VariantInfoPopup}.
 */
public class VariantDrawer {

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

    // Quality thresholds
    private static final double MIN_QUALITY_FULL_OPACITY = 30.0;

    public VariantDrawer() {
        this.sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
        this.visibleIndex = new VisibleVariantIndex();
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
        boolean drawClickableRects = pixelSize > 1.0;

        variantList.ensureVisibleChain(filter);

        int[] visibleTrackIndices = visibleIndex.getSampleTrackIndices();
        double[] yPositions = visibleIndex.getYPositions();
        List<SampleTrack> allTracks = sampleRegistry.getSampleTracks();

        // Track last drawn X pixel per sample to avoid overdraw of point variants when zoomed out
        // (SV spans are exempt from this deduplication as they span multiple pixels)
        int[] lastDrawnPixelX = new int[visibleTrackIndices.length];
        for (int i = 0; i < lastDrawnPixelX.length; i++) {
            lastDrawnPixelX[i] = -1;
        }

        VcfManager vcfManager = VcfManager.getInstance();
        java.util.Set<VcfVariantType> legendTypes = vcfManager.getSessionAvailableTypes();

        // Spanning SVs that start upstream of the view (no chromosome-start scan).
        for (VariantNode node : variantList.getVisibleSvByPosition()) {
            if (node.position >= screenStart) {
                break;
            }
            if (node.svEnd < screenStart || node.position > screenEnd) {
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
                visibleTrackIndices, yPositions, allTracks, sampleHeight, lastDrawnPixelX);
        }

        // Synthetic LOH AA/BB regions: arc from first→last marker (not a solid bar).
        for (VariantNode node : variantList.getLohRegions()) {
            if (node == null || !VariantTypeVisuals.isLohRegion(node.type)) {
                continue;
            }
            if (node.svEnd < screenStart || node.position > screenEnd) {
                continue;
            }
            if (!vcfManager.isCanvasTypeDrawn(node.type, legendTypes)) {
                continue;
            }
            drawLohRegionForVisibleSamples(
                gc, variantList, node, filter, drawClickableRects, chromPosToScreenPos,
                canvasWidth, visibleTrackIndices, yPositions, allTracks, sampleHeight);
        }

        // Point variants and in-window SV starts via nextVisible skip chain.
        VariantNode node = drawSeek.seek(variantList, screenStart);
        while (node != null && node.position <= screenEnd) {
            double x = chromPosToScreenPos.apply((double) node.position);
            boolean isSvSpan = isSvWithSpan(node);
            boolean isVisible = (x >= 0 && x <= canvasWidth)
                || (isSvSpan && node.svEnd >= screenStart && node.position <= screenEnd);

            if (isVisible) {
                if (vcfManager.isCanvasTypeDrawn(node.type, legendTypes)) {
                    drawNodeForVisibleSamples(
                        gc, variantList, node, filter, isSvSpan, drawClickableRects,
                        chromPosToScreenPos, canvasWidth, x,
                        visibleTrackIndices, yPositions, allTracks, sampleHeight, lastDrawnPixelX);
                }
            }

            node = node.nextVisible;
        }
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
            double[] yPositions,
            List<SampleTrack> allTracks,
            double sampleHeight,
            int[] lastDrawnPixelX) {
        int xPixel = (int) x;
        int chainGen = variantList.getVisibleChainGeneration();
        boolean useCache = node.hasDisplayCache(chainGen);

        for (int slot = 0; slot < visibleTrackIndices.length; slot++) {
            int trackIndex = visibleTrackIndices[slot];
            if (trackIndex < 0 || trackIndex >= allTracks.size()) {
                continue;
            }
            SampleTrack track = allTracks.get(trackIndex);
            if (track == null) {
                continue;
            }

            VariantNode.SampleCall call;
            if (useCache) {
                call = node.getDisplayCall(track, chainGen);
            } else {
                call = variantList.getDisplayCall(node, trackIndex, track, filter);
            }
            if (call == null || !call.isUiVisible()) {
                continue;
            }

            if (!isSvSpan && !drawClickableRects && xPixel == lastDrawnPixelX[slot]) {
                continue;
            }

            double y = yPositions[slot];
            if (isSvSpan) {
                drawSvSpan(gc, node, call, chromPosToScreenPos, canvasWidth, x, y, sampleHeight,
                    drawClickableRects);
            } else if (drawClickableRects) {
                drawVariantRect(gc, node, call, chromPosToScreenPos, canvasWidth, x, y, sampleHeight);
            } else {
                drawVariantLine(gc, node, call, x, y, sampleHeight);
                lastDrawnPixelX[slot] = xPixel;
            }
        }
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
            double[] yPositions,
            List<SampleTrack> allTracks,
            double sampleHeight) {
        int chainGen = variantList.getVisibleChainGeneration();
        boolean useCache = node.hasDisplayCache(chainGen);
        double startX = chromPosToScreenPos.apply((double) node.position);
        double endPos = node.svEnd >= node.position ? node.svEnd : node.position;
        double endX = chromPosToScreenPos.apply(endPos);

        for (int slot = 0; slot < visibleTrackIndices.length; slot++) {
            int trackIndex = visibleTrackIndices[slot];
            if (trackIndex < 0 || trackIndex >= allTracks.size()) {
                continue;
            }
            SampleTrack track = allTracks.get(trackIndex);
            if (track == null) {
                continue;
            }
            VariantNode.SampleCall call;
            if (useCache) {
                call = node.getDisplayCall(track, chainGen);
            } else {
                call = variantList.getDisplayCall(node, track, filter);
            }
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
        return VariantTypeVisuals.color(variant != null ? variant.type : null);
    }

    public void markIndexDirty() {
        visibleIndex.markDirty();
    }

    public VisibleVariantIndex getVisibleIndex() {
        return visibleIndex;
    }
}
