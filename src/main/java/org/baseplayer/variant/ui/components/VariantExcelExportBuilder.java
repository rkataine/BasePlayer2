package org.baseplayer.variant.ui.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.io.VariantTableExcelWriter.SheetSource;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable.GeneGroup;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable.TableRow;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable.VariantEntry;

/**
 * Streams expanded Gene → Variant → Sample rows into Excel sheet sources.
 * Gene description is repeated on every sample row for quick scanning in Excel.
 */
public final class VariantExcelExportBuilder {

    private VariantExcelExportBuilder() {}

    public static SheetSource pointSheet(
            String sheetName,
            List<TableRow> rows,
            VariantFilter filter,
            boolean groupByChromosome) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return new StreamingSheetSource(
            sheetName,
            pointHeaders(),
            estimateSampleRows(rows, filter),
            consumer -> streamPointRows(rows, filter, groupByChromosome, consumer));
    }

    public static SheetSource structuralSheet(
            String sheetName,
            List<TableRow> rows,
            VariantFilter filter) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return new StreamingSheetSource(
            sheetName,
            structuralHeaders(),
            estimateSampleRows(rows, filter),
            consumer -> streamStructuralRows(rows, filter, consumer));
    }

    private static void streamPointRows(
            List<TableRow> rows,
            VariantFilter filter,
            boolean groupByChromosome,
            Consumer<List<String>> consumer) {
        List<GeneGroup> groups = AbstractNestedVariantTable.buildGeneGroups(
            rows, groupByChromosome, filter, false);
        List<String> reusable = new ArrayList<>(20);
        for (GeneGroup group : groups) {
            String description = resolveGeneDescription(group);
            for (VariantEntry entry : group.variants) {
                if (entry == null || entry.row == null || entry.row.node() == null) {
                    continue;
                }
                if (entry.calls == null || entry.calls.isEmpty()) {
                    continue;
                }
                for (VariantNode.SampleCall call : entry.calls) {
                    fillPointRow(reusable, group, entry.row, call, filter, description);
                    // Writer consumes synchronously before the next fill clears the buffer.
                    consumer.accept(reusable);
                }
            }
        }
    }

    private static void streamStructuralRows(
            List<TableRow> rows,
            VariantFilter filter,
            Consumer<List<String>> consumer) {
        List<GeneGroup> groups = AbstractNestedVariantTable.buildGeneGroups(
            rows, false, filter, false);
        List<String> reusable = new ArrayList<>(18);
        for (GeneGroup group : groups) {
            String description = resolveGeneDescription(group);
            for (VariantEntry entry : group.variants) {
                if (entry == null || entry.row == null || entry.row.node() == null) {
                    continue;
                }
                if (entry.calls == null || entry.calls.isEmpty()) {
                    continue;
                }
                for (VariantNode.SampleCall call : entry.calls) {
                    fillStructuralRow(reusable, group, entry.row, call, filter, description);
                    // Writer consumes synchronously before the next fill clears the buffer.
                    consumer.accept(reusable);
                }
            }
        }
    }

    /**
     * Cheap estimate: count display calls without allocating row strings.
     * Used only for the progress bar denominator.
     */
    private static int estimateSampleRows(List<TableRow> rows, VariantFilter filter) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (TableRow row : rows) {
            if (row == null || row.node() == null) {
                continue;
            }
            for (VariantNode.SampleCall call : row.node().getSamples()) {
                if (call == null) {
                    continue;
                }
                if (filter != null && !filter.passesSampleDisplay(row.node(), call)) {
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    private static List<String> pointHeaders() {
        List<String> headers = new ArrayList<>();
        headers.add("Gene");
        headers.add("Chromosome");
        headers.add("Position");
        headers.add("Ref");
        headers.add("ALT");
        headers.add("Type");
        headers.add("Effect");
        headers.add("Transcript");
        headers.add("HGVS.c");
        headers.add("HGVS.p");
        headers.add("Codon");
        headers.add("Sample");
        headers.add("GT");
        headers.add("AF");
        headers.add("GQ");
        headers.add("DP");
        headers.add("Site QUAL");
        headers.add("Cancer gene");
        // Future: control / population columns append here, before description.
        headers.add("Gene description");
        return headers;
    }

    private static List<String> structuralHeaders() {
        List<String> headers = new ArrayList<>();
        headers.add("Gene");
        headers.add("Chromosome");
        headers.add("Start");
        headers.add("End");
        headers.add("Length");
        headers.add("Type");
        headers.add("Effect");
        headers.add("Mate chromosome");
        headers.add("Mate position");
        headers.add("Sample");
        headers.add("GT");
        headers.add("AF");
        headers.add("GQ");
        headers.add("DP");
        headers.add("Site QUAL");
        headers.add("Cancer gene");
        // Future: control / population columns append here, before description.
        headers.add("Gene description");
        return headers;
    }

    private static void fillPointRow(
            List<String> values,
            GeneGroup group,
            TableRow row,
            VariantNode.SampleCall call,
            VariantFilter filter,
            String description) {
        values.clear();
        VariantNode node = row.node();
        VariantAnnotation ann = AbstractNestedVariantTable.rowAnnotation(node);
        values.add(nullToEmpty(group != null ? group.name : AbstractNestedVariantTable.exportGeneName(row)));
        values.add(chromDisplay(row));
        values.add(node.position > 0 ? Long.toString(node.position) : "");
        values.add(nullToEmpty(node.ref));
        values.add(node.alt != null && !node.alt.isEmpty() ? node.alt : ".");
        values.add(AbstractNestedVariantTable.resolveTableColumnValue(row, "variantType", filter));
        values.add(AbstractNestedVariantTable.resolveTableColumnValue(row, "effectDisplay", filter));
        values.add(ann != null && ann.transcriptId() != null ? ann.transcriptId() : "");
        values.add(ann != null && ann.codonChange() != null ? ann.codonChange() : "");
        values.add(ann != null && ann.aaChange() != null ? ann.aaChange() : "");
        values.add(ann != null && ann.codonNumber() > 0 ? Integer.toString(ann.codonNumber()) : "");
        values.add(AbstractNestedVariantTable.sampleDisplayName(call));
        values.add(formatGt(node, call, filter));
        values.add(formatAf(call));
        values.add(formatGq(call));
        values.add(formatDp(call));
        values.add(formatSiteQual(node));
        values.add(formatCancerGene(group));
        values.add(nullToEmpty(description));
    }

    private static void fillStructuralRow(
            List<String> values,
            GeneGroup group,
            TableRow row,
            VariantNode.SampleCall call,
            VariantFilter filter,
            String description) {
        values.clear();
        VariantNode node = row.node();
        values.add(nullToEmpty(group != null ? group.name : AbstractNestedVariantTable.exportGeneName(row)));
        values.add(chromDisplay(row));
        values.add(node.position > 0 ? Long.toString(node.position) : "");
        values.add(node.svEnd > node.position ? Long.toString(node.svEnd) : "");
        values.add(AbstractNestedVariantTable.formatSvLength(row));
        values.add(VariantTypeVisuals.shortLabel(node.type));
        values.add(AbstractNestedVariantTable.resolveTableColumnValue(row, "effectDisplay", filter));
        String mateChrom = node.mateChromosome();
        values.add(mateChrom != null && !mateChrom.isBlank() ? ChromosomeNames.forDisplay(mateChrom) : "");
        long matePos = node.matePosition();
        values.add(matePos >= 0 ? Long.toString(matePos) : "");
        values.add(AbstractNestedVariantTable.sampleDisplayName(call));
        values.add(formatGt(node, call, filter));
        values.add(formatAf(call));
        values.add(formatGq(call));
        values.add(formatDp(call));
        values.add(formatSiteQual(node));
        values.add(formatCancerGene(group));
        values.add(nullToEmpty(description));
    }

    private static String resolveGeneDescription(GeneGroup group) {
        if (group == null || group.name == null || group.name.isBlank()) {
            return "";
        }
        String description = AnnotationData.getGeneDescription(group.name);
        return description != null ? description : "";
    }

    private static String chromDisplay(TableRow row) {
        if (row == null || row.chromosome() == null || row.chromosome().isBlank()) {
            return "";
        }
        return ChromosomeNames.forDisplay(row.chromosome());
    }

    private static String formatGt(VariantNode node, VariantNode.SampleCall call, VariantFilter filter) {
        if (call == null) {
            return "";
        }
        String raw = call.gt != null ? call.gt : "";
        if (filter != null && filter.isLohMode() && node != null) {
            String loh = node.lohAlleleClass(call);
            if (loh != null && !loh.isBlank()) {
                return loh + " (" + (raw.isBlank() ? "." : raw) + ")";
            }
        }
        return raw;
    }

    private static String formatAf(VariantNode.SampleCall call) {
        if (call == null || call.alleleFraction < 0) {
            return "";
        }
        return String.format(Locale.ROOT, "%.4f", call.alleleFraction);
    }

    private static String formatGq(VariantNode.SampleCall call) {
        if (call == null || call.quality < 0) {
            return "";
        }
        return String.format(Locale.ROOT, "%.0f", call.quality);
    }

    private static String formatDp(VariantNode.SampleCall call) {
        if (call == null || call.depth < 0) {
            return "";
        }
        return Integer.toString(call.depth);
    }

    private static String formatSiteQual(VariantNode node) {
        if (node == null || node.siteQuality < 0) {
            return "";
        }
        return String.format(Locale.ROOT, "%.0f", node.siteQuality);
    }

    /** Cancer census flag plus tier when available, e.g. {@code Yes (1)} or empty. */
    private static String formatCancerGene(GeneGroup group) {
        if (group == null || !group.cancer) {
            return "";
        }
        String tier = group.tier;
        if (tier != null && !tier.isBlank()) {
            return "Yes (" + tier.trim() + ")";
        }
        return "Yes";
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private record StreamingSheetSource(
            String name,
            List<String> headers,
            int estimatedRows,
            Consumer<Consumer<List<String>>> rowWriter) implements SheetSource {

        @Override
        public void writeRows(Consumer<List<String>> rowConsumer) {
            rowWriter.accept(rowConsumer);
        }
    }
}
