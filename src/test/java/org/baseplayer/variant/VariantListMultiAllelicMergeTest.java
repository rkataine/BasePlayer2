package org.baseplayer.variant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the streaming merge cursor used by annotate-all / multi-VCF loads.
 * Gene-region and full-chrom loads must keep every distinct allele at a locus.
 */
class VariantListMultiAllelicMergeTest {

  private final List<SampleTrack> addedTracks = new ArrayList<>();

  @AfterEach
  void cleanup() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    registry.getSampleTracks().removeAll(addedTracks);
    addedTracks.clear();
  }

  @Test
  void cursorMergeKeepsTwoAllelesAtSamePositionAcrossManyVcfs() {
    VariantList cursorList = new VariantList("1");
    VariantList truthList = new VariantList("1");
    VariantNode cursor = null;

    // Simulate annotate-all: many sorted VCF files, shared cursor, no head restart.
    // MSI-like hotspot at POS=1000 with two indel alleles; filler variants elsewhere.
    final long hotspot = 1000L;
    final String ref = "A";
    final String altA = "AT";
    final String altB = "ATT";

    for (int file = 0; file < 40; file++) {
      SampleTrack track = registerTrack("S" + file);
      VariantNode.SampleCall call = hetCall(track);

      // Filler before hotspot (sorted within file).
      long fillerPos = 10L + file;
      cursor = cursorList.addVariantWithCursor(
          cursor, fillerPos, "C", "T", VcfVariantType.SNV, call);
      truthList.addVariant(fillerPos, "C", "T", VcfVariantType.SNV, call);

      // Hotspot allele A for every sample.
      cursor = cursorList.addVariantWithCursor(
          cursor, hotspot, ref, altA, VcfVariantType.INSERTION, call);
      truthList.addVariant(hotspot, ref, altA, VcfVariantType.INSERTION, call);

      // One sample also carries a second allele at the same POS (multiallelic).
      if (file == 17) {
        cursor = cursorList.addVariantWithCursor(
            cursor, hotspot, ref, altB, VcfVariantType.INSERTION, call);
        truthList.addVariant(hotspot, ref, altB, VcfVariantType.INSERTION, call);
      }

      // Filler after hotspot.
      long afterPos = 5000L + file;
      cursor = cursorList.addVariantWithCursor(
          cursor, afterPos, "G", "A", VcfVariantType.SNV, call);
      truthList.addVariant(afterPos, "G", "A", VcfVariantType.SNV, call);
    }

    assertEquals(alleleHistogram(truthList), alleleHistogram(cursorList));
    assertEquals(2, countAllelesAt(cursorList, hotspot));
    assertEquals(40, sampleCountAt(cursorList, hotspot, ref, altA));
    assertEquals(1, sampleCountAt(cursorList, hotspot, ref, altB));
  }

  @Test
  void secondAlleleStillPresentWhenPreviousVcfEndedAtSameLocus() {
    VariantList list = new VariantList("1");
    VariantNode cursor = null;

    SampleTrack t1 = registerTrack("T1");
    SampleTrack t2 = registerTrack("T2");

    // VCF1 ends on allele A at the hotspot.
    cursor = list.addVariantWithCursor(
        cursor, 100, "A", "G", VcfVariantType.SNV, hetCall(t1));
    cursor = list.addVariantWithCursor(
        cursor, 500, "A", "AT", VcfVariantType.INSERTION, hetCall(t1));

    // VCF2 starts earlier, then hits the same POS with a different allele.
    // annotate-all does not reset stream merge state between files.
    cursor = list.addVariantWithCursor(
        cursor, 50, "C", "T", VcfVariantType.SNV, hetCall(t2));
    cursor = list.addVariantWithCursor(
        cursor, 500, "A", "ATT", VcfVariantType.INSERTION, hetCall(t2));

    assertNotNull(cursor);
    assertEquals(2, countAllelesAt(list, 500));
    assertEquals(1, sampleCountAt(list, 500, "A", "AT"));
    assertEquals(1, sampleCountAt(list, 500, "A", "ATT"));
  }

  @Test
  void newStreamSessionAfterRetainUsesSameMergeAsFreshLoad() {
    VariantList list = new VariantList("1");
    VariantNode cursor = null;

    SampleTrack t1 = registerTrack("T1");
    SampleTrack t2 = registerTrack("T2");
    SampleTrack t3 = registerTrack("T3");

    cursor = list.addVariantWithCursor(
        cursor, 50, "G", "A", VcfVariantType.SNV, hetCall(t1));
    cursor = list.addVariantWithCursor(
        cursor, 100, "A", "AT", VcfVariantType.INSERTION, hetCall(t1));
    cursor = list.addVariantWithCursor(
        cursor, 100, "A", "ATT", VcfVariantType.INSERTION, hetCall(t1));

    // Cache retention ends a stream session (production always resets before the next).
    list.retainVariants(n -> n.position != 100);
    assertEquals(0, countAllelesAt(list, 100));

    // Next session — same entry as gene load / annotate-all.
    list.resetStreamMergeState();
    cursor = null;
    cursor = list.addVariantWithCursor(
        cursor, 100, "A", "AT", VcfVariantType.INSERTION, hetCall(t2));
    cursor = list.addVariantWithCursor(
        cursor, 100, "A", "ATT", VcfVariantType.INSERTION, hetCall(t3));

    assertEquals(2, countAllelesAt(list, 100));
    assertEquals(1, sampleCountAt(list, 100, "A", "AT"));
    assertEquals(1, sampleCountAt(list, 100, "A", "ATT"));
  }

  private SampleTrack registerTrack(String name) {
    SampleTrack track = new SampleTrack(name);
    ServiceRegistry.getInstance().getSampleRegistry().getSampleTracks().add(track);
    addedTracks.add(track);
    return track;
  }

  private static VariantNode.SampleCall hetCall(SampleTrack track) {
    return new VariantNode.SampleCall(track, null, "0/1", 30, 40, 0.5);
  }

  private static int countAllelesAt(VariantList list, long position) {
    int n = 0;
    for (VariantNode node = list.getFirst(); node != null; node = node.next) {
      if (node.position == position) {
        n++;
      }
    }
    return n;
  }

  private static int sampleCountAt(VariantList list, long position, String ref, String alt) {
    for (VariantNode node = list.getFirst(); node != null; node = node.next) {
      if (node.position == position
          && ref.equals(node.ref)
          && alt.equals(node.alt)) {
        return node.getSampleCount();
      }
    }
    return 0;
  }

  private static Map<String, Integer> alleleHistogram(VariantList list) {
    Map<String, Integer> hist = new LinkedHashMap<>();
    for (VariantNode node = list.getFirst(); node != null; node = node.next) {
      String key = node.position + "|" + node.ref + "|" + node.alt + "|" + node.type;
      hist.put(key, node.getSampleCount());
    }
    return hist;
  }
}
