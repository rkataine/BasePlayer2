package org.baseplayer.analysis;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.baseplayer.services.SampleRegistry;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;

/**
 * Holds last analysis results per mode for tables and aggregate canvas sync.
 */
public final class AnalysisSession {

  private static final AnalysisSession INSTANCE = new AnalysisSession();

  private final Map<AnalysisMode, GroupStatResult> results = new EnumMap<>(AnalysisMode.class);
  private final Map<AnalysisMode, GroupStatResult> filteredResults = new EnumMap<>(AnalysisMode.class);
  private final Map<AnalysisMode, FeatureValueMatrix> matrices = new EnumMap<>(AnalysisMode.class);
  private final IntegerProperty generation = new SimpleIntegerProperty(0);
  private final BooleanProperty showOnAggregate = new SimpleBooleanProperty(true);
  /** Group ids hidden from aggregate stack rows (clickable row labels). */
  private final Set<Integer> hiddenAggregateGroupIds = new HashSet<>();
  private AnalysisMode activeCanvasMode = AnalysisMode.SIGNAL;
  private DifferenceThresholds thresholds = new DifferenceThresholds();
  private GroupStatTest groupStatTest = GroupStatTest.AUTO;
  private SignalSourceSpec signalSpec = new SignalSourceSpec();
  private final CopyOnWriteArrayList<Consumer<AnalysisSession>> listeners = new CopyOnWriteArrayList<>();

  private AnalysisSession() {}

  public static AnalysisSession get() {
    return INSTANCE;
  }

  public IntegerProperty generationProperty() {
    return generation;
  }

  public BooleanProperty showOnAggregateProperty() {
    return showOnAggregate;
  }

  public boolean isShowOnAggregate() {
    return showOnAggregate.get();
  }

  public void setShowOnAggregate(boolean show) {
    showOnAggregate.set(show);
    notifyListeners();
  }

  public synchronized boolean isAggregateGroupVisible(int groupId) {
    return !hiddenAggregateGroupIds.contains(groupId);
  }

  public synchronized Set<Integer> getHiddenAggregateGroupIds() {
    return Collections.unmodifiableSet(new HashSet<>(hiddenAggregateGroupIds));
  }

  public synchronized void setAggregateGroupVisible(int groupId, boolean visible) {
    if (visible) {
      if (!hiddenAggregateGroupIds.remove(groupId)) {
        return;
      }
    } else if (!hiddenAggregateGroupIds.add(groupId)) {
      return;
    }
    notifyListeners();
  }

  public synchronized void toggleAggregateGroupVisible(int groupId) {
    if (hiddenAggregateGroupIds.contains(groupId)) {
      hiddenAggregateGroupIds.remove(groupId);
    } else {
      hiddenAggregateGroupIds.add(groupId);
    }
    notifyListeners();
  }

  public AnalysisMode getActiveCanvasMode() {
    return activeCanvasMode;
  }

  public void setActiveCanvasMode(AnalysisMode mode) {
    if (mode != null) {
      this.activeCanvasMode = AnalysisMode.fromPersisted(mode.name());
      notifyListeners();
    }
  }

  public synchronized DifferenceThresholds getThresholds() {
    return thresholds.copy();
  }

  public synchronized void setThresholds(DifferenceThresholds thresholds) {
    this.thresholds = thresholds != null ? thresholds.copy() : new DifferenceThresholds();
    refilterAll();
    generation.set(generation.get() + 1);
    notifyListeners();
  }

  public synchronized SignalSourceSpec getSignalSpec() {
    return signalSpec;
  }

  public synchronized void setSignalSpec(SignalSourceSpec signalSpec) {
    if (signalSpec != null) {
      this.signalSpec = signalSpec;
    }
  }

  public synchronized GroupStatTest getGroupStatTest() {
    return groupStatTest;
  }

  public synchronized void setGroupStatTest(GroupStatTest test) {
    this.groupStatTest = test != null ? test : GroupStatTest.AUTO;
    notifyListeners();
  }

  /**
   * Recompute stats from the stored matrix for {@code mode} using the current test choice.
   * Returns null when no matrix is available.
   */
  public synchronized GroupStatResult recomputeFromMatrix(
      AnalysisMode mode,
      SampleRegistry registry,
      List<Integer> groupIds,
      Integer baselineGroupId,
      String scoreLabel) {
    FeatureValueMatrix matrix = matrices.get(mode);
    if (matrix == null || matrix.isEmpty() || registry == null || groupIds == null) {
      return null;
    }
    GroupStatResult result = GroupStatEngine.compute(
        mode, matrix, registry, groupIds, baselineGroupId, groupStatTest, scoreLabel);
    putResult(mode, result, matrix, thresholds);
    return getFilteredResult(mode);
  }

  public synchronized void putResult(
      AnalysisMode mode,
      GroupStatResult result,
      FeatureValueMatrix matrix,
      DifferenceThresholds appliedThresholds) {
    if (mode == null) {
      return;
    }
    if (appliedThresholds != null) {
      this.thresholds = appliedThresholds.copy();
    }
    if (result != null) {
      results.put(mode, result);
      filteredResults.put(mode, ThresholdFilter.apply(result, this.thresholds));
    } else {
      results.remove(mode);
      filteredResults.remove(mode);
    }
    if (matrix != null) {
      matrices.put(mode, matrix);
    } else {
      matrices.remove(mode);
    }
    activeCanvasMode = mode;
    generation.set(generation.get() + 1);
    notifyListeners();
  }

  /** Convenience when thresholds already stored on the session. */
  public synchronized void putResult(AnalysisMode mode, GroupStatResult result, FeatureValueMatrix matrix) {
    putResult(mode, result, matrix, thresholds);
  }

  public synchronized GroupStatResult getResult(AnalysisMode mode) {
    return results.get(mode);
  }

  /** Threshold-filtered view used by table (when filter on), canvas, and export. */
  public synchronized GroupStatResult getFilteredResult(AnalysisMode mode) {
    GroupStatResult filtered = filteredResults.get(mode);
    return filtered != null ? filtered : results.get(mode);
  }

  public synchronized FeatureValueMatrix getMatrix(AnalysisMode mode) {
    return matrices.get(mode);
  }

  public synchronized GroupStatResult getCanvasResult() {
    if (!showOnAggregate.get()) {
      return null;
    }
    return getFilteredResult(activeCanvasMode);
  }

  public void addListener(Consumer<AnalysisSession> listener) {
    if (listener != null) {
      listeners.add(listener);
    }
  }

  public void removeListener(Consumer<AnalysisSession> listener) {
    listeners.remove(listener);
  }

  public synchronized void clear() {
    results.clear();
    filteredResults.clear();
    matrices.clear();
    hiddenAggregateGroupIds.clear();
    generation.set(generation.get() + 1);
    notifyListeners();
  }

  private void refilterAll() {
    for (AnalysisMode mode : AnalysisMode.values()) {
      GroupStatResult raw = results.get(mode);
      if (raw != null) {
        filteredResults.put(mode, ThresholdFilter.apply(raw, thresholds));
      }
    }
  }

  private void notifyListeners() {
    for (Consumer<AnalysisSession> listener : listeners) {
      try {
        listener.accept(this);
      } catch (RuntimeException ignored) {
        // ignore
      }
    }
  }
}
