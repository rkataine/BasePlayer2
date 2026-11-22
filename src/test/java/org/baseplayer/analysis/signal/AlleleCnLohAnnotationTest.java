package org.baseplayer.analysis.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.baseplayer.analysis.allelecn.AlleleCnSegment;
import org.baseplayer.analysis.allelecn.LohFromAlleleCn;
import org.baseplayer.samples.SampleTrack;
import org.junit.jupiter.api.Test;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupStatResult;

class AlleleCnLohAnnotationTest {

  @Test
  void diploidOneOneIsNotLoh() {
    LohFromAlleleCn policy = LohFromAlleleCn.binary();
    AlleleCnSegment diploid = new AlleleCnSegment("1", 100, 200, "s1", 1.0, 1.0, 2.0, null);
    AlleleCnSegment loh = new AlleleCnSegment("1", 100, 200, "s1", 0.0, 2.0, 2.0, null);

    assertFalse(policy.isLoh(diploid));
    assertEquals(0.0, policy.score(diploid), 1e-9);
    assertTrue(policy.isLoh(loh));
    assertEquals(1.0, policy.score(loh), 1e-9);
  }

  @Test
  void lohOnlyDefaultsKeepAnyPositiveMean() {
    SignalValueThresholds thr = SignalValueThresholds.defaultsFor(SignalValueKind.TABLE_LOH);
    assertTrue(thr.isLohOnly());
    assertFalse(thr.passes(0.0));
    assertTrue(thr.passes(0.01));
    assertTrue(thr.passes(1.0));
  }

  @Test
  void annotateListsOnlyLohGenesAndFormatsHits() {
    SampleTrack a = new SampleTrack("A");
    SampleTrack b = new SampleTrack("B");
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.setMissingDefault(0.0);
    matrix.putLocus("LOH_GENE", "chr1", 100, 200);
    matrix.putLocus("DIPLOID_GENE", "chr1", 300, 400);
    // LOH-only collect would never put DIPLOID_GENE; simulate that.
    matrix.put("LOH_GENE", a, 1.0);
    matrix.put("LOH_GENE", b, 0.0);
    matrix.put("DIPLOID_GENE", a, 0.0);
    matrix.put("DIPLOID_GENE", b, 0.0);

    SignalValueThresholds thr = SignalValueThresholds.defaultsFor(SignalValueKind.TABLE_LOH);
    GroupStatResult result = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL,
        matrix,
        List.of(a, b),
        thr,
        "Signal TABLE_LOH / MEAN");

    assertTrue(SignalAnnotator.isLohAnnotation(result));
    assertEquals(1, result.rows().size());
    assertEquals("LOH_GENE", result.rows().get(0).geneName());
    // Expand lists deviant samples only — single LOH hit formats as "LOH".
    assertEquals(1, result.rows().get(0).groups().size());
    assertEquals("LOH", SignalAnnotator.formatLohHits(result.rows().get(0)));
    assertEquals("LOH", SignalAnnotator.formatLohSampleValue(1.0));
    assertEquals("—", SignalAnnotator.formatLohSampleValue(0.0));
  }

  @Test
  void singleSampleLohShowsLabelNotDecimal() {
    SampleTrack track = new SampleTrack("s1");
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.putLocus("G", "chr1", 10, 20);
    matrix.put("G", track, 1.0);

    GroupStatResult result = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL,
        matrix,
        List.of(track),
        SignalValueThresholds.defaultsFor(SignalValueKind.TABLE_LOH),
        "Signal TABLE_LOH / MEAN");

    assertEquals("LOH", SignalAnnotator.formatAnnotationValue(result.rows().get(0), true));
  }
}
