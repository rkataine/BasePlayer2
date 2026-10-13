package org.baseplayer.variant.annotation;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
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

class ControlFrequencyAnnotatorGnomadSmokeTest {

    private static final Path GNOMAD =
        Path.of(System.getProperty("user.home"),
            "work/genomic_data/controls/gnomAD_exomes_v4.1.1.vcf.gz");

    private final List<SampleTrack> added = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        registry.getSampleTracks().removeAll(added);
        added.clear();
    }

    @Test
    void annotateKnownChr1Site() throws Exception {
        assumeTrue(Files.isRegularFile(GNOMAD), "gnomAD fixture not present");
        assumeTrue(Files.isRegularFile(Path.of(GNOMAD + ".tbi")), "gnomAD tbi missing");

        SampleTrack track = new SampleTrack("gnomad-smoke");
        ServiceRegistry.getInstance().getSampleRegistry().getSampleTracks().add(track);
        added.add(track);

        try (ControlFrequencyAnnotator a = new ControlFrequencyAnnotator()) {
            long t0 = System.nanoTime();
            a.open(GNOMAD);
            long openMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(a.listPopulations().size() > 1, "expected pop INFO fields");

            a.setSelectedPopulations(List.of(""));
            VariantList list = new VariantList("1");
            list.addVariant(930188, "G", "A", VcfVariantType.SNV,
                new VariantNode.SampleCall(track, null, "0/1", 30, 40, 0.5));

            t0 = System.nanoTime();
            a.annotate(list);
            long annMs = (System.nanoTime() - t0) / 1_000_000;

            VariantNode n = list.getFirst();
            assertTrue(n.controlAf != null && n.controlAf > 0, "AF at 930188");
            // AC=13 AN=1409162
            assertTrue(Math.abs(n.controlAf - (13.0 / 1409162.0)) < 1e-12);
            System.out.println("gnomAD smoke open_ms=" + openMs + " annotate_ms=" + annMs
                + " af=" + n.controlAf);
        }
    }
}
