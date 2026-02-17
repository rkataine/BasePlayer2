package org.baseplayer.io;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.baseplayer.MainApp;
import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawFunctions;
import org.baseplayer.sample.Sample;
import org.baseplayer.sample.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.tracks.BedTrack;
import org.baseplayer.tracks.BigWigTrack;
import org.baseplayer.tracks.FeatureTracksCanvas;

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

    SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    
    List<String> addedSamples = new ArrayList<>();
    
    // Load files in background threads to keep UI responsive
    for (File file : files) {
      java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        try {
          Sample sample = new Sample(file.toPath());
          return sample;
        } catch (IOException e) {
          System.err.println("Failed to open BAM file: " + file + " - " + e.getMessage());
          return null;
        }
      }).thenAcceptAsync(sample -> {
        if (sample != null) {
          SampleTrack track = new SampleTrack(sample);
          sampleRegistry.getSampleTracks().add(track);
          sampleRegistry.getSampleList().add(sample.getName());
          sampleRegistry.setLastVisibleSample(sampleRegistry.getSampleList().size() - 1);
          sampleRegistry.setSampleHeight(0); // Will be recalculated on draw
          System.out.println("Loaded BAM: " + sample.getName() + " (" + file.getName() + ")");
          DrawFunctions.update.set(!DrawFunctions.update.get());
        }
      }, javafx.application.Platform::runLater);
      
      addedSamples.add(file.getName()); // Return filename immediately
    }

    if (!addedSamples.isEmpty()) {
      DrawFunctions.update.set(!DrawFunctions.update.get());
    }

    return addedSamples;
  }

  /**
   * Remove a sample by index and close its file handle.
   */
  public static void removeSample(int index) {
    SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    
    if (index < 0 || index >= sampleRegistry.getSampleTracks().size()) return;
    
    try {
      sampleRegistry.getSampleTracks().get(index).close();
    } catch (IOException e) {
      System.err.println("Error closing sample: " + e.getMessage());
    }
    sampleRegistry.getSampleTracks().remove(index);
    if (index < sampleRegistry.getSampleList().size()) {
      sampleRegistry.getSampleList().remove(index);
    }
    
    // Adjust visible range
    if (sampleRegistry.getSampleList().isEmpty()) {
      sampleRegistry.setFirstVisibleSample(0);
      sampleRegistry.setLastVisibleSample(0);
      sampleRegistry.setScrollBarPosition(0);
    } else {
      sampleRegistry.setLastVisibleSample(Math.min(sampleRegistry.getLastVisibleSample(), sampleRegistry.getSampleList().size() - 1));
      sampleRegistry.setFirstVisibleSample(Math.min(sampleRegistry.getFirstVisibleSample(), sampleRegistry.getLastVisibleSample()));
    }
    
    DrawFunctions.update.set(!DrawFunctions.update.get());
  }

  /**
   * Add a BAM/CRAM file to an existing individual's track.
   * Opens a file chooser and adds the BAM data under the same individual.
   */
  public static void addBamToTrack(int sampleIndex) {
    SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    
    if (sampleIndex < 0 || sampleIndex >= sampleRegistry.getSampleTracks().size()) return;
    
    SampleTrack track = sampleRegistry.getSampleTracks().get(sampleIndex);
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Add BAM/CRAM to " + track.getDisplayName());
    File lastDir = UserPreferences.getLastDirectory("BAM");
    if (lastDir != null) {
      try {
        fileChooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException e) {
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
    
    // Load file in background to keep UI responsive
    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        return new Sample(file.toPath());
      } catch (IOException e) {
        System.err.println("Failed to open BAM: " + file + " - " + e.getMessage());
        return null;
      }
    }).thenAcceptAsync(newSample -> {
      if (newSample != null) {
        track.addSample(newSample);
        System.out.println("Added BAM: " + newSample.getName() + " to " + track.getDisplayName());
        DrawFunctions.update.set(!DrawFunctions.update.get());
      }
    }, javafx.application.Platform::runLater);
  }

  /**
   * Add a BED file to an existing individual's track.
   * Opens a file chooser and adds the BED data under the same individual.
   */
  public static void addBedToTrack(int sampleIndex) {
    SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    
    if (sampleIndex < 0 || sampleIndex >= sampleRegistry.getSampleTracks().size()) return;
    
    SampleTrack track = sampleRegistry.getSampleTracks().get(sampleIndex);
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Add BED to " + track.getDisplayName());
    File lastDir = UserPreferences.getLastDirectory("BED");
    if (lastDir != null) {
      try {
        fileChooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException e) {
        System.err.println("Last directory not accessible: " + lastDir + ". Using default.");
      }
    }
    fileChooser.getExtensionFilters().addAll(
      new ExtensionFilter("BED files", "*.bed", "*.bed.gz"),
      new ExtensionFilter("All files", "*.*")
    );
    
    File file = fileChooser.showOpenDialog(MainApp.stage);
    if (file == null) return;
    
    UserPreferences.setLastDirectory("BED", file.getParentFile());
    
    // Load file in background to keep UI responsive
    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        BedTrack bedTrack = new BedTrack(file.toPath());
        return new Sample(file.toPath(), bedTrack);
      } catch (IOException e) {
        System.err.println("Failed to open BED: " + file + " - " + e.getMessage());
        return null;
      }
    }).thenAcceptAsync(newSample -> {
      if (newSample != null) {
        track.addSample(newSample);
        System.out.println("Added BED: " + newSample.getName() + " to " + track.getDisplayName());
        DrawFunctions.update.set(!DrawFunctions.update.get());
      }
    }, javafx.application.Platform::runLater);
  }
  
  /**
   * Add a BED file. Since BED files are region-based annotations,
   * they are added to the feature tracks panel.
   */
  public static void addBedFile() {
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Open BED File");
    File lastDir = UserPreferences.getLastDirectory("BED");
    if (lastDir != null) {
      try {
        fileChooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException e) {
        System.err.println("Last directory not accessible: " + lastDir + ". Using default.");
      }
    }
    fileChooser.getExtensionFilters().addAll(
      new ExtensionFilter("BED files", "*.bed", "*.bed.gz"),
      new ExtensionFilter("All files", "*.*")
    );
    
    File file = fileChooser.showOpenDialog(MainApp.stage);
    if (file != null) {
      UserPreferences.setLastDirectory("BED", file.getParentFile());
      FeatureTracksCanvas featureCanvas = MainController.getFeatureTracksCanvas();
      if (featureCanvas != null) {
        // Load file in background to keep UI responsive
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
          try {
            return new BedTrack(file.toPath());
          } catch (IOException e) {
            System.err.println("Failed to load BED file: " + e.getMessage());
            return null;
          }
        }).thenAcceptAsync(track -> {
          if (track != null) {
            featureCanvas.addTrack(track);
            featureCanvas.setCollapsed(false);
            System.out.println("Loaded BED file: " + file.getName());
          }
        }, javafx.application.Platform::runLater);
      }
    }
  }
  
  /**
   * Add a BigWig file. Since BigWig files are continuous coverage data,
   * they are added to the feature tracks panel.
   */
  public static void addBigWigFile() {
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Open BigWig File");
    File lastDir = UserPreferences.getLastDirectory("BIGWIG");
    if (lastDir != null) {
      try {
        fileChooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException e) {
        System.err.println("Last directory not accessible: " + lastDir + ". Using default.");
      }
    }
    fileChooser.getExtensionFilters().addAll(
      new ExtensionFilter("BigWig files", "*.bw", "*.bigwig", "*.bigWig"),
      new ExtensionFilter("All files", "*.*")
    );
    
    File file = fileChooser.showOpenDialog(MainApp.stage);
    if (file != null) {
      UserPreferences.setLastDirectory("BIGWIG", file.getParentFile());
      FeatureTracksCanvas featureCanvas = MainController.getFeatureTracksCanvas();
      if (featureCanvas != null) {
        // Load file in background to keep UI responsive
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
          try {
            return new BigWigTrack(file.toPath());
          } catch (IOException e) {
            System.err.println("Failed to load BigWig file: " + e.getMessage());
            return null;
          }
        }).thenAcceptAsync(track -> {
          if (track != null) {
            featureCanvas.addTrack(track);
            featureCanvas.setCollapsed(false);
            System.out.println("Loaded BigWig file: " + file.getName());
          }
        }, javafx.application.Platform::runLater);
      }
    }
  }
}
