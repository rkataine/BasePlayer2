package org.baseplayer.controllers.commands;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.io.VcfManager;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.ServiceRegistry;

/**
 * Handles navigation operations: zoom, pan, chromosome switching, gene navigation.
 */
public class NavigationCommands {

  private static final DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
  /**
   * Shift centered single-position windows by +0.5 bp so base tiles align with
   * the center marker borders rather than sitting half a base off.
   */
  private static final double SINGLE_BASE_ALIGNMENT_BP = 0.5;

  private record ViewWindow(double start, double end) {}

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
      stack.sampleTrackCanvas.zoomAnimation(middle - flank, middle + flank);
    } else {
      // Zoom in by 75% (show 25% of current view)
      double newViewLength = stack.getViewLength() * 0.25;
      double newStart = middle - newViewLength / 2;
      double newEnd = middle + newViewLength / 2;
      stack.sampleTrackCanvas.zoomAnimation(newStart, newEnd);
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
    if (stack.getViewLength() >= stack.chromSize * 0.99) {
      return;
    }
    
    if (fullChrom) {
      // Zoom all the way out to full chromosome
      stack.sampleTrackCanvas.zoomAnimation(1, stack.chromSize + 1);
    } else {
      // Zoom out by 300% (triple the view)
      double middle = stack.middlePos();
      double newViewLength = Math.min(stack.getViewLength() * 3, stack.chromSize);
      
      // Don't zoom out if new view would be essentially the same (within 1% of full)
      if (newViewLength >= stack.chromSize * 0.99) {
        stack.sampleTrackCanvas.zoomAnimation(1, stack.chromSize + 1);
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
      
      stack.sampleTrackCanvas.zoomAnimation(newStart, newEnd);
    }
  }
  
  /**
   * Navigate to a genomic position on a specific chromosome on the hover stack.
   * 
   * @param chromosome Chromosome name (e.g., "1", "X", "MT")
   * @param start Start position (1-based)
   * @param end End position (1-based, inclusive)
   */
  public static void navigateToPosition(String chromosome, int start, int end) {
    DrawStack stack = stackManager.getHoverStack();
    if (stack != null) {
      stack.navigateTo(chromosome, start, end);
    }
  }
  
  /**
   * Navigate to a genomic position on the hover stack (current chromosome).
   * 
   * @param start Start position (1-based)
   * @param end End position (1-based, inclusive)
   */
  public static void navigateToPosition(int start, int end) {
    DrawStack stack = stackManager.getHoverStack();
    if (stack != null) {
      stack.navigateTo(stack.getChromosome(), start, end);
    }
  }
  
  /**
   * Navigate to a single position with minZoom flanks on a specific chromosome.
   * 
   * @param chromosome Chromosome name (e.g., "1", "X", "MT")
   * @param position Position to center on (1-based)
   */
  public static void navigateToPosition(String chromosome, int position) {
    ViewWindow window = centeredWindow(position, GenomicCanvas.minZoom, SINGLE_BASE_ALIGNMENT_BP);
    DrawStack stack = stackManager.getHoverStack();
    if (stack != null) {
      stack.navigateTo(chromosome, window.start(), window.end());
    }
  }
  
  /**
   * Navigate to a single position with minZoom flanks (current chromosome).
   * 
   * @param position Position to center on (1-based)
   */
  public static void navigateToPosition(int position) {
    ViewWindow window = centeredWindow(position, GenomicCanvas.minZoom, SINGLE_BASE_ALIGNMENT_BP);
    DrawStack stack = stackManager.getHoverStack();
    if (stack != null) {
      stack.navigateTo(stack.getChromosome(), window.start(), window.end());
    }
  }

  /**
   * Build a symmetric [start,end] window around a center position.
   *
   * @param centerBp genomic center position (1-based coordinates)
   * @param viewLengthBp desired window length in bp
   * @param alignmentOffsetBp optional bp offset used for base/pixel alignment
   */
  private static ViewWindow centeredWindow(double centerBp, double viewLengthBp, double alignmentOffsetBp) {
    double half = viewLengthBp / 2.0;
    double start = centerBp - half + alignmentOffsetBp;
    return new ViewWindow(start, start + viewLengthBp);
  }
  
  /**
   * Navigate to a gene by name.
   * Switches chromosome if needed and zooms to gene location with variant region load.
   * 
   * @param geneName Name of the gene to navigate to
   */
  public static void navigateToGene(String geneName) {
    navigateToGene(geneName, true);
  }

  /**
   * Navigate to a gene by name.
   * Switches chromosome if needed and zooms to gene location.
   *
   * @param geneName Name of the gene to navigate to
   * @param loadVariantRegion whether to trigger VCF region loading as part of navigation
   */
  public static void navigateToGene(String geneName, boolean loadVariantRegion) {
    GeneLocation loc = AnnotationData.getGeneLocation(geneName);
    if (loc == null) return;
    DrawStack stack = stackManager.getHoverStack();
    if (stack == null && !stackManager.isEmpty()) {
      stack = stackManager.getFirst();
    }
    applyGeneNavigation(stack, loc, loadVariantRegion);
    AnnotationData.clearHighlightedGene();
  }

  public static void navigateToGenes(List<String> geneNames) {
    if (geneNames == null || geneNames.isEmpty()) {
      return;
    }
    List<GeneLocation> loci = new ArrayList<>();
    List<String> seen = new ArrayList<>();
    for (String raw : geneNames) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String name = raw.trim();
      boolean duplicate = false;
      for (String existing : seen) {
        if (existing.equalsIgnoreCase(name)) {
          duplicate = true;
          break;
        }
      }
      if (duplicate) {
        continue;
      }
      GeneLocation loc = AnnotationData.getGeneLocation(name);
      if (loc == null) {
        continue;
      }
      seen.add(name);
      loci.add(loc);
    }
    if (loci.isEmpty()) {
      return;
    }
    if (loci.size() == 1) {
      navigateToGene(seen.get(0), true);
      return;
    }

    for (int i = 0; i < loci.size(); i++) {
      GeneLocation loc = loci.get(i);
      if (i < stackManager.size()) {
        applyGeneNavigation(stackManager.getStacks().get(i), loc, true);
      } else {
        long[] view = geneViewBounds(loc);
        MainController.addStackAtRegion(loc.chrom(), view[0], view[1]);
      }
    }
    AnnotationData.clearHighlightedGene();
  }

  private static void applyGeneNavigation(DrawStack stack, GeneLocation loc, boolean loadVariantRegion) {
    if (stack == null || loc == null) {
      return;
    }
    if (loadVariantRegion) {
      VcfManager.getInstance().loadRegionVariants(loc.chrom(), loc.start(), loc.end());
    }
    long[] view = geneViewBounds(loc);
    stack.navigateTo(loc.chrom(), view[0], view[1]);
  }

  private static long[] geneViewBounds(GeneLocation loc) {
    long padding = Math.max(1000L, (loc.end() - loc.start()) / 2);
    return new long[] { loc.start() - padding, loc.end() + padding };
  }
  
  /**
   * Switch chromosome on ALL stacks (global chromosome change).
   * 
   * @param chromosome Chromosome name (e.g., "1", "X", "MT")
   */
  public static void switchChromosome(String chromosome) {
    for (var stack : stackManager.getStacks()) {
      stack.switchToChromosome(chromosome);
    }
  }
}
