package org.baseplayer.analysis;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.baseplayer.project.ProjectDocument;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTag;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.VariantFilter;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.paint.Color;
import org.baseplayer.analysis.groupstat.GroupStatTest;
import org.baseplayer.analysis.signal.BigWigCompanionRule;
import org.baseplayer.analysis.signal.SignalComparisonSettings;
import org.baseplayer.analysis.signal.SignalDataset;
import org.baseplayer.analysis.signal.SignalDatasetCoordinator;
import org.baseplayer.analysis.signal.SignalDatasetNaming;
import org.baseplayer.analysis.signal.SignalDatasetRegistry;
import org.baseplayer.analysis.signal.SignalEventDirection;
import org.baseplayer.analysis.signal.SignalSourceSpec;
import org.baseplayer.analysis.signal.SignalValueKind;
import org.baseplayer.analysis.signal.SignalValueThresholds;

/**
 * Shared cohort context for Analysis Manager modes.
 * Compares every named sample group. With exactly one named group, ungrouped
 * samples form a virtual {@link #OTHER_GROUP_NAME} cohort automatically.
 */
public final class ComparisonContext {

  /**
   * Synthetic analysis cohort: samples not in the sole named group.
   * Not persisted in Sample Groups UI.
   */
  public static final int OTHER_GROUP_ID = -2;
  public static final String OTHER_GROUP_NAME = "Other";

  private static final ComparisonContext INSTANCE = new ComparisonContext();

  private final LinkedHashSet<Integer> participatingGroupIds = new LinkedHashSet<>();
  private Integer baselineGroupId;
  private final IntegerProperty revision = new SimpleIntegerProperty(0);
  private final List<Consumer<ComparisonContext>> listeners = new CopyOnWriteArrayList<>();
  private ChangeListener<Number> groupsListener;
  private boolean bound;
  /**
   * Signal dataset rows / legacy flat block waiting for sample tracks to exist
   * ({@link #loadFromDocument} runs before tracks are restored).
   */
  private ProjectDocument.AnalysisComparisonSpec pendingSignalDatasetRestore;
  private boolean pendingLegacySignalDataset;

  private ComparisonContext() {}

  public static ComparisonContext get() {
    return INSTANCE;
  }

  public static boolean isOtherGroupId(int groupId) {
    return groupId == OTHER_GROUP_ID;
  }

  /** Bind to sample-group revisions once (safe to call repeatedly). */
  public void ensureBound() {
    if (bound) {
      return;
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    groupsListener = (obs, o, n) -> syncAllGroups(registry);
    registry.sampleGroupsRevisionProperty().addListener(groupsListener);
    bound = true;
    syncAllGroups(registry);
  }

  public IntegerProperty revisionProperty() {
    return revision;
  }

  public void addListener(Consumer<ComparisonContext> listener) {
    if (listener != null) {
      listeners.add(listener);
    }
  }

  public void removeListener(Consumer<ComparisonContext> listener) {
    listeners.remove(listener);
  }

  public synchronized List<Integer> getParticipatingGroupIds() {
    return List.copyOf(participatingGroupIds);
  }

  public synchronized Integer getBaselineGroupId() {
    return baselineGroupId;
  }

  /**
   * True when the registry has exactly one included named group — ungrouped
   * samples participate as {@link #OTHER_GROUP_NAME}.
   */
  public static boolean usesAutoOther(SampleRegistry registry) {
    return registry != null && registry.getIncludedSampleGroups().size() == 1;
  }

  public static SampleGroup otherGroup() {
    return new SampleGroup(OTHER_GROUP_ID, OTHER_GROUP_NAME, Color.web("#9aa0a6"));
  }

  /** Tracks for a named group id, or ungrouped tracks for {@link #OTHER_GROUP_ID}. */
  public static List<SampleTrack> tracksForGroup(SampleRegistry registry, int groupId) {
    if (registry == null) {
      return List.of();
    }
    if (isOtherGroupId(groupId)) {
      return ungroupedTracks(registry);
    }
    return registry.getTracksInGroup(groupId);
  }

  public static String groupName(SampleRegistry registry, int groupId) {
    if (isOtherGroupId(groupId)) {
      return OTHER_GROUP_NAME;
    }
    if (registry != null) {
      for (SampleGroup group : registry.getSampleGroups()) {
        if (group.getId() == groupId) {
          return group.getName();
        }
      }
    }
    return "Group " + groupId;
  }

  public static Color groupColor(SampleRegistry registry, int groupId) {
    if (isOtherGroupId(groupId)) {
      Color other = otherGroup().getColor();
      return other != null ? other : Color.web("#9aa0a6");
    }
    if (registry != null) {
      SampleGroup group = registry.getSampleGroup(groupId);
      if (group != null && group.getColor() != null) {
        return group.getColor();
      }
    }
    return Color.web("#888888");
  }

  /** Included named groups plus virtual Other when applicable. */
  public static List<Integer> analysisGroupIds(SampleRegistry registry) {
    List<Integer> ids = new ArrayList<>();
    if (registry == null) {
      return ids;
    }
    for (SampleGroup group : registry.getIncludedSampleGroups()) {
      ids.add(group.getId());
    }
    if (usesAutoOther(registry)) {
      ids.add(OTHER_GROUP_ID);
    }
    return ids;
  }

  /** Refresh participating ids: included named groups, plus Other when only one exists. */
  public void syncAllGroups(SampleRegistry registry) {
    synchronized (this) {
      participatingGroupIds.clear();
      participatingGroupIds.addAll(analysisGroupIds(registry));
      baselineGroupId = participatingGroupIds.isEmpty()
          ? null
          : participatingGroupIds.iterator().next();
    }
    persist();
    bump();
  }

  public List<SampleGroup> resolveGroups(SampleRegistry registry) {
    List<SampleGroup> out = new ArrayList<>();
    if (registry == null) {
      return out;
    }
    out.addAll(registry.getIncludedSampleGroups());
    if (usesAutoOther(registry)) {
      out.add(otherGroup());
    }
    return out;
  }

  public List<SampleTrack> resolveTracks(SampleRegistry registry) {
    List<SampleTrack> collected = new ArrayList<>();
    if (registry == null) {
      return collected;
    }
    for (int groupId : analysisGroupIds(registry)) {
      for (SampleTrack track : tracksForGroup(registry, groupId)) {
        if (!containsIdentity(collected, track)) {
          collected.add(track);
        }
      }
    }
    return collected;
  }

  public String describe(SampleRegistry registry) {
    List<Integer> ids = analysisGroupIds(registry);
    if (ids.isEmpty()) {
      return "No sample groups — create a group in Sample Groups (ungrouped samples become Other)";
    }
    StringBuilder sb = new StringBuilder("Comparing ");
    for (int i = 0; i < ids.size(); i++) {
      if (i > 0) {
        sb.append(i == ids.size() - 1 ? " and " : ", ");
      }
      int id = ids.get(i);
      int n = tracksForGroup(registry, id).size();
      sb.append(groupName(registry, id)).append(" (n=").append(n).append(')');
      if (baselineGroupId != null && baselineGroupId == id) {
        sb.append(" [baseline]");
      }
    }
    return sb.toString();
  }

  public String runBlockReason(SampleRegistry registry) {
    if (registry == null) {
      return "Sample registry unavailable";
    }
    List<Integer> ids = analysisGroupIds(registry);
    if (ids.size() < 2) {
      return "Need ≥1 sample group with some samples left ungrouped (Other), or ≥2 named groups";
    }
    int withSamples = 0;
    for (int id : ids) {
      if (!tracksForGroup(registry, id).isEmpty()) {
        withSamples++;
      }
    }
    if (withSamples < 2) {
      if (usesAutoOther(registry) && tracksForGroup(registry, OTHER_GROUP_ID).isEmpty()) {
        return "Leave some samples ungrouped to form Other, or add a second sample group";
      }
      return "Need samples in at least 2 cohorts";
    }
    return null;
  }

  private static List<SampleTrack> ungroupedTracks(SampleRegistry registry) {
    List<SampleTrack> out = new ArrayList<>();
    for (SampleTrack track : registry.getSampleTracks()) {
      if (track != null && !track.hasGroup() && registry.isTrackIncludedInProject(track)) {
        out.add(track);
      }
    }
    return out;
  }

  public void loadFromDocument(ProjectDocument doc) {
    if (doc == null || doc.analysisComparison == null) {
      SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
      syncAllGroups(registry);
      return;
    }
    // Ignore persisted group include/baseline subsets — always use all registry groups.
    loadSignalSettings(doc.analysisComparison);
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    syncAllGroups(registry);
  }

  public void writeToDocument(ProjectDocument doc) {
    if (doc == null) {
      return;
    }
    if (doc.analysisComparison == null) {
      doc.analysisComparison = new ProjectDocument.AnalysisComparisonSpec();
    }
    synchronized (this) {
      // Persist mirror of current registry for diagnostics; not restored as a subset.
      doc.analysisComparison.participatingGroupIds = new ArrayList<>(participatingGroupIds);
      doc.analysisComparison.baselineGroupId = baselineGroupId;
    }
    writeSignalSettings(doc.analysisComparison);
  }

  private static void loadSignalSettings(ProjectDocument.AnalysisComparisonSpec spec) {
    ComparisonContext ctx = INSTANCE;
    if (spec.signalDatasets != null && !spec.signalDatasets.isEmpty()) {
      // Tracks are not restored yet — defer membership matching.
      ctx.pendingSignalDatasetRestore = spec;
      ctx.pendingLegacySignalDataset = false;
      loadFlatSignalSettings(spec);
      return;
    }
    // Legacy single-block projects.
    ctx.pendingSignalDatasetRestore = null;
    ctx.pendingLegacySignalDataset = true;
    AnalysisSession.get().setActiveSignalDatasetId(null);
    loadFlatSignalSettings(spec);
  }

  /**
   * Create named Signal datasets after sample tracks / matrices are restored.
   * Safe to call repeatedly; no-ops when nothing is pending.
   */
  public void restoreSignalDatasetsAfterTracks(ProjectDocument doc) {
    ProjectDocument.AnalysisComparisonSpec pending = pendingSignalDatasetRestore;
    boolean legacy = pendingLegacySignalDataset;
    pendingSignalDatasetRestore = null;
    pendingLegacySignalDataset = false;
    if (pending != null) {
      loadMultiDatasetSettings(pending);
    } else if (legacy) {
      ProjectDocument.AnalysisComparisonSpec spec = doc != null ? doc.analysisComparison : null;
      if (spec != null) {
        maybeRestoreLegacyDataset(spec);
      }
    }
    // Always cover orphans (e.g. matrices restored after legacy rematch, or
    // projects saved before named Signal datasets existed).
    SignalDatasetCoordinator.ensureDatasetsForOpenTracks();
  }

  private static void loadMultiDatasetSettings(ProjectDocument.AnalysisComparisonSpec spec) {
    SignalDatasetRegistry datasets = SignalDatasetRegistry.get();
    datasets.clear();
    AnalysisSession session = AnalysisSession.get();
    SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    List<SampleTrack> allTracks = sampleRegistry != null
        ? new ArrayList<>(sampleRegistry.getSampleTracks())
        : List.of();

    String firstId = null;
    for (ProjectDocument.SignalDatasetSpec row : spec.signalDatasets) {
      if (row == null || row.signalValueKind == null) {
        continue;
      }
      SignalValueKind kind;
      try {
        String kindName = row.signalValueKind;
        if ("ALLELE_CN_LOH".equals(kindName)) {
          kindName = SignalValueKind.TABLE_LOH.name();
        }
        kind = SignalValueKind.valueOf(kindName);
      } catch (IllegalArgumentException ex) {
        continue;
      }
      String name = row.displayName != null && !row.displayName.isBlank()
          ? row.displayName.trim()
          : legacyNameForKind(kind);
      SignalDataset dataset = new SignalDataset(
          row.id != null && !row.id.isBlank() ? row.id : null,
          name,
          kind);
      List<SampleTrack> matched = SignalDatasetRegistry.matchTracksByName(allTracks, row.trackNames);
      if (matched.isEmpty()) {
        // Soft fallback: any usable tracks of this kind not yet claimed.
        for (SampleTrack track : allTracks) {
          if (track != null && datasets.findByTrack(track) == null
              && trackMatchesKind(track, kind)) {
            matched.add(track);
          }
        }
      }
      if (matched.isEmpty()) {
        continue;
      }
      dataset.addTracks(matched);
      dataset.setSharedLoci(SignalDatasetNaming.sharedLociFromTracks(matched));
      datasets.register(dataset);
      session.ensureSignalSlice(dataset.getId());
      session.setActiveSignalDatasetId(dataset.getId());
      ProjectDocument.AnalysisComparisonSpec settings =
          row.settings != null ? row.settings : new ProjectDocument.AnalysisComparisonSpec();
      // Kind lives on the dataset row; copy into nested settings for flat loader.
      if (settings.signalValueKind == null) {
        settings.signalValueKind = kind.name();
      }
      loadFlatSignalSettings(settings);
      if (firstId == null) {
        firstId = dataset.getId();
      }
    }
    String active = spec.activeSignalDatasetId;
    if (active != null && datasets.get(active) != null) {
      session.setActiveSignalDatasetId(active);
    } else if (firstId != null) {
      session.setActiveSignalDatasetId(firstId);
    }
  }

  private static void maybeRestoreLegacyDataset(ProjectDocument.AnalysisComparisonSpec spec) {
    SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    if (sampleRegistry == null || sampleRegistry.getSampleTracks().isEmpty()) {
      return;
    }
    List<SampleTrack> tracks = new ArrayList<>(sampleRegistry.getSampleTracks());
    SignalValueKind kind = null;
    if (spec.signalValueKind != null) {
      try {
        String kindName = spec.signalValueKind;
        if ("ALLELE_CN_LOH".equals(kindName)) {
          kindName = SignalValueKind.TABLE_LOH.name();
        }
        kind = SignalValueKind.valueOf(kindName);
      } catch (IllegalArgumentException ignored) {
        // fall through
      }
    }
    if (kind == null) {
      kind = SignalDatasetNaming.inferKind(tracks);
    }
    List<SampleTrack> members = new ArrayList<>();
    for (SampleTrack track : tracks) {
      if (trackMatchesKind(track, kind)) {
        members.add(track);
      }
    }
    if (members.isEmpty()) {
      return;
    }
    String name = legacyNameForKind(kind);
    SignalDataset dataset = new SignalDataset(name, kind);
    dataset.addTracks(members);
    dataset.setSharedLoci(SignalDatasetNaming.sharedLociFromTracks(members));
    SignalDatasetRegistry.get().register(dataset);
    AnalysisSession.get().ensureSignalSlice(dataset.getId());
    AnalysisSession.get().setActiveSignalDatasetId(dataset.getId());
  }

  private static String legacyNameForKind(SignalValueKind kind) {
    if (kind == null) {
      return "Signal";
    }
    return switch (kind) {
      case TABLE_LOH -> "LOH";
      case BAM_METHYL -> "Methylation";
      case BED_SCORES -> "BED";
      case BED_ANY -> "Features";
      case TABLE_VALUES -> "RNA";
      case BIGWIG -> "Signal";
    };
  }

  private static boolean trackMatchesKind(SampleTrack track, SignalValueKind kind) {
    if (track == null || kind == null) {
      return false;
    }
    return switch (kind) {
      case TABLE_LOH ->
          org.baseplayer.analysis.source.AlleleCnLohValueSource.trackHasAlleleCn(track);
      case TABLE_VALUES ->
          org.baseplayer.analysis.source.MappedTableValueSource.trackHasValueTable(track)
              || org.baseplayer.analysis.source.MultiChannelMappedTableSource.trackHasPayload(track);
      case BED_SCORES ->
          org.baseplayer.analysis.source.BedScoreSource.trackHasScoredBed(track);
      case BED_ANY ->
          org.baseplayer.analysis.source.BedScoreSource.firstBed(track) != null;
      case BIGWIG ->
          org.baseplayer.analysis.source.BigWigValueSource.trackHasBigWig(track);
      case BAM_METHYL ->
          org.baseplayer.analysis.source.BamMethylSource.trackHasMethylBam(track);
    };
  }

  private static void loadFlatSignalSettings(ProjectDocument.AnalysisComparisonSpec spec) {
    SignalSourceSpec signal = AnalysisSession.get().getSignalSpec();
    if (spec.signalValueKind != null) {
      try {
        String kind = spec.signalValueKind;
        // Migrated away from Signal-tab CN importer → opened mapped tables.
        if ("ALLELE_CN_LOH".equals(kind)) {
          kind = SignalValueKind.TABLE_LOH.name();
        }
        signal.setValueKind(SignalValueKind.valueOf(kind));
      } catch (IllegalArgumentException ignored) {
        // keep default
      }
    }
    if (spec.comparisonUnit != null) {
      try {
        signal.setUnit(ComparisonUnit.valueOf(spec.comparisonUnit));
      } catch (IllegalArgumentException ignored) {
        // keep default
      }
    }
    if (spec.genomicScope != null) {
      try {
        signal.setScope(GenomicScope.valueOf(spec.genomicScope));
      } catch (IllegalArgumentException ignored) {
        // keep default
      }
    }
    if (spec.valueAggregation != null) {
      try {
        signal.setAggregation(ValueAggregation.valueOf(spec.valueAggregation));
      } catch (IllegalArgumentException ignored) {
        // keep default
      }
    }
    if (spec.bigWigCompanionRule != null) {
      try {
        signal.setBigWigRule(BigWigCompanionRule.valueOf(spec.bigWigCompanionRule));
      } catch (IllegalArgumentException ignored) {
        // keep default
      }
    }
    if (spec.bigWigNameContains != null) {
      signal.setBigWigNameContains(spec.bigWigNameContains);
    }
    if (spec.windowBp != null) {
      signal.setWindowBp(spec.windowBp);
    }
    if (spec.requireBedScores != null) {
      signal.setRequireBedScores(spec.requireBedScores);
    }
    DifferenceThresholds thr = new DifferenceThresholds();
    if (spec.thresholdsEnabled != null) {
      thr.setEnabled(spec.thresholdsEnabled);
    }
    if (spec.minAbsLog2Fc != null) {
      thr.setMinAbsLog2Fc(spec.minAbsLog2Fc);
    }
    if (spec.maxQ != null) {
      thr.setMaxQ(spec.maxQ);
    }
    if (spec.minNPerGroup != null) {
      thr.setMinNPerGroup(spec.minNPerGroup);
    }
    // Optional criteria: explicit flags when present; else legacy inference.
    if (spec.useMinAbsLog2Fc != null) {
      thr.setUseMinAbsLog2Fc(spec.useMinAbsLog2Fc);
    } else if (spec.minAbsLog2Fc != null) {
      thr.setUseMinAbsLog2Fc(spec.minAbsLog2Fc > 0);
    }
    if (spec.useMaxQ != null) {
      thr.setUseMaxQ(spec.useMaxQ);
    }
    if (spec.useMinNPerGroup != null) {
      thr.setUseMinNPerGroup(spec.useMinNPerGroup);
    } else if (spec.minNPerGroup != null) {
      thr.setUseMinNPerGroup(spec.minNPerGroup > 1);
    }
    if (spec.maxRows != null) {
      thr.setMaxRows(spec.maxRows);
    }
    if (spec.showUpDifferences != null) {
      thr.setShowUp(spec.showUpDifferences);
    }
    if (spec.showDownDifferences != null) {
      thr.setShowDown(spec.showDownDifferences);
    }
    if (spec.showSimilarDifferences != null) {
      thr.setShowSimilar(spec.showSimilarDifferences);
    }
    AnalysisSession.get().setThresholds(thr);
    if (spec.showOnAggregate != null) {
      AnalysisSession.get().setShowOnAggregate(spec.showOnAggregate);
    }
    if (spec.groupStatTest != null) {
      AnalysisSession.get().setGroupStatTest(GroupStatTest.fromPersisted(spec.groupStatTest));
    }
    if (spec.effectMode != null) {
      AnalysisSession.get().setEffectMode(EffectMode.fromPersisted(spec.effectMode), true);
    }
    if (spec.minMeanPrefilter != null) {
      AnalysisSession.get().setMinMeanPrefilter(spec.minMeanPrefilter);
    }
    loadValueThresholds(spec);
    loadSignalComparison(spec);
    loadAllowedEventDirections(spec);
  }

  private static void loadAllowedEventDirections(ProjectDocument.AnalysisComparisonSpec spec) {
    if (spec.signalAllowedEventDirections == null || spec.signalAllowedEventDirections.isEmpty()) {
      return;
    }
    EnumSet<SignalEventDirection> dirs = EnumSet.noneOf(SignalEventDirection.class);
    for (String name : spec.signalAllowedEventDirections) {
      if (name == null || name.isBlank()) {
        continue;
      }
      try {
        SignalEventDirection d = SignalEventDirection.valueOf(name.trim());
        if (d != SignalEventDirection.NONE) {
          dirs.add(d);
        }
      } catch (IllegalArgumentException ignored) {
        // skip unknown
      }
    }
    SignalSourceSpec signal = AnalysisSession.get().getSignalSpec();
    signal.setAllowedEventDirections(dirs);
  }

  private static void loadValueThresholds(ProjectDocument.AnalysisComparisonSpec spec) {
    if (spec.valueThresholdsEnabled == null
        && spec.valueMin == null
        && spec.valueMax == null
        && spec.valueMiddle == null
        && spec.valueMinAbsFromMiddle == null
        && spec.valueMaxRows == null
        && spec.valueLohOnly == null) {
      return;
    }
    SignalValueThresholds v = new SignalValueThresholds();
    if (spec.valueThresholdsEnabled != null) {
      v.setEnabled(spec.valueThresholdsEnabled);
    }
    if (spec.valueMin != null) {
      v.setMinValue(spec.valueMin);
    }
    if (spec.valueMax != null) {
      v.setMaxValue(spec.valueMax);
    }
    if (spec.valueMiddle != null) {
      v.setMiddle(spec.valueMiddle);
    }
    if (spec.valueMinAbsFromMiddle != null) {
      v.setMinAbsFromMiddle(spec.valueMinAbsFromMiddle);
    }
    if (spec.valueMaxRows != null) {
      v.setMaxRows(spec.valueMaxRows);
    }
    if (spec.valueLohOnly != null) {
      v.setLohOnly(spec.valueLohOnly);
    }
    AnalysisSession.get().setValueThresholds(v);
  }

  private static void loadSignalComparison(ProjectDocument.AnalysisComparisonSpec spec) {
    if (spec.signalMinSharedSamples == null
        && spec.signalMaxSharedSamples == null
        && spec.signalGeneLevel == null
        && spec.signalComparisonWindowBp == null
        && spec.signalPresentMatchMode == null
        && (spec.signalTagRoles == null || spec.signalTagRoles.isEmpty())) {
      return;
    }
    SignalComparisonSettings settings = new SignalComparisonSettings();
    if (spec.signalMinSharedSamples != null) {
      settings.setMinSharedSamples(spec.signalMinSharedSamples);
    }
    if (spec.signalMaxSharedSamples != null) {
      settings.setMaxSharedSamples(spec.signalMaxSharedSamples);
    }
    if (spec.signalGeneLevel != null) {
      settings.setGeneLevel(spec.signalGeneLevel);
    }
    if (spec.signalComparisonWindowBp != null) {
      settings.setComparisonWindowBp(spec.signalComparisonWindowBp);
    }
    if (spec.signalPresentMatchMode != null) {
      try {
        settings.setPresentMatchMode(
            VariantFilter.PresentMatchMode.valueOf(spec.signalPresentMatchMode));
      } catch (IllegalArgumentException ignored) {
        // keep default
      }
    }
    if (spec.signalTagRoles != null && !spec.signalTagRoles.isEmpty()) {
      Map<SampleTag, VariantFilter.GroupRole> roles = new EnumMap<>(SampleTag.class);
      for (ProjectDocument.GroupRoleSpec entry : spec.signalTagRoles) {
        if (entry == null || entry.tag == null || entry.role == null) {
          continue;
        }
        try {
          SampleTag tag = SampleTag.valueOf(entry.tag);
          VariantFilter.GroupRole role = VariantFilter.GroupRole.valueOf(entry.role);
          if (role != VariantFilter.GroupRole.IGNORE) {
            roles.put(tag, role);
          }
        } catch (IllegalArgumentException ignored) {
          // skip unknown tag/role
        }
      }
      settings.setTagRoles(roles);
    }
    AnalysisSession.get().setSignalComparison(settings);
  }

  private static void writeSignalSettings(ProjectDocument.AnalysisComparisonSpec spec) {
    AnalysisSession session = AnalysisSession.get();
    String previousActive = session.getActiveSignalDatasetId();
    List<ProjectDocument.SignalDatasetSpec> rows = new ArrayList<>();
    for (SignalDataset dataset : SignalDatasetRegistry.get().all()) {
      if (dataset == null || dataset.isEmpty()) {
        continue;
      }
      session.setActiveSignalDatasetId(dataset.getId());
      ProjectDocument.SignalDatasetSpec row = new ProjectDocument.SignalDatasetSpec();
      row.id = dataset.getId();
      row.displayName = dataset.getDisplayName();
      row.signalValueKind = dataset.getKind().name();
      row.trackNames = new ArrayList<>(dataset.trackDisplayNames());
      row.settings = new ProjectDocument.AnalysisComparisonSpec();
      writeFlatSignalSettings(row.settings);
      row.settings.signalValueKind = dataset.getKind().name();
      rows.add(row);
    }
    if (previousActive != null) {
      session.setActiveSignalDatasetId(previousActive);
    } else if (!rows.isEmpty()) {
      session.setActiveSignalDatasetId(rows.get(0).id);
    }
    spec.signalDatasets = rows;
    spec.activeSignalDatasetId = session.getActiveSignalDatasetId();
    // Keep legacy flat fields as a mirror of the active dataset for older readers.
    writeFlatSignalSettings(spec);
  }

  private static void writeFlatSignalSettings(ProjectDocument.AnalysisComparisonSpec spec) {
    SignalSourceSpec signal = AnalysisSession.get().getSignalSpec();
    DifferenceThresholds thr = AnalysisSession.get().getThresholds();
    if (signal.getValueKind() != null) {
      spec.signalValueKind = signal.getValueKind().name();
    }
    if (signal.getUnit() != null) {
      spec.comparisonUnit = signal.getUnit().name();
    }
    if (signal.getScope() != null) {
      spec.genomicScope = signal.getScope().name();
    }
    if (signal.getAggregation() != null) {
      spec.valueAggregation = signal.getAggregation().name();
    }
    if (signal.getBigWigRule() != null) {
      spec.bigWigCompanionRule = signal.getBigWigRule().name();
    }
    spec.bigWigNameContains = signal.getBigWigNameContains();
    spec.windowBp = signal.getWindowBp();
    spec.requireBedScores = signal.isRequireBedScores();
    spec.thresholdsEnabled = thr.isEnabled();
    spec.useMinAbsLog2Fc = thr.isUseMinAbsLog2Fc();
    spec.useMaxQ = thr.isUseMaxQ();
    spec.useMinNPerGroup = thr.isUseMinNPerGroup();
    spec.minAbsLog2Fc = thr.getMinAbsLog2Fc();
    spec.maxQ = thr.getMaxQ();
    spec.minNPerGroup = thr.getMinNPerGroup();
    spec.maxRows = thr.getMaxRows();
    spec.showUpDifferences = thr.isShowUp();
    spec.showDownDifferences = thr.isShowDown();
    spec.showSimilarDifferences = thr.isShowSimilar();
    spec.showOnAggregate = AnalysisSession.get().isShowOnAggregate();
    GroupStatTest test = AnalysisSession.get().getGroupStatTest();
    spec.groupStatTest = test != null ? test.name() : GroupStatTest.AUTO.name();
    EffectMode effect = AnalysisSession.get().getEffectMode();
    spec.effectMode = effect != null ? effect.name() : EffectMode.AUTO.name();
    spec.minMeanPrefilter = AnalysisSession.get().getMinMeanPrefilter();
    writeValueThresholds(spec);
    writeSignalComparison(spec);
    writeAllowedEventDirections(spec);
  }

  private static void writeAllowedEventDirections(ProjectDocument.AnalysisComparisonSpec spec) {
    SignalSourceSpec signal = AnalysisSession.get().getSignalSpec();
    List<String> names = new ArrayList<>();
    for (SignalEventDirection d : SignalEventDirection.filterTypes()) {
      if (signal.allowsEventDirection(d)) {
        names.add(d.name());
      }
    }
    spec.signalAllowedEventDirections = names;
  }

  private static void writeValueThresholds(ProjectDocument.AnalysisComparisonSpec spec) {
    SignalValueThresholds v = AnalysisSession.get().getValueThresholds();
    spec.valueThresholdsEnabled = v.isEnabled();
    spec.valueMin = v.getMinValue();
    spec.valueMax = v.getMaxValue();
    spec.valueMiddle = v.getMiddle();
    spec.valueMinAbsFromMiddle = v.getMinAbsFromMiddle();
    spec.valueMaxRows = v.getMaxRows();
    spec.valueLohOnly = v.isLohOnly();
  }

  private static void writeSignalComparison(ProjectDocument.AnalysisComparisonSpec spec) {
    SignalComparisonSettings settings = AnalysisSession.get().getSignalComparison();
    spec.signalMinSharedSamples = settings.getMinSharedSamples();
    int maxShared = settings.getMaxSharedSamples();
    spec.signalMaxSharedSamples = maxShared == Integer.MAX_VALUE ? null : maxShared;
    spec.signalGeneLevel = settings.isGeneLevel();
    spec.signalComparisonWindowBp = settings.getComparisonWindowBp();
    VariantFilter.PresentMatchMode match = settings.getPresentMatchMode();
    spec.signalPresentMatchMode = match != null ? match.name() : null;
    List<ProjectDocument.GroupRoleSpec> roles = new ArrayList<>();
    for (Map.Entry<SampleTag, VariantFilter.GroupRole> e : settings.getTagRoles().entrySet()) {
      if (e.getKey() == null || e.getValue() == null) {
        continue;
      }
      ProjectDocument.GroupRoleSpec row = new ProjectDocument.GroupRoleSpec();
      row.tag = e.getKey().name();
      row.role = e.getValue().name();
      roles.add(row);
    }
    spec.signalTagRoles = roles;
  }

  private void persist() {
    ProjectDocument doc = ProjectSessionState.get().getDocument();
    if (doc != null) {
      writeToDocument(doc);
      ProjectSessionState.get().markDirty();
    }
  }

  private void bump() {
    revision.set(revision.get() + 1);
    for (Consumer<ComparisonContext> listener : listeners) {
      try {
        listener.accept(this);
      } catch (RuntimeException ignored) {
        // Keep other listeners running.
      }
    }
  }

  private static boolean containsIdentity(List<SampleTrack> list, SampleTrack track) {
    for (SampleTrack existing : list) {
      if (existing == track) {
        return true;
      }
    }
    return false;
  }
}
