package org.baseplayer.variant.draw;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
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
    private static final Color COLOR_LOW_QUALITY = Color.web("#666666", 0.3); // Gray semi-transparent
    
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
                    double masterTrackHeight) {
        
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
        
        VariantNode node = variantList.findFirstAfter(screenStart);
        
        // Draw variants in screen range
        int[] visibleTrackIndices = visibleIndex.getSampleTrackIndices();
        double[] yPositions = visibleIndex.getYPositions();
        
        while (node != null && node.position <= screenEnd) {
            double x = chromPosToScreenPos.apply((double) node.position);
            
            // Skip if off-screen horizontally
            if (x >= 0 && x <= canvasWidth) {
                // Draw variant line for each visible sample that has it
                for (int i = 0; i < visibleTrackIndices.length; i++) {
                    int trackIndex = visibleTrackIndices[i];
                    
                    if (node.hasSample(trackIndex)) {
                        double y = yPositions[i];
                        drawVariantLine(gc, node, trackIndex, x, y, sampleHeight);
                    }
                }
            }
            
            node = node.next;
        }
    }
    
    /**
     * Draw a single variant line for one sample.
     */
    private void drawVariantLine(GraphicsContext gc, VariantNode variant, int sampleTrackIndex,
                                 double x, double y, double sampleHeight) {
        
        // Get genotype info if available
        VariantNode.GenotypeInfo genotype = variant.getGenotype(sampleTrackIndex);
        
        // Determine color based on variant type and genotype
        Color baseColor = getVariantColor(variant.type);
        
        // Adjust opacity based on genotype (het vs hom) and quality
        double opacity = 1.0;
        
        if (genotype != null) {
            // Lower opacity for heterozygous variants
            if (variant.isHeterozygous(sampleTrackIndex)) {
                opacity = 0.6;
            }
            
            // Dim low-quality variants
            if (genotype.quality >= 0 && genotype.quality < MIN_QUALITY_FULL_OPACITY) {
                opacity *= (genotype.quality / MIN_QUALITY_FULL_OPACITY);
            }
        }
        
        Color drawColor = new Color(
            baseColor.getRed(),
            baseColor.getGreen(),
            baseColor.getBlue(),
            opacity
        );
        
        // Draw the variant line
        gc.setStroke(drawColor);
        gc.setLineWidth(1.0);
        
        // Line height: use full sample height if tall enough, otherwise single pixel
        double lineHeight = sampleHeight >= 3 ? sampleHeight : 1;
        
        gc.strokeLine(x, y, x, y + lineHeight);
        
        // Optional: add a small marker at top for homozygous variants
        if (genotype != null && variant.isHomozygousAlt(sampleTrackIndex) && sampleHeight >= 6) {
            gc.setFill(drawColor);
            gc.fillRect(x - 1, y, 3, 2);
        }
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
