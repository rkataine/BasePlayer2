package org.baseplayer.analysis.mutation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.baseplayer.analysis.AllChromAction;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.DifferenceThresholds;
import org.baseplayer.analysis.EffectMode;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.groupstat.GroupStatTest;
import org.baseplayer.analysis.pattern.SamplePatternEngine;
import org.baseplayer.analysis.signal.SignalValueType;
import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;

/**
 * Streaming genomewide Discover / Compare for Point and SV: annotate one chromosome
 * at a time, fold into a gene×sample matrix, discard temp lists, then score.
 * Only threshold-passing result rows are kept in {@link AnalysisSession}.
 */
public final class MutationGenomewideRunner {

  public interface Listener {
    void onStarted(AnalysisMode mode, AllChromAction action);

    void onFinished(
        AnalysisMode mode,
        AllChromAction action,
        GroupStatResult filteredOrRaw,
        SamplePatternEngine.Result patternOrNull);

    void onFailed(AnalysisMode mode, AllChromAction action, String message);

    void onCancelled(AnalysisMode mode, AllChromAction action);
  }

  private MutationGenomewideRunner() {}

  public static ThreadRunner.RunnerTask run(
      AnalysisMode mode,
      AllChromAction action,
      List<String> chromosomes,
      VariantFilter filter,
      DifferenceThresholds thresholds,
      SamplePatternEngine.Settings patternSettings,
      Consumer<VariantList> afterChromosomeReady,
      Listener listener) {
    AnalysisMode runMode = mode != null ? mode : AnalysisMode.MUTATIONS_POINT;
    AllChromAction act = action != null ? action : AllChromAction.COMPARE;
    if (act == AllChromAction.ANNOTATE) {
      if (listener != null) {
        listener.onFailed(runMode, act, "Use Annotate All Chromosomes for full cache");
      }
      return null;
    }
    if (chromosomes == null || chromosomes.isEmpty()) {
      if (listener != null) {
        listener.onFailed(runMode, act, "No chromosomes available");
      }
      return null;
    }

    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    String block = ComparisonContext.get().runBlockReason(registry);
    if (act == AllChromAction.COMPARE && block != null) {
      if (listener != null) {
        listener.onFailed(runMode, act, block);
      }
      return null;
    }
    if (!VcfManager.getInstance().hasLoadedVcf()) {
      if (listener != null) {
        listener.onFailed(runMode, act, "Load VCF samples first");
      }
      return null;
    }

    VariantFilter filterSnap = filter != null ? filter.copy() : VcfManager.getInstance().getCurrentFilter();
    if (filterSnap != null) {
      filterSnap = filterSnap.copy();
    }
    DifferenceThresholds thr = thresholds != null
        ? thresholds.copy()
        : AnalysisSession.get().getThresholds();
    SamplePatternEngine.Settings settings = patternSettings != null
        ? patternSettings
        : SamplePatternEngine.Settings.defaults();
    List<String> chroms = List.copyOf(chromosomes);
    ComparisonContext ctx = ComparisonContext.get();
    List<Integer> groupIds = ctx.getParticipatingGroupIds();
    Integer baseline = ctx.getBaselineGroupId();

    if (listener != null) {
      listener.onStarted(runMode, act);
    }

    final VariantFilter filterFinal = filterSnap;
    return ThreadRunner.get().submit(
        taskName(runMode, act),
        () -> {
          MutationGroupEnrichment.Accumulator acc;
          if (act == AllChromAction.DISCOVER) {
            List<SampleTrack> tracks = new ArrayList<>(registry.getIncludedSampleTracks());
            acc = new MutationGroupEnrichment.Accumulator(tracks);
            if (acc.tracks().size() < 2) {
              return new GenomeOutcome(
                  null, null, "Need ≥2 samples for pattern discovery");
            }
          } else {
            if (groupIds == null || groupIds.size() < 2) {
              return new GenomeOutcome(null, null, "Need ≥2 sample groups");
            }
            acc = MutationGroupEnrichment.accumulatorForGroups(registry, groupIds);
            if (acc.tracks().size() < 2) {
              return new GenomeOutcome(null, null, "Need samples in selected groups");
            }
          }

          List<String> warnings = new ArrayList<>();
          int folded = 0;
          for (String chrom : chroms) {
            if (Thread.currentThread().isInterrupted()) {
              return new GenomeOutcome(null, null, "Cancelled");
            }
            VcfManager.ChromFoldSource src =
                VcfManager.getInstance().prepareChromosomeForGenomewideFold(
                    chrom, filterFinal, warnings, afterChromosomeReady);
            VariantList list = src != null ? src.list() : null;
            if (list != null && !list.isEmpty()) {
              if (filterFinal != null) {
                list.rebuildVisibleChain(filterFinal);
              }
              acc.fold(runMode, list);
              folded++;
            }
            // Drop temporary lists; cached chroms stay in variantCache.
            if (src != null && src.discardAfterFold()) {
              // fall out of scope
            }
          }
          if (folded == 0) {
            return new GenomeOutcome(null, null, "No annotated variants across chromosomes");
          }
          if (Thread.currentThread().isInterrupted()) {
            return new GenomeOutcome(null, null, "Cancelled");
          }

          if (act == AllChromAction.DISCOVER) {
            FeatureValueMatrix matrix = acc.toMatrix();
            if (matrix == null || matrix.isEmpty()) {
              return new GenomeOutcome(null, null, "No genes with filter-passing variants");
            }
            GroupStatTest test = AnalysisSession.get().getGroupStatTest();
            EffectMode effect = AnalysisSession.get().getEffectMode();
            SamplePatternEngine.Result pattern = SamplePatternEngine.discover(
                matrix,
                acc.tracks(),
                settings,
                effect,
                SignalValueType.RAW,
                test);
            return new GenomeOutcome(
                new MutationGroupEnrichment.Outcome(null, matrix),
                pattern,
                null);
          }

          MutationGroupEnrichment.Outcome outcome =
              MutationGroupEnrichment.finish(runMode, acc, registry, groupIds, baseline);
          if (outcome != null && outcome.result() != null) {
            String summary = outcome.result().summary() != null
                ? outcome.result().summary() + " · genome-wide (streaming)"
                : "Genome-wide comparison";
            GroupStatResult tagged = new GroupStatResult(
                outcome.result().mode(),
                outcome.result().groupIds(),
                outcome.result().baselineGroupId(),
                outcome.result().rows(),
                summary,
                outcome.result().methodStrip() != null
                    ? outcome.result().methodStrip() + " · genome-wide (streaming)"
                    : "genome-wide (streaming)");
            return new GenomeOutcome(
                new MutationGroupEnrichment.Outcome(tagged, outcome.matrix()),
                null,
                null);
          }
          return new GenomeOutcome(outcome, null, "Enrichment failed");
        },
        genome -> {
          if (genome == null) {
            notifyFailed(listener, runMode, act, "Enrichment failed");
            return;
          }
          if ("Cancelled".equals(genome.error())) {
            if (listener != null) {
              listener.onCancelled(runMode, act);
            }
            return;
          }
          if (genome.error() != null && !genome.error().isBlank()) {
            notifyFailed(listener, runMode, act, genome.error());
            return;
          }
          if (act == AllChromAction.DISCOVER) {
            FeatureValueMatrix store =
                genome.outcome() != null ? genome.outcome().matrix() : null;
            SamplePatternEngine.Result pattern = SamplePatternEngine.withEffectsFromMatrix(
                genome.pattern(), store);
            AnalysisSession.get().setLastPatternResult(runMode, pattern);
            GroupStatResult markers = pattern != null ? pattern.markers() : null;
            AnalysisSession.get().putResult(runMode, markers, store, thr);
            GroupStatResult filtered = AnalysisSession.get().getFilteredResult(runMode);
            if (listener != null) {
              listener.onFinished(runMode, act, filtered != null ? filtered : markers, pattern);
            }
            org.baseplayer.draw.GenomicCanvas.update.set(
                !org.baseplayer.draw.GenomicCanvas.update.get());
            return;
          }
          MutationGroupEnrichment.Outcome outcome = genome.outcome();
          if (outcome == null || outcome.result() == null) {
            notifyFailed(listener, runMode, act, "Enrichment failed");
            return;
          }
          AnalysisSession.get().putResult(runMode, outcome.result(), outcome.matrix(), thr);
          GroupStatResult filtered = AnalysisSession.get().getFilteredResult(runMode);
          if (listener != null) {
            listener.onFinished(
                runMode,
                act,
                filtered != null ? filtered : outcome.result(),
                null);
          }
          org.baseplayer.draw.GenomicCanvas.update.set(
              !org.baseplayer.draw.GenomicCanvas.update.get());
        });
  }

  private static void notifyFailed(
      Listener listener, AnalysisMode mode, AllChromAction action, String message) {
    if (listener != null) {
      listener.onFailed(mode, action, message != null ? message : "Failed");
    }
  }

  private static String taskName(AnalysisMode mode, AllChromAction action) {
    String kind = mode == AnalysisMode.MUTATIONS_SV ? "SV/CNV" : "Point";
    return switch (action) {
      case DISCOVER -> kind + " Discover All Chromosomes";
      case COMPARE -> kind + " Compare All Chromosomes";
      default -> kind + " genomewide";
    };
  }

  private record GenomeOutcome(
      MutationGroupEnrichment.Outcome outcome,
      SamplePatternEngine.Result pattern,
      String error) {}
}
