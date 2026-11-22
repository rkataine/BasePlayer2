package org.baseplayer.analysis;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import org.baseplayer.analysis.source.ValueSource;
import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.LoadingManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;

import javafx.application.Platform;
import org.baseplayer.analysis.grid.CompactTableGrid;
import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.grid.ValueGrid;
import org.baseplayer.analysis.groupstat.GroupStatEngine;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.signal.SignalAnnotator;
import org.baseplayer.analysis.signal.SignalComparisonFilter;
import org.baseplayer.analysis.signal.SignalSourceSpec;
import org.baseplayer.analysis.signal.SignalValueThresholds;

/** Background runner that fills a matrix, computes group stats, and publishes to {@link AnalysisSession}. */
public final class AnalysisRunner {

  private AnalysisRunner() {}

  public static ThreadRunner.RunnerTask run(
      AnalysisMode mode,
      ValueSource source,
      SignalSourceSpec spec,
      DifferenceThresholds thresholds,
      Consumer<String> onStatus,
      Consumer<GroupStatResult> onDone) {
    ComparisonContext context = ComparisonContext.get();
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    String block = context.runBlockReason(registry);
    if (block != null) {
      if (onStatus != null) {
        onStatus.accept(block);
      }
      if (onDone != null) {
        Platform.runLater(() -> onDone.accept(null));
      }
      return null;
    }
    SignalSourceSpec effectiveSpec = spec != null ? spec : new SignalSourceSpec();
    if (effectiveSpec.getUnit() == ComparisonUnit.GENE && !AnnotationData.isGenesLoaded()) {
      if (onStatus != null) {
        onStatus.accept("Gene annotation is not loaded yet");
      }
      if (onDone != null) {
        Platform.runLater(() -> onDone.accept(null));
      }
      return null;
    }
    if (source == null) {
      if (onStatus != null) {
        onStatus.accept("No value source selected");
      }
      return null;
    }

    List<Integer> groupIds = context.getParticipatingGroupIds();
    Integer baseline = context.getBaselineGroupId();
    List<SampleTrack> tracks = context.resolveTracks(registry);
    DifferenceThresholds thr = thresholds != null ? thresholds.copy() : new DifferenceThresholds();
    AnalysisMode runMode = mode != null ? mode : AnalysisMode.SIGNAL;

    return ThreadRunner.get().submit(
        "Analysis " + runMode,
        () -> {
          ThreadRunner.RunnerTask task = ThreadRunner.currentTask();
          CollectContext collectCtx = new CollectContext(task, onStatus);
          if (task != null) {
            task.onCancel(collectCtx::markCancelled);
          }
          try {
            if (onStatus != null) {
              Platform.runLater(() -> onStatus.accept("Collecting values…"));
            }
            collectCtx.progress(0, 1, "Collecting values…");
            AnalysisSession session = AnalysisSession.get();
            // Gene-matrix SSOT: reuse masked MappedTableData grid (no collect copy).
            if (session.getSignalGrid() instanceof CompactTableGrid) {
              ValueGrid filtered = session.getSignalGridForGroupRun();
              if (filtered == null || filtered.isEmpty()) {
                return new RunOutcome("No units pass Sample Comparison", null, null, false);
              }
              if (onStatus != null) {
                Platform.runLater(() -> onStatus.accept("Computing group stats…"));
              }
              collectCtx.progress(1, 1, "Computing group stats…");
              String scoreLabel = effectiveSpec.getValueKind() != null
                  ? "Signal " + effectiveSpec.getValueKind().name()
                      + " / " + effectiveSpec.getAggregation().name()
                  : "Signal";
              AnalysisSession sess = AnalysisSession.get();
              GroupStatResult result = GroupStatEngine.compute(
                  runMode,
                  filtered,
                  registry,
                  groupIds,
                  baseline,
                  sess.getGroupStatTest(),
                  scoreLabel,
                  sess.getEffectMode(),
                  sess.resolveValueTypeForEffect(),
                  sess.getMinMeanPrefilter());
              return new RunOutcome("Gene matrix SSOT", result, null, false);
            }
            FeatureValueMatrix matrix = new FeatureValueMatrix();
            String notes = source.collect(tracks, matrix, effectiveSpec, collectCtx);
            collectCtx.checkCancelled();
            if (matrix.isEmpty()) {
              return new RunOutcome(notes, null, matrix, false);
            }
            matrix = SignalComparisonFilter.filterMatrixUnits(
                matrix,
                session.getSignalComparison(),
                tracks);
            if (matrix.isEmpty()) {
              return new RunOutcome(
                  (notes != null ? notes + " · " : "")
                      + "No units pass Sample Comparison",
                  null,
                  matrix,
                  false);
            }
            if (onStatus != null) {
              Platform.runLater(() -> onStatus.accept("Computing group stats…"));
            }
            collectCtx.progress(1, 1, "Computing group stats…");
            String scoreLabel = effectiveSpec.getValueKind() != null
                ? "Signal " + effectiveSpec.getValueKind().name()
                    + " / " + effectiveSpec.getAggregation().name()
                : "Signal";
            AnalysisSession sess = AnalysisSession.get();
            GroupStatResult result = GroupStatEngine.compute(
                runMode,
                matrix,
                registry,
                groupIds,
                baseline,
                sess.getGroupStatTest(),
                scoreLabel,
                sess.getEffectMode(),
                sess.resolveValueTypeForEffect(),
                sess.getMinMeanPrefilter());
            collectCtx.checkCancelled();
            return new RunOutcome(notes, result, matrix, false);
          } catch (CancellationException cancelled) {
            return new RunOutcome("Cancelled", null, null, true);
          } finally {
            collectCtx.clearUi();
          }
        },
        outcome -> {
          LoadingManager.get().clearDetail();
          if (outcome != null && outcome.cancelled()) {
            if (onStatus != null) {
              onStatus.accept("Cancelled");
            }
            if (onDone != null) {
              onDone.accept(null);
            }
            return;
          }
          if (outcome != null && outcome.result() != null) {
            AnalysisSession.get().setSignalSpec(effectiveSpec);
            AnalysisSession.get().putResult(runMode, outcome.result(), outcome.matrix(), thr);
          }
          if (onStatus != null && outcome != null) {
            onStatus.accept(outcome.notes());
          }
          if (onDone != null) {
            GroupStatResult shown = outcome != null && outcome.result() != null
                ? AnalysisSession.get().getFilteredResult(runMode)
                : null;
            onDone.accept(shown != null ? shown : (outcome != null ? outcome.result() : null));
          }
        });
  }

  /** Back-compat for callers that only pass aggregation. */
  public static ThreadRunner.RunnerTask run(
      AnalysisMode mode,
      ValueSource source,
      ValueAggregation aggregation,
      Consumer<String> onStatus,
      Consumer<GroupStatResult> onDone) {
    SignalSourceSpec spec = new SignalSourceSpec();
    if (aggregation != null) {
      spec.setAggregation(aggregation);
    }
    return run(mode, source, spec, AnalysisSession.get().getThresholds(), onStatus, onDone);
  }

  /**
   * Collect values for all usable tracks and publish a groupless annotation listing.
   * Does not require sample groups.
   */
  public static ThreadRunner.RunnerTask annotate(
      ValueSource source,
      SignalSourceSpec spec,
      List<SampleTrack> tracks,
      SignalValueThresholds thresholds,
      Consumer<String> onStatus,
      Consumer<GroupStatResult> onDone) {
    SignalSourceSpec effectiveSpec = spec != null ? spec : new SignalSourceSpec();
    if (effectiveSpec.getUnit() == ComparisonUnit.GENE && !AnnotationData.isGenesLoaded()) {
      if (onStatus != null) {
        onStatus.accept("Gene annotation is not loaded yet");
      }
      if (onDone != null) {
        Platform.runLater(() -> onDone.accept(null));
      }
      return null;
    }
    if (source == null) {
      if (onStatus != null) {
        onStatus.accept("No value source selected");
      }
      if (onDone != null) {
        Platform.runLater(() -> onDone.accept(null));
      }
      return null;
    }
    List<SampleTrack> usable = tracks != null ? List.copyOf(tracks) : List.of();
    if (usable.isEmpty()) {
      if (onStatus != null) {
        onStatus.accept("Open signal data to annotate");
      }
      if (onDone != null) {
        Platform.runLater(() -> onDone.accept(null));
      }
      return null;
    }
    SignalValueThresholds thr = thresholds != null
        ? thresholds.copy()
        : AnalysisSession.get().getValueThresholds();

    return ThreadRunner.get().submit(
        "Annotate SIGNAL",
        () -> {
          ThreadRunner.RunnerTask task = ThreadRunner.currentTask();
          CollectContext collectCtx = new CollectContext(task, onStatus);
          if (task != null) {
            task.onCancel(collectCtx::markCancelled);
          }
          try {
            if (onStatus != null) {
              Platform.runLater(() -> onStatus.accept("Collecting values…"));
            }
            collectCtx.progress(0, 1, "Collecting values…");
            FeatureValueMatrix matrix = null;
            FeatureValueBundle bundle = null;
            ValueGrid grid = null;
            String notes;
            // Gene-matrix tables: zero-copy SSOT grid (same floats as sample canvas).
            if (!effectiveSpec.hasChannels()) {
              grid = CompactTableGrid.tryCreate(usable, effectiveSpec.getScope());
            }
            if (grid != null) {
              notes = String.format(
                  java.util.Locale.ROOT,
                  "Gene matrix SSOT: %d units × %d samples",
                  grid.size(),
                  usable.size());
            } else if (effectiveSpec.hasChannels()
                && source instanceof org.baseplayer.analysis.source.MultiChannelMappedTableSource multi) {
              matrix = new FeatureValueMatrix();
              bundle = new FeatureValueBundle(effectiveSpec.getChannels());
              notes = multi.collectBundle(usable, bundle, matrix, effectiveSpec, collectCtx);
              grid = matrix;
            } else {
              matrix = new FeatureValueMatrix();
              notes = source.collect(usable, matrix, effectiveSpec, collectCtx);
              grid = matrix;
            }
            collectCtx.checkCancelled();
            boolean empty = bundle != null ? bundle.isEmpty()
                : (grid == null || grid.isEmpty());
            if (empty) {
              return new AnnotateOutcome(notes, null, matrix, bundle, grid, false);
            }
            if (onStatus != null) {
              Platform.runLater(() -> onStatus.accept("Annotating…"));
            }
            String scoreLabel = effectiveSpec.getValueKind() != null
                ? "Signal " + effectiveSpec.getValueKind().name()
                    + " / " + effectiveSpec.getAggregation().name()
                : "Signal";
            // Compact SSOT: presence = finite cell (mask applied separately on Apply).
            SignalValueThresholds annotateThr = grid instanceof CompactTableGrid
                ? topNOnly(thr)
                : thr;
            GroupStatResult result = bundle != null
                ? SignalAnnotator.annotateBundle(
                    AnalysisMode.SIGNAL, bundle, usable, scoreLabel)
                : SignalAnnotator.annotate(
                    AnalysisMode.SIGNAL, grid, usable, annotateThr, scoreLabel);
            collectCtx.checkCancelled();
            String combined = notes != null && !notes.isBlank()
                ? notes + " · " + result.summary()
                : result.summary();
            return new AnnotateOutcome(combined, result, matrix, bundle, grid, false);
          } catch (CancellationException cancelled) {
            return new AnnotateOutcome("Cancelled", null, null, null, null, true);
          } finally {
            collectCtx.clearUi();
          }
        },
        outcome -> {
          LoadingManager.get().clearDetail();
          if (outcome != null && outcome.cancelled()) {
            if (onStatus != null) {
              onStatus.accept("Cancelled");
            }
            if (onDone != null) {
              onDone.accept(null);
            }
            return;
          }
          if (outcome != null && outcome.result() != null) {
            AnalysisSession.get().setSignalSpec(effectiveSpec);
            AnalysisSession.get().putAnnotation(
                AnalysisMode.SIGNAL,
                outcome.result(),
                outcome.grid() != null ? outcome.grid() : outcome.matrix(),
                thr,
                outcome.bundle());
          }
          if (onStatus != null && outcome != null) {
            onStatus.accept(outcome.notes());
          }
          if (onDone != null) {
            GroupStatResult shown = outcome != null && outcome.result() != null
                ? AnalysisSession.get().getAnnotationResult()
                : null;
            onDone.accept(shown != null ? shown : (outcome != null ? outcome.result() : null));
          }
        });
  }

  private record RunOutcome(
      String notes, GroupStatResult result, FeatureValueMatrix matrix, boolean cancelled) {}

  private record AnnotateOutcome(
      String notes,
      GroupStatResult result,
      FeatureValueMatrix matrix,
      FeatureValueBundle bundle,
      ValueGrid grid,
      boolean cancelled) {}

  private static SignalValueThresholds topNOnly(SignalValueThresholds src) {
    SignalValueThresholds t = new SignalValueThresholds();
    if (src != null) {
      t.setMaxRows(src.getMaxRows());
    }
    return t;
  }
}
