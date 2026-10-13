package org.baseplayer.variant.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfVariantType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ControlFrequencyAnnotatorSweepTest {

    private final List<SampleTrack> addedTracks = new ArrayList<>();

    @AfterEach
    void removeAddedTracks() {
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        registry.getSampleTracks().removeAll(addedTracks);
        addedTracks.clear();
    }

    @Test
    void annotateWritesControlAfOnAlleleMatch() throws Exception {
        Path vcf = fixtureVcf();
        SampleTrack track = registerTrack("ctrl-test");
        try (ControlFrequencyAnnotator annotator = new ControlFrequencyAnnotator()) {
            annotator.open(vcf);
            assertTrue(annotator.isOpen());

            List<ControlFrequencyAnnotator.PopulationOption> pops = annotator.listPopulations();
            assertTrue(pops.stream().anyMatch(p -> "fin".equals(p.id())));

            annotator.setSelectedPopulations(List.of(""));
            VariantList list = new VariantList("1");
            list.addVariant(100, "A", "G", VcfVariantType.SNV, call(track));
            list.addVariant(200, "C", "A", VcfVariantType.SNV, call(track));
            list.addVariant(300, "G", "C", VcfVariantType.SNV, call(track));
            list.addVariant(999, "A", "T", VcfVariantType.SNV, call(track));

            annotator.annotate(list);

            VariantNode n100 = list.getFirst();
            assertNotNull(n100);
            assertEquals(100, n100.position);
            assertEquals(0.01, n100.controlAf, 1e-12);

            VariantNode n200 = n100.next;
            assertEquals(200, n200.position);
            assertEquals(0.005, n200.controlAf, 1e-12);

            VariantNode n300 = n200.next;
            assertEquals(300, n300.position);
            // Global AF missing → AC/AN = 5/1000
            assertEquals(0.005, n300.controlAf, 1e-12);

            VariantNode n999 = n300.next;
            assertEquals(999, n999.position);
            assertEquals(null, n999.controlAf);
        }
    }

    @Test
    void annotateUsesSelectedPopulationMax() throws Exception {
        Path vcf = fixtureVcf();
        SampleTrack track = registerTrack("ctrl-test-fin");
        try (ControlFrequencyAnnotator annotator = new ControlFrequencyAnnotator()) {
            annotator.open(vcf);
            annotator.setSelectedPopulations(List.of("fin"));
            VariantList list = new VariantList("1");
            list.addVariant(100, "A", "G", VcfVariantType.SNV, call(track));
            annotator.annotate(list);
            assertEquals(0.02, list.getFirst().controlAf, 1e-12);
        }
    }

    @Test
    void sampleStatsReadsAnWithoutHtsjdk() throws Exception {
        Path vcf = fixtureVcf();
        try (ControlFrequencyAnnotator annotator = new ControlFrequencyAnnotator()) {
            annotator.open(vcf);
            ControlFrequencyAnnotator.ControlFileStats stats = annotator.sampleStats(10);
            assertTrue(stats.rowsSampled() >= 1);
            assertTrue(stats.hasAfFields());
            ControlFrequencyAnnotator.PopulationAnStats global = stats.forPopulation("");
            assertNotNull(global);
            assertTrue(global.maxAn() >= 100);
        }
    }

    private SampleTrack registerTrack(String name) {
        SampleTrack track = new SampleTrack(name);
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        registry.getSampleTracks().add(track);
        addedTracks.add(track);
        return track;
    }

    private static VariantNode.SampleCall call(SampleTrack track) {
        return new VariantNode.SampleCall(track, null, "0/1", 30, 40, 0.5);
    }

    private static Path fixtureVcf() throws Exception {
        URL url = ControlFrequencyAnnotatorSweepTest.class.getResource("/control/control.vcf.gz");
        assertNotNull(url, "test fixture /control/control.vcf.gz missing");
        return Path.of(url.toURI());
    }
}
