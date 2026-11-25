package org.baseplayer.analysis.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.EffectMode;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.groupstat.GroupStatTest;
import org.baseplayer.analysis.signal.SignalValueType;

import javafx.application.Platform;

class SamplePatternEngineTest {

  @BeforeAll
  static void initJavaFx() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
      // toolkit already started
    }
  }

  @Test
  void recoversTwoSampleBlocks() {
    // 6 samples: 0-2 high on genes A*, 3-5 high on genes B*
    List<SampleTrack> tracks = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      tracks.add(new SampleTrack("S" + i));
    }
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    for (int g = 0; g < 40; g++) {
      String id = "A" + g;
      for (int s = 0; s < 6; s++) {
        double v = s < 3 ? 10.0 + 0.01 * g + 0.1 * s : 0.1 + 0.01 * s;
        matrix.put(id, tracks.get(s), v);
      }
      matrix.putLocus(id, "1", g + 1, g + 10);
    }
    for (int g = 0; g < 40; g++) {
      String id = "B" + g;
      for (int s = 0; s < 6; s++) {
        double v = s >= 3 ? 10.0 + 0.01 * g + 0.1 * s : 0.1 + 0.01 * s;
        matrix.put(id, tracks.get(s), v);
      }
      matrix.putLocus(id, "1", 100 + g, 110 + g);
    }

    SamplePatternEngine.Result result = SamplePatternEngine.discover(
        matrix,
        tracks,
        new SamplePatternEngine.Settings(80, 2, 0.0, true),
        EffectMode.LOG2_FOLD,
        SignalValueType.EXPRESSION,
        GroupStatTest.MODERATED);

    assertNotNull(result);
    assertEquals(2, result.k());
    assertEquals(6, result.clusterOfTrack().length);

    int c0 = result.clusterOfTrack()[0];
    assertEquals(c0, result.clusterOfTrack()[1]);
    assertEquals(c0, result.clusterOfTrack()[2]);
    int c1 = result.clusterOfTrack()[3];
    assertEquals(c1, result.clusterOfTrack()[4]);
    assertEquals(c1, result.clusterOfTrack()[5]);
    assertTrue(c0 != c1);

    assertNotNull(result.markers());
    assertTrue(result.markers().rows().size() > 0, "expected marker genes");
    assertTrue(result.hasHeatmapData());
    assertEquals(6, result.leafOrder().length);
    assertEquals(6, result.sampleFeature().length);
    assertEquals(result.selectedFeatures().size(), result.sampleFeature()[0].length);
    assertTrue(result.zScored());
  }

  @Test
  void fromGroupComparisonBuildsCohortOrderedHeatmap() {
    SampleRegistry registry = new SampleRegistry();
    SampleGroup gA = registry.createSampleGroup("Tumor");
    SampleGroup gB = registry.createSampleGroup("Normal");
    List<SampleTrack> aTracks = new ArrayList<>();
    List<SampleTrack> bTracks = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      SampleTrack t = new SampleTrack("T" + i);
      aTracks.add(t);
      registry.getSampleTracks().add(t);
    }
    for (int i = 0; i < 3; i++) {
      SampleTrack t = new SampleTrack("N" + i);
      bTracks.add(t);
      registry.getSampleTracks().add(t);
    }
    registry.setTracksExclusiveGroup(aTracks, gA.getId());
    registry.setTracksExclusiveGroup(bTracks, gB.getId());

    FeatureValueMatrix matrix = new FeatureValueMatrix();
    for (String gene : List.of("G1", "G2", "G3")) {
      for (int i = 0; i < aTracks.size(); i++) {
        matrix.put(gene, aTracks.get(i), 5.0 + i);
      }
      for (int i = 0; i < bTracks.size(); i++) {
        matrix.put(gene, bTracks.get(i), 1.0 + i);
      }
    }
    List<GroupStatResult.Row> rows = new ArrayList<>();
    for (String gene : List.of("G1", "G2", "G3")) {
      rows.add(new GroupStatResult.Row(
          gene, gene, "1", 1, 10, List.of(), 1.0, 0.01, 0.02, "t", "up"));
    }
    GroupStatResult de = new GroupStatResult(
        AnalysisMode.SIGNAL, List.of(gA.getId(), gB.getId()), gB.getId(), rows, "de");

    SamplePatternEngine.Result heat = SamplePatternEngine.fromGroupComparison(
        matrix, registry, List.of(gA.getId(), gB.getId()), de);
    assertTrue(heat.hasHeatmapData());
    assertEquals(3, heat.selectedFeatures().size());
    assertEquals(6, heat.tracks().size());
    assertEquals("Tumor", heat.clusterLabel(0));
    assertEquals("Normal", heat.clusterLabel(1));
    // Cohort order: all Tumor then all Normal.
    assertEquals(0, heat.clusterOfTrack()[heat.leafOrder()[0]]);
    assertEquals(0, heat.clusterOfTrack()[heat.leafOrder()[2]]);
    assertEquals(1, heat.clusterOfTrack()[heat.leafOrder()[3]]);
  }

  @Test
  void pearsonIdenticalIsOne() {
    double[] a = {1, 2, 3, 4};
    double[] b = {1, 2, 3, 4};
    assertEquals(1.0, SamplePatternEngine.pearson(a, b), 1e-9);
  }

  @Test
  void hierarchicalCutTwoGroups() {
    double[][] dist = {
        {0, 0.1, 0.1, 0.9, 0.9, 0.9},
        {0.1, 0, 0.1, 0.9, 0.9, 0.9},
        {0.1, 0.1, 0, 0.9, 0.9, 0.9},
        {0.9, 0.9, 0.9, 0, 0.1, 0.1},
        {0.9, 0.9, 0.9, 0.1, 0, 0.1},
        {0.9, 0.9, 0.9, 0.1, 0.1, 0}
    };
    SamplePatternEngine.HierarchicalCut cut = SamplePatternEngine.hierarchicalCut(dist, 2);
    int[] c = cut.clusterOf();
    assertEquals(c[0], c[1]);
    assertEquals(c[0], c[2]);
    assertEquals(c[3], c[4]);
    assertEquals(c[3], c[5]);
    assertTrue(c[0] != c[3]);
    assertEquals(6, cut.leafOrder().length);
    // Leaves from the same cluster are contiguous in dendrogram order.
    int[] order = cut.leafOrder();
    assertEquals(c[order[0]], c[order[1]]);
    assertEquals(c[order[0]], c[order[2]]);
    assertEquals(c[order[3]], c[order[4]]);
    assertEquals(c[order[3]], c[order[5]]);
    assertTrue(c[order[0]] != c[order[3]]);
  }
}
