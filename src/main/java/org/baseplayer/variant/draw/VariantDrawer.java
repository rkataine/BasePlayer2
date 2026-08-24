package org.baseplayer.variant.draw;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
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
    private static final Color COLOR_SV_BREAKEND = Color.web("#ffdd00");      // Yellow
    
    // Quality thresholds
    private static final double MIN_QUALITY_FULL_OPACITY = 30.0;
    
    public VariantDrawer() {
        this.sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
        this.visibleIndex = new VisibleVariantIndex();
    }
    
    /**
     * Draw variants for all visible samples.
     * 
     * @param gc Graphics context to draw on
     * @param variantList The shared variant list (one per genomic region)
     * @param drawStack Current view parameters
     * @param chromPosToScreenPos Function to convert genomic position to screen X
     * @param canvasWidth Width of the canvas
     * @param masterTrackHeight Height of the master track at top
     */
    public void draw(GraphicsContext gc,
                    VariantList variantList,
                    DrawStack drawStack,
                    Function<Double, Double> chromPosToScreenPos,
                    double canvasWidth,
                    double masterTrackHeight,
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
                sampleRegistry.getScrollBarPosition(),
                masterTrackHeight
            );
        }
        
        if (visibleIndex.getVisibleCount() == 0) {
            return;
        }
        
        // Find first variant in screen range
        long screenStart = Math.max(0, (long) drawStack.start);
        long screenEnd = (long) drawStack.end;
        
        // For SVs with spans, we need to start earlier to catch variants that start before
        // the screen but span into it. Use a generous lookback (e.g., 10x view length).
        long lookbackDistance = Math.min(screenStart, (screenEnd - screenStart) * 10);
        long searchStart = screenStart - lookbackDistance;
        
        VariantNode node = variantList.findFirstAfter(searchStart);
        
        // Draw variants in screen range (or spanning into it)
        int[] visibleTrackIndices = visibleIndex.getSampleTrackIndices();
        double[] yPositions = visibleIndex.getYPositions();
        
        // Track last drawn X pixel per sample to avoid overdraw of point variants when zoomed out
        // (SV spans are exempt from this deduplication as they span multiple pixels)
        int[] lastDrawnPixelX = new int[visibleTrackIndices.length];
        for (int i = 0; i < lastDrawnPixelX.length; i++) {
            lastDrawnPixelX[i] = -1;
        }
        
        while (node != null && node.position <= screenEnd) {
            double x = chromPosToScreenPos.apply((double) node.position);
            
            // For SVs with spans, check if span overlaps screen even if start is off-screen
            boolean isVisible = (x >= 0 && x <= canvasWidth);
            if (!isVisible && isSvWithSpan(node)) {
                // Check if SV span overlaps the screen
                isVisible = node.svEnd >= screenStart && node.position <= screenEnd;
            }
            
            if (isVisible) {
                int xPixel = (int) x;
                boolean isSvSpan = isSvWithSpan(node);
                
                for (int i = 0; i < visibleTrackIndices.length; i++) {
                    int trackIndex = visibleTrackIndices[i];
                    if (node.hasSample(trackIndex)) {
                        if (filter != null && !filter.passes(node, trackIndex)) continue;
                        
                        // Skip point variants if this sample already has one at this X pixel
                        // SV spans are never skipped—they span multiple pixels and always need to be drawn
                        if (!isSvSpan && xPixel == lastDrawnPixelX[i]) continue;
                        
                        double y = yPositions[i];
                        
                        // Check if this is an SV with a span
                        if (isSvSpan) {
                            drawSvSpan(gc, node, trackIndex, chromPosToScreenPos, canvasWidth, x, y, sampleHeight);
                        } else {
                            drawVariantLine(gc, node, trackIndex, x, y, sampleHeight);
                            lastDrawnPixelX[i] = xPixel;
                        }
                    }
                }
            }
            
            node = node.next;
        }
    }
    
    private void drawVariantLine(GraphicsContext gc, VariantNode variant, int sampleTrackIndex,
                                 double x, double y, double sampleHeight) {
        VariantNode.SampleCall call = variant.getSampleCall(sampleTrackIndex);
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
    private void drawSvSpan(GraphicsContext gc, VariantNode variant, int sampleTrackIndex,
                           Function<Double, Double> chromPosToScreenPos, double canvasWidth,
                           double startX, double y, double sampleHeight) {
        VariantNode.SampleCall call = variant.getSampleCall(sampleTrackIndex);
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
