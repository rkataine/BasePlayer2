package org.baseplayer.features;

import org.baseplayer.components.InfoPopup;
import org.baseplayer.components.PopupContent;

import javafx.scene.paint.Color;

/**
 * Builds {@link PopupContent} for a UCSC track data point.
 * Used together with a shared {@link InfoPopup} instance.
 */
public final class UcscTrackDataPopup {

  private UcscTrackDataPopup() {} // utility class

  /**
   * Build popup content for a UCSC track score at a genomic position.
   */
  public static PopupContent buildContent(String trackName, String trackType,
                                          String chromosome, long position,
                                          double score, Double minValue, Double maxValue) {
    PopupContent c = new PopupContent();
    if (trackName == null) return c;

    c.title(trackName, Color.web("#88ccff"));

    if (trackType != null) {
      c.row("Track Type", trackType, Color.web("#888888"));
    }

    c.separator();
    c.section("Genomic Position");
    c.row("Chromosome", chromosome);
    c.row("Position", String.format("%,d", position));

    c.separator();
    c.section("Value");
    Color scoreColor = getScoreColor(score, minValue, maxValue);
    c.row("Score", formatScore(score), scoreColor);

    if (minValue != null && maxValue != null) {
      c.row("", String.format("Scale: %.2f to %.2f", minValue, maxValue), Color.web("#666666"));
    } else if (minValue != null || maxValue != null) {
      double min = minValue != null ? minValue : 0.0;
      double max = maxValue != null ? maxValue : 0.0;
      c.row("", String.format("Scale: %.2f to %.2f", min, max), Color.web("#666666"));
    }

    return c;
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private static String formatScore(double score) {
    if (Math.abs(score) < 0.01 && score != 0) return String.format("%.2e", score);
    if (Math.abs(score) >= 1000) return String.format("%,.0f", score);
    return String.format("%.3f", score);
  }

  private static Color getScoreColor(double score, Double min, Double max) {
    if (min != null && max != null && max > min) {
      double normalized = (score - min) / (max - min);
      normalized = Math.max(0.0, Math.min(1.0, normalized));
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
