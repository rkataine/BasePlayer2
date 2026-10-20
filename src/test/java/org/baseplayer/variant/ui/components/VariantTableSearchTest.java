package org.baseplayer.variant.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable.TableRow;
import org.junit.jupiter.api.Test;

class VariantTableSearchTest {

    @Test
    void normalizeRequiresTwoCharacters() {
        assertEquals("", AbstractNestedVariantTable.normalizeSearchQuery(""));
        assertEquals("", AbstractNestedVariantTable.normalizeSearchQuery(" "));
        assertEquals("", AbstractNestedVariantTable.normalizeSearchQuery("T"));
        assertEquals("tp", AbstractNestedVariantTable.normalizeSearchQuery(" TP "));
        assertFalse(AbstractNestedVariantTable.isActiveSearchQuery("a"));
        assertTrue(AbstractNestedVariantTable.isActiveSearchQuery("ab"));
    }

    @Test
    void matchesSearchUsesHaystackAndIgnoresShortQueries() {
        TableRow row = annotatedRow("17", "TP53");
        assertTrue(AbstractNestedVariantTable.matchesSearch(row, "", null));
        // Single-letter queries are treated as inactive (match all).
        assertTrue(AbstractNestedVariantTable.matchesSearch(row, "t", null));
        assertTrue(AbstractNestedVariantTable.matchesSearch(
            row, AbstractNestedVariantTable.normalizeSearchQuery("tp"), null));
        assertTrue(AbstractNestedVariantTable.matchesSearch(
            row, AbstractNestedVariantTable.normalizeSearchQuery("TP53"), null));
        assertTrue(AbstractNestedVariantTable.matchesSearch(
            row, AbstractNestedVariantTable.normalizeSearchQuery("sampleA"), null));
        assertFalse(AbstractNestedVariantTable.matchesSearch(
            row, AbstractNestedVariantTable.normalizeSearchQuery("BRCA"), null));
    }

    @Test
    void rowsMatchingSearchTreatsSingleLetterAsNoFilter() {
        TableRow row = annotatedRow("1", "EGFR");
        List<TableRow> source = List.of(row);
        assertEquals(source, AbstractNestedVariantTable.rowsMatchingSearch(source, "E", null));
        assertEquals(1, AbstractNestedVariantTable.rowsMatchingSearch(source, "EG", null).size());
        assertTrue(AbstractNestedVariantTable.rowsMatchingSearch(source, "ZZ", null).isEmpty());
    }

    private static TableRow annotatedRow(String chromosome, String gene) {
        VariantNode node = new VariantNode(100, "A", "G", VcfVariantType.SNV);
        node.annotation = new VariantAnnotation(
            chromosome, 100, VariantEffect.CODING_MISSENSE,
            gene, "ENST0001", null, null, 0,
            false, null, List.of());
        node.addSample(new VariantNode.SampleCall(new SampleTrack("sampleA"), "0/1", 40, 30, 0.5));
        return new TableRow(chromosome, node);
    }
}
