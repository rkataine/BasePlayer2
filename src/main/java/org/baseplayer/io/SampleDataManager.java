package org.baseplayer.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.baseplayer.MainApp;
import org.baseplayer.components.SampleOpenFailuresDialog;
import org.baseplayer.components.sidebars.OpenDirectorySamplesDialog;
import org.baseplayer.components.sidebars.OpenDragenDialog;
import org.baseplayer.components.sidebars.SelectSampleDirectoriesDialog;
import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BigWigTrack;
import org.baseplayer.samples.alignment.draw.TrackBodyCanvas;
import org.baseplayer.io.dragen.DragenAnalysisKind;
import org.baseplayer.io.dragen.DragenFolderScan;
import org.baseplayer.io.dragen.DragenFolderScanner;
import org.baseplayer.io.dragen.DragenLoadPlan;
import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.project.SessionDocumentSync;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.samples.alignment.AlignmentFile;
import org.baseplayer.sanger.SangerTrace;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.FeatureTrackViewportRegistry;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.SomaticSampleResolver;
import org.baseplayer.variant.VariantLoader;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;

/**
 * Manages adding sample data files (BAM, CRAM, VCF, BED, BigWig, AB1, etc.)
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

  /**
   * Choose a DRAGEN output folder, pick analyses in a dedicated dialog, then
   * load matching BAM/VCF and companion BigWigs onto one sample track.
   */
  public static void openDragenFolder() {
    DirectoryChooser chooser = new DirectoryChooser();
    chooser.setTitle("Open DRAGEN folder");
    File lastDir = UserPreferences.getLastDirectory("DRAGEN");
    if (lastDir != null) {
      try {
        chooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException e) {
        System.err.println("Last directory not accessible: " + lastDir + ". Using default.");
      }
    }
    File dir = chooser.showDialog(MainApp.stage);
    if (dir == null) {
      return;
    }
    UserPreferences.setLastDirectory("DRAGEN", dir);
    openDragenFolder(dir);
  }

  /** Open the given DRAGEN output directory with the analysis picker. */
  public static void openDragenFolder(File directory) {
    if (directory == null || !directory.isDirectory()) {
      return;
    }
    DragenFolderScan scan = DragenFolderScanner.scan(directory);
    if (!scan.looksLikeDragen() || scan.available().isEmpty()) {
      Alert alert = new Alert(Alert.AlertType.INFORMATION);
      alert.setTitle("Open DRAGEN");
      alert.setHeaderText("No DRAGEN outputs found");
      alert.setContentText(
          "No recognized DRAGEN files in:\n" + directory.getAbsolutePath()
              + "\n\nExpected files such as *.hard-filtered.vcf.gz, *.cnv.vcf.gz, or *-replay.json.");
      alert.initOwner(MainApp.stage);
      alert.showAndWait();
      return;
    }
    OpenDragenDialog.show(MainApp.stage, scan)
        .ifPresent(result -> loadDragenFolder(scan, result));
  }

  private static void loadDragenFolder(DragenFolderScan scan, OpenDragenDialog.Result selection) {
    Set<DragenAnalysisKind> selected = selection.kinds();
    DragenLoadPlan plan = DragenFolderScanner.resolveLoadPlan(
        scan, selected, selection.optionalBigWigs());
    if (plan.isEmpty()) {
      return;
    }
    String stem = plan.stem().isBlank()
        ? (scan.directory() != null ? scan.directory().getName() : "DRAGEN")
        : plan.stem();

    SampleOpenFailuresDialog failures = SampleOpenFailuresDialog.create();
    List<File> bamFiles = plan.bamFiles();
    List<File> vcfFiles = plan.vcfFiles();
    List<File> bigWigFiles = plan.bigWigFiles();

    ThreadRunner.get().submit(
        "Loading DRAGEN samples",
        () -> {
          record DragenLoaded(List<Sample> bams, List<Sample> bigWigs) {}
          List<Sample> loadedBams = new ArrayList<>();
          List<Sample> loadedBigWigs = new ArrayList<>();
          int total = Math.max(1, bamFiles.size() + bigWigFiles.size());
          int index = 0;
          for (File bam : bamFiles) {
            if (bam == null) {
              continue;
            }
            index++;
            org.baseplayer.services.LoadingManager.get().setProgress(index, total);
            try {
              loadedBams.add(new Sample(bam.toPath()));
            } catch (IOException e) {
              System.err.println("Failed to open BAM: " + bam + " - " + e.getMessage());
              failures.add(bam, e.getMessage());
            }
          }
          for (File bigWig : bigWigFiles) {
            if (bigWig == null) {
              continue;
            }
            index++;
            org.baseplayer.services.LoadingManager.get().setProgress(index, total);
            try {
              BigWigTrack bw = new BigWigTrack(bigWig.toPath());
              loadedBigWigs.add(new Sample(bigWig.toPath(), bw));
            } catch (IOException e) {
              System.err.println("Failed to open BigWig: " + bigWig + " - " + e.getMessage());
              failures.add(bigWig, e.getMessage());
            }
          }
          return new DragenLoaded(loadedBams, loadedBigWigs);
        },
        loaded -> {
          SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
          SampleTrack track = new SampleTrack(stem);
          track.setSignalLayout(SampleTrack.SignalLayout.OVERLAY);
          registry.getSampleTracks().add(track);
          registry.getSampleList().add(stem);
          if (loaded != null) {
            if (loaded.bams() != null) {
              for (Sample sample : loaded.bams()) {
                if (sample != null) {
                  track.addSample(sample);
                  UserPreferences.addRecentFile("BAM", sample.getPath().toFile());
                }
              }
            }
            if (loaded.bigWigs() != null) {
              for (Sample sample : loaded.bigWigs()) {
                if (sample == null) {
                  continue;
                }
                BigWigTrack bw = sample.getBigWigTrack();
                if (bw != null) {
                  bw.setVisible(true);
                  bw.setOnDataLoaded(() -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));
                }
                track.addSample(sample);
                UserPreferences.addRecentFile("BIGWIG", sample.getPath().toFile());
              }
            }
          }
          registry.includeNewTracksAtEndResetHeight();
          ProjectSessionState.get().markDirty();
          GenomicCanvas.update.set(!GenomicCanvas.update.get());
          MainController.initializeLoadRegionButton();
          MainController.addLoadRegionButtonToViewport();
          // Kick sample-body BigWig region load once the track is registered.
          for (org.baseplayer.draw.DrawStack stack :
              ServiceRegistry.getInstance().getDrawStackManager().getStacks()) {
            if (stack.sampleTrackCanvas != null) {
              stack.sampleTrackCanvas.forceNotifyRegionChanged();
            }
          }

          if (!vcfFiles.isEmpty()) {
            loadDirectoryVcfs(vcfFiles, false, Map.of(), failures);
          } else {
            failures.commitAndShowLater();
          }
        });
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
    List<File> allAb1s = new ArrayList<>();
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
          case AB1 -> allAb1s.add(file);
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
            if (!allAb1s.isEmpty()) {
              addAb1Files(allAb1s);
            }
          });
    } else {
      if (!allVcfs.isEmpty()) {
        loadDirectoryVcfs(allVcfs, settings.addToGroup(), groupNameByDir, null);
      }
      for (File bigWig : allBigWigs) {
        addBigWigFile(bigWig);
      }
      if (!allAb1s.isEmpty()) {
        addAb1Files(allAb1s);
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
          SampleTrack track = addOrMergeSampleReturningTrack(registry, sample);
          if (track != null && !tracks.contains(track)) {
            tracks.add(track);
          }
        }
      } else if (!entry.getValue().isEmpty()) {
        Sample first = entry.getValue().get(0);
        SampleTrack track = addOrMergeSampleReturningTrack(registry, first);
        for (int i = 1; i < entry.getValue().size(); i++) {
          Sample next = entry.getValue().get(i);
          SampleTrack existing = registry.findTrackMatchingName(next.getName());
          if (existing != null) {
            existing.addSample(next);
            preferShorterTrackName(existing, next.getName());
            if (!tracks.contains(existing)) {
              tracks.add(existing);
            }
          } else if (track != null) {
            track.addSample(next);
            preferShorterTrackName(track, next.getName());
          }
        }
        if (track != null && !tracks.contains(track)) {
          tracks.add(track);
        }
      }
    }
    for (Map.Entry<File, List<Sample>> entry : bedSamplesByDir.entrySet()) {
      List<SampleTrack> tracks = out.computeIfAbsent(entry.getKey(), key -> new ArrayList<>());
      if (separateTracks) {
        for (Sample sample : entry.getValue()) {
          SampleTrack track = addOrMergeSampleReturningTrack(registry, sample);
          if (track != null && !tracks.contains(track)) {
            tracks.add(track);
          }
        }
      } else if (!entry.getValue().isEmpty()) {
        Sample first = entry.getValue().get(0);
        SampleTrack track = addOrMergeSampleReturningTrack(registry, first);
        for (int i = 1; i < entry.getValue().size(); i++) {
          Sample next = entry.getValue().get(i);
          SampleTrack existing = registry.findTrackMatchingName(next.getName());
          if (existing != null) {
            existing.addSample(next);
            preferShorterTrackName(existing, next.getName());
            if (!tracks.contains(existing)) {
              tracks.add(existing);
            }
          } else if (track != null) {
            track.addSample(next);
            preferShorterTrackName(track, next.getName());
          }
        }
        if (track != null && !tracks.contains(track)) {
          tracks.add(track);
        }
      }
    }
  }

  /**
   * Attach {@code sample} to an existing track when names match by equality or
   * mutual substring; otherwise create a new track. When merging onto a longer
   * prefixed name, the shorter sample name becomes the track display name.
   *
   * @return true if a <em>new</em> track was created
   */
  private static boolean addSampleMergingByName(SampleRegistry registry, Sample sample) {
    if (registry == null || sample == null) {
      return false;
    }
    SampleTrack existing = registry.findTrackMatchingName(sample.getName());
    if (existing != null) {
      existing.addSample(sample);
      preferShorterTrackName(existing, sample.getName());
      return false;
    }
    SampleTrack track = new SampleTrack(sample);
    registry.getSampleTracks().add(track);
    registry.getSampleList().add(sample.getName());
    return true;
  }

  /** Like {@link #addSampleMergingByName} but returns the track the sample landed on. */
  private static SampleTrack addOrMergeSampleReturningTrack(SampleRegistry registry, Sample sample) {
    if (registry == null || sample == null) {
      return null;
    }
    SampleTrack existing = registry.findTrackMatchingName(sample.getName());
    if (existing != null) {
      existing.addSample(sample);
      preferShorterTrackName(existing, sample.getName());
      return existing;
    }
    SampleTrack track = new SampleTrack(sample);
    registry.getSampleTracks().add(track);
    registry.getSampleList().add(sample.getName());
    return track;
  }

  /**
   * If {@code candidateName} is a shorter mutual-substring match of the track's
   * current display name, rename the track to that shorter name (keeps sidebar
   * labels clean when a prefixed file was opened first).
   */
  static void preferShorterTrackName(SampleTrack track, String candidateName) {
    if (track == null || candidateName == null) {
      return;
    }
    String candidate = SampleRegistry.normalizeNameKey(candidateName);
    String current = SampleRegistry.normalizeNameKey(track.getDisplayName());
    if (candidate.isEmpty() || current.isEmpty()) {
      return;
    }
    if (candidate.length() >= current.length()) {
      return;
    }
    String candidateLower = candidate.toLowerCase(java.util.Locale.ROOT);
    String currentLower = current.toLowerCase(java.util.Locale.ROOT);
    if (!currentLower.contains(candidateLower) && !candidateLower.contains(currentLower)) {
      return;
    }
    // Prefer the shorter token as the canonical individual name.
    track.setName(candidate);
    track.setCustomName(null);
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    int trackIndex = registry.getTrackIndex(track);
    if (trackIndex >= 0 && trackIndex < registry.getSampleList().size()) {
      registry.getSampleList().set(trackIndex, candidate);
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
    loadDirectoryVcfs(vcfFiles, addToGroup, groupNameByDir, priorFailures, true);
  }

  /**
   * @param openVariantManager when false, VCFs are still registered and variants
   *     loaded for the current view, but the Variant Manager window is not shown
   */
  private static void loadDirectoryVcfs(
      List<File> vcfFiles,
      boolean addToGroup,
      Map<File, String> groupNameByDir,
      SampleOpenFailuresDialog priorFailures,
      boolean openVariantManager) {
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
          final boolean addedTracks = Boolean.TRUE.equals(result);
          Platform.runLater(() -> {
            finishVcfBatchUi(addedTracks);
            VcfManager.getInstance().loadVariantsForCurrentView();
            if (openVariantManager) {
              org.baseplayer.variant.ui.VariantManagerWindow.openVariantManager(
                  MainApp.stage, VcfManager.getInstance(), null);
            }
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
            boolean addedTracks = false;
            if (loaded != null) {
              for (Sample sample : loaded) {
                if (addSampleMergingByName(sampleRegistry, sample)) {
                  addedTracks = true;
                }
              }
            }
            // All BAM files loaded; update visible range and redraw
            int trackCount = sampleRegistry.getDisplayedTrackCount();
            if (trackCount > 0) {
              if (addedTracks) {
                sampleRegistry.showAllTracksResetHeight();
              } else {
                sampleRegistry.includeNewTracksAtEndResetHeight();
              }
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
   * Add a VCF onto an existing track (track "+" menu), even when other VCFs are
   * already open. Maps every eligible sample column onto this track and attaches
   * a sidebar VCF file entry under it.
   */
  public static void addVcfToTrack(int sampleIndex) {
    SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    if (sampleIndex < 0 || sampleIndex >= sampleRegistry.getSampleTracks().size()) {
      return;
    }

    SampleTrack track = sampleRegistry.getSampleTracks().get(sampleIndex);
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Add VCF to " + track.getDisplayName());
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

    File file = fileChooser.showOpenDialog(MainApp.stage);
    if (file == null) {
      return;
    }
    UserPreferences.setLastDirectory("VCF", file.getParentFile());

    final int trackIndex = sampleIndex;
    SampleOpenFailuresDialog failures = SampleOpenFailuresDialog.create();
    ThreadRunner.get().submit(
        "Opening VCF: " + file.getName() + "\u2026",
        () -> {
          try {
            Path vcfPath = file.toPath();
            if (!VcfManager.getInstance().isVcfFileLoaded(file)) {
              VcfReader reader = new VcfReader(vcfPath);
              if (!reader.hasTbiOrCsiIndex()) {
                try {
                  reader.close();
                } catch (IOException ignored) {
                }
                throw new IOException("Tabix/CSI index (.tbi/.csi) not found for: " + vcfPath);
              }
              VariantLoader loader = new VariantLoader(reader);
              SomaticSampleResolver.resolve(
                  loader, true, new SomaticSampleResolver.BatchContext());
              loader.mapAllEligibleToTrack(trackIndex);
              VcfManager.VcfData vcfData =
                  VcfManager.getInstance().registerLoadedVcf(reader, loader, file);
              if (vcfData == null) {
                try {
                  reader.close();
                } catch (IOException ignored) {
                }
                loader.setVcfReader(null);
              } else {
                try {
                  reader.close();
                } catch (IOException ignored) {
                }
                vcfData.reader = null;
                loader.setVcfReader(null);
                UserPreferences.addRecentFile("VCF", file);
              }
            }
            // Sidebar entry + co-located BAM (worker thread, same as batch VCF open).
            if (sampleIndex >= 0
                && sampleIndex < sampleRegistry.getSampleTracks().size()
                && sampleRegistry.getSampleTracks().get(sampleIndex) == track) {
              addVcfSampleIfMissing(track, vcfPath);
              attachMatchingBamToTrack(track, vcfPath, failures);
            }
            return vcfPath;
          } catch (Exception e) {
            System.err.println("Failed to open VCF: " + file + " - " + e.getMessage());
            e.printStackTrace();
            failures.add(file, e.getMessage());
            return null;
          }
        },
        vcfPath -> {
          failures.commitToSessionLog();
          Platform.runLater(() -> {
            ProjectSessionState.get().markDirty();
            GenomicCanvas.update.set(!GenomicCanvas.update.get());
            VcfManager.getInstance().loadVariantsForCurrentView();
            org.baseplayer.variant.ui.VariantManagerWindow.openVariantManager(
                MainApp.stage, VcfManager.getInstance(), null);
            org.baseplayer.controllers.MainController.initializeLoadRegionButton();
            org.baseplayer.controllers.MainController.addLoadRegionButtonToViewport();
            SampleOpenFailuresDialog.showPendingNonModal();
          });
        });
  }

  /**
   * Remove one data file from a track. When it is the only file, removes the whole
   * track. For VCF files, purges bound sample calls and unloads orphaned VCFs.
   */
  public static void removeFileFromTrack(int sampleIndex, Sample file) {
    SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    if (file == null || sampleIndex < 0 || sampleIndex >= sampleRegistry.getSampleTracks().size()) {
      return;
    }
    SampleTrack track = sampleRegistry.getSampleTracks().get(sampleIndex);
    if (track == null) {
      return;
    }
    int fileIndex = track.getSamples().indexOf(file);
    if (fileIndex < 0) {
      return;
    }
    if (track.getSampleCount() <= 1) {
      removeSample(sampleIndex);
      return;
    }

    boolean isVcf = file.getDataType() == Sample.DataType.VCF;
    if (isVcf) {
      java.util.IdentityHashMap<org.baseplayer.variant.VariantList, Boolean> seen =
          new java.util.IdentityHashMap<>();
      java.util.function.Consumer<org.baseplayer.variant.VariantList> purge = variantList -> {
        if (variantList == null || seen.put(variantList, Boolean.TRUE) != null) {
          return;
        }
        variantList.removeSampleFile(file);
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
      for (org.baseplayer.variant.VariantList cached :
          VcfManager.getInstance().snapshotVariantCache().values()) {
        purge.accept(cached);
      }
    }

    track.removeSample(fileIndex);

    if (isVcf) {
      VcfManager.getInstance().unloadVcfsWithNoOpenSamples();
      DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
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
      refreshVariantPresentation();
    } else {
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
    }
    ProjectSessionState.get().markDirty();
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
          boolean created = addSampleMergingByName(sampleRegistry, newSample);
          if (created) {
            sampleRegistry.includeNewTracksAtEndResetHeight();
          }
          UserPreferences.addRecentFile("BED", file);
          ProjectSessionState.get().markDirty();
          GenomicCanvas.update.set(!GenomicCanvas.update.get());
        });
  }

  /**
   * Add an AB1 file to an existing individual's track.
   */
  public static void addAb1ToTrack(int sampleIndex) {
    SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    if (sampleIndex < 0 || sampleIndex >= sampleRegistry.getSampleTracks().size()) {
      return;
    }

    SampleTrack track = sampleRegistry.getSampleTracks().get(sampleIndex);
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Add AB1 / Sanger to " + track.getDisplayName());
    File lastDir = UserPreferences.getLastDirectory("AB1");
    if (lastDir != null) {
      try {
        fileChooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException e) {
        System.err.println("Last directory not accessible: " + lastDir + ". Using default.");
      }
    }
    fileChooser.getExtensionFilters().addAll(
        new ExtensionFilter("AB1 files", "*.ab1", "*.AB1"),
        new ExtensionFilter("All files", "*.*")
    );

    List<File> files = fileChooser.showOpenMultipleDialog(MainApp.stage);
    if (files == null || files.isEmpty()) {
      return;
    }
    UserPreferences.setLastDirectory("AB1", files.get(0).getParentFile());

    SampleOpenFailuresDialog failures = SampleOpenFailuresDialog.create();
    ThreadRunner.get().submit(
        "Loading AB1 files",
        () -> {
          List<Sample> loaded = new ArrayList<>();
          for (File file : files) {
            if (file == null) {
              continue;
            }
            try {
              SangerTrace trace = new SangerTrace(file.toPath());
              loaded.add(new Sample(file.toPath(), trace));
            } catch (IOException e) {
              System.err.println("Failed to open AB1: " + file + " - " + e.getMessage());
              failures.add(file, e.getMessage());
            }
          }
          return loaded;
        },
        loaded -> {
          if (loaded != null) {
            for (Sample sample : loaded) {
              track.addSample(sample);
              if (sample.getPath() != null) {
                UserPreferences.addRecentFile("AB1", sample.getPath().toFile());
              }
            }
          }
          ProjectSessionState.get().markDirty();
          GenomicCanvas.update.set(!GenomicCanvas.update.get());
          failures.commitAndShowLater();
        });
  }

  /**
   * Open an AB1 file chooser and add chromatograms as sample tracks.
   */
  public static void addAb1Files() {
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Open AB1 / Sanger File(s)");
    File lastDir = UserPreferences.getLastDirectory("AB1");
    if (lastDir != null) {
      try {
        fileChooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException e) {
        System.err.println("Last directory not accessible: " + lastDir + ". Using default.");
      }
    }
    fileChooser.getExtensionFilters().addAll(
        new ExtensionFilter("AB1 files", "*.ab1", "*.AB1"),
        new ExtensionFilter("All files", "*.*")
    );
    List<File> files = fileChooser.showOpenMultipleDialog(MainApp.stage);
    if (files == null || files.isEmpty()) {
      return;
    }
    UserPreferences.setLastDirectory("AB1", files.get(0).getParentFile());
    addAb1Files(files);
  }

  /** Open a single AB1 file directly without showing a chooser. */
  public static void addAb1File(File file) {
    if (file == null) {
      return;
    }
    UserPreferences.setLastDirectory("AB1", file.getParentFile());
    addAb1Files(Collections.singletonList(file));
  }

  /**
   * Open AB1 files directly without showing a chooser.
   */
  public static void addAb1Files(List<File> files) {
    if (files == null || files.isEmpty()) {
      return;
    }

    SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    SampleOpenFailuresDialog failures = SampleOpenFailuresDialog.create();
    final int totalFiles = files.size();

    ThreadRunner.get().submit(
        "Loading AB1 files",
        () -> {
          List<Sample> loaded = new ArrayList<>();
          int index = 0;
          for (File file : files) {
            if (file == null) {
              continue;
            }
            index++;
            org.baseplayer.services.LoadingManager.get().setProgress(index, totalFiles);
            try {
              SangerTrace trace = new SangerTrace(file.toPath());
              loaded.add(new Sample(file.toPath(), trace));
            } catch (IOException e) {
              System.err.println("Failed to open AB1: " + file + " - " + e.getMessage());
              failures.add(file, e.getMessage());
            }
          }
          return loaded;
        },
        loaded -> {
          boolean addedTracks = false;
          if (loaded != null) {
            for (Sample sample : loaded) {
              if (addSampleMergingByName(sampleRegistry, sample)) {
                addedTracks = true;
              }
              if (sample.getPath() != null) {
                UserPreferences.addRecentFile("AB1", sample.getPath().toFile());
              }
            }
          }
          int trackCount = sampleRegistry.getDisplayedTrackCount();
          if (trackCount > 0) {
            if (addedTracks) {
              sampleRegistry.showAllTracksResetHeight();
            } else {
              sampleRegistry.includeNewTracksAtEndResetHeight();
            }
          }
          ProjectSessionState.get().markDirty();
          GenomicCanvas.update.set(!GenomicCanvas.update.get());
          MainController.initializeLoadRegionButton();
          MainController.addLoadRegionButtonToViewport();
          failures.commitAndShowLater();
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

    Path bedPath = file.toPath();
    ThreadRunner.get().submit(
        "Scanning " + file.getName() + "\u2026",
        () -> {
          try {
            return org.baseplayer.io.readers.BedFeatureNameCatalog.scanForDialog(bedPath);
          } catch (IOException e) {
            System.err.println("Failed to scan BED names: " + e.getMessage());
            return java.util.Optional
                .<org.baseplayer.io.readers.BedFeatureNameCatalog.Catalog>empty();
          }
        },
        catalogOpt -> {
          org.baseplayer.features.BedFeatureFilterDialog.Outcome filter = null;
          if (catalogOpt != null
              && catalogOpt.isPresent()
              && catalogOpt.get().shouldPromptTypeSelection()) {
            var choice = org.baseplayer.features.BedFeatureFilterDialog.show(
                MainApp.stage,
                file.getName(),
                catalogOpt.get(),
                null,
                "Load",
                bedPath);
            if (choice.isEmpty()) {
              return;
            }
            filter = choice.get();
          }
          org.baseplayer.features.BedFeatureFilterDialog.Outcome finalFilter = filter;
          ThreadRunner.get().submit(
              "Loading " + file.getName() + "\u2026",
              () -> {
                try {
                  BedTrack track = new BedTrack(bedPath);
                  if (finalFilter != null && finalFilter.catalog() != null) {
                    track.setFeatureNameCatalog(finalFilter.catalog());
                  } else if (catalogOpt != null && catalogOpt.isPresent()) {
                    track.setFeatureNameCatalog(catalogOpt.get());
                  }
                  if (finalFilter != null && !finalFilter.loadAll()) {
                    track.setNameFilter(
                        finalFilter.selectedKeys(), finalFilter.listedNames());
                  }
                  return track;
                } catch (IOException e) {
                  System.err.println("Failed to load BED: " + e.getMessage());
                  return null;
                }
              },
              bedTrack -> {
                if (bedTrack == null) {
                  return;
                }
                bedTrack.setVisible(true);
                featureCanvas.addTrack(bedTrack);
                UserPreferences.addRecentFile("BED", file);
                ProjectSessionState.get().markDirty();
              });
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
   * Files are registered sequentially in a single ThreadRunner task; viewport
   * resize / canvas redraw run once after the last file.
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
            final boolean addedTracks = Boolean.TRUE.equals(result);
            Platform.runLater(() -> {
              finishVcfBatchUi(addedTracks);
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

  /** One viewport fit + redraw after a multi-file VCF open (not per file). */
  private static void finishVcfBatchUi(boolean addedTracks) {
    if (addedTracks) {
      ServiceRegistry.getInstance().getSampleRegistry().includeNewTracksAtEndResetHeight();
    }
    ProjectSessionState.get().markDirty();
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }
  
  /**
   * Open many VCFs without per-file canvas resize / session rewrite.
   * @return true if any new sample tracks were created
   */
  private static Boolean loadVcfFilesBatchWithProgress(
      List<File> files, int totalFiles, SampleOpenFailuresDialog failures) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    registry.beginBatchTrackMutation();
    boolean addedTracks = false;
    SomaticSampleResolver.BatchContext somaticBatch = new SomaticSampleResolver.BatchContext();
    try {
      for (int index = 0; index < files.size(); index++) {
        if (Thread.currentThread().isInterrupted()) {
          break;
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
          if (loadVcfFileSynchronously(file, true, failures, somaticBatch)) {
            addedTracks = true;
          }
        } catch (Exception e) {
          if (Thread.currentThread().isInterrupted()) {
            break;
          }
          System.err.println("Failed to load VCF: " + file + " - " + e.getMessage());
          e.printStackTrace();
          if (failures != null) {
            failures.add(file, e.getMessage());
          }
        }
      }
    } finally {
      registry.endBatchTrackMutation();
    }
    return addedTracks;
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
    registry.beginBatchTrackMutation();
    try {
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
          // No dialogs on restore — heuristics + saved project somatic preference only.
          SomaticSampleResolver.resolve(loader, false, null);

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
              if (registry.findTrackMatchingName(sampleName) != null) {
                continue;
              }
              SampleTrack track = new SampleTrack(sampleName);
              registry.getSampleTracks().add(track);
              registry.getSampleList().add(sampleName);
              addedTracks = true;
            }
          }
          loader.updateMapping();

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
    } finally {
      registry.endBatchTrackMutation();
    }

    if (addedTracks) {
      registry.includeNewTracksAtEndResetHeight();
    }
  }

  private static boolean loadVcfFileSynchronously(
      File file,
      boolean deferUi,
      SampleOpenFailuresDialog failures,
      SomaticSampleResolver.BatchContext somaticBatch) throws IOException {
    if (file == null || !file.exists()) {
      throw new IOException("VCF file not found: " + file);
    }

    if (VcfManager.getInstance().isVcfFileLoaded(file)) {
      return false;
    }
    
    Path vcfPath = file.toPath();
    VcfReader reader = new VcfReader(vcfPath);
    if (!reader.hasTbiOrCsiIndex()) {
      try { reader.close(); } catch (IOException ignored) {}
      throw new IOException("Tabix/CSI index (.tbi/.csi) not found for: " + vcfPath);
    }
    VariantLoader loader = new VariantLoader(reader);
    SomaticSampleResolver.resolve(
        loader, true, somaticBatch != null ? somaticBatch : new SomaticSampleResolver.BatchContext());
    
    List<String> unmappedSamples = loader.getUnmappedSamples();
    
    VcfManager.VcfData vcfData = VcfManager.getInstance().registerLoadedVcf(reader, loader, file);
    if (vcfData == null) {
      try { reader.close(); } catch (IOException ignored) {}
      loader.setVcfReader(null);
      return false;
    }

    // Create tracks on this thread (same as session restore) so mapping is ready
    // before the batch completion callback starts variant loading.
    boolean addedTracks = false;
    if (!unmappedSamples.isEmpty()) {
      SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
      for (String sampleName : unmappedSamples) {
        if (registry.findTrackMatchingName(sampleName) != null) {
          continue;
        }
        SampleTrack track = new SampleTrack(sampleName);
        registry.getSampleTracks().add(track);
        registry.getSampleList().add(sampleName);
        addedTracks = true;
      }
      loader.updateMapping();
    }

    // Sidebar VCF entries — safe on the worker thread; no canvas resize.
    attachVcfSamplesToMappedTracks(loader, vcfPath);
    // Co-located BAM/CRAM with a matching sample or file stem → same track.
    attachMatchingBamsForMappedTracks(loader, vcfPath, failures);

    if (loader.getMappedSampleCount() == 0) {
      System.err.println("Warning: Could not create or map any VCF samples from: " + file);
    }

    final boolean createdTracks = addedTracks;
    if (!deferUi) {
      Platform.runLater(() -> {
        if (createdTracks) {
          ServiceRegistry.getInstance().getSampleRegistry().includeNewTracksAtEndResetHeight();
        }
        ProjectSessionState.get().markDirty();
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      });
    }
    
    // Close reader on background thread to free VCFHeader and tabix index immediately
    try { reader.close(); } catch (IOException ignored) {}
    vcfData.reader = null;  // Null out reader in VcfData
    loader.setVcfReader(null);  // Release loader's reference too
    
    UserPreferences.addRecentFile("VCF", file);
    return createdTracks;
  }
  
  /**
   * Attach a VCF file entry under every sample track this loader maps to,
   * so the sidebar can list {@code VCF: filename} when there is room.
   * When a mapped VCF sample ID is a shorter substring of the track name,
   * the track is renamed to that shorter ID.
   */
  public static void attachVcfSamplesToMappedTracks(VariantLoader loader, Path vcfPath) {
    if (loader == null || vcfPath == null) {
      return;
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    for (Map.Entry<String, Integer> entry : loader.getSampleMapping().entrySet()) {
      Integer trackIndex = entry.getValue();
      if (trackIndex == null || trackIndex < 0
          || trackIndex >= registry.getSampleTracks().size()) {
        continue;
      }
      SampleTrack track = registry.getSampleTracks().get(trackIndex);
      addVcfSampleIfMissing(track, vcfPath);
      preferShorterTrackName(track, entry.getKey());
    }
  }

  /**
   * When a VCF is opened, attach any co-located BAM/CRAM whose name matches a
   * mapped sample ID (or the VCF file stem for single-sample VCFs) onto that
   * sample's track. Skips tracks that already have alignment data and paths
   * already open elsewhere. Not used during session restore (BAMs come from
   * the project).
   */
  public static void attachMatchingBamsForMappedTracks(
      VariantLoader loader, Path vcfPath) {
    attachMatchingBamsForMappedTracks(loader, vcfPath, null);
  }

  public static void attachMatchingBamsForMappedTracks(
      VariantLoader loader, Path vcfPath, SampleOpenFailuresDialog failures) {
    if (loader == null || vcfPath == null) {
      return;
    }
    Map<String, Integer> mapping = loader.getSampleMapping();
    if (mapping == null || mapping.isEmpty()) {
      return;
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    boolean allowVcfStemMatch = mapping.size() == 1;
    Set<String> claimedBamPaths = new HashSet<>();

    for (Map.Entry<String, Integer> entry : mapping.entrySet()) {
      Integer trackIndex = entry.getValue();
      if (trackIndex == null || trackIndex < 0
          || trackIndex >= registry.getSampleTracks().size()) {
        continue;
      }
      SampleTrack track = registry.getSampleTracks().get(trackIndex);
      if (track == null || trackHasBam(track)) {
        continue;
      }
      Path bamPath = findMatchingBamBesideVcf(vcfPath, entry.getKey(), allowVcfStemMatch);
      if (bamPath == null) {
        continue;
      }
      String pathKey = bamPath.toAbsolutePath().normalize().toString();
      if (!claimedBamPaths.add(pathKey) || isBamPathAlreadyOpen(registry, bamPath)) {
        continue;
      }
      try {
        track.addSample(new Sample(bamPath));
        preferShorterTrackName(track, SampleRegistry.normalizeNameKey(bamPath.getFileName().toString()));
        UserPreferences.addRecentFile("BAM", bamPath.toFile());
      } catch (IOException e) {
        System.err.println("Failed to auto-open matching BAM: " + bamPath + " - " + e.getMessage());
        if (failures != null) {
          failures.add(bamPath, e.getMessage());
        } else {
          SampleOpenFailuresDialog.create().add(bamPath, e.getMessage()).commitAndShowLater();
        }
      }
    }
  }

  /**
   * Attach a co-located matching BAM/CRAM onto a single track (track "+" → Add VCF).
   */
  public static void attachMatchingBamToTrack(
      SampleTrack track, Path vcfPath, SampleOpenFailuresDialog failures) {
    if (track == null || vcfPath == null || trackHasBam(track)) {
      return;
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    Path bamPath = findMatchingBamBesideVcf(vcfPath, track.getDisplayName(), true);
    if (bamPath == null) {
      bamPath = findMatchingBamBesideVcf(vcfPath, track.getName(), true);
    }
    if (bamPath == null || isBamPathAlreadyOpen(registry, bamPath)) {
      return;
    }
    try {
      track.addSample(new Sample(bamPath));
      preferShorterTrackName(track, SampleRegistry.normalizeNameKey(bamPath.getFileName().toString()));
      UserPreferences.addRecentFile("BAM", bamPath.toFile());
    } catch (IOException e) {
      System.err.println("Failed to auto-open matching BAM: " + bamPath + " - " + e.getMessage());
      if (failures != null) {
        failures.add(bamPath, e.getMessage());
      } else {
        SampleOpenFailuresDialog.create().add(bamPath, e.getMessage()).commitAndShowLater();
      }
    }
  }

  private static boolean trackHasBam(SampleTrack track) {
    return track != null && track.getFirstBam() != null;
  }

  private static boolean isBamPathAlreadyOpen(SampleRegistry registry, Path bamPath) {
    if (registry == null || bamPath == null) {
      return false;
    }
    String pathKey = bamPath.toAbsolutePath().normalize().toString();
    for (SampleTrack track : registry.getSampleTracks()) {
      if (track == null) {
        continue;
      }
      for (Sample sample : track.getSamples()) {
        if (sample == null || sample.getDataType() != Sample.DataType.BAM || sample.getPath() == null) {
          continue;
        }
        if (pathKey.equals(sample.getPath().toAbsolutePath().normalize().toString())) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Minimum shared prefix length when matching VCF sample/file stems to BAM/CRAM
   * names that continue with pipeline suffixes (e.g. {@code .final_chr6_…}).
   */
  private static final int MIN_BAM_NAME_PREFIX_MATCH = 12;

  /**
   * Look beside the VCF for {@code <stem>.bam} / {@code .cram} where stem is the
   * VCF sample ID and, when {@code allowVcfStemMatch}, the VCF filename stem.
   * Also accepts BAM/CRAM names that continue after the stem with {@code .},
   * {@code _}, or {@code -} (or share a long delimiter-bounded prefix), so
   * {@code Sample.final_chr6_….cram} joins {@code Sample_Mutect2_….vcf.gz}.
   */
  private static Path findMatchingBamBesideVcf(
      Path vcfPath, String sampleOrTrackName, boolean allowVcfStemMatch) {
    if (vcfPath == null) {
      return null;
    }
    Path dir = vcfPath.getParent();
    if (dir == null || !Files.isDirectory(dir)) {
      return null;
    }

    LinkedHashSet<String> stems = new LinkedHashSet<>();
    String sampleStem = SampleRegistry.normalizeNameKey(sampleOrTrackName);
    if (!sampleStem.isEmpty()) {
      stems.add(sampleStem);
    }
    if (allowVcfStemMatch && vcfPath.getFileName() != null) {
      String vcfStem = SampleRegistry.normalizeNameKey(vcfPath.getFileName().toString());
      if (!vcfStem.isEmpty()) {
        stems.add(vcfStem);
      }
    }
    if (stems.isEmpty()) {
      return null;
    }

    for (String stem : stems) {
      for (String ext : List.of(".bam", ".cram")) {
        Path candidate = dir.resolve(stem + ext);
        if (Files.isRegularFile(candidate)) {
          return candidate;
        }
      }
    }

    // Fuzzy scan: exact (case-insensitive), stem-as-prefix, or shared prefix.
    Path best = null;
    int bestScore = -1;
    boolean bestTied = false;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path entry : stream) {
        if (entry == null || entry.getFileName() == null || !Files.isRegularFile(entry)) {
          continue;
        }
        String fileName = entry.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".bam") && !lower.endsWith(".cram")) {
          continue;
        }
        String key = SampleRegistry.normalizeNameKey(fileName);
        int score = -1;
        for (String stem : stems) {
          score = Math.max(score, scoreBamNameAgainstStem(stem, key));
        }
        if (score < 0) {
          continue;
        }
        if (score > bestScore) {
          bestScore = score;
          best = entry;
          bestTied = false;
        } else if (score == bestScore && best != null && !best.equals(entry)) {
          bestTied = true;
        }
      }
    } catch (IOException e) {
      System.err.println("Could not scan for matching BAM beside " + vcfPath + ": " + e.getMessage());
    }
    return bestTied ? null : best;
  }

  /**
   * Score how well a BAM/CRAM basename (extensions stripped) matches a VCF
   * sample or file stem. Higher is better; {@code -1} means no match.
   */
  static int scoreBamNameAgainstStem(String stem, String bamKey) {
    if (stem == null || bamKey == null) {
      return -1;
    }
    String a = stem.trim().toLowerCase(Locale.ROOT);
    String b = bamKey.trim().toLowerCase(Locale.ROOT);
    if (a.isEmpty() || b.isEmpty()) {
      return -1;
    }
    if (a.equals(b)) {
      return 1_000_000 + a.length();
    }
    // Sample.cram ↔ Sample.final_chr6_….cram (or stem continues with pipeline tags).
    if (b.startsWith(a) && isNameBoundary(b, a.length())) {
      return 500_000 + a.length();
    }
    if (a.startsWith(b) && isNameBoundary(a, b.length())) {
      return 500_000 + b.length();
    }
    // Sample_Mutect2_….vcf ↔ Sample.final_….cram — shared sample-id prefix.
    int lcp = commonPrefixLength(a, b);
    if (lcp < MIN_BAM_NAME_PREFIX_MATCH) {
      return -1;
    }
    if (!isNameBoundary(a, lcp) || !isNameBoundary(b, lcp)) {
      // Walk back to the last shared delimiter so we don't cut mid-token.
      int cut = lcp;
      while (cut >= MIN_BAM_NAME_PREFIX_MATCH && !isNameBoundary(a, cut)) {
        cut--;
      }
      if (cut < MIN_BAM_NAME_PREFIX_MATCH || !isNameBoundary(b, cut)) {
        return -1;
      }
      lcp = cut;
    }
    return lcp;
  }

  /** {@code index} is end-of-string or sits on {@code .} / {@code _} / {@code -}. */
  private static boolean isNameBoundary(String name, int index) {
    if (name == null || index <= 0) {
      return false;
    }
    if (index >= name.length()) {
      return true;
    }
    char c = name.charAt(index);
    return c == '.' || c == '_' || c == '-';
  }

  private static int commonPrefixLength(String a, String b) {
    int n = Math.min(a.length(), b.length());
    int i = 0;
    while (i < n && a.charAt(i) == b.charAt(i)) {
      i++;
    }
    return i;
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
    refreshVariantPresentation(true);
  }

  /**
   * Toggle VCF transparent/overlay drawing. Overlay is read live while painting,
   * so only a canvas redraw is needed — not an aggregate density rebuild.
   */
  public static void applyVcfSampleOverlay(Sample vcfSample, boolean overlay) {
    if (vcfSample == null || vcfSample.getDataType() != Sample.DataType.VCF) {
      return;
    }
    vcfSample.overlay = overlay;
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }

  /** Rebuild visible chains and aggregate density after visibility / sample-set changes. */
  public static void refreshVariantPresentation() {
    refreshVariantPresentation(true);
  }

  /**
   * @param recalculateDensity when false, skip aggregate density (e.g. overlay-only tweaks)
   */
  public static void refreshVariantPresentation(boolean recalculateDensity) {
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
      if (recalculateDensity && stack.sampleAggregateCanvas != null) {
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
   * Copy rendering + per-file visibility/transparent flags from {@code source}
   * onto every other sample track. Flags are applied by ordinal within each
   * data type (1st VCF→1st VCF, 2nd→2nd, …) so mixed transparent patterns
   * are preserved rather than broadcasting one file's setting to all.
   */
  public static void applyTrackSettingsToAll(SampleTrack source) {
    if (source == null) {
      return;
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    List<Sample> sourceBams = samplesOfType(source, Sample.DataType.BAM);
    List<Sample> sourceBeds = samplesOfType(source, Sample.DataType.BED);
    List<Sample> sourceVcfs = samplesOfType(source, Sample.DataType.VCF);
    List<Sample> sourceAb1s = samplesOfType(source, Sample.DataType.AB1);
    List<Sample> sourceBigWigs = samplesOfType(source, Sample.DataType.BIGWIG);

    boolean touchedVcfVisibility = false;
    for (SampleTrack track : registry.getSampleTracks()) {
      if (track == null || track == source) {
        continue;
      }
      track.setSignalLayout(source.getSignalLayout());
      List<Sample> targetBams = samplesOfType(track, Sample.DataType.BAM);
      List<Sample> targetBeds = samplesOfType(track, Sample.DataType.BED);
      List<Sample> targetVcfs = samplesOfType(track, Sample.DataType.VCF);
      List<Sample> targetAb1s = samplesOfType(track, Sample.DataType.AB1);
      List<Sample> targetBigWigs = samplesOfType(track, Sample.DataType.BIGWIG);

      for (int i = 0; i < targetBams.size() && i < sourceBams.size(); i++) {
        Sample from = sourceBams.get(i);
        Sample to = targetBams.get(i);
        to.visible = from.visible;
        to.overlay = from.overlay;
        AlignmentFile fromBam = from.getBamFile();
        AlignmentFile toBam = to.getBamFile();
        if (fromBam != null && toBam != null) {
          toBam.setReadColorMode(fromBam.getReadColorMode());
          toBam.setReadStackingMode(fromBam.getReadStackingMode());
          toBam.setSuppressMethylMismatches(fromBam.getSuppressMethylMismatches());
        }
      }
      for (int i = 0; i < targetBeds.size() && i < sourceBeds.size(); i++) {
        Sample from = sourceBeds.get(i);
        Sample to = targetBeds.get(i);
        to.visible = from.visible;
        to.overlay = from.overlay;
      }
      for (int i = 0; i < targetVcfs.size() && i < sourceVcfs.size(); i++) {
        Sample from = sourceVcfs.get(i);
        Sample to = targetVcfs.get(i);
        if (to.visible != from.visible) {
          to.visible = from.visible;
          touchedVcfVisibility = true;
        }
        to.overlay = from.overlay;
      }
      for (int i = 0; i < targetAb1s.size() && i < sourceAb1s.size(); i++) {
        Sample from = sourceAb1s.get(i);
        Sample to = targetAb1s.get(i);
        to.visible = from.visible;
        to.overlay = from.overlay;
      }
      for (int i = 0; i < targetBigWigs.size() && i < sourceBigWigs.size(); i++) {
        Sample from = sourceBigWigs.get(i);
        Sample to = targetBigWigs.get(i);
        to.visible = from.visible;
        to.overlay = from.overlay;
      }
    }

    ProjectSessionState.get().markDirty();
    if (touchedVcfVisibility) {
      refreshVariantPresentation(true);
    } else {
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
    }
  }

  private static List<Sample> samplesOfType(SampleTrack track, Sample.DataType type) {
    List<Sample> out = new ArrayList<>();
    if (track == null || type == null) {
      return out;
    }
    for (Sample sample : track.getSamples()) {
      if (sample != null && sample.getDataType() == type) {
        out.add(sample);
      }
    }
    return out;
  }

  /**
   * Load a single VCF file directly without showing a chooser.
   */
  public static void addVcfFile(File file) {
    if (file == null) return;
    UserPreferences.setLastDirectory("VCF", file.getParentFile());
    addVcfFiles(Collections.singletonList(file));
  }

  public static void clearAllData() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    FeatureTrackViewportRegistry featureRegistry =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();

    ThreadRunner.get().cancelAll();

    // Detach Variant Manager listeners before mutating sampleTracks, otherwise a
    // queued refreshGroups can CME while this method clears the live list.
    org.baseplayer.variant.ui.VariantManagerWindow.closeAndDispose();

    for (var track : new ArrayList<>(registry.getSampleTracks())) {
      try { track.close(); } catch (IOException e) {
        System.err.println("Error closing track: " + e.getMessage());
      }
    }
    // Full registry wipe (list, focus/filter subset, groups, visible-range slider).
    // Do not only clear() the ObservableList — that left focus refs + slider state.
    registry.clearSampleTracks();
    registry.clearAllSubsetSources();
    registry.setMasterTrackHeight(SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT);
    registry.setSampleFilterStripHeightPixels(0);
    registry.setHoverSample(-1);

    VcfManager.getInstance().closeCurrentVcf();
    // Do not reset Settings — draw prefs (read height, coverage, …) are user-global
    // and must survive new project / clear / project switch.
    ProjectSessionState.get().resetDocument();

    // Feature tracks + visible-window slider (master band / filter strip).
    featureRegistry.clearFeatureTracks();

    ProjectSessionState.get().markDirty();
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }

  /** Refresh live document sample-track paths after add/remove (SSOT). */
  public static void syncSampleTracksToDocument() {
    SessionDocumentSync.writeSampleTracksFromRuntime(ProjectSessionState.get().getFile());
    SessionDocumentSync.writeSampleGroupsFromRegistry();
  }
}

