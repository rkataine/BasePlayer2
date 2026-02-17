package org.baseplayer.controllers.commands;

import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawFunctions;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

/**
 * Handles file operations: loading BAM, VCF, BED, BigWig files.
 */
public class FileCommands {
  
  private static final SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
  
  /**
   * Load simulated VCF data for testing.
   * 
   * @param samples Number of samples to generate
   * @param variantsPerChrom Number of variants per chromosome
   */
  public static void loadSimulatedVCF(int samples, int variantsPerChrom) {
    // Generate simulated variants for all chromosomes
    DrawStack.generateSimulatedVariants(samples, variantsPerChrom);
    
    // Update sample height
    sampleRegistry.setSampleHeight(MainController.drawPane.getHeight() / sampleRegistry.getVisibleSampleCount());
    
    // Load variants for each stack based on its chromosome
    for (var stack : MainController.drawStacks) {
      stack.loadSimulatedVariants();
    }
    
    DrawFunctions.update.set(!DrawFunctions.update.get());
  }
  
  /**
   * Open a file dialog for loading files.
   * Currently supports VCF for testing.
   * 
   * @param fileType Type of file (VCF, BAM, BED, etc.)
   */
  public static void openFile(String fileType) {
    if (fileType.equals("VCF")) {
      // For now, use simulated data
      loadSimulatedVCF(5, 100_000);
    }
    // TODO: Implement actual file loading dialogs for other types
  }
}
