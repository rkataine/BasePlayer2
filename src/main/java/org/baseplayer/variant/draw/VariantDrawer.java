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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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

        // Track → visible slot via identity (no indexOf / getSampleCall in the hot loop).
        Map<SampleTrack, Integer> trackToSlot = new IdentityHashMap<>(visibleTrackIndices.length * 2);
        List<SampleTrack> allTracks = sampleRegistry.getSampleTracks();
        for (int i = 0; i < visibleTrackIndices.length; i++) {
            int trackIndex = visibleTrackIndices[i];
            if (trackIndex < 0 || trackIndex >= allTracks.size()) {
                continue;
            }
            SampleTrack track = allTracks.get(trackIndex);
            if (track != null) {
                trackToSlot.put(track, i);
            }
        }

        // Track last drawn X pixel per sample to avoid overdraw of point variants when zoomed out
        // (SV spans are exempt from this deduplication as they span multiple pixels)
        int[] lastDrawnPixelX = new int[visibleTrackIndices.length];
        for (int i = 0; i < lastDrawnPixelX.length; i++) {
            lastDrawnPixelX[i] = -1;
        }

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
            if (!VcfManager.getInstance().isCanvasTypeVisible(node.type)) {
                continue;
            }
            drawNodeForVisibleSamples(
                gc, node, filter, true, drawClickableRects, chromPosToScreenPos, canvasWidth,
                chromPosToScreenPos.apply((double) node.position),
                trackToSlot, yPositions, sampleHeight, lastDrawnPixelX);
        }

        // Point variants and in-window SV starts via nextVisible skip chain.
        VariantNode node = drawSeek.seek(variantList, screenStart);
        while (node != null && node.position <= screenEnd) {
            double x = chromPosToScreenPos.apply((double) node.position);
            boolean isSvSpan = isSvWithSpan(node);
            boolean isVisible = (x >= 0 && x <= canvasWidth)
                || (isSvSpan && node.svEnd >= screenStart && node.position <= screenEnd);

            if (isVisible) {
                if (VcfManager.getInstance().isCanvasTypeVisible(node.type)) {
                    drawNodeForVisibleSamples(
                        gc, node, filter, isSvSpan, drawClickableRects, chromPosToScreenPos, canvasWidth, x,
                        trackToSlot, yPositions, sampleHeight, lastDrawnPixelX);
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
            VariantNode node,
            VariantFilter filter,
            boolean isSvSpan,
            boolean drawClickableRects,
            Function<Double, Double> chromPosToScreenPos,
            double canvasWidth,
            double x,
            Map<SampleTrack, Integer> trackToSlot,
            double[] yPositions,
            double sampleHeight,
            int[] lastDrawnPixelX) {
        int xPixel = (int) x;
        boolean lohMode = filter != null && filter.isLohMode();
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (call == null) {
                continue;
            }
            boolean homRef = node.isHomozygousRef(call);
            if (homRef && !lohMode) {
                continue;
            }
            SampleTrack track = call.getTrack();
            if (track == null) {
                continue;
            }
            if (!call.isUiVisible()) {
                continue;
            }
            Integer slot = trackToSlot.get(track);
            if (slot == null) {
                continue;
            }
            if (filter != null && !filter.passesSampleDisplay(node, call)) {
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
        return variant.svEnd > variant.position &&
               (variant.type == VcfVariantType.SV_DELETION ||
                variant.type == VcfVariantType.SV_DUPLICATION ||
                variant.type == VcfVariantType.SV_INVERSION ||
                variant.type == VcfVariantType.SV_INSERTION);
    }

    private void drawSvSpan(GraphicsContext gc, VariantNode variant, VariantNode.SampleCall call,
                           Function<Double, Double> chromPosToScreenPos, double canvasWidth,
                           double startX, double y, double sampleHeight,
                           boolean recordHit) {
        Color baseColor = colorForCall(variant, call);
        double opacity = callOpacity(call, 0.7, variant.isHomozygousRef(call));

        double endX = chromPosToScreenPos.apply((double) variant.svEnd);

        double x1 = Math.max(0, startX);
        double x2 = Math.min(canvasWidth, endX);
        double width = x2 - x1;

        if (width < 1) width = 1;

        gc.setFill(baseColor);
        if (opacity != 1.0) gc.setGlobalAlpha(opacity);

        double rectHeight = sampleHeight >= 4 ? sampleHeight - 1 : sampleHeight;
        gc.fillRect(x1, y, width, rectHeight);

        if (opacity != 1.0) gc.setGlobalAlpha(1.0);

        if (recordHit) {
            hitRegions.add(new VariantHit(variant, call, x1, y, x1 + width, y + rectHeight));
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
