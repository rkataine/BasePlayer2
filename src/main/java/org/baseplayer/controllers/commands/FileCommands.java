package org.baseplayer.controllers.commands;

import org.baseplayer.draw.GenomicCanvas;

/**
 * Handles file operations: loading BAM, VCF, BED, BigWig files.
 */
public class FileCommands {


  /**
   * Open a file dialog for loading files.
   *
   * @param fileType Type of file (VCF, BAM, BED, etc.)
   */
  public static void openFile(String fileType) {
    // TODO: Implement file loading dialogs
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }
}
