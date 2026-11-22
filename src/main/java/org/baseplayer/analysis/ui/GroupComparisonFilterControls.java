package org.baseplayer.analysis.ui;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.DifferenceThresholds;
import org.baseplayer.analysis.EffectMode;
import org.baseplayer.analysis.groupstat.GroupStatTest;
import org.baseplayer.components.ColoredToggle;
import org.baseplayer.components.HelpInfoButton;
import org.baseplayer.components.InfoModal;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

import javafx.collections.FXCollections;
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
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Shared difference-threshold controls for Signal and mutation/SV group comparison.
 */
public final class GroupComparisonFilterControls {

  private final ColoredToggle thresholdsEnabled = new ColoredToggle("Filter by thresholds");
  private final Spinner<Double> minAbsDelta = new Spinner<>();
  private final Spinner<Double> maxQ = new Spinner<>();
  private final Spinner<Integer> minN = new Spinner<>();
  private final Spinner<Integer> maxRows = new Spinner<>();
  private final Spinner<Double> minMeanPrefilter = new Spinner<>();
  private final ChoiceBox<EffectMode> effectChoice = new ChoiceBox<>();
  private final Label scaleDetectLabel = new Label();
  private final ColoredToggle showUp = new ColoredToggle("Different / higher outside baseline");
  private final ColoredToggle showDown = new ColoredToggle("Higher in baseline");
  private final ColoredToggle showSimilar = new ColoredToggle("Similar");
  private boolean refreshing;
  private Runnable onChanged;

  public GroupComparisonFilterControls() {
    minAbsDelta.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 20, 0.5, 0.1));
    minAbsDelta.setEditable(true);
    minAbsDelta.setPrefWidth(90);

    maxQ.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 1, 0.05, 0.01));
    maxQ.setEditable(true);
    maxQ.setPrefWidth(90);

    minN.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 1000, 2, 1));
    minN.setEditable(true);
    minN.setPrefWidth(90);

    maxRows.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 1_000_000, 0, 500));
    maxRows.setEditable(true);
    maxRows.setPrefWidth(90);

    minMeanPrefilter.setValueFactory(
        new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 1_000_000, 0, 0.1));
    minMeanPrefilter.setEditable(true);
    minMeanPrefilter.setPrefWidth(90);

    effectChoice.setItems(FXCollections.observableArrayList(EffectMode.values()));
    effectChoice.setConverter(new StringConverter<>() {
      @Override
      public String toString(EffectMode mode) {
        return mode != null ? mode.displayName() : "";
      }

      @Override
      public EffectMode fromString(String string) {
        return EffectMode.fromPersisted(string);
      }
    });
    effectChoice.setValue(AnalysisSession.get().getEffectMode());
    effectChoice.setMaxWidth(Double.MAX_VALUE);

    thresholdsEnabled.setSelected(false);
    showUp.setSelected(true);
    showDown.setSelected(true);
    showSimilar.setSelected(true);

    scaleDetectLabel.getStyleClass().add("value-label");
    scaleDetectLabel.setWrapText(true);

    Tooltip.install(thresholdsEnabled, new Tooltip(
        "When on, hide result rows that fail Min |Δ|, Max q, Min hits/group, direction, or Top N."));
    Tooltip.install(minAbsDelta, new Tooltip(
        "Minimum absolute effect vs baseline (log2 fold or mean Δ, per Effect mode)."));
    Tooltip.install(maxQ, new Tooltip("Maximum FDR-adjusted q-value to keep."));
    Tooltip.install(minN, new Tooltip("Minimum samples with a value in each compared group."));
    Tooltip.install(maxRows, new Tooltip("Keep only the top N rows by |Δ| (0 = show all)."));
    Tooltip.install(minMeanPrefilter, new Tooltip(
        "Drop features with overall mean below this before testing (0 = off)."));
    Tooltip.install(effectChoice, new Tooltip(
        "How Δ is computed vs baseline. Auto follows detected / channel value type."));
    Tooltip.install(showSimilar, new Tooltip("Include rows that are not called different between groups."));

    wire();
    refreshDirectionLabels();
    refreshScaleDetectLabel();
  }

  public void setOnChanged(Runnable onChanged) {
    this.onChanged = onChanged;
  }

  /**
   * Shared Group comparison chrome: left = Run/Test + thresholds, right = Show in table.
   * No panel title — the tab already names the mode.
   */
  public Node createWorkspace(Button runButton, ChoiceBox<GroupStatTest> testChoice, Label statusLabel) {
    SplitPane split = new SplitPane(
        wrapScroll(buildLeftColumn(runButton, testChoice, statusLabel)),
        wrapScroll(buildShowColumn()));
    split.setDividerPositions(0.58);
    split.getStyleClass().add("tab-content-split");
    return split;
  }

  /**
   * Compact stack for embedding under Signal Sample Comparison shared-events slider.
   */
  public Node createEmbeddedWorkspace(
      Button runButton, ChoiceBox<GroupStatTest> testChoice, Label statusLabel) {
    VBox box = new VBox(8,
        buildLeftColumn(runButton, testChoice, statusLabel),
        new Separator(),
        buildShowColumn());
    box.setPadding(new Insets(0, 0, 8, 0));
    return box;
  }

  private VBox buildLeftColumn(Button runButton, ChoiceBox<GroupStatTest> testChoice, Label statusLabel) {
    Label deHelp = new Label(
        "Differential analysis between sample groups on the current chromosome. "
            + "Table shows significant features only (q / |Δ| / direction).");
    deHelp.getStyleClass().add("subsection-label");
    deHelp.setWrapText(true);

    Label testLabel = new Label("Test");
    testLabel.getStyleClass().addAll("section-header", "filter-slider-label");
    testLabel.setMinWidth(36);
    testLabel.setPrefWidth(36);
    testLabel.setTooltip(new Tooltip(
        "Auto picks Fisher (sparse 0/1, 2 groups), Moderated t (continuous, 2 groups), "
            + "or Kruskal–Wallis (N>2)."));

    HBox.setHgrow(testChoice, Priority.ALWAYS);
    testChoice.setMaxWidth(Double.MAX_VALUE);
    Button testHelp = HelpInfoButton.create(
        "Statistical tests",
        "Group comparison tests",
        GroupComparisonFilterControls::testHelpSections);
    HBox topRow = new HBox(8, runButton, testLabel, testChoice, testHelp);
    topRow.setAlignment(Pos.CENTER_LEFT);

    Label effectLabel = new Label("Effect");
    effectLabel.getStyleClass().addAll("section-header", "filter-slider-label");
    effectLabel.setMinWidth(36);
    effectLabel.setPrefWidth(36);
    HBox.setHgrow(effectChoice, Priority.ALWAYS);
    HBox effectRow = new HBox(8, effectLabel, effectChoice);
    effectRow.setAlignment(Pos.CENTER_LEFT);

    Label thrHeader = new Label("Difference thresholds");
    thrHeader.getStyleClass().add("section-header");

    VBox left = new VBox(8,
        deHelp,
        topRow,
        effectRow,
        scaleDetectLabel,
        statusLabel,
        new Separator(),
        thrHeader,
        thresholdsEnabled,
        fieldRow("Min |Δ|", minAbsDelta, "Minimum absolute effect vs baseline"),
        fieldRow("Max q", maxQ, "Maximum FDR-adjusted q-value"),
        fieldRow("Min hits/group", minN, "Minimum samples with a value per group"),
        fieldRow("Min mean", minMeanPrefilter, "Prefilter: drop low-mean features (0 = off)"),
        fieldRow("Top N", maxRows, "Top N rows by |Δ| (0 = all)"));
    left.setPadding(new Insets(16));
    left.getStyleClass().add("filter-panel");
    return left;
  }

  private VBox buildShowColumn() {
    Label showHeader = new Label("Show in table");
    showHeader.getStyleClass().add("section-header");
    VBox right = new VBox(8, showHeader, showUp, showDown, showSimilar);
    right.setPadding(new Insets(16));
    right.getStyleClass().add("filter-panel");
    return right;
  }

  private static List<InfoModal.Section> testHelpSections() {
    List<InfoModal.Section> sections = new ArrayList<>();
    sections.add(new InfoModal.Section("Overview", GroupStatTest.helpIntro()));
    for (GroupStatTest test : GroupStatTest.values()) {
      sections.add(new InfoModal.Section(test.helpTitle(), test.helpBody()));
    }
    return sections;
  }

  public DifferenceThresholds buildThresholds() {
    commitSpinners();
    DifferenceThresholds thr = new DifferenceThresholds();
    thr.setEnabled(thresholdsEnabled.isSelected());
    Double delta = minAbsDelta.getValue();
    Double q = maxQ.getValue();
    Integer n = minN.getValue();
    Integer top = maxRows.getValue();
    if (delta != null) {
      thr.setMinAbsLog2Fc(delta);
    }
    if (q != null) {
      thr.setMaxQ(q);
    }
    if (n != null) {
      thr.setMinNPerGroup(n);
    }
    if (top != null) {
      thr.setMaxRows(top);
    }
    thr.setShowUp(showUp.isSelected());
    thr.setShowDown(showDown.isSelected());
    thr.setShowSimilar(showSimilar.isSelected());
    return thr;
  }

  public void loadFrom(DifferenceThresholds thr) {
    if (thr == null) {
      return;
    }
    refreshing = true;
    try {
      thresholdsEnabled.setSelected(thr.isEnabled());
      minAbsDelta.getValueFactory().setValue(thr.getMinAbsLog2Fc());
      maxQ.getValueFactory().setValue(thr.getMaxQ());
      minN.getValueFactory().setValue(thr.getMinNPerGroup());
      maxRows.getValueFactory().setValue(thr.getMaxRows());
      showUp.setSelected(thr.isShowUp());
      showDown.setSelected(thr.isShowDown());
      showSimilar.setSelected(thr.isShowSimilar());
      minMeanPrefilter.getValueFactory().setValue(AnalysisSession.get().getMinMeanPrefilter());
      effectChoice.setValue(AnalysisSession.get().getEffectMode());
      refreshDirectionLabels();
      refreshScaleDetectLabel();
    } finally {
      refreshing = false;
    }
  }

  public void refreshScaleDetectLabel() {
    String summary = AnalysisSession.get().getScaleDetectSummary();
    if (summary == null || summary.isBlank()) {
      scaleDetectLabel.setText("Scale: not detected yet — annotate or load a matrix first.");
    } else {
      scaleDetectLabel.setText(summary + " — change Effect if wrong.");
    }
  }

  public void refreshDirectionLabels() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    List<Integer> ids = ComparisonContext.get().getParticipatingGroupIds();
    Integer baseline = ComparisonContext.get().getBaselineGroupId();
    if (ids.size() == 2 && baseline != null) {
      int otherId = ids.get(0).equals(baseline) ? ids.get(1) : ids.get(0);
      String baseName = ComparisonContext.groupName(registry, baseline);
      String otherName = ComparisonContext.groupName(registry, otherId);
      showUp.setText("Higher in " + otherName);
      showDown.setText("Higher in " + baseName);
      showDown.setDisable(false);
      showDown.setVisible(true);
      showDown.setManaged(true);
      Tooltip.install(showUp, new Tooltip(
          "Baseline: " + baseName + ". Keep rows higher in " + otherName + "."));
      Tooltip.install(showDown, new Tooltip(
          "Baseline: " + baseName + ". Keep rows higher in " + baseName + "."));
    } else {
      showUp.setText("Different between groups");
      showDown.setDisable(true);
      showDown.setVisible(false);
      showDown.setManaged(false);
      Tooltip.install(showUp, new Tooltip(
          "With 3+ groups: mutation/SV Diff is direction split or most/least hit rate; "
              + "Signal Diff uses ↑ highest / ↓ lowest mean."));
    }
  }

  private void wire() {
    Runnable fire = () -> {
      if (!refreshing && onChanged != null) {
        onChanged.run();
      }
    };
    thresholdsEnabled.selectedProperty().addListener((o, a, b) -> fire.run());
    minAbsDelta.valueProperty().addListener((o, a, b) -> fire.run());
    maxQ.valueProperty().addListener((o, a, b) -> fire.run());
    minN.valueProperty().addListener((o, a, b) -> fire.run());
    maxRows.valueProperty().addListener((o, a, b) -> fire.run());
    showUp.selectedProperty().addListener((o, a, b) -> fire.run());
    showDown.selectedProperty().addListener((o, a, b) -> fire.run());
    showSimilar.selectedProperty().addListener((o, a, b) -> fire.run());
    wireSpinnerCommit(minAbsDelta, fire);
    wireSpinnerCommit(maxQ, fire);
    wireSpinnerCommit(minN, fire);
    wireSpinnerCommit(maxRows, fire);
    wireSpinnerCommit(minMeanPrefilter, () -> {
      if (refreshing) {
        return;
      }
      Double v = minMeanPrefilter.getValue();
      AnalysisSession.get().setMinMeanPrefilter(v != null ? v : 0.0);
      ComparisonContext.get().writeToDocument(ProjectSessionState.get().getDocument());
      ProjectSessionState.get().markDirty();
      fire.run();
    });
    effectChoice.valueProperty().addListener((o, a, b) -> {
      if (refreshing || b == null) {
        return;
      }
      AnalysisSession.get().setEffectMode(b, true);
      ComparisonContext.get().writeToDocument(ProjectSessionState.get().getDocument());
      ProjectSessionState.get().markDirty();
      refreshScaleDetectLabel();
      fire.run();
    });
    AnalysisSession.get().addListener(session -> {
      if (refreshing) {
        return;
      }
      refreshing = true;
      try {
        EffectMode mode = session.getEffectMode();
        if (effectChoice.getValue() != mode) {
          effectChoice.setValue(mode);
        }
        double minMean = session.getMinMeanPrefilter();
        Double cur = minMeanPrefilter.getValue();
        if (cur == null || Math.abs(cur - minMean) > 1e-12) {
          minMeanPrefilter.getValueFactory().setValue(minMean);
        }
        refreshScaleDetectLabel();
      } finally {
        refreshing = false;
      }
    });
    ComparisonContext.get().revisionProperty().addListener((o, a, b) -> refreshDirectionLabels());
  }

  /** Editable spinners only commit on Enter by default — also commit on focus loss. */
  private static <T> void wireSpinnerCommit(Spinner<T> spinner, Runnable onCommit) {
    spinner.focusedProperty().addListener((o, was, isNow) -> {
      if (!isNow) {
        commitSpinner(spinner);
        if (onCommit != null) {
          onCommit.run();
        }
      }
    });
    spinner.getEditor().setOnAction(e -> {
      commitSpinner(spinner);
      if (onCommit != null) {
        onCommit.run();
      }
    });
  }

  private void commitSpinners() {
    commitSpinner(minAbsDelta);
    commitSpinner(maxQ);
    commitSpinner(minN);
    commitSpinner(maxRows);
    commitSpinner(minMeanPrefilter);
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

  private static ScrollPane wrapScroll(Node content) {
    ScrollPane scroll = new ScrollPane(content);
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    scroll.getStyleClass().add("filter-scroll");
    return scroll;
  }

  private static HBox fieldRow(String labelText, Node control, String tooltip) {
    Label label = new Label(labelText);
    label.getStyleClass().addAll("section-header", "filter-slider-label");
    label.setMinWidth(110);
    label.setPrefWidth(110);
    if (tooltip != null && !tooltip.isBlank()) {
      label.setTooltip(new Tooltip(tooltip));
    }
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox row = new HBox(8, label, spacer, control);
    row.setAlignment(Pos.CENTER_LEFT);
    return row;
  }
}
