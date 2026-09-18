package org.baseplayer.variant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public volatile VariantNode next;
    public volatile VariantNode nextVisible;
    /** Previous drawable node under the current filter-visible skip chain; null if unset. */
    public volatile VariantNode prevVisible;
    /** End position for structural variants (from INFO/END); -1 for SNVs/indels. */
    public volatile long svEnd = -1;
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

    public int getSampleCount() {
        return samples == null ? 0 : samples.size();
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
        if (call == null || call.gt == null) return false;
        return isHetGt(call.gt);
    }

    public boolean isHomozygousAlt(int trackIndex) {
        SampleCall call = getSampleCall(trackIndex);
        if (call == null || call.gt == null) return false;
        String[] a = call.gt.split("[/|]");
        return a.length >= 2 && a[0].equals(alt) && a[1].equals(alt);
    }

    /** GT uses allele bases (e.g. "G/A"); het = both alleles present and differ. */
    public static boolean isHetGt(String gt) {
        String[] a = gt.split("[/|]");
        return a.length >= 2 && !a[0].equals(".") && !a[1].equals(".") && !a[0].equals(a[1]);
    }

    @Override
    public String toString() {
        return String.format("VariantNode{pos=%d, %s>%s, type=%s, samples=%d}",
            position, ref, alt, type, getSampleCount());
    }
}
