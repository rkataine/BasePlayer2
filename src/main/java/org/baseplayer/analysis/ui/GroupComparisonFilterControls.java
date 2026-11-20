package org.baseplayer.analysis.ui;

import java.util.List;

import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.DifferenceThresholds;
import org.baseplayer.components.ColoredToggle;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Shared difference-threshold controls for Signal and mutation/SV group comparison.
 */
public final class GroupComparisonFilterControls {

  private final ColoredToggle thresholdsEnabled = new ColoredToggle("Filter by thresholds");
  private final Spinner<Double> minAbsDelta = new Spinner<>();
  private final Spinner<Double> maxQ = new Spinner<>();
  private final Spinner<Integer> minN = new Spinner<>();
  private final Spinner<Integer> maxRows = new Spinner<>();
  private final ColoredToggle showUp = new ColoredToggle("Different / higher outside baseline");
  private final ColoredToggle showDown = new ColoredToggle("Higher in baseline");
  private final ColoredToggle showSimilar = new ColoredToggle("Similar");
  private final Label directionHintLabel = new Label();
  private boolean refreshing;
  private Runnable onChanged;

  public GroupComparisonFilterControls() {
    minAbsDelta.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 20, 0.5, 0.1));
    minAbsDelta.setEditable(true);
    minAbsDelta.setPrefWidth(80);

    maxQ.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 1, 0.05, 0.01));
    maxQ.setEditable(true);
    maxQ.setPrefWidth(80);

    minN.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 1000, 2, 1));
    minN.setEditable(true);
    minN.setPrefWidth(70);

    maxRows.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 1_000_000, 0, 500));
    maxRows.setEditable(true);
    maxRows.setPrefWidth(90);

    thresholdsEnabled.setSelected(false);
    showUp.setSelected(true);
    showDown.setSelected(true);
    showSimilar.setSelected(true);

    directionHintLabel.setWrapText(true);
    directionHintLabel.setStyle("-fx-font-size: 11px; -fx-opacity: 0.8;");

    wire();
    refreshDirectionLabels();
  }

  public void setOnChanged(Runnable onChanged) {
    this.onChanged = onChanged;
  }

  public Node createPane() {
    Label paramsHeader = new Label("Difference thresholds");
    paramsHeader.getStyleClass().add("section-header");
    FlowPane thrRow = new FlowPane(10, 6);
    thrRow.getChildren().addAll(
        thresholdsEnabled,
        labeled("Min |Δ|", minAbsDelta),
        labeled("Max q", maxQ),
        labeled("Min hits/group", minN),
        labeled("Top N (0=all)", maxRows));

    Label showHeader = new Label("Show in table");
    showHeader.getStyleClass().add("section-header");
    FlowPane dirRow = new FlowPane(10, 6);
    dirRow.getChildren().addAll(showUp, showDown, showSimilar);

    VBox root = new VBox(8, paramsHeader, thrRow, showHeader, dirRow, directionHintLabel);
    return root;
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
      refreshDirectionLabels();
    } finally {
      refreshing = false;
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
      directionHintLabel.setText(
          "Baseline: " + baseName + ". All genes stay in the result; turn on Filter by "
              + "thresholds to hide rows by Min |Δ| / Max q / direction / Top N.");
    } else {
      showUp.setText("Different between groups");
      showDown.setDisable(true);
      showDown.setVisible(false);
      showDown.setManaged(false);
      directionHintLabel.setText(
          "With 3+ groups, Diff shows every group (↑ highest / ↓ lowest). "
              + "All genes stay in the result; enable Filter by thresholds to hide rows.");
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

  private static HBox labeled(String text, Node node) {
    HBox box = new HBox(6, new Label(text), node);
    box.setAlignment(Pos.CENTER_LEFT);
    return box;
  }
}
