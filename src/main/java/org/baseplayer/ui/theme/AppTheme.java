package org.baseplayer.ui.theme;

import javafx.scene.paint.Color;

/**
 * Semantic theme colors for BasePlayer.
 *
 * <p>Two layers under one mode:
 * <ul>
 *   <li>{@link ChromePalette} — panels, menus, ComboBoxes, dialogs (CSS + controls)</li>
 *   <li>{@link CanvasPalette} — track/sidebar canvas surfaces and overlay ink</li>
 * </ul>
 * Genomic <em>data</em> colors (bases, variants, reads) stay theme-independent
 * in {@code DrawColors} / {@code BaseColors} so they remain visible on both
 * dark and light canvas backgrounds.
 *
 * <p>CSS looked-up {@code -bp-*} colors in {@code theme-dark.css} /
 * {@code theme-light.css} must stay in sync with {@link ChromePalette}.
 */
public final class AppTheme {

  public enum Mode {
    DARK,
    LIGHT
  }

  /** UI chrome: panels, controls, text. */
  public record ChromePalette(
      Color surface,
      Color panel,
      Color control,
      Color text,
      Color textMuted,
      Color border,
      Color accent) {

    public String surfaceHex() {
      return hex(surface);
    }

    public String panelHex() {
      return hex(panel);
    }

    public String controlHex() {
      return hex(control);
    }

    public String textHex() {
      return hex(text);
    }

    public String textMutedHex() {
      return hex(textMuted);
    }

    public String borderHex() {
      return hex(border);
    }

    public String accentHex() {
      return hex(accent);
    }
  }

  /**
   * Canvas surfaces and ink. Painters should migrate here later; data glyphs
   * remain in DrawColors.
   */
  public record CanvasPalette(
      Color trackBackground,
      Color sidebarBackground,
      Color axisInk,
      Color overlayInk,
      Color zoomLine) {

    public String trackBackgroundHex() {
      return hex(trackBackground);
    }

    public String sidebarBackgroundHex() {
      return hex(sidebarBackground);
    }
  }

  public static final ChromePalette CHROME_DARK = new ChromePalette(
      Color.web("#1e1e1e"),
      Color.web("#252526"),
      Color.web("#3c3c3c"),
      Color.web("#cccccc"),
      Color.web("#bbbbbb"),
      Color.web("#3c3c3c"),
      Color.web("#0e639c"));

  public static final ChromePalette CHROME_LIGHT = new ChromePalette(
      Color.web("#ffffff"),
      Color.web("#f3f3f3"),
      Color.web("#ffffff"),
      Color.web("#1e1e1e"),
      Color.web("#666666"),
      Color.web("#cccccc"),
      Color.web("#0078d4"));

  public static final CanvasPalette CANVAS_DARK = new CanvasPalette(
      Color.web("#1e1e1e"),
      Color.web("#252526"),
      Color.web("#cccccc"),
      Color.web("#ffffff"),
      new Color(0.3, 0.6, 0.6, 0.5));

  public static final CanvasPalette CANVAS_LIGHT = new CanvasPalette(
      Color.web("#f5f5f5"),
      Color.web("#eeeeee"),
      Color.web("#333333"),
      Color.web("#1e1e1e"),
      new Color(0.5, 0.8, 0.8, 0.5));

  private static Mode mode = Mode.DARK;

  private AppTheme() {}

  public static Mode mode() {
    return mode;
  }

  public static boolean isDark() {
    return mode == Mode.DARK;
  }

  public static void setMode(Mode next) {
    mode = next != null ? next : Mode.DARK;
  }

  public static void setDark(boolean dark) {
    setMode(dark ? Mode.DARK : Mode.LIGHT);
  }

  public static ChromePalette chrome() {
    return isDark() ? CHROME_DARK : CHROME_LIGHT;
  }

  public static CanvasPalette canvas() {
    return isDark() ? CANVAS_DARK : CANVAS_LIGHT;
  }

  static String hex(Color c) {
    if (c == null) {
      return "#000000";
    }
    int r = (int) Math.round(c.getRed() * 255);
    int g = (int) Math.round(c.getGreen() * 255);
    int b = (int) Math.round(c.getBlue() * 255);
    return String.format("#%02x%02x%02x", r, g, b);
  }
}
