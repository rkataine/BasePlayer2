package org.baseplayer.analysis.groupstat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.baseplayer.analysis.EffectMode;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.signal.SignalScaleInfer;
import org.baseplayer.analysis.signal.SignalValueType;

class GroupStatEngineTest {

  @Test
  void welchDetectsMeanDifference() {
    List<Double> a = List.of(1.0, 1.1, 0.9, 1.05, 0.95);
    List<Double> b = List.of(5.0, 5.2, 4.8, 5.1, 4.9);
    Double p = GroupStatEngine.welchP(a, b);
    assertNotNull(p);
    assertTrue(p > 0.0, "p must be positive, got " + p);
    assertTrue(p < 0.01, "expected small p, got " + p);
  }

  @Test
  void studentTCdfMatchesKnownValues() {
    // Two-sided p for |t|=2, df=10 is ~0.0734; studentTCdf(-|t|,df) is one-sided half.
    double oneSided = GroupStatEngine.studentTCdf(-2.0, 10.0);
    assertEquals(0.036694, oneSided, 5e-4);
    assertEquals(0.073388, 2.0 * oneSided, 1e-3);

    double mild = GroupStatEngine.studentTCdf(-1.0, 10.0);
    assertEquals(0.170447, mild, 5e-4);

    // Strong signal must stay tiny but strictly positive (old bug clamped negatives → 0).
    double strong = 2.0 * GroupStatEngine.studentTCdf(-5.0, 10.0);
    assertTrue(strong > 0.0 && strong < 1e-3, "got " + strong);
  }

  @Test
  void chiSquareSfMatchesKnownValues() {
    assertEquals(0.05, GroupStatEngine.chiSquareSf(3.8414588, 1), 2e-3);
    assertEquals(0.006738, GroupStatEngine.chiSquareSf(10.0, 2), 5e-4);
  }

  @Test
  void welchModerateSeparationIsNotClampedToZero() {
    // Mild mean shift: broken incomplete-beta used to yield negative p → clamp → 0.
    List<Double> a = List.of(1.0, 1.2, 0.8, 1.1, 0.9);
    List<Double> b = List.of(1.5, 1.7, 1.3, 1.6, 1.4);
    Double p = GroupStatEngine.welchP(a, b);
    assertNotNull(p);
    assertTrue(p > 1e-6 && p < 0.2, "expected mid-range p, got " + p);
  }

  @Test
  void moderatedDetectsMeanDifference() {
    List<Double> a = List.of(1.0, 1.1, 0.9, 1.05, 0.95);
    List<Double> b = List.of(5.0, 5.2, 4.8, 5.1, 4.9);
    List<Double> vars = List.of(0.01, 0.02, 0.015, 0.012, 0.018, 0.2);
    GroupStatEngine.ModeratedPrior prior = GroupStatEngine.estimateModeratedPrior(vars);
    Double p = GroupStatEngine.moderatedP(a, b, prior.s0sq(), prior.d0());
    assertNotNull(p);
    assertTrue(p < 0.01, "expected small moderated p, got " + p);
  }

  @Test
  void moderatedFallsBackWhenPriorMissing() {
    List<Double> a = List.of(1.0, 1.1, 0.9, 1.05, 0.95);
    List<Double> b = List.of(5.0, 5.2, 4.8, 5.1, 4.9);
    Double welch = GroupStatEngine.welchP(a, b);
    Double moderated = GroupStatEngine.moderatedP(a, b, Double.NaN, 4.0);
    assertNotNull(welch);
    assertNotNull(moderated);
    assertEquals(welch, moderated, 1e-12);
  }

  @Test
  void effectModes() {
    assertEquals(2.0, GroupStatEngine.effectValue(EffectMode.MEAN_DELTA, 5.0, 3.0), 1e-12);
    assertEquals(2.0, GroupStatEngine.effectValue(EffectMode.ALREADY_LOG, 5.0, 3.0), 1e-12);
    double fold = GroupStatEngine.effectValue(EffectMode.LOG2_FOLD, 4.0, 1.0);
    assertTrue(fold > 1.9 && fold < 2.1, "log2(4/1) ~ 2, got " + fold);
  }

  @Test
  void bhMonotonic() {
    List<GroupStatResult.Row> rows = new ArrayList<>();
    rows.add(row(0.001));
    rows.add(row(0.04));
    rows.add(row(0.2));
    GroupStatEngine.applyBenjaminiHochberg(rows);
    assertNotNull(rows.get(0).qValue());
    assertTrue(rows.get(0).qValue() <= rows.get(1).qValue() + 1e-12);
    assertTrue(rows.get(1).qValue() <= rows.get(2).qValue() + 1e-12);
    for (GroupStatResult.Row r : rows) {
      assertNotNull(r.qValue());
      assertTrue(r.qValue() >= 0.0 && r.qValue() <= 1.0, "q must be in [0,1], got " + r.qValue());
    }
  }

  @Test
  void bhKeepsQInUnitIntervalForTinyP() {
    List<GroupStatResult.Row> rows = new ArrayList<>();
    rows.add(row(1e-20));
    rows.add(row(1e-10));
    rows.add(row(0.5));
    GroupStatEngine.applyBenjaminiHochberg(rows);
    for (GroupStatResult.Row r : rows) {
      assertNotNull(r.qValue());
      assertTrue(r.qValue() >= 0.0 && r.qValue() <= 1.0, "q=" + r.qValue());
    }
  }

  @Test
  void meanAndMedian() {
    assertEquals(2.0, GroupStatEngine.mean(List.of(1.0, 2.0, 3.0)), 1e-9);
    assertEquals(2.0, GroupStatEngine.median(List.of(1.0, 2.0, 3.0)), 1e-9);
    assertEquals(2.5, GroupStatEngine.median(List.of(1.0, 2.0, 3.0, 4.0)), 1e-9);
  }

  @Test
  void scaleInferMethylationAndTpmLike() {
    double[] betas = new double[200];
    for (int i = 0; i < betas.length; i++) {
      betas[i] = (i % 100) / 100.0;
    }
    SignalScaleInfer.Suggestion meth = SignalScaleInfer.infer(betas, betas.length);
    assertEquals(SignalValueType.METHYLATION, meth.type());
    assertEquals(EffectMode.MEAN_DELTA, meth.effectMode());

    double[] tpm = {0.0, 0.5, 1.2, 3.4, 10.5, 100.2, 0.01, 2.25};
    SignalScaleInfer.Suggestion expr = SignalScaleInfer.infer(tpm, tpm.length);
    assertEquals(SignalValueType.EXPRESSION, expr.type());
    assertEquals(EffectMode.LOG2_FOLD, expr.effectMode());

    double[] logVals = {-2.0, -1.0, 0.0, 0.5, 1.2, -0.3};
    SignalScaleInfer.Suggestion logged = SignalScaleInfer.infer(logVals, logVals.length);
    assertEquals(EffectMode.ALREADY_LOG, logged.effectMode());
  }

  @Test
  void autoTestPrefersModeratedForContinuous() {
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    // no missingDefault → continuous
    assertEquals(GroupStatTest.MODERATED, GroupStatTest.AUTO.resolve(matrix, 2));
    assertEquals(GroupStatTest.RANK, GroupStatTest.AUTO.resolve(matrix, 3));
    matrix.setMissingDefault(0.0);
    assertEquals(GroupStatTest.FISHER, GroupStatTest.AUTO.resolve(matrix, 2));
  }

  private static GroupStatResult.Row row(double p) {
    return new GroupStatResult.Row(
        "g", "g", "1", 1, 10, List.of(), null, p, null, "t", "up");
  }
}
