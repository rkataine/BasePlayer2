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
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;

/**
 * Annotates sample variants with population AF from a local control VCF (e.g. gnomAD sites).
 * One chromosome = one tabix span of raw VCF bytes, then a sorted merge-join on POS only;
 * REF/ALT/INFO are parsed only on position hits — no htsjdk.
 * Selected populations use {@code AF}/{@code AC}+{@code AN} (and {@code AF_fin}/…); when
 * several are selected, {@link VariantNode#controlAf} is the max among those fields.
 */
public final class ControlFrequencyAnnotator implements AutoCloseable {

    private static final Pattern POP_FIELD =
        Pattern.compile("^(AF|AC|AN)(?:_(.+))?$", Pattern.CASE_INSENSITIVE);

    private static final Pattern INFO_ID =
        Pattern.compile("##INFO=<ID=([^,>]+)", Pattern.CASE_INSENSITIVE);

    /** Optional progress: {@code chrom}, sample index, sample total, matches so far. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String chromosome, int sampleIndex, int sampleTotal, int matched);
    }

    /** Empty string = global AF/AC/AN. */
    public static final String GLOBAL_POPULATION = "";
    /** Persist token for {@link #GLOBAL_POPULATION} in project docs. */
    public static final String GLOBAL_POPULATION_TOKEN = "global";

    private Path path;
    private TabixBgzfScanner scanner;
    /** Population suffixes discovered from ##INFO (excludes global). Cached on open. */
    private List<String> headerPopulationSuffixes = List.of();

    /**
     * Selected population suffixes (empty string = global). Order preserved for UI;
     * fingerprint uses a sorted join.
     */
    private final LinkedHashSet<String> selectedPopulations = new LinkedHashSet<>();

    public ControlFrequencyAnnotator() {
        selectedPopulations.add(GLOBAL_POPULATION);
    }

    public boolean isOpen() {
        return scanner != null;
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
        Path tbi = TabixIndex.findSiblingIndex(vcfPath);
        if (tbi == null) {
            throw new IOException(
                "Control VCF must be bgzipped with a sibling .tbi index: " + vcfPath.getFileName());
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
        if (scanner == null || path == null || list == null) {
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
        for (String suffix : headerPopulationSuffixes) {
            out.add(new PopulationOption(suffix, populationLabel(suffix)));
        }
        return out;
    }

    /**
     * Sample the first {@code limit} variants and estimate diploid sample counts per
     * population ({@code max AN / 2}). Percentages are relative to global {@code AN}.
     */
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
            pops.add(GLOBAL_POPULATION);
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
                Double an = infoNumber(buf, infoStart, infoEnd, anKey, 0);
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
        return new ControlFileStats(rows[0], List.copyOf(byPopulation), sawAf[0] || sawAcAn[0]);
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
        if (scanner == null || list == null || path == null) {
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

        long spanStart = nodes.get(0).position;
        long spanEnd = alleleEnd(nodes.get(nodes.size() - 1));
        long spanLen = Math.max(1L, spanEnd - spanStart + 1);
        Set<String> pops = selectedPopulations();
        final int total = nodes.size();
        final int[] sampleIdx = { 0 };
        final int[] matched = { 0 };
        final int[] scanned = { 0 };
        final boolean[] stop = { false };

        if (progress != null) {
            progress.onProgress(chromosome, 0, progressTotal(spanLen), matched[0]);
        }

        scanner.visitRegion(chromosome, spanStart, spanEnd, (buf, off, len) -> {
            if (stop[0]) {
                return false;
            }
            scanned[0]++;
            long pos = parseVcfPos(buf, off, len);
            if (pos < 0) {
                return true;
            }
            if (pos > spanEnd) {
                stop[0] = true;
                return false;
            }

            int i = sampleIdx[0];
            while (i < total && nodes.get(i).position < pos) {
                i++;
            }
            sampleIdx[0] = i;
            if (i >= total) {
                stop[0] = true;
                return false;
            }
            if (nodes.get(i).position == pos) {
                matchRawLine(buf, off, len, pos, nodes, i, total, pops, matched);
            }
            if (progress != null && scanned[0] % 10_000 == 0) {
                progress.onProgress(
                    chromosome, progressCurrent(pos, spanStart, spanLen),
                    progressTotal(spanLen), matched[0]);
            }
            return true;
        });

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

    private static List<String> scanHeaderPopulationSuffixes(TabixBgzfScanner scanner)
            throws IOException {
        LinkedHashSet<String> suffixes = new LinkedHashSet<>();
        scanner.visitHeaderLines(
            (buf, off, len) -> {
                if (len < 10 || buf[off] != '#') {
                    return true;
                }
                // ##INFO=<ID=...
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

    /** POS field only — stops at second tab. Returns -1 on malformed lines. */
    static long parseVcfPos(String line) {
        if (line == null || line.isEmpty() || line.charAt(0) == '#') {
            return -1;
        }
        byte[] bytes = line.getBytes(StandardCharsets.US_ASCII);
        return parseVcfPos(bytes, 0, bytes.length);
    }

    /** POS field only from a raw VCF line slice. Returns -1 on malformed lines. */
    static long parseVcfPos(byte[] buf, int off, int len) {
        if (buf == null || len <= 0 || buf[off] == '#') {
            return -1;
        }
        int end = off + len;
        int t1 = indexOf(buf, off, end, (byte) '\t');
        if (t1 < 0) {
            return -1;
        }
        int t2 = indexOf(buf, t1 + 1, end, (byte) '\t');
        if (t2 < 0) {
            return -1;
        }
        return parseLongDigits(buf, t1 + 1, t2);
    }

    private static void matchRawLine(
            byte[] buf,
            int off,
            int len,
            long pos,
            List<VariantNode> nodes,
            int sampleAtPos,
            int total,
            Set<String> pops,
            int[] matched) {
        int end = off + len;
        // CHROM POS ID REF ALT QUAL FILTER INFO …
        int t1 = indexOf(buf, off, end, (byte) '\t');
        int t2 = t1 < 0 ? -1 : indexOf(buf, t1 + 1, end, (byte) '\t');
        int t3 = t2 < 0 ? -1 : indexOf(buf, t2 + 1, end, (byte) '\t'); // end of ID
        int t4 = t3 < 0 ? -1 : indexOf(buf, t3 + 1, end, (byte) '\t'); // end of REF
        int t5 = t4 < 0 ? -1 : indexOf(buf, t4 + 1, end, (byte) '\t'); // end of ALT
        int t6 = t5 < 0 ? -1 : indexOf(buf, t5 + 1, end, (byte) '\t'); // end of QUAL
        int t7 = t6 < 0 ? -1 : indexOf(buf, t6 + 1, end, (byte) '\t'); // end of FILTER
        int t8 = t7 < 0 ? -1 : indexOf(buf, t7 + 1, end, (byte) '\t'); // end of INFO
        if (t4 < 0 || t5 < 0 || t7 < 0) {
            return;
        }
        int refStart = t3 + 1;
        int refLen = t4 - refStart;
        int altStart = t4 + 1;
        int altEnd = t5;
        if (refLen <= 0 || altStart >= altEnd) {
            return;
        }
        int infoStart = t7 + 1;
        int infoEnd = t8 < 0 ? end : t8;

        // Count alts
        int altCount = 1;
        for (int i = altStart; i < altEnd; i++) {
            if (buf[i] == ',') {
                altCount++;
            }
        }

        int a = 0;
        int tokenStart = altStart;
        for (int i = altStart; i <= altEnd; i++) {
            if (i == altEnd || buf[i] == ',') {
                int tokenLen = i - tokenStart;
                if (tokenLen > 0 && !isSymbolicAlt(buf, tokenStart, tokenLen)) {
                    Double af = resolveAfFromInfoBytes(buf, infoStart, infoEnd, a, altCount, pops);
                    if (af != null) {
                        int written = writeAfToMatchingNodes(
                            nodes, sampleAtPos, total, pos, buf, refStart, refLen, tokenStart, tokenLen, af);
                        matched[0] += written;
                    }
                }
                a++;
                tokenStart = i + 1;
            }
        }
    }

    private static int writeAfToMatchingNodes(
            List<VariantNode> nodes,
            int sampleAtPos,
            int total,
            long pos,
            byte[] buf,
            int refStart,
            int refLen,
            int altStart,
            int altLen,
            double af) {
        int written = 0;
        for (int j = sampleAtPos; j < total && nodes.get(j).position == pos; j++) {
            VariantNode sample = nodes.get(j);
            if (!equalsIgnoreCaseAscii(sample.ref, buf, refStart, refLen)) {
                continue;
            }
            if (!equalsIgnoreCaseAscii(sample.alt, buf, altStart, altLen)) {
                continue;
            }
            if (sample.controlAf == null) {
                sample.controlAf = af;
                written++;
            } else {
                sample.controlAf = Math.max(sample.controlAf, af);
            }
        }
        return written;
    }

    private static boolean isSymbolicAlt(byte[] buf, int start, int len) {
        if (len <= 0) {
            return true;
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

    private static boolean equalsIgnoreCaseAscii(String s, byte[] buf, int off, int len) {
        if (s == null) {
            return len == 0;
        }
        if (s.length() != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            int b = buf[off + i] & 0xFF;
            if (c >= 'a' && c <= 'z') {
                c = (char) (c - 32);
            }
            if (b >= 'a' && b <= 'z') {
                b -= 32;
            }
            if (c != b) {
                return false;
            }
        }
        return true;
    }

    /** Max AF among selected populations from a raw INFO column (String API for tests). */
    static Double resolveAfFromInfoString(
            String info, int altIndex, int altCount, Collection<String> populationIds) {
        if (info == null || info.isEmpty() || populationIds == null || populationIds.isEmpty()) {
            return null;
        }
        byte[] bytes = info.getBytes(StandardCharsets.US_ASCII);
        return resolveAfFromInfoBytes(bytes, 0, bytes.length, altIndex, altCount, populationIds);
    }

    static Double resolveAfFromInfoBytes(
            byte[] buf,
            int infoStart,
            int infoEnd,
            int altIndex,
            int altCount,
            Collection<String> populationIds) {
        if (buf == null || infoStart >= infoEnd || populationIds == null || populationIds.isEmpty()) {
            return null;
        }
        Double max = null;
        for (String raw : populationIds) {
            String pop = normalizePopulationId(raw);
            String afKey = pop.isEmpty() ? "AF" : "AF_" + pop;
            String acKey = pop.isEmpty() ? "AC" : "AC_" + pop;
            String anKey = pop.isEmpty() ? "AN" : "AN_" + pop;
            Double fromAf = infoNumber(buf, infoStart, infoEnd, afKey, altIndex);
            if (fromAf != null) {
                Double clamped = clampAf(fromAf);
                if (clamped != null) {
                    max = max == null ? clamped : Math.max(max, clamped);
                }
                continue;
            }
            Double ac = infoNumber(buf, infoStart, infoEnd, acKey, altIndex);
            Double an = infoNumber(buf, infoStart, infoEnd, anKey, 0);
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
        byte[] bytes = info.getBytes(StandardCharsets.US_ASCII);
        return infoNumber(bytes, 0, bytes.length, key, index);
    }

    static Double infoNumber(byte[] buf, int start, int end, String key, int index) {
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
            int keyStart = at;
            if (eq - keyStart == keyLen && regionMatchesIgnoreCase(buf, keyStart, key, keyLen)) {
                int valStart = eq + 1;
                int valEnd = indexOf(buf, valStart, end, (byte) ';');
                if (valEnd < 0) {
                    valEnd = end;
                }
                return parseInfoNumberToken(buf, valStart, valEnd, index);
            }
            int next = indexOf(buf, eq + 1, end, (byte) ';');
            if (next < 0) {
                return null;
            }
            at = next + 1;
        }
        return null;
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
            // Flag fields without '='
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

    private static Double parseInfoNumberToken(byte[] buf, int start, int end, int index) {
        int tokenStart = start;
        int tokenIndex = 0;
        int firstEnd = -1;
        for (int i = start; i <= end; i++) {
            if (i == end || buf[i] == ',') {
                if (tokenIndex == 0) {
                    firstEnd = i;
                }
                if (tokenIndex == index) {
                    return parseNumberBytes(buf, tokenStart, i);
                }
                tokenIndex++;
                tokenStart = i + 1;
            }
        }
        if (firstEnd >= start) {
            return parseNumberBytes(buf, start, firstEnd);
        }
        return null;
    }

    private static long alleleEnd(VariantNode node) {
        int refLen = node.ref != null ? Math.max(1, node.ref.length()) : 1;
        return node.position + refLen - 1;
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

    private static Double parseNumberBytes(byte[] buf, int start, int end) {
        if (start >= end) {
            return null;
        }
        // trim
        while (start < end && buf[start] == ' ') {
            start++;
        }
        while (end > start && buf[end - 1] == ' ') {
            end--;
        }
        if (start >= end || (end - start == 1 && buf[start] == '.')) {
            return null;
        }
        try {
            return Double.parseDouble(new String(buf, start, end - start, StandardCharsets.US_ASCII));
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

    /** 0-based field start (field 0 = CHROM). Returns -1 if missing. */
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
