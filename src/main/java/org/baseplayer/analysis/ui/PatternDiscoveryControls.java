package org.baseplayer.analysis.ui;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.groupstat.GroupStatTest;
import org.baseplayer.analysis.pattern.SamplePatternEngine;
import org.baseplayer.components.HelpInfoButton;
import org.baseplayer.components.InfoModal;

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
  private AnalysisMode patternMode = AnalysisMode.SIGNAL;

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
    Label title = new Label("Patterns");
    title.getStyleClass().add("section-header");
    Button help = HelpInfoButton.create(
        "Pattern discovery",
        "Sample pattern search",
        PatternDiscoveryControls::helpSections);
    HBox titleRow = new HBox(4, title, help);
    titleRow.setAlignment(Pos.CENTER_LEFT);

    HBox actions = new HBox(8, discoverButton, applyGroupsButton, showHeatmapButton);
    actions.setAlignment(Pos.CENTER_LEFT);

    VBox box = new VBox(8,
        titleRow,
        actions,
        fieldRow("Clusters k", kClusters),
        fieldRow("Top features", topN),
        fieldRow("Min mean", minMean),
        statusLabel);
    box.setPadding(new Insets(4, 0, 8, 0));
    return box;
  }

  /** Help sections for the Patterns “?” button (same InfoModal pattern as Group comparison). */
  static List<InfoModal.Section> helpSections() {
    GroupStatTest test = AnalysisSession.get().getGroupStatTest();
    if (test == null) {
      test = GroupStatTest.AUTO;
    }

    List<InfoModal.Section> sections = new ArrayList<>();
    sections.add(new InfoModal.Section(
        "Overview",
        "Unsupervised search for groups of samples that share similar patterns on the "
            + "current feature matrix. For Signal, features are value units (genes/windows/"
            + "native features). For Point/SV, features are genes with binary carrier scores "
            + "(0/1) from filter-passing variants. Marker features (each pattern vs the rest) "
            + "are listed in the Group comparison results table. This is clustering + marker "
            + "scoring — not differential analysis of predefined sample groups. Use Group "
            + "comparison after Apply as groups when you want DE between named groups."));
    sections.add(new InfoModal.Section(
        "Input data",
        "Patterns use the same filter-passing input as Group comparison. "
            + "Point/SV: set Variant Filters first (types, effects, Q, DP, AF, sample tags, …); "
            + "Discover scores only those variants. Types and effects are include/exclude only "
            + "— e.g. uncheck synonymous to run missense-focused patterns; there is no automatic "
            + "missense-vs-truncating split inside Discover. "
            + "Signal: uses the current value/feature matrix and its thresholds."));
    sections.add(new InfoModal.Section(
        "Clustering method",
        "1) Optional Min mean filter drops low-mean features. "
            + "2) Keep the Top features most variable across samples. "
            + "3) Z-score features, build sample–sample distances from correlation, "
            + "then average-linkage (UPGMA) hierarchical clustering. "
            + "4) Cut the dendrogram into Clusters k groups (Pattern 1…k)."));
    sections.add(new InfoModal.Section(
        "Marker test",
        "For each feature, every pattern cluster is tested against the remaining samples; "
            + "the strongest (lowest-p) contrast is kept, with Benjamini–Hochberg FDR. "
            + "The test follows the Group comparison Test setting "
            + "(currently: " + test.displayName() + "). "
            + "Auto / Moderated → moderated t on continuous values; "
            + "Rank → Mann–Whitney; otherwise Welch. "
            + "For binary mutation carriers, Auto typically selects Fisher."));
    sections.add(new InfoModal.Section(
        "Parameters",
        "Clusters k — number of pattern groups cut from the dendrogram. "
            + "Top features — how many most-variable features enter clustering. "
            + "Min mean — drop features below this mean before selecting top-N (0 = off)."));
    sections.add(new InfoModal.Section(
        "Apply and heatmap",
        "Apply as groups creates or replaces Sample Groups named Pattern 1…k from the "
            + "last discovery. Show heatmap opens a features×samples view in dendrogram "
            + "order with group and pattern annotation bars."));
    return sections;
  }

  /** Which AnalysisSession pattern slot Apply/Heatmap read (Signal vs Point/SV). */
  public void setPatternMode(AnalysisMode mode) {
    this.patternMode = mode != null ? mode : AnalysisMode.SIGNAL;
  }

  /** Refresh Apply/Heatmap using a mode-specific pattern result. */
  public void refreshApplyEnabled(AnalysisMode mode) {
    SamplePatternEngine.Result result = AnalysisSession.get().getLastPatternResult(
        mode != null ? mode : patternMode);
    applyGroupsButton.setDisable(result == null);
    showHeatmapButton.setDisable(result == null || !result.hasHeatmapData());
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
    refreshApplyEnabled(patternMode);
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
