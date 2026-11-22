package org.baseplayer.analysis.signal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupStatResult;

/**
 * After a group Run, Signals listing stays annotation — comparison is separate.
 */
class SignalDisplayResultSeparationTest {

  @AfterEach
  void clearSession() {
    AnalysisSession.get().clear();
  }

  @Test
  void signalDisplayStaysAnnotationAfterGroupRun() {
    org.baseplayer.samples.SampleTrack track = new org.baseplayer.samples.SampleTrack("s1");
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.putLocus("GENEA", "chr1", 100, 200);
    matrix.put("GENEA", track, 0.8);

    GroupStatResult annotation = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL, matrix, List.of(track), new SignalValueThresholds(), "test");
    assertTrue(SignalAnnotator.isAnnotationResult(annotation));

    AnalysisSession.get().putAnnotation(
        AnalysisMode.SIGNAL, annotation, matrix, new SignalValueThresholds());

    GroupStatResult comparison = new GroupStatResult(
        AnalysisMode.SIGNAL,
        List.of(0, 1),
        0,
        annotation.rows(),
        "Compared 1 unit",
        "Group test");
    AnalysisSession.get().putResult(AnalysisMode.SIGNAL, comparison, matrix);

    GroupStatResult display = AnalysisSession.get().getSignalDisplayResult();
    assertNotNull(display);
    assertTrue(SignalAnnotator.isAnnotationResult(display));
    assertSame(AnalysisSession.get().getAnnotationResult(), display);

    GroupStatResult group = AnalysisSession.get().getFilteredResult(AnalysisMode.SIGNAL);
    assertNotNull(group);
    assertTrue(!SignalAnnotator.isAnnotationResult(group));
  }
}
