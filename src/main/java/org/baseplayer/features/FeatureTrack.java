package org.baseplayer.features;

import java.util.concurrent.CompletableFuture;

import org.baseplayer.components.InfoPopup;
import org.baseplayer.components.PopupContent;
import org.baseplayer.io.APIs.UcscApiClient.ConservationData;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.stage.Window;

/**
 * Generic feature track that can display data from any source (UCSC, gnomAD, etc.).
 * Uses a pluggable {@link DataFetcher} to retrieve data and renders it as a histogram.
 * Click handling shows an {@link InfoPopup} with position and score details.
 * 
 * <p>An optional {@link PopupContentBuilder} can be set to customise the popup
 * shown on click (e.g. to display gnomAD allele frequencies, consequence, etc.).
 * When no custom builder is provided a default popup with the score value is shown.
 *
 * <p>The {@code coordinateBase} field (0 or 1) controls the offset applied when
 * converting between the data coordinate system and the 1-based display coordinates.
 * UCSC tracks use 0-based half-open coordinates; gnomAD/VCF uses 1-based.
 */
public class FeatureTrack extends AbstractUcscTrack {

  /**
   * Functional interface for fetching track data from any source.
   * Implementations convert source-specific data into the generic {@link ConservationData} format.
   */
  @FunctionalInterface
  public interface DataFetcher {
    CompletableFuture<ConservationData> fetch(String chromosome, long start, long end, int bins);
  }

  /**
   * Functional interface for building custom popup content when a track is clicked.
   * This allows data-source-specific information (e.g. gnomAD allele frequencies,
   * gene links, consequence types) to be shown alongside the generic score.
   *
   * <p>If no builder is set, a default popup with track name, position and score is shown.
   */
  @FunctionalInterface
  public interface PopupContentBuilder {
    /**
     * Build popup content for a click at the given genomic position.
     *
     * @param chromosome the chromosome
     * @param position   1-based genomic position of the click
     * @param score      the binned score value at this position
     * @param binIndex   the bin index in the current data
     * @return popup content, or {@code null} to fall back to the default popup
     */
    PopupContent build(String chromosome, long position, double score, int binIndex);
  }

  private final DataFetcher dataFetcher;
  private final InfoPopup popup = new InfoPopup();
  private PopupContentBuilder popupContentBuilder;

  /**
   * Coordinate base of the data source: 0 for 0-based half-open (UCSC), 1 for 1-based (VCF/gnomAD).
   * Used when displaying positions in the popup so users see correct 1-based coordinates.
   */
  private int coordinateBase = 0;

  public FeatureTrack(String displayName, String source, DataFetcher fetcher) {
    super(displayName, source);
    this.dataFetcher = fetcher;
    this.preferredHeight = 50;
  }

  /**
   * Set a custom popup builder for this track.
   * When set, clicks will show the content returned by this builder instead of
   * the default score popup.
   */
  public void setPopupContentBuilder(PopupContentBuilder builder) {
    this.popupContentBuilder = builder;
  }

  /**
   * Set the coordinate base for this track's data source.
   * @param base 0 for 0-based half-open (UCSC), 1 for 1-based (VCF/gnomAD)
   */
  public void setCoordinateBase(int base) {
    this.coordinateBase = base;
  }

  public int getCoordinateBase() {
    return coordinateBase;
  }

  @Override
  protected CompletableFuture<ConservationData> fetchDataFromApi(
      String chromosome, long start, long end, int bins) {
    return dataFetcher.fetch(chromosome, start, end, bins);
  }

  @Override
  protected void drawTrackData(GraphicsContext gc, double x, double y,
                                double width, double height, double viewStart, double viewEnd) {
    double[] scores = currentData.scores();
    if (scores == null || scores.length == 0) return;

    double dataStart = (double) currentData.start();
    double dataEnd = (double) currentData.end();
    int bins = scores.length;
    double dataBinSize = (dataEnd - dataStart) / bins;

    double dataMin = currentData.minScore();
    double dataMax = currentData.maxScore();
    if (minValue != null) dataMin = minValue;
    if (maxValue != null) dataMax = maxValue;
    double range = dataMax - dataMin;
    if (range <= 0) range = 1;

    double viewLength = viewEnd - viewStart;
    boolean isBaseLevelData = currentData.isBaseLevelData();

    for (int i = 0; i < bins; i++) {
      double value = scores[i];
      if (value == 0) continue;

      double binGenomicStart = dataStart + (i * dataBinSize);
      double binGenomicEnd = isBaseLevelData ? (binGenomicStart + 1.0) : (dataStart + ((i + 1) * dataBinSize));

      if (binGenomicEnd < viewStart || binGenomicStart > viewEnd) continue;

      double screenX1 = x + ((binGenomicStart - viewStart) / viewLength) * width;
      double screenX2 = x + ((binGenomicEnd - viewStart) / viewLength) * width;
      double binWidth = Math.max(1, screenX2 - screenX1);

      double normalized = (value - dataMin) / range;
      double barHeight = normalized * height;

      gc.setFill(getColorForValue(Math.max(0.0, Math.min(1.0, normalized))));
      gc.fillRect(screenX1, y + height - barHeight, binWidth, barHeight);
    }

    gc.setStroke(Color.web("#444444"));
    gc.strokeLine(x, y + height, x + width, y + height);
  }

  private Color getColorForValue(double normalized) {
    if (normalized < 0.5) {
      double t = normalized * 2;
      return Color.rgb(
          (int) (50 + t * 200),
          (int) (50 + t * 200),
          (int) (200 - t * 200));
    } else {
      double t = (normalized - 0.5) * 2;
      return Color.rgb(250, (int) (250 - t * 200), (int) (50 - t * 50));
    }
  }

  // ── Click handling ────────────────────────────────────────────────────────

  @Override
  public boolean supportsClick() {
    return currentData != null && currentData.hasData();
  }

  @Override
  public boolean handleClick(double clickX, double clickY, double trackWidth, double trackHeight,
                             String chromosome, double viewStart, double viewEnd,
                             Window owner, double screenX, double screenY) {

    if (currentData == null || !currentData.hasData()) return false;

    double viewLength = viewEnd - viewStart;
    double genomicPos = viewStart + (clickX / trackWidth) * viewLength;
    long clickedPosition = Math.round(genomicPos);

    double[] scores = currentData.scores();
    long dataStart = currentData.start();
    long dataEnd = currentData.end();
    int bins = scores.length;

    double dataBinSize = (double) (dataEnd - dataStart) / bins;
    int binIndex = (int) ((clickedPosition - dataStart) / dataBinSize);
    if (binIndex < 0 || binIndex >= bins) return false;

    double scoreValue = scores[binIndex];

    // Convert to 1-based display position if data is 0-based
    final long displayPosition = coordinateBase == 0 ? clickedPosition + 1 : clickedPosition;

    Platform.runLater(() -> {
      PopupContent content = null;

      // Try custom popup builder first
      if (popupContentBuilder != null) {
        content = popupContentBuilder.build(chromosome, displayPosition, scoreValue, binIndex);
      }

      // Fall back to default popup
      if (content == null) {
        content = buildDefaultPopup(chromosome, displayPosition, scoreValue);
      }

      popup.show(content, owner, screenX, screenY);
    });

    return true;
  }

  private PopupContent buildDefaultPopup(String chromosome, long position, double scoreValue) {
    double displayMin = minValue != null ? minValue : currentData.minScore();
    double displayMax = maxValue != null ? maxValue : currentData.maxScore();

    PopupContent c = new PopupContent();
    c.title(getName(), Color.web("#88ccff"));
    c.row("Type", getType(), Color.web("#888888"));
    c.separator();
    c.section("Genomic Position");
    c.row("Chromosome", chromosome);
    c.row("Position", String.format("%,d", position));
    c.separator();
    c.section("Value");
    c.row("Score", formatScore(scoreValue), getScoreColor(scoreValue, displayMin, displayMax));
    c.row("", String.format("Scale: %.2f to %.2f", displayMin, displayMax), Color.web("#666666"));
    return c;
  }

  @Override
  public void hidePopup() {
    popup.hide();
  }

  @Override
  public void dispose() {
    super.dispose();
    popup.hide();
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static String formatScore(double score) {
    if (Math.abs(score) < 0.01 && score != 0) return String.format("%.2e", score);
    if (Math.abs(score) >= 1000) return String.format("%,.0f", score);
    return String.format("%.3f", score);
  }

  private static Color getScoreColor(double score, double min, double max) {
    if (max > min) {
      double normalized = Math.max(0.0, Math.min(1.0, (score - min) / (max - min)));
      if (normalized < 0.5) {
        double t = normalized * 2;
        return Color.rgb((int) (80 + t * 170), (int) (140 + t * 110), (int) (200 - t * 150));
      } else {
        double t = (normalized - 0.5) * 2;
        return Color.rgb(250, (int) (250 - t * 200), 50);
      }
    }
    if (score > 0) return Color.web("#88ccff");
    if (score < 0) return Color.web("#ff8888");
    return Color.web("#888888");
  }
}
