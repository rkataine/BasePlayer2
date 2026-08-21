package org.baseplayer.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.baseplayer.MainApp;
import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BigWigTrack;
import org.baseplayer.features.FeatureTracksCanvas;
import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantLoader;

import javafx.application.Platform;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;

/**
 * Manages adding sample data files (BAM, CRAM, VCF, BED, BigWig, etc.)
 * to the sample tracks panel.
 */
public class SampleDataManager {

  private SampleDataManager() {
    // Utility class
  }

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
    return addBamFiles(files);
  }

  /**
   * Open BAM/CRAM files directly without showing a chooser.
   */
  public static List<String> addBamFiles(List<File> files) {
    if (files == null || files.isEmpty()) return Collections.emptyList();

    SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    ThreadRunner runner = ThreadRunner.get();
    List<String> addedSamples = new ArrayList<>();
    
    final int totalFiles = files.size();
    addedSamples.addAll(files.stream().map(File::getName).toList());

    runner.submit("Loading BAM/CRAM files",
        () -> {
            final java.util.concurrent.atomic.AtomicInteger fileIndex = new java.util.concurrent.atomic.AtomicInteger(0);
            for (File file : files) {
              if (file == null) continue;
              int currentIndex = fileIndex.incrementAndGet();
              
              // Update progress bar
              org.baseplayer.services.LoadingManager.get().setProgress(currentIndex, totalFiles);
              
              try {
                Sample sample = new Sample(file.toPath());
                SampleTrack track = new SampleTrack(sample);
                sampleRegistry.getSampleTracks().add(track);
              } catch (IOException e) {
                System.err.println("Failed to open BAM: " + file + " - " + e.getMessage());
              }
            }
            return null;
        },
        result -> {
            // All BAM files loaded; update visible range and redraw
            int trackCount = sampleRegistry.getDisplayedTrackCount();
            if (trackCount > 0) {
              sampleRegistry.setFirstVisibleSample(0);
              sampleRegistry.setLastVisibleSample(trackCount - 1);
              sampleRegistry.setSampleHeight(0);
            }
            GenomicCanvas.update.set(!GenomicCanvas.update.get());
        });

    return addedSamples;
  }

  /**
   * Open a single BAM/CRAM file directly without showing a chooser.
   */
  public static List<String> addBamFile(File file) {
    if (file == null) return Collections.emptyList();
    UserPreferences.setLastDirectory("BAM", file.getParentFile());
    return addBamFiles(Collections.singletonList(file));
  }

  /**
   * Remove a sample by index and close its file handle.
   */
  public static void removeSample(int index) {
    SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    
    if (index < 0 || index >= sampleRegistry.getSampleTracks().size()) return;

    int oldFirst = sampleRegistry.getFirstVisibleSample();
    int oldLast = sampleRegistry.getLastVisibleSample();
    int oldWindow = Math.max(1, oldLast - oldFirst + 1);
    int removedSlot = sampleRegistry.getDisplayedSlotForTrackIndex(index);
    
    try {
      sampleRegistry.getSampleTracks().get(index).close();
    } catch (IOException e) {
      System.err.println("Error closing sample: " + e.getMessage());
    }
    sampleRegistry.getSampleTracks().remove(index);
    if (index < sampleRegistry.getSampleList().size()) {
      sampleRegistry.getSampleList().remove(index);
    }
    
    // Remove variants for this track from all variant lists
    DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
    for (DrawStack stack : stackManager.getStacks()) {
      if (stack.alignmentCanvas != null) {
        org.baseplayer.variant.VariantList variantList = stack.alignmentCanvas.getVariantList();
        if (variantList != null) {
          int nodesRemoved = variantList.removeTrackIndex(index);
          System.err.println("[SampleDataManager.removeSample] Removed " + nodesRemoved + 
                           " variant nodes from " + stack.chromosome + " after removing track " + index);
        }
      }
    }
    
    // Adjust visible range
    int newCount = sampleRegistry.getDisplayedTrackCount();
    if (sampleRegistry.getSampleTracks().isEmpty() || newCount <= 0) {
      sampleRegistry.setFirstVisibleSample(-1);
      sampleRegistry.setLastVisibleSample(-1);
      sampleRegistry.setScrollBarPosition(0);
    } else {
      int newWindow = Math.min(oldWindow, newCount);

      int newFirst = oldFirst;
      if (removedSlot >= 0 && removedSlot < oldFirst) {
        // Removed before viewport in displayed order: shift one slot up.
        newFirst = oldFirst - 1;
      }

      int maxFirst = Math.max(0, newCount - newWindow);
      newFirst = Math.max(0, Math.min(maxFirst, newFirst));
      int newLast = newFirst + newWindow - 1;

      sampleRegistry.setFirstVisibleSample(newFirst);
      sampleRegistry.setLastVisibleSample(newLast);

      double viewportHeight = sampleRegistry.getSampleHeight() * Math.max(1, newWindow);
      double targetScroll = newFirst * sampleRegistry.getSampleHeight();
      sampleRegistry.setScrollBarPosition(
          sampleRegistry.clampScrollBarPosition(targetScroll, viewportHeight));
    }
    
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
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

    ThreadRunner runner = ThreadRunner.get();
    runner.submit("Opening " + file.getName() + "\u2026",
        () -> {
          try { return new Sample(file.toPath()); }
          catch (IOException e) {
            System.err.println("Failed to open BAM: " + file + " - " + e.getMessage());
            return null;
          }
        },
        newSample -> {
          if (newSample == null) return;
          track.addSample(newSample);
          UserPreferences.addRecentFile("BAM", file);
          GenomicCanvas.update.set(!GenomicCanvas.update.get());
          newSample.setOnFirstFetchStarted(() -> {
            ThreadRunner.RunnerTask readTask =
                runner.track("Loading reads: " + newSample.getName(), newSample::cancelAndSuspend);
            newSample.setOnFirstLoadComplete(readTask::complete);
          });
        });
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

    ThreadRunner.get().submit("Loading " + file.getName() + "\u2026",
        () -> {
          try {
            BedTrack bedTrack = new BedTrack(file.toPath());
            return new Sample(file.toPath(), bedTrack);
          } catch (IOException e) {
            System.err.println("Failed to open BED: " + file + " - " + e.getMessage());
            return null;
          }
        },
        newSample -> {
          if (newSample == null) return;
          track.addSample(newSample);
          UserPreferences.addRecentFile("BED", file);
          GenomicCanvas.update.set(!GenomicCanvas.update.get());
        });
  }

  /**
   * Add a BED file as a new sample track (same behavior as BAM add, but BED type).
   */
  public static void addBedSampleFile() {
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
    if (file == null) return;
    addBedSampleFile(file);
  }

  /**
   * Add a BED file as a new sample track directly without showing a chooser.
   */
  public static void addBedSampleFile(File file) {
    if (file == null) return;
    UserPreferences.setLastDirectory("BED", file.getParentFile());

    SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();

    ThreadRunner.get().submit("Loading " + file.getName() + "\u2026",
        () -> {
          try {
            BedTrack bedTrack = new BedTrack(file.toPath());
            return new Sample(file.toPath(), bedTrack);
          } catch (IOException e) {
            System.err.println("Failed to open BED: " + file + " - " + e.getMessage());
            return null;
          }
        },
        newSample -> {
          if (newSample == null) return;
          SampleTrack track = new SampleTrack(newSample);
          sampleRegistry.getSampleTracks().add(track);
          sampleRegistry.getSampleList().add(newSample.getName());
          if (sampleRegistry.getFirstVisibleSample() < 0) {
            sampleRegistry.setFirstVisibleSample(0);
          }
          sampleRegistry.setLastVisibleSample(sampleRegistry.getSampleList().size() - 1);
          sampleRegistry.setSampleHeight(0);
          UserPreferences.addRecentFile("BED", file);
          GenomicCanvas.update.set(!GenomicCanvas.update.get());
        });
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
    if (file == null) return;
    UserPreferences.setLastDirectory("BED", file.getParentFile());
    addBedFile(file);
  }

  /**
   * Add a BED file directly without showing a chooser.
   */
  public static void addBedFile(File file) {
    if (file == null) return;
    UserPreferences.setLastDirectory("BED", file.getParentFile());
    FeatureTracksCanvas featureCanvas = MainController.getFeatureTracksCanvas();
    if (featureCanvas == null) return;

    ThreadRunner.get().submit("Loading " + file.getName() + "\u2026",
        () -> {
          try { return new BedTrack(file.toPath()); }
          catch (IOException e) { System.err.println("Failed to load BED: " + e.getMessage()); return null; }
        },
        bedTrack -> {
          if (bedTrack == null) return;
          featureCanvas.addTrack(bedTrack);
          featureCanvas.setCollapsed(false);
          UserPreferences.addRecentFile("BED", file);
        });
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
    if (file == null) return;
    UserPreferences.setLastDirectory("BIGWIG", file.getParentFile());
    addBigWigFile(file);
  }

  /**
   * Add a BigWig file directly without showing a chooser.
   */
  public static void addBigWigFile(File file) {
    if (file == null) return;
    UserPreferences.setLastDirectory("BIGWIG", file.getParentFile());
    FeatureTracksCanvas featureCanvas = MainController.getFeatureTracksCanvas();
    if (featureCanvas == null) return;

    ThreadRunner.get().submit("Loading " + file.getName() + "\u2026",
        () -> {
          try { return new BigWigTrack(file.toPath()); }
          catch (IOException e) { System.err.println("Failed to load BigWig: " + e.getMessage()); return null; }
        },
        bigWigTrack -> {
          if (bigWigTrack == null) return;
          featureCanvas.addTrack(bigWigTrack);
          featureCanvas.setCollapsed(false);
          UserPreferences.addRecentFile("BIGWIG", file);
        });
  }
  
  /**
   * Open a VCF file chooser and load variants from one or more files.
   * VCF files must be bgzipped with tabix or csi index.
   * If multiple files are selected, they are loaded sequentially
   * (each file replaces the previous one in the viewer).
   */
  public static void addVcfFile() {
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Open VCF File(s)");
    File lastDir = UserPreferences.getLastDirectory("VCF");
    if (lastDir != null) {
      try {
        fileChooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException e) {
        System.err.println("Last directory not accessible: " + lastDir + ". Using default.");
      }
    }
    fileChooser.getExtensionFilters().addAll(
      new ExtensionFilter("VCF files", "*.vcf.gz"),
      new ExtensionFilter("All files", "*.*")
    );
    
    List<File> files = fileChooser.showOpenMultipleDialog(MainApp.stage);
    if (files == null || files.isEmpty()) return;
    UserPreferences.setLastDirectory("VCF", files.get(0).getParentFile());
    addVcfFiles(files);
  }
  
  /**
   * Load one or more VCF files directly without showing a chooser.
   * Files are loaded sequentially in a single ThreadRunner task to prevent modal flashing.
   * Tracks appear progressively as each file is loaded.
   * 
   * @param files List of VCF files to load
   */
  public static void addVcfFiles(List<File> files) {
    if (files == null || files.isEmpty()) return;
    

    
    // Suppress variant loading until all tracks are registered
    VcfManager.getInstance().setSuppressVariantLoading(true);

    // Single ThreadRunner task that wraps the entire batch load to prevent modal flashing
    final int totalFiles = files.size();
    ThreadRunner.get().submit(
        "Loading VCF files",
      () -> {
        try {
          return loadVcfFilesBatchWithProgress(files, totalFiles);
        } finally {
          VcfManager.getInstance().setSuppressVariantLoading(false);
        }
      },
        result -> {
            // Defer to the next FX pulse so the current RunnerTask can complete first.
            // This prevents the popup from sticking to the previous "Loading VCF files" task.
            Platform.runLater(() -> {
              VcfManager.getInstance().loadVariantsForCurrentView();
              VcfManager.getInstance().autoOpenVariantManager();
            });
        }
    );
  }
  
  /**
   * Load all files in the batch sequentially on the background thread.
   * Fires canvas updates after each file for progressive track appearance.
   * Called from within a single ThreadRunner.submit() task.
   * 
   * Separation of concerns:
   * - Background thread: Opens VCF, parses header, creates VariantLoader
   * - FX thread: Creates SampleTrack objects, updates registry, fires canvas update
   */
  private static Void loadVcfFilesBatchWithProgress(List<File> files, int totalFiles) {
    for (int index = 0; index < files.size(); index++) {
      if (Thread.currentThread().isInterrupted()) {
        return null;
      }

      File file = files.get(index);
      if (file == null || !file.exists()) {
        System.err.println("Skipping missing file: " + file);
        continue;
      }
      
      // Update progress bar
      final int currentIndex = index + 1;
      org.baseplayer.services.LoadingManager.get().setProgress(currentIndex, totalFiles);
      
      try {
        loadVcfFileSynchronously(file);
      } catch (Exception e) {
        if (Thread.currentThread().isInterrupted()) {
          return null;
        }
        System.err.println("Failed to load VCF: " + file + " - " + e.getMessage());
        e.printStackTrace();
      }

      // Redraw every 10 files to allow the spinner to animate between bursts
      if (currentIndex % 10 == 0) {
        Platform.runLater(() -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));
      }
    }
    
    return null;
  }
  
  /**
   * Load all files in the batch sequentially (deprecated - use loadVcfFilesBatchWithProgress).
   */
  @Deprecated
  private static Void loadVcfFilesBatch(List<File> files) {
    for (int index = 0; index < files.size(); index++) {
      File file = files.get(index);
      if (file == null || !file.exists()) {
        System.err.println("Skipping missing file: " + file);
        continue;
      }
      
      try {
        loadVcfFileSynchronously(file);
      } catch (Exception e) {
        System.err.println("Failed to load VCF: " + file + " - " + e.getMessage());
        e.printStackTrace();
      }

      // Redraw every 10 files to allow the spinner to animate between bursts
      if ((index + 1) % 10 == 0) {
        Platform.runLater(() -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));
      }
    }
    
    return null;
  }
  
  /**
   * Load a single VCF file synchronously (on the calling thread, which is a ThreadRunner background thread).
   * This is a blocking operation that:
   * 1. Parses the VCF header on background thread
   * 2. Pushes sample track registration to FX thread
   * 3. Closes reader to free VCFHeader memory
   * 4. Fires canvas update to progressively display tracks
   */
  private static void loadVcfFileSynchronously(File file) throws IOException {
    if (file == null || !file.exists()) {
      throw new IOException("VCF file not found: " + file);
    }
    
    Path vcfPath = file.toPath();
    VcfReader reader = new VcfReader(vcfPath);
    VariantLoader loader = new VariantLoader(reader);
    
    // Parse header and prepare sample mappings on background thread
    List<String> unmappedSamples = loader.getUnmappedSamples();
    
    // Register the VCF in VcfManager and get the VcfData reference
    VcfManager.VcfData vcfData = VcfManager.getInstance().registerLoadedVcf(reader, loader, file);
    
    // Now push UI updates to FX thread
    Platform.runLater(() -> {
      // Create SampleTrack objects and add to registry on FX thread
      if (!unmappedSamples.isEmpty()) {
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        for (String sampleName : unmappedSamples) {
          SampleTrack track = new SampleTrack(sampleName);
          registry.getSampleTracks().add(track);
          registry.getSampleList().add(sampleName);
        }
        if (registry.getFirstVisibleSample() < 0) {
          registry.setFirstVisibleSample(0);
        }
        registry.setLastVisibleSample(registry.getSampleList().size() - 1);
        registry.setSampleHeight(0);
        loader.updateMapping();
      }

      if (loader.getMappedSampleCount() == 0) {
        System.err.println("Warning: Could not create or map any VCF samples from: " + file);
      }
      
      // Fire canvas update to render the new track progressively
      // (batch-level updates are fired every 10 files in loadVcfFilesBatch)
    });
    
    // Close reader on background thread to free VCFHeader and tabix index immediately
    try { reader.close(); } catch (IOException ignored) {}
    vcfData.reader = null;  // Null out reader in VcfData
    loader.setVcfReader(null);  // Release loader's reference too
    
    UserPreferences.addRecentFile("VCF", file);
  }
  
  /**
   * Load a single VCF file directly without showing a chooser.
   */
  public static void addVcfFile(File file) {
    if (file == null) return;
    UserPreferences.setLastDirectory("VCF", file.getParentFile());
    
    VcfManager.getInstance().loadVcfFile(file);
  }

  /** Remove all samples, tracks, and VCF data — restores initial empty state. */
  public static void clearAllData() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();

    // Hard reset: stop all active background tasks immediately.
    ThreadRunner.get().cancelAll();
    VcfManager.getInstance().setSuppressVariantLoading(false);

    for (var track : new ArrayList<>(registry.getSampleTracks())) {
      try { track.close(); } catch (IOException e) {
        System.err.println("Error closing track: " + e.getMessage());
      }
    }
    registry.getSampleTracks().clear();
    registry.getSampleList().clear();
    
    // Reset all visibility and UI state to empty
    registry.clearActiveSampleFilterQuery();
    registry.setFirstVisibleSample(-1);
    registry.setLastVisibleSample(-1);
    registry.setScrollBarPosition(0);
    registry.setSampleHeight(0);
    registry.setMasterTrackHeight(SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT);
    registry.setHoverSample(-1);  // Clear any hover state

    VcfManager.getInstance().closeCurrentVcf();

    var stackManager = ServiceRegistry.getInstance().getDrawStackManager();
    for (var stack : stackManager.getStacks()) {
      if (stack.featureTracksCanvas != null) {
        for (var t : new ArrayList<>(stack.featureTracksCanvas.getTracks())) {
          if (t instanceof BedTrack || t instanceof BigWigTrack) {
            stack.featureTracksCanvas.removeTrack(t);
          }
        }
      }
    }

    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }
}

