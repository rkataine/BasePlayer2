package org.baseplayer.variant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.annotation.VariantAnnotation;

/** One node per unique (position, ref, alt) in the variant linked list, shared across samples. */
public class VariantNode {

    public final long position;
    public final String ref;
    /** Single alt allele; multi-allelic sites are split into separate nodes at load time. */
    public final String alt;
    public final VcfVariantType type;

    private List<SampleCall> samples;     // null until first sample added
    /** Lazy trackIndex → call; null when dirty. Avoids O(S) scans in LOH hot paths. */
    private SampleCall[] byTrackIndex;

    /**
     * Display-eligible calls for the current visible-chain generation
     * ({@link VariantList#getVisibleChainGeneration()}). Null when unset.
     * Prefer live per-track checks for canvas/density — full maps are large with big cohorts.
     */
    private IdentityHashMap<SampleTrack, SampleCall> displayByTrack;
    private int displayCacheGeneration = -1;
    /** Failed lineage ids for the current chain generation (shared across per-track checks). */
    private Set<Integer> displayFailedLineages;
    private int displayFailedGeneration = -1;

    public volatile VariantNode next;
    public volatile VariantNode nextVisible;
    /** Previous drawable node under the current filter-visible skip chain; null if unset. */
    public volatile VariantNode prevVisible;
    /** End position for structural variants (from INFO/END); -1 for SNVs/indels. */
    public volatile long svEnd = -1;
    /** Mate chromosome for translocations / breakends; null if unknown. */
    public volatile String svChr2;
    /** Mate position (1-based) for translocations / breakends; -1 if unknown. */
    public volatile long svEnd2 = -1;
    /** Record-level VCF QUAL value; -1 when missing/unknown. */
    public volatile double siteQuality = -1.0;
    /** VCF ID column; null when missing / {@code .}. */
    public volatile String vcfId;
    /** VCF FILTER column (joined with {@code ;}); null when missing. */
    public volatile String vcfFilter;
    /**
     * Site-level INFO key→string values from the VCF record (immutable when set).
     * Null until the first load copies INFO onto this node.
     */
    private volatile Map<String, String> infoFields;
    /** Set by VariantAnnotator; null until annotation has been run for this chromosome. */
    public VariantAnnotation annotation;

    /**
     * VCF call data for one sample at this allele.
     * Identity is the {@link SampleTrack}; {@link #getTrackIndex()} always resolves
     * against the live sample list so drawing follows list add/remove/reorder.
     */
    public static class SampleCall {
        private final SampleTrack track;
        public final Sample sample;
        public final String gt;
        public final double quality;
        public final int depth;
        public final double alleleFraction;
        public final boolean isPhased;
        /**
         * Extra FORMAT fields beyond GT/GQ/DP/AF (immutable; empty when none).
         * Used by the click details popup for CNV / caller-specific values.
         */
        public final Map<String, String> formatFields;

        public SampleCall(Sample sample, String gt, double quality, int depth, double alleleFraction) {
            this(sample != null ? sample.getTrack() : null, sample, gt, quality, depth, alleleFraction, null);
        }

        public SampleCall(SampleTrack track, String gt, double quality, int depth, double alleleFraction) {
            this(track, null, gt, quality, depth, alleleFraction, null);
        }

        /** Resolves {@code trackIndex} to a track at construction time and stores that track. */
        public SampleCall(int trackIndex, String gt, double quality, int depth, double alleleFraction) {
            this(resolveTrackByIndex(trackIndex), null, gt, quality, depth, alleleFraction, null);
        }

        public SampleCall(int trackIndex, Sample sample, String gt, double quality, int depth, double alleleFraction) {
            this(
                sample != null && sample.getTrack() != null
                    ? sample.getTrack()
                    : resolveTrackByIndex(trackIndex),
                sample,
                gt,
                quality,
                depth,
                alleleFraction,
                null);
        }

        public SampleCall(
            int trackIndex,
            Sample sample,
            String gt,
            double quality,
            int depth,
            double alleleFraction,
            Map<String, String> formatFields) {
            this(
                sample != null && sample.getTrack() != null
                    ? sample.getTrack()
                    : resolveTrackByIndex(trackIndex),
                sample,
                gt,
                quality,
                depth,
                alleleFraction,
                formatFields);
        }

        public SampleCall(SampleTrack track, Sample sample, String gt, double quality, int depth, double alleleFraction) {
            this(track, sample, gt, quality, depth, alleleFraction, null);
        }

        public SampleCall(
            SampleTrack track,
            Sample sample,
            String gt,
            double quality,
            int depth,
            double alleleFraction,
            Map<String, String> formatFields) {
            this.track = track;
            this.sample = sample;
            this.gt = gt;
            this.quality = quality;
            this.depth = depth;
            this.alleleFraction = alleleFraction;
            this.isPhased = gt != null && gt.contains("|");
            this.formatFields = formatFields == null || formatFields.isEmpty()
                ? Map.of()
                : Map.copyOf(formatFields);
        }

        /** Current index in {@link SampleRegistry#getSampleTracks()}, or -1 if the track is gone. */
        public int getTrackIndex() {
            if (track == null) {
                return -1;
            }
            try {
                return ServiceRegistry.getInstance().getSampleRegistry().getTrackIndex(track);
            } catch (Exception ignored) {
                return -1;
            }
        }

        public SampleTrack getTrack() {
            return track;
        }

        /**
         * Whether this call should appear in the canvas, master density, and variant table.
         * Hidden VCF file entries stay in the cache; only UI presentation is skipped.
         */
        public boolean isUiVisible() {
            if (sample != null) {
                return sample.visible;
            }
            if (track == null) {
                return true;
            }
            boolean hasVcf = false;
            for (Sample s : track.getSamples()) {
                if (s.getDataType() == Sample.DataType.VCF) {
                    hasVcf = true;
                    if (s.visible) {
                        return true;
                    }
                }
            }
            return !hasVcf;
        }

        /** Transparent VCF overlay — draw/table still include the call at reduced opacity. */
        public boolean isUiOverlay() {
            if (sample != null) {
                return sample.overlay;
            }
            if (track == null) {
                return false;
            }
            for (Sample s : track.getSamples()) {
                if (s.getDataType() == Sample.DataType.VCF && s.overlay) {
                    return true;
                }
            }
            return false;
        }

        private static SampleTrack resolveTrackByIndex(int trackIndex) {
            if (trackIndex < 0) {
                return null;
            }
            try {
                SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
                if (trackIndex >= registry.getSampleTracks().size()) {
                    return null;
                }
                return registry.getSampleTracks().get(trackIndex);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    public VariantNode(long position, String ref, String alt, VcfVariantType type) {
        this.position = position;
        this.ref = ref;
        this.alt = alt;
        this.type = type;
    }

    /** Immutable INFO map; empty when the VCF record had none / not yet copied. */
    public Map<String, String> getInfoFields() {
        Map<String, String> fields = infoFields;
        return fields != null ? fields : Map.of();
    }

    /** INFO value for {@code key}, or null. */
    public String getInfoValue(String key) {
        if (key == null) {
            return null;
        }
        Map<String, String> fields = infoFields;
        return fields != null ? fields.get(key) : null;
    }

    /**
     * Copy INFO onto this node once. Later sample merges for the same allele keep the first map.
     */
    public void setInfoFieldsIfAbsent(Map<String, String> fields) {
        if (infoFields != null || fields == null || fields.isEmpty()) {
            return;
        }
        // Preserve VCF key order when the source is a LinkedHashMap.
        infoFields = fields instanceof LinkedHashMap
            ? Collections.unmodifiableMap(new LinkedHashMap<>(fields))
            : Map.copyOf(fields);
    }

    /** Drop INFO payload when the owning list is disposed. */
    public void clearInfoForDispose() {
        infoFields = null;
        vcfId = null;
        vcfFilter = null;
    }

    /**
     * Mate chromosome for TRA/BND, from {@link #svChr2} or breakend ALT notation.
     */
    public String mateChromosome() {
        if (svChr2 != null && !svChr2.isBlank()) {
            return svChr2;
        }
        BreakendAlt.Mate mate = BreakendAlt.parse(alt);
        return mate != null ? mate.chrom() : null;
    }

    /**
     * Mate position (1-based) for TRA/BND, from {@link #svEnd2} or breakend ALT notation.
     * @return position, or -1 if unknown
     */
    public long matePosition() {
        if (svEnd2 >= 0) {
            return svEnd2;
        }
        BreakendAlt.Mate mate = BreakendAlt.parse(alt);
        return mate != null ? mate.pos() : -1;
    }

    /**
     * Mark a sample as present. When {@link SampleCall#sample} is set, identity is per
     * VCF/BAM file so multiple VCFs on one track keep separate calls; otherwise falls
     * back to track identity (legacy / LOH).
     * Synchronized: LOH AA synthesis and filter rebuilds can run on different threads.
     */
    public synchronized void addSample(SampleCall call) {
        if (call == null || call.getTrack() == null) {
            return;
        }

        SampleTrack track = call.getTrack();
        Sample boundSample = call.sample;
        if (samples != null) {
            for (int i = 0; i < samples.size(); i++) {
                SampleCall existing = samples.get(i);
                if (existing == null) {
                    continue;
                }
                if (boundSample != null) {
                    if (existing.sample == boundSample) {
                        samples.set(i, call);
                        byTrackIndex = null;
                        return;
                    }
                } else if (existing.sample == null && existing.getTrack() == track) {
                    samples.set(i, call);
                    byTrackIndex = null;
                    return;
                }
            }
        } else {
            samples = new ArrayList<>();
        }
        samples.add(call);
        byTrackIndex = null;
    }

    public boolean hasSample(int trackIndex) {
        return getSampleCall(trackIndex) != null;
    }

    /** Returns the SampleCall for the live track index, or null. */
    public synchronized SampleCall getSampleCall(int trackIndex) {
        if (samples == null || trackIndex < 0) {
            return null;
        }
        ensureTrackIndex();
        if (trackIndex >= byTrackIndex.length) {
            return null;
        }
        return byTrackIndex[trackIndex];
    }

    /**
     * Snapshot of sample calls. Safe to iterate while another thread synthesizes
     * LOH AA calls or merges alleles into this node.
     */
    public synchronized List<SampleCall> getSamples() {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        return List.copyOf(samples);
    }

    /**
     * Iterate sample calls without allocating a copy. Prefer this on hot LOH/filter paths.
     * {@code consumer} must not call back into mutating methods on this node.
     */
    public synchronized void forEachSample(Consumer<SampleCall> consumer) {
        if (samples == null || consumer == null) {
            return;
        }
        for (SampleCall call : samples) {
            consumer.accept(call);
        }
    }

    private void ensureTrackIndex() {
        if (byTrackIndex != null) {
            return;
        }
        if (samples == null || samples.isEmpty()) {
            byTrackIndex = new SampleCall[0];
            return;
        }
        int max = -1;
        for (SampleCall call : samples) {
            if (call == null) {
                continue;
            }
            int idx = call.getTrackIndex();
            if (idx > max) {
                max = idx;
            }
        }
        SampleCall[] index = new SampleCall[Math.max(0, max + 1)];
        for (SampleCall call : samples) {
            if (call == null) {
                continue;
            }
            int idx = call.getTrackIndex();
            if (idx >= 0) {
                index[idx] = call;
            }
        }
        byTrackIndex = index;
    }

    public void clearDisplayCache() {
        displayByTrack = null;
        displayCacheGeneration = -1;
        displayFailedLineages = null;
        displayFailedGeneration = -1;
    }

    /** Drop sample-call payload when the owning {@link VariantList} is disposed. */
    public synchronized void clearSamplesForDispose() {
        samples = null;
        byTrackIndex = null;
        clearDisplayCache();
    }

    public void setDisplayCache(int generation, IdentityHashMap<SampleTrack, SampleCall> byTrack) {
        this.displayCacheGeneration = generation;
        this.displayByTrack = byTrack;
    }

    public boolean hasDisplayCache(int generation) {
        return displayByTrack != null && displayCacheGeneration == generation;
    }

    public void setDisplayFailedLineages(int generation, Set<Integer> failed) {
        displayFailedGeneration = generation;
        displayFailedLineages = failed != null ? failed : Set.of();
    }

    public boolean hasDisplayFailedLineages(int generation) {
        return displayFailedGeneration == generation && displayFailedLineages != null;
    }

    public Set<Integer> getDisplayFailedLineages(int generation) {
        return hasDisplayFailedLineages(generation) ? displayFailedLineages : Set.of();
    }

    /** Display-eligible call for {@code track} when cache matches {@code generation}. */
    public SampleCall getDisplayCall(SampleTrack track, int generation) {
        if (track == null || !hasDisplayCache(generation)) {
            return null;
        }
        return displayByTrack.get(track);
    }

    /** Unmodifiable view of cached display calls, or empty if cache missing/stale. */
    public Map<SampleTrack, SampleCall> getDisplayByTrack(int generation) {
        if (!hasDisplayCache(generation)) {
            return Map.of();
        }
        return Collections.unmodifiableMap(displayByTrack);
    }

    public synchronized int getSampleCount() {
        return samples == null ? 0 : samples.size();
    }

    /** True if any sample call on this node is currently UI-visible. */
    public synchronized boolean hasUiVisibleSample() {
        if (samples == null || samples.isEmpty()) {
            return false;
        }
        for (SampleCall call : samples) {
            if (call != null && call.isUiVisible()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove one sample call from this variant node.
     * @return true if the node has no more samples (should be removed from list)
     */
    public synchronized boolean removeSample(SampleCall call) {
        if (call == null) {
            return samples == null || samples.isEmpty();
        }
        if (samples != null) {
            samples.remove(call);
            byTrackIndex = null;
            if (samples.isEmpty()) {
                samples = null;
            }
        }
        return samples == null || samples.isEmpty();
    }

    /** Remove all sample calls that belong to a track object. */
    public synchronized boolean removeSample(SampleTrack track) {
        if (track == null || samples == null || samples.isEmpty()) {
            return samples == null || samples.isEmpty();
        }

        List<SampleCall> removeCalls = new ArrayList<>();
        for (SampleCall call : samples) {
            if (call.getTrack() == track) {
                removeCalls.add(call);
            }
        }
        for (SampleCall call : removeCalls) {
            removeSample(call);
        }
        return samples == null || samples.isEmpty();
    }

    /** Remove all sample calls bound to a specific data file ({@link Sample}). */
    public synchronized boolean removeSampleFile(Sample sample) {
        if (sample == null || samples == null || samples.isEmpty()) {
            return samples == null || samples.isEmpty();
        }

        List<SampleCall> removeCalls = new ArrayList<>();
        for (SampleCall call : samples) {
            if (call != null && call.sample == sample) {
                removeCalls.add(call);
            }
        }
        for (SampleCall call : removeCalls) {
            removeSample(call);
        }
        return samples == null || samples.isEmpty();
    }

    public boolean isHeterozygous(int trackIndex) {
        SampleCall call = getSampleCall(trackIndex);
        return call != null && isHetGt(call.gt);
    }

    public boolean isHomozygousAlt(int trackIndex) {
        SampleCall call = getSampleCall(trackIndex);
        return call != null && isHomAltGt(call.gt, alt);
    }

    /** True if this call is heterozygous for the node's allele. */
    public boolean isHeterozygous(SampleCall call) {
        return call != null && isHetGt(call.gt);
    }

    /** True if this call is homozygous ALT for the node's allele. */
    public boolean isHomozygousAlt(SampleCall call) {
        return call != null && isHomAltGt(call.gt, alt);
    }

    /** True if this call is homozygous REF for the node's allele. */
    public boolean isHomozygousRef(SampleCall call) {
        return call != null && isHomRefGt(call.gt, ref);
    }

    /** True if the call carries the ALT (het or homozygous ALT), not HomRef/NA. */
    public boolean isAltCarrier(SampleCall call) {
        return call != null && (isHeterozygous(call) || isHomozygousAlt(call));
    }

    /**
     * LOH allele class relative to this site's REF/ALT.
     * {@code AA}=hom-ref, {@code AB}=het, {@code BB}=hom-alt; null if unusable.
     */
    public String lohAlleleClass(SampleCall call) {
        if (call == null) {
            return null;
        }
        if (isHomozygousRef(call)) {
            return "AA";
        }
        if (isHeterozygous(call)) {
            return "AB";
        }
        if (isHomozygousAlt(call)) {
            return "BB";
        }
        return null;
    }

    /**
     * GT alleles differ and neither is missing. Works for allele-base ({@code G/A})
     * and numeric ({@code 0/1}) forms. Missing/NA GT returns false.
     */
    public static boolean isHetGt(String gt) {
        String[] a = splitGtAlleles(gt);
        if (a == null) {
            return false;
        }
        return !a[0].equals(a[1]);
    }

    /**
     * Both alleles are the ALT. Accepts allele-base ({@code A/A}) and numeric
     * ({@code 1/1}, {@code 1|1}) forms. Missing/NA GT returns false.
     */
    public static boolean isHomAltGt(String gt, String alt) {
        String[] a = splitGtAlleles(gt);
        if (a == null) {
            return false;
        }
        if (alt != null && a[0].equals(alt) && a[1].equals(alt)) {
            return true;
        }
        // Numeric diploid ALT (single-alt sites: allele index 1).
        return a[0].equals("1") && a[1].equals("1");
    }

    /**
     * Both alleles are the REF. Accepts allele-base ({@code A/A}) and numeric
     * ({@code 0/0}, {@code 0|0}) forms. Missing/NA GT returns false.
     */
    public static boolean isHomRefGt(String gt, String ref) {
        String[] a = splitGtAlleles(gt);
        if (a == null) {
            return false;
        }
        if (ref != null && a[0].equals(ref) && a[1].equals(ref)) {
            return true;
        }
        return a[0].equals("0") && a[1].equals("0");
    }

    /** Diploid allele pair, or null if GT is missing / unusable for zygosity. */
    private static String[] splitGtAlleles(String gt) {
        if (gt == null || gt.isBlank()) {
            return null;
        }
        String trimmed = gt.trim();
        if ("NA".equalsIgnoreCase(trimmed) || ".".equals(trimmed) || "./.".equals(trimmed)
                || ".|.".equals(trimmed)) {
            return null;
        }
        String[] a = trimmed.split("[/|]");
        if (a.length < 2) {
            return null;
        }
        if (".".equals(a[0]) || ".".equals(a[1])) {
            return null;
        }
        return a;
    }

    @Override
    public String toString() {
        return String.format("VariantNode{pos=%d, %s>%s, type=%s, samples=%d}",
            position, ref, alt, type, getSampleCount());
    }
}
