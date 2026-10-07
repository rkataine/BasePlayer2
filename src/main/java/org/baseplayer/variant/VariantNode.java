package org.baseplayer.variant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Display-eligible calls for the current visible-chain generation
     * ({@link VariantList#getVisibleChainGeneration()}). Null when unset.
     */
    private IdentityHashMap<SampleTrack, SampleCall> displayByTrack;
    private int displayCacheGeneration = -1;

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

        public SampleCall(Sample sample, String gt, double quality, int depth, double alleleFraction) {
            this(sample != null ? sample.getTrack() : null, sample, gt, quality, depth, alleleFraction);
        }

        public SampleCall(SampleTrack track, String gt, double quality, int depth, double alleleFraction) {
            this(track, null, gt, quality, depth, alleleFraction);
        }

        /** Resolves {@code trackIndex} to a track at construction time and stores that track. */
        public SampleCall(int trackIndex, String gt, double quality, int depth, double alleleFraction) {
            this(resolveTrackByIndex(trackIndex), null, gt, quality, depth, alleleFraction);
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
                alleleFraction);
        }

        public SampleCall(SampleTrack track, Sample sample, String gt, double quality, int depth, double alleleFraction) {
            this.track = track;
            this.sample = sample;
            this.gt = gt;
            this.quality = quality;
            this.depth = depth;
            this.alleleFraction = alleleFraction;
            this.isPhased = gt != null && gt.contains("|");
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

    /** Mark a sample as present using sample-track identity. */
    public void addSample(SampleCall call) {
        if (call == null || call.getTrack() == null) {
            return;
        }

        SampleTrack track = call.getTrack();
        if (samples != null) {
            for (int i = 0; i < samples.size(); i++) {
                if (samples.get(i).getTrack() == track) {
                    samples.set(i, call);
                    return;
                }
            }
        } else {
            samples = new ArrayList<>();
        }
        samples.add(call);
    }

    public boolean hasSample(int trackIndex) {
        return getSampleCall(trackIndex) != null;
    }

    /** Returns the SampleCall for the live track index, or null. */
    public SampleCall getSampleCall(int trackIndex) {
        if (samples == null || trackIndex < 0) {
            return null;
        }
        for (SampleCall call : samples) {
            if (call.getTrackIndex() == trackIndex) {
                return call;
            }
        }
        return null;
    }

    /** Returns all sample calls for table/annotation iteration. */
    public List<SampleCall> getSamples() {
        return samples == null ? Collections.emptyList() : Collections.unmodifiableList(samples);
    }

    public void clearDisplayCache() {
        displayByTrack = null;
        displayCacheGeneration = -1;
    }

    public void setDisplayCache(int generation, IdentityHashMap<SampleTrack, SampleCall> byTrack) {
        this.displayCacheGeneration = generation;
        this.displayByTrack = byTrack;
    }

    public boolean hasDisplayCache(int generation) {
        return displayByTrack != null && displayCacheGeneration == generation;
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

    public int getSampleCount() {
        return samples == null ? 0 : samples.size();
    }

    /** True if any sample call on this node is currently UI-visible. */
    public boolean hasUiVisibleSample() {
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
    public boolean removeSample(SampleCall call) {
        if (call == null) {
            return samples == null || samples.isEmpty();
        }
        if (samples != null) {
            samples.remove(call);
            if (samples.isEmpty()) {
                samples = null;
            }
        }
        return samples == null || samples.isEmpty();
    }

    /** Remove all sample calls that belong to a track object. */
    public boolean removeSample(SampleTrack track) {
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
