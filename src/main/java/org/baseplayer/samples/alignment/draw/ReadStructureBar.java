package org.baseplayer.samples.alignment.draw;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.samples.alignment.BAMRecord;
import org.baseplayer.samples.alignment.draw.ReadInfoPopup.MateNavigator;
import org.baseplayer.utils.AppFonts;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * Visual diagram of a chimeric read's structure.
 *
 * <p>The read is drawn as a horizontal bar (full original read length). Each
 * primary + SA supplementary alignment is rendered as an arrow-tipped rectangle
 * positioned on the portion of the read it maps. Arrow direction encodes strand;
 * fill colour encodes target chromosome. Segments that overlap in read-coordinate
 * space are stacked onto separate vertical levels.
 *
 * <p>Clicking a segment navigates to its genomic location via the supplied
 * {@link MateNavigator}.
 */
public class ReadStructureBar extends Pane {

    // ── Colour palette (colour-blind-friendly) ───────────────────────────────
    private static final Color[] CHROM_COLORS = {
        Color.web("#4E79A7"), Color.web("#F28E2B"), Color.web("#E15759"),
        Color.web("#76B7B2"), Color.web("#59A14F"), Color.web("#EDC948"),
        Color.web("#B07AA1"), Color.web("#FF9DA7"), Color.web("#9C755F"),
        Color.web("#BAB0AC")
    };

    private static final double ROW_HEIGHT = 20;
    private static final double ROW_GAP    = 4;
    private static final double TOP_MARGIN = 14;
    private static final double BOT_MARGIN = 4;

    private record Segment(
            int readStart, int readEnd,
            String chrom, int refPos1based,
            boolean reverse, int mapq, String cigar) {
        int length() { return readEnd - readStart; }
    }

    private record DrawnSegment(Segment seg, double x, double y, double w, double h) {}

    private final List<Segment> segments = new ArrayList<>();
    private final List<DrawnSegment> drawn = new ArrayList<>();
    private int totalReadLength = 0;

    public ReadStructureBar(BAMRecord primary, String primaryChrom,
                            MateNavigator navigator, double width) {
        parseSegments(primary, primaryChrom);
        if (segments.isEmpty() || totalReadLength <= 0) return;

        int[] levels = packLevels();
        int numLevels = 1;
        for (int l : levels) numLevels = Math.max(numLevels, l + 1);

        double height = TOP_MARGIN + numLevels * (ROW_HEIGHT + ROW_GAP) + BOT_MARGIN;
        Canvas canvas = new Canvas(width, height);
        setPrefSize(width, height);
        setMinSize(width, height);
        setMaxSize(width, height);
        getChildren().add(canvas);

        draw(canvas.getGraphicsContext2D(), width, height, levels);

        Tooltip tooltip = new Tooltip();
        tooltip.setShowDelay(Duration.millis(250));
        tooltip.setHideDelay(Duration.ZERO);
        tooltip.setFont(AppFonts.getUIFont());
        Tooltip.install(canvas, tooltip);

        canvas.setOnMouseMoved(e -> {
            DrawnSegment d = hit(e.getX(), e.getY());
            if (d != null) {
                Segment s = d.seg;
                tooltip.setText(String.format(
                    "%s:%,d %s%nMAPQ=%d%nread %,d\u2013%,d (%,d bp)",
                    s.chrom, s.refPos1based, s.reverse ? "(-)" : "(+)",
                    s.mapq, s.readStart, s.readEnd, s.length()));
                canvas.setCursor(javafx.scene.Cursor.HAND);
            } else {
                tooltip.setText("");
                canvas.setCursor(javafx.scene.Cursor.DEFAULT);
            }
        });

        canvas.setOnMouseClicked(e -> {
            if (navigator == null) return;
            DrawnSegment d = hit(e.getX(), e.getY());
            if (d != null) {
                navigator.goToMate(d.seg.chrom, d.seg.refPos1based - 1);
            }
        });
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private void parseSegments(BAMRecord primary, String primaryChrom) {
        if (primary.cigarOps == null || primary.cigarOps.length == 0) return;

        int[] pspan = cigarSpanOps(primary.cigarOps);
        int total = pspan[0] + pspan[1] + pspan[2];
        boolean prev = primary.isReverseStrand();
        int rs = prev ? pspan[2] : pspan[0];
        int re = rs + pspan[1];
        segments.add(new Segment(rs, re, primaryChrom, primary.pos + 1,
                prev, primary.mapq, cigarOpsToString(primary.cigarOps)));
        totalReadLength = total;

        if (primary.saTag == null || primary.saTag.isBlank()) return;
        for (String entry : primary.saTag.split(";")) {
            if (entry.isBlank()) continue;
            String[] parts = entry.split(",", -1);
            if (parts.length < 5) continue;
            String chrom = parts[0];
            int pos;
            try { pos = Integer.parseInt(parts[1]); }
            catch (NumberFormatException ignore) { continue; }
            boolean rev = "-".equals(parts[2]);
            String cigar = parts[3];
            int mapq;
            try { mapq = Integer.parseInt(parts[4]); }
            catch (NumberFormatException ignore) { mapq = 0; }

            int[] span = cigarSpanStr(cigar);
            int tot = span[0] + span[1] + span[2];
            totalReadLength = Math.max(totalReadLength, tot);
            int sStart = rev ? span[2] : span[0];
            int sEnd = sStart + span[1];
            segments.add(new Segment(sStart, sEnd, chrom, pos, rev, mapq, cigar));
        }
    }

    // ── Level packing ────────────────────────────────────────────────────────

    /** First-fit placement: put each segment on the first level whose last
     *  occupied segment ends before the new segment starts. */
    private int[] packLevels() {
        Integer[] order = new Integer[segments.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) ->
            Integer.compare(segments.get(a).readStart, segments.get(b).readStart));

        int[] levels = new int[segments.size()];
        List<Integer> levelEnds = new ArrayList<>();
        for (int idx : order) {
            Segment s = segments.get(idx);
            int placed = -1;
            for (int L = 0; L < levelEnds.size(); L++) {
                if (levelEnds.get(L) <= s.readStart) { placed = L; break; }
            }
            if (placed < 0) {
                placed = levelEnds.size();
                levelEnds.add(s.readEnd);
            } else {
                levelEnds.set(placed, s.readEnd);
            }
            levels[idx] = placed;
        }
        return levels;
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    private void draw(GraphicsContext g, double width, double height, int[] levels) {
        g.clearRect(0, 0, width, height);
        g.setFill(Color.web("#1a1a1a"));
        g.fillRect(0, 0, width, height);

        double leftPad = 4, rightPad = 4;
        double usable = width - leftPad - rightPad;
        double scale = usable / totalReadLength;

        // Scale labels
        g.setFill(Color.web("#888"));
        g.setFont(AppFonts.getUIFont());
        g.fillText("0", leftPad, 10);
        String endLbl = String.format("%,d bp", totalReadLength);
        double endWidth = endLbl.length() * 5.5;
        g.fillText(endLbl, leftPad + usable - endWidth, 10);

        // Baseline for each level (full read extent)
        g.setStroke(Color.web("#444"));
        g.setLineWidth(1);
        int maxLevel = 0;
        for (int l : levels) maxLevel = Math.max(maxLevel, l);
        for (int L = 0; L <= maxLevel; L++) {
            double y = TOP_MARGIN + L * (ROW_HEIGHT + ROW_GAP) + ROW_HEIGHT / 2;
            g.strokeLine(leftPad, y, leftPad + usable, y);
        }

        // Segments
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            double x = leftPad + s.readStart * scale;
            double w = Math.max(s.length() * scale, 4);
            double y = TOP_MARGIN + levels[i] * (ROW_HEIGHT + ROW_GAP);
            drawSegment(g, s, x, y, w, ROW_HEIGHT);
            drawn.add(new DrawnSegment(s, x, y, w, ROW_HEIGHT));
        }
    }

    private void drawSegment(GraphicsContext g, Segment s,
                             double x, double y, double w, double h) {
        Color fill = colorFor(s.chrom);

        // Arrow-tipped rectangle: tip on right for '+', left for '-'
        double tip = Math.min(6, w * 0.3);
        double[] xs, ys;
        if (!s.reverse) {
            xs = new double[]{x, x + w - tip, x + w, x + w - tip, x};
            ys = new double[]{y, y, y + h / 2, y + h, y + h};
        } else {
            xs = new double[]{x + tip, x + w, x + w, x + tip, x};
            ys = new double[]{y, y, y + h, y + h, y + h / 2};
        }
        g.setFill(fill);
        g.fillPolygon(xs, ys, 5);
        g.setStroke(fill.darker());
        g.setLineWidth(0.8);
        g.strokePolygon(xs, ys, 5);

        // Label inside if there's room
        if (w > 50) {
            g.setFill(Color.WHITE);
            g.setFont(AppFonts.getUIFont());
            String lbl = s.chrom + ":" + formatPos(s.refPos1based);
            g.fillText(lbl, x + (s.reverse ? tip + 2 : 4), y + h / 2 + 4);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private DrawnSegment hit(double x, double y) {
        for (DrawnSegment d : drawn) {
            if (x >= d.x && x <= d.x + d.w && y >= d.y && y <= d.y + d.h) return d;
        }
        return null;
    }

    private static String formatPos(int p) {
        if (p >= 1_000_000) return String.format("%.1fM", p / 1_000_000.0);
        if (p >= 1_000) return String.format("%.0fK", p / 1_000.0);
        return String.valueOf(p);
    }

    private static Color colorFor(String chrom) {
        String c = chrom;
        if (c.startsWith("chr")) c = c.substring(3);
        int h = Math.abs(c.hashCode());
        return CHROM_COLORS[h % CHROM_COLORS.length];
    }

    // ── CIGAR parsing ────────────────────────────────────────────────────────

    /** Returns [leftClip, alignedOnQuery, rightClip] where alignedOnQuery =
     *  sum of M/I/=/X lengths and clips include both S and H. */
    private static int[] cigarSpanOps(int[] ops) {
        int left = 0, aligned = 0, right = 0;
        boolean seenAligned = false;
        for (int co : ops) {
            int op = co & 0xF;
            int len = co >>> 4;
            boolean isClip = (op == 4 || op == 5); // S or H
            boolean alignsQuery = (op == 0 || op == 1 || op == 7 || op == 8); // M,I,=,X
            if (isClip) {
                if (!seenAligned) left += len; else right += len;
            } else if (alignsQuery) {
                aligned += len;
                seenAligned = true;
            }
        }
        return new int[]{left, aligned, right};
    }

    private static int[] cigarSpanStr(String cigar) {
        int left = 0, aligned = 0, right = 0;
        boolean seenAligned = false;
        int num = 0;
        for (int i = 0; i < cigar.length(); i++) {
            char ch = cigar.charAt(i);
            if (ch >= '0' && ch <= '9') { num = num * 10 + (ch - '0'); continue; }
            switch (ch) {
                case 'S', 'H' -> { if (!seenAligned) left += num; else right += num; }
                case 'M', 'I', '=', 'X' -> { aligned += num; seenAligned = true; }
                default -> { /* D, N, P don't consume query */ }
            }
            num = 0;
        }
        return new int[]{left, aligned, right};
    }

    private static final char[] CIGAR_CHARS = {'M','I','D','N','S','H','P','=','X'};

    private static String cigarOpsToString(int[] ops) {
        StringBuilder sb = new StringBuilder();
        for (int co : ops) {
            int op = co & 0xF;
            int len = co >>> 4;
            sb.append(len);
            sb.append(op < CIGAR_CHARS.length ? CIGAR_CHARS[op] : '?');
        }
        return sb.toString();
    }
}
