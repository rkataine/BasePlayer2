package org.baseplayer.alignment.draw;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.baseplayer.alignment.AlignmentFile;
import org.baseplayer.alignment.BAMRecord;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.sample.Sample;
import org.baseplayer.utils.DrawColors;

import javafx.scene.canvas.GraphicsContext;

/**
 * Draws sashimi-plot arches (splice junction arches) for RNA-seq data.
 * <p>
 * Each arch spans from the donor site to the acceptor site of a splice
 * junction discovered in the cached reads. Arch height is proportional
 * (log-scaled) to the number of reads supporting the junction, and the
 * read count is printed at the apex of each arch.
 * <p>
 * The class is stateless; all parameters are supplied to {@link #draw}.
 */
public class SashimiDrawer {

  // ── Splice junction data record ──

  /**
   * Represents a splice junction with its genomic coordinates and read count.
   */
  private static class SpliceJunction {
    final int start; // donor (exon end, 0-based)
    final int end;   // acceptor (next exon start, 0-based)
    int count;       // number of reads spanning this junction

    SpliceJunction(int start, int end) {
      this.start = start;
      this.end = end;
      this.count = 1;
    }
  }

  // ── Public API ──

  /**
   * Collect splice junctions from the sample's cached reads and draw sashimi
   * arches into the coverage area of the sample track.
   *
   * @param gc                    target graphics context
   * @param sample                sample whose reads are queried
   * @param sampleY               top-y of the sample's coverage band (canvas coords)
   * @param covH                  height of the coverage band in pixels
   * @param canvasWidth           full canvas width in pixels
   * @param drawStack             current view parameters
   * @param chromPosToScreenPos   mapping from genomic position to screen x
   */
  public void draw(GraphicsContext gc, Sample sample,
                   double sampleY, double covH, double canvasWidth,
                   DrawStack drawStack,
                   Function<Double, Double> chromPosToScreenPos) {

    AlignmentFile bamFile = sample.getBamFile();
    if (bamFile == null) return;

    List<BAMRecord> reads = bamFile.getCachedReads(drawStack);
    if (reads == null || reads.isEmpty()) return;

    // ── Collect splice junctions from CIGAR N operations ──
    Map<Long, SpliceJunction> junctions = new HashMap<>();
    for (BAMRecord read : reads) {
      if (read.cigarOps == null) continue;
      int refPos = read.pos;
      for (int cigarOp : read.cigarOps) {
        int op  = cigarOp & 0xF;
        int len = cigarOp >>> 4;
        if (op == BAMRecord.CIGAR_N && len > 0) {
          long key = ((long) refPos << 32) | (refPos + len);
          SpliceJunction junc = junctions.get(key);
          if (junc != null) {
            junc.count++;
          } else {
            junctions.put(key, new SpliceJunction(refPos, refPos + len));
          }
        }
        // Advance ref position for ref-consuming ops
        if (op == BAMRecord.CIGAR_M || op == BAMRecord.CIGAR_D || op == BAMRecord.CIGAR_N
            || op == BAMRecord.CIGAR_EQ || op == BAMRecord.CIGAR_X) {
          refPos += len;
        }
      }
    }

    if (junctions.isEmpty()) return;

    // ── Find max count for log-scale arch heights ──
    int maxCount = 1;
    for (SpliceJunction junc : junctions.values()) {
      if (junc.count > maxCount) maxCount = junc.count;
    }

    // ── Draw arches ──
    double archMaxH = covH * 0.6; // max arch height as fraction of coverage area
    double archMinH = 8;          // minimum visible arch height

    gc.setFont(javafx.scene.text.Font.font("Segoe UI", 9));

    for (SpliceJunction junc : junctions.values()) {
      double sx1 = chromPosToScreenPos.apply((double) junc.start);
      double sx2 = chromPosToScreenPos.apply((double) junc.end);

      // Skip if completely off screen
      if (sx2 < 0 || sx1 > canvasWidth) continue;

      double archW = sx2 - sx1;
      if (archW < 2) continue; // too narrow to draw

      // Log-scale height proportional to count
      double fraction = maxCount > 1
          ? Math.log(1 + junc.count) / Math.log(1 + maxCount)
          : 1.0;
      double archH = archMinH + fraction * (archMaxH - archMinH);

      // Quadratic Bezier arch (curves upward from the base line)
      double baseY = sampleY + covH - 1;
      double midX  = (sx1 + sx2) / 2;
      double cpY   = baseY - archH; // control point above baseline

      double lineW = Math.min(4, 1.0 + junc.count * 0.3);
      gc.setLineWidth(lineW);
      gc.setStroke(DrawColors.SASHIMI_ARC_STROKE);

      gc.beginPath();
      gc.moveTo(sx1, baseY);
      gc.quadraticCurveTo(midX, cpY, sx2, baseY);
      gc.stroke();

      // Count label at arch apex
      if (archW > 20) {
        String label  = String.valueOf(junc.count);
        double labelX = midX - label.length() * 2.5;
        double labelY = cpY + (baseY - cpY) * 0.25 - 2;
        gc.setFill(DrawColors.SASHIMI_LABEL);
        gc.fillText(label, labelX, labelY);
      }
    }
    gc.setLineWidth(1.0);
  }
}
