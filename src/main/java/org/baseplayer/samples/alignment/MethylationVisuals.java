package org.baseplayer.samples.alignment;

import javafx.scene.paint.Color;

/**
 * Labels and colors for methylation canvas legends (site contexts + mod types).
 */
public final class MethylationVisuals {

  private MethylationVisuals() {}

  public static String contextLabel(MethylSiteContext context) {
    return context != null ? context.shortLabel() : "?";
  }

  public static Color contextColor(MethylSiteContext context) {
    if (context == null) {
      return Color.GRAY;
    }
    return switch (context) {
      case CPG -> Color.rgb(40, 180, 180);
      case CHG -> Color.rgb(80, 140, 220);
      case CHH -> Color.rgb(180, 120, 60);
    };
  }

  public static String modLabel(char modCode) {
    return switch (modCode) {
      case 'm' -> "5mC";
      case 'h' -> "5hmC";
      case 'f' -> "5fC";
      case 'c' -> "5caC";
      case 'g' -> "5hmU";
      case 'e' -> "5fU";
      case 'b' -> "5caU";
      case 'a' -> "6mA";
      case 'o' -> "8oxoG";
      case 'n' -> "Xan";
      default -> String.valueOf(modCode);
    };
  }

  public static Color modColor(char modCode) {
    // Match ModificationColorScheme.BY_TYPE at full intensity.
    return switch (modCode) {
      case 'm' -> Color.color(0.9, 0.2, 0.2);
      case 'h' -> Color.color(1.0, 0.5, 0.1);
      case 'f' -> Color.color(1.0, 0.7, 0.0);
      case 'c' -> Color.color(1.0, 0.8, 0.3);
      case 'g' -> Color.color(0.4, 0.8, 0.4);
      case 'e' -> Color.color(0.3, 0.7, 0.5);
      case 'b' -> Color.color(0.5, 0.9, 0.6);
      case 'a' -> Color.color(0.6, 0.3, 0.8);
      case 'o' -> Color.color(0.3, 0.5, 0.9);
      case 'n' -> Color.color(0.8, 0.4, 0.6);
      default -> Color.gray(0.55);
    };
  }
}
