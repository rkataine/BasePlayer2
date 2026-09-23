package org.baseplayer.project;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.baseplayer.MainApp;
import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BigWigTrack;
import org.baseplayer.features.DefaultFeatureTracks;
import org.baseplayer.features.FeatureTrack;
import org.baseplayer.features.AbstractTrack;
import org.baseplayer.features.Track;
import org.baseplayer.genome.ReferenceGenome;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.io.Settings;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.FeatureTrackViewportRegistry;
import org.baseplayer.services.InitializationService;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.VariantEffect;

import javafx.application.Platform;
import javafx.scene.paint.Color;

/**
 * Snapshots live domain state to/from {@link ProjectDocument}. Not a runtime Session god-object.
 */
public final class ProjectService {

  private ProjectService() {}

  public static ProjectDocument capture(Path projectFile) {
    ServiceRegistry services = ServiceRegistry.getInstance();
    SampleRegistry samples = services.getSampleRegistry();
    FeatureTrackViewportRegistry features = services.getFeatureTrackViewportRegistry();
    DrawStackManager stacks = services.getDrawStackManager();

    ProjectDocument doc = new ProjectDocument();
    ProjectSessionState session = ProjectSessionState.get();
    doc.name = session.getName();
    if (doc.name == null || doc.name.isBlank() || "Untitled".equals(doc.name)) {
      if (projectFile != null && projectFile.getFileName() != null) {
        String fn = projectFile.getFileName().toString();
        int dot = fn.lastIndexOf('.');
        doc.name = dot > 0 ? fn.substring(0, dot) : fn;
      }
    }

    ReferenceGenome genome = services.getReferenceGenomeService().getCurrentGenome();
    if (genome != null) {
      doc.genome.id = genome.getName();
    } else {
      doc.genome.id = Settings.get().getLastGenome();
    }
    doc.genome.annotation = Settings.get().getLastAnnotation();

    doc.settings = Settings.get().toSnapshot();
    doc.ui.darkMode = MainApp.darkMode;
    if (!stacks.getStacks().isEmpty() && stacks.getStacks().get(0).chromosomeCanvas != null) {
      doc.ui.maneOnly = stacks.getStacks().get(0).chromosomeCanvas.isShowManeOnly();
    }
    MainController main = MainController.get();
    if (main != null) {
      main.captureDividerPositions(doc.ui);
    }

    doc.variantFilter = captureFilter(VcfManager.getInstance().getCurrentFilter());
    doc.sampleViewport = captureViewport(
        samples.getFirstVisibleTrackSlot(),
        samples.getLastVisibleTrackSlot(),
        samples.getTrackRowHeightPixels(),
        samples.getVerticalScrollOffsetPixels(),
        samples.getMasterTrackHeight());
    doc.featureViewport = captureViewport(
        features.getFirstVisibleTrackSlot(),
        features.getLastVisibleTrackSlot(),
        features.getTrackRowHeightPixels(),
        features.getVerticalScrollOffsetPixels(),
        features.getMasterBandHeightPixels());
    doc.sampleFilter.query = samples.getActiveSampleFilterQuery();
    doc.sampleFilter.focusedGene = samples.getFocusedGeneName();

    for (DrawStack stack : stacks.getStacks()) {
      ProjectDocument.StackSpec spec = new ProjectDocument.StackSpec();
      spec.chromosome = stack.getChromosome();
      spec.viewStart = stack.getViewStart();
      spec.viewEnd = stack.getViewEnd();
      doc.stacks.add(spec);
    }

    VcfManager vcfManager = VcfManager.getInstance();
    List<SampleTrack> sampleTracks = samples.getSampleTracks();
    for (int trackIndex = 0; trackIndex < sampleTracks.size(); trackIndex++) {
      SampleTrack track = sampleTracks.get(trackIndex);
      ProjectDocument.SampleTrackSpec trackSpec = new ProjectDocument.SampleTrackSpec();
      trackSpec.displayName = track.getDisplayName();

      for (Sample sample : track.getSamples()) {
        if (sample.getDataType() == Sample.DataType.VCF && sample.getPath() != null) {
          trackSpec.samples.add(capturePathAsSampleFile("VCF", sample.getPath(), projectFile));
          continue;
        }
        if (sample.getBamFile() == null && sample.getBedTrack() == null) {
          continue;
        }
        ProjectDocument.SampleFileSpec fileSpec = captureSampleFile(sample, projectFile);
        if (fileSpec != null) {
          trackSpec.samples.add(fileSpec);
        }
      }

      for (File vcf : vcfManager.getVcfFilesForTrackIndex(trackIndex)) {
        if (vcf == null) continue;
        String pathKey = vcf.toPath().toAbsolutePath().normalize().toString();
        boolean alreadyListed = trackSpec.samples.stream().anyMatch(spec ->
            "VCF".equalsIgnoreCase(spec.type)
                && ((spec.path != null
                    && pathKey.equals(Path.of(spec.path).toAbsolutePath().normalize().toString()))
                    || (spec.pathRelative != null && spec.pathRelative.equals(vcf.getName()))));
        if (!alreadyListed) {
          trackSpec.samples.add(capturePathAsSampleFile("VCF", vcf.toPath(), projectFile));
        }
      }

      if (trackSpec.samples.isEmpty()) {
        continue;
      }
      doc.sampleTracks.add(trackSpec);
    }

    for (Track track : features.getFeatureTracks()) {
      ProjectDocument.FeatureTrackSpec ft = captureFeatureTrack(track, projectFile);
      if (ft != null) {
        doc.featureTracks.add(ft);
      }
    }

    return doc;
  }

  public static void save(Path projectFile) throws IOException {
    if (projectFile == null) {
      throw new IllegalArgumentException("projectFile is required");
    }
    ProjectDocument doc = capture(projectFile);
    finalizeDocumentName(doc, projectFile);
    writeDocumentAndCache(doc, projectFile);
    ProjectSessionState.get().setOpened(projectFile, doc.name);
    UserPreferences.addRecentProject(projectFile.toFile());
  }

  /**
   * Capture on the FX thread, then write JSON + variant cache in the background
   * with the shared loading popup.
   */
  public static void saveAsync(Path projectFile, Runnable onSuccess, java.util.function.Consumer<Exception> onError) {
    if (projectFile == null) {
      if (onError != null) {
        onError.accept(new IllegalArgumentException("projectFile is required"));
      }
      return;
    }

    ProjectDocument doc;
    try {
      doc = capture(projectFile);
      finalizeDocumentName(doc, projectFile);
    } catch (Exception e) {
      if (onError != null) onError.accept(e);
      return;
    }

    final ProjectDocument snapshot = doc;
    final Exception[] writeError = new Exception[1];
    ThreadRunner.get().submit("Saving project…",
        () -> {
          try {
            writeDocumentAndCache(snapshot, projectFile);
            return Boolean.TRUE;
          } catch (Exception e) {
            writeError[0] = e;
            return null;
          }
        },
        ok -> {
          if (ok == null || writeError[0] != null) {
            Exception err = writeError[0] != null
                ? writeError[0]
                : new IOException("Session save failed");
            if (onError != null) onError.accept(err);
            return;
          }
          ProjectSessionState.get().setOpened(projectFile, snapshot.name);
          UserPreferences.addRecentProject(projectFile.toFile());
          if (onSuccess != null) onSuccess.run();
        });
  }

  private static void finalizeDocumentName(ProjectDocument doc, Path projectFile) {
    if (doc.name == null || doc.name.isBlank()) {
      String fn = projectFile.getFileName().toString();
      int dot = fn.lastIndexOf('.');
      doc.name = dot > 0 ? fn.substring(0, dot) : fn;
    }
  }

  private static void writeDocumentAndCache(ProjectDocument doc, Path projectFile) throws IOException {
    ProjectSerializer.write(doc, projectFile);
    try {
      VariantCacheStore.writeSessionCache(projectFile);
    } catch (Exception e) {
      System.err.println("Failed to write variant session cache: " + e.getMessage());
      e.printStackTrace();
      if (e instanceof IOException io) {
        throw io;
      }
      throw new IOException("Failed to write variant session cache", e);
    }
  }

  /**
   * Clears current session and restores {@code document}. Runs heavy IO off the FX thread.
   *
   * @param onDone called on FX thread when finished (may be null)
   */
  public static void loadAsync(Path projectFile, ProjectDocument document, Runnable onDone) {
    if (document == null) {
      if (onDone != null) Platform.runLater(onDone);
      return;
    }

    List<String> warnings = new ArrayList<>();

    Platform.runLater(() -> {
      ProjectSessionState.get().setSuppressDirty(true);
      SampleDataManager.clearAllData();
      clearUserFeatureTracks();

      if (document.settings != null && !document.settings.isEmpty()) {
        Settings.get().applySnapshot(document.settings);
      }
      applyGenome(document, warnings);
      applyDarkMode(document.ui != null && document.ui.darkMode);
      applyManeOnly(document.ui == null || document.ui.maneOnly);

      ThreadRunner.get().submit("Opening project…",
          () -> {
            restoreSampleTracks(document, projectFile, warnings);
            restoreVcfs(document, projectFile, warnings);
            Map<String, VariantList> cachedVariants = Map.of();
            try {
              org.baseplayer.services.LoadingManager.get().setProgress(0, 1);
              cachedVariants = VariantCacheStore.readSessionCache(projectFile, warnings);
            } catch (Exception e) {
              warnings.add("Failed to read variant cache: " + e.getMessage());
            }
            return cachedVariants;
          },
          cachedVariants -> {
            try {
              restoreFeatureTracks(document, projectFile, warnings);
              restoreFiltersAndViewports(document);
              restoreStacks(document);
              restoreUiDividers(document);
              String name = document.name;
              ProjectSessionState.get().setOpened(projectFile, name);
              if (projectFile != null) {
                UserPreferences.addRecentProject(projectFile.toFile());
              }
              GenomicCanvas.update.set(!GenomicCanvas.update.get());

              boolean usedCache = false;
              if (cachedVariants != null && !cachedVariants.isEmpty()) {
                usedCache = VcfManager.getInstance().installCachedVariantLists(cachedVariants);
              }

              if (VcfManager.getInstance().hasLoadedVcf()) {
                if (!usedCache) {
                  VcfManager.getInstance().loadVariantsForCurrentView();
                }
                org.baseplayer.variant.ui.VariantManagerWindow.openVariantManager(
                    MainApp.stage, VcfManager.getInstance(), null);
                org.baseplayer.controllers.MainController.initializeLoadRegionButton();
                org.baseplayer.controllers.MainController.addLoadRegionButtonToViewport();
              }

              if (!warnings.isEmpty()) {
                System.err.println("Session open warnings:");
                for (String w : warnings) {
                  System.err.println("  - " + w);
                }
              }
              if (onDone != null) onDone.run();
            } finally {
              ProjectSessionState.get().setSuppressDirty(false);
            }
          });
    });
  }

  private static ProjectDocument.ViewportSpec captureViewport(
      int first, int last, double rowHeight, double scroll, double masterBand) {
    ProjectDocument.ViewportSpec spec = new ProjectDocument.ViewportSpec();
    spec.first = first;
    spec.last = last;
    spec.rowHeight = rowHeight;
    spec.scroll = scroll;
    spec.masterBandHeight = masterBand;
    return spec;
  }

  private static ProjectDocument.VariantFilterSpec captureFilter(VariantFilter filter) {
    ProjectDocument.VariantFilterSpec spec = new ProjectDocument.VariantFilterSpec();
    if (filter == null) return spec;
    spec.minQuality = filter.getMinQuality();
    spec.minDepth = filter.getMinDepth();
    spec.minAlleleFraction = filter.getMinAlleleFraction();
    spec.cancerGenesOnly = filter.isCancerGenesOnly();
    spec.minSharedSamples = filter.getMinSharedSamples();
    spec.maxSharedSamples = filter.getMaxSharedSamples();
    spec.geneLevel = filter.isGeneLevel();
    spec.comparisonWindowBp = filter.getComparisonWindowBp();
    for (VcfVariantType t : filter.getAllowedTypes()) {
      if (t != null) spec.allowedTypes.add(t.name());
    }
    for (VariantEffect e : filter.getAllowedEffects()) {
      if (e != null) spec.allowedEffects.add(e.name());
    }
    if (filter.getInfoFieldFilters() != null) {
      spec.infoFieldFilters = new HashMap<>(filter.getInfoFieldFilters());
    }
    if (filter.getAllowedFilterValues() != null) {
      spec.allowedFilterValues = new ArrayList<>(filter.getAllowedFilterValues());
    }
    return spec;
  }

  private static VariantFilter restoreFilter(ProjectDocument.VariantFilterSpec spec) {
    VariantFilter filter = new VariantFilter();
    if (spec == null) return filter;
    filter.setMinQuality(spec.minQuality);
    filter.setMinDepth(spec.minDepth);
    filter.setMinAlleleFraction(spec.minAlleleFraction);
    filter.setCancerGenesOnly(spec.cancerGenesOnly);
    filter.setMinSharedSamples(spec.minSharedSamples > 0 ? spec.minSharedSamples : 1);
    filter.setMaxSharedSamples(spec.maxSharedSamples > 0 ? spec.maxSharedSamples : Integer.MAX_VALUE);
    filter.setGeneLevel(spec.geneLevel);
    filter.setComparisonWindowBp(Math.max(0, spec.comparisonWindowBp));
    if (spec.allowedTypes != null && !spec.allowedTypes.isEmpty()) {
      EnumSet<VcfVariantType> types = EnumSet.noneOf(VcfVariantType.class);
      for (String name : spec.allowedTypes) {
        try {
          types.add(VcfVariantType.valueOf(name));
        } catch (Exception ignored) { /* skip */ }
      }
      if (!types.isEmpty()) filter.setAllowedTypes(types);
    }
    if (spec.allowedEffects != null && !spec.allowedEffects.isEmpty()) {
      EnumSet<VariantEffect> effects = EnumSet.noneOf(VariantEffect.class);
      for (String name : spec.allowedEffects) {
        try {
          effects.add(VariantEffect.valueOf(name));
        } catch (Exception ignored) { /* skip */ }
      }
      filter.setAllowedEffects(effects);
    }
    if (spec.infoFieldFilters != null) {
      filter.setInfoFieldFilters(new HashMap<>(spec.infoFieldFilters));
    }
    if (spec.allowedFilterValues != null) {
      filter.setAllowedFilterValues(new HashSet<>(spec.allowedFilterValues));
    }
    return filter;
  }

  private static ProjectDocument.SampleFileSpec captureSampleFile(Sample sample, Path projectFile) {
    ProjectDocument.SampleFileSpec spec = new ProjectDocument.SampleFileSpec();
    if (sample.getBamFile() != null) {
      spec.type = "BAM";
    } else if (sample.getBedTrack() != null) {
      spec.type = "BED";
    } else {
      return null;
    }
    Path path = sample.getPath();
    if (path != null) {
      Path abs = path.toAbsolutePath().normalize();
      spec.path = PathResolver.toAbsoluteString(abs);
      String relative = PathResolver.toRelativeString(abs, projectFile);
      if (relative != null) {
        spec.pathRelative = relative;
      }
    }
    spec.visible = sample.visible;
    spec.overlay = sample.overlay;
    return spec;
  }

  private static ProjectDocument.SampleFileSpec capturePathAsSampleFile(
      String type, Path path, Path projectFile) {
    ProjectDocument.SampleFileSpec spec = new ProjectDocument.SampleFileSpec();
    spec.type = type;
    if (path != null) {
      Path abs = path.toAbsolutePath().normalize();
      spec.path = PathResolver.toAbsoluteString(abs);
      String relative = PathResolver.toRelativeString(abs, projectFile);
      if (relative != null) {
        spec.pathRelative = relative;
      }
    }
    spec.visible = true;
    spec.overlay = false;
    return spec;
  }

  private static ProjectDocument.FeatureTrackSpec captureFeatureTrack(Track track, Path projectFile) {
    if (track == null) return null;
    ProjectDocument.FeatureTrackSpec spec = new ProjectDocument.FeatureTrackSpec();
    spec.displayName = track.getName();
    spec.visible = track.isVisible();
    if (track.getColor() != null) {
      spec.color = colorToHex(track.getColor());
    }
    spec.min = track.getMinValue();
    spec.max = track.getMaxValue();

    if (track instanceof BedTrack) {
      spec.kind = "bed";
      Path path = track.getSourcePath();
      if (path != null) {
        Path abs = path.toAbsolutePath().normalize();
        spec.path = PathResolver.toAbsoluteString(abs);
        String relative = PathResolver.toRelativeString(abs, projectFile);
        if (relative != null) {
          spec.pathRelative = relative;
        }
      }
    } else if (track instanceof BigWigTrack) {
      spec.kind = "bigwig";
      Path path = track.getSourcePath();
      if (path != null) {
        Path abs = path.toAbsolutePath().normalize();
        spec.path = PathResolver.toAbsoluteString(abs);
        String relative = PathResolver.toRelativeString(abs, projectFile);
        if (relative != null) {
          spec.pathRelative = relative;
        }
      }
    } else if (track.getUcscTrackId() != null) {
      spec.kind = "ucsc";
      spec.ucscTrackId = track.getUcscTrackId();
    } else if (track.getName() != null
        && DefaultFeatureTracks.isGnomad(track)) {
      spec.kind = "gnomad";
    } else {
      spec.kind = "feature";
    }
    return spec;
  }

  private static void applyGenome(ProjectDocument document, List<String> warnings) {
    String genomeId = document.genome != null ? document.genome.id : null;
    if (genomeId == null || genomeId.isBlank()) {
      genomeId = Settings.get().getLastGenome();
    }
    if (genomeId == null || genomeId.isBlank()) return;

    InitializationService init = new InitializationService();
    List<ReferenceGenome> genomes = init.loadAvailableGenomes();
    ReferenceGenome match = null;
    for (ReferenceGenome g : genomes) {
      if (genomeId.equals(g.getName())) {
        match = g;
        break;
      }
    }
    if (match == null) {
      warnings.add("Genome not found: " + genomeId);
      return;
    }
    Settings.get().setLastGenome(match.getName());
    if (document.genome != null && document.genome.annotation != null) {
      Settings.get().setLastAnnotation(document.genome.annotation);
    }
    init.selectReferenceGenome(match);
  }

  private static void applyDarkMode(boolean wantDark) {
    if (MainApp.darkMode != wantDark) {
      MainApp.setDarkMode();
    }
  }

  private static void applyManeOnly(boolean maneOnly) {
    for (DrawStack stack : ServiceRegistry.getInstance().getDrawStackManager().getStacks()) {
      if (stack.chromosomeCanvas != null) {
        stack.chromosomeCanvas.setShowManeOnly(maneOnly);
      }
    }
  }

  private static void clearUserFeatureTracks() {
    ServiceRegistry.getInstance().getFeatureTrackViewportRegistry().clearFeatureTracks();
  }

  private static void seedDefaultFeatureTracks() {
    FeatureTrackViewportRegistry features =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
    features.addFeatureTrack(DefaultFeatureTracks.createPhyloP());
    features.addFeatureTrack(DefaultFeatureTracks.createGnomad());
  }

  private static void restoreSampleTracks(
      ProjectDocument document, Path projectFile, List<String> warnings) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    if (document.sampleTracks == null) return;

    for (ProjectDocument.SampleTrackSpec trackSpec : document.sampleTracks) {
      if (trackSpec == null) continue;
      try {
        SampleTrack track = null;
        if (trackSpec.samples == null || trackSpec.samples.isEmpty()) {
          if (trackSpec.displayName != null && !trackSpec.displayName.isBlank()) {
            track = new SampleTrack(trackSpec.displayName);
          }
        } else {
          for (ProjectDocument.SampleFileSpec fileSpec : trackSpec.samples) {
            if (fileSpec == null) continue;
            String type = fileSpec.type == null ? "" : fileSpec.type.toUpperCase(Locale.ROOT);

            // VCF: ensure a named track exists and list the file in the sidebar.
            if ("VCF".equals(type)) {
              if (track == null) {
                track = new SampleTrack(
                    trackSpec.displayName != null ? trackSpec.displayName : "Sample");
              }
              Path path = PathResolver.resolve(fileSpec.path, fileSpec.pathRelative, projectFile);
              if (path != null && path.toFile().exists()) {
                Sample vcfSample = new Sample(path, Sample.DataType.VCF);
                vcfSample.visible = fileSpec.visible;
                vcfSample.overlay = fileSpec.overlay;
                track.addSample(vcfSample);
              } else if (fileSpec.path != null || fileSpec.pathRelative != null) {
                warnings.add("Missing VCF: "
                    + (fileSpec.path != null ? fileSpec.path : fileSpec.pathRelative));
              }
              continue;
            }

            // Legacy NAME placeholders — create track by display name only.
            if ("NAME".equals(type) || (fileSpec.path == null && fileSpec.pathRelative == null)) {
              if (track == null) {
                track = new SampleTrack(
                    trackSpec.displayName != null ? trackSpec.displayName : "Sample");
              }
              continue;
            }

            Path path = PathResolver.resolve(fileSpec.path, fileSpec.pathRelative, projectFile);
            if (path == null || !path.toFile().exists()) {
              warnings.add("Missing sample file: "
                  + (fileSpec.path != null ? fileSpec.path : fileSpec.pathRelative));
              continue;
            }
            Sample sample;
            if ("BED".equals(type)) {
              BedTrack bed = new BedTrack(path);
              sample = new Sample(path, bed);
            } else if ("BAM".equals(type) || type.isEmpty()) {
              sample = new Sample(path);
            } else {
              warnings.add("Unknown sample type: " + type);
              continue;
            }
            sample.visible = fileSpec.visible;
            sample.overlay = fileSpec.overlay;
            if (track == null) {
              track = new SampleTrack(sample);
              if (trackSpec.displayName != null && !trackSpec.displayName.isBlank()) {
                track.setCustomName(trackSpec.displayName);
              }
            } else {
              track.addSample(sample);
            }
          }
        }
        if (track == null) {
          continue;
        }
        registry.getSampleTracks().add(track);
        registry.getSampleList().add(track.getDisplayName());
      } catch (Exception e) {
        warnings.add("Failed to restore sample track "
            + trackSpec.displayName + ": " + e.getMessage());
      }
    }
    if (!registry.getSampleTracks().isEmpty()) {
      registry.includeNewTracksAtEndResetHeight();
    }
  }

  private static void restoreVcfs(ProjectDocument document, Path projectFile, List<String> warnings) {
    LinkedHashSet<String> uniqueAbsolutePaths = new LinkedHashSet<>();

    // New format: VCF entries nested under sampleTracks[].samples
    if (document.sampleTracks != null) {
      for (ProjectDocument.SampleTrackSpec trackSpec : document.sampleTracks) {
        if (trackSpec == null || trackSpec.samples == null) continue;
        for (ProjectDocument.SampleFileSpec fileSpec : trackSpec.samples) {
          if (fileSpec == null || fileSpec.type == null) continue;
          if (!"VCF".equalsIgnoreCase(fileSpec.type)) continue;
          Path path = PathResolver.resolve(fileSpec.path, fileSpec.pathRelative, projectFile);
          if (path == null || !path.toFile().exists()) {
            warnings.add("Missing VCF: "
                + (fileSpec.path != null ? fileSpec.path : fileSpec.pathRelative));
            continue;
          }
          uniqueAbsolutePaths.add(path.toAbsolutePath().normalize().toString());
        }
      }
    }

    for (String absolute : uniqueAbsolutePaths) {
      Path path = Path.of(absolute);
      CountDownLatch latch = new CountDownLatch(1);
      VcfManager.getInstance().loadVcfFileWithCallback(
          path.toFile(),
          latch::countDown,
          true);
      try {
        if (!latch.await(120, TimeUnit.SECONDS)) {
          warnings.add("Timed out loading VCF: " + path);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        warnings.add("Interrupted loading VCF: " + path);
      }
    }
  }

  private static void restoreFeatureTracks(
      ProjectDocument document, Path projectFile, List<String> warnings) {
    FeatureTrackViewportRegistry features =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();

    // Legacy sessions without a featureTracks section keep the built-in defaults.
    if (document.featureTracks == null) {
      seedDefaultFeatureTracks();
      return;
    }

    for (ProjectDocument.FeatureTrackSpec spec : document.featureTracks) {
      if (spec == null || spec.kind == null) continue;
      String kind = spec.kind.toLowerCase(Locale.ROOT);
      try {
        switch (kind) {
          case "bed" -> {
            Path path = PathResolver.resolve(spec.path, spec.pathRelative, projectFile);
            if (path == null || !path.toFile().exists()) {
              warnings.add("Missing BED track: " + spec.path);
              break;
            }
            BedTrack bed = new BedTrack(path);
            applyFeatureAppearance(bed, spec);
            features.addFeatureTrack(bed);
          }
          case "bigwig" -> {
            Path path = PathResolver.resolve(spec.path, spec.pathRelative, projectFile);
            if (path == null || !path.toFile().exists()) {
              warnings.add("Missing BigWig track: " + spec.path);
              break;
            }
            BigWigTrack bw = new BigWigTrack(path);
            applyFeatureAppearance(bw, spec);
            features.addFeatureTrack(bw);
          }
          case "ucsc" -> {
            Track existing = findFeatureByUcscId(features, spec.ucscTrackId);
            if (existing != null) {
              applyFeatureAppearance(existing, spec);
            } else if (spec.ucscTrackId != null && !spec.ucscTrackId.isBlank()) {
              String display = spec.displayName != null ? spec.displayName : spec.ucscTrackId;
              FeatureTrack track = FeatureTrack.forUcscTrack(spec.ucscTrackId, display);
              applyFeatureAppearance(track, spec);
              features.addFeatureTrack(track);
            }
          }
          case "gnomad" -> {
            Track existing = findGnomadTrack(features);
            if (existing != null) {
              applyFeatureAppearance(existing, spec);
            } else {
              FeatureTrack gnomad = DefaultFeatureTracks.createGnomad();
              applyFeatureAppearance(gnomad, spec);
              features.addFeatureTrack(gnomad);
            }
          }
          default -> { /* ignore unknown kinds */ }
        }
      } catch (Exception e) {
        warnings.add("Failed to restore feature track (" + kind + "): " + e.getMessage());
      }
    }
  }

  private static Track findFeatureByUcscId(FeatureTrackViewportRegistry features, String ucscId) {
    if (ucscId == null) return null;
    for (Track track : features.getFeatureTracks()) {
      if (ucscId.equals(track.getUcscTrackId())) return track;
    }
    return null;
  }

  private static Track findGnomadTrack(FeatureTrackViewportRegistry features) {
    for (Track track : features.getFeatureTracks()) {
      if (DefaultFeatureTracks.isGnomad(track)) {
        return track;
      }
    }
    return null;
  }

  private static void applyFeatureAppearance(Track track, ProjectDocument.FeatureTrackSpec spec) {
    track.setVisible(spec.visible);
    if (spec.color != null && track instanceof AbstractTrack abstractTrack) {
      try {
        abstractTrack.setColor(Color.web(spec.color));
      } catch (Exception ignored) { /* keep */ }
    }
    if (spec.min != null) track.setMinValue(spec.min);
    if (spec.max != null) track.setMaxValue(spec.max);
  }

  private static void restoreFiltersAndViewports(ProjectDocument document) {
    SampleRegistry samples = ServiceRegistry.getInstance().getSampleRegistry();
    FeatureTrackViewportRegistry features =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();

    VariantFilter filter = restoreFilter(document.variantFilter);
    VcfManager.getInstance().applyFilter(filter);

    if (document.sampleFilter != null) {
      samples.applyTextSubsetQuery(
          document.sampleFilter.query != null ? document.sampleFilter.query : "");
    }

    if (document.sampleViewport != null) {
      applyViewport(samples, document.sampleViewport);
      if (document.sampleViewport.masterBandHeight > 0) {
        samples.setMasterTrackHeight(document.sampleViewport.masterBandHeight);
      }
    }
    if (document.featureViewport != null) {
      applyViewport(features, document.featureViewport);
      if (document.featureViewport.masterBandHeight > 0) {
        features.setMasterBandHeightPixels(document.featureViewport.masterBandHeight);
      }
    }
  }

  private static void applyViewport(
      org.baseplayer.services.TrackViewportRegistry registry,
      ProjectDocument.ViewportSpec spec) {
    if (spec == null) return;
    double viewportHeight = registry.getTrackViewportHeightPixels();
    if (viewportHeight <= 0) {
      viewportHeight = Math.max(1, (spec.last - spec.first + 1) * Math.max(1, spec.rowHeight));
    }
    if (spec.first >= 0 && spec.last >= spec.first) {
      registry.setVisibleTrackRange(
          spec.first,
          spec.last,
          spec.rowHeight > 0 ? spec.rowHeight : Double.NaN,
          spec.scroll,
          viewportHeight);
    }
  }

  private static void restoreUiDividers(ProjectDocument document) {
    if (document.ui == null) return;
    MainController main = MainController.get();
    if (main == null) return;
    main.applyDividerPositions(document.ui);
  }

  private static void restoreStacks(ProjectDocument document) {
    DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
    List<DrawStack> stacks = stackManager.getStacks();
    if (document.stacks == null || document.stacks.isEmpty() || stacks.isEmpty()) return;

    int n = Math.min(stacks.size(), document.stacks.size());
    for (int i = 0; i < n; i++) {
      ProjectDocument.StackSpec spec = document.stacks.get(i);
      if (spec == null || spec.chromosome == null) continue;
      stacks.get(i).navigateTo(spec.chromosome, spec.viewStart, spec.viewEnd);
    }
  }

  private static String colorToHex(Color color) {
    int r = (int) Math.round(color.getRed() * 255);
    int g = (int) Math.round(color.getGreen() * 255);
    int b = (int) Math.round(color.getBlue() * 255);
    return String.format("#%02X%02X%02X", r, g, b);
  }
}
