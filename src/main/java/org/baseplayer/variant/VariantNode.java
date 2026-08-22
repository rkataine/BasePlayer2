package org.baseplayer.variant;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

import org.baseplayer.variant.annotation.VariantAnnotation;

/** One node per unique (position, ref, alt) in the variant linked list, shared across samples. */
public class VariantNode {

    public final long position;
    public final String ref;
    /** Single alt allele; multi-allelic sites are split into separate nodes at load time. */
    public final String alt;
    public final VcfVariantType type;

    private final BitSet samplePresence;  // O(1) presence check for drawing
    private List<SampleCall> samples;     // null until first sample added

    public volatile VariantNode next;    /** End position for structural variants (from INFO/END); -1 for SNVs/indels. */
    public volatile long svEnd = -1;
    /** Record-level VCF QUAL value; -1 when missing/unknown. */
    public volatile double siteQuality = -1.0;
    /** Set by VariantAnnotator; null until annotation has been run for this chromosome. */
    public VariantAnnotation annotation;

    /** VCF call data for one sample at this allele. */
    public static class SampleCall {
        public final int trackIndex;
        public final String gt;
        public final double quality;
        public final int depth;
        public final double alleleFraction;  // Fraction of reads supporting alt allele (from AD field)
        public final boolean isPhased;

        public SampleCall(int trackIndex, String gt, double quality, int depth, double alleleFraction) {
            this.trackIndex = trackIndex;
            this.gt = gt;
            this.quality = quality;
            this.depth = depth;
            this.alleleFraction = alleleFraction;
            this.isPhased = gt != null && gt.contains("|");
        }
    }

    public VariantNode(long position, String ref, String alt, VcfVariantType type) {
        this.position = position;
        this.ref = ref;
        this.alt = alt;
        this.type = type;
        this.samplePresence = new BitSet();
    }

    /** Mark a sample as present; call may be null if no FORMAT data is available. */
    public void addSample(int trackIndex, SampleCall call) {
        samplePresence.set(trackIndex);
        if (call != null) {
            if (samples == null) samples = new ArrayList<>();
            samples.add(call);
        }
    }

    public boolean hasSample(int trackIndex) {
        return samplePresence.get(trackIndex);
    }

    /** Returns a snapshot BitSet for drawing iteration over all present samples. */
    public BitSet getSamplePresence() {
        return (BitSet) samplePresence.clone();
    }

    /** Returns the SampleCall for trackIndex, or null if not present or no call data stored. */
    public SampleCall getSampleCall(int trackIndex) {
        if (samples == null) return null;
        for (SampleCall call : samples) {
            if (call.trackIndex == trackIndex) return call;
        }
        return null;
    }

    /** Returns all sample calls for table/annotation iteration. */
    public List<SampleCall> getSamples() {
        return samples == null ? Collections.emptyList() : Collections.unmodifiableList(samples);
    }

    public int getSampleCount() {
        return samplePresence.cardinality();
    }

    /**
     * Remove a sample from this variant node.
     * @param trackIndex The track index to remove
     * @return true if the node has no more samples (should be removed from list)
     */
    public boolean removeSample(int trackIndex) {
        samplePresence.clear(trackIndex);
        if (samples != null) {
            samples.removeIf(call -> call.trackIndex == trackIndex);
            if (samples.isEmpty()) samples = null;
        }
        return samplePresence.isEmpty();
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
