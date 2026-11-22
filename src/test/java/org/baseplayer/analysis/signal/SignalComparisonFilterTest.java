package org.baseplayer.analysis.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.samples.SampleTag;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.variant.VariantFilter;
import org.junit.jupiter.api.Test;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupStatResult;

class SignalComparisonFilterTest {

  @Test
  void sharedSampleMinDropsSingletonEvents() {
    SampleTrack a = new SampleTrack("A");
    SampleTrack b = new SampleTrack("B");
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.putLocus("ONLY_A", "chr1", 100, 200);
    matrix.put("ONLY_A", a, 2.0);
    matrix.putLocus("BOTH", "chr1", 300, 400);
    matrix.put("BOTH", a, 2.0);
    matrix.put("BOTH", b, 2.0);

    GroupStatResult annotated = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL,
        matrix,
        List.of(a, b),
        new SignalValueThresholds(),
        "test");

    SignalComparisonSettings settings = new SignalComparisonSettings();
    settings.setMinSharedSamples(2);

    GroupStatResult filtered = SignalComparisonFilter.apply(
        annotated, settings, List.of(a, b), matrix, null, null, null);
    assertEquals(1, filtered.rows().size());
    assertEquals("BOTH", filtered.rows().get(0).geneName());
    assertTrue(filtered.summary().contains("1 / 2"));
  }

  @Test
  void outOfThresholdValueCountsAsAbsentForSharedN() {
    SampleTrack a = new SampleTrack("A");
    SampleTrack b = new SampleTrack("B");
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.putLocus("GENE", "chr1", 100, 200);
    matrix.put("GENE", a, 0.2);
    matrix.put("GENE", b, 0.9);

    // Annotate with filters OFF → both samples listed on the row.
    GroupStatResult annotated = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL, matrix, List.of(a, b), new SignalValueThresholds(), "test");
    assertEquals(1, annotated.rows().size());
    assertEquals(2, annotated.rows().get(0).groups().size());

    SignalValueThresholds thr = new SignalValueThresholds();
    thr.setEnabled(true);
    thr.setMinValue(0.5);

    SignalComparisonSettings settings = new SignalComparisonSettings();
    settings.setMinSharedSamples(2);

    // Sample Comparison presence uses matrix + thresholds: A (0.2) is absent → drop.
    GroupStatResult filtered = SignalComparisonFilter.apply(
        annotated, settings, List.of(a, b), matrix, null, null, thr);
    assertEquals(0, filtered.rows().size());
    assertTrue(filtered.summary().contains("0 / 1"));

    Set<SampleTrack> present = SignalComparisonFilter.presentForUnit(
        "GENE", List.of(a, b), matrix, null, null, thr);
    assertEquals(1, present.size());
    assertTrue(present.contains(b));
    assertFalse(present.contains(a));
  }

  @Test
  void withoutThresholdsBothSamplesPresentKeepsGene() {
    SampleTrack a = new SampleTrack("A");
    SampleTrack b = new SampleTrack("B");
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.putLocus("GENE", "chr1", 100, 200);
    matrix.put("GENE", a, 0.2);
    matrix.put("GENE", b, 0.9);

    SignalValueThresholds thrOff = new SignalValueThresholds();
    GroupStatResult annotated = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL, matrix, List.of(a, b), thrOff, "test");

    SignalComparisonSettings settings = new SignalComparisonSettings();
    settings.setMinSharedSamples(2);

    GroupStatResult filtered = SignalComparisonFilter.apply(
        annotated, settings, List.of(a, b), matrix, null, null, thrOff);
    assertEquals(1, filtered.rows().size());
    assertEquals("GENE", filtered.rows().get(0).geneName());
  }

  @Test
  void filterMatrixUnitsRespectsValueThresholds() {
    SampleTrack a = new SampleTrack("A");
    SampleTrack b = new SampleTrack("B");
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.putLocus("ONLY_HIGH", "chr1", 100, 200);
    matrix.put("ONLY_HIGH", a, 0.2);
    matrix.put("ONLY_HIGH", b, 0.9);
    matrix.putLocus("BOTH_HIGH", "chr1", 300, 400);
    matrix.put("BOTH_HIGH", a, 0.8);
    matrix.put("BOTH_HIGH", b, 0.9);

    SignalValueThresholds thr = new SignalValueThresholds();
    thr.setEnabled(true);
    thr.setMinValue(0.5);

    SignalComparisonSettings settings = new SignalComparisonSettings();
    settings.setMinSharedSamples(2);

    FeatureValueMatrix filtered = SignalComparisonFilter.filterMatrixUnits(
        matrix, settings, List.of(a, b), null, null, thr);
    assertEquals(1, filtered.unitIds().size());
    assertTrue(filtered.unitIds().contains("BOTH_HIGH"));
  }

  @Test
  void flankingWindowUnionsNearbyGenes() {
    SampleTrack a = new SampleTrack("A");
    SampleTrack b = new SampleTrack("B");
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.putLocus("G1", "chr1", 100, 150);
    matrix.put("G1", a, 2.0);
    matrix.putLocus("G2", "chr1", 180, 220);
    matrix.put("G2", b, 2.0);
    matrix.putLocus("FAR", "chr1", 5000, 5100);
    matrix.put("FAR", a, 2.0);

    GroupStatResult annotated = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL,
        matrix,
        List.of(a, b),
        new SignalValueThresholds(),
        "test");

    SignalComparisonSettings settings = new SignalComparisonSettings();
    settings.setGeneLevel(false);
    settings.setComparisonWindowBp(100);
    settings.setMinSharedSamples(2);

    GroupStatResult filtered = SignalComparisonFilter.apply(
        annotated, settings, List.of(a, b), matrix, null, null, null);
    assertEquals(2, filtered.rows().size());
    assertTrue(filtered.rows().stream().anyMatch(r -> "G1".equals(r.geneName())));
    assertTrue(filtered.rows().stream().anyMatch(r -> "G2".equals(r.geneName())));
  }

  @Test
  void absentTagRoleDropsEventsPresentInTaggedSample() {
    SampleTrack child = new SampleTrack("child");
    child.addTag(SampleTag.CHILD);
    SampleTrack parent = new SampleTrack("parent");
    parent.addTag(SampleTag.PARENTAL);

    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.putLocus("SHARED", "chr1", 100, 200);
    matrix.put("SHARED", child, 2.0);
    matrix.put("SHARED", parent, 2.0);
    matrix.putLocus("CHILD_ONLY", "chr1", 300, 400);
    matrix.put("CHILD_ONLY", child, 2.0);

    GroupStatResult annotated = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL,
        matrix,
        List.of(child, parent),
        new SignalValueThresholds(),
        "test");

    SignalComparisonSettings settings = new SignalComparisonSettings();
    Map<SampleTag, VariantFilter.GroupRole> roles = new EnumMap<>(SampleTag.class);
    roles.put(SampleTag.CHILD, VariantFilter.GroupRole.PRESENT);
    roles.put(SampleTag.PARENTAL, VariantFilter.GroupRole.ABSENT);
    settings.setTagRoles(roles);

    GroupStatResult filtered = SignalComparisonFilter.apply(
        annotated, settings, List.of(child, parent), matrix, null, null, null);
    assertEquals(1, filtered.rows().size());
    assertEquals("CHILD_ONLY", filtered.rows().get(0).geneName());
  }

  @Test
  void singlePresentTagIsSiteLevelForSignal() {
    SampleTrack child = new SampleTrack("child");
    child.addTag(SampleTag.CHILD);
    SampleTrack other = new SampleTrack("other");

    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.putLocus("WITH_CHILD", "chr1", 100, 200);
    matrix.put("WITH_CHILD", child, 2.0);
    matrix.put("WITH_CHILD", other, 2.0);
    matrix.putLocus("NO_CHILD", "chr1", 300, 400);
    matrix.put("NO_CHILD", other, 2.0);

    GroupStatResult annotated = SignalAnnotator.annotate(
        AnalysisMode.SIGNAL,
        matrix,
        List.of(child, other),
        new SignalValueThresholds(),
        "test");

    SignalComparisonSettings settings = new SignalComparisonSettings();
    Map<SampleTag, VariantFilter.GroupRole> roles = new EnumMap<>(SampleTag.class);
    roles.put(SampleTag.CHILD, VariantFilter.GroupRole.PRESENT);
    settings.setTagRoles(roles);

    GroupStatResult filtered = SignalComparisonFilter.apply(
        annotated, settings, List.of(child, other), matrix, null, null, null);
    assertEquals(1, filtered.rows().size());
    assertEquals("WITH_CHILD", filtered.rows().get(0).geneName());
  }

  @Test
  void filterMatrixUnitsRemovesFailingGenes() {
    SampleTrack a = new SampleTrack("A");
    SampleTrack b = new SampleTrack("B");
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.putLocus("ONLY_A", "chr1", 100, 200);
    matrix.put("ONLY_A", a, 2.0);
    matrix.putLocus("BOTH", "chr1", 300, 400);
    matrix.put("BOTH", a, 2.0);
    matrix.put("BOTH", b, 2.0);

    SignalComparisonSettings settings = new SignalComparisonSettings();
    settings.setMinSharedSamples(2);

    FeatureValueMatrix filtered =
        SignalComparisonFilter.filterMatrixUnits(matrix, settings, List.of(a, b));
    assertEquals(1, filtered.unitIds().size());
    assertTrue(filtered.unitIds().contains("BOTH"));
  }
}
