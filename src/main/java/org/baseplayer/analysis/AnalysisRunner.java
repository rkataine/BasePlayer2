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
            FeatureValueMatrix matrix = new FeatureValueMatrix();
            String notes = source.collect(tracks, matrix, effectiveSpec, collectCtx);
            collectCtx.checkCancelled();
            if (matrix.isEmpty()) {
              return new RunOutcome(notes, null, matrix, false);
            }
            if (onStatus != null) {
              Platform.runLater(() -> onStatus.accept("Computing group stats…"));
            }
            collectCtx.progress(1, 1, "Computing group stats…");
            String scoreLabel = effectiveSpec.getValueKind() != null
                ? "Signal " + effectiveSpec.getValueKind().name()
                    + " / " + effectiveSpec.getAggregation().name()
                : "Signal";
            GroupStatResult result = GroupStatEngine.compute(
                runMode,
                matrix,
                registry,
                groupIds,
                baseline,
                AnalysisSession.get().getGroupStatTest(),
                scoreLabel);
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

  private record RunOutcome(
      String notes, GroupStatResult result, FeatureValueMatrix matrix, boolean cancelled) {}
}
