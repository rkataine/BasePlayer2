package org.baseplayer.variant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds synthetic LOH_AA / LOH_BB span calls from the parental/marker heterozygous
 * marker grid.
 *
 * <p>Informative sites are positions where a Parental/Marker cohort is heterozygous.
 * At each such site the Child (homozygous cohort) is scored AA / BB / AB / missing.
 * Regions grow across consecutive same-class homozygous calls and break on retained
 * het (AB), genotype flip, or a gap larger than the threshold between homozygous hits.
 */
public final class LohRegionBuilder {

    /** Default merge gap when comparison window is unset. */
    public static final int DEFAULT_GAP_BP = 100_000;

    private LohRegionBuilder() {}

    /**
     * Build LOH regions by walking the full variant linked list ({@code next}), using
     * parental/marker hets as the backbone. Call after {@code ensureLohAaCalls}.
     */
    public static List<VariantNode> buildFromList(VariantNode listHead, VariantFilter filter) {
        return buildFromList(listHead, filter, null);
    }

    public static List<VariantNode> buildFromList(
            VariantNode listHead, VariantFilter filter, ComparisonProgress progress) {
        if (filter == null || !filter.isLohMode() || listHead == null) {
            return List.of();
        }
        Set<Integer> hetTracks = filter.getLohHeterozygousTrackIndices();
        Set<Integer> homTracks = filter.getLohHomozygousTrackIndices();
        if (hetTracks.isEmpty() || homTracks.isEmpty()) {
            return List.of();
        }
        int gapBp = filter.lohRegionGapBp();

        Map<Integer, List<SiteHit>> byTrack = new HashMap<>();
        int processed = 0;
        for (VariantNode node = listHead; node != null; node = node.next) {
            processed++;
            if (progress != null && (processed & 0x3FF) == 0) {
                progress.reportNodes(processed);
            }
            collectInformativeSite(node, filter, hetTracks, homTracks, byTrack);
        }
        if (progress != null) {
            progress.reportNodes(processed);
        }
        return mergeCollectedSites(byTrack, gapBp);
    }

    /**
     * Record one site into {@code byTrack} when parental/marker het is present.
     * Used by the fused comparison rebuild to avoid a second full list walk.
     */
    public static void collectInformativeSite(
            VariantNode node,
            VariantFilter filter,
            Set<Integer> hetTracks,
            Set<Integer> homTracks,
            Map<Integer, List<SiteHit>> byTrack) {
        if (node == null || byTrack == null || hetTracks == null || homTracks == null) {
            return;
        }
        if (VariantTypeVisuals.isSpanningCall(node.type)) {
            return;
        }
        if (!hasParentalMarkerHet(node, hetTracks, filter)) {
            return;
        }
        for (Integer trackIndex : homTracks) {
            if (trackIndex == null || trackIndex < 0) {
                continue;
            }
            VariantNode.SampleCall call = node.getSampleCall(trackIndex);
            ChildClass childClass = classifyChild(node, call);
            byTrack.computeIfAbsent(trackIndex, k -> new ArrayList<>())
                .add(new SiteHit(node.position, childClass, call));
        }
    }

    /** Merge previously collected per-track site hits into LOH region nodes. */
    public static List<VariantNode> mergeCollectedSites(
            Map<Integer, List<SiteHit>> byTrack, int gapBp) {
        if (byTrack == null || byTrack.isEmpty()) {
            return List.of();
        }
        Map<RegionKey, VariantNode> regions = new HashMap<>();
        for (List<SiteHit> sites : byTrack.values()) {
            sites.sort(Comparator.comparingLong(s -> s.position));
            mergeTrackSites(sites, gapBp, regions);
        }

        List<VariantNode> out = new ArrayList<>(regions.values());
        out.sort(Comparator
            .comparingLong((VariantNode n) -> n.position)
            .thenComparingLong(n -> n.svEnd)
            .thenComparing(n -> n.type.name()));
        return out;
    }

    private static void mergeTrackSites(
            List<SiteHit> sites, int gapBp, Map<RegionKey, VariantNode> regions) {
        int i = 0;
        while (i < sites.size()) {
            SiteHit first = sites.get(i);
            if (first.childClass != ChildClass.AA && first.childClass != ChildClass.BB) {
                i++;
                continue;
            }
            long start = first.position;
            long end = first.position;
            ChildClass alleleClass = first.childClass;
            VariantNode.SampleCall lastCall = first.call;
            int j = i + 1;
            while (j < sites.size()) {
                SiteHit next = sites.get(j);
                if (next.childClass == ChildClass.MISSING) {
                    // Skip; gap is checked against the next scored homozygous site.
                    j++;
                    continue;
                }
                if (next.childClass == ChildClass.AB) {
                    break;
                }
                if (next.childClass != alleleClass) {
                    break;
                }
                if (next.position - end > gapBp) {
                    break;
                }
                end = next.position;
                if (next.call != null) {
                    lastCall = next.call;
                }
                j++;
            }
            VcfVariantType type = alleleClass == ChildClass.AA
                ? VcfVariantType.LOH_AA
                : VcfVariantType.LOH_BB;
            RegionKey key = new RegionKey(type, start, end);
            VariantNode region = regions.get(key);
            if (region == null) {
                String altLabel = alleleClass == ChildClass.AA ? "AA" : "BB";
                region = new VariantNode(start, "N", altLabel, type);
                region.svEnd = end;
                regions.put(key, region);
            }
            if (lastCall != null) {
                region.addSample(lastCall);
            }
            if (j < sites.size() && sites.get(j).childClass == ChildClass.AB) {
                i = j + 1;
            } else {
                i = j;
            }
        }
    }

    private static boolean hasParentalMarkerHet(
            VariantNode node, Set<Integer> hetTracks, VariantFilter filter) {
        for (Integer trackIndex : hetTracks) {
            if (trackIndex == null || trackIndex < 0) {
                continue;
            }
            VariantNode.SampleCall call = node.getSampleCall(trackIndex);
            if (call == null) {
                continue;
            }
            if (filter != null && !filter.passesSampleThresholds(node, call)) {
                continue;
            }
            if (node.isHeterozygous(call)) {
                return true;
            }
        }
        return false;
    }

    private static ChildClass classifyChild(VariantNode node, VariantNode.SampleCall call) {
        if (call == null) {
            return ChildClass.MISSING;
        }
        String allele = node.lohAlleleClass(call);
        if ("AA".equals(allele)) {
            return ChildClass.AA;
        }
        if ("BB".equals(allele)) {
            return ChildClass.BB;
        }
        if ("AB".equals(allele)) {
            return ChildClass.AB;
        }
        return ChildClass.MISSING;
    }

    private enum ChildClass { AA, BB, AB, MISSING }

    /** Package-visible so fused rebuild can stream sites without a second scan. */
    record SiteHit(long position, ChildClass childClass, VariantNode.SampleCall call) {}

    private record RegionKey(VcfVariantType type, long start, long end) {}
}
