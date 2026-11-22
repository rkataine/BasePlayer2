package org.baseplayer.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.EnumSet;

import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VcfVariantType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Gates that prevent useless VCF region/chrom streams (unchecked samples,
 * already-loaded gene body under padded navigation).
 */
class VcfLoadSkipTest {

  @BeforeEach
  void clearTracks() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    registry.getSampleTracks().clear();
    registry.getSampleList().clear();
  }

  @AfterEach
  void clearAgain() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    registry.getSampleTracks().clear();
    registry.getSampleList().clear();
  }

  @Test
  void hasVisibleVcfSampleFalseWhenNoTracks() {
    assertFalse(VcfManager.getInstance().hasVisibleVcfSample());
  }

  @Test
  void hasVisibleVcfSampleFalseWhenAllVcfSamplesUnchecked() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    SampleTrack track = new SampleTrack("S1");
    Sample vcf = new Sample(Path.of("/tmp/s1.vcf.gz"), Sample.DataType.VCF);
    vcf.visible = false;
    track.addSample(vcf);
    registry.getSampleTracks().add(track);
    registry.getSampleList().add("S1");

    assertFalse(VcfManager.getInstance().hasVisibleVcfSample());
  }

  @Test
  void hasVisibleVcfSampleTrueWhenAnyVcfVisible() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    SampleTrack track = new SampleTrack("S1");
    Sample vcf = new Sample(Path.of("/tmp/s1.vcf.gz"), Sample.DataType.VCF);
    vcf.visible = true;
    track.addSample(vcf);
    registry.getSampleTracks().add(track);
    registry.getSampleList().add("S1");

    assertTrue(VcfManager.getInstance().hasVisibleVcfSample());
  }

  @Test
  void geneBodyCoveredEvenWhenPaddedViewExceedsCache() {
    VariantList list = new VariantList("1");
    // Prior Load-region window covering the gene body only.
    list.addLoadedRegion(100_000, 110_000);
    long geneStart = 100_500;
    long geneEnd = 109_500;
    long pad = Math.max(1000L, (geneEnd - geneStart) / 2);
    long viewStart = Math.max(1L, geneStart - pad);
    long viewEnd = geneEnd + pad;

    assertTrue(list.isRegionLoaded(geneStart, geneEnd));
    assertFalse(list.isRegionLoaded(viewStart, viewEnd));
  }

  /**
   * Load-time snapshots expand empty class slices to pass-all; post-load admitType
   * narrows the session filter — keys diverge even when the gene window is loaded.
   * Gene/region reuse must tolerate that drift (full-chrom reload still requires match).
   */
  @Test
  void postAdmitLoadIdentityDiffersFromPreAdmitPassAllSnapshot() {
    VariantFilter session = new VariantFilter();
    VariantFilter point = new VariantFilter();
    point.setAllowedTypes(EnumSet.noneOf(VcfVariantType.class));
    VariantFilter sv = new VariantFilter();
    sv.setAllowedTypes(EnumSet.noneOf(VcfVariantType.class));
    session.setClassSlices(point, sv);

    VariantFilter streamSnap = session.copy();
    streamSnap.ensureUnobservedClassSlicesPassAll();
    String preAdmitKey = streamSnap.toLoadIdentityKey();

    session.admitType(VcfVariantType.SNV);
    VariantFilter postAdmitSnap = session.copy();
    postAdmitSnap.ensureUnobservedClassSlicesPassAll();
    String postAdmitKey = postAdmitSnap.toLoadIdentityKey();

    assertNotEquals(preAdmitKey, postAdmitKey);

    VariantList list = new VariantList("12");
    list.addLoadedRegion(100_000, 110_000);
    // Cache still tagged with the stream-time key; UI now has the post-admit key.
    list.setLoadedFilterKey(preAdmitKey);
    list.setVcfCountWhenLoaded(1);

    assertTrue(list.isRegionLoaded(100_500, 109_500));
    assertNotEquals(postAdmitKey, list.getLoadedFilterKey());
    assertEquals(preAdmitKey, list.getLoadedFilterKey());
  }
}
