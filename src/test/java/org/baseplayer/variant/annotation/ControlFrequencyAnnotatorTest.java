package org.baseplayer.variant.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ControlFrequencyAnnotatorTest {

    @Test
    void resolveAfFromAcAn() {
        Map<String, Object> info = new HashMap<>();
        info.put("AC", 3);
        info.put("AN", 135096);
        Double af = ControlFrequencyAnnotator.resolveAf(info, 0, 1);
        assertEquals(3.0 / 135096.0, af, 1e-12);
    }

    @Test
    void resolveAfPrefersInfoAf() {
        Map<String, Object> info = new HashMap<>();
        info.put("AF", 0.25);
        info.put("AC", 1);
        info.put("AN", 100);
        assertEquals(0.25, ControlFrequencyAnnotator.resolveAf(info, 0, 1), 1e-12);
    }

    @Test
    void resolveAfPerAltAcList() {
        Map<String, Object> info = new HashMap<>();
        info.put("AC", List.of(3, 1));
        info.put("AN", 135146);
        assertEquals(3.0 / 135146.0, ControlFrequencyAnnotator.resolveAf(info, 0, 2), 1e-12);
        assertEquals(1.0 / 135146.0, ControlFrequencyAnnotator.resolveAf(info, 1, 2), 1e-12);
    }

    @Test
    void resolveAfMissingReturnsNull() {
        assertNull(ControlFrequencyAnnotator.resolveAf(Map.of(), 0, 1));
        assertNull(ControlFrequencyAnnotator.resolveAf(Map.of("AC", 1), 0, 1));
    }

    @Test
    void alleleKeyNormalizesCase() {
        assertEquals(
            ControlFrequencyAnnotator.alleleKey(100, "g", "a"),
            ControlFrequencyAnnotator.alleleKey(100, "G", "A"));
    }

    @Test
    void resolveAfUsesPopulationFields() {
        Map<String, Object> info = new HashMap<>();
        info.put("AF", 0.5);
        info.put("AF_fin", 0.01);
        info.put("AC_fin", 2);
        info.put("AN_fin", 200);
        assertEquals(0.01, ControlFrequencyAnnotator.resolveAf(info, 0, 1, "fin"), 1e-12);
        assertEquals(0.5, ControlFrequencyAnnotator.resolveAf(info, 0, 1, ""), 1e-12);
    }

    @Test
    void resolveAfDoesNotFallBackToGlobalWhenPopMissing() {
        Map<String, Object> info = new HashMap<>();
        info.put("AC", 1);
        info.put("AN", 100);
        assertNull(ControlFrequencyAnnotator.resolveAf(info, 0, 1, "fin"));
    }

    @Test
    void resolveAfMaxAcrossSelectedPopulations() {
        Map<String, Object> info = new HashMap<>();
        info.put("AF_fin", 0.01);
        info.put("AF_nfe", 0.05);
        info.put("AF", 0.2);
        assertEquals(
            0.05,
            ControlFrequencyAnnotator.resolveAf(info, 0, 1, List.of("fin", "nfe")),
            1e-12);
        assertEquals(
            0.2,
            ControlFrequencyAnnotator.resolveAf(info, 0, 1, List.of("", "fin")),
            1e-12);
    }

    @Test
    void normalizePopulationIdAcceptsAfPrefix() {
        assertEquals("fin", ControlFrequencyAnnotator.normalizePopulationId("AF_fin"));
        assertEquals("", ControlFrequencyAnnotator.normalizePopulationId("global"));
    }

    @Test
    void parseVcfPosReadsSecondColumnOnly() {
        assertEquals(12345L, ControlFrequencyAnnotator.parseVcfPos("1\t12345\t.\tA\tG\t.\t.\tAC=1;AN=100"));
        assertEquals(-1L, ControlFrequencyAnnotator.parseVcfPos("#CHROM\tPOS\tID"));
    }

    @Test
    void resolveAfFromInfoStringUsesAcAnAndPops() {
        String info = "AC=3;AN=135096;AF_fin=0.01;AC_nfe=5;AN_nfe=100";
        assertEquals(
            3.0 / 135096.0,
            ControlFrequencyAnnotator.resolveAfFromInfoString(info, 0, 1, List.of("")),
            1e-12);
        assertEquals(
            0.01,
            ControlFrequencyAnnotator.resolveAfFromInfoString(info, 0, 1, List.of("fin")),
            1e-12);
        assertEquals(
            0.05,
            ControlFrequencyAnnotator.resolveAfFromInfoString(info, 0, 1, List.of("nfe")),
            1e-12);
    }

    @Test
    void infoNumberPicksAltIndex() {
        assertEquals(
            2.0,
            ControlFrequencyAnnotator.infoNumber("AC=1,2,3;AN=10", "AC", 1),
            1e-12);
    }
}
