package org.baseplayer.variant.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable.TableRow;
import org.junit.jupiter.api.Test;

class VariantExcelExportBuilderTest {

    @Test
    void geneColumnUsesGeneSymbolWhenGroupedByChromosome() {
        TableRow row = annotatedRow("17", "TP53");
        List<List<String>> written = new ArrayList<>();
        VariantExcelExportBuilder.pointSheet("Coding", List.of(row), null, true)
            .writeRows(written::add);

        assertFalse(written.isEmpty());
        assertEquals("TP53", written.get(0).get(0));
        assertEquals(ChromosomeNames.forDisplay("17"), written.get(0).get(1));
    }

    @Test
    void geneColumnStaysEmptyWhenVariantHasNoGene() {
        VariantNode node = new VariantNode(100, "A", "G", VcfVariantType.SNV);
        node.addSample(new VariantNode.SampleCall(new SampleTrack("s"), "0/1", 40, 30, 0.5));
        TableRow row = new TableRow("1", node);

        List<List<String>> written = new ArrayList<>();
        VariantExcelExportBuilder.pointSheet("Intergenic", List.of(row), null, true)
            .writeRows(written::add);

        assertFalse(written.isEmpty());
        assertEquals("", written.get(0).get(0));
        assertEquals(ChromosomeNames.forDisplay("1"), written.get(0).get(1));
    }

    private static TableRow annotatedRow(String chromosome, String gene) {
        VariantNode node = new VariantNode(100, "A", "G", VcfVariantType.SNV);
        node.annotation = new VariantAnnotation(
            chromosome, 100, VariantEffect.CODING_MISSENSE,
            gene, "ENST0001", null, null, 0,
            false, null, List.of());
        node.addSample(new VariantNode.SampleCall(new SampleTrack("s"), "0/1", 40, 30, 0.5));
        return new TableRow(chromosome, node);
    }
}
