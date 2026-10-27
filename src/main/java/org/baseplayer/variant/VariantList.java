package org.baseplayer.variant;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.annotation.VariantAnnotator;

/**
 * Sorted linked list of variants by genomic position.
 * Memory-efficient: one node per unique genomic position, shared across all samples.
 * 
 * Single source of truth for all variant data and metadata for a chromosome:
 * - Variant nodes (linked list)
 * - Loaded regions (which parts have been fetched)
 * - Filter used to load these variants
 * - Annotation status
 * 
 * This structure is optimized for:
 * - Sequential iteration during drawing (cache-friendly)
 * - Memory efficiency (shared nodes across samples)
 * - Fast range queries (start/end position bounds)
 * - Incremental loading (track what's loaded, add new VCF samples to existing lists)
 */
public class VariantList {
    
    private VariantNode head;
    private VariantNode tail;
    private int size;
    
    /** Genomic region bounds for this variant list */
    private final String chromosome;
    private long startPosition;
    private long endPosition;
    
    /** Tracks which genomic regions have been loaded */
    private final List<LoadedRegion> loadedRegions = new ArrayList<>();
    
    /** Stable key for the filter used to load these variants (for detecting filter changes) */
    private String loadedFilterKey;
    
    /** Filter that was used to load these variants */
    private VariantFilter loadedFilter;
    
    /** Whether these variants have been annotated */
    private boolean annotated = false;

    /**
     * Fingerprint of the point-allele control VCF used for non-structural
     * {@link VariantNode#controlAf}, or null when not annotated / cleared.
     */
    private String controlAfSourcePath;
    /**
     * Fingerprint of the SV control VCF + window/type settings used for structural
     * {@link VariantNode#controlAf}, or null when not annotated / cleared.
     */
    private String svControlAfSourcePath;
    /** Fingerprint of the ClinVar VCF used for {@link VariantNode#clinVarClnsig}. */
    private String clinVarSourcePath;
    
    private int vcfCountWhenLoaded = 0;

    /**
     * Streaming merge cursor helpers for {@link #addVariantWithCursor}: first allele at the
     * current genomic position and the node before that run. Avoids O(n) head restarts on
     * multi-allelic sites. Cleared by {@link #resetStreamMergeState()} between loads.
     */
    private VariantNode streamAlleleRunStart;
    private VariantNode streamAlleleRunPrev;

    /** Call before each VCF stream pass so multi-allelic run pointers are not stale. */
    public void resetStreamMergeState() {
        streamAlleleRunStart = null;
        streamAlleleRunPrev = null;
    }

    /** First drawable node under {@link #visibleFilterKey}. */
    private VariantNode visibleHead;
    /** Drawable nodes in genomic order (for binary seek). */
    private final ArrayList<VariantNode> visibleByPosition = new ArrayList<>();
    /** Drawable spanning SVs in genomic order. */
    private final ArrayList<VariantNode> visibleSvByPosition = new ArrayList<>();
    /**
     * Synthetic LOH AA/BB region nodes for the current visible-chain filter.
     * Not part of the VCF linked list; rebuilt with the visible chain.
     */
    private List<VariantNode> lohRegions = List.of();
    /** Stable key of the filter used to build the visible chain; null if unset/dirty. */
    private String visibleFilterKey;
    /** Bumped on every visible-chain clear/rebuild so draw seekers can detect staleness. */
    private int visibleChainGeneration;

    /**
     * Gene name (lower case) → track indices of samples with a qualifying mutation in that gene.
     * Built for the current {@link #geneSampleIndexFilterKey}; used for gene-level filtering
     * and gene-click sample subsets. Not stored on annotation gene objects.
     */
    private Map<String, Set<Integer>> geneSampleIndex = Map.of();
    /**
     * Same content as {@link #geneSampleIndex}, split by variant type so SV gene-level
     * comparison can require N samples with a DEL (not DUP/TRA) in a gene.
     */
    private Map<VcfVariantType, Map<String, Set<Integer>>> geneSampleIndexByType = Map.of();
    private String geneSampleIndexFilterKey;

    /**
     * Transitive window cluster per allele (identity-keyed). Only members of clusters
     * with size ≥ 2 are present. Built when {@link VariantFilter#hasComparisonWindow()}.
     */
    private Map<VariantNode, VariantComparisonClusters.ComparisonCluster> clusterIndex = Map.of();
    private String clusterSampleIndexFilterKey;
    
    /**
     * Tracks a genomic region that has been loaded.
     * Used to detect if a new region needs loading when viewing different parts of the chromosome.
     */
    public static class LoadedRegion {
        public final long start;
        public final long end;
        
        public LoadedRegion(long start, long end) {
            this.start = start;
            this.end = end;
        }
        
        @Override
        public String toString() {
            return String.format("[%d-%d]", start, end);
        }
    }
    
    public VariantList(String chromosome) {
        this.chromosome = org.baseplayer.utils.ChromosomeNames.strip(chromosome);
        this.head = null;
        this.tail = null;
        this.size = 0;
        this.startPosition = Long.MAX_VALUE;
        this.endPosition = Long.MIN_VALUE;
        this.loadedFilterKey = null;
        this.loadedFilter = null;
        this.annotated = false;
        this.vcfCountWhenLoaded = 0;
    }
    
    public VariantNode addVariant(long position, String ref, String alt,
                                   VcfVariantType type, VariantNode.SampleCall call) {
        if (call == null || call.getTrackIndex() < 0) {
            return null;
        }

        if (position < startPosition) startPosition = position;
        if (position > endPosition) endPosition = position;

        if (head == null) {
            head = new VariantNode(position, ref, alt, type);
            head.addSample(call);
            tail = head;
            size = 1;
            invalidateSampleIndexes();
            return head;
        }

        VariantNode prev = null;
        VariantNode current = head;

        while (current != null && current.position < position) {
            prev = current;
            current = current.next;
        }

        // Scan every allele at this position before inserting a duplicate node.
        while (current != null && current.position == position) {
            if (sameAllele(current.ref, ref) && sameAllele(current.alt, alt)) {
                current.addSample(call);
                invalidateSampleIndexes();
                return current;
            }
            prev = current;
            current = current.next;
        }

        VariantNode newNode = new VariantNode(position, ref, alt, type);
        newNode.addSample(call);

        if (prev == null) {
            newNode.next = head;
            head = newNode;
        } else {
            newNode.next = current;
            prev.next = newNode;
            if (current == null) {
                tail = newNode;
            }
        }

        size++;
        invalidateSampleIndexes();
        return newNode;
    }

    /**
     * Streaming merge used by every VCF load (gene region and annotate-all).
     * Callers must {@link #resetStreamMergeState()} once before a stream session, then pass
     * the returned node as {@code cursor} for each subsequent record — same cursor movement
     * for every path, no alternate merge algorithms.
     *
     * <p>Within a session, VCFs are position-sorted so the cursor walks forward; a new file
     * that restarts at a lower coordinate falls back to a head seek (same rule for all loads).
     *
     * @param cursor last node returned by a previous call ({@code null} = start of session)
     * @return the inserted or updated node – pass it as cursor to the next call
     */
    public VariantNode addVariantWithCursor(VariantNode cursor, long position, String ref,
            String alt, VcfVariantType type, VariantNode.SampleCall call) {
        if (call == null || call.getTrackIndex() < 0) {
            return cursor;
        }

        if (position < startPosition) startPosition = position;
        if (position > endPosition) endPosition = position;

        if (head == null) {
            head = new VariantNode(position, ref, alt, type);
            head.addSample(call);
            tail = head;
            size = 1;
            streamAlleleRunPrev = null;
            streamAlleleRunStart = head;
            invalidateSampleIndexes();
            return head;
        }

        // Same allele as the cursor at this locus (hotspot / multi-sample).
        // Allele-run must be active for this position (reset between sessions).
        if (cursor != null && cursor.position == position
                && streamAlleleRunStart != null
                && streamAlleleRunStart.position == position
                && sameAllele(cursor.ref, ref) && sameAllele(cursor.alt, alt)) {
            cursor.addSample(call);
            invalidateSampleIndexes();
            return cursor;
        }

        VariantNode prev;
        VariantNode current;
        if (cursor != null && cursor.position == position
                && streamAlleleRunStart != null
                && streamAlleleRunStart.position == position) {
            // Another alt at the current locus — rescan only this position's allele run.
            prev = streamAlleleRunPrev;
            current = streamAlleleRunStart;
        } else if (cursor != null && cursor.position < position) {
            // Sorted forward merge.
            prev = cursor;
            current = cursor.next;
        } else {
            // Session start (cursor null) or next VCF restarts at a lower coordinate.
            prev = null;
            current = head;
        }

        while (current != null && current.position < position) {
            prev = current;
            current = current.next;
        }

        // Allele-run bounds for the next alt / sample at this position.
        streamAlleleRunPrev = prev;
        streamAlleleRunStart = current;

        while (current != null && current.position == position) {
            if (sameAllele(current.ref, ref) && sameAllele(current.alt, alt)) {
                current.addSample(call);
                invalidateSampleIndexes();
                return current;
            }
            prev = current;
            current = current.next;
        }

        VariantNode newNode = new VariantNode(position, ref, alt, type);
        newNode.addSample(call);
        if (streamAlleleRunStart == null || streamAlleleRunStart.position != position) {
            streamAlleleRunStart = newNode;
            streamAlleleRunPrev = prev;
        }
        if (prev == null) {
            newNode.next = head;
            head = newNode;
        } else {
            newNode.next = current;
            prev.next = newNode;
            if (current == null) {
                tail = newNode;
            }
        }
        size++;
        invalidateSampleIndexes();
        return newNode;
    }

    private static boolean sameAllele(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Get the first variant node in the list.
     */
    public VariantNode getFirst() {
        return head;
    }
    
    /**
     * Get the last variant node in the list.
     */
    public VariantNode getLast() {
        return tail;
    }
    
    /**
     * Find the first variant node at or after the given position.
     * Returns null if no such node exists.
     * Prefer {@link #findFirstVisibleAtOrAfter(long)} for drawing.
     */
    public VariantNode findFirstAfter(long position) {
        VariantNode current = head;
        while (current != null && current.position < position) {
            current = current.next;
        }
        
        return current;
    }

    /**
     * Invalidate the filter-visible skip chain. Next {@link #ensureVisibleChain} rebuilds it.
     */
    public void clearVisibleChain() {
        for (VariantNode node = head; node != null; node = node.next) {
            node.nextVisible = null;
            node.prevVisible = null;
            node.clearDisplayCache();
        }
        visibleHead = null;
        visibleByPosition.clear();
        visibleSvByPosition.clear();
        // Keep lohRegions — calculated LOH spans are independent of the visible chain.
        visibleFilterKey = null;
        visibleChainGeneration++;
        // Mutations and filter rebuilds both go through here; drop stale gene→sample unions.
        clearGeneSampleIndex();
        clearClusterSampleIndex();
    }

    /**
     * Mark the visible chain as stale without walking nodes. Safe on the FX thread;
     * next {@link #ensureVisibleChain} / {@link #rebuildVisibleChain} does the full clear.
     * Bumps {@link #visibleChainGeneration} so existing display maps are treated as stale
     * without freeing them here (GC after the next clear/rebuild replaces refs).
     */
    public void invalidateVisibleFilterKey() {
        visibleFilterKey = null;
        visibleChainGeneration++;
    }

    /**
     * Rebuild {@link VariantNode#nextVisible}/{@link VariantNode#prevVisible} and seek indexes
     * for {@code filter}. A node is visible if it passes node-level checks and has at least one
     * sample call passing sample thresholds (same rule as drawing). Same path for AF/GQ and
     * genotype roles (het/hom/present) — LOH regions are never built here.
     */
    public void rebuildVisibleChain(VariantFilter filter) {
        rebuildForComparison(filter, null);
    }

    /**
     * Visible-chain rebuild with optional progress (bench / legacy callers). Same filter
     * semantics as {@link #rebuildVisibleChain}; does not call LOH region builder.
     */
    public void rebuildForComparison(VariantFilter filter, ComparisonProgress progress) {
        synchronized (this) {
            rebuildForComparisonUnlocked(filter, progress);
        }
    }

    private void rebuildForComparisonUnlocked(VariantFilter filter, ComparisonProgress progress) {
        // Basic visible-chain rebuild only. LOH regions are never built here —
        // call {@link #calculateLohRegions} explicitly (Calculate LOH button).
        if (progress != null) {
            progress.setStage("Filtering sites", 0.0, 1.0);
        }
        clearVisibleChain();

        Map<String, Set<Integer>> geneTracks = null;
        Map<VcfVariantType, Set<String>> passingGenesByType = null;
        Map<VariantNode, VariantComparisonClusters.ComparisonCluster> clusters = null;
        if (filter != null && filter.isGeneLevel()) {
            geneTracks = ensureGeneSampleIndex(filter);
            VariantFilter svSlice = filter.getSvSlice() != null ? filter.getSvSlice() : filter;
            if (svSlice.isGeneLevel()) {
                passingGenesByType = computePassingGenesByType(svSlice);
            }
        } else if (filter != null && filter.hasComparisonWindow()) {
            clusters = ensureComparisonClusters(filter);
        }

        VariantNode prevVisible = null;
        VariantNode current = head;
        int processed = 0;
        while (current != null) {
            processed++;
            if (progress != null && (processed & 0x3FF) == 0) {
                progress.reportNodes(processed);
            }

            if (isDrawableUnderFilter(
                    this, current, filter, geneTracks, passingGenesByType, clusters)) {
                current.nextVisible = null;
                current.prevVisible = prevVisible;
                if (prevVisible == null) {
                    visibleHead = current;
                } else {
                    prevVisible.nextVisible = current;
                }
                prevVisible = current;
                visibleByPosition.add(current);
                if (current.svEnd > current.position) {
                    visibleSvByPosition.add(current);
                }
                // Display maps are built lazily on first canvas/density hit for this node
                // (viewport-sized), not for every visible site × sample during rebuild.
            } else {
                current.nextVisible = null;
                current.prevVisible = null;
            }
            current = current.next;
        }
        if (progress != null) {
            progress.reportNodes(processed);
        }
        // Keep any previously calculated LOH regions; filter rebuild does not touch them.
        visibleFilterKey = filterKeyOf(filter);
    }

    /**
     * On-demand LOH region calling (Calculate LOH). Builds synthetic LOH_AA / LOH_BB
     * span nodes like an SV list — does not mutate marker genotypes. No-op when the
     * filter is not configured with heterozygous + homozygous LOH roles.
     */
    public void calculateLohRegions(VariantFilter filter) {
        calculateLohRegions(filter, null);
    }

    public void calculateLohRegions(VariantFilter filter, ComparisonProgress progress) {
        synchronized (this) {
            calculateLohRegionsUnlocked(filter, progress);
        }
    }

    /** Drop calculated LOH spans (e.g. roles cleared). */
    public void clearLohRegions() {
        synchronized (this) {
            lohRegions = List.of();
        }
    }

    private void calculateLohRegionsUnlocked(VariantFilter filter, ComparisonProgress progress) {
        VariantFilter lohFilter = resolveLohFilter(filter);
        LohTiming timing = LohTiming.startIfEnabled(chromosome, lohFilter);
        if (lohFilter == null) {
            lohRegions = List.of();
            if (timing != null) {
                timing.count("nodes", size);
                timing.count("regions", 0);
                timing.finish();
            }
            return;
        }
        if (progress != null) {
            progress.setStage("Collecting LOH sites", 0.0, 0.7);
        }
        if (timing != null) {
            timing.count("nodes", size);
            timing.begin("collect");
        }
        List<VariantNode> built = LohRegionBuilder.buildFromList(head, lohFilter, progress);
        if (timing != null) {
            timing.end("collect");
            timing.begin("annotate");
        }
        if (progress != null) {
            progress.setStage("Annotating LOH", 0.7, 1.0);
        }
        applyLohRegionsUnlocked(built, lohFilter, progress);
        if (timing != null) {
            timing.end("annotate");
            timing.count("regions", lohRegions.size());
            timing.finish();
        }
    }

    private static VariantFilter resolveLohFilter(VariantFilter filter) {
        if (filter == null || !filter.isLohMode()) {
            return null;
        }
        if (filter.hasClassSlices()) {
            VariantFilter point = filter.getPointSlice();
            VariantFilter sv = filter.getSvSlice();
            if (point != null && point.isLohMode()) {
                return point;
            }
            if (sv != null && sv.isLohMode()) {
                return sv;
            }
        }
        return filter;
    }

    private void applyLohRegionsUnlocked(
            List<VariantNode> built, VariantFilter lohFilter, ComparisonProgress progress) {
        if (built == null || built.isEmpty()) {
            lohRegions = List.of();
            return;
        }
        for (VariantNode region : built) {
            region.setDisplayCache(
                visibleChainGeneration, lohFilter.buildDisplayByTrack(region));
        }
        lohRegions = List.copyOf(built);
        if (annotated && !lohRegions.isEmpty()) {
            try {
                ReferenceGenomeService ref =
                    ServiceRegistry.getInstance().getReferenceGenomeService();
                new VariantAnnotator(ref).annotateLohRegions(this, chromosome, progress);
            } catch (RuntimeException ignored) {
                // Genes may be unavailable; table still shows unannotated regions.
            }
        } else if (!lohRegions.isEmpty()) {
            // Annotate spans even when the point/SV list was not annotated yet —
            // LOH regions are independent of marker annotation state.
            try {
                ReferenceGenomeService ref =
                    ServiceRegistry.getInstance().getReferenceGenomeService();
                new VariantAnnotator(ref).annotateLohRegions(this, chromosome, progress);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Display-eligible call for {@code track} on {@code node} under the current chain
     * generation. Uses a full display map when present; otherwise a live per-track check
     * with failed-lineages cached once per node (no IdentityHashMap × samples alloc).
     */
    public VariantNode.SampleCall getDisplayCall(
            VariantNode node, SampleTrack track, VariantFilter filter) {
        if (node == null || track == null) {
            return null;
        }
        int gen = visibleChainGeneration;
        if (node.hasDisplayCache(gen)) {
            return node.getDisplayCall(track, gen);
        }
        int trackIndex = ServiceRegistry.getInstance().getSampleRegistry().getTrackIndex(track);
        return getDisplayCall(node, trackIndex, track, filter);
    }

    /**
     * Display call by live track index (drawer hot path — avoids {@code indexOf} per paint).
     */
    public VariantNode.SampleCall getDisplayCall(
            VariantNode node, int trackIndex, SampleTrack track, VariantFilter filter) {
        if (node == null) {
            return null;
        }
        int gen = visibleChainGeneration;
        if (track != null && node.hasDisplayCache(gen)) {
            return node.getDisplayCall(track, gen);
        }
        if (filter == null) {
            return null;
        }
        Set<Integer> failedLineages = ensureDisplayFailedLineages(node, gen, filter);

        // Prefer a solid (non-overlay) visible call when several VCFs share a track.
        // Scan under the node lock via forEachSampleUntil — no List.copyOf.
        final VariantNode.SampleCall[] overlayFallback = { null };
        final VariantNode.SampleCall[] solid = { null };
        node.forEachSampleUntil(candidate -> {
            if (candidate == null) {
                return false;
            }
            if (track != null) {
                if (candidate.getTrack() != track) {
                    return false;
                }
            } else if (trackIndex >= 0 && candidate.getTrackIndex() != trackIndex) {
                return false;
            }
            if (!isDisplayEligible(node, candidate, filter, failedLineages)) {
                return false;
            }
            if (VariantTypeVisuals.isLohRegion(node.type)) {
                solid[0] = candidate;
                return true;
            }
            if (!candidate.isUiOverlay()) {
                solid[0] = candidate;
                return true;
            }
            if (overlayFallback[0] == null) {
                overlayFallback[0] = candidate;
            }
            return false;
        });
        return solid[0] != null ? solid[0] : overlayFallback[0];
    }

    /** Failed lineage ids for live display checks; cached per chain generation. */
    public Set<Integer> displayFailedLineages(VariantNode node, VariantFilter filter) {
        if (node == null || filter == null) {
            return Set.of();
        }
        return ensureDisplayFailedLineages(node, visibleChainGeneration, filter);
    }

    private static Set<Integer> ensureDisplayFailedLineages(
            VariantNode node, int gen, VariantFilter filter) {
        if (!node.hasDisplayFailedLineages(gen)) {
            Set<Integer> failed = filter.hasActiveGroupComparison()
                ? filter.failedLineagesForNode(node)
                : Set.of();
            node.setDisplayFailedLineages(gen, failed);
        }
        return node.getDisplayFailedLineages(gen);
    }

    /** Whether {@code candidate} may be drawn for {@code node} under {@code filter}. */
    public static boolean isDisplayEligible(
            VariantNode node,
            VariantNode.SampleCall candidate,
            VariantFilter filter,
            Set<Integer> failedLineages) {
        if (node == null || candidate == null || filter == null) {
            return false;
        }
        if (!filter.passesSampleThresholds(node, candidate)) {
            return false;
        }
        if (!VariantTypeVisuals.isLohRegion(node.type) && node.isHomozygousRef(candidate)) {
            return false;
        }
        if (VariantTypeVisuals.isLohRegion(node.type)) {
            return true;
        }
        return filter.passesSampleGroupConstraint(node, candidate, failedLineages);
    }

    /**
     * Collect unique displayed track indices with a display-eligible call on {@code node}.
     * Walks samples once (inverted vs tracks×samples) and writes into {@code into}.
     *
     * @return number of bits set in {@code into} for this node (cardinality after OR is caller's)
     */
    public int collectDisplayTrackIndices(
            VariantNode node,
            VariantFilter filter,
            Map<SampleTrack, Integer> displayedTrackToIndex,
            java.util.BitSet into) {
        if (node == null || displayedTrackToIndex == null || displayedTrackToIndex.isEmpty()
                || into == null) {
            return 0;
        }
        int gen = visibleChainGeneration;
        int before = into.cardinality();
        if (node.hasDisplayCache(gen)) {
            for (Map.Entry<SampleTrack, Integer> entry : displayedTrackToIndex.entrySet()) {
                VariantNode.SampleCall call = node.getDisplayCall(entry.getKey(), gen);
                if (call == null) {
                    continue;
                }
                if (!VariantTypeVisuals.isLohRegion(node.type) && node.isHomozygousRef(call)) {
                    continue;
                }
                Integer idx = entry.getValue();
                if (idx != null && idx >= 0) {
                    into.set(idx);
                }
            }
            return into.cardinality() - before;
        }
        if (filter == null) {
            return 0;
        }
        Set<Integer> failedLineages = ensureDisplayFailedLineages(node, gen, filter);
        node.forEachSample(candidate -> {
            if (candidate == null) {
                return;
            }
            SampleTrack t = candidate.getTrack();
            if (t == null) {
                return;
            }
            Integer idx = displayedTrackToIndex.get(t);
            if (idx == null || idx < 0 || into.get(idx)) {
                return;
            }
            if (!isDisplayEligible(node, candidate, filter, failedLineages)) {
                return;
            }
            into.set(idx);
        });
        return into.cardinality() - before;
    }

    /**
     * No-op. Missing child genotypes are implied as AA in
     * {@link LohRegionBuilder} / {@link VariantFilter#listDisplayCalls} without
     * mutating marker nodes.
     */
    public void ensureLohAaCalls(VariantFilter filter) {
        // retained for call-site compatibility
    }

    /**
     * Build or reuse gene → mutated sample track indices under {@code filter}'s base
     * (type/effect/Q/DP/AF) rules. Safe to call when gene-level mode is off (still useful
     * for gene-click subsets).
     */
    public Map<String, Set<Integer>> ensureGeneSampleIndex(VariantFilter filter) {
        String key = geneSampleIndexKey(filter);
        if (key.equals(geneSampleIndexFilterKey) && geneSampleIndex != null) {
            return geneSampleIndex;
        }
        Map<String, Set<Integer>> index = new HashMap<>();
        Map<VcfVariantType, Map<String, Set<Integer>>> byType = new EnumMap<>(VcfVariantType.class);
        VariantNode current = head;
        while (current != null) {
            final VariantNode node = current;
            if ((filter == null || filter.passesBaseNodeLevel(node))
                    && org.baseplayer.features.BedVariantAnnotation.passes(this, node)) {
                List<String> genes = geneKeysOf(node);
                if (!genes.isEmpty()) {
                    Set<Integer> trackHits = new HashSet<>();
                    node.forEachSample(call -> {
                        if (call == null) {
                            return;
                        }
                        if (node.isHomozygousRef(call)) {
                            return; // HomRef is not a mutated sample
                        }
                        if (filter != null && !filter.passesSampleThresholds(node, call)) {
                            return;
                        }
                        int trackIndex = call.getTrackIndex();
                        if (trackIndex >= 0) {
                            trackHits.add(trackIndex);
                        }
                    });
                    if (!trackHits.isEmpty()) {
                        Map<String, Set<Integer>> typeIndex = null;
                        if (node.type != null) {
                            typeIndex = byType.computeIfAbsent(node.type, t -> new HashMap<>());
                        }
                        for (String gene : genes) {
                            index.computeIfAbsent(gene, g -> new HashSet<>()).addAll(trackHits);
                            if (typeIndex != null) {
                                typeIndex.computeIfAbsent(gene, g -> new HashSet<>()).addAll(trackHits);
                            }
                        }
                    }
                }
            }
            current = current.next;
        }
        geneSampleIndex = index;
        geneSampleIndexByType = byType;
        geneSampleIndexFilterKey = key;
        return index;
    }

    /** Track indices mutated in {@code geneName} under the filter's base sample rules. */
    public Set<Integer> getGeneSampleTracks(String geneName, VariantFilter filter) {
        if (geneName == null || geneName.isBlank()) {
            return Set.of();
        }
        Map<String, Set<Integer>> index = ensureGeneSampleIndex(filter);
        Set<Integer> tracks = index.get(geneName.toLowerCase(Locale.ROOT));
        return tracks != null ? tracks : Set.of();
    }

    public void clearGeneSampleIndex() {
        geneSampleIndex = Map.of();
        geneSampleIndexByType = Map.of();
        geneSampleIndexFilterKey = null;
    }

    /**
     * Per-type gene → samples (populated by {@link #ensureGeneSampleIndex}).
     * Keys are lower-case gene symbols.
     */
    public Map<VcfVariantType, Map<String, Set<Integer>>> getGeneSampleIndexByType() {
        return geneSampleIndexByType != null ? geneSampleIndexByType : Map.of();
    }

    /**
     * Genes (lower case) whose type-scoped sample set passes {@code filter}'s min/max shared
     * samples and group comparison. Call {@link #ensureGeneSampleIndex} first.
     */
    public Map<VcfVariantType, Set<String>> computePassingGenesByType(VariantFilter filter) {
        Map<VcfVariantType, Set<String>> out = new EnumMap<>(VcfVariantType.class);
        if (filter == null || geneSampleIndexByType == null || geneSampleIndexByType.isEmpty()) {
            return out;
        }
        for (Map.Entry<VcfVariantType, Map<String, Set<Integer>>> typeEntry
                : geneSampleIndexByType.entrySet()) {
            Set<String> passing = new HashSet<>();
            for (Map.Entry<String, Set<Integer>> geneEntry : typeEntry.getValue().entrySet()) {
                Set<Integer> tracks = geneEntry.getValue();
                // Genotype roles need per-call GT; defer those to per-node checks.
                boolean groupOk = filter.hasActiveGenotypeGroupComparison()
                    || filter.passesGroupComparison(tracks);
                if (filter.passesSharedSampleCount(tracks) && groupOk) {
                    passing.add(geneEntry.getKey());
                }
            }
            if (!passing.isEmpty()) {
                out.put(typeEntry.getKey(), passing);
            }
        }
        return out;
    }

    /**
     * Annotated gene symbols on {@code node} that appear in {@code passingGenesLower}
     * (preserves annotation casing / order).
     */
    public static List<String> displayGenesPassing(VariantNode node, Set<String> passingGenesLower) {
        if (node == null || passingGenesLower == null || passingGenesLower.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (node.annotation != null
                && node.annotation.overlappingGenes() != null
                && !node.annotation.overlappingGenes().isEmpty()) {
            for (String name : node.annotation.overlappingGenes()) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                String key = name.trim().toLowerCase(Locale.ROOT);
                if (passingGenesLower.contains(key) && seen.add(key)) {
                    out.add(name.trim());
                }
            }
            return out;
        }
        String display = node.annotation != null ? node.annotation.geneName() : null;
        if (display != null && !display.isBlank()) {
            String key = display.trim().toLowerCase(Locale.ROOT);
            if (passingGenesLower.contains(key)) {
                return List.of(display.trim());
            }
        }
        return List.of();
    }

    /**
     * Build or reuse transitive window clusters.
     * Only alleles in clusters of size ≥ 2 are indexed; singletons are omitted so
     * the visible chain drops them. Sample counts are distinct tracks in the cluster.
     */
    public Map<VariantNode, VariantComparisonClusters.ComparisonCluster> ensureComparisonClusters(
            VariantFilter filter) {
        if (filter == null || !filter.hasComparisonWindow()) {
            // Drop cached chains when window/gene-level rules turn clustering off.
            clearClusterSampleIndex();
            return Map.of();
        }
        String key = clusterSampleIndexKey(filter);
        if (key.equals(clusterSampleIndexFilterKey) && clusterIndex != null) {
            return clusterIndex;
        }

        ArrayList<VariantNode> nodes = new ArrayList<>();
        VariantNode current = head;
        while (current != null) {
            if (filter.passesBaseNodeLevel(current)
                    && org.baseplayer.features.BedVariantAnnotation.passes(this, current)) {
                nodes.add(current);
            }
            current = current.next;
        }

        Map<String, ArrayList<VariantNode>> byFamily = new HashMap<>();
        for (VariantNode node : nodes) {
            // Skip types whose class slice has no window — they stay on exact-allele rules.
            if (filter.comparisonWindowBpFor(node.type) <= 0) {
                continue;
            }
            byFamily
                .computeIfAbsent(VariantComparisonClusters.sweepBucket(node.type), f -> new ArrayList<>())
                .add(node);
        }

        Map<VariantNode, VariantComparisonClusters.ComparisonCluster> index =
            new java.util.IdentityHashMap<>(Math.max(16, nodes.size()));
        for (ArrayList<VariantNode> familyNodes : byFamily.values()) {
            int windowBp = windowBpForFamily(filter, familyNodes);
            if (windowBp <= 0) {
                continue;
            }
            VariantComparisonClusters.sortGenomic(familyNodes);
            for (VariantComparisonClusters.ChainRange range
                    : VariantComparisonClusters.chainRanges(familyNodes, windowBp)) {
                if (range.size() < 2) {
                    continue;
                }
                Set<Integer> union = new HashSet<>();
                long paintEnd = 0;
                for (int i = range.fromInclusive; i < range.toExclusive; i++) {
                    VariantNode member = familyNodes.get(i);
                    collectClusterSampleTracks(member, filter, union);
                    paintEnd = Math.max(
                        paintEnd, VariantComparisonClusters.paintEndExclusive(member));
                }
                VariantNode first = familyNodes.get(range.fromInclusive);
                VariantNode last = familyNodes.get(range.toExclusive - 1);
                VariantComparisonClusters.ComparisonCluster cluster =
                    new VariantComparisonClusters.ComparisonCluster(
                        first, last, range.size(), union, paintEnd);
                for (int i = range.fromInclusive; i < range.toExclusive; i++) {
                    index.put(familyNodes.get(i), cluster);
                }
            }
        }

        clusterIndex = index;
        clusterSampleIndexFilterKey = key;
        return index;
    }

    /** Max applied window among types present in this sweep bucket. */
    private static int windowBpForFamily(VariantFilter filter, List<VariantNode> familyNodes) {
        if (filter == null || familyNodes == null || familyNodes.isEmpty()) {
            return 0;
        }
        int w = 0;
        for (VariantNode node : familyNodes) {
            if (node != null) {
                w = Math.max(w, filter.comparisonWindowBpFor(node.type));
            }
        }
        return w;
    }

    private static void collectClusterSampleTracks(
            VariantNode node, VariantFilter filter, Set<Integer> into) {
        if (node == null || into == null) {
            return;
        }
        node.forEachSample(call -> {
            if (call == null) {
                return;
            }
            if (!node.isAltCarrier(call)) {
                return;
            }
            if (filter != null && !filter.passesSampleThresholds(node, call)) {
                return;
            }
            int trackIndex = call.getTrackIndex();
            if (trackIndex >= 0) {
                into.add(trackIndex);
            }
        });
    }

    public void clearClusterSampleIndex() {
        clusterIndex = Map.of();
        clusterSampleIndexFilterKey = null;
    }

    /**
     * Distinct comparison clusters that currently appear on the visible chain
     * (respects shared-sample / group / type rules). Empty when the comparison
     * window is off. Order is genomic start.
     */
    public List<VariantComparisonClusters.ComparisonCluster> listUniqueComparisonClusters(
            VariantFilter filter) {
        if (filter == null || !filter.hasComparisonWindow()) {
            clearClusterSampleIndex();
            return List.of();
        }
        ensureVisibleChain(filter);
        Map<VariantNode, VariantComparisonClusters.ComparisonCluster> index =
            ensureComparisonClusters(filter);
        if (index.isEmpty()) {
            return List.of();
        }
        // Only clusters with ≥1 allele still passing the active filter — stricter
        // shared-sample / group rules must drop highlights, not leave stale bands.
        java.util.IdentityHashMap<VariantComparisonClusters.ComparisonCluster, Boolean> seen =
            new java.util.IdentityHashMap<>();
        List<VariantComparisonClusters.ComparisonCluster> out = new ArrayList<>();
        for (VariantNode node = visibleHead; node != null; node = node.nextVisible) {
            VariantComparisonClusters.ComparisonCluster cluster = index.get(node);
            if (cluster != null && seen.put(cluster, Boolean.TRUE) == null) {
                out.add(cluster);
            }
        }
        out.sort(java.util.Comparator
            .comparingLong(VariantComparisonClusters.ComparisonCluster::genomicStart)
            .thenComparingLong(VariantComparisonClusters.ComparisonCluster::genomicEnd));
        return out;
    }

    private void invalidateSampleIndexes() {
        geneSampleIndexFilterKey = null;
        clusterSampleIndexFilterKey = null;
    }

    private static String geneKeyOf(VariantNode node) {
        if (node == null || node.annotation == null || node.annotation.geneName() == null) {
            return null;
        }
        String name = node.annotation.geneName().trim();
        return name.isEmpty() ? null : name.toLowerCase(Locale.ROOT);
    }

    /** All gene keys to index for a node (overlapping census genes, else primary geneName). */
    private static List<String> geneKeysOf(VariantNode node) {
        if (node == null || node.annotation == null) {
            return List.of();
        }
        List<String> overlapping = node.annotation.overlappingGenes();
        if (overlapping != null && !overlapping.isEmpty()) {
            List<String> keys = new ArrayList<>(overlapping.size());
            for (String name : overlapping) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                keys.add(name.trim().toLowerCase(Locale.ROOT));
            }
            return keys;
        }
        String single = geneKeyOf(node);
        return single != null ? List.of(single) : List.of();
    }

    /** Union of sample tracks for all genes annotated on {@code node}. */
    public static Set<Integer> aggregatedTracksForGenes(
            VariantNode node, Map<String, Set<Integer>> geneSampleIndex) {
        if (geneSampleIndex == null || geneSampleIndex.isEmpty()) {
            return Set.of();
        }
        List<String> genes = geneKeysOf(node);
        if (genes.isEmpty()) {
            return Set.of();
        }
        if (genes.size() == 1) {
            return geneSampleIndex.getOrDefault(genes.get(0), Set.of());
        }
        Set<Integer> union = new HashSet<>();
        for (String gene : genes) {
            Set<Integer> tracks = geneSampleIndex.get(gene);
            if (tracks != null) {
                union.addAll(tracks);
            }
        }
        return union;
    }

    /**
     * Index key ignores shared-sample / group / geneLevel / window flags so the same index
     * serves gene-level filtering and gene-click extraction.
     */
    private static String geneSampleIndexKey(VariantFilter filter) {
        if (filter == null) {
            return "bedAnn|" + org.baseplayer.features.BedVariantAnnotation.filterKey();
        }
        return "base|"
            + filter.getMinQuality()
            + "|" + filter.getMinDepth()
            + "|" + filter.getMinAlleleFraction()
            + "|" + filter.getMaxAlleleFraction()
            + "|" + filter.isCancerGenesOnly()
            + "|" + filter.isShowCoding()
            + "|" + filter.isShowIntronic()
            + "|" + filter.isShowIntergenic()
            + "|" + filter.getAllowedTypes()
            + "|" + filter.getAllowedEffects()
            + "|" + filter.getInfoFieldFilters()
            + "|" + filter.getAllowedFilterValues()
            + "|" + filter.isFilterFieldsActive()
            + "|" + org.baseplayer.features.BedVariantAnnotation.filterKey();
    }

    private static String clusterSampleIndexKey(VariantFilter filter) {
        if (filter == null) {
            return geneSampleIndexKey(null) + "|cmpWindow=0";
        }
        int pointW = filter.getPointSlice() != null
            ? filter.comparisonWindowBpFor(VcfVariantType.SNV)
            : filter.getComparisonWindowBp();
        int svW = filter.getSvSlice() != null
            ? filter.comparisonWindowBpFor(VcfVariantType.SV_DELETION)
            : filter.getComparisonWindowBp();
        // Genomic chains + sample unions depend on base node/sample thresholds and window.
        // Shared-sample min/max is applied when listing visible clusters, not here —
        // otherwise every slider nudge would rebuild the whole index.
        return geneSampleIndexKey(filter) + "|cmpWindow=" + pointW + "/" + svW;
    }

    /** Generation counter for the filter-visible skip chain; seekers use this to invalidate. */
    public int getVisibleChainGeneration() {
        return visibleChainGeneration;
    }

    /** Number of nodes currently linked in the filter-visible skip chain. */
    public int getVisibleNodeCount() {
        return visibleByPosition.size();
    }

    /**
     * Rebuild the visible chain if it is missing or built for a different filter.
     */
    public void ensureVisibleChain(VariantFilter filter) {
        String key = filterKeyOf(filter);
        if (key.equals(visibleFilterKey)) {
            return;
        }
        rebuildVisibleChain(filter);
    }

    /**
     * Ensure the visible chain matches {@code filter}, then run {@code action} while
     * holding the list lock so a concurrent rebuild cannot clear {@code nextVisible}
     * mid-paint (slider filter apply vs canvas draw race).
     */
    public void withVisibleChain(VariantFilter filter, Runnable action) {
        if (action == null) {
            return;
        }
        synchronized (this) {
            String key = filterKeyOf(filter);
            if (!key.equals(visibleFilterKey)) {
                rebuildForComparisonUnlocked(filter, null);
            }
            action.run();
        }
    }

    /**
     * First drawable node at or after {@code position}, via binary seek on the visible index.
     */
    public VariantNode findFirstVisibleAtOrAfter(long position) {
        if (visibleByPosition.isEmpty()) {
            return null;
        }
        int lo = 0;
        int hi = visibleByPosition.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (visibleByPosition.get(mid).position < position) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo < visibleByPosition.size() ? visibleByPosition.get(lo) : null;
    }

    public VariantNode getVisibleHead() {
        return visibleHead;
    }

    /**
     * Drawable spanning SVs (svEnd &gt; position), genomic order. Used to paint
     * spans that start upstream of the view without scanning from chromosome start.
     */
    public List<VariantNode> getVisibleSvByPosition() {
        return visibleSvByPosition;
    }

    /**
     * Synthetic LOH AA/BB region spans for the current visible-chain filter.
     * Empty when not in LOH mode. Not part of the VCF linked list.
     */
    public List<VariantNode> getLohRegions() {
        return lohRegions != null ? lohRegions : List.of();
    }

    public String getVisibleFilterKey() {
        return visibleFilterKey;
    }

    private static String filterKeyOf(VariantFilter filter) {
        String base = filter == null ? "" : filter.toStableKey();
        return base + "|" + org.baseplayer.features.BedVariantAnnotation.filterKey();
    }


    private static boolean isDrawableUnderFilter(
            VariantList list,
            VariantNode node,
            VariantFilter filter,
            Map<String, Set<Integer>> geneSampleIndex,
            Map<VcfVariantType, Set<String>> passingGenesByType,
            Map<VariantNode, VariantComparisonClusters.ComparisonCluster> comparisonClusters) {
        if (node == null || node.getSampleCount() == 0) {
            return false;
        }
        if (!org.baseplayer.features.BedVariantAnnotation.passes(list, node)) {
            return false;
        }
        if (filter == null) {
            return true;
        }

        // SV gene-level: visible iff base filters pass and ≥1 annotated gene passes type-scoped rules
        if (filter.isGeneLevel()
                && VariantTypeVisuals.isStructural(node.type)
                && passingGenesByType != null) {
            if (!filter.passesBaseNodeLevel(node)) {
                return false;
            }
            Set<String> passing = passingGenesByType.get(node.type);
            if (displayGenesPassing(node, passing).isEmpty()) {
                return false;
            }
            VariantFilter svFilter = filter.getSvSlice() != null ? filter.getSvSlice() : filter;
            if (svFilter.hasActiveGenotypeGroupComparison()
                    && !svFilter.passesGroupComparison(node)) {
                return false;
            }
            boolean[] any = { false };
            node.forEachSample(call -> {
                if (!any[0] && filter.passesSampleThresholds(node, call)) {
                    any[0] = true;
                }
            });
            return any[0];
        }

        Set<Integer> aggregatedTracks = null;
        if (filter.isGeneLevel() && geneSampleIndex != null) {
            aggregatedTracks = aggregatedTracksForGenes(node, geneSampleIndex);
        } else if (filter.comparisonWindowBpFor(node.type) > 0) {
            // Window mode for this class: only multi-allele chains; singletons hidden.
            VariantComparisonClusters.ComparisonCluster cluster =
                comparisonClusters != null ? comparisonClusters.get(node) : null;
            if (cluster == null || cluster.size < 2) {
                return false;
            }
            aggregatedTracks = cluster.sampleTracks;
        }
        if (!filter.passesNodeLevel(node, aggregatedTracks)) {
            return false;
        }
        // Genotype/presence roles: same eligibility as tables (display samples), not
        // "any threshold-passing call" — that left sites on the chain with 0 table rows.
        if (filter.hasActiveGroupComparison()) {
            return filter.countDisplaySamples(node) > 0;
        }
        boolean[] any = { false };
        node.forEachSample(call -> {
            if (!any[0] && filter.passesSampleThresholds(node, call)) {
                any[0] = true;
            }
        });
        return any[0];
    }

    /**
     * Ensure the visible chain matches {@code filter}, then return a stable snapshot
     * of visible nodes (safe if another thread rebuilds afterward).
     */
    public List<VariantNode> snapshotVisibleNodes(VariantFilter filter) {
        synchronized (this) {
            String key = filterKeyOf(filter);
            if (!key.equals(visibleFilterKey)) {
                rebuildForComparisonUnlocked(filter, null);
            }
            if (visibleByPosition.isEmpty()) {
                return List.of();
            }
            return List.copyOf(visibleByPosition);
        }
    }
    
    /**
     * Get variants in a specific genomic range.
     * Returns a list for compatibility, but iteration via nodes is more efficient.
     */
    public List<VariantNode> getVariantsInRange(long start, long end) {
        List<VariantNode> result = new ArrayList<>();
        VariantNode current = findFirstAfter(start);
        
        while (current != null && current.position <= end) {
            result.add(current);
            current = current.next;
        }
        
        return result;
    }
    
    /**
     * Count variants in the list.
     */
    public int size() {
        return size;
    }
    
    /**
     * Check if the list is empty.
     */
    public boolean isEmpty() {
        return head == null;
    }
    
    /**
     * Remove all variants for a specific sample track object.
     * If a variant has no more samples after removal, it's removed from the list.
     * @param track The sample track to remove
     * @return The number of variant nodes removed
     */
    public int removeTrack(SampleTrack track) {
        if (head == null || track == null) return 0;

        int nodesRemoved = 0;
        VariantNode prev = null;
        VariantNode current = head;

        while (current != null) {
            VariantNode next = current.next;
            boolean isEmpty = current.removeSample(track);

            if (isEmpty) {
                if (prev == null) {
                    head = next;
                } else {
                    prev.next = next;
                }

                if (current == tail) {
                    tail = prev;
                }

                size--;
                nodesRemoved++;
            } else {
                prev = current;
            }

            current = next;
        }

        clearVisibleChain();
        invalidateSampleIndexes();
        return nodesRemoved;
    }

    /**
     * Remove all sample calls bound to one data file. Nodes left with no samples
     * are removed from the list.
     * @return The number of variant nodes removed
     */
    public int removeSampleFile(Sample sample) {
        if (head == null || sample == null) return 0;

        int nodesRemoved = 0;
        VariantNode prev = null;
        VariantNode current = head;

        while (current != null) {
            VariantNode next = current.next;
            boolean isEmpty = current.removeSampleFile(sample);

            if (isEmpty) {
                if (prev == null) {
                    head = next;
                } else {
                    prev.next = next;
                }

                if (current == tail) {
                    tail = prev;
                }

                size--;
                nodesRemoved++;
            } else {
                prev = current;
            }

            current = next;
        }

        clearVisibleChain();
        invalidateSampleIndexes();
        return nodesRemoved;
    }

    /**
     * Retain only sample calls that satisfy {@code keepPredicate}. Variant nodes with no
     * remaining samples are removed from the list.
     */
    public void retainSamples(BiPredicate<VariantNode, VariantNode.SampleCall> keepPredicate) {
        if (head == null || keepPredicate == null) return;

        VariantNode prev = null;
        VariantNode current = head;

        while (current != null) {
            VariantNode next = current.next;

            // Snapshot because getSamples() returns an unmodifiable live view.
            List<VariantNode.SampleCall> calls = new ArrayList<>(current.getSamples());
            for (VariantNode.SampleCall call : calls) {
                if (!keepPredicate.test(current, call)) {
                    current.removeSample(call);
                }
            }

            if (current.getSampleCount() == 0) {
                if (prev == null) {
                    head = next;
                } else {
                    prev.next = next;
                }
                if (current == tail) {
                    tail = prev;
                }
                size--;
            } else {
                prev = current;
            }

            current = next;
        }

        recalculateBounds();
        clearVisibleChain();
    }

    /**
     * Remove variants that don't satisfy {@code keepPredicate}.
     * Useful for filtering variants based on annotation (e.g., effect type).
     */
    public void retainVariants(java.util.function.Predicate<VariantNode> keepPredicate) {
        if (head == null || keepPredicate == null) return;

        VariantNode prev = null;
        VariantNode current = head;

        while (current != null) {
            VariantNode next = current.next;

            if (!keepPredicate.test(current)) {
                // Remove this node
                if (prev == null) {
                    head = next;
                } else {
                    prev.next = next;
                }
                if (current == tail) {
                    tail = prev;
                }
                size--;
                // Detach so stale stream-merge cursors cannot walk back into the live list.
                current.next = null;
            } else {
                prev = current;
            }

            current = next;
        }
        // Stream-merge helpers may still point at removed nodes — drop them.
        resetStreamMergeState();

        recalculateBounds();
        clearVisibleChain();
    }

    public void freezeCachedVariants() {
				System.out.println("Freezing cached variants");
        clearGeneSampleIndex();
        clearClusterSampleIndex();

        head = visibleHead;
        int newSize = 0;
        VariantNode last = null;
        VariantNode node = visibleHead;
        while (node != null) {
            VariantNode nextVis = node.nextVisible;
            node.next = nextVis;
            last = node;
            newSize++;
            node = nextVis;
        }
        tail = last;
        size = newSize;
        recalculateBounds();
    }

    private void recalculateBounds() {
        if (head == null) {
            tail = null;
            startPosition = Long.MAX_VALUE;
            endPosition = Long.MIN_VALUE;
            return;
        }
        startPosition = head.position;
        VariantNode cur = head;
        long maxPos = head.position;
        VariantNode last = head;
        while (cur != null) {
            if (cur.position > maxPos) maxPos = cur.position;
            last = cur;
            cur = cur.next;
        }
        endPosition = maxPos;
        tail = last;
    }
    
    /**
     * Clear all variants from the list and reset all metadata.
     */
    public void clear() {
        clearVisibleChain();
        clearGeneSampleIndex();
        clearClusterSampleIndex();
        // Unlink every node so a stray TableRow/UI ref to one VariantNode cannot
        // keep the whole chromosome's SV graph alive after New Project.
        for (VariantNode node = head; node != null; ) {
            VariantNode next = node.next;
            node.next = null;
            node.nextVisible = null;
            node.prevVisible = null;
            node.annotation = null;
            node.controlAf = null;
            node.clearDisplayCache();
            node.clearSamplesForDispose();
            node.clearInfoForDispose();
            node = next;
        }
        for (VariantNode node : lohRegions) {
            if (node == null) {
                continue;
            }
            node.next = null;
            node.nextVisible = null;
            node.prevVisible = null;
            node.annotation = null;
            node.clearDisplayCache();
            node.clearSamplesForDispose();
            node.clearInfoForDispose();
        }
        head = null;
        tail = null;
        size = 0;
        startPosition = Long.MAX_VALUE;
        endPosition = Long.MIN_VALUE;
        loadedRegions.clear();
        loadedFilterKey = null;
        loadedFilter = null;
        annotated = false;
        controlAfSourcePath = null;
        svControlAfSourcePath = null;
        clinVarSourcePath = null;
        vcfCountWhenLoaded = 0;
        streamAlleleRunStart = null;
        streamAlleleRunPrev = null;
        lohRegions = List.of();
    }
    
    /**
     * Get the chromosome this list covers.
     */
    public String getChromosome() {
        return chromosome;
    }
    
    /**
     * Get the start position of variants in this list.
     */
    public long getStartPosition() {
        return startPosition;
    }
    
    /**
     * Get the end position of variants in this list.
     */
    public long getEndPosition() {
        return endPosition;
    }
    
    /**
     * Check if this list covers the given genomic range.
     */
    public boolean coversRange(long start, long end) {
        return !isEmpty() && startPosition <= start && endPosition >= end;
    }
    
    /**
     * Get statistics about this variant list.
     */
    public String getStats() {
        if (isEmpty()) {
            return "Empty variant list";
        }
        
        int totalSampleOccurrences = 0;
        int minSamples = Integer.MAX_VALUE;
        int maxSamples = 0;
        
        VariantNode current = head;
        while (current != null) {
            int count = current.getSampleCount();
            totalSampleOccurrences += count;
            minSamples = Math.min(minSamples, count);
            maxSamples = Math.max(maxSamples, count);
            current = current.next;
        }
        
        double avgSamplesPerVariant = (double) totalSampleOccurrences / size;
        
        return String.format(
            "VariantList: %d variants on %s:%d-%d, avg %.1f samples/variant (min=%d, max=%d)",
            size, chromosome, startPosition, endPosition, 
            avgSamplesPerVariant, minSamples, maxSamples);
    }
    
    @Override
    public String toString() {
        return String.format("VariantList[%s:%d-%d, n=%d]", 
            chromosome, startPosition, endPosition, size);
    }
    
    /**
     * Check if the given region has been loaded.
     * Returns true if the range [start, end] is fully covered by loaded regions.
     */
    public boolean isRegionLoaded(long start, long end) {
        for (LoadedRegion region : loadedRegions) {
            if (region.start <= start && region.end >= end) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Record that a genomic region has been loaded.
     * Merges overlapping/adjacent regions to keep the list compact.
     */
    public void addLoadedRegion(long start, long end) {
        if (start > end) return;
        
        // Merge with overlapping or adjacent regions
        long mergedStart = start;
        long mergedEnd = end;
        List<LoadedRegion> toRemove = new ArrayList<>();
        
        for (LoadedRegion r : loadedRegions) {
            if (r.end < mergedStart - 1 || r.start > mergedEnd + 1) {
                continue;  // No overlap or adjacency
            }
            // Overlapping or adjacent: merge
            mergedStart = Math.min(mergedStart, r.start);
            mergedEnd = Math.max(mergedEnd, r.end);
            toRemove.add(r);
        }
        
        loadedRegions.removeAll(toRemove);
        loadedRegions.add(new LoadedRegion(mergedStart, mergedEnd));
    }
    
    /**
     * Get all loaded regions for this chromosome.
     */
    public List<LoadedRegion> getLoadedRegions() {
        return new ArrayList<>(loadedRegions);
    }
    
    /**
     * Set the filter key that was used to load these variants.
     * The key is a stable identifier used to detect when the filter changes.
     */
    public void setLoadedFilterKey(String filterKey) {
        this.loadedFilterKey = filterKey;
    }
    
    /**
     * Get the filter key used to load these variants.
     */
    public String getLoadedFilterKey() {
        return loadedFilterKey;
    }
    
    /**
     * Set the filter that was used to load these variants.
     * Used to detect when filter changes require a reload.
     */
    public void setLoadedFilter(VariantFilter filter) {
        this.loadedFilter = filter;
    }
    
    /**
     * Get the filter used to load these variants.
     */
    public VariantFilter getLoadedFilter() {
        return loadedFilter;
    }
    
    /**
     * Mark whether these variants have been annotated.
     */
    public void setAnnotated(boolean annotated) {
        this.annotated = annotated;
        // Gene names drive the sample index; invalidate when annotation state changes.
        clearGeneSampleIndex();
    }
    
    /**
     * Check if these variants have been annotated.
     */
    public boolean isAnnotated() {
        return annotated;
    }

    public String getControlAfSourcePath() {
        return controlAfSourcePath;
    }

    public void setControlAfSourcePath(String controlAfSourcePath) {
        this.controlAfSourcePath = controlAfSourcePath;
    }

    public String getSvControlAfSourcePath() {
        return svControlAfSourcePath;
    }

    public void setSvControlAfSourcePath(String svControlAfSourcePath) {
        this.svControlAfSourcePath = svControlAfSourcePath;
    }

    public String getClinVarSourcePath() {
        return clinVarSourcePath;
    }

    public void setClinVarSourcePath(String clinVarSourcePath) {
        this.clinVarSourcePath = clinVarSourcePath;
    }

    public void setVcfCountWhenLoaded(int count) {
        this.vcfCountWhenLoaded = count;
    }

    public int getVcfCountWhenLoaded() {
        return vcfCountWhenLoaded;
    }

    /**
     * Append a fully-built node from session cache. Caller must add nodes in
     * non-decreasing genomic position order. Does not rebuild visible chains.
     */
    public void appendNodeFromCache(VariantNode node) {
        if (node == null) return;
        node.next = null;
        node.nextVisible = null;
        node.prevVisible = null;

        if (head == null) {
            head = node;
            tail = node;
            size = 1;
            startPosition = node.position;
            endPosition = node.position;
            return;
        }

        tail.next = node;
        tail = node;
        size++;
        if (node.position < startPosition) startPosition = node.position;
        if (node.position > endPosition) endPosition = node.position;
    }
    
    /**
     * Collect unique variant types that still have at least one UI-visible sample call.
     * Hidden / removed samples do not keep types in legends or Variant Manager.
     */
    public java.util.Set<VcfVariantType> collectVariantTypes() {
        java.util.Set<VcfVariantType> types = new java.util.HashSet<>();
        VariantNode current = head;
        while (current != null) {
            if (current.hasUiVisibleSample()) {
                types.add(current.type);
            }
            current = current.next;
        }
        // Synthetic LOH regions sit outside the VCF linked list.
        for (VariantNode region : getLohRegions()) {
            if (region != null && region.type != null && region.hasUiVisibleSample()) {
                types.add(region.type);
            }
        }
        return types;
    }

    /**
     * Collect annotated {@link org.baseplayer.variant.annotation.VariantEffect} values
     * present on UI-visible nodes (skips unannotated nodes).
     */
    public java.util.Set<org.baseplayer.variant.annotation.VariantEffect> collectVariantEffects() {
        return collectVariantEffects(null);
    }

    /**
     * Collect annotation effects present on drawable variants.
     * When {@code variantClass} is non-null, only nodes of that class contribute.
     */
    public java.util.Set<org.baseplayer.variant.annotation.VariantEffect> collectVariantEffects(
            VariantTypeVisuals.VariantClass variantClass) {
        java.util.Set<org.baseplayer.variant.annotation.VariantEffect> effects =
            java.util.EnumSet.noneOf(org.baseplayer.variant.annotation.VariantEffect.class);
        VariantNode current = head;
        while (current != null) {
            if (current.hasUiVisibleSample()
                && current.annotation != null
                && current.annotation.effect() != null
                && (variantClass == null || variantClass.contains(current.type))) {
                effects.add(current.annotation.effect());
            }
            current = current.next;
        }
        return effects;
    }
}
