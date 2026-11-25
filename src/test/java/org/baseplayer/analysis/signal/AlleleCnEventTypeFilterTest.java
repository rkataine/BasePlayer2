package org.baseplayer.analysis.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.io.table.MappedTableColumnRole;
import org.baseplayer.samples.SampleTrack;
import org.junit.jupiter.api.Test;

/**
 * Allele-CN annotation lists Gain/Loss/LOH; type filters and Sample Comparison follow.
 */
class AlleleCnEventTypeFilterTest {

  private static SignalChannel minor() {
    return new SignalChannel(
        "minor", "Minor", SignalValueType.ALLELE_CN, MappedTableColumnRole.MINOR_CN,
        SignalValueThresholds.defaultsFor(SignalValueType.ALLELE_CN));
  }

  private static SignalChannel major() {
    return new SignalChannel(
        "major", "Major", SignalValueType.ALLELE_CN, MappedTableColumnRole.MAJOR_CN,
        SignalValueThresholds.defaultsFor(SignalValueType.ALLELE_CN));
  }

  private static FeatureValueBundle eventBundle(SampleTrack track) {
    FeatureValueBundle bundle = new FeatureValueBundle(List.of(minor(), major()));
    bundle.putLocus("LOH_GENE", "chr1", 100, 200);
    bundle.put("minor", "LOH_GENE", track, 0.0);
    bundle.put("major", "LOH_GENE", track, 2.0);

    bundle.putLocus("GAIN_GENE", "chr1", 300, 400);
    bundle.put("minor", "GAIN_GENE", track, 1.0);
    bundle.put("major", "GAIN_GENE", track, 2.0);

    // Homozygous deletion (0/0) — Loss; hemizygous 0/1 is LOH.
    bundle.putLocus("LOSS_GENE", "chr1", 500, 600);
    bundle.put("minor", "LOSS_GENE", track, 0.0);
    bundle.put("major", "LOSS_GENE", track, 0.0);

    bundle.putLocus("HEMI_LOH", "chr1", 700, 800);
    bundle.put("minor", "HEMI_LOH", track, 0.0);
    bundle.put("major", "HEMI_LOH", track, 1.0);
    return bundle;
  }

  @Test
  void annotateIncludesAllEventTypes() {
    SampleTrack track = new SampleTrack("s1");
    FeatureValueBundle bundle = eventBundle(track);
    GroupStatResult result = SignalAnnotator.annotateBundle(
        AnalysisMode.SIGNAL, bundle, List.of(track), "test");

    Set<String> genes = result.rows().stream()
        .map(GroupStatResult.Row::geneName)
        .collect(Collectors.toSet());
    assertEquals(Set.of("LOH_GENE", "GAIN_GENE", "LOSS_GENE", "HEMI_LOH"), genes);
    assertEquals(SignalEventDirection.LOH,
        SignalEventDirection.fromResultRow(result, result.rows().stream()
            .filter(r -> "LOH_GENE".equals(r.geneName())).findFirst().orElseThrow()));
    assertEquals(SignalEventDirection.GAIN,
        SignalEventDirection.fromResultRow(result, result.rows().stream()
            .filter(r -> "GAIN_GENE".equals(r.geneName())).findFirst().orElseThrow()));
    assertEquals(SignalEventDirection.LOSS,
        SignalEventDirection.fromResultRow(result, result.rows().stream()
            .filter(r -> "LOSS_GENE".equals(r.geneName())).findFirst().orElseThrow()));
    assertEquals(SignalEventDirection.LOH,
        SignalEventDirection.fromResultRow(result, result.rows().stream()
            .filter(r -> "HEMI_LOH".equals(r.geneName())).findFirst().orElseThrow()));
  }

  @Test
  void sameGeneEmitsOneRowPerEventType() {
    List<SignalChannel> channels = List.of(minor(), major());
    FeatureValueBundle bundle = new FeatureValueBundle(channels);
    SampleTrack lohA = new SampleTrack("lohA");
    SampleTrack lohB = new SampleTrack("lohB");
    SampleTrack lossA = new SampleTrack("lossA");
    SampleTrack gainA = new SampleTrack("gainA");

    bundle.putLocus("TP53", "chr17", 100, 200);
    // 2 LOH (0/1), 1 homozygous Loss (0/0), 1 Gain (1/2)
    bundle.put("minor", "TP53", lohA, 0.0);
    bundle.put("major", "TP53", lohA, 1.0);
    bundle.put("minor", "TP53", lohB, 0.0);
    bundle.put("major", "TP53", lohB, 2.0);
    bundle.put("minor", "TP53", lossA, 0.0);
    bundle.put("major", "TP53", lossA, 0.0);
    bundle.put("minor", "TP53", gainA, 1.0);
    bundle.put("major", "TP53", gainA, 2.0);

    GroupStatResult result = SignalAnnotator.annotateBundle(
        AnalysisMode.SIGNAL, bundle, List.of(lohA, lohB, lossA, gainA), "test");

    assertEquals(3, result.rows().size());
    Map<String, Integer> nByDir = new java.util.HashMap<>();
    for (GroupStatResult.Row row : result.rows()) {
      assertEquals("TP53", row.geneName());
      SignalEventDirection dir = SignalEventDirection.fromResultRow(result, row);
      nByDir.put(dir.name(), row.groups() != null ? row.groups().size() : 0);
    }
    assertEquals(2, nByDir.get("LOH").intValue());
    assertEquals(1, nByDir.get("LOSS").intValue());
    assertEquals(1, nByDir.get("GAIN").intValue());
  }

  @Test
  void filterResultKeepsOnlyAllowedDirections() {
    SampleTrack track = new SampleTrack("s1");
    FeatureValueBundle bundle = eventBundle(track);
    GroupStatResult all = SignalAnnotator.annotateBundle(
        AnalysisMode.SIGNAL, bundle, List.of(track), "test");

    GroupStatResult lohOnly = SignalEventDirection.filterResult(
        all, EnumSet.of(SignalEventDirection.LOH));
    Set<String> lohGenes = lohOnly.rows().stream()
        .map(GroupStatResult.Row::geneName)
        .collect(Collectors.toSet());
    assertEquals(Set.of("LOH_GENE", "HEMI_LOH"), lohGenes);

    GroupStatResult gainLoss = SignalEventDirection.filterResult(
        all, EnumSet.of(SignalEventDirection.GAIN, SignalEventDirection.LOSS));
    Set<String> genes = gainLoss.rows().stream()
        .map(GroupStatResult.Row::geneName)
        .collect(Collectors.toSet());
    assertEquals(Set.of("GAIN_GENE", "LOSS_GENE"), genes);
  }

  @Test
  void sampleComparisonPresenceFollowsAllowedDirections() {
    SampleTrack a = new SampleTrack("A");
    SampleTrack b = new SampleTrack("B");
    List<SignalChannel> channels = List.of(minor(), major());
    FeatureValueBundle bundle = new FeatureValueBundle(channels);

    // SHARED: A=LOH, B=LOH → present for both when LOH allowed
    bundle.putLocus("SHARED_LOH", "chr1", 100, 200);
    bundle.put("minor", "SHARED_LOH", a, 0.0);
    bundle.put("major", "SHARED_LOH", a, 2.0);
    bundle.put("minor", "SHARED_LOH", b, 0.0);
    bundle.put("major", "SHARED_LOH", b, 2.0);

    // MIXED: A=LOH, B=GAIN → shared only when both types allowed; LOH-only → one sample
    bundle.putLocus("MIXED", "chr1", 300, 400);
    bundle.put("minor", "MIXED", a, 0.0);
    bundle.put("major", "MIXED", a, 2.0);
    bundle.put("minor", "MIXED", b, 1.0);
    bundle.put("major", "MIXED", b, 2.0);

    GroupStatResult annotated = SignalAnnotator.annotateBundle(
        AnalysisMode.SIGNAL, bundle, List.of(a, b), "test");
    // SHARED_LOH → one LOH row; MIXED → LOH row + GAIN row
    assertEquals(3, annotated.rows().size());

    SignalComparisonSettings settings = new SignalComparisonSettings();
    settings.setGeneLevel(true);
    settings.setMinSharedSamples(2);

    GroupStatResult allTypes = SignalComparisonFilter.apply(
        annotated,
        settings,
        List.of(a, b),
        null,
        bundle,
        channels,
        null,
        null,
        SignalEventDirection.defaultAllowed());
    // Only SHARED_LOH has the same event in ≥2 samples
    assertEquals(1, allTypes.rows().size());
    assertEquals("SHARED_LOH", allTypes.rows().get(0).geneName());

    GroupStatResult lohOnly = SignalComparisonFilter.apply(
        annotated,
        settings,
        List.of(a, b),
        null,
        bundle,
        channels,
        null,
        null,
        EnumSet.of(SignalEventDirection.LOH));
    assertEquals(1, lohOnly.rows().size());
    assertEquals("SHARED_LOH", lohOnly.rows().get(0).geneName());
  }

  @Test
  void fromBundleSampleClassifiesPerSample() {
    SampleTrack track = new SampleTrack("s1");
    List<SignalChannel> channels = List.of(minor(), major());
    FeatureValueBundle bundle = eventBundle(track);
    assertEquals(
        SignalEventDirection.LOH,
        SignalEventDirection.fromBundleSample(bundle, channels, "LOH_GENE", track));
    assertEquals(
        SignalEventDirection.GAIN,
        SignalEventDirection.fromBundleSample(bundle, channels, "GAIN_GENE", track));
    assertEquals(
        SignalEventDirection.LOSS,
        SignalEventDirection.fromBundleSample(bundle, channels, "LOSS_GENE", track));
    assertEquals(
        SignalEventDirection.LOH,
        SignalEventDirection.fromBundleSample(bundle, channels, "HEMI_LOH", track));
    assertEquals(
        SignalEventDirection.NONE,
        SignalEventDirection.fromBundleSample(bundle, channels, "MISSING", track));
  }

  @Test
  void sourceSpecDefaultsAllowAllEventTypes() {
    SignalSourceSpec spec = new SignalSourceSpec();
    assertTrue(spec.allowsEventDirection(SignalEventDirection.GAIN));
    assertTrue(spec.allowsEventDirection(SignalEventDirection.LOSS));
    assertTrue(spec.allowsEventDirection(SignalEventDirection.LOH));
    assertFalse(spec.allowsEventDirection(SignalEventDirection.NONE));

    spec.setAllowedEventDirections(EnumSet.of(SignalEventDirection.LOH));
    assertFalse(spec.allowsEventDirection(SignalEventDirection.GAIN));
    assertTrue(spec.allowsEventDirection(SignalEventDirection.LOH));
  }
}
