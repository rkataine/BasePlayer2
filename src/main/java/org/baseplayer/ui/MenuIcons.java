package org.baseplayer.ui;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/**
 * Shared colored FontAwesome icons for dropdown menus.
 * Keep graphics natural-sized — JavaFX wraps them in {@code .graphic-container}.
 */
public final class MenuIcons {
  public static final int SIZE = 14;

  public static final String COLOR_VCF = "#9b6bce";
  public static final String COLOR_BAM = "#6aab73";
  public static final String COLOR_AB1 = "#d0894a";
  public static final String COLOR_BED = "#4a9ec9";
  public static final String COLOR_BIGWIG = "#c96b6b";
  public static final String COLOR_DIRECTORY = "#e2b04a";
  public static final String COLOR_MOTIF = "#7a9e6a";
  public static final String COLOR_UCSC = "#5b9bd5";

  private MenuIcons() {}

  public static void apply(MenuItem item, Ikon ikon, String colorHex) {
    if (item == null) return;
    FontIcon icon = new FontIcon(ikon);
    icon.setIconSize(SIZE);
    icon.setIconColor(Color.web(colorHex));
    icon.getStyleClass().add("menu-item-icon");
    item.setGraphic(icon);
  }

  public static void vcf(MenuItem item) {
    apply(item, FontAwesomeSolid.DNA, COLOR_VCF);
  }

  public static void bam(MenuItem item) {
    apply(item, FontAwesomeSolid.ALIGN_LEFT, COLOR_BAM);
  }

  public static void ab1(MenuItem item) {
    apply(item, FontAwesomeSolid.WAVE_SQUARE, COLOR_AB1);
  }

  public static void bed(MenuItem item) {
    apply(item, FontAwesomeSolid.CHART_AREA, COLOR_BED);
  }

  public static void bigwig(MenuItem item) {
    apply(item, FontAwesomeSolid.CHART_AREA, COLOR_BIGWIG);
  }

  public static void directory(MenuItem item) {
    apply(item, FontAwesomeSolid.FOLDER, COLOR_DIRECTORY);
  }

  public static void motif(MenuItem item) {
    apply(item, FontAwesomeSolid.FINGERPRINT, COLOR_MOTIF);
  }

  public static void ucsc(MenuItem item) {
    apply(item, FontAwesomeSolid.GLOBE, COLOR_UCSC);
  }

  /** Narrow spacer so text-only items share the graphic column with icon menus. */
  public static void spacer(MenuItem item) {
    if (item == null || item instanceof SeparatorMenuItem || item.getGraphic() != null) {
      return;
    }
    Region spacer = new Region();
    spacer.setMinSize(SIZE, SIZE);
    spacer.setPrefSize(SIZE, SIZE);
    spacer.setMaxSize(SIZE, SIZE);
    item.setGraphic(spacer);
  }
}
