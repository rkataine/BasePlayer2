package org.baseplayer.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import org.baseplayer.analysis.grid.CompactTableGrid;
import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.grid.ValueGrid;
import org.baseplayer.analysis.groupstat.GroupStatEngine;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.groupstat.GroupStatTest;
import org.baseplayer.analysis.pattern.SamplePatternEngine;
import org.baseplayer.analysis.signal.SignalAnnotator;
import org.baseplayer.analysis.signal.SignalChannel;
import org.baseplayer.analysis.signal.SignalComparisonFilter;
import org.baseplayer.analysis.signal.SignalComparisonSettings;
import org.baseplayer.analysis.signal.SignalScaleInfer;
import org.baseplayer.analysis.signal.SignalSourceSpec;
import org.baseplayer.analysis.signal.SignalValueThresholds;
import org.baseplayer.analysis.signal.SignalValueType;

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
  private SignalValueThresholds valueThresholds = new SignalValueThresholds();
  private GroupStatResult annotationResult;
  private GroupStatResult filteredAnnotationResult;
  private FeatureValueBundle signalBundle;
  /** Gene-matrix SSOT grid or sparse collect matrix for Signal annotate/compare. */
  private ValueGrid signalGrid;
  private SignalComparisonSettings signalComparison = new SignalComparisonSettings();
  private GroupStatTest groupStatTest = GroupStatTest.AUTO;
  private EffectMode effectMode = EffectMode.AUTO;
  private boolean effectModeUserOverride;
  private SignalValueType inferredValueType = SignalValueType.RAW;
  private String scaleDetectSummary = "";
  private double minMeanPrefilter;
  private SamplePatternEngine.Result lastPatternResult;
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

  public synchronized SignalValueThresholds getValueThresholds() {
    return valueThresholds.copy();
  }

  public synchronized void setValueThresholds(SignalValueThresholds valueThresholds) {
    this.valueThresholds = valueThresholds != null
        ? valueThresholds.copy()
        : new SignalValueThresholds();
    refilterAnnotation();
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

  public synchronized EffectMode getEffectMode() {
    return effectMode;
  }

  public synchronized void setEffectMode(EffectMode mode) {
    setEffectMode(mode, true);
  }

  public synchronized void setEffectMode(EffectMode mode, boolean userOverride) {
    this.effectMode = mode != null ? mode : EffectMode.AUTO;
    if (userOverride) {
      this.effectModeUserOverride = true;
    }
    notifyListeners();
  }

  public synchronized boolean isEffectModeUserOverride() {
    return effectModeUserOverride;
  }

  public synchronized SignalValueType getInferredValueType() {
    return inferredValueType;
  }

  public synchronized String getScaleDetectSummary() {
    return scaleDetectSummary != null ? scaleDetectSummary : "";
  }

  public synchronized double getMinMeanPrefilter() {
    return minMeanPrefilter;
  }

  public synchronized void setMinMeanPrefilter(double minMeanPrefilter) {
    this.minMeanPrefilter = Math.max(0.0, minMeanPrefilter);
    notifyListeners();
  }

  public synchronized SamplePatternEngine.Result getLastPatternResult() {
    return lastPatternResult;
  }

  public synchronized void setLastPatternResult(SamplePatternEngine.Result result) {
    this.lastPatternResult = result;
    notifyListeners();
  }

  /** Primary channel type for effect Auto; falls back to inferred type. */
  public synchronized SignalValueType resolveValueTypeForEffect() {
    if (signalSpec != null && signalSpec.hasChannels()) {
      List<SignalChannel> channels = signalSpec.getChannels();
      if (channels != null && !channels.isEmpty() && channels.get(0) != null) {
        return channels.get(0).type();
      }
    }
    return inferredValueType != null ? inferredValueType : SignalValueType.RAW;
  }

  /**
   * Infer scale from the current signal grid when the user has not overridden effect mode.
   */
  public synchronized void maybeInferScaleFromGrid() {
    ValueGrid grid = getSignalGrid();
    if (grid == null || grid.isEmpty()) {
      return;
    }
    SignalScaleInfer.Suggestion suggestion = SignalScaleInfer.inferFromGrid(grid);
    inferredValueType = suggestion.type();
    scaleDetectSummary = suggestion.summary();
    if (!effectModeUserOverride) {
      effectMode = suggestion.effectMode();
    }
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
      // Signal may still have a compact grid without a FeatureValueMatrix copy.
      if (mode != AnalysisMode.SIGNAL) {
        return null;
      }
    }
    ValueGrid grid = matrix;
    if (mode == AnalysisMode.SIGNAL) {
      grid = getSignalGridForGroupRun();
      if (grid == null || grid.isEmpty()) {
        return null;
      }
    } else if (grid == null || grid.isEmpty()) {
      return null;
    }
    GroupStatResult result = GroupStatEngine.compute(
        mode,
        grid,
        registry,
        groupIds,
        baselineGroupId,
        groupStatTest,
        scoreLabel,
        effectMode,
        resolveValueTypeForEffect(),
        minMeanPrefilter);
    FeatureValueMatrix store = grid instanceof FeatureValueMatrix fvm ? fvm : matrix;
    putResult(mode, result, store, thresholds);
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
      GroupStatResult afterThr = ThresholdFilter.apply(result, this.thresholds);
      // When filters are on (significant-only DE), keep a single slim copy —
      // do not retain the unfiltered row list in parallel. Gene-type visibility
      // is view-only (filteredResults) so legend toggles can restore rows.
      GroupStatResult view = applyGeneVisibility(mode, afterThr != null ? afterThr : result);
      if (this.thresholds != null && this.thresholds.isEnabled() && afterThr != null) {
        results.put(mode, afterThr);
        filteredResults.put(mode, view != null ? view : afterThr);
      } else {
        results.put(mode, result);
        filteredResults.put(mode, view != null ? view : result);
      }
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

  /**
   * Store a groupless annotation listing. Keeps the matrix for later group comparison
   * without applying {@link DifferenceThresholds}.
   */
  public synchronized void putAnnotation(
      AnalysisMode mode,
      GroupStatResult annotation,
      FeatureValueMatrix matrix,
      SignalValueThresholds applied) {
    putAnnotation(mode, annotation, matrix, applied, null);
  }

  public synchronized void putAnnotation(
      AnalysisMode mode,
      GroupStatResult annotation,
      FeatureValueMatrix matrix,
      SignalValueThresholds applied,
      FeatureValueBundle bundle) {
    putAnnotation(mode, annotation, (ValueGrid) matrix, applied, bundle);
  }

  public synchronized void putAnnotation(
      AnalysisMode mode,
      GroupStatResult annotation,
      ValueGrid grid,
      SignalValueThresholds applied,
      FeatureValueBundle bundle) {
    if (mode == null) {
      return;
    }
    if (applied != null) {
      this.valueThresholds = applied.copy();
    }
    this.signalGrid = grid;
    if (grid instanceof FeatureValueMatrix fvm) {
      matrices.put(mode, fvm);
    } else if (mode != null) {
      // Compact SSOT grid — no boxed FeatureValueMatrix copy.
      matrices.remove(mode);
    }
    this.signalBundle = bundle;
    // Filter changes invalidate prior group-comparison rows; user can re-Run.
    results.remove(mode);
    filteredResults.remove(mode);
    annotationResult = annotation;
    filteredAnnotationResult = applySignalSampleComparison(annotation);
    activeCanvasMode = mode;
    maybeInferScaleFromGrid();
    generation.set(generation.get() + 1);
    notifyListeners();
  }

  public synchronized FeatureValueBundle getSignalBundle() {
    return signalBundle;
  }

  /** Signal value grid (compact gene matrix or sparse collect). */
  public synchronized ValueGrid getSignalGrid() {
    if (signalGrid != null) {
      return signalGrid;
    }
    return matrices.get(AnalysisMode.SIGNAL);
  }

  /** Update SSOT grid after masking without clearing annotation (refilter separately). */
  public synchronized void replaceSignalGrid(ValueGrid grid) {
    this.signalGrid = grid;
    if (grid instanceof FeatureValueMatrix fvm) {
      matrices.put(AnalysisMode.SIGNAL, fvm);
    } else {
      matrices.remove(AnalysisMode.SIGNAL);
    }
  }

  public synchronized SignalComparisonSettings getSignalComparison() {
    return signalComparison.copy();
  }

  public synchronized void setSignalComparison(SignalComparisonSettings settings) {
    this.signalComparison = settings != null ? settings.copy() : new SignalComparisonSettings();
    filteredAnnotationResult = applySignalSampleComparison(annotationResult);
    // Group results must be re-Run; clear stale comparison rows for Signal.
    results.remove(AnalysisMode.SIGNAL);
    filteredResults.remove(AnalysisMode.SIGNAL);
    generation.set(generation.get() + 1);
    notifyListeners();
  }

  /** Matrix for group Run with Sample Comparison units removed. */
  public synchronized FeatureValueMatrix getSignalMatrixForGroupRun() {
    FeatureValueMatrix matrix = matrices.get(AnalysisMode.SIGNAL);
    if (matrix != null && !matrix.isEmpty()) {
      return filterSignalMatrixForComparison(matrix);
    }
    return null;
  }

  /** Grid for group Run (compact SSOT or sparse matrix) with Sample Comparison applied. */
  public synchronized ValueGrid getSignalGridForGroupRun() {
    ValueGrid grid = getSignalGrid();
    if (grid == null || grid.isEmpty()) {
      return grid;
    }
    if (grid instanceof FeatureValueMatrix matrix) {
      return filterSignalMatrixForComparison(matrix);
    }
    return SignalComparisonFilter.filterGridUnits(grid, signalComparison, signalRoster(grid));
  }

  /**
   * Drop units that fail Sample Comparison. Presence = finite cell in the SSOT
   * (value thresholds already applied via compact mask when using gene matrices).
   */
  private FeatureValueMatrix filterSignalMatrixForComparison(FeatureValueMatrix matrix) {
    return SignalComparisonFilter.filterMatrixUnits(
        matrix, signalComparison, signalRoster(matrix));
  }

  private List<SampleTrack> signalRoster(ValueGrid grid) {
    if (signalBundle != null && !signalBundle.tracks().isEmpty()) {
      return new ArrayList<>(signalBundle.tracks());
    }
    if (grid instanceof CompactTableGrid ctg) {
      return new ArrayList<>(ctg.trackOrder());
    }
    return grid != null ? new ArrayList<>(grid.tracks()) : List.of();
  }

  public synchronized GroupStatResult getAnnotationResult() {
    return filteredAnnotationResult != null ? filteredAnnotationResult : annotationResult;
  }

  public synchronized GroupStatResult getRawAnnotationResult() {
    return annotationResult;
  }

  /**
   * Signal annotation listing for the Signals results tab.
   * Group comparison stays in {@link #getFilteredResult(AnalysisMode)} / canvas path.
   */
  public synchronized GroupStatResult getSignalDisplayResult() {
    return getAnnotationResult();
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
    annotationResult = null;
    filteredAnnotationResult = null;
    signalBundle = null;
    signalGrid = null;
    signalComparison = new SignalComparisonSettings();
    hiddenAggregateGroupIds.clear();
    generation.set(generation.get() + 1);
    notifyListeners();
  }

  private void refilterAll() {
    for (AnalysisMode mode : AnalysisMode.values()) {
      GroupStatResult raw = results.get(mode);
      if (raw != null) {
        GroupStatResult afterThr = ThresholdFilter.apply(raw, thresholds);
        filteredResults.put(mode, applyGeneVisibility(mode, afterThr != null ? afterThr : raw));
      }
    }
  }

  /**
   * Gene-biotype / COSMIC legend filter for table/export views.
   * Skipped for Signal window/feature units (not gene-typed).
   */
  private GroupStatResult applyGeneVisibility(AnalysisMode mode, GroupStatResult result) {
    if (result == null) {
      return null;
    }
    if (mode == AnalysisMode.SIGNAL) {
      ComparisonUnit unit = signalSpec != null ? signalSpec.getUnit() : ComparisonUnit.GENE;
      if (unit != null && unit != ComparisonUnit.GENE) {
        return result;
      }
    }
    return GeneVisibilityFilter.apply(result);
  }

  /**
   * Re-apply value thresholds and gene-biotype visibility without recollecting.
   * Called when Genome sidebar gene types change.
   */
  public synchronized void refilterAnnotationForVisibility() {
    refilterAnnotation();
    refilterAll();
    generation.set(generation.get() + 1);
    notifyListeners();
  }

  /**
   * Re-apply absolute value thresholds without recollecting.
   * Multi-channel: refilter {@link #signalBundle} with channel thresholds from spec.
   */
  private void refilterAnnotation() {
    String scoreLabel = signalSpec.getValueKind() != null
        ? "Signal " + signalSpec.getValueKind().name()
            + " / " + signalSpec.getAggregation().name()
        : "Signal";
    if (signalBundle != null && !signalBundle.isEmpty() && signalSpec.hasChannels()) {
      List<SampleTrack> tracks = new ArrayList<>(signalBundle.tracks());
      annotationResult = SignalAnnotator.annotateBundle(
          AnalysisMode.SIGNAL, signalBundle, signalSpec.getChannels(), tracks, scoreLabel);
      filteredAnnotationResult = applySignalSampleComparison(annotationResult);
      return;
    }
    ValueGrid grid = getSignalGrid();
    if (grid == null || grid.isEmpty()) {
      return;
    }
    List<SampleTrack> tracks = signalRoster(grid);
    // Compact SSOT: values already NaN-masked — annotate finite cells only.
    SignalValueThresholds thr = grid instanceof CompactTableGrid
        ? topNOnly(valueThresholds)
        : valueThresholds;
    annotationResult = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL, grid, tracks, thr, scoreLabel);
    filteredAnnotationResult = applySignalSampleComparison(annotationResult);
  }

  private static SignalValueThresholds topNOnly(SignalValueThresholds src) {
    SignalValueThresholds t = new SignalValueThresholds();
    if (src != null) {
      t.setMaxRows(src.getMaxRows());
    }
    return t;
  }

  private GroupStatResult applySignalSampleComparison(GroupStatResult raw) {
    if (raw == null) {
      return null;
    }
    ValueGrid grid = getSignalGrid();
    List<SampleTrack> tracks = signalRoster(grid);
    FeatureValueMatrix matrix = grid instanceof FeatureValueMatrix fvm ? fvm : null;
    return SignalComparisonFilter.apply(
        raw,
        signalComparison,
        tracks,
        matrix,
        signalBundle,
        signalSpec != null && signalSpec.hasChannels() ? signalSpec.getChannels() : List.of(),
        null,
        grid);
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
