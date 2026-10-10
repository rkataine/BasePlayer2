package org.baseplayer.draw;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.features.BedTrack;
import org.baseplayer.features.Track;
import org.baseplayer.io.readers.BedFileReader.BedFeature;
import org.baseplayer.services.FeatureTrackViewportRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.ui.theme.AppTheme;
import org.baseplayer.utils.AppFonts;

import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

/**
 * Feature-column aggregate band: feature-name density and clickable name legends.
 * Region set-ops (union / intersect / subtract) live in feature master settings.
 */
public class FeatureAggregateCanvas extends AggregateBandCanvas {

  private static final double RESIZE_EDGE_PX = 6;
  private static final int DENSITY_BINS = 400;

  private final FeatureTrackViewportRegistry registry =
      ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
  private final CanvasColorLegend colorLegend = new CanvasColorLegend();

  private boolean resizingBand;
  private double resizeStartScreenY;
  private double resizeStartMasterHeight;

  public FeatureAggregateCanvas(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack) {
    super(
        reactiveCanvas,
        parent,
        drawStack,
        ServiceRegistry.getInstance()
            .getFeatureTrackViewportRegistry()
            .aggregateBandHeightProperty());

    Canvas reactive = getReactiveCanvas();
    reactive.addEventFilter(MouseEvent.MOUSE_MOVED, this::onBandMouseMoved);
    reactive.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onBandMousePressed);
    reactive.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onBandMouseDragged);
    reactive.addEventFilter(MouseEvent.MOUSE_RELEASED, this::onBandMouseReleased);
    reactive.addEventHandler(MouseEvent.MOUSE_CLICKED, this::onBandClicked);

    registry.selectionRevisionProperty().addListener((obs, o, n) -> draw());
    registry.aggregateRevisionProperty().addListener((obs, o, n) -> draw());
  }

  private boolean inResizeEdge(double y) {
    return y >= getHeight() - RESIZE_EDGE_PX;
  }

  private void onBandMouseMoved(MouseEvent event) {
    if (resizingBand || inResizeEdge(event.getY())) {
      applyCursor(Cursor.V_RESIZE);
      event.consume();
      return;
    }
    boolean overInteractive = colorLegend.isOver(event.getX(), event.getY());
    applyCursor(overInteractive ? Cursor.HAND : Cursor.DEFAULT);
  }

  private void onBandMousePressed(MouseEvent event) {
    if (event.getButton() != MouseButton.PRIMARY || !inResizeEdge(event.getY())) {
      return;
    }
    resizingBand = true;
    resizeStartScreenY = event.getScreenY();
    resizeStartMasterHeight = registry.getMasterTrackHeight();
    applyCursor(Cursor.V_RESIZE);
    event.consume();
  }

  private void onBandMouseDragged(MouseEvent event) {
    if (!resizingBand) {
      return;
    }
    double delta = event.getScreenY() - resizeStartScreenY;
    registry.setMasterTrackHeight(
        Math.max(20, Math.min(200, resizeStartMasterHeight + delta)));
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
    event.consume();
  }

  private void onBandMouseReleased(MouseEvent event) {
    if (!resizingBand) {
      return;
    }
    resizingBand = false;
    applyCursor(Cursor.DEFAULT);
    event.consume();
  }

  private void applyCursor(Cursor cursor) {
    getReactiveCanvas().setCursor(cursor);
    setCursor(cursor);
  }

  private void onBandClicked(MouseEvent event) {
    if (mouseDragged || resizingBand || event.getButton() != MouseButton.PRIMARY) {
      return;
    }
    if (handleLegendClick(event.getX(), event.getY())) {
      event.consume();
    }
  }

  private boolean handleLegendClick(double x, double y) {
    Object hitId = colorLegend.hitTest(x, y);
    if (hitId == null) {
      return false;
    }
    List<String> presentNames = collectPresentFeatureNames();
    if (CanvasColorLegend.isSelectAll(hitId)) {
      boolean allVisible = true;
      for (String name : presentNames) {
        if (!registry.isLegendFeatureNameVisible(name)) {
          allVisible = false;
          break;
        }
      }
      registry.setAllLegendFeatureNamesVisible(presentNames, !allVisible);
      draw();
      return true;
    }
    if (hitId instanceof String name) {
      registry.toggleLegendFeatureName(name);
      draw();
      return true;
    }
    return false;
  }

  @Override
  protected void drawBand() {
    double h = getHeight();
    double w = getWidth();
    if (h <= 0 || w <= 0) {
      return;
    }
    GraphicsContext gc = getGraphicsContext2D();
    gc.setFill(AppTheme.canvas().trackBackground());
    gc.fillRect(0, 0, w, h);

    colorLegend.clear();
    double contentTop = 4;

    String chrom = drawStack.getChromosome();
    double viewStart = drawStack.getViewStart();
    double viewEnd = drawStack.getViewEnd();
    Map<String, Color> nameColors = new LinkedHashMap<>();
    paintFeatureDensity(gc, chrom, viewStart, viewEnd, w, contentTop, h, nameColors);

    double legendTop = Math.max(contentTop, h - 22);
    List<CanvasColorLegend.Item> legendItems = new ArrayList<>();
    for (Map.Entry<String, Color> entry : nameColors.entrySet()) {
      String name = entry.getKey();
      String label = name.isEmpty() ? "(unnamed)" : name;
      legendItems.add(new CanvasColorLegend.Item(
          name,
          label,
          entry.getValue(),
          registry.isLegendFeatureNameVisible(name)));
    }
    if (!legendItems.isEmpty()) {
      colorLegend.draw(gc, legendItems, 8, legendTop, w - 8, h - 4);
    }

    gc.setStroke(AppTheme.chrome().border());
    gc.setLineWidth(1);
    gc.strokeLine(0, h - 0.5, w, h - 0.5);
  }

  /**
   * Density bars (feature-overlap counts per genomic bin), stacked by feature name.
   * Unlike track-body painting this is a cohort summary, not a copy of intervals.
   */
  private void paintFeatureDensity(
      GraphicsContext gc,
      String chrom,
      double viewStart,
      double viewEnd,
      double width,
      double contentTop,
      double height,
      Map<String, Color> nameColorsOut) {
    if (chrom == null || viewEnd <= viewStart || width < 2) {
      return;
    }
    double viewLength = viewEnd - viewStart;
    double densTop = contentTop + 2;
    double densH = Math.max(4, height - contentTop - 26);
    int bins = Math.max(32, Math.min(DENSITY_BINS, (int) Math.ceil(width)));

    Map<String, int[]> countsByName = new LinkedHashMap<>();
    int[] totalByBin = new int[bins];
    int maxCount = 1;

    List<Track> eligible = registry.getAggregateEligibleTracks();
    for (Track track : eligible) {
      if (!(track instanceof BedTrack bed)) {
        continue;
      }
      if (bed.isIndexed()
          && bed.isViewTooLargeForIndexed((long) viewStart, (long) viewEnd)) {
        continue;
      }
      bed.prepareRegion(chrom, (long) viewStart, (long) viewEnd);
      List<BedFeature> features =
          bed.getFeaturesOverlapping(chrom, (long) viewStart, (long) viewEnd);
      if (features == null || features.isEmpty()) {
        continue;
      }
      int from = BedTrack.findFirstOverlappingIndex(features, viewStart, viewEnd);
      for (int i = from; i < features.size(); i++) {
        BedFeature feature = features.get(i);
        if (feature.start() + 1 > viewEnd) {
          break;
        }
        if (feature.end() < viewStart) {
          continue;
        }
        String nameKey = feature.name() == null ? "" : feature.name();
        Color color = BedTrack.colorForFeature(feature);
        nameColorsOut.putIfAbsent(nameKey, color);
        if (!registry.isLegendFeatureNameVisible(nameKey)) {
          continue;
        }
        long featStart = Math.max((long) viewStart, feature.start() + 1);
        long featEnd = Math.min((long) viewEnd, feature.end());
        if (featEnd < featStart) {
          continue;
        }
        int b0 = (int) Math.max(0,
            Math.min(bins - 1, (featStart - viewStart) * bins / viewLength));
        int b1 = (int) Math.max(0,
            Math.min(bins - 1, (featEnd - viewStart) * bins / viewLength));
        int[] counts = countsByName.computeIfAbsent(nameKey, k -> new int[bins]);
        for (int b = b0; b <= b1; b++) {
          counts[b]++;
          totalByBin[b]++;
          if (totalByBin[b] > maxCount) {
            maxCount = totalByBin[b];
          }
        }
      }
    }

    if (countsByName.isEmpty()) {
      return;
    }

    double binW = width / bins;
    for (int b = 0; b < bins; b++) {
      if (totalByBin[b] <= 0) {
        continue;
      }
      double x = b * binW;
      double stack = 0;
      for (Map.Entry<String, int[]> entry : countsByName.entrySet()) {
        int c = entry.getValue()[b];
        if (c <= 0) {
          continue;
        }
        double segH = densH * (c / (double) maxCount);
        Color color = nameColorsOut.getOrDefault(entry.getKey(), Color.STEELBLUE);
        gc.setFill(Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.9));
        gc.fillRect(x, densTop + densH - stack - segH, Math.max(1, binW + 0.5), segH);
        stack += segH;
      }
    }

    // Scale label (max count)
    gc.setFill(AppTheme.chrome().muted());
    gc.setFont(AppFonts.getUIFont(9));
    gc.setTextAlign(TextAlignment.RIGHT);
    gc.fillText(String.valueOf(maxCount), width - 4, densTop + 10);
    gc.setTextAlign(TextAlignment.LEFT);
  }

  private List<String> collectPresentFeatureNames() {
    Set<String> names = new LinkedHashSet<>();
    String chrom = drawStack.getChromosome();
    double viewStart = drawStack.getViewStart();
    double viewEnd = drawStack.getViewEnd();
    if (chrom == null) {
      return List.of();
    }
    for (Track track : registry.getAggregateEligibleTracks()) {
      if (!(track instanceof BedTrack bed)) {
        continue;
      }
      List<BedFeature> features =
          bed.getFeaturesOverlapping(chrom, (long) viewStart, (long) viewEnd);
      int from = BedTrack.findFirstOverlappingIndex(features, viewStart, viewEnd);
      for (int i = from; i < features.size(); i++) {
        BedFeature feature = features.get(i);
        if (feature.start() + 1 > viewEnd) {
          break;
        }
        if (feature.end() < viewStart) {
          continue;
        }
        names.add(feature.name() == null ? "" : feature.name());
      }
    }
    return new ArrayList<>(names);
  }
}
