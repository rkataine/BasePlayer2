package org.baseplayer.controllers.commands;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.GeneLocation;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.ServiceRegistry;

/**
 * Handles navigation operations: zoom, pan, chromosome switching, gene navigation.
 */
public class NavigationCommands {

  private static final DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();

  /**
   * Zoom in on the hover stack.
   * Single click: 75% zoom (show 25% of current view)
   * Double click: zoom to minZoom
   */
  public static void zoomIn() {
    zoomIn(false);
  }
  
  public static void zoomIn(boolean maxZoom) {
    if (stackManager.getHoverStack() == null) return;
    
    var stack = stackManager.getHoverStack();
    double middle = stack.middlePos();
    
    if (maxZoom) {
      // Zoom all the way in (minZoom)
      int flank = GenomicCanvas.minZoom / 2;
      stack.alignmentCanvas.zoomAnimation(middle - flank, middle + flank);
    } else {
      // Zoom in by 75% (show 25% of current view)
      double newViewLength = stack.viewLength * 0.25;
      double newStart = middle - newViewLength / 2;
      double newEnd = middle + newViewLength / 2;
      stack.alignmentCanvas.zoomAnimation(newStart, newEnd);
    }
  }
  
  /**
   * Zoom out on the hover stack.
   * Single click: 300% zoom (triple the view)
   * Double click: zoom to full chromosome
   */
  public static void zoomOut() {
    zoomOut(false);
  }
  
  public static void zoomOut(boolean fullChrom) {
    if (stackManager.getHoverStack() == null) return;
    
    var stack = stackManager.getHoverStack();
    
    // Prevent zoom out if already showing 99% or more of the chromosome
    if (stack.viewLength >= stack.chromSize * 0.99) {
      return;
    }
    
    if (fullChrom) {
      // Zoom all the way out to full chromosome
      stack.alignmentCanvas.zoomAnimation(1, stack.chromSize + 1);
    } else {
      // Zoom out by 300% (triple the view)
      double middle = stack.middlePos();
      double newViewLength = Math.min(stack.viewLength * 3, stack.chromSize);
      
      // Don't zoom out if new view would be essentially the same (within 1% of full)
      if (newViewLength >= stack.chromSize * 0.99) {
        stack.alignmentCanvas.zoomAnimation(1, stack.chromSize + 1);
        return;
      }
      
      double newStart = middle - newViewLength / 2;
      double newEnd = middle + newViewLength / 2;
      
      // Clamp to chromosome bounds
      if (newStart < 1) {
        newStart = 1;
        newEnd = newStart + newViewLength;
      }
      if (newEnd > stack.chromSize + 1) {
        newEnd = stack.chromSize + 1;
        newStart = newEnd - newViewLength;
      }
      
      stack.alignmentCanvas.zoomAnimation(newStart, newEnd);
    }
  }
  
  /**
   * Navigate to a genomic position on the hover stack.
   * 
   * @param start Start position (1-based)
   * @param end End position (1-based, inclusive)
   */
  public static void navigateToPosition(int start, int end) {
    if (stackManager.getHoverStack() != null) {
      stackManager.getHoverStack().alignmentCanvas.zoomAnimation(start, end);
    }
  }
  
  /**
   * Navigate to a single position with minZoom flanks.
   * 
   * @param position Position to center on (1-based)
   */
  public static void navigateToPosition(int position) {
    int flank = GenomicCanvas.minZoom / 2;
    navigateToPosition(position - flank, position + flank);
  }
  
  /**
   * Navigate to a gene by name.
   * Switches chromosome if needed and zooms to gene location with padding.
   * 
   * @param geneName Name of the gene to navigate to
   */
  public static void navigateToGene(String geneName) {
    GeneLocation loc = AnnotationData.getGeneLocation(geneName);
    if (loc == null) return;
    
    // Only navigate the active/hover stack
    if (stackManager.getHoverStack() != null) {
      DrawStack stack = stackManager.getHoverStack();
      
      // Switch chromosome if needed (only for this stack)
      if (!loc.chrom().equals(stack.chromosome)) {
        Long chromLength = DrawStack.CHROMOSOME_SIZES.get(loc.chrom());
        if (chromLength != null) {
          stack.chromosome = loc.chrom();
          stack.chromSize = chromLength;
          stack.chromosomeDropdown.setValue(loc.chrom());
          stack.loadSimulatedVariants();
          stack.alignmentCanvas.setStartEnd(1.0, chromLength + 1);
          stack.chromosomeCanvas.setStartEnd(1.0, chromLength + 1);
        }
      }
      
      // Navigate to gene location with some padding
      long padding = Math.max(1000, (loc.end() - loc.start()) / 2);
      double newStart = loc.start() - padding;
      double newEnd = loc.end() + padding;
      stack.alignmentCanvas.zoomAnimation(newStart, newEnd);
    }
    
    AnnotationData.clearHighlightedGene();
  }
  
  /**
   * Switch chromosome on ALL stacks (global chromosome change).
   * 
   * @param chromosome Chromosome name (e.g., "1", "X", "MT")
   */
  public static void switchChromosome(String chromosome) {
    Long chromLength = DrawStack.CHROMOSOME_SIZES.get(chromosome);
    if (chromLength == null) return;
    
    // Update ALL stacks to same chromosome (global chromosome change)
    for (var stack : stackManager.getStacks()) {
      stack.chromosome = chromosome;
      stack.chromSize = chromLength;
      stack.chromosomeDropdown.setValue(chromosome);
      stack.loadSimulatedVariants();
      // Properly set coordinates and derived values (pixelSize, scale)
      stack.alignmentCanvas.setStartEnd(1.0, chromLength + 1);
      stack.chromosomeCanvas.setStartEnd(1.0, chromLength + 1);
    }
  }
}
