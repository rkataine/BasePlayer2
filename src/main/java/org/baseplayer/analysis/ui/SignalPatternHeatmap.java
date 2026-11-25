package org.baseplayer.analysis.ui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.baseplayer.MainApp;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.pattern.SamplePatternEngine;
import org.baseplayer.controllers.commands.NavigationCommands;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.ui.theme.AppTheme;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Features×samples heatmap for Pattern Discovery or Group comparison results.
 * Columns follow dendrogram / cohort leaf order; annotation strips show Sample
 * Groups and Pattern / Cohort clusters.
 *
 * <p>Canvas is content-sized so every sample gets a readable column width;
 * pan via {@link ScrollPane}, zoom via Ctrl/Cmd+wheel or toolbar, click genes
 * to navigate.
 */
public final class SignalPatternHeatmap {

  /** Open heatmap stages — lowered before native FileChoosers so dialogs are visible. */
  private static final CopyOnWriteArrayList<WeakReference<Stage>> OPEN_STAGES =
      new CopyOnWriteArrayList<>();

  private static final int DEFAULT_MAX_ROWS = 200;
  private static final double Z_CLIP = 2.5;
  private static final double LEFT_MARGIN = 88;
  private static final double RIGHT_MARGIN = 16;
  private static final double TOP_MARGIN = 12;
  private static final double BOTTOM_MARGIN = 72;
  private static final double ANNOT_H = 12;
  private static final double ANNOT_GAP = 3;
  private static final double LEGEND_H = 18;
  /** Space under the color bar for −2.5 / 0 / +2.5 (or low/high) labels. */
  private static final double LEGEND_LABEL_H = 14;
  private static final double LEGEND_TO_ANNOT_GAP = 6;
  private static final double BASE_COL_W = 16;
  private static final double BASE_ROW_H = 12;
  private static final double ZOOM_MIN = 0.05;
  private static final double ZOOM_MAX = 4.0;
  private static final double ZOOM_STEP = 1.15;

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
  private final Map<String, GroupStatResult.Row> markerByUnit;
  private final String clusterBarLabel;
  private final Stage stage;
  private final Canvas canvas = new Canvas(900, 640);
  private final ScrollPane scroll = new ScrollPane();
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
  private double groupsAnnotY = -1;
  private double patternAnnotY = -1;
  private double zoom = 1.0;
  private String stickyStatus;

  public SignalPatternHeatmap(SamplePatternEngine.Result result) {
    this(result, "Pattern heatmap", "Pattern", null);
  }

  public SignalPatternHeatmap(
      SamplePatternEngine.Result result, String title, String clusterBarLabel) {
    this(result, title, clusterBarLabel, null);
  }

  public SignalPatternHeatmap(
      SamplePatternEngine.Result result, String title, String clusterBarLabel, Window owner) {
    if (result == null || !result.hasHeatmapData()) {
      throw new IllegalArgumentException("Result has no heatmap data");
    }
    this.result = result;
    this.clusterBarLabel =
        clusterBarLabel != null && !clusterBarLabel.isBlank() ? clusterBarLabel : "Pattern";
    this.markerByUnit = result.markers() != null
        ? result.markers().indexByUnit()
        : Map.of();

    // Independent stage (no initOwner): owned children steal focus from MainApp on
    // Linux/GTK and File/Tools menus stop opening until the child is closed.
    stage = new Stage(StageStyle.DECORATED);
    stage.initModality(Modality.NONE);
    stage.setResizable(true);
    stage.setTitle(title != null && !title.isBlank() ? title : "Heatmap");
    stage.setMinWidth(640);
    stage.setMinHeight(480);

    Window host = owner != null ? owner : MainApp.stage;
    if (host != null) {
      host.showingProperty().addListener((obs, wasShowing, isShowing) -> {
        if (!isShowing && stage.isShowing()) {
          stage.hide();
        }
      });
    }
    stage.setOnShown(e -> trackOpen(stage));
    stage.setOnHidden(e -> untrack(stage));

    int featureCount = result.selectedFeatures().size();
    int initialRows = Math.min(DEFAULT_MAX_ROWS, featureCount);
    maxRowsSpinner.setValueFactory(
        new SpinnerValueFactory.IntegerSpinnerValueFactory(10, Math.max(10, featureCount), initialRows, 10));
    maxRowsSpinner.setEditable(true);
    maxRowsSpinner.setPrefWidth(90);
    maxRowsSpinner.valueProperty().addListener((o, a, b) -> {
      stickyStatus = null;
      if (stage.isShowing()) {
        Platform.runLater(this::fitToViewport);
      } else {
        redraw();
      }
    });

    hoverTip.setShowDelay(Duration.millis(80));
    hoverTip.setHideDelay(Duration.millis(80));
    Tooltip.install(canvas, hoverTip);

    buildUi();
    canvas.addEventHandler(MouseEvent.MOUSE_MOVED, this::onMouseMoved);
    canvas.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
      hoverTip.setText("");
      canvas.setCursor(Cursor.DEFAULT);
    });
    canvas.addEventHandler(MouseEvent.MOUSE_CLICKED, this::onMouseClicked);
    canvas.addEventFilter(ScrollEvent.SCROLL, this::onScrollZoom);
  }

  public void show() {
    stage.show();
    stage.toFront();
    // Viewport size is only valid after the stage is laid out.
    Platform.runLater(this::fitToViewport);
  }

  /**
   * Temporarily hide every open heatmap so a native FileChooser / AppDialog is
   * not covered. Pair with {@link #restoreAfterNativeDialog()}.
   */
  public static void prepareForNativeDialog() {
    pruneOpenStages();
    for (WeakReference<Stage> ref : OPEN_STAGES) {
      Stage s = ref.get();
      if (s != null && s.isShowing()) {
        s.setAlwaysOnTop(false);
        // Hide (not toBack): toBack still leaves a focused Stage that can block
        // MainApp MenuBar clicks on Linux after the dialog closes.
        s.hide();
        SUPPRESSED_FOR_DIALOG.add(new WeakReference<>(s));
      }
    }
  }

  /** Re-show heatmaps; focus {@link MainApp#stage} when present. */
  public static void restoreAfterNativeDialog() {
    restoreAfterNativeDialog(MainApp.stage);
  }

  /**
   * Re-show heatmaps hidden by {@link #prepareForNativeDialog()}, then raise
   * {@code preferFocus} (the FileChooser / dialog owner). Never raise MainApp
   * over Analysis when Analysis owns the native dialog — that buries the chooser
   * on Linux/GTK.
   */
  public static void restoreAfterNativeDialog(Window preferFocus) {
    List<WeakReference<Stage>> pending = new ArrayList<>(SUPPRESSED_FOR_DIALOG);
    SUPPRESSED_FOR_DIALOG.clear();
    for (WeakReference<Stage> ref : pending) {
      Stage s = ref.get();
      if (s != null && !s.isShowing()) {
        s.show();
        // Intentionally no toFront — keep preferFocus on top for native dialogs.
      }
    }
    raiseWindow(preferFocus != null ? preferFocus : MainApp.stage);
  }

  /** Bring {@code window} to the front so a native FileChooser stacks above it. */
  public static void raiseWindow(Window window) {
    if (window instanceof Stage stage && stage.isShowing()) {
      stage.toFront();
      stage.requestFocus();
    } else if (window != null && window.isShowing()) {
      window.requestFocus();
    }
  }

  private static final CopyOnWriteArrayList<WeakReference<Stage>> SUPPRESSED_FOR_DIALOG =
      new CopyOnWriteArrayList<>();

  private static void trackOpen(Stage stage) {
    if (stage == null) {
      return;
    }
    pruneOpenStages();
    for (WeakReference<Stage> ref : OPEN_STAGES) {
      if (ref.get() == stage) {
        return;
      }
    }
    OPEN_STAGES.add(new WeakReference<>(stage));
  }

  private static void untrack(Stage stage) {
    if (stage == null) {
      return;
    }
    // CopyOnWriteArrayList iterator does not support remove — rebuild.
    List<WeakReference<Stage>> keep = new ArrayList<>();
    for (WeakReference<Stage> ref : OPEN_STAGES) {
      Stage s = ref.get();
      if (s != null && s != stage) {
        keep.add(ref);
      }
    }
    OPEN_STAGES.clear();
    OPEN_STAGES.addAll(keep);
  }

  private static void pruneOpenStages() {
    List<WeakReference<Stage>> keep = new ArrayList<>();
    for (WeakReference<Stage> ref : OPEN_STAGES) {
      Stage s = ref.get();
      if (s != null) {
        keep.add(ref);
      }
    }
    if (keep.size() != OPEN_STAGES.size()) {
      OPEN_STAGES.clear();
      OPEN_STAGES.addAll(keep);
    }
  }

  private void buildUi() {
    Label rowsLabel = new Label("Plot features");
    rowsLabel.getStyleClass().add("section-header");

    Button zoomOut = new Button("−");
    Button zoomFit = new Button("Fit");
    Button zoomIn = new Button("+");
    zoomOut.setFocusTraversable(false);
    zoomFit.setFocusTraversable(false);
    zoomIn.setFocusTraversable(false);
    Tooltip.install(zoomOut, new Tooltip("Zoom out (Ctrl/Cmd+wheel)"));
    Tooltip.install(zoomFit, new Tooltip("Fit all samples and features to the window"));
    Tooltip.install(zoomIn, new Tooltip("Zoom in (Ctrl/Cmd+wheel)"));
    zoomOut.setOnAction(e -> applyZoom(zoom / ZOOM_STEP, null, null));
    zoomFit.setOnAction(e -> fitToViewport());
    zoomIn.setOnAction(e -> applyZoom(zoom * ZOOM_STEP, null, null));

    HBox toolbar = new HBox(8, rowsLabel, maxRowsSpinner, zoomOut, zoomFit, zoomIn);
    toolbar.setAlignment(Pos.CENTER_LEFT);

    statusLabel.getStyleClass().add("value-label");
    statusLabel.setWrapText(true);

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox top = new HBox(12, toolbar, spacer, statusLabel);
    top.setAlignment(Pos.CENTER_LEFT);
    top.setPadding(new Insets(0, 0, 8, 0));

    scroll.setContent(canvas);
    scroll.setFitToWidth(false);
    scroll.setFitToHeight(false);
    scroll.setPannable(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    scroll.setMinSize(0, 0);

    BorderPane root = new BorderPane();
    root.setPadding(new Insets(12));
    root.getStyleClass().addAll("root-pane", "dialog-surface", "filter-panel");
    root.setTop(top);
    root.setCenter(scroll);

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

  private void onScrollZoom(ScrollEvent e) {
    if (!(e.isControlDown() || e.isMetaDown())) {
      return;
    }
    e.consume();
    double factor = e.getDeltaY() > 0 ? ZOOM_STEP : 1.0 / ZOOM_STEP;
    applyZoom(zoom * factor, e.getX(), e.getY());
  }

  /** Scale so the full heatmap fits in the current ScrollPane viewport (at most 100%). */
  private void fitToViewport() {
    stickyStatus = null;
    zoom = computeFitZoom();
    redraw();
    scroll.setHvalue(0);
    scroll.setVvalue(0);
  }

  private double computeFitZoom() {
    commitSpinner(maxRowsSpinner);
    Integer maxRows = maxRowsSpinner.getValue();
    int featureCount = result.selectedFeatures().size();
    int rows = Math.max(1, Math.min(maxRows != null ? maxRows : DEFAULT_MAX_ROWS, featureCount));
    int nSamples = Math.max(1, result.tracks().size());

    boolean showGroups = hasAnySampleGroup(result.tracks());
    int annotRows = 1 + (showGroups ? 1 : 0);
    double annotBlock = annotRows * ANNOT_H + (annotRows - 1) * ANNOT_GAP + 6;
    double topChrome = TOP_MARGIN + legendBlockHeight() + annotBlock;

    Bounds view = scroll.getViewportBounds();
    double viewW = view.getWidth();
    double viewH = view.getHeight();
    if (viewW <= 1 || viewH <= 1) {
      viewW = Math.max(100, stage.getWidth() - 48);
      viewH = Math.max(100, stage.getHeight() - 100);
    }

    double availW = Math.max(40, viewW - LEFT_MARGIN - RIGHT_MARGIN);
    double availH = Math.max(40, viewH - topChrome - BOTTOM_MARGIN);
    double zoomX = availW / (nSamples * BASE_COL_W);
    double zoomY = availH / (rows * BASE_ROW_H);
    // Cap at 1.0 so Fit never enlarges past natural cell size; user zooms in from there.
    return Math.max(ZOOM_MIN, Math.min(1.0, Math.min(zoomX, zoomY)));
  }

  private void applyZoom(double newZoom, Double pivotCanvasX, Double pivotCanvasY) {
    double oldZoom = zoom;
    zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, newZoom));
    stickyStatus = null;
    if (Math.abs(zoom - oldZoom) < 1e-9) {
      updateStatus();
      return;
    }

    Bounds view = scroll.getViewportBounds();
    double viewW = Math.max(1, view.getWidth());
    double viewH = Math.max(1, view.getHeight());
    double oldW = Math.max(1, canvas.getWidth());
    double oldH = Math.max(1, canvas.getHeight());
    double left = scroll.getHvalue() * Math.max(0, oldW - viewW);
    double top = scroll.getVvalue() * Math.max(0, oldH - viewH);

    double pivotX = pivotCanvasX != null ? pivotCanvasX : left + viewW / 2;
    double pivotY = pivotCanvasY != null ? pivotCanvasY : top + viewH / 2;
    double viewPivotX = pivotX - left;
    double viewPivotY = pivotY - top;

    double scale = zoom / oldZoom;
    double newPivotX = heatX + (pivotX - heatX) * scale;
    double newPivotY = heatY + (pivotY - heatY) * scale;

    redraw();

    double newW = Math.max(1, canvas.getWidth());
    double newH = Math.max(1, canvas.getHeight());
    double newLeft = newPivotX - viewPivotX;
    double newTop = newPivotY - viewPivotY;
    double hRange = Math.max(1e-6, newW - viewW);
    double vRange = Math.max(1e-6, newH - viewH);
    scroll.setHvalue(clamp01(newLeft / hRange));
    scroll.setVvalue(clamp01(newTop / vRange));
  }

  private void redraw() {
    commitSpinner(maxRowsSpinner);
    Integer maxRows = maxRowsSpinner.getValue();
    int featureCount = result.selectedFeatures().size();
    plotRows = Math.max(1, Math.min(maxRows != null ? maxRows : DEFAULT_MAX_ROWS, featureCount));

    int nSamples = result.tracks().size();
    boolean showGroups = hasAnySampleGroup(result.tracks());
    int annotRows = 1 + (showGroups ? 1 : 0);
    double annotBlock = annotRows * ANNOT_H + (annotRows - 1) * ANNOT_GAP + 6;

    colW = BASE_COL_W * zoom;
    rowH = BASE_ROW_H * zoom;
    heatX = LEFT_MARGIN;
    heatY = TOP_MARGIN + legendBlockHeight() + annotBlock;
    heatW = nSamples * colW;
    heatH = plotRows * rowH;

    double canvasW = LEFT_MARGIN + heatW + RIGHT_MARGIN;
    double canvasH = heatY + heatH + BOTTOM_MARGIN;
    if (Math.abs(canvas.getWidth() - canvasW) > 0.5) {
      canvas.setWidth(canvasW);
    }
    if (Math.abs(canvas.getHeight() - canvasH) > 0.5) {
      canvas.setHeight(canvasH);
    }

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

    drawLegend(g, heatX, TOP_MARGIN, Math.min(160, Math.max(80, heatW * 0.25)), LEGEND_H, ink, muted);
    drawAnnotationBars(g, showGroups, muted);
    drawHeatmapBody(g);
    drawLabels(g, ink, muted);
    updateStatus();
  }

  private void updateStatus() {
    if (stickyStatus != null && !stickyStatus.isBlank()) {
      statusLabel.setText(stickyStatus);
      return;
    }
    int featureCount = result.selectedFeatures().size();
    statusLabel.setText(String.format(Locale.ROOT,
        "%d samples · showing %d / %d features · %s=%d · %s · zoom %.0f%%",
        result.tracks().size(),
        plotRows,
        featureCount,
        clusterBarLabel.toLowerCase(Locale.ROOT),
        result.k(),
        result.zScored() ? "z-score" : "raw values",
        zoom * 100));
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
    double labelY = y + height + 11;
    if (result.zScored()) {
      g.fillText(String.format(Locale.ROOT, "−%.1f", Z_CLIP), x, labelY);
      g.fillText("0", x + width / 2 - 3, labelY);
      g.fillText(String.format(Locale.ROOT, "+%.1f", Z_CLIP), x + width - 18, labelY);
    } else {
      g.fillText("low", x, labelY);
      g.fillText("high", x + width - 22, labelY);
    }
  }

  /** Color bar + value labels + gap before annotation strips. */
  private static double legendBlockHeight() {
    return LEGEND_H + LEGEND_LABEL_H + LEGEND_TO_ANNOT_GAP;
  }

  private void drawAnnotationBars(GraphicsContext g, boolean showGroups, Color muted) {
    int[] leaf = result.leafOrder();
    List<SampleTrack> tracks = result.tracks();
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();

    double y = TOP_MARGIN + legendBlockHeight();
    groupsAnnotY = -1;
    if (showGroups) {
      groupsAnnotY = y;
      g.setFill(muted);
      g.setFont(javafx.scene.text.Font.font(10));
      g.fillText("Groups", 8, y + ANNOT_H - 2);
      for (int col = 0; col < leaf.length; col++) {
        SampleTrack track = tracks.get(leaf[col]);
        g.setFill(groupColor(track, registry));
        g.fillRect(heatX + col * colW, y, Math.max(1, colW), ANNOT_H);
      }
      g.setStroke(muted.deriveColor(0, 1, 1, 0.35));
      g.strokeRect(heatX, y, heatW, ANNOT_H);
      y += ANNOT_H + ANNOT_GAP;
    }

    patternAnnotY = y;
    g.setFill(muted);
    g.setFont(javafx.scene.text.Font.font(10));
    g.fillText(clusterBarLabel, 8, y + ANNOT_H - 2);
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
    int labelStep = Math.max(1, (int) Math.ceil(10.0 / Math.max(rowH, 0.1)));
    for (int r = 0; r < plotRows; r += labelStep) {
      String id = features.get(r);
      String label = id.length() > 12 ? id.substring(0, 11) + "…" : id;
      double y = heatY + r * rowH + Math.min(rowH, 11);
      g.fillText(label, 4, y);
    }

    List<SampleTrack> tracks = result.tracks();
    int[] leaf = result.leafOrder();
    int sampleLabelStep = Math.max(1, (int) Math.ceil(12.0 / Math.max(colW, 0.1)));
    g.setFill(muted);
    for (int col = 0; col < leaf.length; col += sampleLabelStep) {
      String name = tracks.get(leaf[col]).getName();
      if (name == null) {
        name = "?";
      }
      String label = name.length() > 12 ? name.substring(0, 11) + "…" : name;
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
    Hit hit = hitTest(e.getX(), e.getY());
    if (hit == null) {
      hoverTip.setText("");
      canvas.setCursor(Cursor.DEFAULT);
      return;
    }
    if (hit.kind == HitKind.GENE || hit.kind == HitKind.CELL) {
      canvas.setCursor(Cursor.HAND);
    } else {
      canvas.setCursor(Cursor.DEFAULT);
    }

    if (hit.kind == HitKind.CELL || hit.kind == HitKind.GENE) {
      int sampleIdx = hit.col >= 0 ? result.leafOrder()[hit.col] : -1;
      String feature = result.selectedFeatures().get(hit.row);
      if (hit.kind == HitKind.GENE || sampleIdx < 0) {
        hoverTip.setText(feature);
        return;
      }
      SampleTrack track = result.tracks().get(sampleIdx);
      double value = result.sampleFeature()[sampleIdx][hit.row];
      int cluster = result.clusterOfTrack()[sampleIdx];
      SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
      String groups = groupNames(track, registry);
      hoverTip.setText(String.format(Locale.ROOT,
          "%s\n%s\n%s%s\nvalue=%s",
          track.getName(),
          feature,
          result.clusterLabel(cluster),
          groups.isEmpty() ? "" : "\ngroups: " + groups,
          formatValue(value)));
      return;
    }

    if (hit.col >= 0) {
      int sampleIdx = result.leafOrder()[hit.col];
      SampleTrack track = result.tracks().get(sampleIdx);
      int cluster = result.clusterOfTrack()[sampleIdx];
      SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
      String groups = groupNames(track, registry);
      hoverTip.setText(String.format(Locale.ROOT,
          "%s\n%s%s",
          track.getName(),
          result.clusterLabel(cluster),
          groups.isEmpty() ? "" : "\ngroups: " + groups));
    }
  }

  private void onMouseClicked(MouseEvent e) {
    if (e.getClickCount() < 1 || colW <= 0 || rowH <= 0) {
      return;
    }
    Hit hit = hitTest(e.getX(), e.getY());
    if (hit == null) {
      return;
    }
    if (hit.kind == HitKind.GENE || hit.kind == HitKind.CELL) {
      navigateToFeature(result.selectedFeatures().get(hit.row));
      return;
    }
    if (hit.col >= 0) {
      int sampleIdx = result.leafOrder()[hit.col];
      SampleTrack track = result.tracks().get(sampleIdx);
      int cluster = result.clusterOfTrack()[sampleIdx];
      SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
      String groups = groupNames(track, registry);
      stickyStatus = String.format(Locale.ROOT,
          "%s · %s%s",
          track.getName(),
          result.clusterLabel(cluster),
          groups.isEmpty() ? "" : " · groups: " + groups);
      updateStatus();
    }
  }

  private Hit hitTest(double x, double y) {
    int nSamples = result.tracks().size();
    if (x >= heatX && x < heatX + heatW) {
      int col = (int) ((x - heatX) / colW);
      if (col < 0 || col >= nSamples) {
        return null;
      }
      if (y >= heatY && y < heatY + heatH) {
        int row = (int) ((y - heatY) / rowH);
        if (row < 0 || row >= plotRows) {
          return null;
        }
        return new Hit(HitKind.CELL, col, row);
      }
      if (groupsAnnotY >= 0 && y >= groupsAnnotY && y < groupsAnnotY + ANNOT_H) {
        return new Hit(HitKind.SAMPLE, col, -1);
      }
      if (patternAnnotY >= 0 && y >= patternAnnotY && y < patternAnnotY + ANNOT_H) {
        return new Hit(HitKind.SAMPLE, col, -1);
      }
      if (y >= heatY + heatH && y < heatY + heatH + BOTTOM_MARGIN) {
        return new Hit(HitKind.SAMPLE, col, -1);
      }
      return null;
    }
    if (x >= 0 && x < heatX && y >= heatY && y < heatY + heatH) {
      int row = (int) ((y - heatY) / rowH);
      if (row < 0 || row >= plotRows) {
        return null;
      }
      return new Hit(HitKind.GENE, -1, row);
    }
    return null;
  }

  private void navigateToFeature(String featureId) {
    if (featureId == null || featureId.isBlank()) {
      return;
    }
    if (!featureId.contains(":")) {
      stickyStatus = "Navigating to " + featureId;
      updateStatus();
      NavigationCommands.navigateToGene(featureId, true);
      return;
    }
    GroupStatResult.Row row = markerByUnit.get(featureId);
    if (row == null) {
      for (GroupStatResult.Row r : markerByUnit.values()) {
        if (featureId.equals(r.geneName()) || featureId.equals(r.unitId())) {
          row = r;
          break;
        }
      }
    }
    if (row != null && row.chromosome() != null && row.end1Based() > 0) {
      stickyStatus = "Navigating to " + featureId;
      updateStatus();
      NavigationCommands.navigateToPosition(
          row.chromosome(),
          (int) row.start1Based(),
          (int) Math.max(row.start1Based(), row.end1Based()));
      return;
    }
    stickyStatus = "No locus for " + featureId;
    updateStatus();
  }

  private static double clamp01(double v) {
    if (v < 0) {
      return 0;
    }
    if (v > 1) {
      return 1;
    }
    return v;
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

  private enum HitKind {
    CELL, GENE, SAMPLE
  }

  private record Hit(HitKind kind, int col, int row) {}
}
