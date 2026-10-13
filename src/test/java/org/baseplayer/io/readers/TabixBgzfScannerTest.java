package org.baseplayer.io.readers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class TabixBgzfScannerTest {

    @Test
    void loadsTbiAndQueriesRegionLines() throws Exception {
        Path vcf = fixtureVcf();
        Path tbi = TabixIndex.findSiblingIndex(vcf);
        assertNotNull(tbi);

        TabixIndex index = new TabixIndex(tbi);
        assertTrue(index.findSequenceIndex("1") >= 0 || index.findSequenceIndex("chr1") >= 0);

        try (TabixBgzfScanner scanner = new TabixBgzfScanner(vcf, index)) {
            List<String> lines = new ArrayList<>();
            scanner.visitRegion("1", 100, 200, (buf, off, len) -> {
                lines.add(new String(buf, off, len, StandardCharsets.US_ASCII));
                return true;
            });
            assertFalse(lines.isEmpty());
            assertTrue(lines.stream().anyMatch(l -> l.contains("\t100\t")));
            assertTrue(lines.stream().anyMatch(l -> l.contains("\t200\t")));
            assertFalse(lines.stream().anyMatch(l -> l.contains("\t300\t")));
        }
    }

    @Test
    void visitFirstBodyLinesSkipsHeader() throws Exception {
        Path vcf = fixtureVcf();
        TabixIndex index = new TabixIndex(TabixIndex.findSiblingIndex(vcf));
        try (TabixBgzfScanner scanner = new TabixBgzfScanner(vcf, index)) {
            List<String> lines = new ArrayList<>();
            scanner.visitFirstBodyLines(2, (buf, off, len) -> {
                lines.add(new String(buf, off, len, StandardCharsets.US_ASCII));
                return true;
            });
            assertEquals(2, lines.size());
            assertTrue(lines.get(0).startsWith("1\t100\t"));
            assertTrue(lines.get(1).startsWith("1\t200\t"));
        }
    }

    private static Path fixtureVcf() throws Exception {
        URL url = TabixBgzfScannerTest.class.getResource("/control/control.vcf.gz");
        assertNotNull(url, "test fixture /control/control.vcf.gz missing");
        return Path.of(url.toURI());
    }
}
