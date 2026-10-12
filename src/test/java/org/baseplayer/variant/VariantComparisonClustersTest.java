package org.baseplayer.variant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VariantComparisonClustersTest {

  private final List<SampleTrack> addedTracks = new ArrayList<>();

  @BeforeEach
  void clearClusterCaches() {
    // ensure clean registry slots for sample-index integration tests
  }

  @AfterEach
  void removeAddedTracks() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    registry.getSampleTracks().removeAll(addedTracks);
    addedTracks.clear();
  }

  @Test
  void chainsTransitiveGapsWithinWindow() {
    List<VariantNode> nodes = List.of(
        snv(100),
        snv(120),
        snv(145));
    List<VariantComparisonClusters.ChainRange> ranges =
        VariantComparisonClusters.chainRanges(nodes, 30);
    assertEquals(1, ranges.size());
    assertEquals(3, ranges.get(0).size());
  }

  @Test
  void breaksWhenGapExceedsWindow() {
    List<VariantNode> nodes = List.of(
        snv(100),
        snv(120),
        snv(200));
    List<VariantComparisonClusters.ChainRange> ranges =
        VariantComparisonClusters.chainRanges(nodes, 30);
    assertEquals(2, ranges.size());
    assertEquals(2, ranges.get(0).size());
    assertEquals(1, ranges.get(1).size());
  }

  @Test
  void singletonRangeWhenAlone() {
    List<VariantNode> nodes = List.of(snv(50));
    List<VariantComparisonClusters.ChainRange> ranges =
        VariantComparisonClusters.chainRanges(nodes, 30);
    assertEquals(1, ranges.size());
    assertEquals(1, ranges.get(0).size());
  }

  @Test
  void classSlicedFilterUsesPointSliceWindowNotParentZero() {
    SampleTrack trackA = registerTrack("A");
    VariantList list = new VariantList("chr1");
    VariantNode n100 = list.addVariant(100, "A", "G", VcfVariantType.SNV, hetCall(trackA));
    VariantNode n120 = list.addVariant(120, "C", "T", VcfVariantType.SNV, hetCall(trackA));

    VariantFilter point = new VariantFilter();
    point.setComparisonWindowBp(30);
    VariantFilter sv = new VariantFilter();
    VariantFilter merged = new VariantFilter();
    merged.setClassSlices(point, sv);

    assertTrue(merged.hasComparisonWindow());
    assertEquals(30, merged.getComparisonWindowBp());
    assertEquals(30, merged.comparisonWindowBpFor(VcfVariantType.SNV));

    Map<VariantNode, VariantComparisonClusters.ComparisonCluster> index =
        list.ensureComparisonClusters(merged);
    assertTrue(index.containsKey(n100));
    assertTrue(index.containsKey(n120));
    assertEquals(2, index.get(n100).size);
  }

  @Test
  void ensureClusterIndexDropsSingletonsAndUnionsSamples() {
    SampleTrack trackA = registerTrack("A");
    SampleTrack trackB = registerTrack("B");

    VariantList list = new VariantList("chr1");
    VariantNode n100 = list.addVariant(
        100, "A", "G", VcfVariantType.SNV, hetCall(trackA));
    VariantNode n120 = list.addVariant(
        120, "C", "T", VcfVariantType.SNV, hetCall(trackA));
    VariantNode n145 = list.addVariant(
        145, "G", "A", VcfVariantType.SNV, hetCall(trackB));
    VariantNode lonely = list.addVariant(
        500, "A", "C", VcfVariantType.SNV, hetCall(trackA));

    VariantFilter filter = new VariantFilter();
    filter.setComparisonWindowBp(30);
    filter.setMinSharedSamples(1);

    Map<VariantNode, VariantComparisonClusters.ComparisonCluster> index =
        list.ensureComparisonClusters(filter);

    assertTrue(index.containsKey(n100));
    assertTrue(index.containsKey(n120));
    assertTrue(index.containsKey(n145));
    assertFalse(index.containsKey(lonely));

    VariantComparisonClusters.ComparisonCluster cluster = index.get(n100);
    assertEquals(cluster, index.get(n120));
    assertEquals(cluster, index.get(n145));
    assertEquals(3, cluster.size);
    assertEquals(n100, cluster.first);
    assertEquals(n145, cluster.last);
    assertEquals(2, cluster.sampleTracks.size());
    assertTrue(filter.passesSharedSampleCount(cluster.sampleTracks));

    filter.setMinSharedSamples(2);
    assertTrue(filter.passesSharedSampleCount(cluster.sampleTracks));
    filter.setMinSharedSamples(3);
    assertFalse(filter.passesSharedSampleCount(cluster.sampleTracks));
  }

  private SampleTrack registerTrack(String name) {
    SampleTrack track = new SampleTrack(name);
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    registry.getSampleTracks().add(track);
    addedTracks.add(track);
    return track;
  }

  private static VariantNode snv(long pos) {
    return new VariantNode(pos, "A", "G", VcfVariantType.SNV);
  }

  private static VariantNode.SampleCall hetCall(SampleTrack track) {
    return new VariantNode.SampleCall(track, null, "0/1", 30, 40, 0.5);
  }
}
