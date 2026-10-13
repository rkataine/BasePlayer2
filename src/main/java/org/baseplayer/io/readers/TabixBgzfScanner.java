package org.baseplayer.io.readers;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * Streams raw tab-delimited lines from a bgzip + {@code .tbi} file without htsjdk.
 * Region queries seek via {@link TabixIndex} chunks; header / prefix body scans start
 * at virtual offset 0.
 *
 * <p>Line callbacks receive a reusable buffer slice {@code (buf, off, len)} — the
 * contents are only valid until the next line is read.
 */
public final class TabixBgzfScanner implements Closeable {

    @FunctionalInterface
    public interface LineConsumer {
        /**
         * @return {@code false} to stop iteration early
         */
        boolean accept(byte[] buf, int off, int len) throws IOException;
    }

    private final Path vcfPath;
    private final TabixIndex index;
    private final BGZFInputStream bgzf;
    private byte[] lineBuf = new byte[8192];

    public TabixBgzfScanner(Path vcfPath, TabixIndex index) throws IOException {
        if (vcfPath == null) {
            throw new IOException("VCF path is null");
        }
        if (index == null) {
            throw new IOException("Tabix index is null");
        }
        this.vcfPath = vcfPath;
        this.index = index;
        this.bgzf = new BGZFInputStream(vcfPath);
    }

    public Path path() {
        return vcfPath;
    }

    public TabixIndex index() {
        return index;
    }

    /**
     * Visit body lines overlapping 1-based inclusive {@code [start, end]} on
     * {@code chromosome}. Stops early when {@code consumer} returns false, or when
     * a line's POS exceeds {@code end1}. Lines before the query start (chunk
     * overlap) are still delivered — the caller filters those by POS.
     */
    public void visitRegion(String chromosome, long start1, long end1, LineConsumer consumer)
            throws IOException {
        if (consumer == null) {
            return;
        }
        List<TabixIndex.Chunk> chunks = index.query(chromosome, start1, end1);
        if (chunks.isEmpty()) {
            return;
        }
        long prevVirtualEnd = -1L;
        for (TabixIndex.Chunk chunk : chunks) {
            long seekTo = chunk.start;
            if (prevVirtualEnd >= 0 && seekTo < prevVirtualEnd) {
                // Overlapping / adjacent chunks already covered.
                seekTo = prevVirtualEnd;
            }
            if (seekTo >= chunk.end) {
                prevVirtualEnd = Math.max(prevVirtualEnd, chunk.end);
                continue;
            }
            bgzf.seek(seekTo);
            while (bgzf.getVirtualOffset() < chunk.end) {
                int len = readLine();
                if (len < 0) {
                    break;
                }
                if (len == 0) {
                    continue;
                }
                if (lineBuf[0] == '#') {
                    continue;
                }
                long pos = parsePos(lineBuf, 0, len);
                if (pos > end1) {
                    return;
                }
                if (pos >= 0 && pos < start1) {
                    continue;
                }
                if (!consumer.accept(lineBuf, 0, len)) {
                    return;
                }
            }
            prevVirtualEnd = Math.max(prevVirtualEnd, chunk.end);
        }
    }

    /** POS = second tab field; {@code -1} if missing/malformed. */
    static long parsePos(byte[] buf, int off, int len) {
        int end = off + len;
        int t1 = -1;
        for (int i = off; i < end; i++) {
            if (buf[i] == '\t') {
                t1 = i;
                break;
            }
        }
        if (t1 < 0) {
            return -1;
        }
        long value = 0;
        boolean any = false;
        for (int i = t1 + 1; i < end; i++) {
            byte b = buf[i];
            if (b == '\t') {
                return any ? value : -1;
            }
            int d = b - '0';
            if (d < 0 || d > 9) {
                return -1;
            }
            value = value * 10 + d;
            any = true;
        }
        return any ? value : -1;
    }

    /**
     * Read meta / header lines from the start of the file until {@code stopAfter}
     * returns true for a line (that line is still delivered). Typically stop on
     * the {@code #CHROM} column header.
     */
    public void visitHeaderLines(LineConsumer consumer, Predicate<String> stopAfterAscii)
            throws IOException {
        if (consumer == null) {
            return;
        }
        bgzf.seek(0);
        while (true) {
            int len = readLine();
            if (len < 0) {
                return;
            }
            if (!consumer.accept(lineBuf, 0, len)) {
                return;
            }
            if (stopAfterAscii != null) {
                String s = new String(lineBuf, 0, len, java.nio.charset.StandardCharsets.US_ASCII);
                if (stopAfterAscii.test(s)) {
                    return;
                }
            }
        }
    }

    /**
     * Skip the VCF header, then deliver up to {@code limit} body lines from the
     * start of the file (file order, not tabix-region order).
     */
    public void visitFirstBodyLines(int limit, LineConsumer consumer) throws IOException {
        if (consumer == null || limit <= 0) {
            return;
        }
        bgzf.seek(0);
        // Skip header
        while (true) {
            int len = readLine();
            if (len < 0) {
                return;
            }
            if (len == 0) {
                continue;
            }
            if (lineBuf[0] != '#') {
                // First body line
                if (!consumer.accept(lineBuf, 0, len)) {
                    return;
                }
                limit--;
                break;
            }
            // #CHROM is last header line — next iteration is body
            if (len >= 6
                    && lineBuf[0] == '#'
                    && lineBuf[1] == 'C'
                    && lineBuf[2] == 'H'
                    && lineBuf[3] == 'R'
                    && lineBuf[4] == 'O'
                    && lineBuf[5] == 'M') {
                break;
            }
        }
        while (limit > 0) {
            int len = readLine();
            if (len < 0) {
                return;
            }
            if (len == 0 || lineBuf[0] == '#') {
                continue;
            }
            if (!consumer.accept(lineBuf, 0, len)) {
                return;
            }
            limit--;
        }
    }

    /**
     * Read one newline-delimited line into {@link #lineBuf}. Strips {@code \r}.
     * @return length, or {@code -1} at EOF
     */
    private int readLine() throws IOException {
        int len = 0;
        while (true) {
            int b = bgzf.read();
            if (b < 0) {
                return len == 0 ? -1 : len;
            }
            if (b == '\n') {
                return len;
            }
            if (b == '\r') {
                continue;
            }
            if (len >= lineBuf.length) {
                byte[] grown = new byte[lineBuf.length * 2];
                System.arraycopy(lineBuf, 0, grown, 0, len);
                lineBuf = grown;
            }
            lineBuf[len++] = (byte) b;
        }
    }

    @Override
    public void close() throws IOException {
        bgzf.close();
    }
}
