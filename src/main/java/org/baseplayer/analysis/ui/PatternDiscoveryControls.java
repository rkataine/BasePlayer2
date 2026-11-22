package org.baseplayer.analysis.ui;

import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.pattern.SamplePatternEngine;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Sample Comparison controls: discover expression/signal patterns (clusters) and
 * promote them to sample groups for Group comparison.
 */
public final class PatternDiscoveryControls {

  private final Spinner<Integer> topN = new Spinner<>();
  private final Spinner<Integer> kClusters = new Spinner<>();
  private final Spinner<Double> minMean = new Spinner<>();
  private final Button discoverButton = new Button("Discover patterns");
  private final Button applyGroupsButton = new Button("Apply as groups");
  private final Button showHeatmapButton = new Button("Show heatmap");
  private final Label statusLabel = new Label();

  public PatternDiscoveryControls() {
    topN.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(50, 50_000, 2000, 100));
    topN.setEditable(true);
    topN.setPrefWidth(90);

    kClusters.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(2, 50, 2, 1));
    kClusters.setEditable(true);
    kClusters.setPrefWidth(90);

    minMean.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 1_000_000, 0, 0.1));
    minMean.setEditable(true);
    minMean.setPrefWidth(90);

    discoverButton.getStyleClass().add("primary-button");
    applyGroupsButton.getStyleClass().add("secondary-button");
    applyGroupsButton.setDisable(true);
    showHeatmapButton.getStyleClass().add("secondary-button");
    showHeatmapButton.setDisable(true);

    statusLabel.getStyleClass().add("value-label");
    statusLabel.setWrapText(true);

    Tooltip.install(topN, new Tooltip("Use the top N most variable features for sample clustering."));
    Tooltip.install(kClusters, new Tooltip("Number of sample pattern groups to cut from the dendrogram."));
    Tooltip.install(minMean, new Tooltip("Drop features with mean below this before selecting top-N (0 = off)."));
    Tooltip.install(discoverButton, new Tooltip(
        "Cluster samples by expression/signal similarity and list marker features (cluster vs rest)."));
    Tooltip.install(applyGroupsButton, new Tooltip(
        "Create/replace Sample Groups named Pattern 1…k from the last discovery."));
    Tooltip.install(showHeatmapButton, new Tooltip(
        "Open a features×samples heatmap in dendrogram order with group and pattern annotation bars."));
  }

  public Node createEmbedded() {
    Label help = new Label(
        "Cluster samples by similar signal patterns, then Apply as groups "
            + "for Group comparison DE.");
    help.getStyleClass().add("subsection-label");
    help.setWrapText(true);

    HBox actions = new HBox(8, discoverButton, applyGroupsButton, showHeatmapButton);
    actions.setAlignment(Pos.CENTER_LEFT);

    VBox box = new VBox(8,
        help,
        actions,
        fieldRow("Clusters k", kClusters),
        fieldRow("Top features", topN),
        fieldRow("Min mean", minMean),
        statusLabel);
    box.setPadding(new Insets(4, 0, 8, 0));
    return box;
  }

  public SamplePatternEngine.Settings buildSettings() {
    commit(topN);
    commit(kClusters);
    commit(minMean);
    Integer n = topN.getValue();
    Integer k = kClusters.getValue();
    Double mean = minMean.getValue();
    return new SamplePatternEngine.Settings(
        n != null ? n : 2000,
        k != null ? k : 2,
        mean != null ? mean : 0.0,
        true);
  }

  public void setOnDiscover(Runnable action) {
    discoverButton.setOnAction(e -> {
      if (action != null) {
        action.run();
      }
    });
  }

  public void setOnApplyGroups(Runnable action) {
    applyGroupsButton.setOnAction(e -> {
      if (action != null) {
        action.run();
      }
    });
  }

  public void setOnShowHeatmap(Runnable action) {
    showHeatmapButton.setOnAction(e -> {
      if (action != null) {
        action.run();
      }
    });
  }

  public void setStatus(String text) {
    statusLabel.setText(text != null ? text : "");
  }

  public void setBusy(boolean busy) {
    discoverButton.setDisable(busy);
    if (!busy) {
      refreshApplyEnabled();
    } else {
      applyGroupsButton.setDisable(true);
      showHeatmapButton.setDisable(true);
    }
  }

  public void refreshApplyEnabled() {
    SamplePatternEngine.Result result = AnalysisSession.get().getLastPatternResult();
    applyGroupsButton.setDisable(result == null);
    showHeatmapButton.setDisable(result == null || !result.hasHeatmapData());
  }

  public Label statusLabel() {
    return statusLabel;
  }

  private static HBox fieldRow(String labelText, Node control) {
    Label label = new Label(labelText);
    label.getStyleClass().addAll("section-header", "filter-slider-label");
    label.setMinWidth(110);
    label.setPrefWidth(110);
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox row = new HBox(8, label, spacer, control);
    row.setAlignment(Pos.CENTER_LEFT);
    return row;
  }

  private static <T> void commit(Spinner<T> spinner) {
    if (spinner == null || spinner.getValueFactory() == null || spinner.getEditor() == null) {
      return;
    }
    try {
      String text = spinner.getEditor().getText();
      T converted = spinner.getValueFactory().getConverter().fromString(text);
      spinner.getValueFactory().setValue(converted);
    } catch (RuntimeException ignored) {
      // keep previous
    }
  }
}
