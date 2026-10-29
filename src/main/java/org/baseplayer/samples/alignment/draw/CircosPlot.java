package org.baseplayer.samples.alignment.draw;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.GeneColors;
import org.baseplayer.variant.annotation.VariantAnnotator;

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
 * Circos-style plot summarising long-range connections originating from the
 * tracks currently loaded in the viewer: split reads / SA tags, discordant
 * inter-chromosomal read pairs, and VCF translocation / inter-chrom breakend
 * calls.
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
        DISCORDANT_PAIR, // inter-chromosomal mate
        VCF_TRA          // VCF TRA / inter-chromosomal BND
    }

    // ── Colour palette (matches ReadStructureBar) ────────────────────────────
    private static final Color[] CHROM_COLORS = {
        Color.web("#4E79A7"), Color.web("#F28E2B"), Color.web("#E15759"),
        Color.web("#76B7B2"), Color.web("#59A14F"), Color.web("#EDC948"),
        Color.web("#B07AA1"), Color.web("#FF9DA7"), Color.web("#9C755F"),
        Color.web("#BAB0AC")
    };

    // Layout is defined as fractions of the canvas size.
    private static final double R_OUTER_FRAC      = 0.34;
    private static final double R_INNER_FRAC      = 0.32;
    private static final double R_LABEL_FRAC      = 0.375; // chromosome names
    private static final double R_GENE_BASE_FRAC  = 0.42;  // innermost gene-label ring
    private static final double R_GENE_STEP_FRAC  = 0.028; // radial stack step
    private static final int    R_GENE_MAX_LEVEL  = 3;
    private static final double GAP_ANGLE_DEG     = 1.0;

    /** Bucket size for treating nearby breakpoints as the same locus. */
    private static final long BREAK_BUCKET = 5_000_000L;
    /** Max distance (bp) from a breakpoint to count a COSMIC gene as "near". */
    private static final long NEAR_GENE_BP = 2_000_000L;

    private static final double COMBINED_SIZE   = 780;
    private static final double PER_SAMPLE_SIZE = 340;

    private record ChromArc(String name, long length, double startRad, double spanRad) {
        double angleFor(long pos) {
            double frac = length <= 0 ? 0 : Math.max(0, Math.min(1.0, (double) pos / length));
            return startRad + frac * spanRad;
        }
    }

    private record HitRegion(Link link, boolean atA, double x, double y, double radius,
                             int splitCount, int discordantCount, int vcfCount, int sampleCount) {}

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
        int splits = 0, discos = 0, vcfTra = 0;
        for (Link l : allLinks) {
            switch (l.type) {
                case SPLIT_READ -> splits++;
                case DISCORDANT_PAIR -> discos++;
                case VCF_TRA -> vcfTra++;
            }
        }
        Label title = new Label(circosTitle(splits, discos, vcfTra));
        title.setFont(AppFonts.getBoldFont(13));
        title.setStyle("-fx-text-fill: #ddd;");

        Label stats = new Label(circosStats(splits, discos, vcfTra, linksBySample.size()));
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
            int s = 0, d = 0, v = 0;
            for (Link l : sampleLinks) {
                switch (l.type) {
                    case SPLIT_READ -> s++;
                    case DISCORDANT_PAIR -> d++;
                    case VCF_TRA -> v++;
                }
            }

            Label name = new Label(sample);
            name.setFont(AppFonts.getBoldFont(12));
            name.setStyle("-fx-text-fill: #ddd;");
            Label counts = new Label(perSampleCounts(s, d, v));
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
            if (len == null) {
              len = lengths.get(org.baseplayer.utils.ChromosomeNames.forData(n, org.baseplayer.utils.ChromosomeNames.CHR_PREFIX));
            }
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
            if (len == null) {
              len = lengths.get(org.baseplayer.utils.ChromosomeNames.forData(n, org.baseplayer.utils.ChromosomeNames.CHR_PREFIX));
            }
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
                    String vcfLine = h.vcfCount > 0
                        ? String.format("%nVCF translocations: %d", h.vcfCount) : "";
                    tooltip.setText(String.format(
                        "%s:%,d%nSplit reads: %d  \u2022  Discordant pairs: %d%s%s%s",
                        chr, pos, h.splitCount, h.discordantCount, vcfLine, sampleCountLine, sampleLine));
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
            record BucketKey(String cA, long bA, String cB, long bB) {}
            Map<BucketKey, List<Link>> grouped = new LinkedHashMap<>();
            for (Link l : links) {
                ChromArc arcA = arcs.get(normalize(l.chrA));
                ChromArc arcB = arcs.get(normalize(l.chrB));
                if (arcA == null || arcB == null) continue;
                String nA = arcA.name, nB = arcB.name;
                long bA = l.posA / BREAK_BUCKET, bB = l.posB / BREAK_BUCKET;
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
                int vcfCount        = (int) group.stream().filter(l -> l.type == LinkType.VCF_TRA).count();
                int discordantCount = count - splitCount - vcfCount;
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

                // Color the chord by target chromosome (chrB) instead of source (chrA).
                Color chord = colorFor(arcB.name).deriveColor(0, 1, 1, 0.70);
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

                hitRegions.add(new HitRegion(rep, true,  ax, ay, dotR + 2, splitCount, discordantCount, vcfCount, sampleCount));
                hitRegions.add(new HitRegion(rep, false, bx, by, dotR + 2, splitCount, discordantCount, vcfCount, sampleCount));
            }
            g.setLineWidth(1);

            // 3) Cancer-gene labels near unique breakpoint loci
            if (showLabels && AnnotationData.isGenesLoaded()) {
                drawCancerGeneLabels(g, cx, cy, rOuter, size);
            }
        }

        /**
         * Label COSMIC census genes near breakpoints. Breakpoints in the same
         * {@link #BREAK_BUCKET} locus are collapsed so each location contributes
         * at most one gene, and each gene is drawn at most once. Nearby labels
         * are stacked on successive radial rings to avoid overlap.
         */
        private void drawCancerGeneLabels(GraphicsContext g, double cx, double cy,
                                          double rOuter, double size) {
            Map<String, List<Gene>> byChrom = AnnotationData.getGenesByChrom();
            if (byChrom == null || byChrom.isEmpty()) return;

            Map<LocusBucket, long[]> locusSum = new LinkedHashMap<>();
            for (Link l : links) {
                addBreakLocus(locusSum, l.chrA, l.posA);
                addBreakLocus(locusSum, l.chrB, l.posB);
            }

            double fontSize = size >= 600 ? 10 : 8;
            javafx.scene.text.Font bold = AppFonts.getBoldFont(fontSize);
            g.setFont(bold);

            Set<String> seenGenes = new LinkedHashSet<>();
            List<GeneLabel> labels = new ArrayList<>();
            for (Map.Entry<LocusBucket, long[]> e : locusSum.entrySet()) {
                LocusBucket key = e.getKey();
                ChromArc arc = arcs.get(key.chrom());
                if (arc == null) continue;

                long sum = e.getValue()[0];
                long n = e.getValue()[1];
                long avgPos = n <= 0 ? 0 : sum / n;

                List<Gene> genes = genesForChrom(byChrom, key.chrom());
                String geneName = VariantAnnotator.nearestCensusGeneAnySide(genes, avgPos);
                if (geneName == null || !seenGenes.add(geneName)) continue;

                long labelPos;
                GeneLocation loc = AnnotationData.getGeneLocation(geneName);
                if (loc != null && normalize(loc.chrom()).equals(key.chrom())) {
                    if (distanceToInterval(avgPos, loc.start(), loc.end()) > NEAR_GENE_BP) {
                        seenGenes.remove(geneName);
                        continue;
                    }
                    labelPos = (loc.start() + loc.end()) / 2;
                } else {
                    Gene geneModel = findGene(genes, geneName);
                    if (geneModel == null) {
                        seenGenes.remove(geneName);
                        continue;
                    }
                    if (distanceToInterval(avgPos, geneModel.start(), geneModel.end()) > NEAR_GENE_BP) {
                        seenGenes.remove(geneName);
                        continue;
                    }
                    labelPos = (geneModel.start() + geneModel.end()) / 2;
                }

                javafx.scene.text.Text measure = new javafx.scene.text.Text(geneName);
                measure.setFont(bold);
                double textW = measure.getLayoutBounds().getWidth();
                labels.add(new GeneLabel(geneName, arc.angleFor(labelPos), textW));
            }
            if (labels.isEmpty()) return;

            labels.sort(java.util.Comparator.comparingDouble(l -> l.angle));
            assignGeneLabelLevels(labels, size);

            double rBase = size * R_GENE_BASE_FRAC;
            double rStep = size * R_GENE_STEP_FRAC;
            Color leader = GeneColors.COSMIC_COLOR.deriveColor(0, 1, 1, 0.55);
            g.setLineWidth(0.9);

            for (GeneLabel lab : labels) {
                double r = rBase + lab.level * rStep;
                double ax = cx + Math.cos(lab.angle) * rOuter;
                double ay = cy + Math.sin(lab.angle) * rOuter;
                double mx = cx + Math.cos(lab.angle) * (rOuter + (r - rOuter) * 0.45);
                double my = cy + Math.sin(lab.angle) * (rOuter + (r - rOuter) * 0.45);
                double lx = cx + Math.cos(lab.angle) * r;
                double ly = cy + Math.sin(lab.angle) * r;

                g.setStroke(leader);
                g.beginPath();
                g.moveTo(ax, ay);
                g.lineTo(mx, my);
                g.lineTo(lx, ly);
                g.stroke();

                double tx = lx - lab.textW / 2;
                double ty = ly + fontSize * 0.35;
                // Dark halo so bold orange text stays readable over chords / labels.
                g.setFill(Color.web("#1a1a1a"));
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) continue;
                        g.fillText(lab.name, tx + dx, ty + dy);
                    }
                }
                g.setFill(GeneColors.COSMIC_COLOR);
                g.fillText(lab.name, tx, ty);
            }
            g.setLineWidth(1);
        }

        /**
         * Greedy radial stacking: place each label on the lowest ring where its
         * angular footprint does not collide with already-placed labels.
         */
        private static void assignGeneLabelLevels(List<GeneLabel> labels, double size) {
            double rBase = size * R_GENE_BASE_FRAC;
            double rStep = size * R_GENE_STEP_FRAC;
            double padPx = 4;

            List<List<GeneLabel>> byLevel = new ArrayList<>(R_GENE_MAX_LEVEL + 1);
            for (int i = 0; i <= R_GENE_MAX_LEVEL; i++) {
                byLevel.add(new ArrayList<>());
            }

            for (GeneLabel lab : labels) {
                int placed = R_GENE_MAX_LEVEL;
                for (int level = 0; level <= R_GENE_MAX_LEVEL; level++) {
                    double r = rBase + level * rStep;
                    double halfSpan = (lab.textW / 2.0 + padPx) / Math.max(1.0, r);
                    boolean ok = true;
                    for (GeneLabel other : byLevel.get(level)) {
                        if (angularDistance(lab.angle, other.angle) < halfSpan + other.halfSpan) {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) {
                        lab.level = level;
                        lab.halfSpan = halfSpan;
                        placed = level;
                        break;
                    }
                }
                if (lab.halfSpan <= 0) {
                    double r = rBase + placed * rStep;
                    lab.level = placed;
                    lab.halfSpan = (lab.textW / 2.0 + padPx) / Math.max(1.0, r);
                }
                byLevel.get(lab.level).add(lab);
            }
        }

        /** Smallest absolute angle between two radians on the circle. */
        private static double angularDistance(double a, double b) {
            double d = Math.abs(a - b) % (2 * Math.PI);
            if (d > Math.PI) d = 2 * Math.PI - d;
            return d;
        }

        private static final class GeneLabel {
            final String name;
            final double angle;
            final double textW;
            int level;
            double halfSpan;

            GeneLabel(String name, double angle, double textW) {
                this.name = name;
                this.angle = angle;
                this.textW = textW;
            }
        }

        private void addBreakLocus(Map<LocusBucket, long[]> locusSum, String chrom, int pos) {
            String n = normalize(chrom);
            if (n.isEmpty() || arcs.get(n) == null) return;
            LocusBucket key = new LocusBucket(n, Math.max(0, pos) / BREAK_BUCKET);
            long[] acc = locusSum.computeIfAbsent(key, k -> new long[2]);
            acc[0] += Math.max(0, pos);
            acc[1] += 1;
        }

        private static List<Gene> genesForChrom(Map<String, List<Gene>> byChrom, String chrom) {
            List<Gene> genes = byChrom.getOrDefault(chrom, List.of());
            if (!genes.isEmpty()) return genes;
            return byChrom.getOrDefault(
                org.baseplayer.utils.ChromosomeNames.forData(
                    chrom, org.baseplayer.utils.ChromosomeNames.CHR_PREFIX),
                List.of());
        }

        private static Gene findGene(List<Gene> genes, String name) {
            if (genes == null || name == null) return null;
            for (Gene gene : genes) {
                if (name.equals(gene.name())) return gene;
            }
            return null;
        }

        private static long distanceToInterval(long pos, long start, long end) {
            if (pos >= start && pos <= end) return 0;
            if (pos < start) return start - pos;
            return pos - end;
        }
    }

    /** Chromosome + position-bucket key for collapsing co-located breakpoints. */
    private record LocusBucket(String chrom, long bucket) {}


    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String circosTitle(int splits, int discos, int vcfTra) {
        boolean hasReads = splits > 0 || discos > 0;
        if (hasReads && vcfTra > 0) {
            return "Circos \u2014 split reads, discordant pairs & VCF translocations";
        }
        if (vcfTra > 0) {
            return "Circos \u2014 VCF translocations";
        }
        return "Circos \u2014 split reads & discordant pairs";
    }

    private static String circosStats(int splits, int discos, int vcfTra, int sampleCount) {
        if (vcfTra > 0) {
            return String.format(
                "%d split-read links  \u2022  %d inter-chromosomal pairs  \u2022  %d VCF translocations  \u2022  %d sample(s)",
                splits, discos, vcfTra, sampleCount);
        }
        return String.format(
            "%d split-read links  \u2022  %d inter-chromosomal pairs  \u2022  %d sample(s)",
            splits, discos, sampleCount);
    }

    private static String perSampleCounts(int splits, int discos, int vcfTra) {
        if (vcfTra > 0) {
            return String.format("%d split  \u2022  %d discordant  \u2022  %d VCF TRA", splits, discos, vcfTra);
        }
        return String.format("%d split  \u2022  %d discordant", splits, discos);
    }

    private static String normalize(String chrom) {
        if (chrom == null) return "";
        return org.baseplayer.utils.ChromosomeNames.strip(chrom);
    }

    private static Color colorFor(String chrom) {
        int h = Math.abs(normalize(chrom).hashCode());
        return CHROM_COLORS[h % CHROM_COLORS.length];
    }
}
