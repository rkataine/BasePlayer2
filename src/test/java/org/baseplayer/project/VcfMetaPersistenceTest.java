package org.baseplayer.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.MateBreakpointIndex;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfVariantType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VcfMetaPersistenceTest {

  @TempDir
  Path tempDir;

  @BeforeEach
  void clearTracks() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    registry.getSampleTracks().clear();
    registry.getSampleList().clear();
  }

  @AfterEach
  void clearAgain() {
    clearTracks();
  }

  @Test
  void mateIndexRoundTripRebindsByTrackName() throws Exception {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    SampleTrack track = new SampleTrack("TumorA");
    registry.getSampleTracks().add(track);
    registry.getSampleList().add("TumorA");

    VariantNode.SampleCall call =
        new VariantNode.SampleCall(0, null, "0/1", 30.0, 40, 0.45, null);
    MateBreakpointIndex.Hit hit = new MateBreakpointIndex.Hit(
        200L, "1", 100L, "N", "A]2:200]", VcfVariantType.SV_BREAKEND, "bnd1", List.of(call));
    MateBreakpointIndex index = MateBreakpointIndex.fromHits(
        List.of(new MateBreakpointIndex.IndexedHit("2", hit)));

    Path mateFile = tempDir.resolve("mates-test.bpm.zst");
    VariantCacheStore.writeMateIndex(mateFile, index, registry);
    assertTrue(Files.isRegularFile(mateFile));

    // Simulate restore with same track name at a different index.
    registry.getSampleTracks().clear();
    registry.getSampleList().clear();
    SampleTrack other = new SampleTrack("Other");
    SampleTrack restored = new SampleTrack("TumorA");
    registry.getSampleTracks().add(other);
    registry.getSampleTracks().add(restored);
    registry.getSampleList().add("Other");
    registry.getSampleList().add("TumorA");

    MateBreakpointIndex loaded = VariantCacheStore.readMateIndex(mateFile, registry);
    assertFalse(loaded.isEmpty());
    List<MateBreakpointIndex.Hit> hits = loaded.query("2", 200L, 200L);
    assertEquals(1, hits.size());
    assertEquals(1, hits.get(0).calls().get(0).getTrackIndex());
    assertEquals("0/1", hits.get(0).calls().get(0).gt);
  }

  @Test
  void fingerprintMatchesRejectsSizeDrift() throws Exception {
    Path vcf = tempDir.resolve("sample.vcf.gz");
    Files.writeString(vcf, "##fileformat=VCFv4.2\n");
    File file = vcf.toFile();

    ProjectDocument.VcfMetaSpec meta = new ProjectDocument.VcfMetaSpec();
    meta.path = file.getAbsolutePath();
    meta.size = file.length();
    meta.mtime = file.lastModified();
    assertTrue(VcfMetaPersistence.fingerprintMatches(meta, file));

    meta.size = file.length() + 1;
    assertFalse(VcfMetaPersistence.fingerprintMatches(meta, file));
  }

  @Test
  void listedVcfPathsIncludesUncheckedSamples() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    SampleTrack track = new SampleTrack("S1");
    Sample vcf = new Sample(tempDir.resolve("hidden.vcf.gz"), Sample.DataType.VCF);
    vcf.visible = false;
    track.addSample(vcf);
    registry.getSampleTracks().add(track);
    registry.getSampleList().add("S1");

    assertTrue(VcfMetaPersistence.listedVcfPaths().contains(
        vcf.getPath().toAbsolutePath().normalize().toString()));
  }

  @Test
  void mateFileNameIsStable() {
    String a = VcfMetaPersistence.mateFileName("/data/a.vcf.gz");
    String b = VcfMetaPersistence.mateFileName("/data/a.vcf.gz");
    assertEquals(a, b);
    assertNotNull(a);
    assertTrue(a.startsWith("mates-"));
    assertTrue(a.endsWith(".bpm.zst"));
  }
}
