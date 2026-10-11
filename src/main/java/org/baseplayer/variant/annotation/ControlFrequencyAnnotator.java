package org.baseplayer.variant.annotation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfSnvIndel;

/**
 * Annotates sample variants with population AF from a local control VCF (e.g. gnomAD sites).
 * Uses merged tabix interval queries over sample positions to limit I/O.
 */
public final class ControlFrequencyAnnotator implements AutoCloseable {

    /** Merge consecutive sample loci when the gap is at most this many bp. */
    private static final long MERGE_GAP_BP = 10_000L;
    /** Cap a single tabix query span even when merge would continue. */
    private static final long MAX_QUERY_SPAN_BP = 1_000_000L;

    private VcfReader reader;
    private Path path;

    public boolean isOpen() {
        return reader != null;
    }

    public Path path() {
        return path;
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

    /** True when this list still needs control AF annotation from the open file. */
    public synchronized boolean needsAnnotation(VariantList list) {
        if (reader == null || path == null || list == null) {
            return false;
        }
        String annotatedFrom = list.getControlAfSourcePath();
        return annotatedFrom == null || !path.toAbsolutePath().toString().equals(annotatedFrom);
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
            list.setControlAfSourcePath(path.toAbsolutePath().toString());
            return;
        }

        List<long[]> intervals = mergeIntervals(nodes);
        Map<String, Double> afByKey = new HashMap<>();
        for (long[] interval : intervals) {
            reader.visitSnvsAndIndels(chromosome, interval[0], interval[1], control -> {
                putMatchingAlleles(control, needed, afByKey);
            });
        }

        for (VariantNode node : nodes) {
            Double af = afByKey.get(alleleKey(node.position, node.ref, node.alt));
            if (af != null) {
                node.controlAf = af;
            }
        }
        list.setControlAfSourcePath(path.toAbsolutePath().toString());
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
            VcfSnvIndel control, Set<String> needed, Map<String, Double> afByKey) {
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
            Double af = resolveAf(control.getInfo(), i, alts.size());
            if (af != null) {
                afByKey.put(key, af);
            }
        }
    }

    static Double resolveAf(Map<String, Object> info, int altIndex, int altCount) {
        if (info == null || info.isEmpty()) {
            return null;
        }
        Double fromAf = firstNumber(info.get("AF"), altIndex, altCount);
        if (fromAf != null) {
            return clampAf(fromAf);
        }
        Double ac = firstNumber(info.get("AC"), altIndex, altCount);
        Double an = firstNumber(info.get("AN"), 0, 1);
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
        // Single value: apply to all alts when altCount > 1 only for AN-like scalars.
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
        // INFO may be "3,1" when not already split by htsjdk.
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
}
