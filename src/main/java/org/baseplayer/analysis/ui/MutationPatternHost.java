package org.baseplayer.analysis.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.DifferenceThresholds;
import org.baseplayer.analysis.EffectMode;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.groupstat.GroupStatTest;
import org.baseplayer.analysis.mutation.MutationGroupEnrichment;
import org.baseplayer.analysis.pattern.SamplePatternEngine;
import org.baseplayer.analysis.signal.SignalValueType;
import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;

import javafx.scene.layout.VBox;

/**
 * Pattern Discovery for Point/SV Sample Comparison — same chrome as Signal,
 * backed by a gene×sample carrier matrix from filter-passing variants.
 */
public final class MutationPatternHost {

  private final AnalysisMode mode;
  private final PatternDiscoveryControls controls = new PatternDiscoveryControls();
  private AnalysisBusyRunner busyRunner;
  private Consumer<GroupStatResult> onResultReady;

  public MutationPatternHost(AnalysisMode mode) {
    this.mode = mode != null ? mode : AnalysisMode.MUTATIONS_POINT;
    controls.setPatternMode(this.mode);
  }

  public void setAnalysisBusyRunner(AnalysisBusyRunner runner) {
    this.busyRunner = runner;
  }

  public void setOnResultReady(Consumer<GroupStatResult> onResultReady) {
    this.onResultReady = onResultReady;
  }

  public PatternDiscoveryControls controls() {
    return controls;
  }

  public void mountInto(VBox host) {
    if (host == null) {
      return;
    }
    controls.setOnDiscover(this::discoverCurrent);
    controls.setOnApplyGroups(this::applyPatternGroups);
    controls.setOnShowHeatmap(this::showPatternHeatmap);
    controls.refreshApplyEnabled(mode);
    host.getChildren().setAll(controls.createEmbedded());
  }

  private void discoverCurrent() {
    runBusy("Discovering patterns…", this::discoverCurrentBody);
  }

  private void discoverCurrentBody(Runnable onDone) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    if (!VcfManager.getInstance().hasLoadedVcf()) {
      controls.setStatus("Load VCF samples first");
      if (onDone != null) {
        onDone.run();
      }
      return;
    }
    List<SampleTrack> tracks = new ArrayList<>(registry.getIncludedSampleTracks());
    if (tracks.size() < 2) {
      controls.setStatus("Need ≥2 samples");
      if (onDone != null) {
        onDone.run();
      }
      return;
    }
    VariantFilter filter = VcfManager.getInstance().getCurrentFilter();
    List<VariantList> lists = new ArrayList<>();
    for (VariantList list : VcfManager.getInstance().snapshotVariantCache().values()) {
      if (list != null && !list.isEmpty()) {
        lists.add(list);
      }
    }
    if (lists.isEmpty()) {
      controls.setStatus("Annotate chromosomes or run Discover All Chromosomes");
      if (onDone != null) {
        onDone.run();
      }
      return;
    }

    SamplePatternEngine.Settings settings = controls.buildSettings();
    DifferenceThresholds thr = AnalysisSession.get().getThresholds();
    controls.setBusy(true);
    controls.setStatus("Building gene×sample matrix…");
    ThreadRunner.get().submit(
        mode == AnalysisMode.MUTATIONS_SV
            ? "SV pattern discovery"
            : "Point pattern discovery",
        () -> {
          MutationGroupEnrichment.Accumulator acc =
              new MutationGroupEnrichment.Accumulator(tracks);
          for (VariantList list : lists) {
            if (Thread.currentThread().isInterrupted()) {
              return null;
            }
            if (filter != null) {
              list.rebuildVisibleChain(filter);
            }
            acc.fold(mode, list);
          }
          FeatureValueMatrix matrix = acc.toMatrix();
          if (matrix == null || matrix.isEmpty()) {
            return new DiscoverPack(null, null, "No genes with filter-passing variants");
          }
          GroupStatTest test = AnalysisSession.get().getGroupStatTest();
          EffectMode effect = AnalysisSession.get().getEffectMode();
          SamplePatternEngine.Result pattern = SamplePatternEngine.discover(
              matrix, acc.tracks(), settings, effect, SignalValueType.RAW, test);
          return new DiscoverPack(pattern, matrix, null);
        },
        pack -> {
          controls.setBusy(false);
          if (pack == null) {
            controls.setStatus("Pattern discovery failed");
            if (onDone != null) {
              onDone.run();
            }
            return;
          }
          if (pack.error() != null) {
            controls.setStatus(pack.error());
            if (onDone != null) {
              onDone.run();
            }
            return;
          }
          SamplePatternEngine.Result result = SamplePatternEngine.withEffectsFromMatrix(
              pack.pattern(), pack.matrix());
          AnalysisSession.get().setLastPatternResult(mode, result);
          controls.refreshApplyEnabled(mode);
          GroupStatResult markers = result != null ? result.markers() : null;
          AnalysisSession.get().putResult(mode, markers, pack.matrix(), thr);
          GroupStatResult shown = AnalysisSession.get().getFilteredResult(mode);
          int kept = shown != null ? shown.rows().size() : 0;
          int raw = markers != null ? markers.rows().size() : 0;
          if (result != null) {
            controls.setStatus(String.format(
                Locale.ROOT,
                "%s · showing %d / %d",
                result.summary(),
                kept,
                raw));
          }
          if (onResultReady != null && shown != null) {
            onResultReady.accept(shown);
          } else if (onResultReady != null && markers != null) {
            onResultReady.accept(markers);
          }
          org.baseplayer.draw.GenomicCanvas.update.set(
              !org.baseplayer.draw.GenomicCanvas.update.get());
          if (onDone != null) {
            onDone.run();
          }
        });
  }

  private void applyPatternGroups() {
    SamplePatternEngine.Result result = AnalysisSession.get().getLastPatternResult(mode);
    if (result == null || result.tracks() == null || result.clusterOfTrack() == null) {
      controls.setStatus("Discover patterns first");
      return;
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    int k = result.k();
    SampleGroup[] groups = new SampleGroup[k];
    for (int c = 0; c < k; c++) {
      String name = "Pattern " + (c + 1);
      SampleGroup existing = registry.findSampleGroupByName(name);
      groups[c] = existing != null ? existing : registry.createSampleGroup(name, null);
    }
    for (int c = 0; c < k; c++) {
      registry.setTracksExclusiveGroup(result.tracksInCluster(c), groups[c].getId());
    }
    ComparisonContext.get().ensureBound();
    controls.setStatus(
        result.summary() + " · applied as Sample Groups (Pattern 1…" + k + ")");
  }

  private void showPatternHeatmap() {
    SamplePatternEngine.Result result = AnalysisSession.get().getLastPatternResult(mode);
    if (result == null || !result.hasHeatmapData()) {
      controls.setStatus("Discover patterns first");
      return;
    }
    new SignalPatternHeatmap(result, "Pattern heatmap", "Pattern", null).show();
  }

  private void runBusy(String status, Consumer<Runnable> work) {
    if (busyRunner != null) {
      busyRunner.run(status, work);
    } else if (work != null) {
      work.accept(null);
    }
  }

  private record DiscoverPack(
      SamplePatternEngine.Result pattern,
      FeatureValueMatrix matrix,
      String error) {}
}
