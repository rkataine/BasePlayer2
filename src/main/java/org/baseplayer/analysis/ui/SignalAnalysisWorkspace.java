package org.baseplayer.analysis.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.baseplayer.MainApp;
import org.baseplayer.analysis.AllChromAction;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisRunner;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.export.AnalysisExportDialog;
import org.baseplayer.analysis.export.AnalysisExportKind;
import org.baseplayer.analysis.export.AnalysisExportService;
import org.baseplayer.components.AppDialog;
import org.baseplayer.analysis.signal.BigWigCompanionRule;
import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.ComparisonUnit;
import org.baseplayer.analysis.DifferenceThresholds;
import org.baseplayer.analysis.grid.CompactTableGrid;
import org.baseplayer.analysis.EffectMode;
import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.GenomicScope;
import org.baseplayer.analysis.groupstat.GroupStatEngine;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.groupstat.GroupStatTest;
import org.baseplayer.analysis.pattern.SamplePatternEngine;
import org.baseplayer.analysis.signal.SignalAnnotator;
import org.baseplayer.analysis.signal.SignalChannel;
import org.baseplayer.analysis.signal.SignalChannels;
import org.baseplayer.analysis.signal.SignalDataset;
import org.baseplayer.analysis.signal.SignalEventDirection;
import org.baseplayer.analysis.signal.SignalSourceSpec;
import org.baseplayer.analysis.signal.SignalValueKind;
import org.baseplayer.analysis.signal.SignalValueStats;
import org.baseplayer.analysis.signal.SignalValueThresholds;
import org.baseplayer.analysis.signal.SignalValueType;
import org.baseplayer.analysis.ThresholdFilter;
import org.baseplayer.analysis.grid.ValueGrid;
import org.baseplayer.analysis.ValueAggregation;
import org.baseplayer.io.table.SharedMatrixLoci;
import org.baseplayer.analysis.source.AlleleCnLohValueSource;
import org.baseplayer.analysis.source.BamMethylSource;
import org.baseplayer.analysis.source.BedScoreSource;
import org.baseplayer.analysis.source.BigWigValueSource;
import org.baseplayer.analysis.source.MappedTableValueSource;
import org.baseplayer.analysis.source.MultiChannelMappedTableSource;
import org.baseplayer.analysis.source.ValueSource;
import org.baseplayer.components.ColoredToggle;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.io.table.MappedTableColumnMapping;
import org.baseplayer.io.table.MappedTableData;
import org.baseplayer.io.table.MappedTableOpen;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Signal mode: shared column mapping + value thresholds, optional group comparison,
 * and a results area matching Point/SV chrome (annotate-all + search).
 */
public final class SignalAnalysisWorkspace {

  /** What the toolbar “all chromosomes” button runs for the active Signal tool tab. */
  private final Label mappingSummaryLabel = new Label();
  private final Button editColumnsButton = new Button("Edit columns…");
  private final Label observedRangeLabel = new Label();
  private final VBox channelFiltersBox = new VBox(10);
  private final Map<SignalEventDirection, ColoredToggle> eventTypeToggles =
      new java.util.EnumMap<>(SignalEventDirection.class);
  private boolean eventTypeFilterSuppress;
  private final ColoredToggle valueThrEnabled = new ColoredToggle("Filter by value");
  private final ColoredToggle useMinBound = new ColoredToggle("Use Min");
  private final ColoredToggle useMaxBound = new ColoredToggle("Use Max");
  private final Spinner<Double> minValueSpinner = new Spinner<>();
  private final Spinner<Double> maxValueSpinner = new Spinner<>();
  private final Spinner<Double> middleSpinner = new Spinner<>();
  private final Spinner<Double> minAbsFromMiddleSpinner = new Spinner<>();
  private final Spinner<Integer> maxRowsSpinner = new Spinner<>();
  private final VBox singleChannelFilterBox = new VBox(8);
  private final Button applyThresholdsButton = new Button("Apply");
  private final Label annotateStatusLabel = new Label();
  private final Label statusLabel = new Label();
  private final Button runButton = new Button("Run");
  private final Button annotateAllButton = new Button("Annotate All Chromosomes");
  private final Button exportButton = new Button();
  private final TextField tableSearchField = new TextField();
  private final ChoiceBox<GroupStatTest> testChoice;
  /** Observed min/max per channel id — seeds spinners without enabling filters. */
  private Map<String, SignalValueStats> channelObservedStats = Map.of();
  /** Channel filter spinners — committed on Apply (editable text does not auto-commit). */
  private final List<Spinner<?>> thresholdSpinners = new ArrayList<>();
  /** Sync channel UI controls → {@link SignalChannel#setThresholds} without refiltering. */
  private final List<Runnable> channelThresholdSyncs = new ArrayList<>();
  /** Annotation gene list (Signals results tab) — never shows group-comparison chrome. */
  private final GroupStatResultTable annotationTable = new GroupStatResultTable();
  /** Group comparison results tab only. */
  private final GroupStatResultTable comparisonTable = new GroupStatResultTable();
  private TabPane resultsTabPane;
  private Tab signalsResultsTab;
  private Tab groupResultsTab;
  private final GroupComparisonFilterControls filterControls = new GroupComparisonFilterControls();
  private final PatternDiscoveryControls patternControls = new PatternDiscoveryControls();
  private final HBox hintButtons;
  private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(200));
  private boolean refreshing;
  private ThreadRunner.RunnerTask annotateTask;
  private Consumer<GroupStatResult> onResultUpdated;
  private AnalysisBusyRunner analysisBusyRunner;
  private SignalValueKind lastDefaultsKind;
  private List<SignalChannel> activeChannels = List.of();
  /** Owning named dataset; null only for legacy single-slot tests. */
  private final SignalDataset dataset;

  public SignalAnalysisWorkspace() {
    this(null);
  }

  public SignalAnalysisWorkspace(SignalDataset dataset) {
    this.dataset = dataset;
    minValueSpinner.setValueFactory(
        new SpinnerValueFactory.DoubleSpinnerValueFactory(-1e9, 1e9, 0, 0.1));
    minValueSpinner.setEditable(true);
    minValueSpinner.setPrefWidth(90);
    maxValueSpinner.setValueFactory(
        new SpinnerValueFactory.DoubleSpinnerValueFactory(-1e9, 1e9, 1, 0.1));
    maxValueSpinner.setEditable(true);
    maxValueSpinner.setPrefWidth(90);
    middleSpinner.setValueFactory(
        new SpinnerValueFactory.DoubleSpinnerValueFactory(-1e9, 1e9, 0, 0.1));
    middleSpinner.setEditable(true);
    middleSpinner.setPrefWidth(90);
    minAbsFromMiddleSpinner.setValueFactory(
        new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 1e9, 0, 0.1));
    minAbsFromMiddleSpinner.setEditable(true);
    minAbsFromMiddleSpinner.setPrefWidth(90);
    maxRowsSpinner.setValueFactory(
        new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 1_000_000, 0, 100));
    maxRowsSpinner.setEditable(true);
    maxRowsSpinner.setPrefWidth(90);

    // Ready to apply once Filter is armed (Apply auto-arms Filter when these are checked).
    useMinBound.setSelected(true);
    useMaxBound.setSelected(true);
    Tooltip.install(valueThrEnabled, new Tooltip(
        "Master switch for value filters. Apply also turns this on when Use Min/Max or |Δ| is set."));
    Tooltip.install(useMinBound, new Tooltip("Inclusive lower bound from Min spinner"));
    Tooltip.install(useMaxBound, new Tooltip("Inclusive upper bound from Max spinner"));
    Tooltip.install(minValueSpinner, new Tooltip("Inclusive lower bound (only if Use Min)"));
    Tooltip.install(maxValueSpinner, new Tooltip("Inclusive upper bound (only if Use Max)"));
    Tooltip.install(middleSpinner, new Tooltip(
        "Baseline (e.g. 1 for allele CN, 0 for expression)"));
    Tooltip.install(minAbsFromMiddleSpinner, new Tooltip(
        "Keep only if |value − Middle| ≥ this (0 = off)"));
    Tooltip.install(maxRowsSpinner, new Tooltip("0 = unlimited"));
    Tooltip.install(editColumnsButton, new Tooltip(
        "Reopen column mapping and apply the same roles to every open table"));
    channelFiltersBox.setFillWidth(true);

    Button useP5 = new Button("Min←p5");
    Button useP95 = new Button("Max←p95");
    Button useMedianMiddle = new Button("Middle←median");
    useP5.setOnAction(e -> applyObservedHint(Hint.MIN_P5));
    useP95.setOnAction(e -> applyObservedHint(Hint.MAX_P95));
    useMedianMiddle.setOnAction(e -> applyObservedHint(Hint.MIDDLE_MEDIAN));
    Tooltip.install(useP5, new Tooltip("Set Min from observed 5th percentile and enable Use Min"));
    Tooltip.install(useP95, new Tooltip("Set Max from observed 95th percentile and enable Use Max"));
    Tooltip.install(useMedianMiddle, new Tooltip("Set Middle from observed median"));
    hintButtons = new HBox(6, useP5, useP95, useMedianMiddle);
    hintButtons.setAlignment(Pos.CENTER_LEFT);

    applyThresholdsButton.getStyleClass().add("primary-button");
    applyThresholdsButton.setOnAction(e -> applyValueFilters());
    Tooltip.install(applyThresholdsButton, new Tooltip(
        "Apply value filters to the Annotations table (refilters collected data; no recollect)"));

    runButton.setText("Run differential");
    runButton.setText("Run differential");
    runButton.getStyleClass().add("primary-button");
    runButton.setOnAction(e -> runAnalysis());
    Tooltip.install(runButton, new Tooltip(
        "Differential analysis between sample groups on the current chromosome "
            + "(moderated t / Welch / rank + BH). Uses the current threshold settings."));
    testChoice = GroupStatTestControls.createChoiceBox(this::onTestChanged);

    annotateAllButton.getStyleClass().add("primary-button");
    annotateAllButton.setOnAction(e -> runAllChromosomes(AllChromAction.ANNOTATE, null));
    setAllChromosomesAction(AllChromAction.ANNOTATE);

    tableSearchField.setPromptText("Search genes, positions…");
    tableSearchField.getStyleClass().addAll("filter-field", "table-search-field");
    tableSearchField.setPrefWidth(180);
    tableSearchField.setMaxWidth(180);
    searchDebounce.setOnFinished(e -> {
      String q = tableSearchField.getText();
      annotationTable.setSearchQuery(q);
      comparisonTable.setSearchQuery(q);
    });
    tableSearchField.textProperty().addListener((o, a, b) -> {
      annotationTable.rememberSearchQuery(b);
      comparisonTable.rememberSearchQuery(b);
      searchDebounce.stop();
      searchDebounce.playFromStart();
    });

    mappingSummaryLabel.setWrapText(true);
    mappingSummaryLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.9;");
    observedRangeLabel.setWrapText(true);
    observedRangeLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.9;");
    annotateStatusLabel.setWrapText(true);
    annotateStatusLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.85;");
    statusLabel.setWrapText(true);
    statusLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.85;");
    annotationTable.setMeanTooltip("Signal value for this sample");
    annotationTable.setUnitColumnTitle(ComparisonUnit.GENE);
    annotationTable.setEmptyMessage("Open signal data to annotate");
    annotationTable.setOnGoto(row -> AnalysisSession.get().setActiveCanvasMode(AnalysisMode.SIGNAL));
    VBox.setVgrow(annotationTable, Priority.ALWAYS);
    comparisonTable.setMeanTooltip("Mean of Signal values over group samples");
    comparisonTable.setUnitColumnTitle(ComparisonUnit.GENE);
    comparisonTable.setEmptyMessage("Run group comparison");
    comparisonTable.setOnGoto(row -> AnalysisSession.get().setActiveCanvasMode(AnalysisMode.SIGNAL));
    VBox.setVgrow(comparisonTable, Priority.ALWAYS);

    editColumnsButton.setOnAction(e -> editColumnsForAllTables());

    filterControls.setOnChanged(() -> {
      applyThresholdsToSession();
      refreshComparisonResult();
      persistSettings();
    });
    filterControls.setOnShowHeatmap(this::showGroupComparisonHeatmap);
    wireListeners();
    loadFromSession();
    refreshSignalChrome();
    updateReadyState();
    filterControls.refreshDirectionLabels();

    GroupStatResult annotated = AnalysisSession.get().getAnnotationResult();
    if (annotated != null) {
      applyAnnotationResult(annotated);
      refreshObservedRangeFromSession();
    }
    GroupStatResult compared = AnalysisSession.get().getFilteredResult(AnalysisMode.SIGNAL);
    if (compared != null && !SignalAnnotator.isAnnotationResult(compared)) {
      applyComparisonResult(compared);
    } else {
      refreshGroupHeatmapEnabled();
    }
  }

  private enum Hint { MIN_P5, MAX_P95, MIDDLE_MEDIAN }

  public SignalDataset getDataset() {
    return dataset;
  }

  public String getDatasetId() {
    return dataset != null ? dataset.getId() : null;
  }

  /** Ensure this workspace's session slice is active before read/write. */
  public void activateSessionSlice() {
    if (dataset != null) {
      AnalysisSession.get().setActiveSignalDatasetId(dataset.getId());
    }
  }

  public void setOnResultUpdated(Consumer<GroupStatResult> onResultUpdated) {
    this.onResultUpdated = onResultUpdated;
  }

  public void refreshFromData() {
    activateSessionSlice();
    refreshSignalChrome();
    updateReadyState();
    // Session restore installs finished results — do not wipe them by re-annotating.
    if (AnalysisSession.get().getAnnotationResult() != null
        || AnalysisSession.get().getResult(AnalysisMode.SIGNAL) != null) {
      hydrateTablesFromSession();
      return;
    }
    annotateCurrentChromosome();
  }

  /**
   * Push session annotation / DE / pattern into tables without collecting or
   * re-running group stats (project open with analysis cache).
   */
  public void hydrateTablesFromSession() {
    activateSessionSlice();
    refreshSignalChrome();
    updateReadyState();
    GroupStatResult annotated = AnalysisSession.get().getAnnotationResult();
    if (annotated != null) {
      applyAnnotationResult(annotated);
      refreshObservedRangeFromSession();
    }
    GroupStatResult compared = AnalysisSession.get().getFilteredResult(AnalysisMode.SIGNAL);
    if (compared != null && !SignalAnnotator.isAnnotationResult(compared)) {
      applyComparisonResult(compared);
    } else {
      refreshGroupHeatmapEnabled();
    }
    SamplePatternEngine.Result pattern = AnalysisSession.get().getLastPatternResult();
    if (pattern != null) {
      patternControls.refreshApplyEnabled();
      if (pattern.summary() != null && !pattern.summary().isBlank()) {
        patternControls.setStatus(pattern.summary());
      }
    }
  }

  /** Annotation (Signals) results table. */
  public GroupStatResultTable getResultTable() {
    return annotationTable;
  }

  public GroupStatResultTable getComparisonResultTable() {
    return comparisonTable;
  }

  /**
   * Refresh Signals table + status from the session annotation (after Sample Comparison Apply).
   * Summary includes {@code Sample comparison: N / M units} when constraints are active.
   */
  public void refreshAnnotationFromSession() {
    GroupStatResult annotated = AnalysisSession.get().getAnnotationResult();
    applyAnnotationResult(annotated);
  }

  /** Shared with Point/SV busy-overlay lock targets. */
  public Button getAnnotateAllButton() {
    return annotateAllButton;
  }

  /**
   * Two-panel Signal tool chrome:
   * left = value thresholds + observed range; right bottom = columns.
   */
  public Node createSignalPane() {
    Label thrHeader = new Label("Value thresholds");
    thrHeader.getStyleClass().add("section-header");
    thrHeader.setTooltip(new Tooltip(
        "Optional filters on annotated units. Off by default — edit, then Apply."));
    HBox minMax = new HBox(8,
        useMinBound, minValueSpinner,
        useMaxBound, maxValueSpinner,
        labeledSpin("Top N", maxRowsSpinner));
    minMax.setAlignment(Pos.CENTER_LEFT);
    HBox middleRow = new HBox(8,
        labeledSpin("Middle", middleSpinner),
        labeledSpin("Min |Δ|", minAbsFromMiddleSpinner));
    middleRow.setAlignment(Pos.CENTER_LEFT);

    Label observedHeader = new Label("Observed values");
    observedHeader.getStyleClass().add("section-header");

    singleChannelFilterBox.getChildren().setAll(valueThrEnabled, minMax, middleRow);
    VBox left = new VBox(8,
        thrHeader,
        channelFiltersBox,
        singleChannelFilterBox,
        applyThresholdsButton,
        new Separator(),
        observedHeader,
        observedRangeLabel,
        hintButtons,
        annotateStatusLabel);
    left.setPadding(new Insets(16));
    left.getStyleClass().add("filter-panel");

    Label columnsHeader = new Label("Columns");
    columnsHeader.getStyleClass().add("section-header");
    columnsHeader.setTooltip(new Tooltip(
        "Shared column roles for all open tables"));

    javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
    VBox.setVgrow(spacer, Priority.ALWAYS);

    VBox right = new VBox(8,
        spacer,
        columnsHeader,
        editColumnsButton,
        mappingSummaryLabel);
    right.setPadding(new Insets(16));
    right.getStyleClass().add("filter-panel");

    SplitPane split = new SplitPane(wrapScroll(left), wrapScroll(right));
    split.setDividerPositions(0.52);
    split.getStyleClass().add("tab-content-split");
    return split;
  }

  /**
   * Results area matching Point/SV: Annotate All + Search above tabs.
   * Signals = annotation only; Group comparison = Run output only.
   */
  public Node createResultsArea() {
    setupExportButton();
    HBox toolbar = new HBox(8, annotateAllButton, exportButton, tableSearchField);
    toolbar.setAlignment(Pos.CENTER_LEFT);
    toolbar.getStyleClass().add("variant-table-toolbar");
    toolbar.setPadding(new Insets(4, 8, 4, 8));

    resultsTabPane = new TabPane();
    resultsTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    resultsTabPane.getStyleClass().add("results-tabs");
    signalsResultsTab = new Tab("Annotations", annotationTable);
    signalsResultsTab.setClosable(false);
    signalsResultsTab.setTooltip(new Tooltip(
        "Annotated units (threshold-passing events). Expand lists deviant samples only."));
    groupResultsTab = new Tab("Group comparison", comparisonTable);
    groupResultsTab.setClosable(false);
    groupResultsTab.setTooltip(new Tooltip(
        "Statistical group comparison of events (same engine as Point/SV Group comparison)."));
    resultsTabPane.getTabs().addAll(signalsResultsTab, groupResultsTab);
    VBox.setVgrow(resultsTabPane, Priority.ALWAYS);

    VBox area = new VBox(toolbar, resultsTabPane);
    area.getStyleClass().add("variant-table-area");
    VBox.setVgrow(area, Priority.ALWAYS);
    return area;
  }

  /** Annotate the canvas chromosome only (same contract as Point/SV on open/navigate). */
  public ThreadRunner.RunnerTask annotateCurrentChromosome() {
    return runAnnotate(GenomicScope.CURRENT_CHROM, null);
  }

  /** Full-genome collect (Point/SV “Annotate All Chromosomes” counterpart). */
  public ThreadRunner.RunnerTask annotateAllChromosomes() {
    return runAllChromosomes(AllChromAction.ANNOTATE, null);
  }

  /**
   * Full-genome collect with optional completion callback (busy overlay).
   *
   * @return background task, or {@code null} if nothing to run
   */
  public ThreadRunner.RunnerTask annotateAllChromosomes(Runnable onComplete) {
    return runAllChromosomes(AllChromAction.ANNOTATE, onComplete);
  }

  /** Update toolbar button text/tooltip for the active Signal tool tab. */
  public void setAllChromosomesAction(AllChromAction action) {
    AllChromAction a = action != null ? action : AllChromAction.ANNOTATE;
    annotateAllButton.setText(a.buttonText());
    Tooltip.install(annotateAllButton, new Tooltip(a.tooltip()));
  }

  /** Resolve action from the selected Signal tool-tab title. */
  public AllChromAction actionForToolTab(Tab toolTab) {
    return AllChromAction.fromToolTabText(toolTab != null ? toolTab.getText() : null);
  }

  /**
   * Run the all-chromosomes action for the active tool tab.
   *
   * @return background task, or {@code null} if nothing to run / finished inline
   */
  public ThreadRunner.RunnerTask runAllChromosomes(AllChromAction action, Runnable onComplete) {
    AllChromAction a = action != null ? action : AllChromAction.ANNOTATE;
    return switch (a) {
      case ANNOTATE -> {
        annotateStatusLabel.setText("Annotating all chromosomes…");
        yield runAnnotate(GenomicScope.ALL_CHROMS, () -> {
          selectResultsTab(false);
          if (onComplete != null) {
            onComplete.run();
          }
        });
      }
      case DISCOVER -> runDiscoverAllChromosomes(onComplete);
      case COMPARE -> runCompareAllChromosomes(onComplete);
    };
  }

  private void selectResultsTab(boolean groupComparison) {
    if (resultsTabPane == null) {
      return;
    }
    Tab target = groupComparison ? groupResultsTab : signalsResultsTab;
    if (target != null) {
      resultsTabPane.getSelectionModel().select(target);
    }
  }

  /**
   * Build the active Signal spec with an explicit genomic scope.
   * Used by annotate entry points and unit tests.
   */
  public SignalSourceSpec buildSpecForScope(GenomicScope scope) {
    SignalSourceSpec spec = resolveSpec();
    if (scope != null) {
      spec.setScope(scope);
    }
    return spec;
  }

  @Deprecated
  public Node createFiltersPane() {
    return createSignalPane();
  }

  /**
   * Embed pattern-discovery controls at the top of Signal Sample Comparison’s right pane
   * (above sample tags). Group comparison tab keeps moderated DE controls.
   */
  public void mountPatternsInto(VBox host) {
    if (host == null) {
      return;
    }
    patternControls.setOnDiscover(this::discoverPatterns);
    patternControls.setOnApplyGroups(this::applyPatternGroups);
    patternControls.setOnShowHeatmap(this::showPatternHeatmap);
    host.getChildren().setAll(patternControls.createEmbedded());
    patternControls.refreshApplyEnabled();
  }

  /**
   * Optional Analysis Manager busy gate (in-window dimmer + loading popup ownership).
   * Wired from {@code AnalysisManagerController} so Discover / Run match annotate-all.
   */
  public void setAnalysisBusyRunner(AnalysisBusyRunner runner) {
    this.analysisBusyRunner = runner;
  }

  public Node createComparisonPane() {
    return filterControls.createWorkspace(runButton, testChoice, statusLabel);
  }

  private static ScrollPane wrapScroll(Node content) {
    ScrollPane scroll = new ScrollPane(content);
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    scroll.getStyleClass().add("filter-scroll");
    return scroll;
  }

  private static HBox labeledSpin(String text, Spinner<?> spinner) {
    Label label = new Label(text);
    label.getStyleClass().add("filter-slider-label");
    HBox box = new HBox(4, label, spinner);
    box.setAlignment(Pos.CENTER_LEFT);
    return box;
  }

  private void wireListeners() {
    // Value-threshold edits apply only via Apply (editable spinners do not commit until then).
    ComparisonContext.get().revisionProperty().addListener((o, a, b) -> {
      refreshSignalChrome();
      updateReadyState();
      filterControls.refreshDirectionLabels();
      annotateCurrentChromosome();
    });
  }

  /**
   * Commit spinner text, mask failing cells on gene-matrix SSOT (MappedTableData),
   * then refilter the Signals table. Canvas redraws the same masked floats.
   */
  private void applyValueFilters() {
    if (refreshing) {
      return;
    }
    commitAllThresholdSpinners();
    armFilterFromBoundControls();
    for (Runnable sync : channelThresholdSyncs) {
      sync.run();
    }
    AnalysisSession.get().setSignalSpec(buildSpec());
    SignalValueThresholds thr = buildValueThresholds();
    CompactTableGrid grid = maskCompactTables(thr);
    if (grid != null) {
      AnalysisSession.get().replaceSignalGrid(grid);
    }
    AnalysisSession.get().setValueThresholds(thr);
    GroupStatResult annotated = AnalysisSession.get().getAnnotationResult();
    if (annotated != null) {
      applyAnnotationResult(annotated);
      org.baseplayer.draw.GenomicCanvas.update.set(!org.baseplayer.draw.GenomicCanvas.update.get());
    } else {
      annotateStatusLabel.setText("No annotation yet — Annotate All first, then Apply filters");
    }
    persistSettings();
  }

  /**
   * NaN-mask compact gene-matrix sample columns so canvas + annotate share one store.
   * When filter is off, restore raw values. Returns the grid, or null if not gene-matrix SSOT.
   */
  private CompactTableGrid maskCompactTables(SignalValueThresholds thr) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    SignalSourceSpec spec = resolveSpec();
    List<SampleTrack> tracks = usableTracks(registry, spec);
    CompactTableGrid grid = CompactTableGrid.tryCreate(tracks, spec.getScope());
    if (grid == null) {
      return null;
    }
    SignalValueThresholds effective = thr != null ? thr : new SignalValueThresholds();
    boolean mask = effective.isEnabled();
    for (org.baseplayer.io.table.MappedTableData table : grid.tables()) {
      if (!mask) {
        table.restoreFromRawBackup();
        continue;
      }
      table.applyValueMask(v -> !effective.passes(v));
    }
    return grid;
  }

  /**
   * Use Min / Use Max / Min |Δ| imply the user wants filtering — turn Filter on so
   * {@link SignalValueThresholds#passes} actually applies bounds.
   */
  private void armFilterFromBoundControls() {
    if (!activeChannels.isEmpty()) {
      return;
    }
    Double minAbs = minAbsFromMiddleSpinner.getValue();
    boolean wantBounds = useMinBound.isSelected()
        || useMaxBound.isSelected()
        || (minAbs != null && minAbs > 0.0);
    if (wantBounds) {
      valueThrEnabled.setSelected(true);
    }
  }

  private void commitAllThresholdSpinners() {
    commitSpinner(minValueSpinner);
    commitSpinner(maxValueSpinner);
    commitSpinner(middleSpinner);
    commitSpinner(minAbsFromMiddleSpinner);
    commitSpinner(maxRowsSpinner);
    for (Spinner<?> spinner : thresholdSpinners) {
      commitSpinner(spinner);
    }
  }

  private static <T> void commitSpinner(Spinner<T> spinner) {
    if (spinner == null || spinner.getValueFactory() == null || spinner.getEditor() == null) {
      return;
    }
    try {
      String text = spinner.getEditor().getText();
      T converted = spinner.getValueFactory().getConverter().fromString(text);
      spinner.getValueFactory().setValue(converted);
    } catch (RuntimeException ignored) {
      // keep previous value
    }
  }

  private void applyKindDefaultThresholds(SignalValueKind kind) {
    if (kind == null || !activeChannels.isEmpty()) {
      // Multi-channel defaults come from SignalValueType on each channel.
      return;
    }
    SignalValueThresholds defaults = SignalValueThresholds.defaultsFor(kind);
    refreshing = true;
    try {
      // Filters stay off; Use Min/Max stay ready so Apply after editing Min works.
      valueThrEnabled.setSelected(false);
      useMinBound.setSelected(true);
      useMaxBound.setSelected(true);
      if (defaults.getMinValue() != null) {
        minValueSpinner.getValueFactory().setValue(defaults.getMinValue());
      }
      if (defaults.getMaxValue() != null) {
        maxValueSpinner.getValueFactory().setValue(defaults.getMaxValue());
      }
      middleSpinner.getValueFactory().setValue(defaults.getMiddle());
      minAbsFromMiddleSpinner.getValueFactory().setValue(defaults.getMinAbsFromMiddle());
    } finally {
      refreshing = false;
    }
    AnalysisSession.get().setValueThresholds(buildValueThresholds());
  }

  private void applyObservedHint(Hint hint) {
    SignalValueStats stats = currentObservedStats();
    if (stats == null || stats.isEmpty()) {
      return;
    }
    refreshing = true;
    try {
      valueThrEnabled.setSelected(true);
      switch (hint) {
        case MIN_P5 -> {
          minValueSpinner.getValueFactory().setValue(stats.p5());
          useMinBound.setSelected(true);
        }
        case MAX_P95 -> {
          maxValueSpinner.getValueFactory().setValue(stats.p95());
          useMaxBound.setSelected(true);
        }
        case MIDDLE_MEDIAN -> middleSpinner.getValueFactory().setValue(stats.p50());
      }
    } finally {
      refreshing = false;
    }
    applyValueFilters();
  }

  private void loadFromSession() {
    refreshing = true;
    try {
      filterControls.loadFrom(AnalysisSession.get().getThresholds());
      loadValueThresholds(AnalysisSession.get().getValueThresholds());
    } finally {
      refreshing = false;
    }
  }

  private void loadValueThresholds(SignalValueThresholds thr) {
    SignalValueThresholds t = thr != null ? thr : new SignalValueThresholds();
    valueThrEnabled.setSelected(t.isEnabled());
    // When filter is off, keep Use Min/Max checked so the next Apply arms bounds.
    useMinBound.setSelected(t.getMinValue() != null || !t.isEnabled());
    useMaxBound.setSelected(t.getMaxValue() != null || !t.isEnabled());
    if (t.getMinValue() != null) {
      minValueSpinner.getValueFactory().setValue(t.getMinValue());
    }
    if (t.getMaxValue() != null) {
      maxValueSpinner.getValueFactory().setValue(t.getMaxValue());
    }
    middleSpinner.getValueFactory().setValue(t.getMiddle());
    minAbsFromMiddleSpinner.getValueFactory().setValue(t.getMinAbsFromMiddle());
    maxRowsSpinner.getValueFactory().setValue(t.getMaxRows());
  }

  private void refreshSignalChrome() {
    SignalSourceSpec spec = resolveSpec();
    annotationTable.setUnitColumnTitle(spec.getUnit());
    comparisonTable.setUnitColumnTitle(spec.getUnit());
    activeChannels = spec.getChannels();
    rebuildChannelFilterUi();
    boolean multi = !activeChannels.isEmpty();
    singleChannelFilterBox.setVisible(!multi);
    singleChannelFilterBox.setManaged(!multi);
    channelFiltersBox.setVisible(multi);
    channelFiltersBox.setManaged(multi);
    if (!multi && spec.getValueKind() != null && spec.getValueKind() != lastDefaultsKind) {
      lastDefaultsKind = spec.getValueKind();
      applyKindDefaultThresholds(spec.getValueKind());
    }
    refreshMappingSummary();
    refreshObservedRangeFromSession();
  }

  private void rebuildChannelFilterUi() {
    channelFiltersBox.getChildren().clear();
    thresholdSpinners.clear();
    channelThresholdSyncs.clear();
    eventTypeToggles.clear();
    if (activeChannels.isEmpty()) {
      return;
    }
    if (SignalChannels.isAlleleCnOnly(activeChannels)) {
      channelFiltersBox.getChildren().add(buildEventTypeFilterBlock());
    }
    Label hint = new Label(
        SignalChannels.isAlleleCnOnly(activeChannels)
            ? "Event types filter the Annotations table immediately. Value filters below need Apply."
            : "Keep gene if any channel passes. Filters off by default — edit, then Apply.");
    hint.setWrapText(true);
    hint.setStyle("-fx-font-size: 11px; -fx-opacity: 0.85;");
    channelFiltersBox.getChildren().add(hint);
    for (SignalChannel ch : activeChannels) {
      channelFiltersBox.getChildren().add(buildChannelFilterBlock(ch));
    }
  }

  private Node buildEventTypeFilterBlock() {
    Label header = new Label("Event types");
    header.getStyleClass().add("section-header");
    header.setTooltip(new Tooltip(
        "Show Gain / Loss / LOH in the Annotations table (like Point/SV type filters)."));

    ColoredToggle allToggle = new ColoredToggle("All");
    Set<SignalEventDirection> allowed =
        AnalysisSession.get().getSignalSpec().getAllowedEventDirections();
    FlowPane types = new FlowPane(8, 6);
    types.setAlignment(Pos.CENTER_LEFT);

    eventTypeFilterSuppress = true;
    try {
      for (SignalEventDirection dir : SignalEventDirection.filterTypes()) {
        int[] rgb = dir.rgb();
        ColoredToggle toggle = new ColoredToggle(
            dir.filterLabel(), Color.rgb(rgb[0], rgb[1], rgb[2]));
        toggle.setSelected(allowed.contains(dir));
        toggle.setPrefWidth(100);
        toggle.setMaxWidth(120);
        toggle.selectedProperty().addListener((o, a, b) -> {
          if (eventTypeFilterSuppress) {
            return;
          }
          updateEventTypeAllToggle(allToggle);
          applyEventTypeFilters();
        });
        eventTypeToggles.put(dir, toggle);
        types.getChildren().add(toggle);
      }
      allToggle.setSelected(
          eventTypeToggles.values().stream().allMatch(ColoredToggle::isSelected));
      allToggle.selectedProperty().addListener((o, a, selected) -> {
        if (eventTypeFilterSuppress || selected == null) {
          return;
        }
        eventTypeFilterSuppress = true;
        try {
          for (ColoredToggle t : eventTypeToggles.values()) {
            t.setSelected(selected);
          }
        } finally {
          eventTypeFilterSuppress = false;
        }
        applyEventTypeFilters();
      });
    } finally {
      eventTypeFilterSuppress = false;
    }

    HBox allRow = new HBox(8, allToggle);
    allRow.setAlignment(Pos.CENTER_LEFT);
    VBox box = new VBox(6, header, allRow, types);
    return box;
  }

  private void updateEventTypeAllToggle(ColoredToggle allToggle) {
    if (allToggle == null || eventTypeToggles.isEmpty()) {
      return;
    }
    boolean allOn = eventTypeToggles.values().stream().allMatch(ColoredToggle::isSelected);
    eventTypeFilterSuppress = true;
    try {
      allToggle.setSelected(allOn);
    } finally {
      eventTypeFilterSuppress = false;
    }
  }

  private void applyEventTypeFilters() {
    if (refreshing || eventTypeFilterSuppress) {
      return;
    }
    EnumSet<SignalEventDirection> allowed = EnumSet.noneOf(SignalEventDirection.class);
    for (Map.Entry<SignalEventDirection, ColoredToggle> e : eventTypeToggles.entrySet()) {
      if (e.getValue() != null && e.getValue().isSelected()) {
        allowed.add(e.getKey());
      }
    }
    SignalSourceSpec spec = buildSpec();
    spec.setAllowedEventDirections(allowed);
    AnalysisSession.get().setSignalSpec(spec);
    AnalysisSession.get().refilterSignalAnnotation();
    refreshAnnotationFromSession();
    persistSettings();
  }

  private Node buildChannelFilterBlock(SignalChannel ch) {
    Label title = new Label(ch.label());
    title.getStyleClass().add("section-header");
    ChoiceBox<SignalValueType> typeChoice = new ChoiceBox<>();
    typeChoice.getItems().setAll(SignalValueType.values());
    typeChoice.setValue(ch.type());
    typeChoice.setOnAction(e -> {
      if (refreshing) {
        return;
      }
      SignalValueType t = typeChoice.getValue();
      replaceActiveChannel(ch.withType(t));
      rebuildChannelFilterUi();
    });
    SignalValueThresholds thr = ch.thresholds();
    ColoredToggle enabled = new ColoredToggle("Filter");
    enabled.setSelected(thr.isEnabled());
    ColoredToggle useMin = new ColoredToggle("Use Min");
    ColoredToggle useMax = new ColoredToggle("Use Max");
    // When filter is off, keep Use Min/Max ready so Apply arms bounds after editing Min.
    useMin.setSelected(thr.getMinValue() != null || !thr.isEnabled());
    useMax.setSelected(thr.getMaxValue() != null || !thr.isEnabled());
    Spinner<Double> min = newSpinner(spinnerSeedMin(ch, thr));
    Spinner<Double> max = newSpinner(spinnerSeedMax(ch, thr));
    Spinner<Double> middle = newSpinner(thr.getMiddle());
    Spinner<Double> minAbs = newSpinner(thr.getMinAbsFromMiddle());
    thresholdSpinners.add(min);
    thresholdSpinners.add(max);
    thresholdSpinners.add(middle);
    thresholdSpinners.add(minAbs);
    Runnable sync = () -> {
      Double abs = minAbs.getValue();
      boolean wantBounds = useMin.isSelected()
          || useMax.isSelected()
          || (abs != null && abs > 0.0);
      if (wantBounds) {
        enabled.setSelected(true);
      }
      SignalValueThresholds next = thr.copy();
      next.setEnabled(enabled.isSelected());
      next.setMinValue(useMin.isSelected() ? min.getValue() : null);
      next.setMaxValue(useMax.isSelected() ? max.getValue() : null);
      next.setMiddle(middle.getValue() != null ? middle.getValue() : 0);
      next.setMinAbsFromMiddle(abs != null ? abs : 0);
      ch.setThresholds(next);
    };
    channelThresholdSyncs.add(sync);
    HBox typeRow = new HBox(8, new Label("Type"), typeChoice);
    typeRow.setAlignment(Pos.CENTER_LEFT);
    HBox row1 = new HBox(8, useMin, min, useMax, max);
    row1.setAlignment(Pos.CENTER_LEFT);
    HBox row2 = new HBox(8, labeledSpin("Middle", middle), labeledSpin("Min |Δ|", minAbs));
    row2.setAlignment(Pos.CENTER_LEFT);
    VBox box = new VBox(6, title, typeRow, enabled, row1, row2);
    box.setPadding(new Insets(4, 0, 4, 0));
    return box;
  }

  private static Spinner<Double> newSpinner(double initial) {
    Spinner<Double> s = new Spinner<>();
    s.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(-1e9, 1e9, initial, 0.1));
    s.setEditable(true);
    s.setPrefWidth(90);
    return s;
  }

  private void replaceActiveChannel(SignalChannel updated) {
    List<SignalChannel> next = new ArrayList<>();
    for (SignalChannel ch : activeChannels) {
      next.add(ch.id().equals(updated.id()) ? updated : ch);
    }
    activeChannels = List.copyOf(next);
  }

  private void refreshMappingSummary() {
    List<Sample> tables = allTableSamples();
    if (tables.isEmpty()) {
      mappingSummaryLabel.setText("No mapped tables open");
      editColumnsButton.setDisable(true);
      return;
    }
    editColumnsButton.setDisable(false);
    MappedTableData first = tables.get(0).getMappedTable();
    MappedTableColumnMapping mapping = first != null ? first.getMapping() : null;
    String roles = mapping != null
        ? mapping.describeRoles(first.getHeaders())
        : "(unmapped)";
    mappingSummaryLabel.setText(String.format(
        Locale.ROOT,
        "%d table%s · %s",
        tables.size(),
        tables.size() == 1 ? "" : "s",
        roles));
  }

  private void editColumnsForAllTables() {
    List<Sample> tables = allTableSamples();
    if (tables.isEmpty()) {
      return;
    }
    MappedTableData template = tables.get(0).getMappedTable();
    Window owner = editColumnsButton.getScene() != null
        ? editColumnsButton.getScene().getWindow()
        : null;
    Optional<Integer> updated = MappedTableOpen.editMappingForAll(owner, template, tables);
    if (updated.isEmpty()) {
      return;
    }
    org.baseplayer.draw.GenomicCanvas.update.set(!org.baseplayer.draw.GenomicCanvas.update.get());
    refreshFromData();
  }

  private List<Sample> allTableSamples() {
    List<Sample> tables = new ArrayList<>();
    for (SampleTrack track : memberTracks()) {
      if (track == null) {
        continue;
      }
      for (Sample sample : track.getSamples()) {
        if (sample != null
            && sample.getDataType() == Sample.DataType.TABLE
            && sample.getMappedTable() != null) {
          tables.add(sample);
        }
      }
    }
    return tables;
  }

  /** Dataset membership, or included registry tracks when unbound (legacy). */
  private List<SampleTrack> memberTracks() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    if (dataset != null) {
      return includedOnly(dataset.trackList(), registry);
    }
    return registry != null ? new ArrayList<>(registry.getIncludedSampleTracks()) : List.of();
  }

  private SignalSourceSpec resolveSpec() {
    activateSessionSlice();
    List<SampleTrack> tracks = memberTracks();

    List<SignalChannel> channels = resolveChannels(tracks);
    SignalValueKind kind = null;
    if (dataset != null) {
      // Frozen at open so RNA + LOH can coexist without priority fights.
      kind = dataset.getKind();
      if (kind == SignalValueKind.TABLE_LOH) {
        channels = List.of();
      }
    } else {
      // Allele-CN (ASCAT/FACETS Minor+Major): multi-channel TABLE_VALUES so annotation
      // lists Gain/Loss/LOH with type filters (legacy TABLE_LOH only when channels missing).
      boolean alleleCnTables = tracks.stream().anyMatch(AlleleCnLohValueSource::trackHasAlleleCn);
      if (alleleCnTables && SignalChannels.isAlleleCnOnly(channels)) {
        kind = SignalValueKind.TABLE_VALUES;
      } else if (!channels.isEmpty()) {
        kind = SignalValueKind.TABLE_VALUES;
      } else if (alleleCnTables) {
        kind = SignalValueKind.TABLE_LOH;
      } else if (tracks.stream().anyMatch(MappedTableValueSource::trackHasValueTable)) {
        kind = SignalValueKind.TABLE_VALUES;
      } else if (tracks.stream().anyMatch(BedScoreSource::trackHasScoredBed)) {
        kind = SignalValueKind.BED_SCORES;
      } else if (tracks.stream().anyMatch(t -> BedScoreSource.firstBed(t) != null)) {
        kind = SignalValueKind.BED_ANY;
      } else if (tracks.stream().anyMatch(BigWigValueSource::trackHasBigWig)) {
        kind = SignalValueKind.BIGWIG;
      } else if (tracks.stream().anyMatch(BamMethylSource::trackHasMethylBam)) {
        kind = SignalValueKind.BAM_METHYL;
      }
    }

    SignalSourceSpec previous = AnalysisSession.get().getSignalSpec();
    SignalSourceSpec spec = new SignalSourceSpec();
    if (kind != null) {
      spec.setValueKind(kind);
    } else if (previous.getValueKind() != null) {
      spec.setValueKind(previous.getValueKind());
      kind = previous.getValueKind();
    }
    spec.setRequireBedScores(kind == SignalValueKind.BED_SCORES);
    spec.setUnit(ComparisonUnit.GENE);
    // Default matches Point/SV: current chromosome. Annotate All overrides via buildSpecForScope.
    spec.setScope(GenomicScope.CURRENT_CHROM);
    spec.setAggregation(ValueAggregation.MEAN);
    spec.setBigWigRule(BigWigCompanionRule.FIRST);
    spec.setWindowBp(SignalSourceSpec.DEFAULT_WINDOW_BP);
    // LOH-only collect: diploid / non-LOH segments never enter the gene matrix.
    spec.setLohOnly(kind == SignalValueKind.TABLE_LOH);
    spec.setChannels(mergeChannelThresholds(channels, previous.getChannels()));
    if (previous != null) {
      spec.setAllowedEventDirections(previous.getAllowedEventDirections());
    }
    return spec;
  }

  private List<SignalChannel> resolveChannels(List<SampleTrack> tracks) {
    for (SampleTrack track : tracks) {
      MappedTableData table = MultiChannelMappedTableSource.firstPayloadTable(track);
      if (table != null) {
        List<SignalChannel> derived = SignalChannels.fromTable(table);
        if (!derived.isEmpty()) {
          return derived;
        }
      }
    }
    return List.of();
  }

  /** Keep user-edited thresholds/types when channel ids match. */
  private List<SignalChannel> mergeChannelThresholds(
      List<SignalChannel> derived,
      List<SignalChannel> previous) {
    if (derived.isEmpty()) {
      return List.of();
    }
    List<SignalChannel> prefer = !activeChannels.isEmpty() ? activeChannels : previous;
    if (prefer == null || prefer.isEmpty()) {
      return derived;
    }
    List<SignalChannel> out = new ArrayList<>();
    for (SignalChannel d : derived) {
      SignalChannel keep = d;
      for (SignalChannel p : prefer) {
        if (p.id().equals(d.id())) {
          keep = new SignalChannel(d.id(), d.label(), p.type(), d.sourceRole(), p.thresholds());
          break;
        }
      }
      out.add(keep);
    }
    return out;
  }

  private SignalSourceSpec buildSpec() {
    SignalSourceSpec spec = resolveSpec();
    SignalSourceSpec previous = AnalysisSession.get().getSignalSpec();
    if (previous != null && previous.getScope() != null) {
      spec.setScope(previous.getScope());
    }
    // TABLE_LOH must stay channel-less (AlleleCnLohValueSource). Do not re-apply a
    // stale Minor/Major channel list from a previous multi-channel session.
    if (!activeChannels.isEmpty() && spec.getValueKind() != SignalValueKind.TABLE_LOH) {
      spec.setChannels(activeChannels);
    }
    return spec;
  }

  private DifferenceThresholds buildThresholds() {
    return filterControls.buildThresholds();
  }

  private SignalValueThresholds buildValueThresholds() {
    if (!activeChannels.isEmpty()) {
      // Primary channel thresholds for session legacy field; refilter uses channel list.
      return activeChannels.get(0).thresholds().copy();
    }
    SignalValueThresholds thr = new SignalValueThresholds();
    thr.setEnabled(valueThrEnabled.isSelected());
    thr.setMinValue(useMinBound.isSelected() ? minValueSpinner.getValue() : null);
    thr.setMaxValue(useMaxBound.isSelected() ? maxValueSpinner.getValue() : null);
    Double middle = middleSpinner.getValue();
    thr.setMiddle(middle != null ? middle : 0.0);
    Double minAbs = minAbsFromMiddleSpinner.getValue();
    thr.setMinAbsFromMiddle(minAbs != null ? minAbs : 0.0);
    Integer top = maxRowsSpinner.getValue();
    thr.setMaxRows(top != null ? top : 0);
    return thr;
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
    AnalysisSession.get().setValueThresholds(buildValueThresholds());
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
      applyComparisonResult(recomputed);
      org.baseplayer.draw.GenomicCanvas.update.set(!org.baseplayer.draw.GenomicCanvas.update.get());
      return;
    }
    statusLabel.setText("Test updated — Run to apply");
  }

  /** Refresh Group comparison results after difference-threshold filter changes. */
  private void refreshComparisonResult() {
    GroupStatResult shown = AnalysisSession.get().getFilteredResult(AnalysisMode.SIGNAL);
    if (shown != null && !SignalAnnotator.isAnnotationResult(shown)) {
      applyComparisonResult(shown);
      org.baseplayer.draw.GenomicCanvas.update.set(!org.baseplayer.draw.GenomicCanvas.update.get());
    }
  }

  /** Refresh Group comparison after Genome sidebar gene-type / COSMIC visibility changes. */
  public void refreshComparisonAfterGeneVisibility() {
    refreshComparisonResult();
  }

  private void applyAnnotationResult(GroupStatResult result) {
    annotationTable.setResult(result);
    String summary = result != null ? result.summary() : "";
    annotateStatusLabel.setText(summary != null ? summary : "");
    if (result != null) {
      String strip = result.methodStrip();
      annotateStatusLabel.setTooltip(
          strip != null && !strip.isBlank() ? new Tooltip(strip) : null);
    }
    updateExportButtonState();
    if (onResultUpdated != null) {
      onResultUpdated.accept(result);
    }
  }

  private void applyComparisonResult(GroupStatResult result) {
    comparisonTable.setResult(result);
    String summary = result != null ? result.summary() : "";
    statusLabel.setText(summary != null ? summary : "");
    if (result != null) {
      String strip = result.methodStrip();
      statusLabel.setTooltip(
          strip != null && !strip.isBlank() ? new Tooltip(strip) : null);
    }
    if (testChoice.getValue() != AnalysisSession.get().getGroupStatTest()) {
      refreshing = true;
      testChoice.setValue(AnalysisSession.get().getGroupStatTest());
      refreshing = false;
    }
    if (resultsTabPane != null && groupResultsTab != null) {
      resultsTabPane.getSelectionModel().select(groupResultsTab);
    }
    updateExportButtonState();
    refreshGroupHeatmapEnabled();
    if (onResultUpdated != null) {
      onResultUpdated.accept(result);
    }
  }

  private void refreshGroupHeatmapEnabled() {
    GroupStatResult shown = AnalysisSession.get().getFilteredResult(AnalysisMode.SIGNAL);
    if (shown == null || SignalAnnotator.isAnnotationResult(shown)) {
      shown = AnalysisSession.get().getResult(AnalysisMode.SIGNAL);
    }
    ValueGrid grid = AnalysisSession.get().getSignalGridForGroupRun();
    if (grid == null || grid.isEmpty()) {
      grid = AnalysisSession.get().getSignalGrid();
    }
    List<Integer> groupIds = ComparisonContext.get().getParticipatingGroupIds();
    boolean ok = shown != null
        && !SignalAnnotator.isAnnotationResult(shown)
        && shown.rows() != null
        && shown.rows().size() >= 2
        && grid != null
        && !grid.isEmpty()
        && groupIds.size() >= 2;
    filterControls.setHeatmapEnabled(ok);
  }

  private void showGroupComparisonHeatmap() {
    GroupStatResult shown = AnalysisSession.get().getFilteredResult(AnalysisMode.SIGNAL);
    if (shown == null || SignalAnnotator.isAnnotationResult(shown)) {
      shown = AnalysisSession.get().getResult(AnalysisMode.SIGNAL);
    }
    if (shown == null || SignalAnnotator.isAnnotationResult(shown)) {
      statusLabel.setText("Run group comparison first");
      return;
    }
    ValueGrid grid = AnalysisSession.get().getSignalGridForGroupRun();
    if (grid == null || grid.isEmpty()) {
      grid = AnalysisSession.get().getSignalGrid();
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    List<Integer> groupIds = ComparisonContext.get().getParticipatingGroupIds();
    SamplePatternEngine.Result heat = SamplePatternEngine.fromGroupComparison(
        grid, registry, groupIds, shown);
    if (!heat.hasHeatmapData()) {
      statusLabel.setText(heat.summary() != null ? heat.summary() : "Need ≥2 DE features");
      refreshGroupHeatmapEnabled();
      return;
    }
    new SignalPatternHeatmap(heat, "Group comparison heatmap", "Cohort", ownerWindow()).show();
  }

  private void setupExportButton() {
    FontIcon icon = new FontIcon(FontAwesomeSolid.FILE_EXPORT);
    icon.setIconSize(14);
    icon.setIconColor(Color.web("#5b9bd5"));
    exportButton.setText("");
    exportButton.setGraphic(icon);
    exportButton.getStyleClass().add("icon-button");
    exportButton.setTooltip(new Tooltip(
        "Export signal values and/or comparison results (TSV / Excel)"));
    exportButton.setOnAction(e -> handleExportAnalysis());
    updateExportButtonState();
  }

  private void updateExportButtonState() {
    exportButton.setDisable(!AnalysisExportService.hasAnythingToExport());
  }

  private void handleExportAnalysis() {
    activateSessionSlice();
    Window owner = ownerWindow();
    EnumSet<AnalysisExportKind> kinds = AnalysisExportDialog.show(owner);
    if (kinds == null || kinds.isEmpty()) {
      return;
    }
    EnumSet<AnalysisExportKind> selected = EnumSet.copyOf(kinds);
    // AppDialog must fully dismiss before a native FileChooser. Raise the same
    // Stage that will own the chooser (Analysis) — never MainApp, or the save
    // dialog stacks behind MainApp on Linux/GTK.
    SignalPatternHeatmap.raiseWindow(owner != null ? owner : MainApp.stage);
    Platform.runLater(() -> {
      Path target = chooseExportTarget(owner, selected);
      if (target == null) {
        return;
      }
      SignalPatternHeatmap.raiseWindow(owner != null ? owner : MainApp.stage);
      Platform.runLater(() -> startExportTask(selected, target, owner));
    });
  }

  private Path chooseExportTarget(Window owner, EnumSet<AnalysisExportKind> kinds) {
    File lastDir = UserPreferences.getLastDirectory("ANALYSIS_EXPORT");
    if (lastDir == null) {
      lastDir = UserPreferences.getLastDirectory("XLSX");
    }
    Window chooserOwner = owner != null ? owner : MainApp.stage;
    // Keep heatmaps suppressed (kind picker already hid them) and raise owner so
    // the native dialog parents to the frontmost BasePlayer window.
    SignalPatternHeatmap.prepareForNativeDialog();
    SignalPatternHeatmap.raiseWindow(chooserOwner);
    try {
      if (kinds.size() > 1) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Export analysis files to folder");
        if (lastDir != null) {
          try {
            chooser.setInitialDirectory(lastDir);
          } catch (IllegalArgumentException ignored) {
            // Directory unavailable.
          }
        }
        File dir = chooser.showDialog(chooserOwner);
        if (dir == null) {
          return null;
        }
        UserPreferences.setLastDirectory("ANALYSIS_EXPORT", dir);
        return dir.toPath();
      }
      AnalysisExportKind kind = kinds.iterator().next();
      FileChooser chooser = new FileChooser();
      chooser.setTitle("Export " + kind.label());
      chooser.getExtensionFilters().add(
          new FileChooser.ExtensionFilter(kind.filterDescription(), kind.filterExtension()));
      chooser.setInitialFileName(kind.defaultFileName());
      if (lastDir != null) {
        try {
          chooser.setInitialDirectory(lastDir);
        } catch (IllegalArgumentException ignored) {
          // Directory unavailable.
        }
      }
      File chosen = chooser.showSaveDialog(chooserOwner);
      if (chosen == null) {
        return null;
      }
      Path path = AnalysisExportService.ensureExtension(chosen.toPath(), kind);
      UserPreferences.setLastDirectory("ANALYSIS_EXPORT", path.toFile());
      return path;
    } finally {
      SignalPatternHeatmap.restoreAfterNativeDialog(chooserOwner);
    }
  }

  private void startExportTask(EnumSet<AnalysisExportKind> kinds, Path target, Window owner) {
    ThreadRunner.get().submit(
        "Exporting analysis…",
        () -> {
          try {
            AnalysisExportService.export(kinds, target);
            return null;
          } catch (Exception ex) {
            return ex;
          }
        },
        error -> {
          if (error != null) {
            AppDialog.info(
                owner,
                "Export analysis",
                "Export failed",
                error.getMessage() != null ? error.getMessage() : error.toString());
          }
        });
  }

  private Window ownerWindow() {
    if (exportButton.getScene() != null) {
      return exportButton.getScene().getWindow();
    }
    if (annotateAllButton.getScene() != null) {
      return annotateAllButton.getScene().getWindow();
    }
    return MainApp.stage;
  }

  private void refreshObservedRangeFromSession() {
    SignalValueStats stats = currentObservedStats();
    if (stats == null || stats.isEmpty()) {
      SignalSourceSpec spec = resolveSpec();
      if (spec.hasChannels()) {
        observedRangeLabel.setText(
            "Multi-channel: filters off by default. Enable Filter + Use Min/Max per channel as needed.");
        seedMinMaxSpinnersFromStats(null);
      } else {
        observedRangeLabel.setText("Annotating… observed range appears after collect");
      }
      return;
    }
    observedRangeLabel.setText(stats.formatSummary());
    seedMinMaxSpinnersFromStats(stats);
  }

  /** Update Min/Max spinner numbers from data without enabling filters. */
  private void seedMinMaxSpinnersFromStats(SignalValueStats stats) {
    if (refreshing) {
      return;
    }
    refreshing = true;
    try {
      if (stats != null && !stats.isEmpty()) {
        minValueSpinner.getValueFactory().setValue(stats.min());
        maxValueSpinner.getValueFactory().setValue(stats.max());
      }
      seedChannelObservedStats();
    } finally {
      refreshing = false;
    }
    if (!activeChannels.isEmpty()) {
      rebuildChannelFilterUi();
    }
  }

  private void seedChannelObservedStats() {
    FeatureValueBundle bundle = AnalysisSession.get().getSignalBundle();
    if (bundle == null || activeChannels.isEmpty()) {
      channelObservedStats = Map.of();
      return;
    }
    SignalSourceSpec spec = resolveSpec();
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    List<SampleTrack> tracks = usableTracks(registry, spec);
    Map<String, SignalValueStats> next = new HashMap<>();
    for (SignalChannel ch : activeChannels) {
      FeatureValueMatrix matrix = bundle.matrix(ch.id());
      next.put(ch.id(), SignalValueStats.fromMatrix(matrix, tracks));
    }
    channelObservedStats = Map.copyOf(next);
  }

  private double spinnerSeedMin(SignalChannel ch, SignalValueThresholds thr) {
    if (thr.getMinValue() != null) {
      return thr.getMinValue();
    }
    SignalValueStats obs = channelObservedStats.get(ch.id());
    if (obs != null && !obs.isEmpty()) {
      return obs.min();
    }
    return 0.0;
  }

  private double spinnerSeedMax(SignalChannel ch, SignalValueThresholds thr) {
    if (thr.getMaxValue() != null) {
      return thr.getMaxValue();
    }
    SignalValueStats obs = channelObservedStats.get(ch.id());
    if (obs != null && !obs.isEmpty()) {
      return obs.max();
    }
    return ch.type() == SignalValueType.METHYLATION ? 1.0 : 1.0;
  }

  private SignalValueStats currentObservedStats() {
    ValueGrid grid = AnalysisSession.get().getSignalGrid();
    if (grid == null || grid.isEmpty()) {
      return SignalValueStats.empty();
    }
    SignalSourceSpec spec = resolveSpec();
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    return SignalValueStats.fromGrid(grid, usableTracks(registry, spec));
  }

  private void updateReadyState() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    String block = ComparisonContext.get().runBlockReason(registry);
    List<SampleTrack> tracks = ComparisonContext.get().resolveTracks(registry);
    SignalSourceSpec spec = buildSpec();
    boolean hasUsable = !usableTracks(registry, spec).isEmpty();
    annotateAllButton.setDisable(!hasUsable);
    updateExportButtonState();
    if (!hasUsable) {
      runButton.setDisable(true);
      if (annotateStatusLabel.getText() == null || annotateStatusLabel.getText().isBlank()) {
        annotateStatusLabel.setText("Open signal data to annotate");
      }
      return;
    }
    int usable = countUsable(tracks, spec);
    if (block != null) {
      runButton.setDisable(true);
      statusLabel.setText(block);
    } else if (usable < 2) {
      runButton.setDisable(true);
      statusLabel.setText("Need ≥2 samples with matching values");
    } else {
      runButton.setDisable(false);
      String current = statusLabel.getText();
      if (current == null
          || current.isBlank()
          || current.endsWith("samples ready")
          || current.startsWith("Open signal")
          || current.startsWith("Need ≥2")
          || current.startsWith("Need ≥1 sample group")) {
        statusLabel.setText("");
      }
    }
  }

  private ThreadRunner.RunnerTask runAnnotate(GenomicScope scope, Runnable onComplete) {
    if (refreshing) {
      if (onComplete != null) {
        onComplete.run();
      }
      return null;
    }
    SignalSourceSpec spec = buildSpecForScope(
        scope != null ? scope : GenomicScope.CURRENT_CHROM);
    ValueSource source = buildSource(spec);
    if (source == null) {
      annotateStatusLabel.setText("Could not build value source");
      if (onComplete != null) {
        onComplete.run();
      }
      return null;
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    List<SampleTrack> tracks = usableTracks(registry, spec);
    if (tracks.isEmpty()) {
      annotateStatusLabel.setText("Open signal data to annotate");
      if (onComplete != null) {
        onComplete.run();
      }
      return null;
    }
    AnalysisSession.get().setSignalSpec(spec);
    AnalysisSession.get().setValueThresholds(buildValueThresholds());
    annotateStatusLabel.setText(
        spec.getScope() == GenomicScope.ALL_CHROMS
            ? "Annotating all chromosomes…"
            : "Annotating…");
    annotateAllButton.setDisable(true);
    annotationTable.setUnitColumnTitle(spec.getUnit());
    if (annotateTask != null) {
      annotateTask.cancel();
    }
    annotateTask = AnalysisRunner.annotate(
        source,
        spec,
        tracks,
        buildValueThresholds(),
        msg -> annotateStatusLabel.setText(msg != null ? msg : ""),
        result -> {
          annotateTask = null;
          refreshObservedRangeFromSession();
          if (result != null) {
            applyAnnotationResult(result);
            if (resultsTabPane != null && signalsResultsTab != null) {
              resultsTabPane.getSelectionModel().select(signalsResultsTab);
            }
            org.baseplayer.draw.GenomicCanvas.update.set(
                !org.baseplayer.draw.GenomicCanvas.update.get());
          }
          updateReadyState();
          if (onComplete != null) {
            onComplete.run();
          }
        });
    return annotateTask;
  }

  private ThreadRunner.RunnerTask runDiscoverAllChromosomes(Runnable onComplete) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    SignalSourceSpec spec = resolveSpec();
    List<SampleTrack> tracks = usableTracks(registry, spec);
    if (tracks.size() < 2) {
      patternControls.setStatus("Need ≥2 samples with values");
      if (onComplete != null) {
        onComplete.run();
      }
      return null;
    }
    CompactTableGrid allGrid = CompactTableGrid.tryCreate(tracks, GenomicScope.ALL_CHROMS);
    if (allGrid != null && !allGrid.isEmpty()) {
      AnalysisSession.get().replaceSignalGrid(allGrid);
      runPatternDiscovery(allGrid, tracks, () -> {
        selectResultsTab(true);
        if (onComplete != null) {
          onComplete.run();
        }
      });
      return null;
    }
    patternControls.setBusy(true);
    patternControls.setStatus("Annotating all chromosomes…");
    return runAnnotate(GenomicScope.ALL_CHROMS, () -> {
      ValueGrid grid = discoveryGridOrSession();
      if (grid == null || grid.isEmpty()) {
        patternControls.setBusy(false);
        patternControls.setStatus("No signal to discover");
        applyComparisonResult(null);
        if (onComplete != null) {
          onComplete.run();
        }
        return;
      }
      runPatternDiscovery(grid, tracks, () -> {
        selectResultsTab(true);
        if (onComplete != null) {
          onComplete.run();
        }
      });
    });
  }

  private ThreadRunner.RunnerTask runCompareAllChromosomes(Runnable onComplete) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    String block = ComparisonContext.get().runBlockReason(registry);
    if (block != null) {
      statusLabel.setText(block);
      if (onComplete != null) {
        onComplete.run();
      }
      return null;
    }
    SignalSourceSpec spec = resolveSpec();
    List<SampleTrack> tracks = usableTracks(registry, spec);
    if (tracks.size() < 2) {
      statusLabel.setText("Need ≥2 samples with values");
      if (onComplete != null) {
        onComplete.run();
      }
      return null;
    }
    DifferenceThresholds thr = buildThresholds();
    AnalysisSession.get().setThresholds(thr);
    persistSettings();
    AnalysisSession.get().setActiveCanvasMode(AnalysisMode.SIGNAL);
    comparisonTable.setUnitColumnTitle(spec.getUnit());
    applyComparisonResult(null);

    ComparisonContext ctx = ComparisonContext.get();
    List<Integer> groupIds = ctx.getParticipatingGroupIds();
    Integer baseline = ctx.getBaselineGroupId();
    GroupStatTest test = AnalysisSession.get().getGroupStatTest();
    EffectMode effect = AnalysisSession.get().getEffectMode();
    SignalValueType valueType = AnalysisSession.get().resolveValueTypeForEffect();
    double minMean = AnalysisSession.get().getMinMeanPrefilter();
    String scoreLabel = spec != null && spec.getValueKind() != null
        ? "Signal " + spec.getValueKind().name() + " / " + spec.getAggregation().name()
        : "Signal";

    // Allele-CN segments: genome-wide event presence (not CompactTableGrid).
    if (SignalChannels.isAlleleCnOnly(spec.getChannels())) {
      runButton.setDisable(true);
      statusLabel.setText("Annotating allele-CN for comparison…");
      annotateStatusLabel.setText("Annotating allele-CN for comparison…");
      return runAnnotate(GenomicScope.ALL_CHROMS, () -> {
        ValueGrid grid = AnalysisSession.get().getSignalGridForGroupRun();
        if (grid == null || grid.isEmpty()) {
          runButton.setDisable(false);
          statusLabel.setText("No allele-CN events to compare");
          applyComparisonResult(null);
          updateReadyState();
          if (onComplete != null) {
            onComplete.run();
          }
          return;
        }
        statusLabel.setText("Comparing allele-CN events…");
        ThreadRunner.RunnerTask task = ThreadRunner.get().submit(
            "Group differential allele-CN",
            () -> {
              GroupStatResult raw = GroupStatEngine.compute(
                  AnalysisMode.SIGNAL,
                  grid,
                  registry,
                  groupIds,
                  baseline,
                  test,
                  scoreLabel,
                  effect,
                  valueType != null ? valueType : SignalValueType.ALLELE_CN,
                  minMean);
              if (raw == null) {
                return null;
              }
              String summary = (raw.summary() != null ? raw.summary() : "Allele-CN")
                  + " · genome-wide event presence";
              String strip = (raw.methodStrip() != null ? raw.methodStrip() : "")
                  + " · allele-CN Gain/Loss/LOH";
              return new GroupStatResult(
                  raw.mode(),
                  raw.groupIds(),
                  raw.baselineGroupId(),
                  raw.rows(),
                  summary,
                  strip,
                  raw.signalChannels(),
                  raw.channelValuesByUnit());
            },
            (GroupStatResult result) -> finishCompareAllChromsResult(result, thr, onComplete));
        wireCompareAllChromsCancel(task, onComplete);
      });
    }

    List<String> chromosomes = resolveChromosomesForMatrix(tracks);
    if (chromosomes.isEmpty()) {
      statusLabel.setText("No chromosomes in matrix");
      if (onComplete != null) {
        onComplete.run();
      }
      return null;
    }

    runButton.setDisable(true);
    statusLabel.setText("Comparing all chromosomes…");
    annotateStatusLabel.setText("Comparing all chromosomes…");
    ThreadRunner.RunnerTask task = ThreadRunner.get().submit(
        "Group differential all chroms",
        () -> computeSignificantAllChroms(
            tracks, chromosomes, groupIds, baseline, test, scoreLabel, effect, valueType, minMean, thr),
        (GroupStatResult result) -> finishCompareAllChromsResult(result, thr, onComplete));
    wireCompareAllChromsCancel(task, onComplete);
    return task;
  }

  private void finishCompareAllChromsResult(
      GroupStatResult result, DifferenceThresholds thr, Runnable onComplete) {
    runButton.setDisable(false);
    if (result == null) {
      statusLabel.setText("Differential analysis failed");
      applyComparisonResult(null);
      updateReadyState();
      if (onComplete != null) {
        onComplete.run();
      }
      return;
    }
    AnalysisSession.get().putResult(AnalysisMode.SIGNAL, result, null, thr);
    GroupStatResult shown = AnalysisSession.get().getFilteredResult(AnalysisMode.SIGNAL);
    applyComparisonResult(shown != null ? shown : result);
    selectResultsTab(true);
    int kept = shown != null ? shown.rows().size() : 0;
    String summary = result.summary() != null ? result.summary() : "Differential";
    if (thr.isEnabled() && thr.isUseMaxQ()) {
      statusLabel.setText(String.format(
          Locale.ROOT, "%s · %d kept (q ≤%.3g)", summary, kept, thr.getMaxQ()));
    } else {
      statusLabel.setText(String.format(Locale.ROOT, "%s · %d kept", summary, kept));
    }
    org.baseplayer.draw.GenomicCanvas.update.set(
        !org.baseplayer.draw.GenomicCanvas.update.get());
    updateReadyState();
    if (onComplete != null) {
      onComplete.run();
    }
  }

  private void wireCompareAllChromsCancel(ThreadRunner.RunnerTask task, Runnable onComplete) {
    if (task != null) {
      task.onCancel(() -> javafx.application.Platform.runLater(() -> {
        runButton.setDisable(false);
        statusLabel.setText("Cancelled");
        updateReadyState();
        if (onComplete != null) {
          onComplete.run();
        }
      }));
    } else {
      runButton.setDisable(false);
      updateReadyState();
      if (onComplete != null) {
        onComplete.run();
      }
    }
  }

  private static GroupStatResult computeSignificantAllChroms(
      List<SampleTrack> tracks,
      List<String> chromosomes,
      List<Integer> groupIds,
      Integer baseline,
      GroupStatTest test,
      String scoreLabel,
      EffectMode effect,
      SignalValueType valueType,
      double minMean,
      DifferenceThresholds thr) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    List<GroupStatResult.Row> kept = new ArrayList<>();
    int tested = 0;
    int chromsWithData = 0;
    String methodStrip = null;
    for (String chrom : chromosomes) {
      CompactTableGrid grid = CompactTableGrid.tryCreateForChrom(tracks, chrom);
      if (grid == null || grid.isEmpty()) {
        continue;
      }
      chromsWithData++;
      GroupStatResult raw = GroupStatEngine.compute(
          AnalysisMode.SIGNAL,
          grid,
          registry,
          groupIds,
          baseline,
          test,
          scoreLabel,
          effect,
          valueType,
          minMean);
      if (raw == null || raw.rows().isEmpty()) {
        continue;
      }
      tested += raw.rows().size();
      if (methodStrip == null) {
        methodStrip = raw.methodStrip();
      }
      GroupStatResult filtered = ThresholdFilter.apply(raw, thr);
      if (filtered != null && filtered.rows() != null) {
        kept.addAll(filtered.rows());
      }
    }
    String summary = String.format(
        Locale.ROOT,
        "All chroms: %d significant / %d tested across %d chroms (q per chrom)",
        kept.size(),
        tested,
        chromsWithData);
    String strip = methodStrip != null
        ? methodStrip + " · genome-wide (per-chromosome BH)"
        : "Group differential · genome-wide (per-chromosome BH)";
    return new GroupStatResult(
        AnalysisMode.SIGNAL,
        groupIds,
        baseline,
        kept,
        summary,
        strip);
  }

  private static List<String> resolveChromosomesForMatrix(List<SampleTrack> tracks) {
    CompactTableGrid all = CompactTableGrid.tryCreate(tracks, GenomicScope.ALL_CHROMS);
    if (all != null && all.sharedLoci() != null) {
      SharedMatrixLoci loci = all.sharedLoci();
      List<String> fromStack = referenceChromosomeOrder();
      if (!fromStack.isEmpty()) {
        List<String> ordered = new ArrayList<>();
        for (String chrom : fromStack) {
          if (loci.indicesOnChromosome(chrom).length > 0) {
            ordered.add(chrom);
          }
        }
        if (!ordered.isEmpty()) {
          return ordered;
        }
      }
      List<String> keys = new ArrayList<>(loci.chromosomeKeys());
      keys.sort(String.CASE_INSENSITIVE_ORDER);
      return keys;
    }
    return referenceChromosomeOrder();
  }

  private static List<String> referenceChromosomeOrder() {
    try {
      var stackManager = ServiceRegistry.getInstance().getDrawStackManager();
      if (stackManager != null && !stackManager.isEmpty()) {
        var drawStack = stackManager.getFirst();
        if (drawStack != null && drawStack.chromosomeDropdown != null) {
          return new ArrayList<>(drawStack.chromosomeDropdown.getItems());
        }
      }
    } catch (RuntimeException ignored) {
      // headless / early startup
    }
    return List.of();
  }

  private void discoverPatterns() {
    runWithAnalysisBusy("Discovering patterns…", this::discoverPatternsBody);
  }

  private void discoverPatternsBody(Runnable onComplete) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    SignalSourceSpec spec = resolveSpec();
    List<SampleTrack> tracks = usableTracks(registry, spec);
    if (tracks.size() < 2) {
      patternControls.setStatus("Need ≥2 samples with values");
      if (onComplete != null) {
        onComplete.run();
      }
      return;
    }
    // Always rebuild for the chromosome in view so Discover replaces prior chrom results.
    CompactTableGrid chromGrid = CompactTableGrid.tryCreate(tracks, GenomicScope.CURRENT_CHROM);
    if (chromGrid != null && !chromGrid.isEmpty()) {
      AnalysisSession.get().replaceSignalGrid(chromGrid);
      runPatternDiscovery(chromGrid, tracks, onComplete);
      return;
    }
    patternControls.setBusy(true);
    patternControls.setStatus("Annotating current chromosome…");
    runAnnotate(GenomicScope.CURRENT_CHROM, () -> {
      ValueGrid grid = discoveryGridOrSession();
      if (grid == null || grid.isEmpty()) {
        patternControls.setBusy(false);
        patternControls.setStatus("No signal on this chromosome");
        applyComparisonResult(null);
        if (onComplete != null) {
          onComplete.run();
        }
        return;
      }
      runPatternDiscovery(grid, tracks, onComplete);
    });
  }

  /** Allele-CN → Gain/Loss/LOH presence matrix; otherwise session signal grid. */
  private static ValueGrid discoveryGridOrSession() {
    FeatureValueMatrix events = AnalysisSession.get().getAlleleCnEventMatrixForDiscovery();
    if (events != null && !events.isEmpty()) {
      return events;
    }
    return AnalysisSession.get().getSignalGrid();
  }

  private void runWithAnalysisBusy(String status, Consumer<Runnable> work) {
    if (analysisBusyRunner != null) {
      analysisBusyRunner.run(status, work);
    } else if (work != null) {
      work.accept(null);
    }
  }

  private void runPatternDiscovery(ValueGrid grid, List<SampleTrack> tracks, Runnable onComplete) {
    if (grid == null || grid.isEmpty()) {
      patternControls.setBusy(false);
      patternControls.setStatus("No signal on this chromosome");
      applyComparisonResult(null);
      if (onComplete != null) {
        onComplete.run();
      }
      return;
    }
    SamplePatternEngine.Settings settings = patternControls.buildSettings();
    EffectMode effect = AnalysisSession.get().getEffectMode();
    SignalValueType valueType = AnalysisSession.get().resolveValueTypeForEffect();
    GroupStatTest test = AnalysisSession.get().getGroupStatTest();
    patternControls.setBusy(true);
    patternControls.setStatus(
        "Discovering patterns on " + grid.size() + " features…");
    // Clear stale chrom rows immediately.
    applyComparisonResult(null);
    ThreadRunner.get().submit(
        "Pattern discovery",
        () -> SamplePatternEngine.discover(grid, tracks, settings, effect, valueType, test),
        result -> {
          patternControls.setBusy(false);
          if (result == null) {
            patternControls.setStatus("Pattern discovery failed");
            applyComparisonResult(null);
            updateReadyState();
            if (onComplete != null) {
              onComplete.run();
            }
            return;
          }
          AnalysisSession.get().setLastPatternResult(result);
          patternControls.refreshApplyEnabled();
          DifferenceThresholds thr = buildThresholds();
          AnalysisSession.get().setThresholds(thr);
          GroupStatResult markers = result.markers();
          FeatureValueMatrix store = grid instanceof FeatureValueMatrix fvm ? fvm : null;
          if (markers != null) {
            AnalysisSession.get().putResult(AnalysisMode.SIGNAL, markers, store, thr);
            GroupStatResult shown = AnalysisSession.get().getFilteredResult(AnalysisMode.SIGNAL);
            int kept = shown != null ? shown.rows().size() : 0;
            int raw = markers.rows().size();
            patternControls.setStatus(String.format(
                Locale.ROOT,
                "%s · showing %d / %d",
                result.summary(),
                kept,
                raw));
            applyComparisonResult(shown);
          } else {
            patternControls.setStatus(result.summary() + " · no marker features");
            AnalysisSession.get().putResult(AnalysisMode.SIGNAL, null, store, thr);
            applyComparisonResult(null);
          }
          updateReadyState();
          if (onComplete != null) {
            onComplete.run();
          }
        });
  }

  private void applyPatternGroups() {
    SamplePatternEngine.Result result = AnalysisSession.get().getLastPatternResult();
    if (result == null || result.tracks() == null || result.clusterOfTrack() == null) {
      patternControls.setStatus("Discover patterns first");
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
    patternControls.setStatus(
        result.summary() + " · applied as Sample Groups (Pattern 1…" + k + ")");
    updateReadyState();
  }

  private void showPatternHeatmap() {
    SamplePatternEngine.Result result = AnalysisSession.get().getLastPatternResult();
    if (result == null || !result.hasHeatmapData()) {
      patternControls.setStatus("Discover patterns first");
      return;
    }
    new SignalPatternHeatmap(result, "Pattern heatmap", "Pattern", ownerWindow()).show();
  }

  private void runAnalysis() {
    runWithAnalysisBusy("Running differential…", this::runAnalysisBody);
  }

  private void runAnalysisBody(Runnable onComplete) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    String block = ComparisonContext.get().runBlockReason(registry);
    if (block != null) {
      statusLabel.setText(block);
      if (onComplete != null) {
        onComplete.run();
      }
      return;
    }
    SignalSourceSpec spec = resolveSpec();
    List<SampleTrack> tracks = usableTracks(registry, spec);
    if (tracks.size() < 2) {
      statusLabel.setText("Need ≥2 samples with values");
      if (onComplete != null) {
        onComplete.run();
      }
      return;
    }
    DifferenceThresholds thr = buildThresholds();
    AnalysisSession.get().setThresholds(thr);
    persistSettings();
    AnalysisSession.get().setActiveCanvasMode(AnalysisMode.SIGNAL);
    comparisonTable.setUnitColumnTitle(spec.getUnit());
    applyComparisonResult(null);

    CompactTableGrid chromGrid = CompactTableGrid.tryCreate(tracks, GenomicScope.CURRENT_CHROM);
    if (chromGrid != null && !chromGrid.isEmpty()) {
      AnalysisSession.get().replaceSignalGrid(chromGrid);
      ValueGrid forRun = AnalysisSession.get().getSignalGridForGroupRun();
      runGroupDifferential(forRun != null ? forRun : chromGrid, thr, spec, onComplete);
      return;
    }
    runButton.setDisable(true);
    statusLabel.setText("Annotating current chromosome…");
    runAnnotate(GenomicScope.CURRENT_CHROM, () -> {
      ValueGrid forRun = AnalysisSession.get().getSignalGridForGroupRun();
      if (forRun == null || forRun.isEmpty()) {
        forRun = AnalysisSession.get().getSignalGrid();
      }
      runGroupDifferential(forRun, thr, spec, onComplete);
    });
  }

  private void runGroupDifferential(
      ValueGrid grid, DifferenceThresholds thr, SignalSourceSpec spec, Runnable onComplete) {
    if (grid == null || grid.isEmpty()) {
      runButton.setDisable(false);
      statusLabel.setText("No signal on this chromosome");
      applyComparisonResult(null);
      updateReadyState();
      if (onComplete != null) {
        onComplete.run();
      }
      return;
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    ComparisonContext ctx = ComparisonContext.get();
    List<Integer> groupIds = ctx.getParticipatingGroupIds();
    Integer baseline = ctx.getBaselineGroupId();
    GroupStatTest test = AnalysisSession.get().getGroupStatTest();
    EffectMode effect = AnalysisSession.get().getEffectMode();
    SignalValueType valueType = AnalysisSession.get().resolveValueTypeForEffect();
    double minMean = AnalysisSession.get().getMinMeanPrefilter();
    String scoreLabel = spec != null && spec.getValueKind() != null
        ? "Signal " + spec.getValueKind().name() + " / " + spec.getAggregation().name()
        : "Signal";

    runButton.setDisable(true);
    statusLabel.setText("Running differential on " + grid.size() + " features…");
    ThreadRunner.RunnerTask task = ThreadRunner.get().submit(
        "Group differential",
        () -> (GroupStatResult) GroupStatEngine.compute(
            AnalysisMode.SIGNAL,
            grid,
            registry,
            groupIds,
            baseline,
            test,
            scoreLabel,
            effect,
            valueType,
            minMean),
        (GroupStatResult result) -> {
          runButton.setDisable(false);
          if (result == null) {
            statusLabel.setText("Differential analysis failed");
            applyComparisonResult(null);
            updateReadyState();
            if (onComplete != null) {
              onComplete.run();
            }
            return;
          }
          FeatureValueMatrix store = grid instanceof FeatureValueMatrix fvm ? fvm : null;
          AnalysisSession.get().putResult(AnalysisMode.SIGNAL, result, store, thr);
          GroupStatResult shown = AnalysisSession.get().getFilteredResult(AnalysisMode.SIGNAL);
          applyComparisonResult(shown != null ? shown : result);
          int kept = shown != null ? shown.rows().size() : 0;
          statusLabel.setText(String.format(
              Locale.ROOT,
              "%s · showing %d / %d",
              result.summary() != null ? result.summary() : "Differential",
              kept,
              result.rows().size()));
          org.baseplayer.draw.GenomicCanvas.update.set(
              !org.baseplayer.draw.GenomicCanvas.update.get());
          updateReadyState();
          if (onComplete != null) {
            onComplete.run();
          }
        });
    if (task != null) {
      task.onCancel(() -> javafx.application.Platform.runLater(() -> {
        runButton.setDisable(false);
        statusLabel.setText("Cancelled");
        updateReadyState();
        if (onComplete != null) {
          onComplete.run();
        }
      }));
    } else {
      runButton.setDisable(false);
      updateReadyState();
      if (onComplete != null) {
        onComplete.run();
      }
    }
  }

  private ValueSource buildSource(SignalSourceSpec spec) {
    if (spec == null || spec.getValueKind() == null) {
      return null;
    }
    if (spec.hasChannels()) {
      return new MultiChannelMappedTableSource();
    }
    return switch (spec.getValueKind()) {
      case BED_SCORES -> new BedScoreSource(true);
      case BED_ANY -> new BedScoreSource(false);
      case BIGWIG -> new BigWigValueSource();
      case BAM_METHYL -> new BamMethylSource();
      case TABLE_VALUES -> new MappedTableValueSource();
      case TABLE_LOH -> new AlleleCnLohValueSource();
    };
  }

  private List<SampleTrack> usableTracks(SampleRegistry registry, SignalSourceSpec spec) {
    if (spec == null || spec.getValueKind() == null) {
      return List.of();
    }
    List<SampleTrack> candidates = dataset != null
        ? includedOnly(dataset.trackList(), registry)
        : (registry != null ? new ArrayList<>(registry.getIncludedSampleTracks()) : List.of());
    List<SampleTrack> out = new ArrayList<>();
    for (SampleTrack track : candidates) {
      if (isUsable(track, spec)) {
        out.add(track);
      }
    }
    return out;
  }

  private static List<SampleTrack> includedOnly(
      List<SampleTrack> tracks, SampleRegistry registry) {
    if (tracks == null || tracks.isEmpty()) {
      return List.of();
    }
    if (registry == null) {
      return new ArrayList<>(tracks);
    }
    List<SampleTrack> out = new ArrayList<>();
    for (SampleTrack track : tracks) {
      if (registry.isTrackIncludedInProject(track)) {
        out.add(track);
      }
    }
    return out;
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
    if (spec == null || spec.getValueKind() == null) {
      return false;
    }
    if (spec.hasChannels()) {
      return MultiChannelMappedTableSource.trackHasPayload(track);
    }
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
