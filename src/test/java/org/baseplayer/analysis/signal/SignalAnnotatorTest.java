package org.baseplayer.analysis.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupStatResult;

class SignalAnnotatorTest {

  @Test
  void annotatesAndFiltersByMinValue() {
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.putLocus("GENEA", "chr1", 100, 200);
    matrix.putLocus("GENEB", "chr1", 300, 400);
    matrix.putLocus("GENEC", "chr2", 50, 80);
    // No SampleTrack — values via missing path: touch + put needs a track.
    // Use null-safe empty tracks and store via a dummy approach: valuesFor empty
    // when no tracks. Instead put with a fake track identity by using put only
    // when we have tracks. For unit test, build summaries from matrix with
    // missingDefault and empty track list falls back to matrix.tracks().
    org.baseplayer.samples.SampleTrack track = newFakeTrack("s1");
    matrix.put("GENEA", track, 0.2);
    matrix.put("GENEB", track, 0.9);
    matrix.put("GENEC", track, 0.6);

    SignalValueThresholds thr = new SignalValueThresholds();
    thr.setEnabled(true);
    thr.setMinValue(0.5);

    GroupStatResult result = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL, matrix, List.of(track), thr, "test");

    assertTrue(SignalAnnotator.isAnnotationResult(result));
    assertEquals(2, result.rows().size());
    assertEquals("GENEB", result.rows().get(0).geneName());
    assertEquals("GENEC", result.rows().get(1).geneName());
    assertEquals(0.9, SignalAnnotator.annotationValue(result.rows().get(0)), 1e-9);
  }

  @Test
  void excludesValuesNearMiddle() {
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    org.baseplayer.samples.SampleTrack track = newFakeTrack("s1");
    matrix.putLocus("DIPLOID", "chr1", 100, 200);
    matrix.putLocus("GAIN", "chr1", 300, 400);
    matrix.putLocus("LOSS", "chr1", 500, 600);
    matrix.put("DIPLOID", track, 2.0);
    matrix.put("GAIN", track, 3.5);
    matrix.put("LOSS", track, 0.8);

    SignalValueThresholds thr = new SignalValueThresholds();
    thr.setEnabled(true);
    thr.setMiddle(2.0);
    thr.setMinAbsFromMiddle(0.5);

    GroupStatResult result = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL, matrix, List.of(track), thr, "cn");

    assertEquals(2, result.rows().size());
    assertEquals(List.of("GAIN", "LOSS"),
        result.rows().stream().map(GroupStatResult.Row::geneName).toList());
  }

  @Test
  void sortsByGenomicPosition() {
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    org.baseplayer.samples.SampleTrack track = newFakeTrack("s1");
    matrix.putLocus("Z", "chr2", 10, 20);
    matrix.putLocus("A", "chr1", 500, 600);
    matrix.putLocus("B", "chr1", 100, 200);
    matrix.put("Z", track, 1.0);
    matrix.put("A", track, 1.0);
    matrix.put("B", track, 1.0);

    GroupStatResult result = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL, matrix, List.of(track), new SignalValueThresholds(), "test");

    assertEquals(List.of("B", "A", "Z"),
        result.rows().stream().map(GroupStatResult.Row::geneName).toList());
  }

  @Test
  void manyPassingSamplesUseCohortPlusTopKNotOnePerSample() {
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.putLocus("GENE", "chr1", 100, 200);
    List<org.baseplayer.samples.SampleTrack> tracks = new java.util.ArrayList<>();
    int n = SignalAnnotator.MAX_EXPAND_SAMPLES + 15;
    for (int i = 0; i < n; i++) {
      org.baseplayer.samples.SampleTrack t = newFakeTrack("s" + i);
      tracks.add(t);
      matrix.put("GENE", t, 1.0 + i);
    }

    GroupStatResult result = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL, matrix, tracks, new SignalValueThresholds(), "test");

    assertEquals(1, result.rows().size());
    // Cohort + at most MAX_EXPAND_SAMPLES extremes (not n summaries).
    assertEquals(
        1 + SignalAnnotator.MAX_EXPAND_SAMPLES,
        result.rows().get(0).groups().size());
    assertTrue(result.rows().get(0).groups().get(0).groupName().startsWith("Cohort"));
  }

  private static org.baseplayer.samples.SampleTrack newFakeTrack(String name) {
    return new org.baseplayer.samples.SampleTrack(name);
  }
}
