package org.baseplayer.analysis.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.baseplayer.MainApp;
import org.baseplayer.analysis.pattern.SamplePatternEngine;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.ui.theme.AppTheme;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Clustered features×samples heatmap for Pattern Discovery results.
 * Columns follow dendrogram leaf order; annotation strips show Sample Groups
 * and discovered Pattern clusters.
 */
public final class SignalPatternHeatmap {

  private static final int DEFAULT_MAX_ROWS = 200;
  private static final double Z_CLIP = 2.5;
  private static final double LEFT_MARGIN = 88;
  private static final double RIGHT_MARGIN = 16;
  private static final double TOP_MARGIN = 12;
  private static final double BOTTOM_MARGIN = 56;
  private static final double ANNOT_H = 12;
  private static final double ANNOT_GAP = 3;
  private static final double LEGEND_H = 18;

  private static final Color[] PATTERN_PALETTE = {
      Color.web("#e41a1c"),
      Color.web("#377eb8"),
      Color.web("#4daf4a"),
      Color.web("#984ea3"),
      Color.web("#ff7f00"),
      Color.web("#a65628"),
      Color.web("#f781bf"),
      Color.web("#999999"),
      Color.web("#66c2a5"),
      Color.web("#fc8d62"),
      Color.web("#8da0cb"),
      Color.web("#e78ac3")
  };

  private final SamplePatternEngine.Result result;
  private final Stage stage;
  private final Canvas canvas = new Canvas(900, 640);
  private final Label statusLabel = new Label();
  private final Spinner<Integer> maxRowsSpinner = new Spinner<>();
  private final Tooltip hoverTip = new Tooltip();

  private int plotRows;
  private double heatX;
  private double heatY;
  private double heatW;
  private double heatH;
  private double colW;
  private double rowH;

  public SignalPatternHeatmap(SamplePatternEngine.Result result) {
    if (result == null || !result.hasHeatmapData()) {
      throw new IllegalArgumentException("Pattern result has no heatmap data");
    }
    this.result = result;

    stage = new Stage(StageStyle.DECORATED);
    stage.initModality(Modality.NONE);
    stage.setResizable(true);
    stage.setTitle("Pattern heatmap");
    stage.setMinWidth(640);
    stage.setMinHeight(480);

    Window main = MainApp.stage;
    if (main != null) {
      main.showingProperty().addListener((obs, wasShowing, isShowing) -> {
        if (!isShowing && stage.isShowing()) {
          stage.hide();
        }
      });
    }

    int featureCount = result.selectedFeatures().size();
    int initialRows = Math.min(DEFAULT_MAX_ROWS, featureCount);
    maxRowsSpinner.setValueFactory(
        new SpinnerValueFactory.IntegerSpinnerValueFactory(10, Math.max(10, featureCount), initialRows, 10));
    maxRowsSpinner.setEditable(true);
    maxRowsSpinner.setPrefWidth(90);
    maxRowsSpinner.valueProperty().addListener((o, a, b) -> redraw());

    hoverTip.setShowDelay(Duration.millis(80));
    hoverTip.setHideDelay(Duration.millis(80));
    Tooltip.install(canvas, hoverTip);

    buildUi();
    canvas.addEventHandler(MouseEvent.MOUSE_MOVED, this::onMouseMoved);
    canvas.addEventHandler(MouseEvent.MOUSE_EXITED, e -> hoverTip.setText(""));
    canvas.widthProperty().addListener((o, a, b) -> redraw());
    canvas.heightProperty().addListener((o, a, b) -> redraw());
  }

  public void show() {
    redraw();
    stage.show();
    stage.toFront();
  }

  private void buildUi() {
    Label rowsLabel = new Label("Plot features");
    rowsLabel.getStyleClass().add("section-header");
    HBox toolbar = new HBox(8, rowsLabel, maxRowsSpinner);
    toolbar.setAlignment(Pos.CENTER_LEFT);

    statusLabel.getStyleClass().add("value-label");
    statusLabel.setWrapText(true);

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox top = new HBox(12, toolbar, spacer, statusLabel);
    top.setAlignment(Pos.CENTER_LEFT);
    top.setPadding(new Insets(0, 0, 8, 0));

    StackPane canvasWrap = new StackPane(canvas);
    canvasWrap.setMinSize(0, 0);
    VBox.setVgrow(canvasWrap, Priority.ALWAYS);
    canvas.widthProperty().bind(canvasWrap.widthProperty());
    canvas.heightProperty().bind(canvasWrap.heightProperty());

    BorderPane root = new BorderPane();
    root.setPadding(new Insets(12));
    root.getStyleClass().addAll("root-pane", "dialog-surface", "filter-panel");
    root.setTop(top);
    root.setCenter(canvasWrap);

    Scene scene = new Scene(root, 960, 720);
    applyTheme(scene);
    scene.setFill(AppTheme.chrome().panel());
    stage.setScene(scene);
  }

  private static void applyTheme(Scene scene) {
    AppTheme.setDark(MainApp.darkMode);
    scene.getStylesheets().clear();
    if (MainApp.darkMode) {
      scene.getStylesheets().add(MainApp.getResource("theme-dark.css").toExternalForm());
    } else {
      scene.getStylesheets().add(MainApp.getResource("theme-light.css").toExternalForm());
    }
    scene.getStylesheets().add(MainApp.getResource("application.css").toExternalForm());
  }

  private void redraw() {
    commitSpinner(maxRowsSpinner);
    Integer maxRows = maxRowsSpinner.getValue();
    int featureCount = result.selectedFeatures().size();
    plotRows = Math.max(1, Math.min(maxRows != null ? maxRows : DEFAULT_MAX_ROWS, featureCount));

    GraphicsContext g = canvas.getGraphicsContext2D();
    double w = canvas.getWidth();
    double h = canvas.getHeight();
    if (w <= 1 || h <= 1) {
      return;
    }

    Color bg = AppTheme.canvas().trackBackground();
    Color ink = AppTheme.canvas().axisInk();
    Color muted = AppTheme.chrome().textMuted();

    g.setFill(bg);
    g.fillRect(0, 0, w, h);

    int nSamples = result.tracks().size();
    boolean showGroups = hasAnySampleGroup(result.tracks());
    int annotRows = 1 + (showGroups ? 1 : 0);
    double annotBlock = annotRows * ANNOT_H + (annotRows - 1) * ANNOT_GAP + 6;

    heatX = LEFT_MARGIN;
    heatY = TOP_MARGIN + annotBlock + LEGEND_H + 8;
    heatW = Math.max(40, w - LEFT_MARGIN - RIGHT_MARGIN);
    heatH = Math.max(40, h - heatY - BOTTOM_MARGIN);
    colW = heatW / nSamples;
    rowH = heatH / plotRows;

    drawLegend(g, heatX, TOP_MARGIN, Math.min(160, heatW * 0.35), LEGEND_H, ink, muted);
    drawAnnotationBars(g, showGroups, ink, muted);
    drawHeatmapBody(g);
    drawLabels(g, ink, muted);

    statusLabel.setText(String.format(Locale.ROOT,
        "%d samples · showing %d / %d features · k=%d · %s · columns = dendrogram order",
        nSamples,
        plotRows,
        featureCount,
        result.k(),
        result.zScored() ? "z-score" : "raw values"));
  }

  private void drawLegend(
      GraphicsContext g, double x, double y, double width, double height, Color ink, Color muted) {
    int steps = 64;
    for (int i = 0; i < steps; i++) {
      double t = i / (double) (steps - 1);
      double v = result.zScored() ? (-Z_CLIP + t * 2 * Z_CLIP) : t;
      g.setFill(valueColor(v));
      g.fillRect(x + i * (width / steps), y, width / steps + 0.5, height);
    }
    g.setStroke(muted);
    g.setLineWidth(1);
    g.strokeRect(x, y, width, height);
    g.setFill(ink);
    g.setFont(javafx.scene.text.Font.font(10));
    if (result.zScored()) {
      g.fillText(String.format(Locale.ROOT, "−%.1f", Z_CLIP), x, y + height + 12);
      g.fillText("0", x + width / 2 - 3, y + height + 12);
      g.fillText(String.format(Locale.ROOT, "+%.1f", Z_CLIP), x + width - 18, y + height + 12);
    } else {
      g.fillText("low", x, y + height + 12);
      g.fillText("high", x + width - 22, y + height + 12);
    }
  }

  private void drawAnnotationBars(GraphicsContext g, boolean showGroups, Color ink, Color muted) {
    int[] leaf = result.leafOrder();
    List<SampleTrack> tracks = result.tracks();
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();

    double y = TOP_MARGIN + LEGEND_H + 8;
    if (showGroups) {
      g.setFill(muted);
      g.setFont(javafx.scene.text.Font.font(10));
      g.fillText("Groups", 8, y + ANNOT_H - 2);
      for (int col = 0; col < leaf.length; col++) {
        SampleTrack track = tracks.get(leaf[col]);
        Color c = groupColor(track, registry);
        g.setFill(c);
        g.fillRect(heatX + col * colW, y, Math.max(1, colW), ANNOT_H);
      }
      g.setStroke(muted.deriveColor(0, 1, 1, 0.35));
      g.strokeRect(heatX, y, heatW, ANNOT_H);
      y += ANNOT_H + ANNOT_GAP;
    }

    g.setFill(muted);
    g.setFont(javafx.scene.text.Font.font(10));
    g.fillText("Pattern", 8, y + ANNOT_H - 2);
    int[] clusters = result.clusterOfTrack();
    for (int col = 0; col < leaf.length; col++) {
      int sampleIdx = leaf[col];
      int cluster = clusters[sampleIdx];
      g.setFill(patternColor(cluster));
      g.fillRect(heatX + col * colW, y, Math.max(1, colW), ANNOT_H);
    }
    g.setStroke(muted.deriveColor(0, 1, 1, 0.35));
    g.strokeRect(heatX, y, heatW, ANNOT_H);
  }

  private void drawHeatmapBody(GraphicsContext g) {
    int[] leaf = result.leafOrder();
    double[][] matrix = result.sampleFeature();
    double rawMin = Double.POSITIVE_INFINITY;
    double rawMax = Double.NEGATIVE_INFINITY;
    if (!result.zScored()) {
      for (int r = 0; r < plotRows; r++) {
        for (int col = 0; col < leaf.length; col++) {
          double v = matrix[leaf[col]][r];
          if (Double.isFinite(v)) {
            rawMin = Math.min(rawMin, v);
            rawMax = Math.max(rawMax, v);
          }
        }
      }
      if (!(rawMax > rawMin)) {
        rawMin = 0;
        rawMax = 1;
      }
    }

    for (int r = 0; r < plotRows; r++) {
      double y = heatY + r * rowH;
      for (int col = 0; col < leaf.length; col++) {
        double v = matrix[leaf[col]][r];
        double mapped = result.zScored()
            ? v
            : (v - rawMin) / (rawMax - rawMin);
        g.setFill(valueColor(mapped));
        g.fillRect(heatX + col * colW, y, Math.max(1, colW + 0.5), Math.max(1, rowH + 0.5));
      }
    }
    g.setStroke(AppTheme.chrome().border());
    g.setLineWidth(1);
    g.strokeRect(heatX, heatY, heatW, heatH);
  }

  private void drawLabels(GraphicsContext g, Color ink, Color muted) {
    g.setFill(ink);
    g.setFont(javafx.scene.text.Font.font(10));
    List<String> features = result.selectedFeatures();
    int labelStep = Math.max(1, (int) Math.ceil(12.0 / Math.max(rowH, 0.1)));
    for (int r = 0; r < plotRows; r += labelStep) {
      if (rowH < 4 && r % Math.max(1, plotRows / 40) != 0) {
        continue;
      }
      String id = features.get(r);
      String label = id.length() > 12 ? id.substring(0, 11) + "…" : id;
      double y = heatY + r * rowH + Math.min(rowH, 11);
      g.fillText(label, 4, y);
    }

    List<SampleTrack> tracks = result.tracks();
    int[] leaf = result.leafOrder();
    int sampleLabelStep = Math.max(1, (int) Math.ceil(14.0 / Math.max(colW, 0.1)));
    g.setFill(muted);
    for (int col = 0; col < leaf.length; col += sampleLabelStep) {
      String name = tracks.get(leaf[col]).getName();
      if (name == null) {
        name = "?";
      }
      String label = name.length() > 10 ? name.substring(0, 9) + "…" : name;
      double x = heatX + col * colW + Math.max(0, (colW - 4) / 2);
      g.save();
      g.translate(x, heatY + heatH + 8);
      g.rotate(45);
      g.fillText(label, 0, 0);
      g.restore();
    }
  }

  private void onMouseMoved(MouseEvent e) {
    if (colW <= 0 || rowH <= 0) {
      return;
    }
    double x = e.getX();
    double y = e.getY();
    if (x < heatX || x >= heatX + heatW || y < heatY || y >= heatY + heatH) {
      hoverTip.setText("");
      return;
    }
    int col = (int) ((x - heatX) / colW);
    int row = (int) ((y - heatY) / rowH);
    if (col < 0 || col >= result.tracks().size() || row < 0 || row >= plotRows) {
      hoverTip.setText("");
      return;
    }
    int sampleIdx = result.leafOrder()[col];
    SampleTrack track = result.tracks().get(sampleIdx);
    String feature = result.selectedFeatures().get(row);
    double value = result.sampleFeature()[sampleIdx][row];
    int cluster = result.clusterOfTrack()[sampleIdx];
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    String groups = groupNames(track, registry);
    hoverTip.setText(String.format(Locale.ROOT,
        "%s\n%s\nPattern %d%s\nvalue=%s",
        track.getName(),
        feature,
        cluster + 1,
        groups.isEmpty() ? "" : "\ngroups: " + groups,
        formatValue(value)));
  }

  private static String formatValue(double v) {
    if (!Double.isFinite(v)) {
      return "NA";
    }
    return String.format(Locale.ROOT, "%.3g", v);
  }

  private Color valueColor(double v) {
    if (!Double.isFinite(v)) {
      return Color.GRAY;
    }
    if (result.zScored()) {
      double t = Math.max(-Z_CLIP, Math.min(Z_CLIP, v)) / Z_CLIP; // [-1,1]
      if (t < 0) {
        return lerp(Color.web("#2166ac"), Color.web("#f7f7f7"), t + 1);
      }
      return lerp(Color.web("#f7f7f7"), Color.web("#b2182b"), t);
    }
    double t = Math.max(0, Math.min(1, v));
    return lerp(Color.web("#f7fbff"), Color.web("#08306b"), t);
  }

  private static Color lerp(Color a, Color b, double t) {
    t = Math.max(0, Math.min(1, t));
    return new Color(
        a.getRed() + (b.getRed() - a.getRed()) * t,
        a.getGreen() + (b.getGreen() - a.getGreen()) * t,
        a.getBlue() + (b.getBlue() - a.getBlue()) * t,
        1.0);
  }

  private static Color patternColor(int cluster) {
    if (cluster < 0) {
      return Color.GRAY;
    }
    return PATTERN_PALETTE[cluster % PATTERN_PALETTE.length];
  }

  private static Color groupColor(SampleTrack track, SampleRegistry registry) {
    if (track == null || registry == null || !track.hasGroup()) {
      return Color.web("#555555");
    }
    SampleGroup group = registry.getSampleGroup(track.getGroupId());
    return group != null ? group.getColor() : Color.web("#555555");
  }

  private static boolean hasAnySampleGroup(List<SampleTrack> tracks) {
    if (tracks == null) {
      return false;
    }
    for (SampleTrack t : tracks) {
      if (t != null && t.hasGroup()) {
        return true;
      }
    }
    return false;
  }

  private static String groupNames(SampleTrack track, SampleRegistry registry) {
    if (track == null || registry == null) {
      return "";
    }
    List<String> names = new ArrayList<>();
    for (int id : track.getGroupIds()) {
      SampleGroup g = registry.getSampleGroup(id);
      if (g != null) {
        names.add(g.getName());
      }
    }
    return String.join(", ", names);
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
      // keep previous
    }
  }
}
