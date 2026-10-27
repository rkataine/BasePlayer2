package org.baseplayer.variant.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
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

class SvControlAnnotatorTest {

    private final List<SampleTrack> addedTracks = new ArrayList<>();

    @AfterEach
    void removeAddedTracks() {
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        registry.getSampleTracks().removeAll(addedTracks);
        addedTracks.clear();
    }

    @Test
    void matchesRequiresBothBreakpointsWithinWindow() {
        SvControlAnnotator.ControlSvRecord ctrl = new SvControlAnnotator.ControlSvRecord(
            346565, 346639, "chr10", -1, "DEL", "<DEL>", 0.01);
        VariantNode inside = svNode(346570, 346635, VcfVariantType.SV_DELETION);
        VariantNode startOut = svNode(346700, 346635, VcfVariantType.SV_DELETION);
        VariantNode endOut = svNode(346570, 346800, VcfVariantType.SV_DELETION);
        assertTrue(SvControlAnnotator.matches(inside, ctrl, "chr10", 50, true));
        assertFalse(SvControlAnnotator.matches(startOut, ctrl, "chr10", 50, true));
        assertFalse(SvControlAnnotator.matches(endOut, ctrl, "chr10", 50, true));
    }

    @Test
    void sameTypeToggleBlocksCrossTypeMatch() {
        SvControlAnnotator.ControlSvRecord dup = new SvControlAnnotator.ControlSvRecord(
            346453, 346647, "chr10", -1, "DUP", "<DUP>", 0.05);
        VariantNode del = svNode(346453, 346647, VcfVariantType.SV_DELETION);
        assertFalse(SvControlAnnotator.matches(del, dup, "chr10", 50, true));
        assertTrue(SvControlAnnotator.matches(del, dup, "chr10", 50, false));
    }

    @Test
    void insertionMatchesPrimaryOnly() {
        SvControlAnnotator.ControlSvRecord ins = new SvControlAnnotator.ControlSvRecord(
            400000, 400000, "chr10", -1, "INS", "<INS>", 0.004);
        VariantNode sample = svNode(400010, 400010, VcfVariantType.SV_INSERTION);
        assertTrue(SvControlAnnotator.matches(sample, ins, "chr10", 50, true));
        VariantNode far = svNode(400200, 400200, VcfVariantType.SV_INSERTION);
        assertFalse(SvControlAnnotator.matches(far, ins, "chr10", 50, true));
    }

    @Test
    void compatibleTypesMapsCnvLossToDel() {
        assertTrue(SvControlAnnotator.compatibleTypes(
            VcfVariantType.SV_CNV_LOSS, "DEL", "<DEL>"));
        assertTrue(SvControlAnnotator.compatibleTypes(
            VcfVariantType.SV_CNV_GAIN, "DUP", "<DUP>"));
        assertFalse(SvControlAnnotator.compatibleTypes(
            VcfVariantType.SV_DELETION, "DUP", "<DUP>"));
    }

    @Test
    void parseControlSvReadsSymbolicDel() {
        String line =
            "chr10\t346565\t.\tN\t<DEL>\t264\tPASS\tAC=1;AN=126030;CHR2=chr10;END=346639;SVLEN=74;SVTYPE=DEL";
        byte[] bytes = line.getBytes(StandardCharsets.US_ASCII);
        SvControlAnnotator.ControlSvRecord rec = SvControlAnnotator.parseControlSv(
            bytes, 0, bytes.length, List.of(""));
        assertNotNull(rec);
        assertEquals(346565, rec.pos());
        assertEquals(346639, rec.end());
        assertEquals("DEL", rec.svType());
        assertEquals(1.0 / 126030.0, rec.af(), 1e-12);
    }

    @Test
    void annotateWritesAfForMatchingDeletion() throws Exception {
        Path vcf = fixtureVcf();
        SampleTrack track = registerTrack("sv-ctrl-del");
        try (SvControlAnnotator annotator = new SvControlAnnotator()) {
            annotator.open(vcf);
            annotator.setBreakpointWindowBp(50);
            annotator.setRequireSameType(true);
            annotator.setSelectedPopulations(List.of(""));

            VariantList list = new VariantList("chr10");
            VariantNode hit = list.addVariant(
                346570, "N", "<DEL>", VcfVariantType.SV_DELETION, call(track));
            hit.svEnd = 346635;
            // Start near a control DEL but end far outside the ±50 window of END=346639.
            VariantNode miss = list.addVariant(
                346580, "N", "<DEL>", VcfVariantType.SV_DELETION, call(track));
            miss.svEnd = 347000;

            annotator.annotate(list);

            assertEquals(1.0 / 126030.0, hit.controlAf, 1e-12);
            assertNull(miss.controlAf);
            assertNotNull(list.getSvControlAfSourcePath());
            assertTrue(list.getSvControlAfSourcePath().contains("svWindow=50"));
        }
    }

    @Test
    void annotateRespectsSameTypeToggle() throws Exception {
        Path vcf = fixtureVcf();
        SampleTrack track = registerTrack("sv-ctrl-type");
        try (SvControlAnnotator annotator = new SvControlAnnotator()) {
            annotator.open(vcf);
            annotator.setBreakpointWindowBp(50);
            annotator.setRequireSameType(true);
            annotator.setSelectedPopulations(List.of(""));

            VariantList list = new VariantList("chr10");
            // Coordinates match the first DUP control record, but sample is DEL.
            VariantNode delOnDup = list.addVariant(
                346453, "N", "<DEL>", VcfVariantType.SV_DELETION, call(track));
            delOnDup.svEnd = 346647;

            annotator.annotate(list);
            assertNull(delOnDup.controlAf);

            list.setSvControlAfSourcePath(null);
            annotator.setRequireSameType(false);
            annotator.annotate(list, null, true);
            assertEquals(7.0 / 123188.0, delOnDup.controlAf, 1e-12);
        }
    }

    @Test
    void annotateMatchesInsertionByStart() throws Exception {
        Path vcf = fixtureVcf();
        SampleTrack track = registerTrack("sv-ctrl-ins");
        try (SvControlAnnotator annotator = new SvControlAnnotator()) {
            annotator.open(vcf);
            annotator.setBreakpointWindowBp(20);
            annotator.setRequireSameType(true);
            annotator.setSelectedPopulations(List.of(""));

            VariantList list = new VariantList("chr10");
            VariantNode ins = list.addVariant(
                400005, "N", "<INS>", VcfVariantType.SV_INSERTION, call(track));
            ins.svEnd = 400005;
            annotator.annotate(list);
            assertEquals(4.0 / 1000.0, ins.controlAf, 1e-12);
        }
    }

    private static VariantNode svNode(long pos, long end, VcfVariantType type) {
        VariantNode node = new VariantNode(pos, "N", "<" + type.name() + ">", type);
        node.svEnd = end;
        return node;
    }

    private static Path fixtureVcf() throws Exception {
        URL url = SvControlAnnotatorTest.class.getResource("/control/sv_control.vcf.gz");
        assertNotNull(url, "missing test fixture /control/sv_control.vcf.gz");
        return Path.of(url.toURI());
    }

    private SampleTrack registerTrack(String name) {
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        SampleTrack track = new SampleTrack(name);
        registry.getSampleTracks().add(track);
        addedTracks.add(track);
        return track;
    }

    private static VariantNode.SampleCall call(SampleTrack track) {
        return new VariantNode.SampleCall(track, null, "0/1", 30, 20, 0.5);
    }
}
