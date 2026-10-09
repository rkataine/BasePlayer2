package org.baseplayer.project;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.baseplayer.MainApp;
import org.baseplayer.annotation.AnnotationLoader;
import org.baseplayer.components.sidebars.GenomeSidebar;
import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.features.AbstractTrack;
import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BigWigTrack;
import org.baseplayer.features.DefaultFeatureTracks;
import org.baseplayer.features.FeatureTrack;
import org.baseplayer.features.Track;
import org.baseplayer.genome.ReferenceGenome;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.io.Settings;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleGroup;
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
 * Rebuilds ephemeral runtime (handles, caches, FX) from a {@link ProjectDocument}.
 * The document itself is already the live SSOT after {@link ProjectSessionState#replaceDocument}.
 */
public final class SessionRuntime {

  private SessionRuntime() {}

  /**
   * Clear current session data and apply {@code document} (already installed as live doc).
   * Heavy IO runs off the FX thread.
   */
  public static void applyAsync(Path projectFile, ProjectDocument document, Runnable onDone) {
    if (document == null) {
      if (onDone != null) {
        Platform.runLater(onDone);
      }
      return;
    }

    List<String> warnings = new ArrayList<>();

    Platform.runLater(() -> {
      ProjectSessionState.get().setSuppressDirty(true);
      SampleDataManager.clearAllData();
      // clearAllData resets the live document — reinstall the project doc.
      ProjectSessionState.get().replaceDocument(document);
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
              if (onDone != null) {
                onDone.run();
              }
            } finally {
              ProjectSessionState.get().setSuppressDirty(false);
            }
          });
    });
  }

  static VariantFilter filterFromSpec(ProjectDocument.VariantFilterSpec spec) {
    return restoreFilter(spec);
  }

  private static void applyGenome(ProjectDocument document, List<String> warnings) {
    String genomeId = document.genome != null ? document.genome.id : null;
    if (genomeId == null || genomeId.isBlank()) {
      genomeId = Settings.get().getLastGenome();
    }
    if (genomeId == null || genomeId.isBlank()) {
      return;
    }

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
    String annotation = document.genome != null ? document.genome.annotation : null;
    if (annotation != null && !annotation.isBlank()) {
      Settings.get().setLastAnnotation(annotation);
    }
    init.selectReferenceGenome(match);

    if (!GenomeSidebar.syncFromSettings()) {
      AnnotationLoader.loadCytobands();
      AnnotationLoader.loadGenesBackground();
    }
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

  private static void restoreSampleTracks(
      ProjectDocument document, Path projectFile, List<String> warnings) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    restoreSampleGroups(document, registry);
    if (document.sampleTracks == null) {
      return;
    }

    for (ProjectDocument.SampleTrackSpec trackSpec : document.sampleTracks) {
      if (trackSpec == null) {
        continue;
      }
      try {
        SampleTrack track = null;
        if (trackSpec.samples == null || trackSpec.samples.isEmpty()) {
          if (trackSpec.displayName != null && !trackSpec.displayName.isBlank()) {
            track = new SampleTrack(trackSpec.displayName);
          }
        } else {
          for (ProjectDocument.SampleFileSpec fileSpec : trackSpec.samples) {
            if (fileSpec == null) {
              continue;
            }
            String type = fileSpec.type == null ? "" : fileSpec.type.toUpperCase(Locale.ROOT);

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
        applyTrackGroupMembership(track, trackSpec.groupIds, registry);
        applyTrackTags(track, trackSpec.tags);
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

  /**
   * Legacy subgroup id → parent root id. Cleared after each restore.
   * Also stores inferred tags for members of named Parental/Marker subgroups.
   */
  private static Map<Integer, Integer> legacySubgroupToParent = Map.of();
  private static Map<Integer, org.baseplayer.samples.SampleTag> legacySubgroupTag = Map.of();

  private static void restoreSampleGroups(ProjectDocument document, SampleRegistry registry) {
    if (registry == null) {
      return;
    }
    List<SampleGroup> flatGroups = new ArrayList<>();
    Map<Integer, Integer> childToParent = new LinkedHashMap<>();
    Map<Integer, org.baseplayer.samples.SampleTag> childTag = new LinkedHashMap<>();
    Map<Integer, ProjectDocument.SampleGroupSpec> specsById = new LinkedHashMap<>();

    if (document != null && document.sampleGroups != null) {
      for (ProjectDocument.SampleGroupSpec spec : document.sampleGroups) {
        if (spec == null || spec.id < 0) {
          continue;
        }
        specsById.put(spec.id, spec);
      }
      for (ProjectDocument.SampleGroupSpec spec : specsById.values()) {
        int parentId = spec.parentGroupId;
        if (parentId >= 0 && specsById.containsKey(parentId) && parentId != spec.id) {
          // Subgroup → merge into parent; infer tag from name.
          childToParent.put(spec.id, parentId);
          org.baseplayer.samples.SampleTag inferred = inferTagFromLegacyGroupName(spec.name);
          if (inferred != null) {
            childTag.put(spec.id, inferred);
          }
          continue;
        }
        Color color = null;
        if (spec.color != null && !spec.color.isBlank()) {
          try {
            color = Color.web(spec.color);
          } catch (Exception ignored) {
            color = null;
          }
        }
        flatGroups.add(new SampleGroup(spec.id, spec.name, color));
      }
    }
    legacySubgroupToParent = childToParent;
    legacySubgroupTag = childTag;
    registry.replaceSampleGroups(flatGroups);
  }

  private static org.baseplayer.samples.SampleTag inferTagFromLegacyGroupName(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String n = name.trim().toLowerCase(Locale.ROOT);
    if (n.contains("marker")) {
      return org.baseplayer.samples.SampleTag.MARKER;
    }
    if (n.contains("parental") || n.contains("parent")) {
      return org.baseplayer.samples.SampleTag.PARENTAL;
    }
    if (n.contains("mother") || n.equals("mom") || n.equals("maternal")) {
      return org.baseplayer.samples.SampleTag.MOTHER;
    }
    if (n.contains("father") || n.equals("dad") || n.equals("paternal")) {
      return org.baseplayer.samples.SampleTag.FATHER;
    }
    if (n.contains("child") || n.contains("daughter") || n.contains("son") || n.contains("proband")) {
      return org.baseplayer.samples.SampleTag.CHILD;
    }
    return null;
  }

  private static void applyTrackGroupMembership(
      SampleTrack track, List<Integer> groupIds, SampleRegistry registry) {
    if (track == null) {
      return;
    }
    track.clearGroup();
    if (groupIds == null || groupIds.isEmpty() || registry == null) {
      return;
    }
    for (Integer groupId : groupIds) {
      if (groupId == null || groupId < 0) {
        continue;
      }
      int resolved = groupId;
      Integer parent = legacySubgroupToParent.get(groupId);
      if (parent != null) {
        resolved = parent;
        org.baseplayer.samples.SampleTag tag = legacySubgroupTag.get(groupId);
        if (tag != null) {
          track.addTag(tag);
        }
      }
      if (registry.getSampleGroup(resolved) != null) {
        track.addGroupId(resolved);
      }
    }
  }

  private static void applyTrackTags(SampleTrack track, List<String> tagNames) {
    if (track == null || tagNames == null || tagNames.isEmpty()) {
      return;
    }
    for (String raw : tagNames) {
      org.baseplayer.samples.SampleTag tag = org.baseplayer.samples.SampleTag.fromName(raw);
      if (tag != null) {
        track.addTag(tag);
      }
    }
  }

  private static void restoreVcfs(ProjectDocument document, Path projectFile, List<String> warnings) {
    LinkedHashSet<String> uniqueAbsolutePaths = new LinkedHashSet<>();

    if (document.sampleTracks != null) {
      for (ProjectDocument.SampleTrackSpec trackSpec : document.sampleTracks) {
        if (trackSpec == null || trackSpec.samples == null) {
          continue;
        }
        for (ProjectDocument.SampleFileSpec fileSpec : trackSpec.samples) {
          if (fileSpec == null || fileSpec.type == null) {
            continue;
          }
          if (!"VCF".equalsIgnoreCase(fileSpec.type)) {
            continue;
          }
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

    if (uniqueAbsolutePaths.isEmpty()) {
      return;
    }

    List<File> files = new ArrayList<>(uniqueAbsolutePaths.size());
    for (String absolute : uniqueAbsolutePaths) {
      files.add(Path.of(absolute).toFile());
    }
    SampleDataManager.loadVcfFilesForSessionRestore(files, warnings);
  }

  private static void restoreFeatureTracks(
      ProjectDocument document, Path projectFile, List<String> warnings) {
    FeatureTrackViewportRegistry features =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();

    // Legacy sessions without a featureTracks section start with an empty feature strip.
    if (document.featureTracks == null) {
      return;
    }

    for (ProjectDocument.FeatureTrackSpec spec : document.featureTracks) {
      if (spec == null || spec.kind == null) {
        continue;
      }
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
          default -> { /* ignore */ }
        }
      } catch (Exception e) {
        warnings.add("Failed to restore feature track (" + kind + "): " + e.getMessage());
      }
    }
  }

  private static Track findFeatureByUcscId(FeatureTrackViewportRegistry features, String ucscId) {
    if (ucscId == null) {
      return null;
    }
    for (Track track : features.getFeatureTracks()) {
      if (ucscId.equals(track.getUcscTrackId())) {
        return track;
      }
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
    if (spec.min != null) {
      track.setMinValue(spec.min);
    }
    if (spec.max != null) {
      track.setMaxValue(spec.max);
    }
  }

  private static void restoreFiltersAndViewports(ProjectDocument document) {
    SampleRegistry samples = ServiceRegistry.getInstance().getSampleRegistry();
    FeatureTrackViewportRegistry features =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();

    VariantFilter filter = restoreFilter(document.variantFilter);
    restoreSessionAvailableFilters(document.variantFilter);
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
    if (spec == null) {
      return;
    }
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
    if (document.ui == null) {
      return;
    }
    MainController main = MainController.get();
    if (main == null) {
      return;
    }
    main.applyDividerPositions(document.ui);
  }

  private static void restoreStacks(ProjectDocument document) {
    DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
    List<DrawStack> stacks = stackManager.getStacks();
    if (document.stacks == null || document.stacks.isEmpty() || stacks.isEmpty()) {
      return;
    }

    int n = Math.min(stacks.size(), document.stacks.size());
    for (int i = 0; i < n; i++) {
      ProjectDocument.StackSpec spec = document.stacks.get(i);
      if (spec == null || spec.chromosome == null) {
        continue;
      }
      stacks.get(i).navigateTo(spec.chromosome, spec.viewStart, spec.viewEnd);
    }
  }

  private static VariantFilter restoreFilter(ProjectDocument.VariantFilterSpec spec) {
    VariantFilter filter = new VariantFilter();
    if (spec == null) {
      return filter;
    }

    boolean hasNested =
        (spec.point != null && spec.point.allowedTypes != null && !spec.point.allowedTypes.isEmpty())
            || (spec.sv != null && spec.sv.allowedTypes != null && !spec.sv.allowedTypes.isEmpty());

    VariantFilter point;
    VariantFilter sv;
    if (hasNested) {
      point = restoreClassFilter(spec.point);
      sv = restoreClassFilter(spec.sv);
    } else {
      point = new VariantFilter();
      point.setMinQuality(spec.minQuality);
      point.setMinDepth(spec.minDepth);
      point.setMinAlleleFraction(spec.minAlleleFraction);
      point.setMaxAlleleFraction(spec.maxAlleleFraction);
      point.setCancerGenesOnly(spec.cancerGenesOnly);
      point.setMinSharedSamples(spec.minSharedSamples > 0 ? spec.minSharedSamples : 1);
      point.setMaxSharedSamples(spec.maxSharedSamples > 0 ? spec.maxSharedSamples : Integer.MAX_VALUE);
      point.setGeneLevel(spec.geneLevel);
      point.setComparisonWindowBp(Math.max(0, spec.comparisonWindowBp));
      EnumSet<VcfVariantType> types = EnumSet.noneOf(VcfVariantType.class);
      if (spec.allowedTypes != null) {
        for (String name : spec.allowedTypes) {
          try {
            types.add(VcfVariantType.valueOf(name));
          } catch (Exception ignored) { /* skip */ }
        }
      }
      EnumSet<VcfVariantType> pointTypes = EnumSet.copyOf(
          org.baseplayer.variant.VariantTypeVisuals.VariantClass.POINT.allTypes());
      pointTypes.retainAll(types);
      if (pointTypes.isEmpty()) {
        for (VcfVariantType t : types) {
          if (org.baseplayer.variant.VariantTypeVisuals.isPoint(t)) {
            pointTypes.add(t);
          }
        }
      }
      if (pointTypes.isEmpty()) {
        pointTypes = org.baseplayer.variant.VariantTypeVisuals.VariantClass.POINT.allTypes();
      }
      point.setAllowedTypes(pointTypes);
      if (spec.allowedEffects != null && !spec.allowedEffects.isEmpty()) {
        EnumSet<VariantEffect> effects = EnumSet.noneOf(VariantEffect.class);
        for (String name : spec.allowedEffects) {
          try {
            effects.add(VariantEffect.valueOf(name));
          } catch (Exception ignored) { /* skip */ }
        }
        point.setAllowedEffects(effects);
      }
      if (spec.infoFieldFilters != null) {
        point.setInfoFieldFilters(new HashMap<>(spec.infoFieldFilters));
      }
      if (spec.allowedFilterValues != null) {
        point.setAllowedFilterValues(new HashSet<>(spec.allowedFilterValues));
      }

      sv = new VariantFilter();
      EnumSet<VcfVariantType> svTypes = EnumSet.noneOf(VcfVariantType.class);
      for (VcfVariantType t : types) {
        if (org.baseplayer.variant.VariantTypeVisuals.isStructural(t)) {
          svTypes.add(t);
        }
      }
      sv.setAllowedTypes(svTypes);
      sv.setAllowedEffects(EnumSet.allOf(VariantEffect.class));
      sv.setMinQuality(spec.minQuality);
      sv.setMinSharedSamples(spec.minSharedSamples > 0 ? spec.minSharedSamples : 1);
      sv.setMaxSharedSamples(spec.maxSharedSamples > 0 ? spec.maxSharedSamples : Integer.MAX_VALUE);
      sv.setGeneLevel(spec.geneLevel);
      sv.setComparisonWindowBp(Math.max(0, spec.comparisonWindowBp));
    }

    filter.setClassSlices(point, sv);
    resolveGroupTrackIndices(filter);
    return filter;
  }

  private static void restoreComparisonRoles(
      VariantFilter filter, ProjectDocument.ClassFilterSpec spec) {
    if (filter == null || spec == null) {
      return;
    }
    if (spec.presentMatchMode != null && !spec.presentMatchMode.isBlank()) {
      try {
        filter.setPresentMatchMode(
            VariantFilter.PresentMatchMode.valueOf(spec.presentMatchMode.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ignored) {
        filter.setPresentMatchMode(VariantFilter.PresentMatchMode.ALL);
      }
    }
    Map<org.baseplayer.samples.SampleTag, VariantFilter.GroupRole> tagRoles =
        new java.util.EnumMap<>(org.baseplayer.samples.SampleTag.class);
    Map<Integer, VariantFilter.GroupRole> legacyRoles = new HashMap<>();
    if (spec.groupRoles != null) {
      for (ProjectDocument.GroupRoleSpec roleSpec : spec.groupRoles) {
        if (roleSpec == null || roleSpec.role == null || roleSpec.role.isBlank()) {
          continue;
        }
        try {
          VariantFilter.GroupRole role =
              VariantFilter.GroupRole.valueOf(roleSpec.role.trim().toUpperCase(Locale.ROOT));
          if (role == VariantFilter.GroupRole.IGNORE) {
            continue;
          }
          if (roleSpec.tag != null && !roleSpec.tag.isBlank()) {
            org.baseplayer.samples.SampleTag tag =
                org.baseplayer.samples.SampleTag.fromName(roleSpec.tag);
            if (tag != null) {
              tagRoles.put(tag, role);
            }
          } else if (roleSpec.groupId != Integer.MIN_VALUE) {
            legacyRoles.put(roleSpec.groupId, role);
          }
        } catch (IllegalArgumentException ignored) {
          // skip
        }
      }
    }
    if (!tagRoles.isEmpty()) {
      filter.setTagRoles(tagRoles);
    } else {
      filter.setGroupRoles(legacyRoles);
    }
  }

  private static void resolveGroupTrackIndices(VariantFilter filter) {
    if (filter == null) {
      return;
    }
    if (filter.getPointSlice() != null) {
      resolveGroupTrackIndicesLocal(filter.getPointSlice());
    }
    if (filter.getSvSlice() != null) {
      resolveGroupTrackIndicesLocal(filter.getSvSlice());
    }
    resolveGroupTrackIndicesLocal(filter);
  }

  private static void resolveGroupTrackIndicesLocal(VariantFilter filter) {
    if (filter == null) {
      return;
    }
    if (filter.getTagRoles() != null && !filter.getTagRoles().isEmpty()) {
      filter.rebuildTagCohortsFromRegistry();
      return;
    }
    if (filter.getGroupRoles() == null || filter.getGroupRoles().isEmpty()) {
      return;
    }
    Map<Integer, Set<Integer>> byGroup = new HashMap<>();
    for (Integer id : filter.getGroupRoles().keySet()) {
      byGroup.put(id, new HashSet<>());
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    List<SampleTrack> tracks = registry.getSampleTracks();
    for (int i = 0; i < tracks.size(); i++) {
      SampleTrack track = tracks.get(i);
      if (track == null) {
        continue;
      }
      if (!track.hasGroup()) {
        Set<Integer> ungrouped = byGroup.get(VariantFilter.UNGROUPED_COHORT_ID);
        if (ungrouped != null) {
          ungrouped.add(i);
        }
        continue;
      }
      for (int cohortId : track.getGroupIds()) {
        Set<Integer> indices = byGroup.get(cohortId);
        if (indices != null) {
          indices.add(i);
        }
      }
    }
    filter.setGroupTrackIndices(byGroup);
    Map<Integer, Integer> scopes = new HashMap<>();
    for (Integer groupId : byGroup.keySet()) {
      if (groupId != null) {
        scopes.put(groupId, groupId);
      }
    }
    filter.setGroupLineageScope(scopes);
  }

  private static VariantFilter restoreClassFilter(ProjectDocument.ClassFilterSpec spec) {
    VariantFilter filter = new VariantFilter();
    if (spec == null) {
      filter.setAllowedTypes(EnumSet.noneOf(VcfVariantType.class));
      return filter;
    }
    filter.setMinQuality(spec.minQuality);
    filter.setMinDepth(spec.minDepth);
    filter.setMinAlleleFraction(spec.minAlleleFraction);
    filter.setMaxAlleleFraction(spec.maxAlleleFraction);
    filter.setCancerGenesOnly(spec.cancerGenesOnly);
    filter.setMinSvLengthBp(spec.minSvLengthBp);
    filter.setMaxSvLengthBp(spec.maxSvLengthBp > 0 ? spec.maxSvLengthBp : Long.MAX_VALUE);
    filter.setMinSharedSamples(spec.minSharedSamples > 0 ? spec.minSharedSamples : 1);
    filter.setMaxSharedSamples(spec.maxSharedSamples > 0 ? spec.maxSharedSamples : Integer.MAX_VALUE);
    filter.setGeneLevel(spec.geneLevel);
    filter.setComparisonWindowBp(Math.max(0, spec.comparisonWindowBp));
    restoreComparisonRoles(filter, spec);
    EnumSet<VcfVariantType> types = EnumSet.noneOf(VcfVariantType.class);
    if (spec.allowedTypes != null) {
      for (String name : spec.allowedTypes) {
        try {
          types.add(VcfVariantType.valueOf(name));
        } catch (Exception ignored) { /* skip */ }
      }
    }
    filter.setAllowedTypes(types);
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

  private static void restoreSessionAvailableFilters(ProjectDocument.VariantFilterSpec spec) {
    EnumSet<VcfVariantType> types = EnumSet.noneOf(VcfVariantType.class);
    EnumSet<VariantEffect> effects = EnumSet.noneOf(VariantEffect.class);
    if (spec != null) {
      if (spec.availableTypes != null) {
        for (String name : spec.availableTypes) {
          try {
            types.add(VcfVariantType.valueOf(name));
          } catch (Exception ignored) { /* skip */ }
        }
      }
      if (spec.availableEffects != null) {
        for (String name : spec.availableEffects) {
          try {
            effects.add(VariantEffect.valueOf(name));
          } catch (Exception ignored) { /* skip */ }
        }
      }
    }
    if (types.isEmpty() && effects.isEmpty()) {
      VcfManager.getInstance().clearSessionAvailableFilters();
    } else {
      VcfManager.getInstance().setSessionAvailableFilters(types, effects);
    }
  }
}
