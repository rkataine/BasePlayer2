package org.baseplayer.analysis.ui;

import java.util.List;
import java.util.function.Consumer;

import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.DifferenceThresholds;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.groupstat.GroupStatTest;
import org.baseplayer.analysis.mutation.MutationGroupComparisonRunner;
import org.baseplayer.io.VcfManager;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Group comparison for filter-passing mutations (point or SV mode).
 * Auto-runs after annotate-all / group changes via {@link MutationGroupComparisonRunner};
 * Run re-scans with the current filters.
 */
public final class MutationEnrichmentPanel extends VBox
    implements MutationGroupComparisonRunner.Listener {

  private final AnalysisMode mode;
  private final Label statusLabel = new Label();
  private final Button runButton = new Button("Run");
  private final ChoiceBox<GroupStatTest> testChoice;
  private final GroupStatResultTable resultTable = new GroupStatResultTable();
  private final GroupComparisonFilterControls filters = new GroupComparisonFilterControls();
  private GroupStatResult lastDisplayed;
  private Consumer<GroupStatResult> onResultReady;
  private AnalysisBusyRunner analysisBusyRunner;
  private Runnable pendingBusyComplete;
  private Tab resultsTab;
  private boolean refreshing;
  private static final String RESULTS_TAB_BASE = "Group comparison";

  public MutationEnrichmentPanel(AnalysisMode mode) {
    this.mode = mode != null ? mode : AnalysisMode.MUTATIONS_POINT;
    setSpacing(0);
    setPadding(Insets.EMPTY);
    VBox.setVgrow(this, Priority.ALWAYS);

    runButton.getStyleClass().add("primary-button");
    runButton.setOnAction(e -> run(true));
    Tooltip.install(runButton, new Tooltip(mode == AnalysisMode.MUTATIONS_SV
        ? "Run CNV/SV group comparison with the current filters"
        : "Run point-mutation group comparison with the current filters"));

    testChoice = GroupStatTestControls.createChoiceBox(this::onTestChanged);
    statusLabel.setWrapText(true);
    statusLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.85;");

    resultTable.setMeanTooltip(mode == AnalysisMode.MUTATIONS_SV
        ? "Mean of signed event burden (gains−losses) over all group samples (missing = 0). "
            + "Collapsed Diff uses polarity + hit rate (not mean ↑/↓)."
        : "Mean of binary carrier score (0/1) over all group samples (missing = 0). "
            + "Collapsed Diff uses hit rate extremes (not mean ↑/↓).");
    resultTable.setEmptyMessage(
        "Load VCF samples, define ≥2 sample groups, then annotate chromosomes or Run");

    filters.loadFrom(AnalysisSession.get().getThresholds());
    filters.setOnChanged(this::onFiltersChanged);
    filters.setOnShowHeatmap(this::showHeatmap);
    filters.setHeatmapEnabled(false);

    getChildren().add(filters.createWorkspace(runButton, testChoice, statusLabel));

    MutationGroupComparisonRunner.addListener(this.mode, this);

    ComparisonContext.get().revisionProperty().addListener((o, a, b) -> {
      filters.refreshDirectionLabels();
      updateReady();
      MutationGroupComparisonRunner.scheduleAutoAfterGroupChange();
    });
    updateReady();

    GroupStatResult existing = AnalysisSession.get().getFilteredResult(this.mode);
    if (existing == null) {
      existing = AnalysisSession.get().getResult(this.mode);
    }
    if (existing != null) {
      applyDisplayed(existing, false);
    }
  }

  public GroupStatResultTable getResultTable() {
    return resultTable;
  }

  public void setOnResultReady(Consumer<GroupStatResult> onResultReady) {
    this.onResultReady = onResultReady;
  }

  /** Optional Analysis Manager busy gate (same chrome as Signal Discover / annotate-all). */
  public void setAnalysisBusyRunner(AnalysisBusyRunner runner) {
    this.analysisBusyRunner = runner;
  }

  /** Variant-table results tab whose title shows the filtered gene count. */
  public void setResultsTab(Tab resultsTab) {
    this.resultsTab = resultsTab;
    updateResultsTabTitle(lastDisplayed);
  }

  /** Manual run; when {@code selectTab} is true, switches to the results tab. */
  public void run(boolean selectTab) {
    DifferenceThresholds thr = filters.buildThresholds();
    AnalysisSession.get().setThresholds(thr);
    ComparisonContext.get().writeToDocument(ProjectSessionState.get().getDocument());
    ProjectSessionState.get().markDirty();
    String status = mode == AnalysisMode.MUTATIONS_SV
        ? "SV/CNV group comparison…"
        : "Point mutation group comparison…";
    if (analysisBusyRunner != null) {
      analysisBusyRunner.run(status, onDone -> {
        pendingBusyComplete = onDone;
        MutationGroupComparisonRunner.run(mode, thr, selectTab);
      });
    } else {
      MutationGroupComparisonRunner.run(mode, thr, selectTab);
    }
  }

  @Override
  public void onStarted(AnalysisMode runMode) {
    runButton.setDisable(true);
    statusLabel.setText("Scanning…");
  }

  @Override
  public void onFinished(AnalysisMode runMode, GroupStatResult filteredOrRaw, boolean selectResultsTab) {
    applyDisplayed(filteredOrRaw, selectResultsTab);
    updateReady();
    finishBusy();
  }

  @Override
  public void onFailed(AnalysisMode runMode, String message) {
    String msg = message != null && !message.isBlank() ? message : "Failed";
    statusLabel.setText(msg);
    if (lastDisplayed == null) {
      resultTable.setEmptyMessage(msg);
    }
    updateReady();
    finishBusy();
  }

  @Override
  public void onCancelled(AnalysisMode runMode) {
    statusLabel.setText("Cancelled");
    updateReady();
    finishBusy();
  }

  private void finishBusy() {
    Runnable done = pendingBusyComplete;
    pendingBusyComplete = null;
    if (done != null) {
      done.run();
    }
  }

  private void onFiltersChanged() {
    if (refreshing) {
      return;
    }
    DifferenceThresholds thr = filters.buildThresholds();
    AnalysisSession.get().setThresholds(thr);
    ComparisonContext.get().writeToDocument(ProjectSessionState.get().getDocument());
    ProjectSessionState.get().markDirty();
    refreshDisplayedResult();
  }

  private void onTestChanged() {
    if (refreshing) {
      return;
    }
    GroupStatResult recomputed = GroupStatTestControls.recomputeIfPossible(mode, scoreLabel());
    if (recomputed != null) {
      applyDisplayed(recomputed, false);
      org.baseplayer.draw.GenomicCanvas.update.set(!org.baseplayer.draw.GenomicCanvas.update.get());
      return;
    }
    statusLabel.setText("Test updated — Run to apply");
  }

  private String scoreLabel() {
    return mode == AnalysisMode.MUTATIONS_SV
        ? "CNV gains−losses (event burden)"
        : "binary carrier 0/1";
  }

  private void refreshDisplayedResult() {
    GroupStatResult filtered = AnalysisSession.get().getFilteredResult(mode);
    if (filtered == null) {
      filtered = AnalysisSession.get().getResult(mode);
    }
    if (filtered != null) {
      applyDisplayed(filtered, false);
      org.baseplayer.draw.GenomicCanvas.update.set(!org.baseplayer.draw.GenomicCanvas.update.get());
    }
  }

  /** Refresh after Genome sidebar gene-type / COSMIC visibility changes. */
  public void refreshAfterGeneVisibility() {
    refreshDisplayedResult();
  }

  /** Refresh from session after genomewide Discover/Compare (or external putResult). */
  public void refreshFromSession(boolean selectResultsTab) {
    GroupStatResult filtered = AnalysisSession.get().getFilteredResult(mode);
    if (filtered == null) {
      filtered = AnalysisSession.get().getResult(mode);
    }
    applyDisplayed(filtered, selectResultsTab);
    updateReady();
  }

  private void applyDisplayed(GroupStatResult result, boolean notify) {
    lastDisplayed = result;
    resultTable.setResult(result);
    statusLabel.setText(result != null ? result.summary() : "");
    String strip = result != null ? result.methodStrip() : null;
    if (strip != null && !strip.isBlank()) {
      statusLabel.setTooltip(new Tooltip(strip));
    } else {
      statusLabel.setTooltip(null);
    }
    if (testChoice.getValue() != AnalysisSession.get().getGroupStatTest()) {
      refreshing = true;
      testChoice.setValue(AnalysisSession.get().getGroupStatTest());
      refreshing = false;
    }
    updateResultsTabTitle(result);
    filters.setHeatmapEnabled(result != null && result.rows() != null && result.rows().size() >= 2);
    if (notify && onResultReady != null && result != null) {
      onResultReady.accept(result);
    }
  }

  private void showHeatmap() {
    GroupStatResult shown = lastDisplayed;
    if (shown == null || shown.rows() == null || shown.rows().size() < 2) {
      statusLabel.setText("Run group comparison first");
      return;
    }
    org.baseplayer.analysis.grid.FeatureValueMatrix matrix =
        AnalysisSession.get().getMatrix(mode);
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    List<Integer> groupIds = ComparisonContext.get().getParticipatingGroupIds();
    org.baseplayer.analysis.pattern.SamplePatternEngine.Result heat =
        org.baseplayer.analysis.pattern.SamplePatternEngine.fromGroupComparison(
            matrix, registry, groupIds, shown);
    if (heat == null || !heat.hasHeatmapData()) {
      statusLabel.setText(heat != null ? heat.summary() : "Need ≥2 DE features for heatmap");
      return;
    }
    // Effects travel with the mutation matrix when present.
    heat = org.baseplayer.analysis.pattern.SamplePatternEngine.withEffectsFromMatrix(heat, matrix);
    new SignalPatternHeatmap(heat, "Group comparison heatmap", "Cohort", null).show();
  }

  private void updateResultsTabTitle(GroupStatResult result) {
    if (resultsTab == null) {
      return;
    }
    if (result == null) {
      resultsTab.setText(RESULTS_TAB_BASE);
      return;
    }
    int shown = result.rows().size();
    GroupStatResult raw = AnalysisSession.get().getResult(mode);
    int total = raw != null ? raw.rows().size() : shown;
    if (total > shown) {
      resultsTab.setText(RESULTS_TAB_BASE + " (" + shown + " / " + total + ")");
    } else {
      resultsTab.setText(RESULTS_TAB_BASE + " (" + shown + ")");
    }
  }

  private void updateReady() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    String block = ComparisonContext.get().runBlockReason(registry);
    boolean hasVcf = VcfManager.getInstance().hasLoadedVcf();
    if (block != null) {
      runButton.setDisable(true);
      statusLabel.setText(block);
    } else if (!hasVcf) {
      runButton.setDisable(true);
      statusLabel.setText("Load VCF samples first");
    } else {
      runButton.setDisable(false);
      if (lastDisplayed == null
          && (statusLabel.getText() == null
              || statusLabel.getText().isBlank()
              || statusLabel.getText().startsWith("Ready")
              || statusLabel.getText().startsWith("Load")
              || statusLabel.getText().startsWith("Annotate")
              || statusLabel.getText().contains("sample group"))) {
        statusLabel.setText(
            "Annotate / Discover / Compare All Chromosomes, or Run on cached chroms");
      }
    }
  }
}
