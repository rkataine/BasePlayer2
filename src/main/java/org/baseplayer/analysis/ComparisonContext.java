package org.baseplayer.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.baseplayer.project.ProjectDocument;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.paint.Color;
import org.baseplayer.analysis.groupstat.GroupStatTest;
import org.baseplayer.analysis.signal.BigWigCompanionRule;
import org.baseplayer.analysis.signal.SignalSourceSpec;
import org.baseplayer.analysis.signal.SignalValueKind;

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
   * True when the registry has exactly one named group — ungrouped samples
   * participate as {@link #OTHER_GROUP_NAME}.
   */
  public static boolean usesAutoOther(SampleRegistry registry) {
    return registry != null && registry.getSampleGroups().size() == 1;
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

  /** Named groups plus virtual Other when applicable. */
  public static List<Integer> analysisGroupIds(SampleRegistry registry) {
    List<Integer> ids = new ArrayList<>();
    if (registry == null) {
      return ids;
    }
    for (SampleGroup group : registry.getSampleGroups()) {
      ids.add(group.getId());
    }
    if (usesAutoOther(registry)) {
      ids.add(OTHER_GROUP_ID);
    }
    return ids;
  }

  /** Refresh participating ids: all named groups, plus Other when only one exists. */
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
    out.addAll(registry.getSampleGroups());
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
      if (track != null && !track.hasGroup()) {
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
  }

  private static void writeSignalSettings(ProjectDocument.AnalysisComparisonSpec spec) {
    SignalSourceSpec signal = AnalysisSession.get().getSignalSpec();
    DifferenceThresholds thr = AnalysisSession.get().getThresholds();
    spec.signalValueKind = signal.getValueKind().name();
    spec.comparisonUnit = signal.getUnit().name();
    spec.genomicScope = signal.getScope().name();
    spec.valueAggregation = signal.getAggregation().name();
    spec.bigWigCompanionRule = signal.getBigWigRule().name();
    spec.bigWigNameContains = signal.getBigWigNameContains();
    spec.windowBp = signal.getWindowBp();
    spec.requireBedScores = signal.isRequireBedScores();
    spec.thresholdsEnabled = thr.isEnabled();
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
