package org.baseplayer.io;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.baseplayer.MainApp;
import org.baseplayer.SharedModel;
import org.baseplayer.draw.DrawFunctions;
import org.baseplayer.reads.bam.SampleFile;

import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;

/**
 * Manages adding sample data files (BAM, CRAM, VCF, BED, BigWig, etc.)
 * to the sample tracks panel.
 */
public class SampleDataManager {

  /**
   * Open a BAM file chooser and add samples from the selected file(s).
   * Returns the list of sample names added, or empty list if cancelled.
   */
  public static List<String> addBamFiles() {
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Open BAM File(s)");
    File lastDir = UserPreferences.getLastDirectory("BAM");
    if (lastDir != null) {
      try {
        fileChooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException e) {
        // Directory became inaccessible, FileChooser will use system default
        System.err.println("Last directory not accessible: " + lastDir + ". Using default.");
      }
    }
    fileChooser.getExtensionFilters().addAll(
      new ExtensionFilter("BAM/CRAM Files", "*.bam", "*.cram"),
      new ExtensionFilter("All Files", "*.*")
    );

    List<File> files = fileChooser.showOpenMultipleDialog(MainApp.stage);
    if (files == null || files.isEmpty()) return Collections.emptyList();

    UserPreferences.setLastDirectory("BAM", files.get(0).getParentFile());

    List<String> addedSamples = new ArrayList<>();
    for (File file : files) {
      try {
        SampleFile sampleFile = new SampleFile(file.toPath());
        SharedModel.bamFiles.add(sampleFile);
        SharedModel.sampleList.add(sampleFile.name);
        addedSamples.add(sampleFile.name);
        System.out.println("Loaded BAM: " + sampleFile.name + " (" + file.getName() + ")");
      } catch (IOException e) {
        System.err.println("Failed to open BAM file: " + file + " - " + e.getMessage());
      }
    }

    if (!addedSamples.isEmpty()) {
      SharedModel.lastVisibleSample = SharedModel.sampleList.size() - 1;
      SharedModel.sampleHeight = 0; // Will be recalculated on draw
      DrawFunctions.update.set(!DrawFunctions.update.get());
    }

    return addedSamples;
  }

  /**
   * Remove a sample by index and close its file handle.
   */
  public static void removeSample(int index) {
    if (index < 0 || index >= SharedModel.bamFiles.size()) return;
    
    try {
      SharedModel.bamFiles.get(index).close();
    } catch (IOException e) {
      System.err.println("Error closing BAM: " + e.getMessage());
    }
    SharedModel.bamFiles.remove(index);
    if (index < SharedModel.sampleList.size()) {
      SharedModel.sampleList.remove(index);
    }
    
    // Adjust visible range
    if (SharedModel.sampleList.isEmpty()) {
      SharedModel.firstVisibleSample = 0;
      SharedModel.lastVisibleSample = 0;
      SharedModel.scrollBarPosition = 0;
    } else {
      SharedModel.lastVisibleSample = Math.min(SharedModel.lastVisibleSample, SharedModel.sampleList.size() - 1);
      SharedModel.firstVisibleSample = Math.min(SharedModel.firstVisibleSample, SharedModel.lastVisibleSample);
    }
    
    DrawFunctions.update.set(!DrawFunctions.update.get());
  }

  /**
   * Add an overlay BAM to an existing sample track.
   * Opens a file chooser and adds the BAM data overlaid on the same track.
   */
  public static void addOverlayBam(int sampleIndex) {
    if (sampleIndex < 0 || sampleIndex >= SharedModel.bamFiles.size()) return;
    
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Add Overlay BAM to " + SharedModel.sampleList.get(sampleIndex));
    File lastDir = UserPreferences.getLastDirectory("BAM");
    if (lastDir != null) {
      try {
        fileChooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException e) {
        // Directory became inaccessible, FileChooser will use system default
        System.err.println("Last directory not accessible: " + lastDir + ". Using default.");
      }
    }
    fileChooser.getExtensionFilters().addAll(
      new ExtensionFilter("BAM/CRAM Files", "*.bam", "*.cram"),
      new ExtensionFilter("All Files", "*.*")
    );
    
    File file = fileChooser.showOpenDialog(MainApp.stage);
    if (file == null) return;
    
    UserPreferences.setLastDirectory("BAM", file.getParentFile());
    
    try {
      SampleFile overlayFile = new SampleFile(file.toPath());
      SharedModel.bamFiles.get(sampleIndex).addOverlay(overlayFile);
      System.out.println("Added overlay BAM: " + overlayFile.name + " to " + SharedModel.sampleList.get(sampleIndex));
      DrawFunctions.update.set(!DrawFunctions.update.get());
    } catch (IOException e) {
      System.err.println("Failed to open overlay BAM: " + file + " - " + e.getMessage());
    }
  }
}
