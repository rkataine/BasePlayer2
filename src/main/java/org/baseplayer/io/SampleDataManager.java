package org.baseplayer.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.baseplayer.MainApp;
import org.baseplayer.components.SampleOpenFailuresDialog;
import org.baseplayer.components.sidebars.OpenDirectorySamplesDialog;
import org.baseplayer.components.sidebars.SelectSampleDirectoriesDialog;
import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BigWigTrack;
import org.baseplayer.samples.alignment.draw.TrackBodyCanvas;
import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.project.SessionDocumentSync;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleGroup;
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
   * Choose a parent folder (JavaFX), multi-select sample directories, then
   * settings modal for the first. If “apply settings to other directories” is
   * chosen, remaining dirs open with the same settings and no further modals.
   */
  public static void openDirectorySamples() {
    SelectSampleDirectoriesDialog.show(MainApp.stage)
        .ifPresent(SampleDataManager::openDirectorySamples);
  }

  /** Open the given directories with per-first / apply-to-rest settings modals. */
  public static void openDirectorySamples(List<File> selectedDirs) {
    if (selectedDirs == null || selectedDirs.isEmpty()) {
      return;
    }
    List<File> batch = new ArrayList<>();
    for (File dir : selectedDirs) {
      if (dir != null && dir.isDirectory() && !listSampleFiles(dir).isEmpty()) {
        batch.add(dir);
      } else if (dir != null) {
        System.err.println("No sample files found in: " + dir);
      }
    }
    if (batch.isEmpty()) {
      return;
    }

    for (int i = 0; i < batch.size(); i++) {
      File dir = batch.get(i);
      Set<SampleFileKind> available = detectTypesInDirectory(dir);
      if (available.isEmpty()) {
        System.err.println("No sample files found in: " + dir);
        continue;
      }

      int remainingIncludingThis = batch.size() - i;
      var settingsOpt = OpenDirectorySamplesDialog.show(
          MainApp.stage, dir.getName(), remainingIncludingThis, available);
      if (settingsOpt.isEmpty()) {
        if (i == 0) {
          return;
        }
        continue;
      }
      OpenDirectorySamplesDialog.Result settings = settingsOpt.get();

      if (settings.applyToAll()) {
        // One load job for this dir + the rest — avoids parallel loads interleaving tracks.
        List<File> toOpen = new ArrayList<>(batch.subList(i, batch.size()));
        openDirectoriesWithSettings(toOpen, settings);
        return;
      }

      openDirectoriesWithSettings(List.of(dir), settings);
    }
  }

  /** One directory with samples, or immediate subfolders that contain samples. */
  static List<File> resolveDirectoryBatch(File chosen) {
    if (chosen == null || !chosen.isDirectory()) {
      return List.of();
    }
    if (!listSampleFiles(chosen).isEmpty()) {
      return List.of(chosen);
    }
    File[] children = chosen.listFiles(File::isDirectory);
    if (children == null || children.length == 0) {
      return List.of();
    }
    List<File> batch = new ArrayList<>();
    for (File child : children) {
      if (child != null && !listSampleFiles(child).isEmpty()) {
        batch.add(child);
      }
    }
    batch.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
    return batch;
  }

  static Set<SampleFileKind> detectTypesInDirectory(File dir) {
    EnumSet<SampleFileKind> kinds = EnumSet.noneOf(SampleFileKind.class);
    for (File file : listSampleFiles(dir)) {
      SampleFileKind kind = SampleFileKind.fromFile(file);
      if (kind != null) {
        kinds.add(kind);
      }
    }
    return kinds;
  }

  public static List<File> listSampleFiles(File dir) {
    if (dir == null || !dir.isDirectory()) {
      return List.of();
    }
    File[] files = dir.listFiles(File::isFile);
    if (files == null || files.length == 0) {
      return List.of();
    }
    List<File> result = new ArrayList<>();
    for (File file : files) {
      if (SampleFileKind.fromFile(file) != null) {
        result.add(file);
      }
    }
    result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
    return result;
  }

  static List<File> listFilesOfKinds(File dir, Set<SampleFileKind> kinds) {
    if (kinds == null || kinds.isEmpty()) {
      return List.of();
    }
    List<File> result = new ArrayList<>();
    for (File file : listSampleFiles(dir)) {
      SampleFileKind kind = SampleFileKind.fromFile(file);
      if (kind != null && kinds.contains(kind)) {
        result.add(file);
      }
    }
    return result;
  }

  private static void openDirectoriesWithSettings(
      List<File> dirs, OpenDirectorySamplesDialog.Result settings) {
    if (dirs == null || dirs.isEmpty() || settings == null) {
      return;
    }

    Map<File, List<File>> bamByDir = new LinkedHashMap<>();
    Map<File, List<File>> bedByDir = new LinkedHashMap<>();
    List<File> allVcfs = new ArrayList<>();
    List<File> allBigWigs = new ArrayList<>();
    Map<File, String> groupNameByDir = new LinkedHashMap<>();

    for (File dir : dirs) {
      if (dir == null) {
        continue;
      }
      List<File> files = listFilesOfKinds(dir, settings.types());
      if (files.isEmpty()) {
        continue;
      }
      groupNameByDir.put(dir, dir.getName());
      for (File file : files) {
        SampleFileKind kind = SampleFileKind.fromFile(file);
        if (kind == null) {
          continue;
        }
        switch (kind) {
          case BAM -> bamByDir.computeIfAbsent(dir, key -> new ArrayList<>()).add(file);
          case BED -> bedByDir.computeIfAbsent(dir, key -> new ArrayList<>()).add(file);
          case VCF -> allVcfs.add(file);
          case BIGWIG -> allBigWigs.add(file);
        }
      }
    }

    boolean loadBamOrBed = !bamByDir.isEmpty() || !bedByDir.isEmpty();
    if (loadBamOrBed) {
      SampleOpenFailuresDialog failures = SampleOpenFailuresDialog.create();
      ThreadRunner.get().submit(
          "Loading directory samples",
          () -> loadBamAndBedSamples(bamByDir, bedByDir, settings.separateTracks(), failures),
          createdByDir -> {
            if (createdByDir != null && settings.addToGroup()) {
              assignDirectoryGroups(createdByDir, groupNameByDir);
            }
            SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
            if (registry.getDisplayedTrackCount() > 0) {
              registry.showAllTracksResetHeight();
            }
            ProjectSessionState.get().markDirty();
            GenomicCanvas.update.set(!GenomicCanvas.update.get());
            MainController.initializeLoadRegionButton();
            MainController.addLoadRegionButtonToViewport();

            if (!allVcfs.isEmpty()) {
              loadDirectoryVcfs(allVcfs, settings.addToGroup(), groupNameByDir, failures);
            } else {
              failures.commitAndShowLater();
            }
            for (File bigWig : allBigWigs) {
              addBigWigFile(bigWig);
            }
          });
    } else {
      if (!allVcfs.isEmpty()) {
        loadDirectoryVcfs(allVcfs, settings.addToGroup(), groupNameByDir, null);
      }
      for (File bigWig : allBigWigs) {
        addBigWigFile(bigWig);
      }
    }
  }

  private static Map<File, List<SampleTrack>> loadBamAndBedSamples(
      Map<File, List<File>> bamByDir,
      Map<File, List<File>> bedByDir,
      boolean separateTracks,
      SampleOpenFailuresDialog failures) {
    Map<File, List<SampleTrack>> created = new LinkedHashMap<>();
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();

    // BAM load on background; track creation must be on FX — collect Samples first.
    Map<File, List<Sample>> bamSamplesByDir = new LinkedHashMap<>();
    Map<File, List<Sample>> bedSamplesByDir = new LinkedHashMap<>();

    int total = 0;
    for (List<File> files : bamByDir.values()) {
      total += files.size();
    }
    for (List<File> files : bedByDir.values()) {
      total += files.size();
    }
    int index = 0;

    for (Map.Entry<File, List<File>> entry : bamByDir.entrySet()) {
      List<Sample> samples = new ArrayList<>();
      for (File file : entry.getValue()) {
        index++;
        org.baseplayer.services.LoadingManager.get().setProgress(index, Math.max(1, total));
        try {
          samples.add(new Sample(file.toPath()));
        } catch (IOException e) {
          System.err.println("Failed to open BAM: " + file + " - " + e.getMessage());
          if (failures != null) {
            failures.add(file, e.getMessage());
          }
        }
      }
      if (!samples.isEmpty()) {
        bamSamplesByDir.put(entry.getKey(), samples);
      }
    }

    for (Map.Entry<File, List<File>> entry : bedByDir.entrySet()) {
      List<Sample> samples = new ArrayList<>();
      for (File file : entry.getValue()) {
        index++;
        org.baseplayer.services.LoadingManager.get().setProgress(index, Math.max(1, total));
        try {
          BedTrack bedTrack = new BedTrack(file.toPath());
          samples.add(new Sample(file.toPath(), bedTrack));
        } catch (IOException e) {
          System.err.println("Failed to open BED: " + file + " - " + e.getMessage());
          if (failures != null) {
            failures.add(file, e.getMessage());
          }
        }
      }
      if (!samples.isEmpty()) {
        bedSamplesByDir.put(entry.getKey(), samples);
      }
    }

    Map<File, List<SampleTrack>> fxCreated = new LinkedHashMap<>();
    if (Platform.isFxApplicationThread()) {
      createTracksFromSamples(bamSamplesByDir, bedSamplesByDir, separateTracks, registry, fxCreated);
    } else {
      CountDownLatch latch = new CountDownLatch(1);
      Platform.runLater(() -> {
        try {
          createTracksFromSamples(
              bamSamplesByDir, bedSamplesByDir, separateTracks, registry, fxCreated);
        } finally {
          latch.countDown();
        }
      });
      try {
        latch.await();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
    created.putAll(fxCreated);
    return created;
  }

  private static void createTracksFromSamples(
      Map<File, List<Sample>> bamSamplesByDir,
      Map<File, List<Sample>> bedSamplesByDir,
      boolean separateTracks,
      SampleRegistry registry,
      Map<File, List<SampleTrack>> out) {
    for (Map.Entry<File, List<Sample>> entry : bamSamplesByDir.entrySet()) {
      List<SampleTrack> tracks = out.computeIfAbsent(entry.getKey(), key -> new ArrayList<>());
      if (separateTracks) {
        for (Sample sample : entry.getValue()) {
          SampleTrack track = new SampleTrack(sample);
          registry.getSampleTracks().add(track);
          registry.getSampleList().add(sample.getName());
          tracks.add(track);
        }
      } else if (!entry.getValue().isEmpty()) {
        Sample first = entry.getValue().get(0);
        SampleTrack track = new SampleTrack(first);
        for (int i = 1; i < entry.getValue().size(); i++) {
          track.addSample(entry.getValue().get(i));
        }
        registry.getSampleTracks().add(track);
        registry.getSampleList().add(track.getName());
        tracks.add(track);
      }
    }
    for (Map.Entry<File, List<Sample>> entry : bedSamplesByDir.entrySet()) {
      List<SampleTrack> tracks = out.computeIfAbsent(entry.getKey(), key -> new ArrayList<>());
      if (separateTracks) {
        for (Sample sample : entry.getValue()) {
          SampleTrack track = new SampleTrack(sample);
          registry.getSampleTracks().add(track);
          registry.getSampleList().add(sample.getName());
          tracks.add(track);
        }
      } else if (!entry.getValue().isEmpty()) {
        Sample first = entry.getValue().get(0);
        SampleTrack track = new SampleTrack(first);
        for (int i = 1; i < entry.getValue().size(); i++) {
          track.addSample(entry.getValue().get(i));
        }
        registry.getSampleTracks().add(track);
        registry.getSampleList().add(track.getName());
        tracks.add(track);
      }
    }
  }

  private static void assignDirectoryGroups(
      Map<File, List<SampleTrack>> createdByDir, Map<File, String> groupNameByDir) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    for (Map.Entry<File, List<SampleTrack>> entry : createdByDir.entrySet()) {
      String groupName = groupNameByDir.get(entry.getKey());
      if (groupName == null || groupName.isBlank() || entry.getValue().isEmpty()) {
        continue;
      }
      SampleGroup group = ensureDirectoryGroup(registry, groupName);
      registry.setTracksExclusiveGroup(entry.getValue(), group.getId());
    }
  }

  private static SampleGroup ensureDirectoryGroup(SampleRegistry registry, String groupName) {
    SampleGroup group = registry.findSampleGroupByName(groupName);
    if (group == null) {
      group = registry.createSampleGroup(groupName, null);
    }
    return group;
  }

  private static void loadDirectoryVcfs(
      List<File> vcfFiles,
      boolean addToGroup,
      Map<File, String> groupNameByDir,
      SampleOpenFailuresDialog priorFailures) {
    if (vcfFiles == null || vcfFiles.isEmpty()) {
      if (priorFailures != null) {
        priorFailures.commitAndShowLater();
      }
      return;
    }
    final int totalFiles = vcfFiles.size();
    SampleOpenFailuresDialog failures =
        priorFailures != null ? priorFailures : SampleOpenFailuresDialog.create();
    ThreadRunner.get().submit(
        "Loading VCF files",
        () -> {
          try {
            return loadVcfFilesBatchWithProgress(vcfFiles, totalFiles, failures);
          } finally {
            // no-op
          }
        },
        result -> {
          if (addToGroup) {
            assignTracksUnderDirectories(groupNameByDir);
          }
          // Commit errors first, then start variant load; popup is deferred/non-modal
          // so it cannot block FX the way showAndWait did.
          failures.commitToSessionLog();
          Platform.runLater(() -> {
            VcfManager.getInstance().loadVariantsForCurrentView();
            org.baseplayer.variant.ui.VariantManagerWindow.openVariantManager(
                MainApp.stage, VcfManager.getInstance(), null);
            MainController.initializeLoadRegionButton();
            MainController.addLoadRegionButtonToViewport();
            SampleOpenFailuresDialog.showPendingNonModal();
          });
        });
  }

  /** Assign any track that has a sample file under {@code dir} to that directory's group. */
  private static void assignTracksUnderDirectories(Map<File, String> groupNameByDir) {
    if (groupNameByDir == null || groupNameByDir.isEmpty()) {
      return;
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    Map<File, Path> dirPaths = new LinkedHashMap<>();
    for (File dir : groupNameByDir.keySet()) {
      dirPaths.put(dir, dir.toPath().toAbsolutePath().normalize());
    }
    Map<String, List<SampleTrack>> byGroupName = new LinkedHashMap<>();
    for (SampleTrack track : registry.getSampleTracks()) {
      if (track == null) {
        continue;
      }
      for (Map.Entry<File, Path> entry : dirPaths.entrySet()) {
        if (trackHasSampleInDirectory(track, entry.getValue())) {
          String name = groupNameByDir.get(entry.getKey());
          byGroupName.computeIfAbsent(name, key -> new ArrayList<>()).add(track);
          break;
        }
      }
    }
    for (Map.Entry<String, List<SampleTrack>> entry : byGroupName.entrySet()) {
      SampleGroup group = ensureDirectoryGroup(registry, entry.getKey());
      registry.setTracksExclusiveGroup(entry.getValue(), group.getId());
    }
  }

  private static boolean trackHasSampleInDirectory(SampleTrack track, Path dirPath) {
    for (Sample sample : track.getSamples()) {
      if (sample == null || sample.getPath() == null) {
        continue;
      }
      Path parent = sample.getPath().toAbsolutePath().normalize().getParent();
      if (dirPath.equals(parent)) {
        return true;
      }
    }
    return false;
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
    SampleOpenFailuresDialog failures = SampleOpenFailuresDialog.create();

    runner.submit("Loading BAM/CRAM files",
        () -> {
            final java.util.concurrent.atomic.AtomicInteger fileIndex = new java.util.concurrent.atomic.AtomicInteger(0);
            List<Sample> loaded = new ArrayList<>();
            for (File file : files) {
              if (file == null) continue;
              int currentIndex = fileIndex.incrementAndGet();
              
              // Update progress bar
              org.baseplayer.services.LoadingManager.get().setProgress(currentIndex, totalFiles);
              
              try {
                loaded.add(new Sample(file.toPath()));
              } catch (IOException e) {
                System.err.println("Failed to open BAM: " + file + " - " + e.getMessage());
                failures.add(file, e.getMessage());
              }
            }
            return loaded;
        },
        loaded -> {
            if (loaded != null) {
              for (Sample sample : loaded) {
                SampleTrack track = new SampleTrack(sample);
                sampleRegistry.getSampleTracks().add(track);
                sampleRegistry.getSampleList().add(sample.getName());
              }
            }
            // All BAM files loaded; update visible range and redraw
            int trackCount = sampleRegistry.getDisplayedTrackCount();
            if (trackCount > 0) {
              sampleRegistry.showAllTracksResetHeight();
            }
            ProjectSessionState.get().markDirty();
            GenomicCanvas.update.set(!GenomicCanvas.update.get());
            
            org.baseplayer.controllers.MainController.initializeLoadRegionButton();
            org.baseplayer.controllers.MainController.addLoadRegionButtonToViewport();
            failures.commitAndShowLater();
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
    SampleTrack removedTrack = sampleRegistry.getSampleTracks().get(index);

    int oldFirst = sampleRegistry.getFirstVisibleSample();
    int oldLast = sampleRegistry.getLastVisibleSample();
    int oldWindow = Math.max(1, oldLast - oldFirst + 1);
    int removedSlot = sampleRegistry.getDisplayedSlotForTrackIndex(index);

    try {
      removedTrack.close();
    } catch (IOException e) {
      System.err.println("Error closing sample: " + e.getMessage());
    }
    sampleRegistry.getSampleTracks().remove(index);
    if (index < sampleRegistry.getSampleList().size()) {
      sampleRegistry.getSampleList().remove(index);
    }

    // Drop calls for this track; remaining calls resolve live indices via SampleTrack.
    java.util.IdentityHashMap<org.baseplayer.variant.VariantList, Boolean> seen =
        new java.util.IdentityHashMap<>();
    java.util.function.Consumer<org.baseplayer.variant.VariantList> purge = variantList -> {
      if (variantList == null || seen.put(variantList, Boolean.TRUE) != null) {
        return;
      }
      variantList.removeTrack(removedTrack);
    };
    DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
    for (DrawStack stack : stackManager.getStacks()) {
      if (stack.sampleTrackCanvas != null) {
        purge.accept(stack.sampleTrackCanvas.getVariantList());
      }
      if (stack.sampleAggregateCanvas != null) {
        purge.accept(stack.sampleAggregateCanvas.getVariantList());
      }
    }
    for (org.baseplayer.variant.VariantList cached : VcfManager.getInstance().snapshotVariantCache().values()) {
      purge.accept(cached);
    }

    // Drop VCFs with no remaining open sample; types follow open VcfData only.
    VcfManager.getInstance().unloadVcfsWithNoOpenSamples();
    for (DrawStack stack : stackManager.getStacks()) {
      if (stack.sampleTrackCanvas != null) {
        stack.sampleTrackCanvas.invalidateVariantIndex();
      }
      if (stack.sampleAggregateCanvas != null) {
        stack.sampleAggregateCanvas.refreshPresentTypesFromList();
        stack.sampleAggregateCanvas.forceCalculateDensity();
      }
    }
    VcfManager.getInstance().bumpVariantsRevision();
    org.baseplayer.variant.ui.VariantManagerController.notifySampleDataChanged();
    
    // Adjust visible range
    int newCount = sampleRegistry.getDisplayedTrackCount();
    if (sampleRegistry.getSampleTracks().isEmpty() || newCount <= 0) {
      sampleRegistry.clearVisibleRange();
    } else {
      sampleRegistry.adjustWindowAfterTrackRemoval(removedSlot, oldFirst, oldWindow);
    }
    
    ProjectSessionState.get().markDirty();
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
            SampleOpenFailuresDialog.create().add(file, e.getMessage()).commitAndShowLater();
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
            SampleOpenFailuresDialog.create().add(file, e.getMessage()).commitAndShowLater();
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
            SampleOpenFailuresDialog.create().add(file, e.getMessage()).commitAndShowLater();
            return null;
          }
        },
        newSample -> {
          if (newSample == null) return;
          SampleTrack track = new SampleTrack(newSample);
          sampleRegistry.getSampleTracks().add(track);
          sampleRegistry.getSampleList().add(newSample.getName());
          sampleRegistry.includeNewTracksAtEndResetHeight();
          UserPreferences.addRecentFile("BED", file);
          ProjectSessionState.get().markDirty();
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
    TrackBodyCanvas featureCanvas = MainController.getFeatureTrackCanvas();
    if (featureCanvas == null) return;

    ThreadRunner.get().submit("Loading " + file.getName() + "\u2026",
        () -> {
          try { return new BedTrack(file.toPath()); }
          catch (IOException e) { System.err.println("Failed to load BED: " + e.getMessage()); return null; }
        },
        bedTrack -> {
          if (bedTrack == null) return;
          bedTrack.setVisible(true);
          featureCanvas.addTrack(bedTrack);
          UserPreferences.addRecentFile("BED", file);
          ProjectSessionState.get().markDirty();
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
    TrackBodyCanvas featureCanvas = MainController.getFeatureTrackCanvas();
    if (featureCanvas == null) return;

    ThreadRunner.get().submit("Loading " + file.getName() + "\u2026",
        () -> {
          try { return new BigWigTrack(file.toPath()); }
          catch (IOException e) { System.err.println("Failed to load BigWig: " + e.getMessage()); return null; }
        },
        bigWigTrack -> {
          if (bigWigTrack == null) return;
          bigWigTrack.setVisible(true);
          featureCanvas.addTrack(bigWigTrack);
          UserPreferences.addRecentFile("BIGWIG", file);
          ProjectSessionState.get().markDirty();
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
    

    
    final int totalFiles = files.size();
    SampleOpenFailuresDialog failures = SampleOpenFailuresDialog.create();
    ThreadRunner.get().submit(
        "Loading VCF files",
      () -> {
        try {
          return loadVcfFilesBatchWithProgress(files, totalFiles, failures);
        } finally {
          //VcfManager.getInstance().setSuppressVariantLoading(false);
        }
      },
        result -> {
            failures.commitToSessionLog();
            // Start Phase 2 on the next FX pulse so the Phase 1 task can
            // complete and be removed first. This avoids task overlap that can
            // cause popup message/progress churn. Error popup is non-modal and
            // deferred so it does not block variant loading.
            Platform.runLater(() -> {
              VcfManager.getInstance().loadVariantsForCurrentView();
              org.baseplayer.variant.ui.VariantManagerWindow.openVariantManager(
                  MainApp.stage, VcfManager.getInstance(), null);
              
              // Initialize LoadRegionButton listener and add to viewport
              org.baseplayer.controllers.MainController.initializeLoadRegionButton();
              org.baseplayer.controllers.MainController.addLoadRegionButtonToViewport();
              SampleOpenFailuresDialog.showPendingNonModal();
            });
        }
    );
  }
  
  private static Void loadVcfFilesBatchWithProgress(
      List<File> files, int totalFiles, SampleOpenFailuresDialog failures) {
    for (int index = 0; index < files.size(); index++) {
      if (Thread.currentThread().isInterrupted()) {
        return null;
      }

      File file = files.get(index);
      if (file == null || !file.exists()) {
        System.err.println("Skipping missing file: " + file);
        if (failures != null) {
          failures.add(file, "file not found");
        }
        continue;
      }

      if (VcfManager.getInstance().isVcfFileLoaded(file)) {
        continue;
      }
      
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
        if (failures != null) {
          failures.add(file, e.getMessage());
        }
      }

      // Redraw every 10 files to allow the spinner to animate between bursts
      if (currentIndex % 10 == 0) {
        Platform.runLater(() -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));
      }
    }
    
    return null;
  }

  /**
   * Open and register many VCFs on the calling thread (no nested ThreadRunner tasks).
   * Used by project restore after sample tracks already exist — maps onto those tracks
   * immediately so the opener does not wait through per-file "Opening VCF…" / sample UI.
   */
  public static void loadVcfFilesForSessionRestore(List<File> files, List<String> warnings) {
    if (files == null || files.isEmpty()) {
      return;
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    boolean addedTracks = false;

    for (File file : files) {
      if (Thread.currentThread().isInterrupted()) {
        return;
      }
      if (file == null || !file.exists()) {
        if (warnings != null) {
          warnings.add("Missing VCF: " + file);
        }
        continue;
      }
      if (VcfManager.getInstance().isVcfFileLoaded(file)) {
        continue;
      }

      try {
        Path vcfPath = file.toPath();
        VcfReader reader = new VcfReader(vcfPath);
        VariantLoader loader = new VariantLoader(reader);

        List<String> unmappedSamples = loader.getUnmappedSamples();
        VcfManager.VcfData vcfData = VcfManager.getInstance().registerLoadedVcf(reader, loader, file);
        if (vcfData == null) {
          try { reader.close(); } catch (IOException ignored) {}
          loader.setVcfReader(null);
          continue;
        }

        // Tracks were restored first; only create rows for truly new sample IDs.
        if (!unmappedSamples.isEmpty()) {
          for (String sampleName : unmappedSamples) {
            SampleTrack track = new SampleTrack(sampleName);
            registry.getSampleTracks().add(track);
            registry.getSampleList().add(sampleName);
          }
          addedTracks = true;
          loader.updateMapping();
        } else {
          loader.updateMapping();
        }

        attachVcfSamplesToMappedTracks(loader, vcfPath);

        try { reader.close(); } catch (IOException ignored) {}
        vcfData.reader = null;
        loader.setVcfReader(null);

        if (loader.getMappedSampleCount() == 0 && warnings != null) {
          warnings.add("Could not map any samples from VCF: " + file.getName());
        }
      } catch (Exception e) {
        if (warnings != null) {
          warnings.add("Failed to load VCF " + file.getName() + ": " + e.getMessage());
        }
        System.err.println("Failed to load VCF during session restore: " + file + " - " + e.getMessage());
      }
    }

    if (addedTracks) {
      registry.includeNewTracksAtEndResetHeight();
    }
  }

  private static void loadVcfFileSynchronously(File file) throws IOException {
    if (file == null || !file.exists()) {
      throw new IOException("VCF file not found: " + file);
    }

    if (VcfManager.getInstance().isVcfFileLoaded(file)) {
      return;
    }
    
    Path vcfPath = file.toPath();
    VcfReader reader = new VcfReader(vcfPath);
    if (!reader.hasTbiOrCsiIndex()) {
      try { reader.close(); } catch (IOException ignored) {}
      throw new IOException("Tabix/CSI index (.tbi/.csi) not found for: " + vcfPath);
    }
    VariantLoader loader = new VariantLoader(reader);
    
    List<String> unmappedSamples = loader.getUnmappedSamples();
    
    VcfManager.VcfData vcfData = VcfManager.getInstance().registerLoadedVcf(reader, loader, file);
    if (vcfData == null) {
      try { reader.close(); } catch (IOException ignored) {}
      loader.setVcfReader(null);
      return;
    }
    
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
        registry.includeNewTracksAtEndResetHeight();
        loader.updateMapping();
      }

      attachVcfSamplesToMappedTracks(loader, vcfPath);

      if (loader.getMappedSampleCount() == 0) {
        System.err.println("Warning: Could not create or map any VCF samples from: " + file);
      }

      ProjectSessionState.get().markDirty();
      
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
   * Attach a VCF file entry under every sample track this loader maps to,
   * so the sidebar can list {@code VCF: filename} when there is room.
   * Track display names stay as VCF header sample IDs (or existing BAM names).
   */
  public static void attachVcfSamplesToMappedTracks(VariantLoader loader, Path vcfPath) {
    if (loader == null || vcfPath == null) {
      return;
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    for (Integer trackIndex : new java.util.LinkedHashSet<>(loader.getTrackIndices())) {
      if (trackIndex == null || trackIndex < 0
          || trackIndex >= registry.getSampleTracks().size()) {
        continue;
      }
      addVcfSampleIfMissing(registry.getSampleTracks().get(trackIndex), vcfPath);
    }
  }

  private static void addVcfSampleIfMissing(SampleTrack track, Path vcfPath) {
    if (track == null || vcfPath == null) {
      return;
    }
    String pathKey = vcfPath.toAbsolutePath().normalize().toString();
    for (Sample sample : track.getSamples()) {
      if (sample.getDataType() == Sample.DataType.VCF && sample.getPath() != null
          && pathKey.equals(sample.getPath().toAbsolutePath().normalize().toString())) {
        return;
      }
    }
    track.addSample(new Sample(vcfPath, Sample.DataType.VCF));
  }

  /**
   * Toggle VCF file visibility without touching the variant cache.
   * Hidden calls stay in {@link org.baseplayer.variant.VariantList} but are skipped
   * by the filter / drawer / density / table via {@link VariantNode.SampleCall#isUiVisible()}.
   */
  public static void applyVcfSampleVisibility(SampleTrack track, Sample vcfSample, boolean visible) {
    if (track == null || vcfSample == null || vcfSample.getDataType() != Sample.DataType.VCF) {
      return;
    }
    vcfSample.visible = visible;
    refreshVariantPresentation();
  }

  /** Rebuild visible chains / density after sample visibility or overlay changes. */
  public static void refreshVariantPresentation() {
    java.util.IdentityHashMap<org.baseplayer.variant.VariantList, Boolean> seen =
        new java.util.IdentityHashMap<>();
    java.util.function.Consumer<org.baseplayer.variant.VariantList> invalidate = variantList -> {
      if (variantList == null || seen.put(variantList, Boolean.TRUE) != null) {
        return;
      }
      variantList.clearVisibleChain();
    };
    DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
    for (DrawStack stack : stackManager.getStacks()) {
      if (stack.sampleTrackCanvas != null) {
        invalidate.accept(stack.sampleTrackCanvas.getVariantList());
        if (stack.sampleTrackCanvas.getVariantDrawer() != null) {
          stack.sampleTrackCanvas.getVariantDrawer().markIndexDirty();
        }
      }
      if (stack.sampleAggregateCanvas != null) {
        stack.sampleAggregateCanvas.refreshPresentTypesFromList();
        stack.sampleAggregateCanvas.forceCalculateDensity();
      }
    }
    for (org.baseplayer.variant.VariantList cached : VcfManager.getInstance().snapshotVariantCache().values()) {
      invalidate.accept(cached);
    }
    VcfManager.getInstance().bumpVariantsRevision();
    org.baseplayer.variant.ui.VariantManagerController.notifySampleDataChanged();
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }

  /**
   * Load a single VCF file directly without showing a chooser.
   */
  public static void addVcfFile(File file) {
    if (file == null) return;
    UserPreferences.setLastDirectory("VCF", file.getParentFile());
    
    VcfManager.getInstance().loadVcfFile(file);
  }

  public static void clearAllData() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();

    ThreadRunner.get().cancelAll();

    for (var track : new ArrayList<>(registry.getSampleTracks())) {
      try { track.close(); } catch (IOException e) {
        System.err.println("Error closing track: " + e.getMessage());
      }
    }
    registry.getSampleTracks().clear();
    registry.getSampleList().clear();
    registry.clearSampleGroups();

    registry.clearAllSubsetSources();
    registry.clearVisibleRange();
    registry.setMasterTrackHeight(SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT);
    registry.setHoverSample(-1);

    VcfManager.getInstance().closeCurrentVcf();
    org.baseplayer.io.Settings.get().resetDefaults();
    ProjectSessionState.get().resetDocument();

    var stackManager = ServiceRegistry.getInstance().getDrawStackManager();
    for (var stack : stackManager.getStacks()) {
      if (stack.featureTrackCanvas != null) {
        for (var t : new ArrayList<>(stack.featureTrackCanvas.getTracks())) {
          if (t instanceof BedTrack || t instanceof BigWigTrack) {
            stack.featureTrackCanvas.removeTrack(t);
          }
        }
      }
    }

    // Dispose Variant Manager so Open Project / next VCF gets a fresh FXML UI
    // (partial reset left SV tabs/filters from the previous project).
    org.baseplayer.variant.ui.VariantManagerWindow.closeAndDispose();

    ProjectSessionState.get().markDirty();
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }

  /** Refresh live document sample-track paths after add/remove (SSOT). */
  public static void syncSampleTracksToDocument() {
    SessionDocumentSync.writeSampleTracksFromRuntime(ProjectSessionState.get().getFile());
    SessionDocumentSync.writeSampleGroupsFromRegistry();
  }
}

