package org.baseplayer.analysis.ui;

import java.util.function.Consumer;

import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.DifferenceThresholds;
import org.baseplayer.analysis.GroupStatResult;
import org.baseplayer.analysis.GroupStatTest;
import org.baseplayer.analysis.MutationGroupComparisonRunner;
import org.baseplayer.io.VcfManager;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Group comparison for filter-passing mutations (point or SV mode).
 * Auto-runs after annotate-all / group changes via {@link MutationGroupComparisonRunner};
 * Refresh re-scans with the current filters.
 */
public final class MutationEnrichmentPanel extends VBox
    implements MutationGroupComparisonRunner.Listener {

  private final AnalysisMode mode;
  private final Label statusLabel = new Label();
  private final Label methodStripLabel = new Label();
  private final Button refreshButton = new Button("Refresh");
  private final ChoiceBox<GroupStatTest> testChoice;
  private final GroupStatResultTable resultTable = new GroupStatResultTable();
  private final GroupComparisonFilterControls filters = new GroupComparisonFilterControls();
  private GroupStatResult lastDisplayed;
  private Consumer<GroupStatResult> onResultReady;
  private Tab resultsTab;
  private boolean refreshing;
  private static final String RESULTS_TAB_BASE = "Group comparison";

  public MutationEnrichmentPanel(AnalysisMode mode) {
    this.mode = mode != null ? mode : AnalysisMode.MUTATIONS_POINT;
    setSpacing(8);
    setPadding(new Insets(12, 16, 12, 16));
    getStyleClass().add("filter-panel");
    VBox.setVgrow(this, Priority.ALWAYS);

    Label title = new Label("Group comparison");
    title.getStyleClass().add("panel-title");

    Label blurb = new Label(mode == AnalysisMode.MUTATIONS_SV
        ? "CNV/SV group comparison: each gene×sample score is signed event burden "
            + "(gains − losses); missing samples = 0. Diff requires within-group sign "
            + "agreement on the extreme groups. Stable across one-chrom vs annotate-all. "
            + "Use Min |Δ| / Max q / Top N to filter the results tab. "
            + "Export via the variant table Excel button (Group comparison sheet)."
        : "Compares filter-passing point mutations across sample groups for every gene "
            + "with a call. Runs automatically after Annotate all chromosomes and when "
            + "sample groups change. Use Min |Δ| / Max q / Top N to filter the "
            + "variant-table Group comparison tab; Refresh re-scans after filter changes. "
            + "Export via the variant table Excel button (Group comparison sheet).");
    blurb.setWrapText(true);
    blurb.setStyle("-fx-font-size: 12px; -fx-opacity: 0.85;");

    refreshButton.getStyleClass().add("primary-button");
    refreshButton.setOnAction(e -> refresh(true));

    testChoice = GroupStatTestControls.createChoiceBox(this::onTestChanged);
    statusLabel.setWrapText(true);
    statusLabel.setStyle("-fx-font-size: 12px;");
    methodStripLabel.setWrapText(true);
    methodStripLabel.setStyle("-fx-font-size: 11px; -fx-opacity: 0.8;");

    resultTable.setMeanTooltip(mode == AnalysisMode.MUTATIONS_SV
        ? "Mean of signed event burden (gains−losses) over all group samples (missing = 0)"
        : "Mean of binary carrier score (0/1) over all group samples (missing = 0)");

    HBox controls = new HBox(10, refreshButton, GroupStatTestControls.labeledChoice(testChoice));
    controls.setAlignment(Pos.CENTER_LEFT);

    filters.loadFrom(AnalysisSession.get().getThresholds());
    filters.setOnChanged(this::onFiltersChanged);

    getChildren().addAll(
        title, blurb, controls, statusLabel, methodStripLabel, filters.createPane());

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

  /** Variant-table results tab whose title shows the filtered gene count. */
  public void setResultsTab(Tab resultsTab) {
    this.resultsTab = resultsTab;
    updateResultsTabTitle(lastDisplayed);
  }

  /** Manual re-scan; when {@code selectTab} is true, switches to the results tab. */
  public void refresh(boolean selectTab) {
    DifferenceThresholds thr = filters.buildThresholds();
    AnalysisSession.get().setThresholds(thr);
    ComparisonContext.get().writeToDocument(ProjectSessionState.get().getDocument());
    ProjectSessionState.get().markDirty();
    MutationGroupComparisonRunner.run(mode, thr, selectTab);
  }

  @Override
  public void onStarted(AnalysisMode runMode) {
    refreshButton.setDisable(true);
    statusLabel.setText("Scanning genes with calls…");
  }

  @Override
  public void onFinished(AnalysisMode runMode, GroupStatResult filteredOrRaw, boolean selectResultsTab) {
    applyDisplayed(filteredOrRaw, selectResultsTab);
    updateReady();
  }

  @Override
  public void onFailed(AnalysisMode runMode, String message) {
    statusLabel.setText(message != null ? message : "Enrichment failed");
    updateReady();
  }

  @Override
  public void onCancelled(AnalysisMode runMode) {
    statusLabel.setText("Cancelled");
    updateReady();
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
    // No matrix yet — full Refresh will pick up the new test.
    statusLabel.setText("Test updated — Refresh to apply (no cached matrix yet)");
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

  private void applyDisplayed(GroupStatResult result, boolean notify) {
    lastDisplayed = result;
    resultTable.setResult(result);
    statusLabel.setText(result != null ? result.summary() : "");
    String strip = result != null ? result.methodStrip() : "";
    methodStripLabel.setText(strip != null ? strip : "");
    if (testChoice.getValue() != AnalysisSession.get().getGroupStatTest()) {
      refreshing = true;
      testChoice.setValue(AnalysisSession.get().getGroupStatTest());
      refreshing = false;
    }
    updateResultsTabTitle(result);
    if (notify && onResultReady != null && result != null) {
      onResultReady.accept(result);
    }
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
      refreshButton.setDisable(true);
      statusLabel.setText(block);
    } else if (!hasVcf) {
      refreshButton.setDisable(true);
      statusLabel.setText("Load VCF samples first");
    } else {
      refreshButton.setDisable(false);
      if (lastDisplayed == null) {
        statusLabel.setText(
            "Ready — auto-runs after Annotate all chromosomes / group changes");
      }
    }
  }
}
