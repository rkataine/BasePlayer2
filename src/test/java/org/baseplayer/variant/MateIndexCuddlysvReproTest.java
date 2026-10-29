package org.baseplayer.variant;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.baseplayer.io.VcfManager;
import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Repro against a real Myoma cuddlysv VCF: mate index must be built only after
 * sample→track mapping, otherwise every BND is softFill-dropped (hits=0).
 */
class MateIndexCuddlysvReproTest {

    private static final Path VCF = Path.of(
        "/home/rkataine/work/genomic_data/samples/VCF/Myoma_SV/My_6223T1_15_0916.cuddlysv.somatic.filtered.vcf.gz");

    private SampleTrack track;
    private VcfManager.VcfData registered;
    private VcfReader openReader;
    private VariantLoader openLoader;

    static boolean vcfExists() {
        return java.nio.file.Files.exists(VCF);
    }

    @AfterEach
    void cleanup() {
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        if (track != null) {
            registry.getSampleTracks().remove(track);
            registry.getSampleList().remove(track.getName());
            track = null;
        }
        if (registered != null) {
            VcfManager.getInstance().unregisterLoadedVcf(registered);
            registered = null;
        }
        if (openLoader != null) {
            openLoader.setVcfReader(null);
            openLoader = null;
        }
        if (openReader != null) {
            try {
                openReader.close();
            } catch (Exception ignored) {
            }
            openReader = null;
        }
    }

    @Test
    @EnabledIf("vcfExists")
    void mappedBuildYieldsHitsAndMateQuery() throws Exception {
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        track = new SampleTrack("repro-tumor");
        registry.getSampleTracks().add(track);
        registry.getSampleList().add(track.getName());
        int trackIndex = registry.getSampleTracks().indexOf(track);

        try (VcfReader reader = new VcfReader(VCF)) {
            assertTrue(reader.canTabixQuery());
            VariantLoader loader = new VariantLoader(reader);
            loader.mapAllEligibleToTrack(trackIndex);
            assertFalse(loader.getSampleMapping().isEmpty(), "mapping empty");

            assertEquals(
                VcfReader.InterChromBreakendStyle.SINGLE_SIDED,
                loader.detectInterChromBreakendStyle());

            MateBreakpointIndex index = loader.buildMateBreakpointIndex();
            assertTrue(index.size() > 0, "expected hits after mapped build, got " + index.size());

            boolean anyMateChrom = false;
            for (String chrom : List.of("1", "2", "12", "14", "X")) {
                if (!index.query(chrom, 1, Long.MAX_VALUE / 4).isEmpty()) {
                    anyMateChrom = true;
                    break;
                }
            }
            assertTrue(anyMateChrom, "expected queryable mate-chrom hits");
        }
    }

    @Test
    @EnabledIf("vcfExists")
    void emptyMappingYieldsZeroHitsDespiteVisits() throws Exception {
        try (VcfReader reader = new VcfReader(VCF)) {
            VariantLoader loader = new VariantLoader(reader);
            loader.mapAllEligibleToTrack(-1);
            assertEquals(0, loader.getMappedSampleCount());

            AtomicInteger visit = new AtomicInteger();
            reader.visitInterChromosomalBreakends(sv -> visit.incrementAndGet());
            assertTrue(visit.get() > 0, "file must contain inter-chrom BNDs");

            MateBreakpointIndex index = loader.buildMateBreakpointIndex();
            assertEquals(0, index.size(), "empty sample map must drop all mate hits");
        }
    }

    @Test
    @EnabledIf("vcfExists")
    void prepareMateSupportInlineDefersUntilMapped() throws Exception {
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        track = new SampleTrack("repro-defer");
        // Track not registered yet — mapping stays empty.

        openReader = new VcfReader(VCF);
        openLoader = new VariantLoader(openReader);
        File file = VCF.toFile();
        registered = VcfManager.getInstance().registerLoadedVcf(openReader, openLoader, file);
        assertNotNull(registered);
        assertNull(registered.getMateBreakpointIndex(), "register must not build mate index");

        VcfManager.getInstance().prepareMateSupportInline(registered);
        assertNull(
            registered.getMateBreakpointIndex(),
            "unmapped prepare must defer (leave mateIndex null), not sticky-empty");

        registry.getSampleTracks().add(track);
        registry.getSampleList().add(track.getName());
        openLoader.mapAllEligibleToTrack(registry.getSampleTracks().indexOf(track));
        assertTrue(openLoader.getMappedSampleCount() > 0);

        VcfManager.getInstance().prepareMateSupportInline(registered);
        MateBreakpointIndex index = registered.getMateBreakpointIndex();
        assertNotNull(index);
        assertTrue(index.size() > 0, "mapped prepare must index BNDs, got hits=" + index.size());
    }
}
