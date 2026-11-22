package org.baseplayer.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.baseplayer.components.MatrixSampleMappingDialog;
import org.baseplayer.io.table.MappedTableColumnMapping;
import org.baseplayer.io.table.MappedTableColumnRole;
import org.baseplayer.io.table.MappedTableData;
import org.baseplayer.io.table.SharedMatrixLoci;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;

class MatrixSampleMappingTest {

  @BeforeAll
  static void initJavaFx() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
      // toolkit already started
    }
  }

  @Test
  void classifyMatchesExistingTrackNames() {
    SampleRegistry registry = new SampleRegistry();
    SampleTrack a = new SampleTrack(new Sample(Path.of("A.bam")));
    a.setName("SampleA");
    SampleTrack b = new SampleTrack(new Sample(Path.of("B.bam")));
    b.setName("SampleB");
    registry.getSampleTracks().add(a);
    registry.getSampleTracks().add(b);
    registry.getSampleList().add("SampleA");
    registry.getSampleList().add("SampleB");

    MappedTableData colA = matrixColumn("SampleA");
    MappedTableData colB = matrixColumn("SampleB");
    MappedTableData colC = matrixColumn("SampleC");

    SampleDataManager.MatrixColumnPlan plan = SampleDataManager.classifyMatrixColumns(
        registry, List.of(colA, colB, colC));

    assertEquals(2, plan.matched().size());
    assertEquals(1, plan.unmatched().size());
    assertEquals("SampleC", plan.unmatched().get(0).getName());
    assertSame(a, plan.matched().get(0).track());
    assertSame(b, plan.matched().get(1).track());
  }

  @Test
  void classifyRequiresExactNameNotSharedPrefixOrSeparatorAlias() {
    SampleRegistry registry = new SampleRegistry();
    SampleTrack t1 = new SampleTrack(new Sample(Path.of("CRC-SW-U0001-T.bam")));
    t1.setName("CRC-SW-U0001-T");
    SampleTrack t2 = new SampleTrack(new Sample(Path.of("CRC-SW-U0002-T.bam")));
    t2.setName("CRC-SW-U0002-T");
    registry.getSampleTracks().add(t1);
    registry.getSampleTracks().add(t2);
    registry.getSampleList().add(t1.getDisplayName());
    registry.getSampleList().add(t2.getDisplayName());

    // Dot-separated matrix headers must not alias hyphen track names.
    MappedTableData dotted = matrixColumn("CRC.SW.U0001.T");
    // Shared prefix with a different suffix must not bind the other track.
    MappedTableData other = matrixColumn("CRC-SW-U0001");
    // Exact match still works (case-insensitive).
    MappedTableData exact = matrixColumn("crc-sw-u0002-t");

    SampleDataManager.MatrixColumnPlan plan = SampleDataManager.classifyMatrixColumns(
        registry, List.of(dotted, other, exact));

    assertEquals(1, plan.matched().size());
    assertEquals(2, plan.unmatched().size());
    assertSame(t2, plan.matched().get(0).track());
    assertEquals(
        SampleRegistry.normalizeNameKey("CRC.SW.U0001.T"),
        "CRC.SW.U0001.T");
  }

  @Test
  void mapExistingAttachesOnlyMatches() {
    SampleRegistry registry = new SampleRegistry();
    SampleTrack a = new SampleTrack(new Sample(Path.of("A.bam")));
    a.setName("SampleA");
    SampleTrack b = new SampleTrack(new Sample(Path.of("B.bam")));
    b.setName("SampleB");
    registry.getSampleTracks().add(a);
    registry.getSampleTracks().add(b);
    registry.getSampleList().add("SampleA");
    registry.getSampleList().add("SampleB");

    MappedTableData colA = matrixColumn("SampleA");
    MappedTableData colB = matrixColumn("SampleB");
    MappedTableData colC = matrixColumn("SampleC");
    SampleDataManager.MatrixColumnPlan plan = SampleDataManager.classifyMatrixColumns(
        registry, List.of(colA, colB, colC));

    SampleDataManager.MatrixApplyResult result = SampleDataManager.applyMatrixSamplePolicy(
        registry,
        Path.of("matrix.tsv"),
        plan,
        MatrixSampleMappingDialog.Policy.MAP_EXISTING);

    assertEquals(2, result.attached());
    assertEquals(0, result.created());
    assertEquals(1, result.skipped());
    assertEquals(2, registry.getSampleTracks().size());
    assertTrue(a.getSamples().stream().anyMatch(s -> s.getMappedTable() != null));
    assertTrue(b.getSamples().stream().anyMatch(s -> s.getMappedTable() != null));
  }

  @Test
  void mergeAttachesMatchesAndCreatesUnmatched() {
    SampleRegistry registry = new SampleRegistry();
    SampleTrack a = new SampleTrack(new Sample(Path.of("A.bam")));
    a.setName("SampleA");
    SampleTrack b = new SampleTrack(new Sample(Path.of("B.bam")));
    b.setName("SampleB");
    registry.getSampleTracks().add(a);
    registry.getSampleTracks().add(b);
    registry.getSampleList().add("SampleA");
    registry.getSampleList().add("SampleB");

    MappedTableData colA = matrixColumn("SampleA");
    MappedTableData colB = matrixColumn("SampleB");
    MappedTableData colC = matrixColumn("SampleC");
    SampleDataManager.MatrixColumnPlan plan = SampleDataManager.classifyMatrixColumns(
        registry, List.of(colA, colB, colC));

    SampleDataManager.MatrixApplyResult result = SampleDataManager.applyMatrixSamplePolicy(
        registry,
        Path.of("matrix.tsv"),
        plan,
        MatrixSampleMappingDialog.Policy.MERGE);

    assertEquals(2, result.attached());
    assertEquals(1, result.created());
    assertEquals(0, result.skipped());
    assertEquals(3, registry.getSampleTracks().size());
    assertEquals("SampleC", registry.getSampleTracks().get(2).getDisplayName());
  }

  private static MappedTableData matrixColumn(String sampleName) {
    SharedMatrixLoci loci = new SharedMatrixLoci(
        new String[] {"chr1"},
        new long[] {1},
        new long[] {10},
        new String[] {"GENE1"});
    MappedTableColumnMapping mapping = new MappedTableColumnMapping(
        java.util.Map.of(
            MappedTableColumnRole.CHROM, 0,
            MappedTableColumnRole.START, 1,
            MappedTableColumnRole.END, 2,
            MappedTableColumnRole.VALUE, 3),
        false);
    return MappedTableData.geneMatrixSample(
        Path.of("matrix.tsv"),
        sampleName,
        mapping,
        new String[] {"gene", "value"},
        loci,
        new float[] {1.0f});
  }
}
