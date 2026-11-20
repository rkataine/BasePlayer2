package org.baseplayer.analysis.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisRunner;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.BigWigCompanionRule;
import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.ComparisonUnit;
import org.baseplayer.analysis.DifferenceThresholds;
import org.baseplayer.analysis.GenomicScope;
import org.baseplayer.analysis.GroupStatResult;
import org.baseplayer.analysis.GroupStatTest;
import org.baseplayer.analysis.SignalSourceSpec;
import org.baseplayer.analysis.SignalValueKind;
import org.baseplayer.analysis.ValueAggregation;
import org.baseplayer.analysis.source.AlleleCnLohValueSource;
import org.baseplayer.analysis.source.MappedTableValueSource;
import org.baseplayer.analysis.source.BamMethylSource;
import org.baseplayer.analysis.source.BedScoreSource;
import org.baseplayer.analysis.source.BigWigValueSource;
import org.baseplayer.analysis.source.ValueSource;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Shared Signal mode state: filters, group comparison run controls, and results table.
 * Comparing values between sample groups for any numeric signal kind (BED / BigWig / methyl).
 */
public final class SignalAnalysisWorkspace {

  private final ComboBox<SignalValueKind> sourceCombo = new ComboBox<>();
  private final ComboBox<BigWigCompanionRule> bigWigRuleCombo = new ComboBox<>();
  private final TextField nameContainsField = new TextField();
  private final ComboBox<ComparisonUnit> unitCombo = new ComboBox<>();
  private final ComboBox<GenomicScope> scopeCombo = new ComboBox<>();
  private final Spinner<Integer> windowBpSpinner = new Spinner<>();
  private final ComboBox<ValueAggregation> aggCombo = new ComboBox<>();
  private final Label statusLabel = new Label();
  private final Label methodStripLabel = new Label();
  private final Label filterStatusLabel = new Label();
  private final Button runButton = new Button("Run group comparison");
  private final ChoiceBox<GroupStatTest> testChoice;
  private final GroupStatResultTable resultTable = new GroupStatResultTable();
  private final GroupComparisonFilterControls filterControls = new GroupComparisonFilterControls();
  private final HBox bigWigRow = new HBox(8);
  private final HBox windowRow = new HBox(8);
  private boolean refreshing;
  private Consumer<GroupStatResult> onResultUpdated;

  public SignalAnalysisWorkspace() {
    sourceCombo.getItems().setAll(SignalValueKind.values());
    bigWigRuleCombo.getItems().setAll(BigWigCompanionRule.values());
    unitCombo.getItems().setAll(ComparisonUnit.values());
    scopeCombo.getItems().setAll(GenomicScope.values());
    aggCombo.getItems().setAll(ValueAggregation.values());

    nameContainsField.setPromptText("substring");
    nameContainsField.setPrefWidth(100);

    windowBpSpinner.setValueFactory(
        new SpinnerValueFactory.IntegerSpinnerValueFactory(
            100, 10_000_000, SignalSourceSpec.DEFAULT_WINDOW_BP, 1000));
    windowBpSpinner.setEditable(true);
    windowBpSpinner.setPrefWidth(110);

    runButton.getStyleClass().add("primary-button");
    runButton.setOnAction(e -> runAnalysis());
    testChoice = GroupStatTestControls.createChoiceBox(this::onTestChanged);

    statusLabel.setWrapText(true);
    statusLabel.setStyle("-fx-font-size: 12px;");
    methodStripLabel.setWrapText(true);
    methodStripLabel.setStyle("-fx-font-size: 11px; -fx-opacity: 0.8;");
    filterStatusLabel.setWrapText(true);
    filterStatusLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.85;");
    resultTable.setMeanTooltip(
        "Mean of Signal values over samples in the test vector (see method strip for missing policy)");

    bigWigRow.setAlignment(Pos.CENTER_LEFT);
    bigWigRow.getChildren().addAll(
        new Label("BigWig pick"), bigWigRuleCombo,
        new Label("name"), nameContainsField);

    windowRow.setAlignment(Pos.CENTER_LEFT);
    windowRow.getChildren().addAll(new Label("Window bp"), windowBpSpinner);

    resultTable.setUnitColumnTitle(ComparisonUnit.GENE);
    resultTable.setOnGoto(row -> AnalysisSession.get().setActiveCanvasMode(AnalysisMode.SIGNAL));
    VBox.setVgrow(resultTable, Priority.ALWAYS);

    filterControls.setOnChanged(() -> {
      applyThresholdsToSession();
      refreshDisplayedResult();
      persistSettings();
    });
    wireListeners();
    loadFromSession();
    refreshAvailableSources();
    updateDependentVisibility();
    updateReadyState();
    filterControls.refreshDirectionLabels();

    GroupStatResult existing = AnalysisSession.get().getFilteredResult(AnalysisMode.SIGNAL);
    if (existing == null) {
      existing = AnalysisSession.get().getResult(AnalysisMode.SIGNAL);
    }
    if (existing != null) {
      applyResult(existing);
    }
  }

  public void setOnResultUpdated(Consumer<GroupStatResult> onResultUpdated) {
    this.onResultUpdated = onResultUpdated;
  }

  /** Re-scan loaded samples for Value kinds (e.g. after opening a table). */
  public void refreshFromData() {
    refreshAvailableSources();
    updateDependentVisibility();
    updateReadyState();
  }

  public GroupStatResultTable getResultTable() {
    return resultTable;
  }

  /** Signal Filters tool tab content. */
  public Node createFiltersPane() {
    Label title = new Label("Signal filters");
    title.getStyleClass().add("panel-title");
    Label blurb = new Label(
        "Choose which numeric values to compare between sample groups "
            + "(BED scores, BigWig / CNV companions, BAM methylation, opened tables). "
            + "Open tables via Add → Table / segments… and map columns once; "
            + "similar headers are remembered.");
    blurb.setWrapText(true);
    blurb.setStyle("-fx-font-size: 12px; -fx-opacity: 0.85;");

    FlowPane row1 = new FlowPane(10, 6);
    row1.getChildren().addAll(
        labeled("Value", sourceCombo),
        labeled("Compare as", unitCombo),
        labeled("Scope", scopeCombo),
        labeled("Aggregate", aggCombo));

    VBox root = new VBox(10, title, blurb, row1, bigWigRow, windowRow, filterStatusLabel);
    root.setPadding(new Insets(12, 16, 12, 16));
    root.getStyleClass().add("filter-panel");
    VBox.setVgrow(root, Priority.ALWAYS);
    return root;
  }

  /** Group comparison tool tab content (run / parameters / table filters). */
  public Node createComparisonPane() {
    Label title = new Label("Group comparison");
    title.getStyleClass().add("panel-title");
    Label blurb = new Label(
        "Compare values across sample groups for the current Signal filters. "
            + "With one named group, ungrouped samples form Other. "
            + "Adjust thresholds / Top N for the Signals table. "
            + "Turn the aggregate overlay on from the sample-column aggregate settings. "
            + "Export via the variant table Excel button (Group comparison sheet).");
    blurb.setWrapText(true);
    blurb.setStyle("-fx-font-size: 12px; -fx-opacity: 0.85;");

    HBox controls = new HBox(
        10, runButton, GroupStatTestControls.labeledChoice(testChoice));
    controls.setAlignment(Pos.CENTER_LEFT);

    VBox root = new VBox(
        10, title, blurb, controls, statusLabel, methodStripLabel, filterControls.createPane());
    root.setPadding(new Insets(12, 16, 12, 16));
    root.getStyleClass().add("filter-panel");
    VBox.setVgrow(root, Priority.ALWAYS);
    return root;
  }

  private void wireListeners() {
    sourceCombo.valueProperty().addListener((o, a, b) -> {
      updateDependentVisibility();
      updateReadyState();
      persistSettings();
    });
    unitCombo.valueProperty().addListener((o, a, b) -> {
      updateDependentVisibility();
      resultTable.setUnitColumnTitle(b != null ? b : ComparisonUnit.GENE);
      updateReadyState();
      persistSettings();
    });
    scopeCombo.valueProperty().addListener((o, a, b) -> {
      updateReadyState();
      persistSettings();
    });
    aggCombo.valueProperty().addListener((o, a, b) -> persistSettings());
    windowBpSpinner.valueProperty().addListener((o, a, b) -> persistSettings());
    bigWigRuleCombo.valueProperty().addListener((o, a, b) -> {
      nameContainsField.setDisable(b != BigWigCompanionRule.NAME_CONTAINS);
      updateReadyState();
      persistSettings();
    });
    nameContainsField.textProperty().addListener((o, a, b) -> {
      updateReadyState();
      persistSettings();
    });
    ComparisonContext.get().revisionProperty().addListener((o, a, b) -> {
      refreshAvailableSources();
      updateReadyState();
      filterControls.refreshDirectionLabels();
    });
  }

  private static HBox labeled(String text, Node node) {
    HBox box = new HBox(6, new Label(text), node);
    box.setAlignment(Pos.CENTER_LEFT);
    return box;
  }

  private void loadFromSession() {
    refreshing = true;
    try {
      SignalSourceSpec spec = AnalysisSession.get().getSignalSpec();
      sourceCombo.setValue(spec.getValueKind());
      unitCombo.setValue(spec.getUnit());
      scopeCombo.setValue(spec.getScope());
      aggCombo.setValue(spec.getAggregation());
      bigWigRuleCombo.setValue(spec.getBigWigRule());
      nameContainsField.setText(spec.getBigWigNameContains());
      windowBpSpinner.getValueFactory().setValue(spec.getWindowBp());
      filterControls.loadFrom(AnalysisSession.get().getThresholds());
      resultTable.setUnitColumnTitle(spec.getUnit());
    } finally {
      refreshing = false;
    }
  }

  private void updateDependentVisibility() {
    SignalValueKind kind = sourceCombo.getValue();
    boolean bigwig = kind == SignalValueKind.BIGWIG;
    bigWigRow.setVisible(bigwig);
    bigWigRow.setManaged(bigwig);
    nameContainsField.setDisable(bigWigRuleCombo.getValue() != BigWigCompanionRule.NAME_CONTAINS);

    ComparisonUnit unit = unitCombo.getValue();
    boolean windows = unit == ComparisonUnit.WINDOW;
    windowRow.setVisible(windows);
    windowRow.setManaged(windows);

    if ((kind == SignalValueKind.TABLE_VALUES || kind == SignalValueKind.TABLE_LOH)
        && unitCombo.getValue() != ComparisonUnit.GENE) {
      unitCombo.setValue(ComparisonUnit.GENE);
    }

    if (kind == SignalValueKind.BAM_METHYL && scopeCombo.getValue() != GenomicScope.CURRENT_VIEW) {
      scopeCombo.setValue(GenomicScope.CURRENT_VIEW);
    }
  }

  private void refreshAvailableSources() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    // Discover from all loaded tracks (not only the current analysis cohort),
    // so TABLE_LOH appears as soon as allele-CN tables are opened.
    List<SampleTrack> tracks = registry != null
        ? new ArrayList<>(registry.getSampleTracks())
        : List.of();
    List<SignalValueKind> available = new ArrayList<>();
    if (tracks.stream().anyMatch(BedScoreSource::trackHasScoredBed)) {
      available.add(SignalValueKind.BED_SCORES);
    }
    if (tracks.stream().anyMatch(t -> BedScoreSource.firstBed(t) != null)) {
      available.add(SignalValueKind.BED_ANY);
    }
    if (tracks.stream().anyMatch(BigWigValueSource::trackHasBigWig)) {
      available.add(SignalValueKind.BIGWIG);
    }
    if (tracks.stream().anyMatch(BamMethylSource::trackHasMethylBam)) {
      available.add(SignalValueKind.BAM_METHYL);
    }
    if (tracks.stream().anyMatch(MappedTableValueSource::trackHasValueTable)) {
      available.add(SignalValueKind.TABLE_VALUES);
    }
    if (tracks.stream().anyMatch(AlleleCnLohValueSource::trackHasAlleleCn)) {
      available.add(SignalValueKind.TABLE_LOH);
    }
    SignalValueKind previous = sourceCombo.getValue();
    sourceCombo.getItems().setAll(available);
    if (available.isEmpty()) {
      sourceCombo.setValue(null);
      return;
    }
    if (previous != null && available.contains(previous)) {
      sourceCombo.setValue(previous);
    } else if (available.contains(SignalValueKind.TABLE_LOH)) {
      sourceCombo.setValue(SignalValueKind.TABLE_LOH);
    } else if (available.contains(SignalValueKind.TABLE_VALUES)) {
      sourceCombo.setValue(SignalValueKind.TABLE_VALUES);
    } else {
      sourceCombo.getSelectionModel().selectFirst();
    }
  }

  private SignalSourceSpec buildSpec() {
    SignalSourceSpec spec = new SignalSourceSpec();
    SignalValueKind kind = sourceCombo.getValue() != null ? sourceCombo.getValue() : SignalValueKind.BED_SCORES;
    spec.setValueKind(kind);
    spec.setRequireBedScores(kind == SignalValueKind.BED_SCORES);
    ComparisonUnit unit = unitCombo.getValue() != null ? unitCombo.getValue() : ComparisonUnit.GENE;
    if (kind == SignalValueKind.TABLE_VALUES || kind == SignalValueKind.TABLE_LOH) {
      unit = ComparisonUnit.GENE;
    }
    spec.setUnit(unit);
    GenomicScope scope = scopeCombo.getValue() != null ? scopeCombo.getValue() : GenomicScope.ALL_CHROMS;
    if (kind == SignalValueKind.BAM_METHYL) {
      scope = GenomicScope.CURRENT_VIEW;
    }
    spec.setScope(scope);
    spec.setAggregation(aggCombo.getValue() != null ? aggCombo.getValue() : ValueAggregation.MEAN);
    spec.setBigWigRule(
        bigWigRuleCombo.getValue() != null ? bigWigRuleCombo.getValue() : BigWigCompanionRule.FIRST);
    spec.setBigWigNameContains(nameContainsField.getText());
    Integer w = windowBpSpinner.getValue();
    spec.setWindowBp(w != null ? w : SignalSourceSpec.DEFAULT_WINDOW_BP);
    return spec;
  }

  private DifferenceThresholds buildThresholds() {
    return filterControls.buildThresholds();
  }

  private void applyThresholdsToSession() {
    if (refreshing) {
      return;
    }
    AnalysisSession.get().setThresholds(buildThresholds());
  }

  private void persistSettings() {
    if (refreshing) {
      return;
    }
    AnalysisSession.get().setSignalSpec(buildSpec());
    applyThresholdsToSession();
    ComparisonContext.get().writeToDocument(ProjectSessionState.get().getDocument());
    ProjectSessionState.get().markDirty();
  }

  private void onTestChanged() {
    if (refreshing) {
      return;
    }
    SignalSourceSpec spec = buildSpec();
    String scoreLabel = spec.getValueKind() != null
        ? "Signal " + spec.getValueKind().name() + " / " + spec.getAggregation().name()
        : "Signal";
    GroupStatResult recomputed = GroupStatTestControls.recomputeIfPossible(
        AnalysisMode.SIGNAL, scoreLabel);
    if (recomputed != null) {
      applyResult(recomputed);
      org.baseplayer.draw.GenomicCanvas.update.set(!org.baseplayer.draw.GenomicCanvas.update.get());
      return;
    }
    statusLabel.setText("Test updated — Run group comparison to apply (no cached matrix yet)");
  }

  private void refreshDisplayedResult() {
    GroupStatResult filtered = AnalysisSession.get().getFilteredResult(AnalysisMode.SIGNAL);
    if (filtered == null) {
      filtered = AnalysisSession.get().getResult(AnalysisMode.SIGNAL);
    }
    if (filtered != null) {
      applyResult(filtered);
      org.baseplayer.draw.GenomicCanvas.update.set(!org.baseplayer.draw.GenomicCanvas.update.get());
    }
  }

  private void applyResult(GroupStatResult result) {
    resultTable.setResult(result);
    if (result != null) {
      statusLabel.setText(result.summary());
      methodStripLabel.setText(result.methodStrip() != null ? result.methodStrip() : "");
    } else {
      methodStripLabel.setText("");
    }
    if (testChoice.getValue() != AnalysisSession.get().getGroupStatTest()) {
      refreshing = true;
      testChoice.setValue(AnalysisSession.get().getGroupStatTest());
      refreshing = false;
    }
    if (onResultUpdated != null) {
      onResultUpdated.accept(result);
    }
  }

  private void updateReadyState() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    String block = ComparisonContext.get().runBlockReason(registry);
    List<SampleTrack> tracks = ComparisonContext.get().resolveTracks(registry);
    if (sourceCombo.getItems().isEmpty() || sourceCombo.getValue() == null) {
      runButton.setDisable(true);
      String ready = "Open signal data (table / BED / BigWig / methyl BAM) to compare";
      statusLabel.setText(ready);
      filterStatusLabel.setText(ready);
      return;
    }
    SignalSourceSpec spec = buildSpec();
    int usable = countUsable(tracks, spec);
    String ready;
    if (block != null) {
      runButton.setDisable(true);
      ready = block;
    } else if (usable < 2) {
      runButton.setDisable(true);
      ready = "Need ≥2 samples with matching values across sample groups";
    } else {
      runButton.setDisable(false);
      ready = "Ready — " + usable + " samples with values";
    }
    statusLabel.setText(ready);
    filterStatusLabel.setText(ready);
  }

  private void runAnalysis() {
    SignalSourceSpec spec = buildSpec();
    DifferenceThresholds thr = buildThresholds();
    ValueSource source = buildSource(spec);
    if (source == null) {
      statusLabel.setText("Could not build value source");
      return;
    }
    persistSettings();
    runButton.setDisable(true);
    statusLabel.setText("Running…");
    AnalysisSession.get().setActiveCanvasMode(AnalysisMode.SIGNAL);
    resultTable.setUnitColumnTitle(spec.getUnit());
    ThreadRunner.RunnerTask task = AnalysisRunner.run(
        AnalysisMode.SIGNAL,
        source,
        spec,
        thr,
        msg -> statusLabel.setText(msg != null ? msg : ""),
        result -> {
          runButton.setDisable(false);
          if (result != null) {
            applyResult(result);
            org.baseplayer.draw.GenomicCanvas.update.set(!org.baseplayer.draw.GenomicCanvas.update.get());
          }
          updateReadyState();
        });
    if (task != null) {
      task.onCancel(() -> javafx.application.Platform.runLater(() -> {
        runButton.setDisable(false);
        statusLabel.setText("Cancelled");
        updateReadyState();
      }));
    } else {
      runButton.setDisable(false);
    }
  }

  private ValueSource buildSource(SignalSourceSpec spec) {
    return switch (spec.getValueKind()) {
      case BED_SCORES -> new BedScoreSource(true);
      case BED_ANY -> new BedScoreSource(false);
      case BIGWIG -> new BigWigValueSource();
      case BAM_METHYL -> new BamMethylSource();
      case TABLE_VALUES -> new MappedTableValueSource();
      case TABLE_LOH -> new AlleleCnLohValueSource();
    };
  }

  private static int countUsable(List<SampleTrack> tracks, SignalSourceSpec spec) {
    if (tracks == null || spec == null) {
      return 0;
    }
    int n = 0;
    for (SampleTrack track : tracks) {
      if (isUsable(track, spec)) {
        n++;
      }
    }
    return n;
  }

  private static boolean isUsable(SampleTrack track, SignalSourceSpec spec) {
    return switch (spec.getValueKind()) {
      case BED_SCORES -> BedScoreSource.trackHasScoredBed(track);
      case BED_ANY -> BedScoreSource.firstBed(track) != null;
      case BIGWIG -> BigWigValueSource.trackHasMatchingBigWig(track, spec);
      case BAM_METHYL -> BamMethylSource.trackHasMethylBam(track);
      case TABLE_VALUES -> MappedTableValueSource.trackHasValueTable(track);
      case TABLE_LOH -> AlleleCnLohValueSource.trackHasAlleleCn(track);
    };
  }
}
