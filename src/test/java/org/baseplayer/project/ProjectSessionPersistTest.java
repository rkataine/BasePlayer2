package org.baseplayer.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.baseplayer.components.MatrixSampleMappingDialog;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.io.table.MatrixKeyMode;
import org.baseplayer.io.table.MatrixTableOpen.MatrixOpenSpec;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;

/**
 * Project save/restore helpers: VCF path scoping and matrix open persistence.
 */
class ProjectSessionPersistTest {

  @BeforeAll
  static void initJavaFx() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
      // toolkit already started
    }
  }

  @BeforeEach
  void setUp() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    registry.beginBatchTrackMutation();
    try {
      registry.getSampleTracks().clear();
      registry.getSampleList().clear();
    } finally {
      registry.endBatchTrackMutation();
    }
    OpenedMatrixRegistry.get().clear();
    ProjectSessionState.get().resetDocument();
  }

  @AfterEach
  void tearDown() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    registry.beginBatchTrackMutation();
    try {
      registry.getSampleTracks().clear();
      registry.getSampleList().clear();
    } finally {
      registry.endBatchTrackMutation();
    }
    OpenedMatrixRegistry.get().clear();
    ProjectSessionState.get().resetDocument();
  }

  @Test
  void findTrackIndicesListingVcfOnlyReturnsTracksWithThatPath() {
    SampleRegistry registry = new SampleRegistry();
    Path vcfA = Path.of("/tmp/project-a.vcf.gz").toAbsolutePath().normalize();
    Path vcfB = Path.of("/tmp/project-b.vcf.gz").toAbsolutePath().normalize();

    SampleTrack t0 = new SampleTrack("S0");
    t0.addSample(new Sample(vcfA, Sample.DataType.VCF));
    SampleTrack t1 = new SampleTrack("S1");
    t1.addSample(new Sample(vcfB, Sample.DataType.VCF));
    SampleTrack t2 = new SampleTrack("S2");
    t2.addSample(new Sample(vcfA, Sample.DataType.VCF));
    t2.addSample(new Sample(vcfB, Sample.DataType.VCF));

    registry.getSampleTracks().add(t0);
    registry.getSampleTracks().add(t1);
    registry.getSampleTracks().add(t2);

    assertEquals(List.of(0, 2), SampleDataManager.findTrackIndicesListingVcf(registry, vcfA));
    assertEquals(List.of(1, 2), SampleDataManager.findTrackIndicesListingVcf(registry, vcfB));
    assertTrue(SampleDataManager.findTrackIndicesListingVcf(
        registry, Path.of("/tmp/missing.vcf.gz")).isEmpty());
  }

  @Test
  void writeSampleTracksPersistsOnlyVcfsOnEachTrack() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    Path vcfA = Path.of("/tmp/only-a.vcf.gz").toAbsolutePath().normalize();
    Path vcfB = Path.of("/tmp/only-b.vcf.gz").toAbsolutePath().normalize();

    SampleTrack t0 = new SampleTrack("PersistS0");
    t0.addSample(new Sample(vcfA, Sample.DataType.VCF));
    SampleTrack t1 = new SampleTrack("PersistS1");
    t1.addSample(new Sample(vcfB, Sample.DataType.VCF));
    registry.beginBatchTrackMutation();
    try {
      registry.getSampleTracks().setAll(t0, t1);
      registry.getSampleList().clear();
      registry.getSampleList().add("PersistS0");
      registry.getSampleList().add("PersistS1");
    } finally {
      registry.endBatchTrackMutation();
    }
    assertEquals(2, registry.getSampleTracks().size());

    Path projectFile = Path.of("/tmp/test-project.bpj");
    SessionDocumentSync.writeSampleTracksFromRuntime(projectFile);

    ProjectDocument doc = ProjectSessionState.get().getDocument();
    assertEquals(2, doc.sampleTracks.size());
    assertEquals("PersistS0", doc.sampleTracks.get(0).displayName);
    assertEquals(1, doc.sampleTracks.get(0).samples.size());
    assertEquals("VCF", doc.sampleTracks.get(0).samples.get(0).type);
    assertTrue(doc.sampleTracks.get(0).samples.get(0).path.contains("only-a.vcf.gz"));
    assertEquals("PersistS1", doc.sampleTracks.get(1).displayName);
    assertEquals(1, doc.sampleTracks.get(1).samples.size());
    assertTrue(doc.sampleTracks.get(1).samples.get(0).path.contains("only-b.vcf.gz"));
  }

  @Test
  void writeMatricesFromRuntimePersistsOpenedMatrixSpec() {
    Path matrix = Path.of("/tmp/rna-matrix.tsv").toAbsolutePath().normalize();
    MatrixOpenSpec open = new MatrixOpenSpec(
        MatrixKeyMode.GENE_ID,
        true,
        false,
        0,
        1,
        MatrixSampleMappingDialog.Policy.MAP_EXISTING);
    OpenedMatrixRegistry.get().register(matrix, open);

    Path projectFile = Path.of("/tmp/test-project.bpj");
    SessionDocumentSync.writeMatricesFromRuntime(projectFile);

    ProjectDocument doc = ProjectSessionState.get().getDocument();
    assertEquals(1, doc.matrices.size());
    ProjectDocument.MatrixSpec spec = doc.matrices.get(0);
    assertEquals(matrix.toString(), Path.of(spec.path).toAbsolutePath().normalize().toString());
    assertEquals("GENE_ID", spec.mode);
    assertTrue(spec.headerRow);
    assertFalse(spec.startZeroBased);
    assertEquals(0, spec.keyColumnStart);
    assertEquals(1, spec.firstSampleColumn);
    assertEquals("MAP_EXISTING", spec.sampleMappingPolicy);

    ProjectDocument copy = ProjectSerializer.deepCopy(doc);
    assertNotNull(copy.matrices);
    assertEquals(1, copy.matrices.size());
    assertEquals("MAP_EXISTING", copy.matrices.get(0).sampleMappingPolicy);
  }
}
