package org.baseplayer.io.readers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.utils.ChromosomeNames;

/**
 * Parses a BGZF-compressed Tabix ({@code .tbi}) index and returns merged virtual-offset
 * chunks overlapping a genomic region. Binning / linear-index logic matches the
 * <a href="https://samtools.github.io/hts-specs/tabix.pdf">Tabix specification</a>
 * (same scheme as BAM BAI).
 */
public final class TabixIndex {

    public static final class Chunk {
        public final long start;
        public final long end;

        public Chunk(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class RefIndex {
        final Map<Integer, List<Chunk>> bins;
        final long[] linearIndex;

        RefIndex(Map<Integer, List<Chunk>> bins, long[] linearIndex) {
            this.bins = bins;
            this.linearIndex = linearIndex;
        }
    }

    private final String[] sequenceNames;
    private final String chromPrefix;
    private final RefIndex[] references;
    private final int format;
    /** True when coordinates in the data file are 0-based half-open (BED rule). */
    private final boolean zeroBased;

    public TabixIndex(Path tbiPath) throws IOException {
        try (BGZFInputStream in = new BGZFInputStream(tbiPath)) {
            in.seek(0);

            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'T' || magic[1] != 'B' || magic[2] != 'I' || magic[3] != 1) {
                throw new IOException("Not a valid TBI file: " + tbiPath);
            }

            int nRef = in.readInt();
            this.format = in.readInt();
            in.readInt(); // col_seq
            in.readInt(); // col_beg
            in.readInt(); // col_end
            in.readInt(); // meta
            in.readInt(); // skip
            this.zeroBased = (format & 0x10000) != 0;

            int lNm = in.readInt();
            if (lNm < 0) {
                throw new IOException("Invalid TBI name block length: " + lNm);
            }
            byte[] nameBytes = new byte[lNm];
            in.readFully(nameBytes);
            this.sequenceNames = splitNullTerminatedNames(nameBytes);
            this.chromPrefix = ChromosomeNames.detectPrefix(sequenceNames);

            this.references = new RefIndex[nRef];
            for (int i = 0; i < nRef; i++) {
                int nBin = in.readInt();
                Map<Integer, List<Chunk>> bins = new HashMap<>(Math.max(16, nBin));
                for (int b = 0; b < nBin; b++) {
                    int bin = in.readInt(); // uint32 fits int for tabix bins
                    int nChunk = in.readInt();
                    List<Chunk> chunks = new ArrayList<>(nChunk);
                    for (int c = 0; c < nChunk; c++) {
                        long beg = in.readLong();
                        long end = in.readLong();
                        chunks.add(new Chunk(beg, end));
                    }
                    bins.put(bin, chunks);
                }
                int nIntv = in.readInt();
                long[] linear = new long[nIntv];
                for (int li = 0; li < nIntv; li++) {
                    linear[li] = in.readLong();
                }
                references[i] = new RefIndex(bins, linear);
            }
            // optional n_no_coor ignored
        }
    }

    public String[] sequenceNames() {
        return sequenceNames.clone();
    }

    public String chromPrefix() {
        return chromPrefix;
    }

    public int format() {
        return format;
    }

    /**
     * Resolve a chromosome name (with or without {@code chr}) to the reference index,
     * or {@code -1} if absent.
     */
    public int findSequenceIndex(String chromosome) {
        if (chromosome == null || chromosome.isBlank()) {
            return -1;
        }
        String want = ChromosomeNames.forData(chromosome, chromPrefix);
        for (int i = 0; i < sequenceNames.length; i++) {
            if (ChromosomeNames.equals(sequenceNames[i], want)
                    || ChromosomeNames.equals(sequenceNames[i], chromosome)) {
                return i;
            }
        }
        // Contig list may use the opposite prefix — try bare / both forms.
        String bare = ChromosomeNames.strip(chromosome);
        for (int i = 0; i < sequenceNames.length; i++) {
            if (ChromosomeNames.equals(sequenceNames[i], bare)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Contig name as stored in the index for {@code chromosome}, or {@code null}.
     */
    public String resolveSequenceName(String chromosome) {
        int idx = findSequenceIndex(chromosome);
        return idx < 0 ? null : sequenceNames[idx];
    }

    /**
     * Chunks that may contain records overlapping 1-based inclusive {@code [start, end]}
     * (VCF coordinates). Internally converts to the 0-based half-open interval used by
     * the binning scheme.
     */
    public List<Chunk> query(String chromosome, long start1Based, long end1Based) {
        int refId = findSequenceIndex(chromosome);
        if (refId < 0) {
            return Collections.emptyList();
        }
        long beg;
        long end;
        if (zeroBased) {
            // Data is already 0-based half-open; callers still pass VCF-style 1-based.
            beg = Math.max(0L, start1Based - 1);
            end = Math.max(beg, end1Based);
        } else {
            // GFF/VCF rule: convert 1-based closed → 0-based half-open for bins.
            beg = Math.max(0L, start1Based - 1);
            end = Math.max(beg + 1, end1Based);
        }
        if (beg > Integer.MAX_VALUE || end > Integer.MAX_VALUE) {
            beg = Math.min(beg, Integer.MAX_VALUE);
            end = Math.min(end, Integer.MAX_VALUE);
        }
        return getChunks(refId, (int) beg, (int) end);
    }

    private List<Chunk> getChunks(int refId, int start, int end) {
        if (refId < 0 || refId >= references.length) {
            return Collections.emptyList();
        }
        RefIndex ref = references[refId];
        if (ref == null || start >= end) {
            return Collections.emptyList();
        }

        int[] bins = reg2bins(start, end);
        List<Chunk> chunks = new ArrayList<>();
        for (int bin : bins) {
            List<Chunk> binChunks = ref.bins.get(bin);
            if (binChunks != null) {
                chunks.addAll(binChunks);
            }
        }
        if (chunks.isEmpty()) {
            return chunks;
        }

        long minOffset = 0;
        int linearIndexPos = start / 16384;
        if (ref.linearIndex.length > 0 && linearIndexPos < ref.linearIndex.length) {
            minOffset = ref.linearIndex[linearIndexPos];
        }

        List<Chunk> filtered = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            if (chunk.end > minOffset) {
                long adjustedStart = Math.max(chunk.start, minOffset);
                filtered.add(new Chunk(adjustedStart, chunk.end));
            }
        }
        if (filtered.isEmpty()) {
            return filtered;
        }

        filtered.sort((a, b) -> Long.compare(a.start, b.start));
        if (filtered.size() == 1) {
            return filtered;
        }

        List<Chunk> merged = new ArrayList<>();
        Chunk current = filtered.get(0);
        for (int i = 1; i < filtered.size(); i++) {
            Chunk next = filtered.get(i);
            if (next.start <= current.end) {
                current = new Chunk(current.start, Math.max(current.end, next.end));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    /** Same bin list as BAI / Tabix {@code reg2bins} for {@code [beg, end)}. */
    static int[] reg2bins(int beg, int end) {
        List<Integer> bins = new ArrayList<>(64);
        int e = end - 1;
        bins.add(0);
        for (int k = 1 + (beg >> 26); k <= 1 + (e >> 26); k++) {
            bins.add(k);
        }
        for (int k = 9 + (beg >> 23); k <= 9 + (e >> 23); k++) {
            bins.add(k);
        }
        for (int k = 73 + (beg >> 20); k <= 73 + (e >> 20); k++) {
            bins.add(k);
        }
        for (int k = 585 + (beg >> 17); k <= 585 + (e >> 17); k++) {
            bins.add(k);
        }
        for (int k = 4681 + (beg >> 14); k <= 4681 + (e >> 14); k++) {
            bins.add(k);
        }
        int[] out = new int[bins.size()];
        for (int i = 0; i < bins.size(); i++) {
            out[i] = bins.get(i);
        }
        return out;
    }

    private static String[] splitNullTerminatedNames(byte[] nameBytes) {
        List<String> names = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < nameBytes.length; i++) {
            if (nameBytes[i] == 0) {
                if (i > start) {
                    names.add(new String(nameBytes, start, i - start, StandardCharsets.US_ASCII));
                }
                start = i + 1;
            }
        }
        if (start < nameBytes.length) {
            names.add(new String(nameBytes, start, nameBytes.length - start, StandardCharsets.US_ASCII));
        }
        return names.toArray(String[]::new);
    }

    /** Sibling {@code path.tbi} or {@code pathWithoutGz.tbi}. */
    public static Path findSiblingIndex(Path dataPath) {
        if (dataPath == null) {
            return null;
        }
        Path direct = dataPath.resolveSibling(dataPath.getFileName().toString() + ".tbi");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        String name = dataPath.getFileName().toString();
        if (name.endsWith(".gz") && name.length() > 3) {
            Path alt = dataPath.resolveSibling(name.substring(0, name.length() - 3) + ".tbi");
            if (Files.isRegularFile(alt)) {
                return alt;
            }
        }
        return null;
    }
}
