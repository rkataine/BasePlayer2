package org.baseplayer.analysis.mutation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.baseplayer.io.VcfManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.DifferenceThresholds;
import org.baseplayer.analysis.groupstat.GroupStatResult;

/**
 * Shared background runner for Point/SV mutation group comparison.
 * Used by the Group comparison tool panel (Refresh) and auto-triggers after
 * annotate-all / sample-group changes.
 */
public final class MutationGroupComparisonRunner {

  public interface Listener {
    void onStarted(AnalysisMode mode);

    void onFinished(AnalysisMode mode, GroupStatResult filteredOrRaw, boolean selectResultsTab);

    void onFailed(AnalysisMode mode, String message);

    void onCancelled(AnalysisMode mode);
  }

  private static final Map<AnalysisMode, CopyOnWriteArrayList<Listener>> LISTENERS =
      new EnumMap<>(AnalysisMode.class);
  private static final AtomicInteger GROUP_DEBOUNCE = new AtomicInteger();
  private static PauseTransition groupDebouncePause;
  private static BooleanSupplier annotateAllRunning = () -> false;

  private MutationGroupComparisonRunner() {}

  public static void setAnnotateAllRunningCheck(BooleanSupplier annotateAllRunning) {
    MutationGroupComparisonRunner.annotateAllRunning =
        annotateAllRunning != null ? annotateAllRunning : () -> false;
  }

  public static void addListener(AnalysisMode mode, Listener listener) {
    if (mode == null || listener == null) {
      return;
    }
    LISTENERS.computeIfAbsent(mode, m -> new CopyOnWriteArrayList<>()).add(listener);
  }

  public static void removeListener(AnalysisMode mode, Listener listener) {
    if (mode == null || listener == null) {
      return;
    }
    CopyOnWriteArrayList<Listener> list = LISTENERS.get(mode);
    if (list != null) {
      list.remove(listener);
    }
  }

  /** Modes that currently have a UI listener (installed enrichment panels). */
  public static List<AnalysisMode> installedModes() {
    List<AnalysisMode> out = new ArrayList<>(2);
    for (AnalysisMode mode : List.of(AnalysisMode.MUTATIONS_POINT, AnalysisMode.MUTATIONS_SV)) {
      CopyOnWriteArrayList<Listener> list = LISTENERS.get(mode);
      if (list != null && !list.isEmpty()) {
        out.add(mode);
      }
    }
    return out;
  }

  public static String blockReason() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    String block = ComparisonContext.get().runBlockReason(registry);
    if (block != null) {
      return block;
    }
    if (!VcfManager.getInstance().hasLoadedVcf()) {
      return "Load VCF samples first";
    }
    return null;
  }

  public static boolean cacheHasVariants() {
    Map<String, VariantList> snap = VcfManager.getInstance().snapshotVariantCache();
    if (snap == null || snap.isEmpty()) {
      return false;
    }
    for (VariantList list : snap.values()) {
      if (list != null && list.getVisibleHead() != null) {
        return true;
      }
    }
    return false;
  }

  /**
   * After annotate-all (or similar): run every installed mode when groups + cache are ready.
   */
  public static void runAutoAfterAnnotation() {
    if (blockReason() != null || !cacheHasVariants()) {
      return;
    }
    for (AnalysisMode mode : installedModes()) {
      run(mode, AnalysisSession.get().getThresholds(), false);
    }
  }

  /**
   * Debounced re-run when sample groups change (skips while annotate-all is in flight).
   */
  public static void scheduleAutoAfterGroupChange() {
    int gen = GROUP_DEBOUNCE.incrementAndGet();
    Platform.runLater(() -> {
      if (groupDebouncePause == null) {
        groupDebouncePause = new PauseTransition(Duration.millis(450));
      }
      groupDebouncePause.stop();
      groupDebouncePause.setOnFinished(e -> {
        if (gen != GROUP_DEBOUNCE.get()) {
          return;
        }
        if (annotateAllRunning.getAsBoolean()) {
          return;
        }
        if (blockReason() != null || !cacheHasVariants()) {
          return;
        }
        for (AnalysisMode mode : installedModes()) {
          run(mode, AnalysisSession.get().getThresholds(), false);
        }
      });
      groupDebouncePause.playFromStart();
    });
  }

  /**
   * Manual Refresh / programmatic run for one mode.
   *
   * @param selectResultsTab when true, listeners may switch to the results tab
   */
  public static ThreadRunner.RunnerTask run(
      AnalysisMode mode,
      DifferenceThresholds thresholds,
      boolean selectResultsTab) {
    AnalysisMode runMode = mode != null ? mode : AnalysisMode.MUTATIONS_POINT;
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    String block = blockReason();
    if (block != null) {
      notifyFailed(runMode, block);
      return null;
    }

    DifferenceThresholds thr = thresholds != null
        ? thresholds.copy()
        : AnalysisSession.get().getThresholds();
    ComparisonContext ctx = ComparisonContext.get();
    List<Integer> groupIds = ctx.getParticipatingGroupIds();
    Integer baseline = ctx.getBaselineGroupId();
    VariantFilter filter = VcfManager.getInstance().getCurrentFilter();

    notifyStarted(runMode);

    ThreadRunner.RunnerTask task = ThreadRunner.get().submit(
        taskName(runMode),
        () -> {
          List<VariantList> lists = new ArrayList<>();
          for (VariantList list : VcfManager.getInstance().snapshotVariantCache().values()) {
            if (Thread.currentThread().isInterrupted()) {
              return null;
            }
            if (list != null) {
              lists.add(list);
            }
          }
          if (lists.isEmpty()) {
            return new MutationGroupEnrichment.Outcome(
                new GroupStatResult(
                    runMode, groupIds, baseline, List.of(), "No annotated chromosomes in cache"),
                null);
          }
          return MutationGroupEnrichment.analyze(
              runMode, lists, filter, registry, groupIds, baseline);
        },
        outcome -> {
          if (outcome != null && outcome.result() != null) {
            AnalysisSession.get().putResult(runMode, outcome.result(), outcome.matrix(), thr);
            GroupStatResult filtered = AnalysisSession.get().getFilteredResult(runMode);
            notifyFinished(
                runMode,
                filtered != null ? filtered : outcome.result(),
                selectResultsTab);
            org.baseplayer.draw.GenomicCanvas.update.set(
                !org.baseplayer.draw.GenomicCanvas.update.get());
          } else {
            notifyFailed(runMode, "Enrichment failed");
          }
        });
    task.onCancel(() -> Platform.runLater(() -> notifyCancelled(runMode)));
    return task;
  }

  private static String taskName(AnalysisMode mode) {
    return mode == AnalysisMode.MUTATIONS_SV
        ? "SV/CNV group comparison"
        : "Point mutation group comparison";
  }

  private static void notifyStarted(AnalysisMode mode) {
    for (Listener listener : listeners(mode)) {
      try {
        listener.onStarted(mode);
      } catch (RuntimeException ignored) {
        // keep other listeners
      }
    }
  }

  private static void notifyFinished(
      AnalysisMode mode, GroupStatResult result, boolean selectResultsTab) {
    for (Listener listener : listeners(mode)) {
      try {
        listener.onFinished(mode, result, selectResultsTab);
      } catch (RuntimeException ignored) {
        // keep other listeners
      }
    }
  }

  private static void notifyFailed(AnalysisMode mode, String message) {
    for (Listener listener : listeners(mode)) {
      try {
        listener.onFailed(mode, message);
      } catch (RuntimeException ignored) {
        // keep other listeners
      }
    }
  }

  private static void notifyCancelled(AnalysisMode mode) {
    for (Listener listener : listeners(mode)) {
      try {
        listener.onCancelled(mode);
      } catch (RuntimeException ignored) {
        // keep other listeners
      }
    }
  }

  private static List<Listener> listeners(AnalysisMode mode) {
    CopyOnWriteArrayList<Listener> list = LISTENERS.get(mode);
    return list != null ? list : List.of();
  }
}
