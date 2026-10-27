package org.baseplayer.project;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.MainApp;
import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BigWigTrack;
import org.baseplayer.features.DefaultFeatureTracks;
import org.baseplayer.features.MotifTrack;
import org.baseplayer.features.Track;
import org.baseplayer.genome.ReferenceGenome;
import org.baseplayer.io.Settings;
import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.FeatureTrackViewportRegistry;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.VariantEffect;

import javafx.scene.paint.Color;

/**
 * Keeps the live {@link ProjectDocument} in sync with runtime services.
 * Targeted writers update the document immediately on mutation; {@link #syncAll}
 * refreshes remaining volatile fields (viewports, paths, stacks) before save.
 */
public final class SessionDocumentSync {

  private SessionDocumentSync() {}

  public static ProjectDocument live() {
    return ProjectSessionState.get().getDocument();
  }

  /**
   * Refresh the live document from runtime so a deep-copy save is complete.
   * Call on the FX thread before snapshotting.
   */
  public static void syncAll(Path projectFile) {
    ProjectDocument doc = live();
    ProjectSessionState session = ProjectSessionState.get();
    doc.name = session.getName();
    if ((doc.name == null || doc.name.isBlank() || "Untitled".equals(doc.name))
        && projectFile != null && projectFile.getFileName() != null) {
      String fn = projectFile.getFileName().toString();
      int dot = fn.lastIndexOf('.');
      doc.name = dot > 0 ? fn.substring(0, dot) : fn;
    }

    ServiceRegistry services = ServiceRegistry.getInstance();
    SampleRegistry samples = services.getSampleRegistry();
    DrawStackManager stacks = services.getDrawStackManager();

    ReferenceGenome genome = services.getReferenceGenomeService().getCurrentGenome();
    if (doc.genome == null) {
      doc.genome = new ProjectDocument.GenomeSpec();
    }
    if (genome != null) {
      doc.genome.id = genome.getName();
    } else {
      doc.genome.id = Settings.get().getLastGenome();
    }
    doc.genome.annotation = Settings.get().getLastAnnotation();

    // User draw settings stay in Settings / java.util.prefs — not in the project.
    // Keep an empty map so older readers see a stable field; do not snapshot prefs here.
    doc.settings = new java.util.LinkedHashMap<>();
    if (doc.ui == null) {
      doc.ui = new ProjectDocument.UiSpec();
    }
    doc.ui.darkMode = MainApp.darkMode;
    if (!stacks.getStacks().isEmpty() && stacks.getStacks().get(0).chromosomeCanvas != null) {
      doc.ui.maneOnly = stacks.getStacks().get(0).chromosomeCanvas.isShowManeOnly();
    }
    MainController main = MainController.get();
    if (main != null) {
      main.captureDividerPositions(doc.ui);
    }

    writeVariantFilterFromRuntime(VcfManager.getInstance());
    writeControlFilesFromRuntime();
    writeSampleGroupsFromRegistry();
    writeSampleTracksFromRuntime(projectFile);
    writeFeatureTracksFromRuntime(projectFile);
    writeStacksFromRuntime();
    writeViewportsFromRuntime();

    if (doc.sampleFilter == null) {
      doc.sampleFilter = new ProjectDocument.SampleFilterSpec();
    }
    doc.sampleFilter.query = samples.getActiveSampleFilterQuery();
    doc.sampleFilter.focusedGene = samples.getFocusedGeneName();
  }

  /** Write sample-group definitions + membership from {@link SampleRegistry}. */
  public static void writeSampleGroupsFromRegistry() {
    ProjectDocument doc = live();
    SampleRegistry samples = ServiceRegistry.getInstance().getSampleRegistry();
    doc.sampleGroups = new ArrayList<>();
    for (SampleGroup group : samples.getSampleGroups()) {
      if (group == null) {
        continue;
      }
      ProjectDocument.SampleGroupSpec groupSpec = new ProjectDocument.SampleGroupSpec();
      groupSpec.id = group.getId();
      groupSpec.name = group.getName();
      groupSpec.color = group.toCssHex();
      groupSpec.parentGroupId = -1;
      doc.sampleGroups.add(groupSpec);
    }
    // Membership + tags are stored on track specs — refresh if tracks already present.
    if (doc.sampleTracks != null && !doc.sampleTracks.isEmpty()) {
      List<SampleTrack> tracks = samples.getSampleTracks();
      int n = Math.min(tracks.size(), doc.sampleTracks.size());
      for (int i = 0; i < n; i++) {
        SampleTrack track = tracks.get(i);
        ProjectDocument.SampleTrackSpec spec = doc.sampleTracks.get(i);
        if (track != null && spec != null) {
          spec.groupIds = new ArrayList<>(track.getGroupIds());
          spec.tags = writeTrackTags(track);
        }
      }
    }
  }

  private static List<String> writeTrackTags(SampleTrack track) {
    List<String> out = new ArrayList<>();
    if (track == null) {
      return out;
    }
    for (org.baseplayer.samples.SampleTag tag : track.getTags()) {
      out.add(tag.name());
    }
    return out;
  }

  /** Persist {@link VariantFilter} into the live document filter specs. */
  public static void writeVariantFilterFromRuntime(VcfManager vcfManager) {
    ProjectDocument doc = live();
    doc.variantFilter = captureFilter(vcfManager);
  }

  /**
   * Persist Control Files tab state (control VCF path + pop-freq toggles) from the
   * open Variant Manager when present, then keep {@link ProjectDocument.ClassFilterSpec}
   * control-AF fields aligned (they are what restore loads into the filter UI).
   */
  public static void writeControlFilesFromRuntime() {
    ProjectDocument doc = live();
    if (doc.controlFiles == null) {
      doc.controlFiles = new ProjectDocument.ControlFilesSpec();
    }
    org.baseplayer.variant.ui.VariantManagerController controller =
        org.baseplayer.variant.ui.VariantManagerWindow.getCurrentController();
    if (controller != null) {
      controller.writeControlFilesToDocument(doc.controlFiles);
    }
    // ClassFilterSpec is restored into the Variant Manager; keep it in lockstep with
    // ControlFilesSpec so a deferred filter-apply cannot leave max AF unsaved.
    syncControlAfIntoVariantFilter(doc);
  }

  /**
   * Live-write Control Files pop-freq toggles into the project document (SSOT).
   * Call from UI listeners so save does not depend on filter-apply debounce.
   */
  public static void writeControlFilterSettings(boolean filterByControlAf, double maxControlAf) {
    writeControlFilterSettings(filterByControlAf, maxControlAf, null);
  }

  public static void writeControlFilterSettings(
      boolean filterByControlAf, double maxControlAf, String controlPopulation) {
    ProjectDocument doc = live();
    if (doc.controlFiles == null) {
      doc.controlFiles = new ProjectDocument.ControlFilesSpec();
    }
    doc.controlFiles.filterByControlAf = filterByControlAf;
    doc.controlFiles.maxControlAf = maxControlAf < 0 ? 0.01 : Math.min(1.0, maxControlAf);
    if (controlPopulation != null) {
      doc.controlFiles.controlPopulation = controlPopulation.isBlank() ? null : controlPopulation;
    }
    syncControlAfIntoVariantFilter(doc);
    ProjectSessionState.get().markDirty();
  }

  /** Live-write SV Control Files AF + breakpoint-match settings. */
  public static void writeSvControlFilterSettings(
      boolean filterByControlAf,
      double maxControlAf,
      String controlPopulation,
      int breakpointWindowBp,
      boolean requireSameType) {
    ProjectDocument doc = live();
    if (doc.controlFiles == null) {
      doc.controlFiles = new ProjectDocument.ControlFilesSpec();
    }
    doc.controlFiles.svFilterByControlAf = filterByControlAf;
    doc.controlFiles.svMaxControlAf = maxControlAf < 0 ? 0.01 : Math.min(1.0, maxControlAf);
    if (controlPopulation != null) {
      doc.controlFiles.svControlPopulation =
          controlPopulation.isBlank() ? null : controlPopulation;
    }
    doc.controlFiles.svBreakpointWindowBp = Math.max(0, breakpointWindowBp);
    doc.controlFiles.svRequireSameType = requireSameType;
    syncControlAfIntoVariantFilter(doc);
    ProjectSessionState.get().markDirty();
  }

  /** Live-write the open control VCF path into the project document. */
  public static void writeControlVcfPath(String controlVcfPath) {
    ProjectDocument doc = live();
    if (doc.controlFiles == null) {
      doc.controlFiles = new ProjectDocument.ControlFilesSpec();
    }
    doc.controlFiles.controlVcfPath = controlVcfPath;
    ProjectSessionState.get().markDirty();
  }

  public static void writeSvControlVcfPath(String svControlVcfPath) {
    ProjectDocument doc = live();
    if (doc.controlFiles == null) {
      doc.controlFiles = new ProjectDocument.ControlFilesSpec();
    }
    doc.controlFiles.svControlVcfPath = svControlVcfPath;
    ProjectSessionState.get().markDirty();
  }

  public static void writeControlPopulation(String controlPopulation) {
    ProjectDocument doc = live();
    if (doc.controlFiles == null) {
      doc.controlFiles = new ProjectDocument.ControlFilesSpec();
    }
    doc.controlFiles.controlPopulation =
        controlPopulation == null || controlPopulation.isBlank() ? null : controlPopulation;
    ProjectSessionState.get().markDirty();
  }

  public static void writeSvControlPopulation(String svControlPopulation) {
    ProjectDocument doc = live();
    if (doc.controlFiles == null) {
      doc.controlFiles = new ProjectDocument.ControlFilesSpec();
    }
    doc.controlFiles.svControlPopulation =
        svControlPopulation == null || svControlPopulation.isBlank() ? null : svControlPopulation;
    ProjectSessionState.get().markDirty();
  }

  /** Live-write the open ClinVar VCF path into the project document. */
  public static void writeClinVarVcfPath(String clinVarVcfPath) {
    ProjectDocument doc = live();
    if (doc.controlFiles == null) {
      doc.controlFiles = new ProjectDocument.ControlFilesSpec();
    }
    doc.controlFiles.clinVarVcfPath = clinVarVcfPath;
    ProjectSessionState.get().markDirty();
  }

  public static void writeClinVarFilterSettings(
      boolean filterByClinVarSignificance, String clinVarSignificanceClasses) {
    ProjectDocument doc = live();
    if (doc.controlFiles == null) {
      doc.controlFiles = new ProjectDocument.ControlFilesSpec();
    }
    doc.controlFiles.filterByClinVarSignificance = filterByClinVarSignificance;
    doc.controlFiles.clinVarSignificanceClasses =
        clinVarSignificanceClasses == null || clinVarSignificanceClasses.isBlank()
            ? null
            : clinVarSignificanceClasses;
    LegacyClinVarCompat.writeControlCompat(
        doc.controlFiles,
        filterByClinVarSignificance,
        doc.controlFiles.clinVarSignificanceClasses);
    syncControlAfIntoVariantFilter(doc);
    ProjectSessionState.get().markDirty();
  }

  /**
   * Copy control-AF / ClinVar toggles from {@link ProjectDocument#controlFiles} into point/SV
   * filter specs when control settings look intentional (path, filter on, or non-default max).
   * Point and SV AF thresholds are independent.
   */
  static void syncControlAfIntoVariantFilter(ProjectDocument doc) {
    if (doc == null || doc.controlFiles == null || !hasControlFilesSettings(doc.controlFiles)) {
      return;
    }
    if (doc.variantFilter == null) {
      doc.variantFilter = new ProjectDocument.VariantFilterSpec();
    }
    if (doc.variantFilter.point == null) {
      doc.variantFilter.point = new ProjectDocument.ClassFilterSpec();
    }
    if (doc.variantFilter.sv == null) {
      doc.variantFilter.sv = new ProjectDocument.ClassFilterSpec();
    }
    applyPointControlAf(doc.variantFilter.point, doc.controlFiles);
    applySvControlAf(doc.variantFilter.sv, doc.controlFiles);
    applyClinVarToClass(doc.variantFilter.point, doc.controlFiles);
    applyClinVarToClass(doc.variantFilter.sv, doc.controlFiles);
  }

  private static boolean hasControlFilesSettings(ProjectDocument.ControlFilesSpec spec) {
    if (spec == null) {
      return false;
    }
    if (spec.controlVcfPath != null && !spec.controlVcfPath.isBlank()) {
      return true;
    }
    if (spec.svControlVcfPath != null && !spec.svControlVcfPath.isBlank()) {
      return true;
    }
    if (spec.clinVarVcfPath != null && !spec.clinVarVcfPath.isBlank()) {
      return true;
    }
    if (spec.filterByControlAf
            || spec.svFilterByControlAf
            || spec.filterByClinVarSignificance
            || LegacyClinVarCompat.legacyRequire(spec)
            || LegacyClinVarCompat.legacyPathogenic(spec)) {
      return true;
    }
    // Default max is 0.01 — any other value means the user edited the field.
    return Math.abs(spec.maxControlAf - 0.01) > 1e-12
        || Math.abs(spec.svMaxControlAf - 0.01) > 1e-12
        || spec.svBreakpointWindowBp != 50
        || !spec.svRequireSameType;
  }

  private static void applyPointControlAf(
      ProjectDocument.ClassFilterSpec classSpec, ProjectDocument.ControlFilesSpec control) {
    if (classSpec == null || control == null) {
      return;
    }
    classSpec.filterByControlAf = control.filterByControlAf;
    classSpec.maxControlAf = control.maxControlAf < 0 ? 0.01 : Math.min(1.0, control.maxControlAf);
  }

  private static void applySvControlAf(
      ProjectDocument.ClassFilterSpec classSpec, ProjectDocument.ControlFilesSpec control) {
    if (classSpec == null || control == null) {
      return;
    }
    classSpec.filterByControlAf = control.svFilterByControlAf;
    classSpec.maxControlAf =
        control.svMaxControlAf < 0 ? 0.01 : Math.min(1.0, control.svMaxControlAf);
  }

  private static void applyClinVarToClass(
      ProjectDocument.ClassFilterSpec classSpec, ProjectDocument.ControlFilesSpec control) {
    if (classSpec == null || control == null) {
      return;
    }
    classSpec.filterByClinVarSignificance = control.filterByClinVarSignificance
        || LegacyClinVarCompat.legacyRequire(control)
        || LegacyClinVarCompat.legacyPathogenic(control);
    classSpec.clinVarSignificanceClasses = control.clinVarSignificanceClasses;
    if ((classSpec.clinVarSignificanceClasses == null
            || classSpec.clinVarSignificanceClasses.isBlank())
            && LegacyClinVarCompat.legacyPathogenic(control)) {
      classSpec.clinVarSignificanceClasses = "PATHOGENIC";
    } else if ((classSpec.clinVarSignificanceClasses == null
            || classSpec.clinVarSignificanceClasses.isBlank())
            && LegacyClinVarCompat.legacyRequire(control)) {
      classSpec.clinVarSignificanceClasses = "PATHOGENIC,VUS,BENIGN,OTHER";
    }
    LegacyClinVarCompat.writeClassCompat(classSpec);
  }

  public static void writeStacksFromRuntime() {
    ProjectDocument doc = live();
    DrawStackManager stacks = ServiceRegistry.getInstance().getDrawStackManager();
    doc.stacks = new ArrayList<>();
    for (DrawStack stack : stacks.getStacks()) {
      ProjectDocument.StackSpec spec = new ProjectDocument.StackSpec();
      spec.chromosome = stack.getChromosome();
      spec.viewStart = stack.getViewStart();
      spec.viewEnd = stack.getViewEnd();
      doc.stacks.add(spec);
    }
  }

  /** Update one stack entry after navigation (grows list if needed). */
  public static void writeStack(int index, String chromosome, double viewStart, double viewEnd) {
    if (index < 0) {
      return;
    }
    ProjectDocument doc = live();
    if (doc.stacks == null) {
      doc.stacks = new ArrayList<>();
    }
    while (doc.stacks.size() <= index) {
      doc.stacks.add(new ProjectDocument.StackSpec());
    }
    ProjectDocument.StackSpec spec = doc.stacks.get(index);
    if (spec == null) {
      spec = new ProjectDocument.StackSpec();
      doc.stacks.set(index, spec);
    }
    spec.chromosome = chromosome;
    spec.viewStart = viewStart;
    spec.viewEnd = viewEnd;
  }

  public static void writeViewportsFromRuntime() {
    ProjectDocument doc = live();
    SampleRegistry samples = ServiceRegistry.getInstance().getSampleRegistry();
    FeatureTrackViewportRegistry features =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
    doc.sampleViewport = captureViewport(
        samples.getFirstVisibleTrackSlot(),
        samples.getLastVisibleTrackSlot(),
        samples.getTrackRowHeightPixels(),
        samples.getVerticalScrollOffsetPixels(),
        samples.getMasterTrackHeight(),
        samples.isMasterAggregatesEnabled());
    doc.featureViewport = captureViewport(
        features.getFirstVisibleTrackSlot(),
        features.getLastVisibleTrackSlot(),
        features.getTrackRowHeightPixels(),
        features.getVerticalScrollOffsetPixels(),
        features.getMasterBandHeightPixels(),
        features.isMasterAggregatesEnabled());
  }

  public static void writeSampleTracksFromRuntime(Path projectFile) {
    ProjectDocument doc = live();
    SampleRegistry samples = ServiceRegistry.getInstance().getSampleRegistry();
    VcfManager vcfManager = VcfManager.getInstance();
    doc.sampleTracks = new ArrayList<>();
    List<SampleTrack> sampleTracks = samples.getSampleTracks();
    for (int trackIndex = 0; trackIndex < sampleTracks.size(); trackIndex++) {
      SampleTrack track = sampleTracks.get(trackIndex);
      ProjectDocument.SampleTrackSpec trackSpec = new ProjectDocument.SampleTrackSpec();
      trackSpec.displayName = track.getDisplayName();
      trackSpec.groupIds = new ArrayList<>(track.getGroupIds());
      trackSpec.tags = writeTrackTags(track);
      trackSpec.signalLayout = track.getSignalLayout() != null
          ? track.getSignalLayout().name()
          : SampleTrack.SignalLayout.OVERLAY.name();

      for (Sample sample : track.getSamples()) {
        if (sample.getDataType() == Sample.DataType.VCF && sample.getPath() != null) {
          ProjectDocument.SampleFileSpec vcfSpec =
              capturePathAsSampleFile("VCF", sample.getPath(), projectFile);
          vcfSpec.visible = sample.visible;
          vcfSpec.overlay = sample.overlay;
          trackSpec.samples.add(vcfSpec);
          continue;
        }
        if (sample.getBamFile() == null
            && sample.getBedTrack() == null
            && sample.getBigWigTrack() == null
            && sample.getSangerTrace() == null) {
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
  }

  public static void writeFeatureTracksFromRuntime(Path projectFile) {
    ProjectDocument doc = live();
    FeatureTrackViewportRegistry features =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
    doc.featureTracks = new ArrayList<>();
    for (Track track : features.getFeatureTracks()) {
      ProjectDocument.FeatureTrackSpec ft = captureFeatureTrack(track, projectFile);
      if (ft != null) {
        doc.featureTracks.add(ft);
      }
    }
  }

  public static void writeUiDarkMode(boolean darkMode) {
    ProjectDocument doc = live();
    if (doc.ui == null) {
      doc.ui = new ProjectDocument.UiSpec();
    }
    doc.ui.darkMode = darkMode;
  }

  public static void writeUiManeOnly(boolean maneOnly) {
    ProjectDocument doc = live();
    if (doc.ui == null) {
      doc.ui = new ProjectDocument.UiSpec();
    }
    doc.ui.maneOnly = maneOnly;
  }

  public static void writeGenome(String genomeId, String annotation) {
    ProjectDocument doc = live();
    if (doc.genome == null) {
      doc.genome = new ProjectDocument.GenomeSpec();
    }
    if (genomeId != null) {
      doc.genome.id = genomeId;
    }
    if (annotation != null) {
      doc.genome.annotation = annotation;
    }
  }

  public static void writeSampleFilter(String query, String focusedGene) {
    ProjectDocument doc = live();
    if (doc.sampleFilter == null) {
      doc.sampleFilter = new ProjectDocument.SampleFilterSpec();
    }
    if (query != null) {
      doc.sampleFilter.query = query;
    }
    doc.sampleFilter.focusedGene = focusedGene;
  }

  // ── private capture helpers (document field writers) ─────────────────────

  private static ProjectDocument.ViewportSpec captureViewport(
      int first, int last, double rowHeight, double scroll, double masterBand,
      boolean masterAggregatesEnabled) {
    ProjectDocument.ViewportSpec spec = new ProjectDocument.ViewportSpec();
    spec.first = first;
    spec.last = last;
    spec.rowHeight = rowHeight;
    spec.scroll = scroll;
    spec.masterBandHeight = masterBand;
    spec.masterAggregatesEnabled = masterAggregatesEnabled;
    return spec;
  }

  private static ProjectDocument.VariantFilterSpec captureFilter(VcfManager vcfManager) {
    VariantFilter filter = vcfManager != null ? vcfManager.getCurrentFilter() : null;
    ProjectDocument.VariantFilterSpec spec = new ProjectDocument.VariantFilterSpec();
    if (filter == null) {
      return spec;
    }

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
      copyComparisonRoles(point, svSpec);
      spec.sv = svSpec;
    }

    if (!org.baseplayer.variant.VariantTypeVisuals.hasClass(
            observedTypes, org.baseplayer.variant.VariantTypeVisuals.VariantClass.STRUCTURAL)
        && spec.sv != null) {
      spec.sv.allowedTypes.clear();
    }

    for (VcfVariantType t : observedTypes) {
      spec.availableTypes.add(t.name());
    }
    for (VariantEffect e : observedEffects) {
      spec.availableEffects.add(e.name());
    }
    return spec;
  }

  private static void copyComparisonRoles(VariantFilter filter, ProjectDocument.ClassFilterSpec spec) {
    if (filter == null || spec == null) {
      return;
    }
    if (filter.getPresentMatchMode() != null) {
      spec.presentMatchMode = filter.getPresentMatchMode().name();
    }
    if (filter.getTagRoles() != null && !filter.getTagRoles().isEmpty()) {
      for (Map.Entry<org.baseplayer.samples.SampleTag, VariantFilter.GroupRole> entry
          : filter.getTagRoles().entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null
            || entry.getValue() == VariantFilter.GroupRole.IGNORE) {
          continue;
        }
        ProjectDocument.GroupRoleSpec roleSpec = new ProjectDocument.GroupRoleSpec();
        roleSpec.tag = entry.getKey().name();
        roleSpec.role = entry.getValue().name();
        spec.groupRoles.add(roleSpec);
      }
      return;
    }
    // Legacy expanded cohort roles (pre-tag docs).
    if (filter.getGroupRoles() == null) {
      return;
    }
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

  private static ProjectDocument.ClassFilterSpec captureClassFilter(VariantFilter filter) {
    ProjectDocument.ClassFilterSpec spec = new ProjectDocument.ClassFilterSpec();
    if (filter == null) {
      return spec;
    }
    spec.minQuality = filter.getMinQuality();
    spec.minDepth = filter.getMinDepth();
    spec.minAlleleFraction = filter.getMinAlleleFraction();
    spec.maxAlleleFraction = filter.getMaxAlleleFraction();
    spec.cancerGenesOnly = filter.isCancerGenesOnly();
    spec.minSvLengthBp = filter.getMinSvLengthBp();
    spec.maxSvLengthBp = filter.getMaxSvLengthBp();
    spec.minSharedSamples = filter.getMinSharedSamples();
    spec.maxSharedSamples = filter.getMaxSharedSamples();
    spec.geneLevel = filter.isGeneLevel();
    spec.comparisonWindowBp = filter.getComparisonWindowBp();
    copyComparisonRoles(filter, spec);
    for (VcfVariantType t : filter.getAllowedTypes()) {
      if (t != null) {
        spec.allowedTypes.add(t.name());
      }
    }
    for (VariantEffect e : filter.getAllowedEffects()) {
      if (e != null) {
        spec.allowedEffects.add(e.name());
      }
    }
    if (filter.getInfoFieldFilters() != null) {
      spec.infoFieldFilters = new HashMap<>(filter.getInfoFieldFilters());
    }
    if (filter.getAllowedFilterValues() != null) {
      spec.allowedFilterValues = new ArrayList<>(filter.getAllowedFilterValues());
    }
    spec.filterByControlAf = filter.isFilterByControlAf();
    spec.maxControlAf = filter.getMaxControlAf();
    spec.filterByClinVarSignificance = filter.isFilterByClinVarSignificance();
    StringBuilder clinVarClasses = new StringBuilder();
    for (org.baseplayer.variant.annotation.ClinVarAnnotator.SignificanceClass c :
            filter.getAllowedClinVarClasses()) {
      if (c == null) {
        continue;
      }
      if (clinVarClasses.length() > 0) {
        clinVarClasses.append(',');
      }
      clinVarClasses.append(c.name());
    }
    spec.clinVarSignificanceClasses =
        clinVarClasses.length() == 0 ? null : clinVarClasses.toString();
    LegacyClinVarCompat.writeClassCompat(spec);
    return spec;
  }

  private static ProjectDocument.SampleFileSpec captureSampleFile(Sample sample, Path projectFile) {
    ProjectDocument.SampleFileSpec spec = new ProjectDocument.SampleFileSpec();
    if (sample.getBamFile() != null) {
      spec.type = "BAM";
    } else if (sample.getBedTrack() != null) {
      spec.type = "BED";
    } else if (sample.getBigWigTrack() != null) {
      spec.type = "BIGWIG";
    } else if (sample.getSangerTrace() != null) {
      spec.type = "AB1";
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
    if (track == null) {
      return null;
    }
    ProjectDocument.FeatureTrackSpec spec = new ProjectDocument.FeatureTrackSpec();
    spec.displayName = track.getName();
    spec.visible = track.isVisible();
    if (track.getColor() != null) {
      spec.color = colorToHex(track.getColor());
    }
    spec.min = track.getMinValue();
    spec.max = track.getMaxValue();

    if (track instanceof BedTrack bedTrack) {
      // Derived (set-op) tracks are runtime-only — no source path to persist.
      if (bedTrack.isDerived() || bedTrack.getSourcePath() == null) {
        return null;
      }
      spec.kind = "bed";
      Path path = track.getSourcePath();
      Path abs = path.toAbsolutePath().normalize();
      spec.path = PathResolver.toAbsoluteString(abs);
      String relative = PathResolver.toRelativeString(abs, projectFile);
      if (relative != null) {
        spec.pathRelative = relative;
      }
      spec.variantAnnotationMode = bedTrack.getVariantAnnotationMode().toPersisted();
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
    } else if (track instanceof MotifTrack motifTrack) {
      spec.kind = "jaspar";
      Path path = track.getSourcePath();
      if (path != null) {
        Path abs = path.toAbsolutePath().normalize();
        spec.path = PathResolver.toAbsoluteString(abs);
        String relative = PathResolver.toRelativeString(abs, projectFile);
        if (relative != null) {
          spec.pathRelative = relative;
        }
      }
      Set<String> ids = motifTrack.getSelectedMotifIds();
      if (ids != null && !ids.isEmpty()) {
        spec.motifIds = new ArrayList<>(ids);
      }
      spec.motifPvalue = motifTrack.getPvalue();
    } else if (track.getUcscTrackId() != null) {
      spec.kind = "ucsc";
      spec.ucscTrackId = track.getUcscTrackId();
    } else if (track.getName() != null && DefaultFeatureTracks.isGnomad(track)) {
      spec.kind = "gnomad";
    } else {
      spec.kind = "feature";
    }
    return spec;
  }

  private static String colorToHex(Color color) {
    int r = (int) Math.round(color.getRed() * 255);
    int g = (int) Math.round(color.getGreen() * 255);
    int b = (int) Math.round(color.getBlue() * 255);
    return String.format("#%02X%02X%02X", r, g, b);
  }
}
