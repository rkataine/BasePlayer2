package org.baseplayer.controllers.commands;

import java.io.File;

import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.SampleDataManager;

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
    if (fileType == null) return;

    switch (fileType.toUpperCase()) {
      case "BAM" -> SampleDataManager.addBamFiles();
      case "BED" -> SampleDataManager.addBedSampleFile();
      case "BIGWIG" -> SampleDataManager.addBigWigFile();
      case "VCF", "CTRL", "SES" -> {
        System.out.println("File menu action not implemented yet: " + fileType);
      }
      default -> {
        System.out.println("Unknown file menu action: " + fileType);
      }
    }
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }

  /**
   * Open a file directly from the recent-files menu.
   */
  public static void openRecentFile(String fileType, String path) {
    if (path == null || path.isBlank()) return;
    File file = new File(path);
    if (!file.exists() || !file.isFile()) {
      System.err.println("Recent file missing: " + path);
      return;
    }

    String resolvedType = fileType;
    if (resolvedType == null || resolvedType.isBlank()) {
      String lower = file.getName().toLowerCase();
      if (lower.endsWith(".bam") || lower.endsWith(".cram")) resolvedType = "BAM";
      else if (lower.endsWith(".bed") || lower.endsWith(".bed.gz")) resolvedType = "BED";
      else if (lower.endsWith(".bw") || lower.endsWith(".bigwig")) resolvedType = "BIGWIG";
      else resolvedType = "";
    }

    switch (resolvedType.toUpperCase()) {
      case "BAM" -> SampleDataManager.addBamFile(file);
      case "BED" -> SampleDataManager.addBedSampleFile(file);
      case "BIGWIG" -> SampleDataManager.addBigWigFile(file);
      default -> System.out.println("Unsupported recent file type: " + resolvedType + " (" + path + ")");
    }
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }
}
