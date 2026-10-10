package org.baseplayer.features.motif;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.utils.BaseColors;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

/**
 * Relative-frequency sequence logo: each column is a full-height stack of
 * A/C/G/T glyphs scaled by frequency (Gagniuc / WebLogo relative style).
 *
 * <p>Glyphs are rasterized with AWT on a background thread. JavaFX
 * {@code Text#snapshot} / {@code Canvas#snapshot} during (or near) track-canvas
 * paint corrupts NGCanvas's opcode stream — seen on project open as
 * {@code Unrecognized PGCanvas token} / Paint↔Font {@code ClassCastException}.
 */
public final class SequenceLogoPainter {

  private static final char[] BASES = {'A', 'C', 'G', 'T'};
  private static final int REF_FONT_SIZE = 120;
  /** Slight horizontal overlap so stretched letters meet with no hairline gaps. */
  private static final double COL_OVERLAP_PX = 0.75;

  private static Image[] glyphs; // A,C,G,T
  private static volatile boolean glyphsReady;
  private static volatile boolean warmUpScheduled;

  private SequenceLogoPainter() {}

  /**
   * Schedule glyph baking if needed. Safe from any thread; never touches a
   * live JavaFX canvas.
   */
  public static void warmUp() {
    if (glyphsReady || warmUpScheduled) {
      return;
    }
    warmUpScheduled = true;
    Thread t = new Thread(SequenceLogoPainter::bakeGlyphs, "jaspar-logo-glyphs");
    t.setDaemon(true);
    t.start();
  }

  /** {@code true} once logo images are available for {@link #draw}. */
  public static boolean isReady() {
    return glyphsReady;
  }

  private static void bakeGlyphs() {
    if (glyphsReady) {
      return;
    }
    try {
      java.awt.Font font = resolveAwtFont();
      Image[] baked = new Image[4];
      for (int i = 0; i < 4; i++) {
        baked[i] = rasterizeGlyph(font, BASES[i], BaseColors.getBaseColor(BASES[i]));
      }
      glyphs = baked;
      glyphsReady = true;
      Platform.runLater(() -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));
    } catch (Throwable ex) {
      warmUpScheduled = false;
      System.err.println("SequenceLogoPainter: glyph bake failed: " + ex.getMessage());
    }
  }

  private static java.awt.Font resolveAwtFont() {
    java.awt.Font font = new java.awt.Font("Arial Black", java.awt.Font.BOLD, REF_FONT_SIZE);
    String family = font.getFamily() == null ? "" : font.getFamily().toLowerCase();
    if (family.contains("dialog") || (!family.contains("arial") && !family.contains("black"))) {
      font = new java.awt.Font("Arial", java.awt.Font.BOLD, REF_FONT_SIZE);
      family = font.getFamily() == null ? "" : font.getFamily().toLowerCase();
      if (family.contains("dialog")) {
        font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, REF_FONT_SIZE);
      }
    }
    return font;
  }

  private static Image rasterizeGlyph(java.awt.Font font, char base, Color fxColor) {
    BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    Graphics2D pg = probe.createGraphics();
    pg.setFont(font);
    FontMetrics fm = pg.getFontMetrics();
    int ascent = fm.getAscent();
    int descent = fm.getDescent();
    int w = Math.max(1, fm.charWidth(base) + 8);
    int h = Math.max(1, ascent + descent + 8);
    pg.dispose();

    BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = bi.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    g.setFont(font);
    g.setColor(new java.awt.Color(
        (float) fxColor.getRed(),
        (float) fxColor.getGreen(),
        (float) fxColor.getBlue(),
        (float) fxColor.getOpacity()));
    g.drawString(String.valueOf(base), 4, ascent + 4);
    g.dispose();

    return trimAndCopy(bi);
  }

  /** Crop to opaque ink so letter images fill the column when stretched. */
  private static Image trimAndCopy(BufferedImage src) {
    int w = src.getWidth();
    int h = src.getHeight();
    int minX = w;
    int minY = h;
    int maxX = -1;
    int maxY = -1;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        if ((src.getRGB(x, y) >>> 24) > 8) {
          if (x < minX) {
            minX = x;
          }
          if (y < minY) {
            minY = y;
          }
          if (x > maxX) {
            maxX = x;
          }
          if (y > maxY) {
            maxY = y;
          }
        }
      }
    }
    if (maxX < minX || maxY < minY) {
      minX = 0;
      minY = 0;
      maxX = w - 1;
      maxY = h - 1;
    }
    int tw = maxX - minX + 1;
    int th = maxY - minY + 1;
    WritableImage img = new WritableImage(tw, th);
    PixelWriter pw = img.getPixelWriter();
    for (int y = 0; y < th; y++) {
      for (int x = 0; x < tw; x++) {
        pw.setArgb(x, y, src.getRGB(minX + x, minY + y));
      }
    }
    return img;
  }

  /**
   * Draw a relative-style logo for {@code matrix} at pixel {@code x}/{@code y}.
   * If glyphs are not ready yet, schedules warm-up and returns without drawing
   * (caller should fall back to a plain bar).
   *
   * @param forward {@code true} for + strand; reverse uses RC column order/bases
   * @return {@code true} if a logo was painted
   */
  public static boolean draw(
      GraphicsContext gc,
      MotifMatrix matrix,
      double x,
      double y,
      double pxPerBp,
      double height,
      boolean forward) {
    if (matrix == null || height < 4 || pxPerBp < 2) {
      return false;
    }
    if (!glyphsReady) {
      warmUp();
      return false;
    }
    Image[] local = glyphs;
    if (local == null) {
      return false;
    }
    int len = matrix.length();
    if (forward) {
      for (int j = 0; j < len; j++) {
        drawColumn(gc, local, matrix, j, false, x + j * pxPerBp, y, pxPerBp, height);
      }
    } else {
      for (int j = len - 1, col = 0; j >= 0; j--, col++) {
        drawColumn(gc, local, matrix, j, true, x + col * pxPerBp, y, pxPerBp, height);
      }
    }
    return true;
  }

  private static void drawColumn(
      GraphicsContext gc,
      Image[] glyphImages,
      MotifMatrix matrix,
      int col,
      boolean reverseComplement,
      double x,
      double y,
      double width,
      double height) {
    int sum = 0;
    int[] counts = new int[4];
    for (int b = 0; b < 4; b++) {
      int src = reverseComplement ? 3 - b : b;
      counts[b] = matrix.count(src, col);
      sum += counts[b];
    }
    if (sum <= 0) {
      return;
    }

    int[] order = {0, 1, 2, 3};
    for (int i = 0; i < 3; i++) {
      for (int j = i + 1; j < 4; j++) {
        if (counts[order[j]] > counts[order[i]]) {
          int tmp = order[i];
          order[i] = order[j];
          order[j] = tmp;
        }
      }
    }

    double yCursor = y;
    double colW = Math.max(1, width);
    double drawW = colW + COL_OVERLAP_PX;
    double ox = x - COL_OVERLAP_PX * 0.5;
    for (int idx : order) {
      double frac = counts[idx] / (double) sum;
      double h = height * frac;
      if (h < 0.75) {
        continue;
      }
      gc.drawImage(glyphImages[idx], ox, yCursor, drawW, h);
      yCursor += h;
    }
  }
}
