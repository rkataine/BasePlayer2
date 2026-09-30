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

  /** UI chrome: panels, controls, text, semantic accents. */
  public record ChromePalette(
      Color surface,
      Color panel,
      Color elevated,
      Color control,
      Color text,
      Color textMuted,
      Color secondary,
      Color muted,
      Color border,
      Color stroke,
      Color accent,
      Color focus,
      Color danger,
      Color warning,
      Color success) {

    public String surfaceHex() {
      return hex(surface);
    }

    public String panelHex() {
      return hex(panel);
    }

    public String elevatedHex() {
      return hex(elevated);
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

    public String secondaryHex() {
      return hex(secondary);
    }

    public String mutedHex() {
      return hex(muted);
    }

    public String borderHex() {
      return hex(border);
    }

    public String strokeHex() {
      return hex(stroke);
    }

    public String accentHex() {
      return hex(accent);
    }

    public String focusHex() {
      return hex(focus);
    }

    public String dangerHex() {
      return hex(danger);
    }

    public String warningHex() {
      return hex(warning);
    }

    public String successHex() {
      return hex(success);
    }
  }

  /**
   * Canvas surfaces and ink. Painters should migrate here for backgrounds;
   * data glyphs remain in DrawColors / BaseColors / VariantTypeVisuals.
   */
  public record CanvasPalette(
      Color trackBackground,
      Color sidebarBackground,
      Color axisInk,
      Color overlayInk,
      Color separator,
      Color zoomLine) {

    public String trackBackgroundHex() {
      return hex(trackBackground);
    }

    public String sidebarBackgroundHex() {
      return hex(sidebarBackground);
    }

    public String axisInkHex() {
      return hex(axisInk);
    }

    public String separatorHex() {
      return hex(separator);
    }
  }

  public static final ChromePalette CHROME_DARK = new ChromePalette(
      Color.web("#1e1e1e"), // surface
      Color.web("#252526"), // panel
      Color.web("#333333"), // elevated
      Color.web("#3c3c3c"), // control
      Color.web("#cccccc"), // text
      Color.web("#bbbbbb"), // textMuted
      Color.web("#aaaaaa"), // secondary
      Color.web("#888888"), // muted
      Color.web("#3c3c3c"), // border
      Color.web("#555555"), // stroke
      Color.web("#0e639c"), // accent
      Color.web("#0078d4"), // focus (unified with light accent family)
      Color.web("#c83232"), // danger
      Color.web("#ffa500"), // warning
      Color.web("#4caf50")); // success

  public static final ChromePalette CHROME_LIGHT = new ChromePalette(
      Color.web("#f0f0f0"), // surface
      Color.web("#e4e4e4"), // panel
      Color.web("#d8d8d8"), // elevated
      Color.web("#fafafa"), // control
      Color.web("#0a0a0a"), // text (near-black for readability)
      Color.web("#2e2e2e"), // textMuted
      Color.web("#404040"), // secondary
      Color.web("#555555"), // muted
      Color.web("#a8a8a8"), // border
      Color.web("#7a7a7a"), // stroke
      Color.web("#005a9e"), // accent (darker blue)
      Color.web("#005a9e"), // focus
      Color.web("#a31515"), // danger
      Color.web("#c43e00"), // warning
      Color.web("#1b5e20")); // success

  public static final CanvasPalette CANVAS_DARK = new CanvasPalette(
      Color.web("#1e1e1e"),
      Color.web("#252526"),
      Color.web("#cccccc"),
      Color.web("#ffffff"),
      Color.rgb(80, 80, 80, 0.60),
      new Color(0.3, 0.6, 0.6, 0.5));

  public static final CanvasPalette CANVAS_LIGHT = new CanvasPalette(
      Color.web("#ececec"),
      Color.web("#e0e0e0"),
      Color.web("#1a1a1a"),
      Color.web("#0a0a0a"),
      Color.rgb(120, 120, 120, 0.60),
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

  /** Public hex helper for callers that need CSS fragments. */
  public static String toHex(Color c) {
    return hex(c);
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
