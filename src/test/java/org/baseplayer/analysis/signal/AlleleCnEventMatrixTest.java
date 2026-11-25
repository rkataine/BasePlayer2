package org.baseplayer.analysis.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.EffectMode;
import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupStatEngine;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.groupstat.GroupStatTest;
import org.baseplayer.analysis.pattern.SamplePatternEngine;
import org.baseplayer.io.table.MappedTableColumnRole;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;

class AlleleCnEventMatrixTest {

  @BeforeAll
  static void initJavaFx() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
      // toolkit already started
    }
  }

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

  @Test
  void buildsGeneEventPresenceWithDiploidZero() {
    List<SignalChannel> channels = List.of(minor(), major());
    FeatureValueBundle bundle = new FeatureValueBundle(channels);
    SampleTrack loh = new SampleTrack("loh");
    SampleTrack gain = new SampleTrack("gain");
    SampleTrack dip = new SampleTrack("dip");

    bundle.putLocus("TP53", "chr17", 100, 200);
    bundle.put("minor", "TP53", loh, 0.0);
    bundle.put("major", "TP53", loh, 1.0);
    bundle.put("minor", "TP53", gain, 1.0);
    bundle.put("major", "TP53", gain, 2.0);

    FeatureValueMatrix matrix = AlleleCnEventMatrix.build(
        bundle, channels, List.of(loh, gain, dip), SignalEventDirection.defaultAllowed());
    assertNotNull(matrix);
    assertEquals(0.0, matrix.getMissingDefault(), 1e-9);
    assertTrue(AlleleCnEventMatrix.isEventMatrix(matrix));

    String lohId = SignalEventDirection.rowUnitId("TP53", SignalEventDirection.LOH);
    String gainId = SignalEventDirection.rowUnitId("TP53", SignalEventDirection.GAIN);
    assertEquals(1.0, matrix.get(lohId, loh), 1e-9);
    assertEquals(0.0, matrix.get(lohId, gain), 1e-9);
    assertEquals(0.0, matrix.get(lohId, dip), 1e-9);
    assertEquals(1.0, matrix.get(gainId, gain), 1e-9);
    assertEquals(0.0, matrix.get(gainId, loh), 1e-9);
  }

  @Test
  void typeFilterOmitsDisallowedDirections() {
    List<SignalChannel> channels = List.of(minor(), major());
    FeatureValueBundle bundle = new FeatureValueBundle(channels);
    SampleTrack loh = new SampleTrack("loh");
    SampleTrack gain = new SampleTrack("gain");
    bundle.putLocus("TP53", "chr17", 100, 200);
    bundle.put("minor", "TP53", loh, 0.0);
    bundle.put("major", "TP53", loh, 2.0);
    bundle.put("minor", "TP53", gain, 1.0);
    bundle.put("major", "TP53", gain, 2.0);

    FeatureValueMatrix lohOnly = AlleleCnEventMatrix.build(
        bundle, channels, List.of(loh, gain), EnumSet.of(SignalEventDirection.LOH));
    assertNotNull(lohOnly);
    assertTrue(lohOnly.unitIds().contains(
        SignalEventDirection.rowUnitId("TP53", SignalEventDirection.LOH)));
    assertFalse(lohOnly.unitIds().contains(
        SignalEventDirection.rowUnitId("TP53", SignalEventDirection.GAIN)));
  }

  @Test
  void autoTestSelectsFisherOnEventMatrix() {
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.setMissingDefault(0.0);
    SampleTrack a = new SampleTrack("a");
    matrix.put("TP53::LOH", a, 1.0);
    matrix.touch("TP53::LOH");
    assertEquals(GroupStatTest.FISHER, GroupStatTest.AUTO.resolve(matrix, 2));
  }

  @Test
  void groupCompareKeepsLohEnrichedGene() {
    List<SignalChannel> channels = List.of(minor(), major());
    FeatureValueBundle bundle = new FeatureValueBundle(channels);

    SampleRegistry registry = new SampleRegistry();
    SampleGroup tumor = registry.createSampleGroup("Tumor");
    SampleGroup normal = registry.createSampleGroup("Normal");
    List<SampleTrack> tumorTracks = new ArrayList<>();
    List<SampleTrack> normalTracks = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      SampleTrack t = new SampleTrack("T" + i);
      tumorTracks.add(t);
      registry.getSampleTracks().add(t);
      bundle.putLocus("TP53", "chr17", 100, 200);
      bundle.put("minor", "TP53", t, 0.0);
      bundle.put("major", "TP53", t, 1.0);
    }
    for (int i = 0; i < 4; i++) {
      SampleTrack n = new SampleTrack("N" + i);
      normalTracks.add(n);
      registry.getSampleTracks().add(n);
      // no event stored → diploid / absent
    }
    registry.setTracksExclusiveGroup(tumorTracks, tumor.getId());
    registry.setTracksExclusiveGroup(normalTracks, normal.getId());

    List<SampleTrack> roster = new ArrayList<>();
    roster.addAll(tumorTracks);
    roster.addAll(normalTracks);
    FeatureValueMatrix matrix = AlleleCnEventMatrix.build(
        bundle, channels, roster, SignalEventDirection.defaultAllowed());
    assertNotNull(matrix);

    GroupStatResult raw = GroupStatEngine.compute(
        AnalysisMode.SIGNAL,
        matrix,
        registry,
        List.of(tumor.getId(), normal.getId()),
        normal.getId(),
        GroupStatTest.FISHER,
        "Allele-CN events",
        EffectMode.MEAN_DELTA,
        SignalValueType.ALLELE_CN,
        0.0);
    GroupStatResult tagged = AlleleCnEventMatrix.tagEventRows(raw);
    assertFalse(tagged.rows().isEmpty());
    GroupStatResult.Row lohRow = tagged.rows().stream()
        .filter(r -> "TP53".equals(r.geneName()) && "LOH".equals(r.direction()))
        .findFirst()
        .orElse(null);
    assertNotNull(lohRow);
    assertTrue(lohRow.pValue() != null && lohRow.pValue() < 0.05, "p=" + lohRow.pValue());
  }

  @Test
  void patternDiscoverSmokeOnEventMatrix() {
    List<SignalChannel> channels = List.of(minor(), major());
    FeatureValueBundle bundle = new FeatureValueBundle(channels);
    List<SampleTrack> tracks = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      tracks.add(new SampleTrack("s" + i));
    }
    // Enough variable gene×event units for clustering.
    for (int g = 0; g < 20; g++) {
      String gene = "G" + g;
      bundle.putLocus(gene, "chr1", g * 10L + 1, g * 10L + 10);
      for (int s = 0; s < tracks.size(); s++) {
        SampleTrack t = tracks.get(s);
        boolean blockA = s < 3;
        if (g < 10) {
          if (blockA) {
            bundle.put("minor", gene, t, 0.0);
            bundle.put("major", gene, t, 1.0);
          } else {
            bundle.put("minor", gene, t, 1.0);
            bundle.put("major", gene, t, 2.0);
          }
        } else {
          if (blockA) {
            bundle.put("minor", gene, t, 1.0);
            bundle.put("major", gene, t, 2.0);
          } else {
            bundle.put("minor", gene, t, 0.0);
            bundle.put("major", gene, t, 1.0);
          }
        }
      }
    }
    FeatureValueMatrix matrix = AlleleCnEventMatrix.build(
        bundle, channels, tracks, SignalEventDirection.defaultAllowed());
    assertNotNull(matrix);
    assertTrue(matrix.size() >= 2);

    SamplePatternEngine.Result result = SamplePatternEngine.discover(
        matrix,
        tracks,
        new SamplePatternEngine.Settings(80, 2, 0.0, true),
        EffectMode.MEAN_DELTA,
        SignalValueType.ALLELE_CN,
        GroupStatTest.FISHER);
    assertNotNull(result);
    assertNotNull(result.markers());
  }

  @Test
  void tagEventRowsSetsGeneAndDirection() {
    GroupStatResult.Row raw = new GroupStatResult.Row(
        "TP53::LOH",
        "TP53::LOH",
        "chr17",
        1L,
        100L,
        List.of(),
        0.5,
        0.01,
        0.02,
        "Fisher",
        "up");
    GroupStatResult result = new GroupStatResult(
        AnalysisMode.SIGNAL, List.of(1, 2), 1, List.of(raw), "test");
    GroupStatResult tagged = AlleleCnEventMatrix.tagEventRows(result);
    assertEquals(1, tagged.rows().size());
    assertEquals("TP53", tagged.rows().get(0).geneName());
    assertEquals("LOH", tagged.rows().get(0).direction());
  }
}
