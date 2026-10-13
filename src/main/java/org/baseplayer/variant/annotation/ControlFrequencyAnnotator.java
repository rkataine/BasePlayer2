package org.baseplayer.variant.annotation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfHeaderFieldDef;
import org.baseplayer.variant.VcfSnvIndel;

import htsjdk.variant.variantcontext.VariantContext;

/**
 * Annotates sample variants with population AF from a local control VCF (e.g. gnomAD sites).
 * Uses merged tabix interval queries over sample positions to limit I/O.
 * Optional population suffix selects {@code AF_fin} / {@code AC_fin}+{@code AN_fin} style fields.
 */
public final class ControlFrequencyAnnotator implements AutoCloseable {

    /** Merge consecutive sample loci when the gap is at most this many bp. */
    private static final long MERGE_GAP_BP = 10_000L;
    /** Cap a single tabix query span even when merge would continue. */
    private static final long MAX_QUERY_SPAN_BP = 1_000_000L;

    private static final Pattern POP_FIELD =
        Pattern.compile("^(AF|AC|AN)(?:_(.+))?$", Pattern.CASE_INSENSITIVE);

    /** Empty string = global AF/AC/AN. */
    public static final String GLOBAL_POPULATION = "";

    private VcfReader reader;
    private Path path;
    /** Population suffix without prefix, e.g. {@code fin}; empty for global. */
    private String populationId = GLOBAL_POPULATION;

    public boolean isOpen() {
        return reader != null;
    }

    public Path path() {
        return path;
    }

    public String populationId() {
        return populationId != null ? populationId : GLOBAL_POPULATION;
    }

    /**
     * Select population INFO suffix (e.g. {@code fin} for {@code AF_fin}).
     * Empty / null = global {@code AF}/{@code AC}/{@code AN}. Clears annotation fingerprint
     * so lists re-annotate.
     */
    public synchronized void setPopulationId(String populationId) {
        String next = normalizePopulationId(populationId);
        if (Objects.equals(this.populationId, next)) {
            return;
        }
        this.populationId = next;
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
        return path.toAbsolutePath() + "|pop=" + populationId();
    }

    /**
     * Populations available from ##INFO (global + suffixes like {@code fin} from {@code AN_fin}).
     * First entry is always global.
     */
    public synchronized List<PopulationOption> listPopulations() {
        List<PopulationOption> out = new ArrayList<>();
        out.add(new PopulationOption(GLOBAL_POPULATION, "Global (AF / AC+AN)"));
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
     * Sample the first {@code limit} variants and report max AN (for the active population)
     * plus an estimated diploid sample count ({@code maxAn / 2}).
     */
    public synchronized ControlFileStats sampleStats(int limit) throws IOException {
        if (reader == null) {
            return ControlFileStats.empty();
        }
        int capped = Math.max(1, limit);
        String anKey = populationId().isEmpty() ? "AN" : "AN_" + populationId();
        String afKey = populationId().isEmpty() ? "AF" : "AF_" + populationId();
        String acKey = populationId().isEmpty() ? "AC" : "AC_" + populationId();

        int rows = 0;
        double maxAn = 0;
        boolean sawAf = false;
        boolean sawAcAn = false;
        try (var it = reader.rawIterator()) {
            while (it.hasNext() && rows < capped) {
                VariantContext ctx = it.next();
                rows++;
                if (ctx == null) {
                    continue;
                }
                Double an = firstNumber(ctx.getAttribute(anKey), 0, 1);
                if (an != null && an > maxAn) {
                    maxAn = an;
                }
                if (ctx.getAttribute(afKey) != null) {
                    sawAf = true;
                }
                if (ctx.getAttribute(acKey) != null && ctx.getAttribute(anKey) != null) {
                    sawAcAn = true;
                }
            }
        }
        long estimatedSamples = maxAn > 0 ? Math.round(maxAn / 2.0) : 0;
        return new ControlFileStats(rows, maxAn, estimatedSamples, anKey, sawAf || sawAcAn);
    }

    /**
     * Annotate all nodes in {@code list} for its chromosome using the open control VCF.
     * No-op when no reader is open, the list is empty, or already annotated from this file.
     */
    public synchronized void annotate(VariantList list) throws IOException {
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
        Set<String> needed = new HashSet<>();
        for (VariantNode node = list.getFirst(); node != null; node = node.next) {
            node.controlAf = null;
            nodes.add(node);
            needed.add(alleleKey(node.position, node.ref, node.alt));
        }
        if (nodes.isEmpty()) {
            list.setControlAfSourcePath(annotationFingerprint());
            return;
        }

        String pop = populationId();
        List<long[]> intervals = mergeIntervals(nodes);
        Map<String, Double> afByKey = new HashMap<>();
        for (long[] interval : intervals) {
            reader.visitSnvsAndIndels(chromosome, interval[0], interval[1], control -> {
                putMatchingAlleles(control, needed, afByKey, pop);
            });
        }

        for (VariantNode node : nodes) {
            Double af = afByKey.get(alleleKey(node.position, node.ref, node.alt));
            if (af != null) {
                node.controlAf = af;
            }
        }
        list.setControlAfSourcePath(annotationFingerprint());
    }

    private static List<long[]> mergeIntervals(List<VariantNode> nodes) {
        List<long[]> intervals = new ArrayList<>();
        long start = nodes.get(0).position;
        long end = alleleEnd(nodes.get(0));
        for (int i = 1; i < nodes.size(); i++) {
            VariantNode node = nodes.get(i);
            long nodeStart = node.position;
            long nodeEnd = alleleEnd(node);
            boolean contiguous = nodeStart - end <= MERGE_GAP_BP;
            boolean withinCap = nodeEnd - start <= MAX_QUERY_SPAN_BP;
            if (contiguous && withinCap) {
                if (nodeEnd > end) {
                    end = nodeEnd;
                }
            } else {
                intervals.add(new long[] { start, end });
                start = nodeStart;
                end = nodeEnd;
            }
        }
        intervals.add(new long[] { start, end });
        return intervals;
    }

    private static long alleleEnd(VariantNode node) {
        int refLen = node.ref != null ? Math.max(1, node.ref.length()) : 1;
        return node.position + refLen - 1;
    }

    private static void putMatchingAlleles(
            VcfSnvIndel control,
            Set<String> needed,
            Map<String, Double> afByKey,
            String populationId) {
        if (control == null || control.getAlt() == null) {
            return;
        }
        List<String> alts = control.getAlt();
        for (int i = 0; i < alts.size(); i++) {
            String alt = alts.get(i);
            String key = alleleKey(control.getPosition(), control.getRef(), alt);
            if (!needed.contains(key) || afByKey.containsKey(key)) {
                continue;
            }
            Double af = resolveAf(control.getInfo(), i, alts.size(), populationId);
            if (af != null) {
                afByKey.put(key, af);
            }
        }
    }

    static Double resolveAf(Map<String, Object> info, int altIndex, int altCount) {
        return resolveAf(info, altIndex, altCount, GLOBAL_POPULATION);
    }

    static Double resolveAf(
            Map<String, Object> info, int altIndex, int altCount, String populationId) {
        if (info == null || info.isEmpty()) {
            return null;
        }
        String pop = normalizePopulationId(populationId);
        if (!pop.isEmpty()) {
            Double fromPop = resolveAfKeys(info, altIndex, altCount, "AF_" + pop, "AC_" + pop, "AN_" + pop);
            if (fromPop != null) {
                return fromPop;
            }
        }
        return resolveAfKeys(info, altIndex, altCount, "AF", "AC", "AN");
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
            return "Global (AF / AC+AN)";
        }
        return suffix + " (AF_" + suffix + " / AC_" + suffix + ")";
    }

    /** One selectable population for the Control Files combo. */
    public record PopulationOption(String id, String label) {
        @Override
        public String toString() {
            return label != null ? label : id;
        }
    }

    /** Quick sample of control-file INFO coverage. */
    public record ControlFileStats(
        int rowsSampled,
        double maxAn,
        long estimatedSamples,
        String anKey,
        boolean hasAfFields) {

        public static ControlFileStats empty() {
            return new ControlFileStats(0, 0, 0, "AN", false);
        }

        public String summaryText() {
            if (rowsSampled <= 0) {
                return "No rows sampled.";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Sampled ").append(rowsSampled).append(" rows");
            if (maxAn > 0) {
                sb.append(" · max ").append(anKey).append('=')
                    .append(maxAn == Math.rint(maxAn) ? String.valueOf((long) maxAn) : maxAn);
                if (estimatedSamples > 0) {
                    sb.append(" (~").append(estimatedSamples).append(" samples)");
                }
            } else {
                sb.append(" · no ").append(anKey).append(" seen");
            }
            if (!hasAfFields) {
                sb.append(" · AF/AC fields not detected in sample");
            }
            return sb.toString();
        }
    }
}
