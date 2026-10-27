package org.baseplayer.variant.annotation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.baseplayer.io.readers.TabixBgzfScanner;
import org.baseplayer.io.readers.TabixIndex;
import org.baseplayer.variant.BreakendAlt;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator.ControlFileStats;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator.PopulationAnStats;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator.PopulationOption;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator.ProgressListener;

/**
 * Annotates structural-variant sample nodes with control AF from a symbolic SV
 * control VCF (e.g. gnomAD-SV style {@code <DEL>}/{@code <DUP>} records).
 *
 * <p>Matching uses a configurable breakpoint window: both ends must fall within
 * ±W bp (INS: start only; TRA/BND: primary + mate, either orientation). An optional
 * same-type requirement maps DEL↔DEL/CNV_LOSS, DUP↔DUP/CNV_GAIN, etc.
 */
public final class SvControlAnnotator implements AutoCloseable {

    public static final int DEFAULT_BREAKPOINT_WINDOW_BP = 50;

    private static final Pattern POP_FIELD =
        Pattern.compile("^(AF|AC|AN)(?:_(.+))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INFO_ID =
        Pattern.compile("##INFO=<ID=([^,>]+)", Pattern.CASE_INSENSITIVE);

    private Path path;
    private TabixBgzfScanner scanner;
    private List<String> headerPopulationSuffixes = List.of();
    private final LinkedHashSet<String> selectedPopulations = new LinkedHashSet<>();

    private int breakpointWindowBp = DEFAULT_BREAKPOINT_WINDOW_BP;
    private boolean requireSameType = true;

    public SvControlAnnotator() {
        selectedPopulations.add(ControlFrequencyAnnotator.GLOBAL_POPULATION);
    }

    public boolean isOpen() {
        return scanner != null;
    }

    public Path path() {
        return path;
    }

    public int breakpointWindowBp() {
        return breakpointWindowBp;
    }

    public void setBreakpointWindowBp(int breakpointWindowBp) {
        this.breakpointWindowBp = Math.max(0, breakpointWindowBp);
    }

    public boolean requireSameType() {
        return requireSameType;
    }

    public void setRequireSameType(boolean requireSameType) {
        this.requireSameType = requireSameType;
    }

    public synchronized Set<String> selectedPopulations() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(selectedPopulations));
    }

    public synchronized void setSelectedPopulations(Collection<String> populationIds) {
        LinkedHashSet<String> next = new LinkedHashSet<>();
        if (populationIds != null) {
            for (String raw : populationIds) {
                next.add(ControlFrequencyAnnotator.normalizePopulationId(raw));
            }
        }
        if (next.isEmpty()) {
            next.add(ControlFrequencyAnnotator.GLOBAL_POPULATION);
        }
        if (next.equals(selectedPopulations)) {
            return;
        }
        selectedPopulations.clear();
        selectedPopulations.addAll(next);
    }

    public synchronized String selectedPopulationsPersistString() {
        return selectedPopulations.stream()
            .map(ControlFrequencyAnnotator::populationIdToPersistToken)
            .collect(Collectors.joining(","));
    }

    public synchronized void open(Path vcfPath) throws IOException {
        if (vcfPath == null) {
            throw new IOException("SV control VCF path is null");
        }
        close();
        Path tbi = TabixIndex.findSiblingIndex(vcfPath);
        if (tbi == null) {
            throw new IOException(
                "SV control VCF must be bgzipped with a sibling .tbi index: "
                    + vcfPath.getFileName());
        }
        TabixIndex openedIndex = new TabixIndex(tbi);
        TabixBgzfScanner openedScanner = new TabixBgzfScanner(vcfPath, openedIndex);
        try {
            List<String> suffixes = scanHeaderPopulationSuffixes(openedScanner);
            this.scanner = openedScanner;
            this.path = vcfPath;
            this.headerPopulationSuffixes = suffixes;
        } catch (IOException e) {
            try {
                openedScanner.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        if (scanner != null) {
            try {
                scanner.close();
            } catch (IOException ignored) {
            }
        }
        scanner = null;
        path = null;
        headerPopulationSuffixes = List.of();
    }

    /** Clear {@link VariantNode#controlAf} on structural nodes only. */
    public static void clear(VariantList list) {
        if (list == null) {
            return;
        }
        for (VariantNode node = list.getFirst(); node != null; node = node.next) {
            if (VariantTypeVisuals.isStructural(node.type)) {
                node.controlAf = null;
            }
        }
        list.setSvControlAfSourcePath(null);
    }

    public synchronized boolean needsAnnotation(VariantList list) {
        if (scanner == null || path == null || list == null) {
            return false;
        }
        String annotatedFrom = list.getSvControlAfSourcePath();
        return annotatedFrom == null || !annotationFingerprint().equals(annotatedFrom);
    }

    private String annotationFingerprint() {
        List<String> sorted = new ArrayList<>(selectedPopulations);
        Collections.sort(sorted);
        return path.toAbsolutePath()
            + "|svWindow=" + breakpointWindowBp
            + "|sameType=" + requireSameType
            + "|pops=" + String.join(",", sorted);
    }

    public synchronized List<PopulationOption> listPopulations() {
        List<PopulationOption> out = new ArrayList<>();
        out.add(new PopulationOption(
            ControlFrequencyAnnotator.GLOBAL_POPULATION,
            "Global"));
        for (String suffix : headerPopulationSuffixes) {
            out.add(new PopulationOption(suffix, suffix));
        }
        return out;
    }

    public synchronized ControlFileStats sampleStats(int limit) throws IOException {
        if (scanner == null) {
            return ControlFileStats.empty();
        }
        int capped = Math.max(1, limit);
        List<PopulationOption> options = listPopulations();
        List<String> pops = new ArrayList<>(options.size());
        for (PopulationOption opt : options) {
            pops.add(opt.id());
        }
        if (pops.isEmpty()) {
            pops.add(ControlFrequencyAnnotator.GLOBAL_POPULATION);
        }

        int[] rows = { 0 };
        Map<String, Double> maxAnByPop = new HashMap<>();
        boolean[] sawAf = { false };
        boolean[] sawAcAn = { false };

        scanner.visitFirstBodyLines(capped, (buf, off, len) -> {
            rows[0]++;
            int infoStart = fieldStart(buf, off, len, 7);
            if (infoStart < 0) {
                return true;
            }
            int infoEnd = fieldEnd(buf, infoStart, off + len);
            for (String pop : pops) {
                String anKey = pop.isEmpty() ? "AN" : "AN_" + pop;
                String afKey = pop.isEmpty() ? "AF" : "AF_" + pop;
                String acKey = pop.isEmpty() ? "AC" : "AC_" + pop;
                Double an = ControlFrequencyAnnotator.infoNumber(buf, infoStart, infoEnd, anKey, 0);
                if (an != null) {
                    maxAnByPop.merge(pop, an, Math::max);
                }
                if (infoHasKey(buf, infoStart, infoEnd, afKey)) {
                    sawAf[0] = true;
                }
                if (infoHasKey(buf, infoStart, infoEnd, acKey)
                        && infoHasKey(buf, infoStart, infoEnd, anKey)) {
                    sawAcAn[0] = true;
                }
            }
            return true;
        });

        double globalAn = maxAnByPop.getOrDefault(ControlFrequencyAnnotator.GLOBAL_POPULATION, 0.0);
        if (globalAn <= 0) {
            for (double an : maxAnByPop.values()) {
                globalAn = Math.max(globalAn, an);
            }
        }

        List<PopulationAnStats> byPopulation = new ArrayList<>(pops.size());
        for (String pop : pops) {
            double maxAn = maxAnByPop.getOrDefault(pop, 0.0);
            long samples = maxAn > 0 ? Math.round(maxAn / 2.0) : 0;
            double pct = globalAn > 0 ? 100.0 * maxAn / globalAn : 0.0;
            byPopulation.add(new PopulationAnStats(pop, maxAn, samples, pct));
        }
        return new ControlFileStats(rows[0], List.copyOf(byPopulation), sawAf[0] || sawAcAn[0]);
    }

    public synchronized void annotate(VariantList list) throws IOException {
        annotate(list, null, false);
    }

    public synchronized void annotate(VariantList list, ProgressListener progress, boolean force)
            throws IOException {
        if (scanner == null || list == null || path == null) {
            return;
        }
        if (!force && !needsAnnotation(list)) {
            return;
        }
        String chromosome = list.getChromosome();
        if (chromosome == null || chromosome.isBlank()) {
            return;
        }

        for (VariantNode node = list.getFirst(); node != null; node = node.next) {
            if (VariantTypeVisuals.isStructural(node.type)) {
                node.controlAf = null;
            }
        }

        List<VariantNode> nodes = collectStructuralNodes(list);
        if (nodes.isEmpty()) {
            list.setSvControlAfSourcePath(annotationFingerprint());
            return;
        }

        Set<String> pops = selectedPopulations();
        int window = breakpointWindowBp;
        boolean sameType = requireSameType;
        List<SearchSpan> spans = buildSearchSpans(nodes, window);
        final int[] matched = { 0 };
        int spanCount = spans.size();
        if (progress != null) {
            progress.onProgress(chromosome, 0, Math.max(1, spanCount), matched[0]);
        }
        for (int s = 0; s < spanCount; s++) {
            SearchSpan span = spans.get(s);
            long queryStart = Math.max(1L, span.start() - window);
            long queryEnd = span.end() + window;
            scanner.visitRegion(chromosome, queryStart, queryEnd, (buf, off, len) -> {
                ControlSvRecord ctrl = parseControlSv(buf, off, len, pops);
                if (ctrl == null) {
                    return true;
                }
                if (ctrl.pos < queryStart || ctrl.pos > queryEnd) {
                    return ctrl.pos <= queryEnd;
                }
                for (int i = span.nodeFrom(); i < span.nodeToExclusive(); i++) {
                    VariantNode sample = nodes.get(i);
                    if (matches(sample, ctrl, chromosome, window, sameType)) {
                        writeAf(sample, ctrl.af, matched);
                    }
                }
                return true;
            });
            if (progress != null) {
                progress.onProgress(chromosome, s + 1, Math.max(1, spanCount), matched[0]);
            }
        }
        list.setSvControlAfSourcePath(annotationFingerprint());
    }

    private static void writeAf(VariantNode sample, Double af, int[] matched) {
        if (af == null) {
            return;
        }
        if (sample.controlAf == null) {
            sample.controlAf = af;
            matched[0]++;
        } else {
            sample.controlAf = Math.max(sample.controlAf, af);
        }
    }

    static boolean matches(
            VariantNode sample,
            ControlSvRecord ctrl,
            String sampleChrom,
            int windowBp,
            boolean requireSameType) {
        if (sample == null || ctrl == null || !VariantTypeVisuals.isStructural(sample.type)) {
            return false;
        }
        if (requireSameType && !compatibleTypes(sample.type, ctrl.svType, ctrl.alt)) {
            return false;
        }
        int w = Math.max(0, windowBp);
        if (isBreakendFamily(sample.type)) {
            return matchesBreakendPair(sample, ctrl, sampleChrom, w);
        }
        // INS: primary breakpoint only.
        if (sample.type == VcfVariantType.SV_INSERTION) {
            return within(sample.position, ctrl.pos, w);
        }
        // Two-breakpoint spanning SVs: both ends must match.
        if (sample.svEnd > sample.position && ctrl.end > ctrl.pos) {
            return within(sample.position, ctrl.pos, w) && within(sample.svEnd, ctrl.end, w);
        }
        // Fallback when END is missing: primary only.
        return within(sample.position, ctrl.pos, w);
    }

    private static boolean matchesBreakendPair(
            VariantNode sample, ControlSvRecord ctrl, String sampleChrom, int w) {
        String sampleMateChrom = sample.mateChromosome();
        long sampleMatePos = sample.matePosition();
        String ctrlMateChrom = ctrl.mateChromosome(sampleChrom);
        long ctrlMatePos = ctrl.matePosition();
        if (sampleMatePos < 0 || ctrlMatePos < 0
                || sampleMateChrom == null || ctrlMateChrom == null) {
            return within(sample.position, ctrl.pos, w);
        }
        // Same orientation: primary↔primary, mate↔mate.
        if (within(sample.position, ctrl.pos, w)
                && matesMatch(sampleMateChrom, sampleMatePos, ctrlMateChrom, ctrlMatePos, w)) {
            return true;
        }
        // Swapped orientation: sample primary ↔ control mate, sample mate ↔ control primary.
        return within(sample.position, ctrlMatePos, w)
            && chromEquals(sampleChrom, ctrlMateChrom)
            && within(sampleMatePos, ctrl.pos, w)
            && chromEquals(sampleMateChrom, sampleChrom);
    }

    private static boolean matesMatch(
            String chromA, long posA, String chromB, long posB, int w) {
        if (posA < 0 || posB < 0) {
            return false;
        }
        return chromEquals(chromA, chromB) && within(posA, posB, w);
    }

    private static boolean chromEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return normalizeChrom(a).equals(normalizeChrom(b));
    }

    private static String normalizeChrom(String chrom) {
        String c = chrom.trim();
        if (c.regionMatches(true, 0, "chr", 0, 3)) {
            return c.substring(3);
        }
        return c;
    }

    private static boolean within(long a, long b, int w) {
        return Math.abs(a - b) <= w;
    }

    static boolean compatibleTypes(VcfVariantType sampleType, String ctrlSvType, String ctrlAlt) {
        TypeFamily sample = familyOf(sampleType);
        TypeFamily control = familyOfControl(ctrlSvType, ctrlAlt);
        if (sample == TypeFamily.OTHER || control == TypeFamily.OTHER) {
            return sample == control;
        }
        return sample == control;
    }

    private static boolean isBreakendFamily(VcfVariantType type) {
        return type == VcfVariantType.SV_TRANSLOCATION || type == VcfVariantType.SV_BREAKEND;
    }

    enum TypeFamily {
        DEL, DUP, INV, INS, BND, OTHER
    }

    static TypeFamily familyOf(VcfVariantType type) {
        if (type == null) {
            return TypeFamily.OTHER;
        }
        return switch (type) {
            case SV_DELETION, SV_CNV_LOSS -> TypeFamily.DEL;
            case SV_DUPLICATION, SV_CNV_GAIN -> TypeFamily.DUP;
            case SV_INVERSION -> TypeFamily.INV;
            case SV_INSERTION -> TypeFamily.INS;
            case SV_TRANSLOCATION, SV_BREAKEND -> TypeFamily.BND;
            default -> TypeFamily.OTHER;
        };
    }

    static TypeFamily familyOfControl(String svType, String alt) {
        String t = svType != null ? svType.trim().toUpperCase(Locale.ROOT) : "";
        if (t.isEmpty() && alt != null) {
            String a = alt.trim().toUpperCase(Locale.ROOT);
            if (a.startsWith("<") && a.endsWith(">")) {
                t = a.substring(1, a.length() - 1);
            } else if (a.contains("[") || a.contains("]")) {
                t = "BND";
            }
        }
        if (t.startsWith("DEL") || "CNV_LOSS".equals(t) || "LOSS".equals(t)) {
            return TypeFamily.DEL;
        }
        if (t.startsWith("DUP") || t.startsWith("CNV_GAIN") || "GAIN".equals(t) || "AMP".equals(t)) {
            return TypeFamily.DUP;
        }
        if (t.startsWith("INV")) {
            return TypeFamily.INV;
        }
        if (t.startsWith("INS") || t.startsWith("MEI") || "ALU".equals(t) || "LINE1".equals(t)
                || "SVA".equals(t)) {
            return TypeFamily.INS;
        }
        if (t.startsWith("BND") || t.startsWith("TRA") || "CTX".equals(t)) {
            return TypeFamily.BND;
        }
        return TypeFamily.OTHER;
    }

    private static List<VariantNode> collectStructuralNodes(VariantList list) {
        List<VariantNode> nodes = new ArrayList<>();
        synchronized (list) {
            if (list.getVisibleNodeCount() > 0) {
                for (VariantNode node = list.getVisibleHead(); node != null; node = node.nextVisible) {
                    if (VariantTypeVisuals.isStructural(node.type)) {
                        nodes.add(node);
                    }
                }
                if (!nodes.isEmpty()) {
                    return nodes;
                }
            }
        }
        for (VariantNode node = list.getFirst(); node != null; node = node.next) {
            if (VariantTypeVisuals.isStructural(node.type)) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    /**
     * Cluster sample primary positions so nearby SVs share one tabix query.
     * Window padding (±W) is applied at query time.
     */
    static List<SearchSpan> buildSearchSpans(List<VariantNode> nodes, int windowBp) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        int mergeGap = Math.max(1, windowBp * 2);
        List<SearchSpan> spans = new ArrayList<>();
        int from = 0;
        long start = nodes.get(0).position;
        long end = nodes.get(0).position;
        for (int i = 1; i < nodes.size(); i++) {
            long pos = nodes.get(i).position;
            if (pos - end <= mergeGap) {
                end = pos;
                continue;
            }
            spans.add(new SearchSpan(start, end, from, i));
            from = i;
            start = pos;
            end = pos;
        }
        spans.add(new SearchSpan(start, end, from, nodes.size()));
        return spans;
    }

    record SearchSpan(long start, long end, int nodeFrom, int nodeToExclusive) {}

    /** Parsed control SV line used by matching (package-visible for tests). */
    record ControlSvRecord(
            long pos,
            long end,
            String chr2,
            long end2,
            String svType,
            String alt,
            Double af) {

        String mateChromosome(String primaryChrom) {
            if (chr2 != null && !chr2.isBlank()) {
                return chr2;
            }
            BreakendAlt.Mate mate = BreakendAlt.parse(alt);
            if (mate != null) {
                return mate.chrom();
            }
            // Intra-chromosomal spanning call: mate is END on the same chrom.
            if (end > pos) {
                return primaryChrom;
            }
            return null;
        }

        long matePosition() {
            if (end2 >= 0) {
                return end2;
            }
            BreakendAlt.Mate mate = BreakendAlt.parse(alt);
            if (mate != null) {
                return mate.pos();
            }
            if (end > pos) {
                return end;
            }
            return -1;
        }
    }

    static ControlSvRecord parseControlSv(
            byte[] buf, int off, int len, Collection<String> pops) {
        if (buf == null || len <= 0 || buf[off] == '#') {
            return null;
        }
        int end = off + len;
        int t1 = indexOf(buf, off, end, (byte) '\t');
        int t2 = t1 < 0 ? -1 : indexOf(buf, t1 + 1, end, (byte) '\t');
        int t3 = t2 < 0 ? -1 : indexOf(buf, t2 + 1, end, (byte) '\t');
        int t4 = t3 < 0 ? -1 : indexOf(buf, t3 + 1, end, (byte) '\t');
        int t5 = t4 < 0 ? -1 : indexOf(buf, t4 + 1, end, (byte) '\t');
        int t6 = t5 < 0 ? -1 : indexOf(buf, t5 + 1, end, (byte) '\t');
        int t7 = t6 < 0 ? -1 : indexOf(buf, t6 + 1, end, (byte) '\t');
        int t8 = t7 < 0 ? -1 : indexOf(buf, t7 + 1, end, (byte) '\t');
        if (t2 < 0 || t4 < 0 || t5 < 0 || t7 < 0) {
            return null;
        }
        long pos = parseLongDigits(buf, t1 + 1, t2);
        if (pos < 0) {
            return null;
        }
        int altStart = t4 + 1;
        int altEnd = t5;
        if (altStart >= altEnd) {
            return null;
        }
        // Only symbolic / breakend ALTs are SV control records.
        if (!isSymbolicOrBreakendAlt(buf, altStart, altEnd - altStart)) {
            return null;
        }
        String alt = new String(buf, altStart, altEnd - altStart, StandardCharsets.US_ASCII);
        int infoStart = t7 + 1;
        int infoEnd = t8 < 0 ? end : t8;
        Double af = ControlFrequencyAnnotator.resolveAfFromInfoBytes(
            buf, infoStart, infoEnd, 0, 1, pops);
        String svType = infoString(buf, infoStart, infoEnd, "SVTYPE");
        long svEnd = infoLong(buf, infoStart, infoEnd, "END");
        String chr2 = infoString(buf, infoStart, infoEnd, "CHR2");
        long end2 = infoLong(buf, infoStart, infoEnd, "END2");
        if (end2 < 0) {
            end2 = infoLong(buf, infoStart, infoEnd, "POS2");
        }
        return new ControlSvRecord(pos, svEnd, chr2, end2, svType, alt, af);
    }

    private static boolean isSymbolicOrBreakendAlt(byte[] buf, int start, int len) {
        if (len <= 0) {
            return false;
        }
        if (buf[start] == '<') {
            return true;
        }
        for (int i = start; i < start + len; i++) {
            byte b = buf[i];
            if (b == '[' || b == ']') {
                return true;
            }
        }
        return false;
    }

    private static List<String> scanHeaderPopulationSuffixes(TabixBgzfScanner scanner)
            throws IOException {
        LinkedHashSet<String> suffixes = new LinkedHashSet<>();
        scanner.visitHeaderLines(
            (buf, off, len) -> {
                if (len < 10 || buf[off] != '#') {
                    return true;
                }
                if (len >= 8
                        && buf[off + 1] == '#'
                        && buf[off + 2] == 'I'
                        && buf[off + 3] == 'N'
                        && buf[off + 4] == 'F'
                        && buf[off + 5] == 'O'
                        && buf[off + 6] == '=') {
                    String line = new String(buf, off, len, StandardCharsets.US_ASCII);
                    Matcher idMatch = INFO_ID.matcher(line);
                    if (idMatch.find()) {
                        String id = idMatch.group(1);
                        Matcher m = POP_FIELD.matcher(id);
                        if (m.matches()) {
                            String suffix = m.group(2);
                            if (suffix != null && !suffix.isBlank()) {
                                suffixes.add(suffix);
                            }
                        }
                    }
                }
                return true;
            },
            line -> line.startsWith("#CHROM") || (line.length() > 0 && line.charAt(0) != '#'));
        return List.copyOf(suffixes);
    }

    private static String infoString(byte[] buf, int start, int end, String key) {
        if (buf == null || key == null || key.isEmpty() || start >= end) {
            return null;
        }
        int keyLen = key.length();
        int at = start;
        while (at < end) {
            int eq = indexOf(buf, at, end, (byte) '=');
            if (eq < 0) {
                return null;
            }
            if (eq - at == keyLen && regionMatchesIgnoreCase(buf, at, key, keyLen)) {
                int valStart = eq + 1;
                int valEnd = indexOf(buf, valStart, end, (byte) ';');
                if (valEnd < 0) {
                    valEnd = end;
                }
                if (valStart >= valEnd) {
                    return null;
                }
                return new String(buf, valStart, valEnd - valStart, StandardCharsets.US_ASCII);
            }
            int next = indexOf(buf, eq + 1, end, (byte) ';');
            if (next < 0) {
                return null;
            }
            at = next + 1;
        }
        return null;
    }

    private static long infoLong(byte[] buf, int start, int end, String key) {
        Double n = ControlFrequencyAnnotator.infoNumber(buf, start, end, key, 0);
        if (n == null || n < 0) {
            return -1;
        }
        return n.longValue();
    }

    private static boolean infoHasKey(byte[] buf, int start, int end, String key) {
        if (buf == null || key == null || key.isEmpty() || start >= end) {
            return false;
        }
        int keyLen = key.length();
        int at = start;
        while (at < end) {
            int eq = indexOf(buf, at, end, (byte) '=');
            int semi = indexOf(buf, at, end, (byte) ';');
            int keyEnd = eq >= 0 && (semi < 0 || eq < semi) ? eq : (semi < 0 ? end : semi);
            if (keyEnd - at == keyLen && regionMatchesIgnoreCase(buf, at, key, keyLen)) {
                return true;
            }
            if (semi < 0) {
                return false;
            }
            at = semi + 1;
        }
        return false;
    }

    private static int fieldStart(byte[] buf, int off, int len, int fieldIndex) {
        int end = off + len;
        int at = off;
        for (int f = 0; f < fieldIndex; f++) {
            int tab = indexOf(buf, at, end, (byte) '\t');
            if (tab < 0) {
                return -1;
            }
            at = tab + 1;
        }
        return at;
    }

    private static int fieldEnd(byte[] buf, int start, int end) {
        int tab = indexOf(buf, start, end, (byte) '\t');
        return tab < 0 ? end : tab;
    }

    private static int indexOf(byte[] buf, int from, int end, byte value) {
        for (int i = from; i < end; i++) {
            if (buf[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static boolean regionMatchesIgnoreCase(byte[] buf, int start, String key, int keyLen) {
        for (int i = 0; i < keyLen; i++) {
            int b = buf[start + i] & 0xFF;
            char c = key.charAt(i);
            if (b >= 'A' && b <= 'Z') {
                b += 32;
            }
            if (c >= 'A' && c <= 'Z') {
                c += 32;
            }
            if (b != c) {
                return false;
            }
        }
        return true;
    }

    private static long parseLongDigits(byte[] buf, int start, int end) {
        if (start >= end) {
            return -1;
        }
        long value = 0;
        for (int i = start; i < end; i++) {
            int d = buf[i] - '0';
            if (d < 0 || d > 9) {
                return -1;
            }
            value = value * 10 + d;
        }
        return value;
    }
}
