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
import java.util.Set;

import org.baseplayer.MainApp;
import org.baseplayer.annotation.AnnotationLoader;
import org.baseplayer.components.sidebars.GenomeSidebar;
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

    doc.variantFilter = captureFilter(VcfManager.getInstance());
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
    for (SampleGroup group : samples.getSampleGroups()) {
      if (group == null) {
        continue;
      }
      ProjectDocument.SampleGroupSpec groupSpec = new ProjectDocument.SampleGroupSpec();
      groupSpec.id = group.getId();
      groupSpec.name = group.getName();
      groupSpec.color = group.toCssHex();
      doc.sampleGroups.add(groupSpec);
    }

    List<SampleTrack> sampleTracks = samples.getSampleTracks();
    for (int trackIndex = 0; trackIndex < sampleTracks.size(); trackIndex++) {
      SampleTrack track = sampleTracks.get(trackIndex);
      ProjectDocument.SampleTrackSpec trackSpec = new ProjectDocument.SampleTrackSpec();
      trackSpec.displayName = track.getDisplayName();
      trackSpec.groupIds = new ArrayList<>(track.getGroupIds());

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

  private static ProjectDocument.VariantFilterSpec captureFilter(VcfManager vcfManager) {
    VariantFilter filter = vcfManager != null ? vcfManager.getCurrentFilter() : null;
    ProjectDocument.VariantFilterSpec spec = new ProjectDocument.VariantFilterSpec();
    if (filter == null) return spec;

    EnumSet<VcfVariantType> observedTypes = EnumSet.noneOf(VcfVariantType.class);
    EnumSet<VariantEffect> observedEffects = EnumSet.noneOf(VariantEffect.class);
    if (vcfManager != null) {
      observedTypes.addAll(vcfManager.getSessionAvailableTypes());
      observedEffects.addAll(vcfManager.getSessionAvailableEffects());
      for (VariantList list : vcfManager.snapshotVariantCache().values()) {
        if (list != null && !list.isEmpty()) {
          observedTypes.addAll(list.collectVariantTypes());
          observedEffects.addAll(list.collectVariantEffects());
        }
      }
    }

    VariantFilter point = filter.getPointSlice() != null ? filter.getPointSlice() : filter;
    VariantFilter sv = filter.getSvSlice() != null ? filter.getSvSlice() : null;
    spec.point = captureClassFilter(point);
    if (sv != null) {
      spec.sv = captureClassFilter(sv);
    } else {
      // Legacy single filter: split point types into the point slice; leave SV empty
      // when none were observed (do not invent a full structural inventory).
      ProjectDocument.ClassFilterSpec svSpec = new ProjectDocument.ClassFilterSpec();
      spec.point.allowedTypes.removeIf(name -> {
        try {
          return org.baseplayer.variant.VariantTypeVisuals.isStructural(
              org.baseplayer.variant.VcfVariantType.valueOf(name));
        } catch (Exception e) {
          return true;
        }
      });
      for (VcfVariantType t : observedTypes) {
        if (org.baseplayer.variant.VariantTypeVisuals.isStructural(t)) {
          svSpec.allowedTypes.add(t.name());
        }
      }
      svSpec.allowedEffects.clear();
      for (VariantEffect e : VariantEffect.values()) {
        svSpec.allowedEffects.add(e.name());
      }
      svSpec.minQuality = point.getMinQuality();
      svSpec.minSharedSamples = point.getMinSharedSamples();
      svSpec.maxSharedSamples = point.getMaxSharedSamples();
      svSpec.geneLevel = point.isGeneLevel();
      svSpec.comparisonWindowBp = point.getComparisonWindowBp();
      spec.sv = svSpec;
    }

    // Never persist pass-all expansion for a class that was never seen in data.
    if (!org.baseplayer.variant.VariantTypeVisuals.hasClass(
            observedTypes, org.baseplayer.variant.VariantTypeVisuals.VariantClass.STRUCTURAL)
        && spec.sv != null) {
      spec.sv.allowedTypes.clear();
    }

    // availableTypes / availableEffects: observed data only — never from allowedTypes.
    for (VcfVariantType t : observedTypes) {
      spec.availableTypes.add(t.name());
    }
    for (VariantEffect e : observedEffects) {
      spec.availableEffects.add(e.name());
    }
    return spec;
  }

  private static ProjectDocument.ClassFilterSpec captureClassFilter(VariantFilter filter) {
    ProjectDocument.ClassFilterSpec spec = new ProjectDocument.ClassFilterSpec();
    if (filter == null) {
      return spec;
    }
    spec.minQuality = filter.getMinQuality();
    spec.minDepth = filter.getMinDepth();
    spec.minAlleleFraction = filter.getMinAlleleFraction();
    spec.cancerGenesOnly = filter.isCancerGenesOnly();
    spec.minSvLengthBp = filter.getMinSvLengthBp();
    spec.maxSvLengthBp = filter.getMaxSvLengthBp();
    spec.minSharedSamples = filter.getMinSharedSamples();
    spec.maxSharedSamples = filter.getMaxSharedSamples();
    spec.geneLevel = filter.isGeneLevel();
    spec.comparisonWindowBp = filter.getComparisonWindowBp();
    if (filter.getPresentMatchMode() != null) {
      spec.presentMatchMode = filter.getPresentMatchMode().name();
    }
    if (filter.getGroupRoles() != null) {
      for (Map.Entry<Integer, VariantFilter.GroupRole> entry : filter.getGroupRoles().entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null
            || entry.getValue() == VariantFilter.GroupRole.IGNORE) {
          continue;
        }
        ProjectDocument.GroupRoleSpec roleSpec = new ProjectDocument.GroupRoleSpec();
        roleSpec.groupId = entry.getKey();
        roleSpec.role = entry.getValue().name();
        spec.groupRoles.add(roleSpec);
      }
    }
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
        // Legacy flat filters with empty/missing types meant pass-all for point loads.
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

      // SV load slice: only types explicitly present in the flat allowed list.
      // Empty is fine — ensureUnobservedClassSlicesPassAll expands at load time.
      // Do not invent a full structural inventory or seed from availableTypes.
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
    Map<Integer, VariantFilter.GroupRole> roles = new HashMap<>();
    if (spec.groupRoles != null) {
      for (ProjectDocument.GroupRoleSpec roleSpec : spec.groupRoles) {
        if (roleSpec == null || roleSpec.role == null || roleSpec.role.isBlank()) {
          continue;
        }
        try {
          VariantFilter.GroupRole role =
              VariantFilter.GroupRole.valueOf(roleSpec.role.trim().toUpperCase(Locale.ROOT));
          if (role != VariantFilter.GroupRole.IGNORE) {
            roles.put(roleSpec.groupId, role);
          }
        } catch (IllegalArgumentException ignored) {
          // skip unknown role names from older / future files
        }
      }
    }
    filter.setGroupRoles(roles);
  }

  /**
   * Rebuild cohort track-index maps from the live {@link SampleRegistry} so restored
   * group roles work before the user re-applies Sample Comparison.
   */
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
    if (filter == null || filter.getGroupRoles() == null || filter.getGroupRoles().isEmpty()) {
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
  }

  private static VariantFilter restoreClassFilter(ProjectDocument.ClassFilterSpec spec) {
    VariantFilter filter = new VariantFilter();
    if (spec == null) {
      // Unobserved / missing class slice: empty allowedTypes (load expands).
      filter.setAllowedTypes(EnumSet.noneOf(VcfVariantType.class));
      return filter;
    }
    filter.setMinQuality(spec.minQuality);
    filter.setMinDepth(spec.minDepth);
    filter.setMinAlleleFraction(spec.minAlleleFraction);
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
    // Explicit empty stays empty — do not leave the VariantFilter allOf default.
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

  /**
   * Restore session-available types/effects from the project file when present.
   * Otherwise leave empty — VCF streaming and variant-cache install fill them from data.
   * Never seeds from {@code allowedTypes} / class filter specs.
   */
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
    String annotation = document.genome != null ? document.genome.annotation : null;
    if (annotation != null && !annotation.isBlank()) {
      Settings.get().setLastAnnotation(annotation);
    }
    init.selectReferenceGenome(match);

    // Sidebar sync reloads cytobands/genes and updates combo boxes.
    // Fall back to a direct reload if the sidebar is not constructed yet.
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

  private static void seedDefaultFeatureTracks() {
    FeatureTrackViewportRegistry features =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
    features.addFeatureTrack(DefaultFeatureTracks.createPhyloP());
    features.addFeatureTrack(DefaultFeatureTracks.createGnomad());
  }

  private static void restoreSampleTracks(
      ProjectDocument document, Path projectFile, List<String> warnings) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    restoreSampleGroups(document, registry);
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
        applyTrackGroupMembership(track, trackSpec.groupIds, registry);
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

  private static void restoreSampleGroups(ProjectDocument document, SampleRegistry registry) {
    if (registry == null) {
      return;
    }
    List<SampleGroup> groups = new ArrayList<>();
    if (document != null && document.sampleGroups != null) {
      for (ProjectDocument.SampleGroupSpec spec : document.sampleGroups) {
        if (spec == null || spec.id < 0) {
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
        groups.add(new SampleGroup(spec.id, spec.name, color));
      }
    }
    registry.replaceSampleGroups(groups);
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
      if (groupId == null || groupId < 0 || registry.getSampleGroup(groupId) == null) {
        continue;
      }
      track.addGroupId(groupId);
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

    if (uniqueAbsolutePaths.isEmpty()) {
      return;
    }

    List<File> files = new ArrayList<>(uniqueAbsolutePaths.size());
    for (String absolute : uniqueAbsolutePaths) {
      files.add(Path.of(absolute).toFile());
    }
    // Same background task as track restore — no per-file "Opening VCF…" waits.
    SampleDataManager.loadVcfFilesForSessionRestore(files, warnings);
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

    // Tracks (and group membership) are already restored; resolve cohort indices here.
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
