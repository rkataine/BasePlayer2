package org.baseplayer.variant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VariantOddsRatioTest {

    @Test
    void matchingFrequenciesYieldNearOne() {
        // case AF = 10/100 = 0.1, control AF = 0.1 → OR ≈ 1
        double or = VariantOddsRatio.alleleOddsRatio(10, 100, 0.1);
        assertEquals(1.0, or, 1e-9);
    }

    @Test
    void enrichmentRaisesOr() {
        // case AF = 0.2 vs control 0.1
        double or = VariantOddsRatio.alleleOddsRatio(20, 100, 0.1);
        assertTrue(or > 2.0 && or < 2.5, "OR=" + or);
    }

    @Test
    void zeroControlAfStaysFinite() {
        double or = VariantOddsRatio.alleleOddsRatio(2, 100, 0.0);
        assertTrue(Double.isFinite(or) && or > 1.0, "OR=" + or);
    }

    @Test
    void formatCapsHugeValues() {
        assertEquals("≥1000", VariantOddsRatio.format(5000));
        assertEquals("12.3", VariantOddsRatio.format(12.34));
        assertEquals("1.50", VariantOddsRatio.format(1.5));
    }

    @Test
    void computeRequiresSharedCarriersAndControlAf() {
        VariantNode node = new VariantNode(100, "A", "G", VcfVariantType.SNV);
        assertNull(VariantOddsRatio.compute(node, 10));

        node.controlAf = 0.01;
        // No sample calls yet → still null
        assertNull(VariantOddsRatio.compute(node, 10));
    }

    @Test
    void computeWithTwoCarriers() {
        org.baseplayer.samples.SampleTrack t1 = new org.baseplayer.samples.SampleTrack("s1");
        org.baseplayer.samples.SampleTrack t2 = new org.baseplayer.samples.SampleTrack("s2");
        VariantNode node = new VariantNode(100, "A", "G", VcfVariantType.SNV);
        node.controlAf = 0.1;
        node.addSample(new VariantNode.SampleCall(t1, "0/1", 40, 30, 0.5));
        node.addSample(new VariantNode.SampleCall(t2, "0/1", 40, 30, 0.5));

        Double or = VariantOddsRatio.compute(node, 10); // AC=2, AN=20, AF_case=0.1 vs 0.1
        assertEquals(1.0, or, 1e-9);
    }
}
