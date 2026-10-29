package org.baseplayer.variant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Single-sided TRA mates (mate only in INFO/ALT) must be discoverable on the mate
 * chrom via {@link MateBreakpointIndex}, without the primary chrom being cached.
 */
class MateBreakpointIndexTest {

    private final List<SampleTrack> addedTracks = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        registry.getSampleTracks().removeAll(addedTracks);
        addedTracks.clear();
    }

    @Test
    void queryReturnsSortedHitsInWindow() {
        SampleTrack track = registerTrack("mate-idx");
        VariantNode.SampleCall call = call(track);

        List<MateBreakpointIndex.IndexedHit> raw = List.of(
            indexed("2", hit(500, "1", 100, "N", "N]2:500]", call)),
            indexed("2", hit(100, "1", 50, "N", "N]2:100]", call)),
            indexed("2", hit(300, "1", 80, "N", "N]2:300]", call)),
            indexed("3", hit(200, "1", 90, "N", "N]3:200]", call)));

        MateBreakpointIndex index = MateBreakpointIndex.fromHits(raw);
        assertEquals(4, index.size());

        List<MateBreakpointIndex.Hit> on2 = index.query("2", 100, 300);
        assertEquals(2, on2.size());
        assertEquals(100, on2.get(0).matePos());
        assertEquals(300, on2.get(1).matePos());

        List<MateBreakpointIndex.Hit> chrPrefixed = index.query("chr2", 1, 10_000);
        assertEquals(3, chrPrefixed.size());

        assertTrue(index.query("X", 1, 1000).isEmpty());
    }

    @Test
    void materializeInsertsMateGlyphsIntoEmptyMateChromList() {
        SampleTrack track = registerTrack("mate-mat");
        VariantNode.SampleCall call = call(track);
        String alt = "N]2:250000]";
        MateBreakpointIndex index = MateBreakpointIndex.fromHits(List.of(
            indexed("2", hit(250_000, "1", 1_000_000, "N", alt, call))));

        VariantList mateList = new VariantList("2");
        VariantNode cursor = null;
        for (MateBreakpointIndex.Hit h : index.query("2", 1, Long.MAX_VALUE / 4)) {
            assertFalse(hasAllele(mateList, h.matePos(), h.ref(), h.alt()));
            for (VariantNode.SampleCall c : h.calls()) {
                cursor = mateList.addVariantWithCursor(
                    cursor, h.matePos(), h.ref(), h.alt(), h.type(), c);
                cursor.svEnd = h.matePos();
                cursor.svChr2 = h.primaryChrom();
                cursor.svEnd2 = h.primaryPos();
            }
        }

        assertEquals(1, mateList.size());
        VariantNode node = mateList.getFirst();
        assertEquals(250_000, node.position);
        assertEquals(VcfVariantType.SV_TRANSLOCATION, node.type);
        assertEquals("1", node.svChr2);
        assertEquals(1_000_000, node.svEnd2);
        assertEquals(1, node.getSamples().size());
    }

    @Test
    void buildIndexFromSingleSidedTraVcf(@TempDir Path dir) throws Exception {
        Path vcf = writeSingleSidedTraVcf(dir);

        SampleTrack track = registerTrack("TUMOR");
        try (VcfReader reader = new VcfReader(vcf)) {
            VariantLoader loader = new VariantLoader(reader);
            loader.mapAllEligibleToTrack(trackIndex(track));

            // No tabix → any inter-chrom break is treated as single-sided (load-all).
            assertEquals(
                VcfReader.InterChromBreakendStyle.SINGLE_SIDED,
                loader.detectInterChromBreakendStyle());

            MateBreakpointIndex index = loader.buildMateBreakpointIndex();
            assertFalse(index.isEmpty());
            List<MateBreakpointIndex.Hit> hits = index.query("2", 1, 1_000_000);
            assertEquals(1, hits.size());
            MateBreakpointIndex.Hit hit = hits.get(0);
            assertEquals(250_000, hit.matePos());
            assertEquals("1", hit.primaryChrom());
            assertEquals(1_000_000, hit.primaryPos());
            assertFalse(hit.calls().isEmpty());
            // DEL must not appear in the mate index.
            assertTrue(index.query("1", 1, 3_000_000).isEmpty());
        }
    }

    @Test
    void delOnlyVcfNeedsNoMateIndex(@TempDir Path dir) throws Exception {
        Path vcf = dir.resolve("del_only.vcf");
        Files.writeString(vcf, """
            ##fileformat=VCFv4.2
            ##INFO=<ID=SVTYPE,Number=1,Type=String,Description="Type of structural variant">
            ##INFO=<ID=END,Number=1,Type=Integer,Description="End">
            ##FORMAT=<ID=GT,Number=1,Type=String,Description="Genotype">
            ##contig=<ID=1,length=249250621>
            #CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tTUMOR
            1\t2000000\tdel1\tN\t<DEL>\t60\tPASS\tSVTYPE=DEL;END=2000100\tGT\t0/1
            """, StandardCharsets.UTF_8);
        try (VcfReader reader = new VcfReader(vcf)) {
            assertEquals(
                VcfReader.InterChromBreakendStyle.NONE,
                reader.detectInterChromBreakendStyle());
        }
    }

    private static Path writeSingleSidedTraVcf(Path dir) throws Exception {
        Path vcf = dir.resolve("single_sided_tra.vcf");
        Files.writeString(vcf, """
            ##fileformat=VCFv4.2
            ##INFO=<ID=SVTYPE,Number=1,Type=String,Description="Type of structural variant">
            ##INFO=<ID=CHR2,Number=1,Type=String,Description="Mate chromosome">
            ##INFO=<ID=END2,Number=1,Type=Integer,Description="Mate position">
            ##FORMAT=<ID=GT,Number=1,Type=String,Description="Genotype">
            ##FORMAT=<ID=GQ,Number=1,Type=Integer,Description="Genotype Quality">
            ##FORMAT=<ID=DP,Number=1,Type=Integer,Description="Read Depth">
            ##contig=<ID=1,length=249250621>
            ##contig=<ID=2,length=243199373>
            #CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tTUMOR
            1\t1000000\ttra1\tN\tN]2:250000]\t60\tPASS\tSVTYPE=BND;CHR2=2;END2=250000\tGT:GQ:DP\t0/1:40:30
            1\t2000000\tdel1\tN\t<DEL>\t60\tPASS\tSVTYPE=DEL;END=2000100;SVLEN=-100\tGT:GQ:DP\t0/1:40:30
            """, StandardCharsets.UTF_8);
        return vcf;
    }

    private static boolean hasAllele(VariantList list, long pos, String ref, String alt) {
        for (VariantNode node = list.getFirst(); node != null; node = node.next) {
            if (node.position == pos && ref.equals(node.ref) && alt.equals(node.alt)) {
                return true;
            }
        }
        return false;
    }

    private static MateBreakpointIndex.IndexedHit indexed(String mateChrom, MateBreakpointIndex.Hit hit) {
        return new MateBreakpointIndex.IndexedHit(mateChrom, hit);
    }

    private static MateBreakpointIndex.Hit hit(
            long matePos, String primaryChrom, long primaryPos,
            String ref, String alt, VariantNode.SampleCall call) {
        return new MateBreakpointIndex.Hit(
            matePos, primaryChrom, primaryPos, ref, alt,
            VcfVariantType.SV_TRANSLOCATION, null, List.of(call));
    }

    private SampleTrack registerTrack(String name) {
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        SampleTrack track = new SampleTrack(name);
        registry.getSampleTracks().add(track);
        registry.getSampleList().add(name);
        addedTracks.add(track);
        return track;
    }

    private static int trackIndex(SampleTrack track) {
        return ServiceRegistry.getInstance().getSampleRegistry().getSampleTracks().indexOf(track);
    }

    private static VariantNode.SampleCall call(SampleTrack track) {
        return new VariantNode.SampleCall(track, null, "0/1", 30, 20, 0.5);
    }
}
