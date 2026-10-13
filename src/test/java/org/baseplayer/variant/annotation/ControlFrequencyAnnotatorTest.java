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
    void resolveAfFallsBackToGlobalWhenPopMissing() {
        Map<String, Object> info = new HashMap<>();
        info.put("AC", 1);
        info.put("AN", 100);
        assertEquals(0.01, ControlFrequencyAnnotator.resolveAf(info, 0, 1, "fin"), 1e-12);
    }

    @Test
    void normalizePopulationIdAcceptsAfPrefix() {
        assertEquals("fin", ControlFrequencyAnnotator.normalizePopulationId("AF_fin"));
        assertEquals("", ControlFrequencyAnnotator.normalizePopulationId("global"));
    }
}
