package org.baseplayer.sanger;

import java.util.List;
import java.util.function.Function;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Draws AB1 chromatogram traces into a JavaFX {@link GraphicsContext}
 * using classic Sanger colors (A green, C blue, G black, T red).
 */
public final class ChromatogramPainter {

  private static final Color COLOR_A = Color.rgb(0, 128, 0);
  private static final Color COLOR_C = Color.BLUE;
  private static final Color COLOR_G = Color.BLACK;
  private static final Color COLOR_T = Color.RED;

  private ChromatogramPainter() {}

  /**
   * Paint one aligned trace into the given row band.
   *
   * @param chromPosToScreen maps genomic coordinate → canvas X
   */
  public static void paint(
      GraphicsContext gc,
      SangerTrace trace,
      double viewStart,
      double viewEnd,
      double canvasWidth,
      double rowTopY,
      double rowHeight,
      Function<Double, Double> chromPosToScreen) {
    if (gc == null || trace == null || !trace.isAligned() || chromPosToScreen == null) {
      return;
    }

    double traceStart = trace.getGenomicStart();
    double traceEnd = trace.getGenomicEnd();
    if (traceStart >= 0 && traceEnd >= 0 && (traceEnd < viewStart || traceStart > viewEnd)) {
      return;
    }

    Ab1Reader reader = trace.getReader();
    int[] traceA = reader.getTraceA();
    int[] traceC = reader.getTraceC();
    int[] traceG = reader.getTraceG();
    int[] traceT = reader.getTraceT();
    if (trace.isReversed()) {
      int[] tmp = traceA;
      traceA = traceT;
      traceT = tmp;
      tmp = traceC;
      traceC = traceG;
      traceG = tmp;
    }

    List<Integer> baseCalls = reader.getBaseCalls();
    if (traceA == null || baseCalls == null || baseCalls.size() < 2) {
      return;
    }

    int maxVal = 1;
    for (int val : traceA) {
      if (val > maxVal) {
        maxVal = val;
      }
    }
    for (int val : traceC) {
      if (val > maxVal) {
        maxVal = val;
      }
    }
    for (int val : traceG) {
      if (val > maxVal) {
        maxVal = val;
      }
    }
    for (int val : traceT) {
      if (val > maxVal) {
        maxVal = val;
      }
    }

    double scaleY = (rowHeight - 2) / maxVal;
    boolean reversed = trace.isReversed();
    int offset = trace.getOffset();
    double[] genomicPositions = trace.getGenomicPositions();

    int startBase = 0;
    int endBase = baseCalls.size() - 1;
    if (offset >= 0) {
      if (reversed) {
        int iMin = (int) (offset - viewEnd);
        int iMax = (int) (offset - viewStart);
        startBase = Math.max(0, iMin - 2);
        endBase = Math.min(baseCalls.size() - 1, iMax + 2);
      } else {
        int iMin = (int) (viewStart - offset);
        int iMax = (int) (viewEnd - offset);
        startBase = Math.max(0, iMin - 2);
        endBase = Math.min(baseCalls.size() - 1, iMax + 2);
      }
    }
    if (startBase >= endBase) {
      return;
    }

    double basesPerPixel = (viewEnd - viewStart) / Math.max(1.0, canvasWidth);
    int traceStep = Math.max(1, (int) (basesPerPixel * 3));

    int lastDrawnX = Integer.MIN_VALUE;
    int minYA = Integer.MAX_VALUE;
    int maxYA = Integer.MIN_VALUE;
    int minYC = Integer.MAX_VALUE;
    int maxYC = Integer.MIN_VALUE;
    int minYG = Integer.MAX_VALUE;
    int maxYG = Integer.MIN_VALUE;
    int minYT = Integer.MAX_VALUE;
    int maxYT = Integer.MIN_VALUE;
    int prevYA = -1;
    int prevYC = -1;
    int prevYG = -1;
    int prevYT = -1;
    int prevX = -1;

    gc.setLineWidth(1.0);

    for (int i = startBase; i < endBase; i++) {
      int p1 = baseCalls.get(i);
      int p2 = baseCalls.get(i + 1);
      if (p1 >= p2 || p2 > traceA.length) {
        continue;
      }

      double g1;
      double g2;
      if (genomicPositions != null && i + 1 < genomicPositions.length) {
        g1 = genomicPositions[i];
        g2 = genomicPositions[i + 1];
      } else {
        g1 = reversed ? (offset - i) : (offset + i);
        g2 = reversed ? (offset - (i + 1)) : (offset + i + 1);
      }

      for (int k = p1; k < p2; k += traceStep) {
        double f = (double) (k - p1) / (p2 - p1);
        double genomicPos = g1 + f * (g2 - g1) + 1.5;
        int x = (int) Math.floor(chromPosToScreen.apply(genomicPos));
        if (x < -1 || x > canvasWidth + 1) {
          continue;
        }

        int kEnd = Math.min(k + traceStep, p2);
        int minA = traceA[k];
        int maxA = traceA[k];
        int minC = traceC[k];
        int maxC = traceC[k];
        int minG = traceG[k];
        int maxG = traceG[k];
        int minT = traceT[k];
        int maxT = traceT[k];
        for (int kk = k + 1; kk < kEnd; kk++) {
          if (traceA[kk] < minA) {
            minA = traceA[kk];
          } else if (traceA[kk] > maxA) {
            maxA = traceA[kk];
          }
          if (traceC[kk] < minC) {
            minC = traceC[kk];
          } else if (traceC[kk] > maxC) {
            maxC = traceC[kk];
          }
          if (traceG[kk] < minG) {
            minG = traceG[kk];
          } else if (traceG[kk] > maxG) {
            maxG = traceG[kk];
          }
          if (traceT[kk] < minT) {
            minT = traceT[kk];
          } else if (traceT[kk] > maxT) {
            maxT = traceT[kk];
          }
        }

        int yAmin = (int) (rowTopY + rowHeight - 1 - maxA * scaleY);
        int yAmax = (int) (rowTopY + rowHeight - 1 - minA * scaleY);
        int yCmin = (int) (rowTopY + rowHeight - 1 - maxC * scaleY);
        int yCmax = (int) (rowTopY + rowHeight - 1 - minC * scaleY);
        int yGmin = (int) (rowTopY + rowHeight - 1 - maxG * scaleY);
        int yGmax = (int) (rowTopY + rowHeight - 1 - minG * scaleY);
        int yTmin = (int) (rowTopY + rowHeight - 1 - maxT * scaleY);
        int yTmax = (int) (rowTopY + rowHeight - 1 - minT * scaleY);

        if (x == lastDrawnX) {
          minYA = Math.min(minYA, yAmin);
          maxYA = Math.max(maxYA, yAmax);
          minYC = Math.min(minYC, yCmin);
          maxYC = Math.max(maxYC, yCmax);
          minYG = Math.min(minYG, yGmin);
          maxYG = Math.max(maxYG, yGmax);
          minYT = Math.min(minYT, yTmin);
          maxYT = Math.max(maxYT, yTmax);
        } else {
          if (lastDrawnX != Integer.MIN_VALUE && minYA != Integer.MAX_VALUE) {
            strokeChannel(gc, COLOR_A, prevX, prevYA, lastDrawnX, minYA, maxYA);
            strokeChannel(gc, COLOR_C, prevX, prevYC, lastDrawnX, minYC, maxYC);
            strokeChannel(gc, COLOR_G, prevX, prevYG, lastDrawnX, minYG, maxYG);
            strokeChannel(gc, COLOR_T, prevX, prevYT, lastDrawnX, minYT, maxYT);
            prevYA = maxYA;
            prevYC = maxYC;
            prevYG = maxYG;
            prevYT = maxYT;
            prevX = lastDrawnX;
          }
          lastDrawnX = x;
          minYA = yAmin;
          maxYA = yAmax;
          minYC = yCmin;
          maxYC = yCmax;
          minYG = yGmin;
          maxYG = yGmax;
          minYT = yTmin;
          maxYT = yTmax;
        }
      }
    }

    if (lastDrawnX != Integer.MIN_VALUE && minYA != Integer.MAX_VALUE) {
      strokeChannel(gc, COLOR_A, prevX, prevYA, lastDrawnX, minYA, maxYA);
      strokeChannel(gc, COLOR_C, prevX, prevYC, lastDrawnX, minYC, maxYC);
      strokeChannel(gc, COLOR_G, prevX, prevYG, lastDrawnX, minYG, maxYG);
      strokeChannel(gc, COLOR_T, prevX, prevYT, lastDrawnX, minYT, maxYT);
    }
  }

  private static void strokeChannel(
      GraphicsContext gc, Color color, int prevX, int prevY, int x, int yMin, int yMax) {
    gc.setStroke(color);
    if (prevY != -1 && prevX != Integer.MIN_VALUE) {
      gc.strokeLine(prevX, prevY, x, yMin);
    }
    gc.strokeLine(x, yMin, x, yMax);
  }
}
