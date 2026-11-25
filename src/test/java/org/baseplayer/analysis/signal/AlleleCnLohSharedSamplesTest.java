package org.baseplayer.analysis.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.samples.SampleTrack;
import org.junit.jupiter.api.Test;

/**
 * ASCAT-style LOH matrix + Sample Comparison min shared samples.
 */
class AlleleCnLohSharedSamplesTest {

  @Test
  void sharedMinTwoKeepsOnlyGenesWithLohInAtLeastTwoSamples() {
    SampleTrack a = new SampleTrack("A");
    SampleTrack b = new SampleTrack("B");
    SampleTrack c = new SampleTrack("C");

    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.setMissingDefault(0.0);
    matrix.putLocus("SHARED", "chr1", 100, 200);
    matrix.putLocus("SINGLE", "chr1", 300, 400);
    // LOH-only collect stores only positive LOH hits.
    matrix.put("SHARED", a, 1.0);
    matrix.put("SHARED", b, 1.0);
    matrix.put("SINGLE", a, 1.0);

    SignalValueThresholds thr = SignalValueThresholds.defaultsFor(SignalValueKind.TABLE_LOH);
    GroupStatResult annotated = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL, matrix, List.of(a, b, c), thr, "Signal TABLE_LOH / MEAN");
    assertEquals(2, annotated.rows().size());

    SignalComparisonSettings settings = new SignalComparisonSettings();
    settings.setGeneLevel(true);
    settings.setMinSharedSamples(2);

    GroupStatResult filtered = SignalComparisonFilter.apply(
        annotated, settings, List.of(a, b, c), matrix, null, List.of(), thr, matrix);

    assertEquals(1, filtered.rows().size());
    assertEquals("SHARED", filtered.rows().get(0).unitId());
    assertTrue(filtered.summary().contains("1 / 2"));
  }

  @Test
  void alleleCnOnlyChannelsDetected() {
    SignalChannel minor = new SignalChannel(
        "minor",
        "Minor",
        SignalValueType.ALLELE_CN,
        org.baseplayer.io.table.MappedTableColumnRole.MINOR_CN,
        SignalValueThresholds.defaultsFor(SignalValueType.ALLELE_CN));
    SignalChannel major = new SignalChannel(
        "major",
        "Major",
        SignalValueType.ALLELE_CN,
        org.baseplayer.io.table.MappedTableColumnRole.MAJOR_CN,
        SignalValueThresholds.defaultsFor(SignalValueType.ALLELE_CN));
    SignalChannel tpm = new SignalChannel(
        "tpm",
        "TPM",
        SignalValueType.EXPRESSION,
        org.baseplayer.io.table.MappedTableColumnRole.VALUE,
        SignalValueThresholds.defaultsFor(SignalValueType.EXPRESSION));

    assertTrue(SignalChannels.isAlleleCnOnly(List.of(minor, major)));
    assertTrue(!SignalChannels.isAlleleCnOnly(List.of(tpm)));
    assertTrue(!SignalChannels.isAlleleCnOnly(List.of(minor, tpm)));
    assertTrue(!SignalChannels.isAlleleCnOnly(List.of()));
  }
}
