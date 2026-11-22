package org.baseplayer.analysis.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.baseplayer.io.table.MappedTableColumnMapping;
import org.baseplayer.io.table.MappedTableColumnRole;
import org.baseplayer.io.table.MappedTableData;
import org.baseplayer.io.table.SharedMatrixLoci;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.junit.jupiter.api.Test;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.GenomicScope;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.signal.SignalAnnotator;
import org.baseplayer.analysis.signal.SignalComparisonFilter;
import org.baseplayer.analysis.signal.SignalComparisonSettings;
import org.baseplayer.analysis.signal.SignalValueThresholds;

class CompactTableGridTest {

  @Test
  void gridReadsSameFloatsAsMappedTableData() {
    SharedMatrixLoci loci = new SharedMatrixLoci(
        new String[] {"chr1", "chr1"},
        new long[] {100, 300},
        new long[] {200, 400},
        new String[] {"GENEA", "GENEB"});
    float[] valuesA = new float[] {0.2f, 0.9f};
    float[] valuesB = new float[] {0.8f, 0.1f};
    MappedTableData tableA = MappedTableData.geneMatrixSample(
        Path.of("a.tsv"), "A", valueMapping(), new String[] {"gene", "value"}, loci, valuesA);
    MappedTableData tableB = MappedTableData.geneMatrixSample(
        Path.of("b.tsv"), "B", valueMapping(), new String[] {"gene", "value"}, loci, valuesB);

    SampleTrack trackA = trackWithTable("A", tableA);
    SampleTrack trackB = trackWithTable("B", tableB);

    CompactTableGrid grid = CompactTableGrid.tryCreate(
        List.of(trackA, trackB), GenomicScope.ALL_CHROMS);
    assertNotNull(grid);
    assertEquals(2, grid.size());
    assertEquals(0.2, grid.get("GENEA", trackA), 1e-5);
    assertEquals(0.9, grid.get("GENEB", trackA), 1e-5);
    assertEquals(0.8, grid.get("GENEA", trackB), 1e-5);

    GroupStatResult annotated = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL, grid, List.of(trackA, trackB), new SignalValueThresholds(), "test");
    assertEquals(2, annotated.rows().size());
    // CompactTableGrid uses slim summaries: cohort + top extreme samples.
    assertEquals(3, annotated.rows().get(0).groups().size());
    assertTrue(annotated.rows().get(0).groups().get(0).groupName().startsWith("Cohort"));
  }

  @Test
  void maskRemovesFiniteCellsThenAnnotateSeesFewerSamples() {
    SharedMatrixLoci loci = new SharedMatrixLoci(
        new String[] {"chr1"},
        new long[] {100},
        new long[] {200},
        new String[] {"GENE"});
    float[] valuesA = new float[] {0.2f};
    float[] valuesB = new float[] {0.9f};
    MappedTableData tableA = MappedTableData.geneMatrixSample(
        Path.of("a.tsv"), "A", valueMapping(), new String[] {"gene", "value"}, loci, valuesA);
    MappedTableData tableB = MappedTableData.geneMatrixSample(
        Path.of("b.tsv"), "B", valueMapping(), new String[] {"gene", "value"}, loci, valuesB);
    SampleTrack trackA = trackWithTable("A", tableA);
    SampleTrack trackB = trackWithTable("B", tableB);

    SignalValueThresholds thr = new SignalValueThresholds();
    thr.setEnabled(true);
    thr.setMinValue(0.5);

    tableA.applyValueMask(v -> !thr.passes(v));
    tableB.applyValueMask(v -> !thr.passes(v));

    assertTrue(Float.isNaN(tableA.compactValue(0)));
    assertEquals(0.9f, tableB.compactValue(0), 1e-5);

    CompactTableGrid grid = CompactTableGrid.tryCreate(
        List.of(trackA, trackB), GenomicScope.ALL_CHROMS);
    assertNotNull(grid);
    assertFalse(grid.hasValue("GENE", trackA));
    assertTrue(grid.hasValue("GENE", trackB));

    GroupStatResult annotated = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL, grid, List.of(trackA, trackB), new SignalValueThresholds(), "test");
    assertEquals(1, annotated.rows().size());
    assertEquals(1, annotated.rows().get(0).groups().size());
    assertEquals("B", annotated.rows().get(0).groups().get(0).groupName());

    SignalComparisonSettings settings = new SignalComparisonSettings();
    settings.setMinSharedSamples(2);
    GroupStatResult filtered = SignalComparisonFilter.apply(
        annotated, settings, List.of(trackA, trackB), null, null, null, null, grid);
    assertEquals(0, filtered.rows().size());

    tableA.restoreFromRawBackup();
    tableB.restoreFromRawBackup();
    assertEquals(0.2f, tableA.compactValue(0), 1e-5);
    assertEquals(0.9f, tableB.compactValue(0), 1e-5);
  }

  private static MappedTableColumnMapping valueMapping() {
    return new MappedTableColumnMapping(
        java.util.Map.of(
            MappedTableColumnRole.CHROM, 0,
            MappedTableColumnRole.START, 1,
            MappedTableColumnRole.END, 2,
            MappedTableColumnRole.VALUE, 3),
        false);
  }

  private static SampleTrack trackWithTable(String name, MappedTableData table) {
    Sample sample = new Sample(Path.of(name + ".tsv"), table);
    return new SampleTrack(sample);
  }
}
