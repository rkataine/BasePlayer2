package org.baseplayer.samples.alignment.draw;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.baseplayer.utils.AppFonts;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Circos-style plot summarising long-range connections (split reads / SA tags
 * and discordant inter-chromosomal read pairs) originating from the tracks
 * currently loaded in the viewer.
 *
 * <p>When multiple samples contribute links a toggle switches between a single
 * combined plot and a grid of smaller per-sample plots.
 */
public final class CircosPlot {

    /** A genomic connection between two positions, tagged with its originating sample. */
    public record Link(String chrA, int posA, String chrB, int posB,
                       LinkType type, String sample) {}

    public enum LinkType {
        SPLIT_READ,      // SA tag
        DISCORDANT_PAIR  // inter-chromosomal mate
    }

    // ── Colour palette (matches ReadStructureBar) ────────────────────────────
    private static final Color[] CHROM_COLORS = {
        Color.web("#4E79A7"), Color.web("#F28E2B"), Color.web("#E15759"),
        Color.web("#76B7B2"), Color.web("#59A14F"), Color.web("#EDC948"),
        Color.web("#B07AA1"), Color.web("#FF9DA7"), Color.web("#9C755F"),
        Color.web("#BAB0AC")
    };

    // Layout is defined as fractions of the canvas size.
    private static final double R_OUTER_FRAC   = 0.41;
    private static final double R_INNER_FRAC   = 0.39;
    private static final double R_LABEL_FRAC   = 0.455;
    private static final double GAP_ANGLE_DEG  = 1.0;

    private static final double COMBINED_SIZE   = 780;
    private static final double PER_SAMPLE_SIZE = 340;

    private record ChromArc(String name, long length, double startRad, double spanRad) {
        double angleFor(long pos) {
            double frac = length <= 0 ? 0 : Math.max(0, Math.min(1.0, (double) pos / length));
            return startRad + frac * spanRad;
        }
    }

    private record HitRegion(Link link, boolean atA, double x, double y, double radius,
                             int splitCount, int discordantCount, int sampleCount) {}

    private final Stage stage = new Stage();
    private final Map<String, ChromArc> arcs = new LinkedHashMap<>();

    private final List<Link> allLinks;
    private final Map<String, List<Link>> linksBySample = new LinkedHashMap<>();
    private final String currentChrom;
    private final BiConsumer<String, Integer> navigator;

    public CircosPlot(List<Link> links, List<String> chromNames,
                      Map<String, Long> chromLengths, String currentChrom,
                      BiConsumer<String, Integer> navigator) {
        this.allLinks     = links;
        this.currentChrom = normalize(currentChrom);
        this.navigator    = navigator;

        for (Link l : links) {
            String s = l.sample() != null ? l.sample() : "(unknown)";
            linksBySample.computeIfAbsent(s, k -> new ArrayList<>()).add(l);
        }

        layoutChromosomes(chromNames, chromLengths);
        buildUi();
    }

    // ── UI construction ──────────────────────────────────────────────────────

    private void buildUi() {
        Label title = new Label("Circos \u2014 split reads & discordant pairs");
        title.setFont(AppFonts.getBoldFont(13));
        title.setStyle("-fx-text-fill: #ddd;");

        int splits = 0, discos = 0;
        for (Link l : allLinks) {
            if (l.type == LinkType.SPLIT_READ) splits++; else discos++;
        }
        Label stats = new Label(String.format(
                "%d split-read links  \u2022  %d inter-chromosomal pairs  \u2022  %d sample(s)",
                splits, discos, linksBySample.size()));
        stats.setFont(AppFonts.getUIFont());
        stats.setStyle("-fx-text-fill: #999;");

        ToggleGroup modeGroup = new ToggleGroup();
        ToggleButton combinedBtn = new ToggleButton("Combined");
        ToggleButton perSampleBtn = new ToggleButton("Per sample");
        combinedBtn.setToggleGroup(modeGroup);
        perSampleBtn.setToggleGroup(modeGroup);
        combinedBtn.setSelected(true);
        final String tbStyle    = "-fx-background-color: #333; -fx-text-fill: #ccc; -fx-border-color: #555;";
        final String tbSelStyle = "-fx-background-color: #3a6ea5; -fx-text-fill: white; -fx-border-color: #5a8ec5;";
        combinedBtn.setStyle(tbSelStyle);
        perSampleBtn.setStyle(tbStyle);
        perSampleBtn.setDisable(linksBySample.size() <= 1);

        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox header = new HBox(12, title, stats, spacer, combinedBtn, perSampleBtn);
        header.setPadding(new Insets(8, 12, 8, 12));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #222; -fx-border-color: #333; -fx-border-width: 0 0 1 0;");

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setStyle("-fx-background-color: #1a1a1a;");
        root.setCenter(buildCombinedView());

        modeGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT == null) { oldT.setSelected(true); return; }
            combinedBtn.setStyle(combinedBtn.isSelected() ? tbSelStyle : tbStyle);
            perSampleBtn.setStyle(perSampleBtn.isSelected() ? tbSelStyle : tbStyle);
            root.setCenter(combinedBtn.isSelected() ? buildCombinedView() : buildPerSampleView());
        });

        Scene scene = new Scene(root, COMBINED_SIZE + 40, COMBINED_SIZE + 68);
        stage.setTitle("Circos plot");
        stage.setScene(scene);
    }

    private javafx.scene.Node buildCombinedView() {
        PlotView pv = new PlotView(allLinks, COMBINED_SIZE, null);
        StackPane wrap = new StackPane(pv.node());
        wrap.setStyle("-fx-background-color: #1a1a1a;");
        return wrap;
    }

    private javafx.scene.Node buildPerSampleView() {
        TilePane tiles = new TilePane();
        tiles.setHgap(12);
        tiles.setVgap(12);
        tiles.setPadding(new Insets(12));
        tiles.setStyle("-fx-background-color: #1a1a1a;");
        tiles.setPrefColumns(2);

        for (Map.Entry<String, List<Link>> e : linksBySample.entrySet()) {
            String sample = e.getKey();
            List<Link> sampleLinks = e.getValue();
            int s = 0, d = 0;
            for (Link l : sampleLinks) {
                if (l.type == LinkType.SPLIT_READ) s++; else d++;
            }

            Label name = new Label(sample);
            name.setFont(AppFonts.getBoldFont(12));
            name.setStyle("-fx-text-fill: #ddd;");
            Label counts = new Label(String.format("%d split  \u2022  %d discordant", s, d));
            counts.setFont(AppFonts.getUIFont());
            counts.setStyle("-fx-text-fill: #888;");

            PlotView pv = new PlotView(sampleLinks, PER_SAMPLE_SIZE, sample);
            VBox cell = new VBox(2, name, counts, pv.node());
            cell.setPadding(new Insets(6));
            cell.setStyle("-fx-background-color: #222; -fx-border-color: #333; "
                        + "-fx-background-radius: 4; -fx-border-radius: 4;");
            tiles.getChildren().add(cell);
        }

        ScrollPane sp = new ScrollPane(tiles);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background: #1a1a1a; -fx-background-color: #1a1a1a;");
        return sp;
    }

    public void show() {
        stage.show();
        stage.toFront();
    }

    // ── Chromosome arc layout ────────────────────────────────────────────────

    private void layoutChromosomes(List<String> names, Map<String, Long> lengths) {
        List<String> keep = new ArrayList<>();
        long total = 0;
        for (String raw : names) {
            String n = normalize(raw);
            Long len = lengths.get(raw);
            if (len == null) len = lengths.get(n);
            if (len == null) len = lengths.get("chr" + n);
            if (len == null || len <= 0) continue;
            keep.add(n);
            total += len;
        }
        if (keep.isEmpty() || total <= 0) return;

        double gapTotal = Math.toRadians(GAP_ANGLE_DEG) * keep.size();
        double usable   = 2 * Math.PI - gapTotal;
        double cursor   = -Math.PI / 2;
        for (String n : keep) {
            Long len = lengths.get(n);
            if (len == null) len = lengths.get("chr" + n);
            double span = usable * ((double) len / total);
            arcs.put(n, new ChromArc(n, len, cursor, span));
            cursor += span + Math.toRadians(GAP_ANGLE_DEG);
        }
    }

    // ── Single plot (canvas + hit regions + interaction) ─────────────────────

    private final class PlotView {

        private final Canvas canvas;
        private final double size;
        private final List<Link> links;
        private final List<HitRegion> hitRegions = new ArrayList<>();

        PlotView(List<Link> links, double size, String sampleName) {
            this.links  = links;
            this.size   = size;
            this.canvas = new Canvas(size, size);
            installHandlers(sampleName);
            draw();
        }

        javafx.scene.Node node() { return canvas; }

        private void installHandlers(String sampleName) {
            Tooltip tooltip = new Tooltip();
            tooltip.setShowDelay(Duration.millis(200));
            tooltip.setHideDelay(Duration.ZERO);
            Tooltip.install(canvas, tooltip);

            canvas.setOnMouseMoved(e -> {
                HitRegion h = hit(e.getX(), e.getY());
                if (h != null) {
                    Link l = h.link;
                    String chr = h.atA ? l.chrA : l.chrB;
                    int   pos  = h.atA ? l.posA  : l.posB;
                    String sampleLine = sampleName != null ? "\n" + sampleName : "";
                    String sampleCountLine = sampleName == null && h.sampleCount > 0
                        ? String.format("%nSamples: %d", h.sampleCount) : "";
                    tooltip.setText(String.format(
                        "%s:%,d%nSplit reads: %d  \u2022  Discordant pairs: %d%s%s",
                        chr, pos, h.splitCount, h.discordantCount, sampleCountLine, sampleLine));
                    canvas.setCursor(javafx.scene.Cursor.HAND);
                } else {
                    tooltip.setText("");
                    canvas.setCursor(javafx.scene.Cursor.DEFAULT);
                }
            });

            canvas.setOnMouseClicked(e -> {
                if (navigator == null) return;
                HitRegion h = hit(e.getX(), e.getY());
                if (h != null) {
                    String chr = h.atA ? h.link.chrA : h.link.chrB;
                    int    pos = h.atA ? h.link.posA : h.link.posB;
                    navigator.accept(chr, Math.max(0, pos - 1));
                    stage.close();
                }
            });
        }

        private HitRegion hit(double x, double y) {
            HitRegion best = null;
            double bestD = Double.MAX_VALUE;
            for (HitRegion h : hitRegions) {
                double dx = x - h.x, dy = y - h.y;
                double d2 = dx * dx + dy * dy;
                double r = h.radius + 2;
                if (d2 <= r * r && d2 < bestD) { best = h; bestD = d2; }
            }
            return best;
        }

        private void draw() {
            GraphicsContext g = canvas.getGraphicsContext2D();
            g.setFill(Color.web("#1a1a1a"));
            g.fillRect(0, 0, size, size);
            hitRegions.clear();

            if (arcs.isEmpty()) {
                g.setFill(Color.web("#888"));
                g.setFont(AppFonts.getUIFont());
                g.fillText("No chromosome data.", 10, 20);
                return;
            }

            double cx = size / 2;
            double cy = size / 2;
            double rOuter = size * R_OUTER_FRAC;
            double rInner = size * R_INNER_FRAC;
            double rLabel = size * R_LABEL_FRAC;

            boolean showLabels = size >= 260;
            double maxThick    = size >= 600 ? 10.0 : 5.0;

            // 1) Chromosome arcs (strokeArc OPEN — no radial pie slices)
            double bandThick = rOuter - rInner;
            double midRadius = (rOuter + rInner) / 2.0;
            g.setLineWidth(bandThick);
            g.setLineCap(javafx.scene.shape.StrokeLineCap.BUTT);
            for (ChromArc a : arcs.values()) {
                boolean isCurrent = a.name.equals(currentChrom);
                Color base = colorFor(a.name);
                g.setStroke(isCurrent ? base.brighter() : base);

                double startDeg = -Math.toDegrees(a.startRad + a.spanRad);
                double arcDeg   =  Math.toDegrees(a.spanRad);
                g.strokeArc(cx - midRadius, cy - midRadius, midRadius * 2, midRadius * 2,
                            startDeg, arcDeg, ArcType.OPEN);

                if (showLabels) {
                    double midAng = a.startRad + a.spanRad / 2;
                    double lx = cx + Math.cos(midAng) * rLabel;
                    double ly = cy + Math.sin(midAng) * rLabel;
                    g.setFill(isCurrent ? Color.WHITE : Color.web("#ccc"));
                    double fontSize = size >= 600 ? 11 : 9;
                    g.setFont(isCurrent ? AppFonts.getBoldFont(fontSize) : AppFonts.getUIFont());
                    String name = a.name;
                    double tw = name.length() * (fontSize * 0.55);
                    g.fillText(name, lx - tw / 2, ly + fontSize * 0.35);
                }
            }
            g.setLineWidth(1);

            // 2) Chords — bucket nearby links so thickness encodes read count
            final long BUCKET = 5_000_000L;

            record BucketKey(String cA, long bA, String cB, long bB) {}
            Map<BucketKey, List<Link>> grouped = new LinkedHashMap<>();
            for (Link l : links) {
                ChromArc arcA = arcs.get(normalize(l.chrA));
                ChromArc arcB = arcs.get(normalize(l.chrB));
                if (arcA == null || arcB == null) continue;
                String nA = arcA.name, nB = arcB.name;
                long bA = l.posA / BUCKET, bB = l.posB / BUCKET;
                BucketKey key = (nA.compareTo(nB) < 0 || (nA.equals(nB) && bA <= bB))
                        ? new BucketKey(nA, bA, nB, bB)
                        : new BucketKey(nB, bB, nA, bA);
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
            }

            int maxCount = grouped.values().stream().mapToInt(List::size).max().orElse(1);
            g.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);

            for (Map.Entry<BucketKey, List<Link>> entry : grouped.entrySet()) {
                List<Link> group = entry.getValue();
                int count = group.size();
                int splitCount      = (int) group.stream().filter(l -> l.type == LinkType.SPLIT_READ).count();
                int discordantCount = count - splitCount;
                int sampleCount     = (int) group.stream()
                        .map(Link::sample).filter(s -> s != null).distinct().count();

                Link rep = group.get(group.size() / 2);

                ChromArc arcA = arcs.get(normalize(rep.chrA));
                ChromArc arcB = arcs.get(normalize(rep.chrB));
                if (arcA == null || arcB == null) continue;

                double angA = arcA.angleFor((long) group.stream().mapToLong(l -> l.posA).average().orElse(rep.posA));
                double angB = arcB.angleFor((long) group.stream().mapToLong(l -> l.posB).average().orElse(rep.posB));
                double ax = cx + Math.cos(angA) * rInner;
                double ay = cy + Math.sin(angA) * rInner;
                double bx = cx + Math.cos(angB) * rInner;
                double by = cy + Math.sin(angB) * rInner;

                double midX  = (ax + bx) / 2;
                double midY  = (ay + by) / 2;
                double ctrlX = cx * 0.45 + midX * 0.55;
                double ctrlY = cy * 0.45 + midY * 0.55;

                double thick = (maxCount <= 1) ? 1.0
                        : 1.0 + (maxThick - 1.0) * Math.log1p(count - 1) / Math.log1p(maxCount - 1);

                String colorKey = arcA.name.equals(currentChrom) ? arcB.name : arcA.name;
                Color chord = colorFor(colorKey).deriveColor(0, 1, 1, 0.70);
                g.setStroke(chord);
                g.setLineWidth(thick);

                g.beginPath();
                g.moveTo(ax, ay);
                g.quadraticCurveTo(ctrlX, ctrlY, bx, by);
                g.stroke();

                double dotR = Math.max(2, (size >= 600 ? 3 : 2) + Math.log1p(count));
                g.setFill(colorFor(arcA.name));
                g.fillOval(ax - dotR, ay - dotR, dotR * 2, dotR * 2);
                g.setFill(colorFor(arcB.name));
                g.fillOval(bx - dotR, by - dotR, dotR * 2, dotR * 2);

                hitRegions.add(new HitRegion(rep, true,  ax, ay, dotR + 2, splitCount, discordantCount, sampleCount));
                hitRegions.add(new HitRegion(rep, false, bx, by, dotR + 2, splitCount, discordantCount, sampleCount));
            }
            g.setLineWidth(1);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String normalize(String chrom) {
        if (chrom == null) return "";
        return chrom.startsWith("chr") ? chrom.substring(3) : chrom;
    }

    private static Color colorFor(String chrom) {
        int h = Math.abs(normalize(chrom).hashCode());
        return CHROM_COLORS[h % CHROM_COLORS.length];
    }
}
