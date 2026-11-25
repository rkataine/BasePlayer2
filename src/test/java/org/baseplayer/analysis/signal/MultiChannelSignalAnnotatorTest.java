package org.baseplayer.analysis.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.baseplayer.io.table.MappedTableColumnRole;
import org.baseplayer.samples.SampleTrack;
import org.junit.jupiter.api.Test;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.groupstat.GroupStatResult;

class MultiChannelSignalAnnotatorTest {

  @Test
  void alleleCnDefaultsAreOffWithSuggestedMiddle() {
    SignalValueThresholds thr = SignalValueThresholds.defaultsFor(SignalValueType.ALLELE_CN);
    assertFalse(thr.isEnabled());
    assertEquals(1.0, thr.getMiddle(), 1e-9);
    assertEquals(0.0, thr.getMinAbsFromMiddle(), 1e-9);
    // Filter off → all finite values pass until the user enables Filter + Min|Δ|.
    assertTrue(thr.passes(1.0));
    assertTrue(thr.passes(0.0));
    assertTrue(thr.passes(2.0));
  }

  @Test
  void methylationDefaultsAreOffWithoutBoundValues() {
    SignalValueThresholds thr = SignalValueThresholds.defaultsFor(SignalValueType.METHYLATION);
    assertFalse(thr.isEnabled());
    assertEquals(null, thr.getMinValue());
    assertEquals(null, thr.getMaxValue());
    assertTrue(thr.passes(0.5));
    assertTrue(thr.passes(-1.0));
  }

  @Test
  void annotateBundleKeepsDeviationDropsPureDiploid() {
    // Diploid drop requires Filter + Min |Δ| (filters are off by default).
    SignalValueThresholds alleleFilter = SignalValueThresholds.defaultsFor(SignalValueType.ALLELE_CN);
    alleleFilter.setEnabled(true);
    alleleFilter.setMinAbsFromMiddle(0.5);
    SignalChannel minor = new SignalChannel(
        "minor", "Minor", SignalValueType.ALLELE_CN, MappedTableColumnRole.MINOR_CN,
        alleleFilter);
    SignalChannel major = new SignalChannel(
        "major", "Major", SignalValueType.ALLELE_CN, MappedTableColumnRole.MAJOR_CN,
        alleleFilter.copy());
    FeatureValueBundle bundle = new FeatureValueBundle(List.of(minor, major));
    SampleTrack track = new SampleTrack("s1");

    bundle.putLocus("DIPLOID", "chr1", 100, 200);
    bundle.put("minor", "DIPLOID", track, 1.0);
    bundle.put("major", "DIPLOID", track, 1.0);

    bundle.putLocus("LOH", "chr1", 300, 400);
    bundle.put("minor", "LOH", track, 0.0);
    bundle.put("major", "LOH", track, 2.0);

    bundle.putLocus("GAIN", "chr1", 500, 600);
    bundle.put("minor", "GAIN", track, 1.0);
    bundle.put("major", "GAIN", track, 2.0);

    GroupStatResult result = SignalAnnotator.annotateBundle(
        AnalysisMode.SIGNAL, bundle, List.of(track), "test");

    assertTrue(result.hasSignalChannels());
    assertEquals(2, result.signalChannels().size());
    assertEquals(2, result.rows().size());
    assertEquals(List.of("LOH", "GAIN"),
        result.rows().stream().map(GroupStatResult.Row::geneName).toList());
    // Allele-CN rows use gene::EVENT unitIds for per-type channel means.
    assertEquals(0.0, result.channelValue(SignalEventDirection.rowUnitId("LOH", SignalEventDirection.LOH), "minor"), 1e-9);
    assertEquals(2.0, result.channelValue(SignalEventDirection.rowUnitId("LOH", SignalEventDirection.LOH), "major"), 1e-9);
    assertEquals(1.0, result.channelValue(SignalEventDirection.rowUnitId("GAIN", SignalEventDirection.GAIN), "minor"), 1e-9);
    assertEquals(2.0, result.channelValue(SignalEventDirection.rowUnitId("GAIN", SignalEventDirection.GAIN), "major"), 1e-9);
  }

  @Test
  void signalChannelsFromAlleleRoles() {
    // Smoke: channel factory helpers exist and ALLELE_CN types apply.
    SignalChannel ch = new SignalChannel(
        "minor", "nMinor", SignalValueType.ALLELE_CN, MappedTableColumnRole.MINOR_CN, null);
    assertEquals(SignalValueType.ALLELE_CN, ch.type());
    assertFalse(ch.thresholds().isEnabled());
    assertEquals(1.0, ch.thresholds().getMiddle(), 1e-9);
  }

  @Test
  void alleleCnFormatsAsInteger() {
    assertEquals("0", SignalValueType.ALLELE_CN.format(0.0));
    assertEquals("1", SignalValueType.ALLELE_CN.format(1.0));
    assertEquals("2", SignalValueType.ALLELE_CN.format(1.6));
    assertEquals("1.234", SignalValueType.RAW.format(1.234));
    assertEquals(2.0, SignalValueType.ALLELE_CN.normalize(1.6), 1e-9);
    assertEquals(1.6, SignalValueType.RAW.normalize(1.6), 1e-9);
  }

  @Test
  void multiSampleExpandOmitsDiploidSamples() {
    SignalChannel minor = new SignalChannel(
        "minor", "Minor", SignalValueType.ALLELE_CN, MappedTableColumnRole.MINOR_CN,
        SignalValueThresholds.defaultsFor(SignalValueType.ALLELE_CN));
    SignalChannel major = new SignalChannel(
        "major", "Major", SignalValueType.ALLELE_CN, MappedTableColumnRole.MAJOR_CN,
        SignalValueThresholds.defaultsFor(SignalValueType.ALLELE_CN));
    FeatureValueBundle bundle = new FeatureValueBundle(List.of(minor, major));
    SampleTrack loh = new SampleTrack("loh");
    SampleTrack dip1 = new SampleTrack("dip1");
    SampleTrack dip2 = new SampleTrack("dip2");

    bundle.putLocus("GENE", "chr1", 100, 200);
    // Sparse collect: only deviant cells stored (diploid 1/1 omitted).
    bundle.put("minor", "GENE", loh, 0.0);
    bundle.put("major", "GENE", loh, 2.0);

    GroupStatResult result = SignalAnnotator.annotateBundle(
        AnalysisMode.SIGNAL, bundle, List.of(loh, dip1, dip2), "test");

    assertEquals(1, result.rows().size());
    assertEquals(1, result.rows().get(0).groups().size());
    assertEquals("loh", result.rows().get(0).groups().get(0).groupName());
    assertFalse(bundle.matrix("minor").hasValue("GENE", dip1));
    assertFalse(bundle.matrix("major").hasValue("GENE", dip2));
  }
}
