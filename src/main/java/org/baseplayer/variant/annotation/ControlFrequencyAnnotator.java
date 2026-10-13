package org.baseplayer.variant.annotation;

import java.io.IOException;
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

import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfHeaderFieldDef;
import htsjdk.variant.variantcontext.VariantContext;

/**
 * Annotates sample variants with population AF from a local control VCF (e.g. gnomAD sites).
 * One chromosome = one tabix span of raw VCF lines, then a sorted merge-join on POS only;
 * REF/ALT/INFO are parsed only on position hits (no HTSJDK {@code VariantContext} decode).
 * Selected populations use {@code AF}/{@code AC}+{@code AN} (and {@code AF_fin}/…); when
 * several are selected, {@link VariantNode#controlAf} is the max among those fields.
 */
public final class ControlFrequencyAnnotator implements AutoCloseable {

    private static final Pattern POP_FIELD =
        Pattern.compile("^(AF|AC|AN)(?:_(.+))?$", Pattern.CASE_INSENSITIVE);

    /** Optional progress: {@code chrom}, sample index, sample total, matches so far. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String chromosome, int sampleIndex, int sampleTotal, int matched);
    }

    /** Empty string = global AF/AC/AN. */
    public static final String GLOBAL_POPULATION = "";
    /** Persist token for {@link #GLOBAL_POPULATION} in project docs. */
    public static final String GLOBAL_POPULATION_TOKEN = "global";

    private VcfReader reader;
    private Path path;
    /**
     * Selected population suffixes (empty string = global). Order preserved for UI;
     * fingerprint uses a sorted join.
     */
    private final LinkedHashSet<String> selectedPopulations = new LinkedHashSet<>();

    public ControlFrequencyAnnotator() {
        selectedPopulations.add(GLOBAL_POPULATION);
    }

    public boolean isOpen() {
        return reader != null;
    }

    public Path path() {
        return path;
    }

    /** First selected population (legacy single-pop callers). */
    public String populationId() {
        synchronized (this) {
            if (selectedPopulations.isEmpty()) {
                return GLOBAL_POPULATION;
            }
            return selectedPopulations.iterator().next();
        }
    }

    /** Immutable snapshot of selected population ids (empty string = global). */
    public synchronized Set<String> selectedPopulations() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(selectedPopulations));
    }

    /**
     * Replace the selected population set. Empty input selects global only.
     * Ids are normalized ({@code AF_fin} → {@code fin}, {@code global} → {@code ""}).
     */
    public synchronized void setSelectedPopulations(Collection<String> populationIds) {
        LinkedHashSet<String> next = new LinkedHashSet<>();
        if (populationIds != null) {
            for (String raw : populationIds) {
                next.add(normalizePopulationId(raw));
            }
        }
        if (next.isEmpty()) {
            next.add(GLOBAL_POPULATION);
        }
        if (next.equals(selectedPopulations)) {
            return;
        }
        selectedPopulations.clear();
        selectedPopulations.addAll(next);
    }

    /** Convenience: select exactly one population (replaces multi-select). */
    public synchronized void setPopulationId(String populationId) {
        setSelectedPopulations(List.of(normalizePopulationId(populationId)));
    }

    /** Persistable comma-separated ids ({@code global,fin,nfe}). */
    public synchronized String selectedPopulationsPersistString() {
        return selectedPopulations.stream()
            .map(ControlFrequencyAnnotator::populationIdToPersistToken)
            .collect(Collectors.joining(","));
    }

    /** Parse a persist string (comma-separated) into normalized population ids. */
    public static List<String> parsePopulationsPersistString(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of(GLOBAL_POPULATION);
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            ids.add(normalizePopulationId(part.trim()));
        }
        if (ids.isEmpty()) {
            return List.of(GLOBAL_POPULATION);
        }
        return List.copyOf(ids);
    }

    public static String populationIdToPersistToken(String populationId) {
        String id = normalizePopulationId(populationId);
        return id.isEmpty() ? GLOBAL_POPULATION_TOKEN : id;
    }

    /**
     * Open a bgzipped+tabix control VCF. Closes any previously open reader.
     *
     * @throws IOException if the file cannot be opened or has no tabix index
     */
    public synchronized void open(Path vcfPath) throws IOException {
        if (vcfPath == null) {
            throw new IOException("Control VCF path is null");
        }
        close();
        VcfReader opened = new VcfReader(vcfPath);
        if (!opened.canTabixQuery()) {
            try {
                opened.close();
            } catch (IOException ignored) {
            }
            throw new IOException(
                "Control VCF must be bgzipped with a sibling .tbi index: " + vcfPath.getFileName());
        }
        this.reader = opened;
        this.path = vcfPath;
    }

    @Override
    public synchronized void close() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
            reader = null;
            path = null;
        }
    }

    /** Clear {@link VariantNode#controlAf} on every node in the list. */
    public static void clear(VariantList list) {
        if (list == null) {
            return;
        }
        for (VariantNode node = list.getFirst(); node != null; node = node.next) {
            node.controlAf = null;
        }
        list.setControlAfSourcePath(null);
    }

    /** True when this list still needs control AF annotation from the open file + population. */
    public synchronized boolean needsAnnotation(VariantList list) {
        if (reader == null || path == null || list == null) {
            return false;
        }
        String annotatedFrom = list.getControlAfSourcePath();
        return annotatedFrom == null || !annotationFingerprint().equals(annotatedFrom);
    }

    private String annotationFingerprint() {
        List<String> sorted = new ArrayList<>(selectedPopulations);
        Collections.sort(sorted);
        return path.toAbsolutePath() + "|pops=" + String.join(",", sorted);
    }

    /**
     * Populations available from ##INFO (global + suffixes like {@code fin} from {@code AN_fin}).
     * First entry is always global.
     */
    public synchronized List<PopulationOption> listPopulations() {
        List<PopulationOption> out = new ArrayList<>();
        out.add(new PopulationOption(GLOBAL_POPULATION, populationLabel(GLOBAL_POPULATION)));
        if (reader == null) {
            return out;
        }
        Set<String> suffixes = new LinkedHashSet<>();
        for (VcfHeaderFieldDef def : reader.getInfoHeaderDefs()) {
            if (def == null || def.id() == null) {
                continue;
            }
            Matcher m = POP_FIELD.matcher(def.id());
            if (!m.matches()) {
                continue;
            }
            String suffix = m.group(2);
            if (suffix != null && !suffix.isBlank()) {
                suffixes.add(suffix);
            }
        }
        for (String suffix : suffixes) {
            out.add(new PopulationOption(suffix, populationLabel(suffix)));
        }
        return out;
    }

    /**
     * Sample the first {@code limit} variants and estimate diploid sample counts per
     * population ({@code max AN / 2}). Percentages are relative to global {@code AN}.
     */
    public synchronized ControlFileStats sampleStats(int limit) throws IOException {
        if (reader == null) {
            return ControlFileStats.empty();
        }
        int capped = Math.max(1, limit);
        List<PopulationOption> options = listPopulations();
        List<String> pops = new ArrayList<>(options.size());
        for (PopulationOption opt : options) {
            pops.add(opt.id());
        }
        if (pops.isEmpty()) {
            pops.add(GLOBAL_POPULATION);
        }

        int rows = 0;
        Map<String, Double> maxAnByPop = new HashMap<>();
        boolean sawAf = false;
        boolean sawAcAn = false;
        try (var it = reader.rawIterator()) {
            while (it.hasNext() && rows < capped) {
                VariantContext ctx = it.next();
                rows++;
                if (ctx == null) {
                    continue;
                }
                for (String pop : pops) {
                    String anKey = pop.isEmpty() ? "AN" : "AN_" + pop;
                    String afKey = pop.isEmpty() ? "AF" : "AF_" + pop;
                    String acKey = pop.isEmpty() ? "AC" : "AC_" + pop;
                    Double an = firstNumber(ctx.getAttribute(anKey), 0, 1);
                    if (an != null) {
                        maxAnByPop.merge(pop, an, Math::max);
                    }
                    if (ctx.getAttribute(afKey) != null) {
                        sawAf = true;
                    }
                    if (ctx.getAttribute(acKey) != null && ctx.getAttribute(anKey) != null) {
                        sawAcAn = true;
                    }
                }
            }
        }

        double globalAn = maxAnByPop.getOrDefault(GLOBAL_POPULATION, 0.0);
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
        return new ControlFileStats(rows, List.copyOf(byPopulation), sawAf || sawAcAn);
    }

    /**
     * Annotate all nodes in {@code list} for its chromosome using the open control VCF.
     * No-op when no reader is open, the list is empty, or already annotated from this file.
     */
    public synchronized void annotate(VariantList list) throws IOException {
        annotate(list, null);
    }

    /**
     * Same as {@link #annotate(VariantList)} with optional progress callbacks.
     * Progress {@code current}/{@code total} tracks genomic span scanned (bar-friendly).
     */
    public synchronized void annotate(VariantList list, ProgressListener progress) throws IOException {
        if (reader == null || list == null || path == null) {
            return;
        }
        if (!needsAnnotation(list)) {
            return;
        }
        String chromosome = list.getChromosome();
        if (chromosome == null || chromosome.isBlank()) {
            return;
        }

        List<VariantNode> nodes = new ArrayList<>();
        for (VariantNode node = list.getFirst(); node != null; node = node.next) {
            node.controlAf = null;
            nodes.add(node);
        }
        if (nodes.isEmpty()) {
            list.setControlAfSourcePath(annotationFingerprint());
            return;
        }

        // One tabix span + single sorted merge-join on POS (raw lines, no VariantContext).
        // Alleles / INFO are parsed only when control POS hits a sample locus.
        long spanStart = nodes.get(0).position;
        long spanEnd = alleleEnd(nodes.get(nodes.size() - 1));
        long spanLen = Math.max(1L, spanEnd - spanStart + 1);
        Set<String> pops = selectedPopulations();
        Map<String, Double> afByKey = new HashMap<>(Math.max(16, nodes.size() / 2));
        final int total = nodes.size();
        final int[] sampleIdx = { 0 };
        final int[] matched = { 0 };
        final int[] scanned = { 0 };

        if (progress != null) {
            progress.onProgress(chromosome, 0, progressTotal(spanLen), matched[0]);
        }

        if (reader.canTabixQuery()) {
            reader.visitRawVariantLines(chromosome, spanStart, spanEnd, line -> {
                scanned[0]++;
                long pos = parseVcfPos(line);
                if (pos < 0) {
                    return;
                }

                int i = sampleIdx[0];
                while (i < total && nodes.get(i).position < pos) {
                    i++;
                }
                sampleIdx[0] = i;
                if (i < total && nodes.get(i).position == pos) {
                    // Position hit — parse REF/ALT/INFO from this line only.
                    matchRawLine(line, pos, nodes, i, total, pops, afByKey, matched);
                }
                if (progress != null && scanned[0] % 10_000 == 0) {
                    progress.onProgress(
                        chromosome, progressCurrent(pos, spanStart, spanLen),
                        progressTotal(spanLen), matched[0]);
                }
            });
        } else {
            // Rare non-tabix control files: full decode fallback.
            reader.visitVariantContexts(chromosome, spanStart, spanEnd, ctx -> {
                if (ctx == null) {
                    return;
                }
                scanned[0]++;
                long pos = ctx.getStart();
                int i = sampleIdx[0];
                while (i < total && nodes.get(i).position < pos) {
                    i++;
                }
                sampleIdx[0] = i;
                if (i < total && nodes.get(i).position == pos && !ctx.isSymbolic()) {
                    String ref = ctx.getReference().getDisplayString();
                    var alts = ctx.getAlternateAlleles();
                    int altCount = alts.size();
                    for (int a = 0; a < altCount; a++) {
                        String alt = alts.get(a).getDisplayString();
                        if (alt != null && (alt.startsWith("<") || alt.contains("[") || alt.contains("]"))) {
                            continue;
                        }
                        String key = alleleKey(pos, ref, alt);
                        if (afByKey.containsKey(key)) {
                            continue;
                        }
                        if (!sampleWantsAllele(nodes, i, total, pos, key)) {
                            continue;
                        }
                        Double af = resolveAfFromContext(ctx, a, altCount, pops);
                        if (af != null) {
                            afByKey.put(key, af);
                            matched[0]++;
                        }
                    }
                }
                if (progress != null && scanned[0] % 10_000 == 0) {
                    progress.onProgress(
                        chromosome, progressCurrent(pos, spanStart, spanLen),
                        progressTotal(spanLen), matched[0]);
                }
            });
        }

        for (VariantNode node : nodes) {
            Double af = afByKey.get(alleleKey(node.position, node.ref, node.alt));
            if (af != null) {
                node.controlAf = af;
            }
        }
        if (progress != null) {
            progress.onProgress(chromosome, progressTotal(spanLen), progressTotal(spanLen), matched[0]);
        }
        list.setControlAfSourcePath(annotationFingerprint());
    }

    private static int progressTotal(long spanLen) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, spanLen));
    }

    private static int progressCurrent(long pos, long spanStart, long spanLen) {
        long cur = Math.max(0L, Math.min(spanLen, pos - spanStart + 1));
        return (int) Math.min(Integer.MAX_VALUE, cur);
    }

    /** POS field only — stops at second tab. Returns -1 on malformed lines. */
    static long parseVcfPos(String line) {
        if (line == null || line.isEmpty() || line.charAt(0) == '#') {
            return -1;
        }
        int t1 = line.indexOf('\t');
        if (t1 < 0) {
            return -1;
        }
        int t2 = line.indexOf('\t', t1 + 1);
        if (t2 < 0) {
            return -1;
        }
        try {
            return Long.parseLong(line, t1 + 1, t2, 10);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void matchRawLine(
            String line,
            long pos,
            List<VariantNode> nodes,
            int sampleAtPos,
            int total,
            Set<String> pops,
            Map<String, Double> afByKey,
            int[] matched) {
        // CHROM POS ID REF ALT QUAL FILTER INFO …
        int t1 = line.indexOf('\t');
        int t2 = line.indexOf('\t', t1 + 1);
        int t3 = line.indexOf('\t', t2 + 1); // end of ID
        int t4 = line.indexOf('\t', t3 + 1); // end of REF
        int t5 = line.indexOf('\t', t4 + 1); // end of ALT
        int t6 = line.indexOf('\t', t5 + 1); // end of QUAL
        int t7 = line.indexOf('\t', t6 + 1); // end of FILTER
        int t8 = line.indexOf('\t', t7 + 1); // end of INFO (or -1 if last)
        if (t4 < 0 || t5 < 0 || t7 < 0) {
            return;
        }
        String ref = line.substring(t3 + 1, t4);
        String altField = line.substring(t4 + 1, t5);
        if (ref.isEmpty() || altField.isEmpty()) {
            return;
        }
        int infoStart = t7 + 1;
        int infoEnd = t8 < 0 ? line.length() : t8;
        String info = line.substring(infoStart, infoEnd);

        String[] alts = altField.split(",", -1);
        int altCount = alts.length;
        for (int a = 0; a < altCount; a++) {
            String alt = alts[a];
            if (alt.isEmpty() || alt.charAt(0) == '<' || alt.indexOf('[') >= 0 || alt.indexOf(']') >= 0) {
                continue;
            }
            String key = alleleKey(pos, ref, alt);
            if (afByKey.containsKey(key)) {
                continue;
            }
            if (!sampleWantsAllele(nodes, sampleAtPos, total, pos, key)) {
                continue;
            }
            Double af = resolveAfFromInfoString(info, a, altCount, pops);
            if (af != null) {
                afByKey.put(key, af);
                matched[0]++;
            }
        }
    }

    private static boolean sampleWantsAllele(
            List<VariantNode> nodes, int sampleAtPos, int total, long pos, String key) {
        for (int j = sampleAtPos; j < total && nodes.get(j).position == pos; j++) {
            VariantNode sample = nodes.get(j);
            if (key.equals(alleleKey(sample.position, sample.ref, sample.alt))) {
                return true;
            }
        }
        return false;
    }

    /** Max AF among selected populations from a raw INFO column. */
    static Double resolveAfFromInfoString(
            String info, int altIndex, int altCount, Collection<String> populationIds) {
        if (info == null || info.isEmpty() || populationIds == null || populationIds.isEmpty()) {
            return null;
        }
        Double max = null;
        for (String raw : populationIds) {
            String pop = normalizePopulationId(raw);
            String afKey = pop.isEmpty() ? "AF" : "AF_" + pop;
            String acKey = pop.isEmpty() ? "AC" : "AC_" + pop;
            String anKey = pop.isEmpty() ? "AN" : "AN_" + pop;
            Double fromAf = infoNumber(info, afKey, altIndex);
            if (fromAf != null) {
                Double clamped = clampAf(fromAf);
                if (clamped != null) {
                    max = max == null ? clamped : Math.max(max, clamped);
                }
                continue;
            }
            Double ac = infoNumber(info, acKey, altIndex);
            Double an = infoNumber(info, anKey, 0);
            if (ac == null || an == null || an <= 0) {
                continue;
            }
            Double clamped = clampAf(ac / an);
            if (clamped != null) {
                max = max == null ? clamped : Math.max(max, clamped);
            }
        }
        return max;
    }

    /** Value for {@code key=} in INFO; comma-list picks {@code index}. */
    static Double infoNumber(String info, String key, int index) {
        if (info == null || key == null || key.isEmpty()) {
            return null;
        }
        int keyLen = key.length();
        int at = 0;
        while (at < info.length()) {
            int eq = info.indexOf('=', at);
            if (eq < 0) {
                return null;
            }
            int keyStart = at;
            if (eq - keyStart == keyLen
                    && info.regionMatches(true, keyStart, key, 0, keyLen)) {
                int valStart = eq + 1;
                int valEnd = info.indexOf(';', valStart);
                if (valEnd < 0) {
                    valEnd = info.length();
                }
                return parseInfoNumberToken(info, valStart, valEnd, index);
            }
            int next = info.indexOf(';', eq + 1);
            if (next < 0) {
                return null;
            }
            at = next + 1;
        }
        return null;
    }

    private static Double parseInfoNumberToken(String info, int start, int end, int index) {
        int tokenStart = start;
        int tokenIndex = 0;
        int firstEnd = -1;
        for (int i = start; i <= end; i++) {
            if (i == end || info.charAt(i) == ',') {
                if (tokenIndex == 0) {
                    firstEnd = i;
                }
                if (tokenIndex == index) {
                    return parseNumber(info.substring(tokenStart, i));
                }
                tokenIndex++;
                tokenStart = i + 1;
            }
        }
        // Past end of list → same as firstNumber: use first token.
        if (firstEnd >= start) {
            return parseNumber(info.substring(start, firstEnd));
        }
        return null;
    }

    private static long alleleEnd(VariantNode node) {
        int refLen = node.ref != null ? Math.max(1, node.ref.length()) : 1;
        return node.position + refLen - 1;
    }

    /** AF from selected pop INFO keys only — reads attributes in place (no INFO map copy). */
    private static Double resolveAfFromContext(
            VariantContext ctx, int altIndex, int altCount, Collection<String> populationIds) {
        if (ctx == null || populationIds == null || populationIds.isEmpty()) {
            return null;
        }
        Double max = null;
        for (String raw : populationIds) {
            Double af = resolveAfKeysFromContext(
                ctx, altIndex, altCount, normalizePopulationId(raw));
            if (af == null) {
                continue;
            }
            max = max == null ? af : Math.max(max, af);
        }
        return max;
    }

    private static Double resolveAfKeysFromContext(
            VariantContext ctx, int altIndex, int altCount, String populationId) {
        String afKey;
        String acKey;
        String anKey;
        if (populationId == null || populationId.isEmpty()) {
            afKey = "AF";
            acKey = "AC";
            anKey = "AN";
        } else {
            afKey = "AF_" + populationId;
            acKey = "AC_" + populationId;
            anKey = "AN_" + populationId;
        }
        Double fromAf = firstNumber(ctx.getAttribute(afKey), altIndex, altCount);
        if (fromAf != null) {
            return clampAf(fromAf);
        }
        Double ac = firstNumber(ctx.getAttribute(acKey), altIndex, altCount);
        Double an = firstNumber(ctx.getAttribute(anKey), 0, 1);
        if (ac == null || an == null || an <= 0) {
            return null;
        }
        return clampAf(ac / an);
    }

    static Double resolveAf(Map<String, Object> info, int altIndex, int altCount) {
        return resolveAf(info, altIndex, altCount, List.of(GLOBAL_POPULATION));
    }

    /**
     * AF for one population only — no fallback to other populations.
     * Empty {@code populationId} uses global {@code AF}/{@code AC}/{@code AN}.
     */
    static Double resolveAf(
            Map<String, Object> info, int altIndex, int altCount, String populationId) {
        return resolveAfForPopulation(info, altIndex, altCount, normalizePopulationId(populationId));
    }

    /**
     * Max AF among the given populations (selected fields only).
     */
    static Double resolveAf(
            Map<String, Object> info,
            int altIndex,
            int altCount,
            Collection<String> populationIds) {
        if (info == null || info.isEmpty() || populationIds == null || populationIds.isEmpty()) {
            return null;
        }
        Double max = null;
        for (String raw : populationIds) {
            Double af = resolveAfForPopulation(info, altIndex, altCount, normalizePopulationId(raw));
            if (af == null) {
                continue;
            }
            max = max == null ? af : Math.max(max, af);
        }
        return max;
    }

    private static Double resolveAfForPopulation(
            Map<String, Object> info, int altIndex, int altCount, String populationId) {
        if (info == null || info.isEmpty()) {
            return null;
        }
        if (populationId == null || populationId.isEmpty()) {
            return resolveAfKeys(info, altIndex, altCount, "AF", "AC", "AN");
        }
        return resolveAfKeys(
            info, altIndex, altCount, "AF_" + populationId, "AC_" + populationId, "AN_" + populationId);
    }

    private static Double resolveAfKeys(
            Map<String, Object> info,
            int altIndex,
            int altCount,
            String afKey,
            String acKey,
            String anKey) {
        Double fromAf = firstNumber(info.get(afKey), altIndex, altCount);
        if (fromAf != null) {
            return clampAf(fromAf);
        }
        Double ac = firstNumber(info.get(acKey), altIndex, altCount);
        Double an = firstNumber(info.get(anKey), 0, 1);
        if (ac == null || an == null || an <= 0) {
            return null;
        }
        return clampAf(ac / an);
    }

    private static Double clampAf(double af) {
        if (Double.isNaN(af) || af < 0) {
            return null;
        }
        return Math.min(1.0, af);
    }

    private static Double firstNumber(Object raw, int index, int altCount) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            if (list.isEmpty()) {
                return null;
            }
            int i = index < list.size() ? index : 0;
            return parseNumber(list.get(i));
        }
        if (raw instanceof Object[] arr) {
            if (arr.length == 0) {
                return null;
            }
            int i = index < arr.length ? index : 0;
            return parseNumber(arr[i]);
        }
        return parseNumber(raw);
    }

    private static Double parseNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        String s = value.toString().trim();
        if (s.isEmpty() || ".".equals(s)) {
            return null;
        }
        int comma = s.indexOf(',');
        if (comma >= 0) {
            s = s.substring(0, comma).trim();
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String alleleKey(long position, String ref, String alt) {
        String r = ref == null ? "" : ref.toUpperCase(Locale.ROOT);
        String a = alt == null ? "" : alt.toUpperCase(Locale.ROOT);
        return position + "|" + r + "|" + a;
    }

    public static String normalizePopulationId(String populationId) {
        if (populationId == null || populationId.isBlank()) {
            return GLOBAL_POPULATION;
        }
        String id = populationId.trim();
        if (id.equalsIgnoreCase("global") || id.equalsIgnoreCase("(global)")) {
            return GLOBAL_POPULATION;
        }
        // Accept "AF_fin" / "fin" / "FIN"
        Matcher m = POP_FIELD.matcher(id);
        if (m.matches() && m.group(2) != null) {
            return m.group(2);
        }
        if (id.regionMatches(true, 0, "AF_", 0, 3)
                || id.regionMatches(true, 0, "AC_", 0, 3)
                || id.regionMatches(true, 0, "AN_", 0, 3)) {
            return id.substring(3);
        }
        return id;
    }

    private static String populationLabel(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return "Global";
        }
        return suffix;
    }

    /** Field-hint tooltip for a population id (empty = global). */
    public static String populationFieldHint(String populationId) {
        String id = normalizePopulationId(populationId);
        if (id.isEmpty()) {
            return "AF / AC+AN";
        }
        return "AF_" + id + " / AC_" + id + " / AN_" + id;
    }

    /** One selectable population for the Control Files list. */
    public record PopulationOption(String id, String label) {
        @Override
        public String toString() {
            return label != null ? label : id;
        }
    }

    /** Per-population AN sample from a read batch. */
    public record PopulationAnStats(
        String populationId,
        double maxAn,
        long estimatedSamples,
        double percentOfFile) {

        public String summaryBesideLabel() {
            if (estimatedSamples <= 0 && maxAn <= 0) {
                return "—";
            }
            String samples = String.format(Locale.US, "%,d", estimatedSamples);
            String pct = String.format(Locale.US, "%.1f%%", percentOfFile);
            return samples + " samples · " + pct;
        }
    }

    /** Quick sample of control-file INFO coverage, keyed by population. */
    public record ControlFileStats(
        int rowsSampled,
        List<PopulationAnStats> byPopulation,
        boolean hasAfFields) {

        public static ControlFileStats empty() {
            return new ControlFileStats(0, List.of(), false);
        }

        public PopulationAnStats forPopulation(String populationId) {
            String id = normalizePopulationId(populationId);
            if (byPopulation == null) {
                return null;
            }
            for (PopulationAnStats s : byPopulation) {
                if (s != null && id.equals(normalizePopulationId(s.populationId()))) {
                    return s;
                }
            }
            return null;
        }
    }
}
