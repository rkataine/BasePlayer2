package org.baseplayer.variant.draw;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.VariantDrawSeek;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.VisibleVariantIndex;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.List;
import java.util.function.Function;

/**
 * Draws variants as vertical lines across sample tracks.
 * 
 * Optimized for:
 * - Memory efficiency: shares VariantNode across samples
 * - Drawing speed: only draws visible samples using VisibleVariantIndex
 * - Visual clarity: colors by variant type, respects sample filtering/scrolling
 */
public class VariantDrawer {
    
    private final SampleRegistry sampleRegistry;
    private final VisibleVariantIndex visibleIndex;
    private final VariantDrawSeek drawSeek = new VariantDrawSeek();
    
    // Color scheme for different variant types
    private static final Color COLOR_SNV = Color.web("#4A90E2");          // Blue
    private static final Color COLOR_INSERTION = Color.web("#7ED321");     // Green
    private static final Color COLOR_DELETION = Color.web("#F5A623");      // Orange
    private static final Color COLOR_MNV = Color.web("#BD10E0");           // Purple
    private static final Color COLOR_COMPLEX = Color.web("#B8E986");       // Light green
    
    // SV colors (match master track)
    private static final Color COLOR_SV_DELETION = Color.web("#00cc44");      // Green
    private static final Color COLOR_SV_INVERSION = Color.web("#4488ff");     // Blue
    private static final Color COLOR_SV_DUPLICATION = Color.web("#c0c0d0");   // Grayish white
    private static final Color COLOR_SV_INSERTION = Color.web("#33cc66");     // Light green
    private static final Color COLOR_SV_TRANSLOCATION = Color.web("#ffdd00"); // Yellow
    private static final Color COLOR_SV_BREAKEND = Color.web("#c0c0c0");      // Light gray
    
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

        variantList.ensureVisibleChain(filter);

        int[] visibleTrackIndices = visibleIndex.getSampleTrackIndices();
        double[] yPositions = visibleIndex.getYPositions();

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
            drawNodeForVisibleSamples(
                gc, node, filter, true, chromPosToScreenPos, canvasWidth,
                chromPosToScreenPos.apply((double) node.position),
                visibleTrackIndices, yPositions, sampleHeight, lastDrawnPixelX);
        }

        // Point variants and in-window SV starts via nextVisible skip chain.
        VariantNode node = drawSeek.seek(variantList, screenStart);
        while (node != null && node.position <= screenEnd) {
            double x = chromPosToScreenPos.apply((double) node.position);
            boolean isSvSpan = isSvWithSpan(node);
            boolean isVisible = (x >= 0 && x <= canvasWidth)
                || (isSvSpan && node.svEnd >= screenStart && node.position <= screenEnd);

            if (isVisible) {
                drawNodeForVisibleSamples(
                    gc, node, filter, isSvSpan, chromPosToScreenPos, canvasWidth, x,
                    visibleTrackIndices, yPositions, sampleHeight, lastDrawnPixelX);
            }

            node = node.nextVisible;
        }
    }

    private void drawNodeForVisibleSamples(
            GraphicsContext gc,
            VariantNode node,
            VariantFilter filter,
            boolean isSvSpan,
            Function<Double, Double> chromPosToScreenPos,
            double canvasWidth,
            double x,
            int[] visibleTrackIndices,
            double[] yPositions,
            double sampleHeight,
            int[] lastDrawnPixelX) {
        int xPixel = (int) x;
        for (int i = 0; i < visibleTrackIndices.length; i++) {
            int trackIndex = visibleTrackIndices[i];
            VariantNode.SampleCall call = node.getSampleCall(trackIndex);
            if (call == null) continue;
            if (filter != null && !filter.passesSampleThresholds(node, call)) continue;

            if (!isSvSpan && xPixel == lastDrawnPixelX[i]) continue;

            double y = yPositions[i];
            if (isSvSpan) {
                drawSvSpan(gc, node, call, chromPosToScreenPos, canvasWidth, x, y, sampleHeight);
            } else {
                drawVariantLine(gc, node, call, x, y, sampleHeight);
                lastDrawnPixelX[i] = xPixel;
            }
        }
    }
    
    private void drawVariantLine(GraphicsContext gc, VariantNode variant, VariantNode.SampleCall call,
                                 double x, double y, double sampleHeight) {
        Color baseColor = getVariantColor(variant.type);
        double opacity = 1.0;

        if (call != null) {
            if (call.gt != null && VariantNode.isHetGt(call.gt)) opacity = 0.6;
            if (call.quality >= 0 && call.quality < MIN_QUALITY_FULL_OPACITY) {
                opacity *= (call.quality / MIN_QUALITY_FULL_OPACITY);
            }
        }

        gc.setStroke(baseColor);
        gc.setLineWidth(1.0);
        if (opacity != 1.0) gc.setGlobalAlpha(opacity);

        double lineHeight = sampleHeight >= 3 ? sampleHeight : 1;
        gc.strokeLine(x, y, x, y + lineHeight);

        if (call != null && call.gt != null && sampleHeight >= 6) {
            String[] a = call.gt.split("[/|]");
            if (a.length >= 2 && a[0].equals(variant.alt) && a[1].equals(variant.alt)) {
                gc.setFill(baseColor);
                gc.fillRect(x - 1, y, 3, 2);
            }
        }

        if (opacity != 1.0) gc.setGlobalAlpha(1.0);
    }
    
    /**
     * Check if this variant is an SV with a defined span (has svEnd).
     */
    private boolean isSvWithSpan(VariantNode variant) {
        return variant.svEnd > variant.position && 
               (variant.type == VcfVariantType.SV_DELETION ||
                variant.type == VcfVariantType.SV_DUPLICATION ||
                variant.type == VcfVariantType.SV_INVERSION ||
                variant.type == VcfVariantType.SV_INSERTION);
    }
    
    /**
     * Draw an SV span as a rectangle spanning start to end positions.
     */
    private void drawSvSpan(GraphicsContext gc, VariantNode variant, VariantNode.SampleCall call,
                           Function<Double, Double> chromPosToScreenPos, double canvasWidth,
                           double startX, double y, double sampleHeight) {
        Color baseColor = getVariantColor(variant.type);
        double opacity = 0.7;  // Slightly transparent for overlapping spans

        if (call != null) {
            if (call.gt != null && VariantNode.isHetGt(call.gt)) opacity = 0.5;
            if (call.quality >= 0 && call.quality < MIN_QUALITY_FULL_OPACITY) {
                opacity *= (call.quality / MIN_QUALITY_FULL_OPACITY);
            }
        }

        // Calculate end position
        double endX = chromPosToScreenPos.apply((double) variant.svEnd);
        
        // Clamp to canvas bounds
        double x1 = Math.max(0, startX);
        double x2 = Math.min(canvasWidth, endX);
        double width = x2 - x1;
        
        // Ensure minimum visibility (1 pixel)
        if (width < 1) width = 1;
        
        // Draw span as filled rectangle
        gc.setFill(baseColor);
        if (opacity != 1.0) gc.setGlobalAlpha(opacity);
        
        double rectHeight = sampleHeight >= 4 ? sampleHeight - 1 : sampleHeight;
        gc.fillRect(x1, y, width, rectHeight);
        
        if (opacity != 1.0) gc.setGlobalAlpha(1.0);
    }
    
    /**
     * Get color for a variant type.
     */
    private Color getVariantColor(VcfVariantType type) {
        switch (type) {
            case SNV:
                return COLOR_SNV;
            case INSERTION:
                return COLOR_INSERTION;
            case DELETION:
                return COLOR_DELETION;
            case MNV:
                return COLOR_MNV;
            case COMPLEX:
                return COLOR_COMPLEX;
            
            // SV colors
            case SV_DELETION:
                return COLOR_SV_DELETION;
            case SV_INVERSION:
                return COLOR_SV_INVERSION;
            case SV_DUPLICATION:
                return COLOR_SV_DUPLICATION;
            case SV_INSERTION:
                return COLOR_SV_INSERTION;
            case SV_TRANSLOCATION:
                return COLOR_SV_TRANSLOCATION;
            case SV_BREAKEND:
                return COLOR_SV_BREAKEND;
            
            default:
                return COLOR_COMPLEX;
        }
    }
    
    /**
     * Mark the visible index as dirty (needs rebuild).
     * Call this when visibility state changes externally.
     */
    public void markIndexDirty() {
        visibleIndex.markDirty();
    }
    
    /**
     * Get the visible variant index (for testing/debugging).
     */
    public VisibleVariantIndex getVisibleIndex() {
        return visibleIndex;
    }
}
