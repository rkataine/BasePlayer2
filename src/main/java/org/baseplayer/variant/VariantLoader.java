package org.baseplayer.variant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

/**
 * Loads variants from VCF files and builds VariantList for efficient drawing.
 * Maps VCF sample names to sample track indices.
 */
public class VariantLoader {
    
    private VcfReader vcfReader; // non-final: released after header parse, set again for variant loading
    private final Map<String, Integer> vcfSampleToTrackIndex;
    private final List<String> unmappedSamples;
    private final int totalVcfSampleCount; // cached so reader can be released after construction
    private String detectedNormalSample = null;
    
    public VariantLoader(VcfReader vcfReader) {
        this.vcfReader = vcfReader;
        this.unmappedSamples = new ArrayList<>();
        detectSomaticVcf();
        this.vcfSampleToTrackIndex = buildSampleMapping();
        this.totalVcfSampleCount = vcfReader.getSampleNames().size();
    }

    /** Set a fresh reader for variant loading; set to null again when done. */
    public void setVcfReader(VcfReader reader) {
        this.vcfReader = reader;
    }
    
    /**
     * Detect if this is a somatic VCF file and identify the normal sample.
     * Somatic VCFs typically have 2 samples: one normal (germline) and one tumor (somatic).
     * The normal sample usually has sample names containing patterns like "_N_", "normal", etc.
     */
    private void detectSomaticVcf() {
        List<String> vcfSamples = vcfReader.getSampleNames();
        
        // Only check if we have exactly 2 samples (typical for somatic calling)
        if (vcfSamples.size() != 2) {
            return;
        }
        
        String sample1 = vcfSamples.get(0);
        String sample2 = vcfSamples.get(1);
        
        // Check for common normal/tumor naming patterns
        String sample1Lower = sample1.toLowerCase();
        String sample2Lower = sample2.toLowerCase();
        
        // Pattern 1: Explicit normal/tumor markers
        boolean sample1IsNormal = sample1Lower.contains("normal") || sample1Lower.contains("_n_") ||
                                 sample1Lower.contains("-n-") || sample1Lower.contains("germline") ||
                                 sample1Lower.endsWith("_n") || sample1Lower.endsWith("-n");
        boolean sample2IsNormal = sample2Lower.contains("normal") || sample2Lower.contains("_n_") ||
                                 sample2Lower.contains("-n-") || sample2Lower.contains("germline") ||
                                 sample2Lower.endsWith("_n") || sample2Lower.endsWith("-n");
        
        boolean sample1IsTumor = sample1Lower.contains("tumor") || sample1Lower.contains("_t_") ||
                                sample1Lower.contains("-t-") || sample1Lower.contains("somatic") ||
                                sample1Lower.endsWith("_t") || sample1Lower.endsWith("-t");
        boolean sample2IsTumor = sample2Lower.contains("tumor") || sample2Lower.contains("_t_") ||
                                sample2Lower.contains("-t-") || sample2Lower.contains("somatic") ||
                                sample2Lower.endsWith("_t") || sample2Lower.endsWith("-t");
        
        if (sample1IsNormal && !sample2IsNormal) {
            detectedNormalSample = sample1;
        } else if (sample2IsNormal && !sample1IsNormal) {
            detectedNormalSample = sample2;
        } else if (sample1IsTumor && !sample2IsTumor) {
            // If only one is explicitly marked as tumor, the other is probably normal
            detectedNormalSample = sample2;
        } else if (sample2IsTumor && !sample1IsTumor) {
            detectedNormalSample = sample1;
        }
    }
    
    /**
     * Build mapping from VCF sample names to sample track indices.
     * Matches VCF sample names to track display names or sample names.
     * Tracks unmapped samples so they can be added later.
     * Automatically excludes detected normal samples in somatic VCFs.
     */
    private Map<String, Integer> buildSampleMapping() {
        Map<String, Integer> mapping = new HashMap<>();
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        List<String> vcfSamples = vcfReader.getSampleNames();
        
        for (String vcfSample : vcfSamples) {
            // Skip normal sample in somatic VCF
            if (detectedNormalSample != null && vcfSample.equals(detectedNormalSample)) {
                continue;
            }
            
            boolean found = false;
            
            // Try to find matching sample track
            for (int i = 0; i < registry.getSampleTracks().size(); i++) {
                SampleTrack track = registry.getSampleTracks().get(i);
                String trackName = track.getDisplayName();
                
                // Match by exact name or if track name contains VCF sample name
                if (trackName.equals(vcfSample) || trackName.contains(vcfSample)) {
                    mapping.put(vcfSample, i);
                    found = true;
                    break;
                }
                
                // Also check individual sample names within the track
                for (var sample : track.getSamples()) {
                    if (sample.getName().equals(vcfSample) || sample.getName().contains(vcfSample)) {
                        mapping.put(vcfSample, i);
                        found = true;
                        break;
                    }
                }
                
                if (found) break;
            }
            
            // Track unmapped samples (but not the skipped normal)
            if (!found) {
                unmappedSamples.add(vcfSample);
            }
        }
        
        return mapping;
    }
    
    /**
     * Load all variants for an entire chromosome and build a VariantList.
     * More efficient than region-based loading when you need the whole chromosome.
     * 
     * @param chromosome Chromosome name
     * @return VariantList containing all variants on the chromosome
     * @throws IOException if VCF query fails
     */
    public VariantList loadChromosomeVariants(String chromosome) throws IOException {
        VariantList variantList = new VariantList(chromosome);
        
        // Query all SNVs and indels for the chromosome
        List<VcfSnvIndel> snvIndels = vcfReader.querySnvsAndIndelsForChromosome(chromosome);
        for (VcfSnvIndel variant : snvIndels) {
            addVariantToList(variantList, variant.getPosition(), variant.getRef(), variant.getAlt(),
                           variant.getType(), variant);
        }
        
        // Query all structural variants for the chromosome
        List<VcfStructuralVariant> svs = vcfReader.queryStructuralVariantsForChromosome(chromosome);
        for (VcfStructuralVariant sv : svs) {
            addVariantToList(variantList, sv.getPosition(), sv.getRef(), sv.getAlt(),
                           sv.getType(), sv);
        }
        
        return variantList;
    }
    
    /**
     * Stream variants for a chromosome directly into {@code target}, using a forward cursor to
     * avoid scanning the list from head on every insertion.
     *
     * @param startCursor hint node to begin scanning from (null = scan from head)
     * @return the last inserted/updated node – pass it as startCursor for the next VCF
     */
    public VariantNode streamChromosomeVariantsToList(String chromosome, VariantList target,
            VariantNode startCursor) throws IOException {
        VariantNode[] cursor = {startCursor};
        vcfReader.iterateChromosomeVariants(chromosome,
            snv -> {
                List<String> alts = snv.getAlt();
                for (String alt : alts) {
                    for (Map.Entry<String, Integer> entry : vcfSampleToTrackIndex.entrySet()) {
                        VariantNode.SampleCall call = getSampleCallForAllele(
                            snv, entry.getKey(), entry.getValue(), alt);
                        if (call != null) {
                            cursor[0] = target.addVariantWithCursor(cursor[0], snv.getPosition(),
                                snv.getRef(), alt, snv.getType(), entry.getValue(), call);
                        }
                    }
                }
            },
            sv -> {
                List<String> alts = sv.getAlt();
                for (String alt : alts) {
                    for (Map.Entry<String, Integer> entry : vcfSampleToTrackIndex.entrySet()) {
                        VariantNode.SampleCall call = getSampleCallForAllele(
                            sv, entry.getKey(), entry.getValue(), alt);
                        if (call != null) {
                            cursor[0] = target.addVariantWithCursor(cursor[0], sv.getPosition(),
                                sv.getRef(), alt, sv.getType(), entry.getValue(), call);
                        }
                    }
                }
            }
        );
        return cursor[0];
    }

    /**
     * Load variants for a genomic region and build a VariantList.
     * @deprecated Use loadChromosomeVariants() for whole-chromosome loading
     * 
     * @param chromosome Chromosome name
     * @param start Start position (1-based)
     * @param end End position (1-based)
     * @return VariantList containing all variants in the region
     * @throws IOException if VCF query fails
     */
    @Deprecated
    public VariantList loadVariants(String chromosome, long start, long end) throws IOException {
        // For now, just load the whole chromosome
        return loadChromosomeVariants(chromosome);
    }
    
    /**
     * Add a variant to the VariantList with genotype information for matched samples.
     */
    private void addVariantToList(VariantList variantList, long position, String ref,
                                  List<String> alts, VcfVariantType type, Object variant) {
        for (String alt : alts) {
            for (Map.Entry<String, Integer> entry : vcfSampleToTrackIndex.entrySet()) {
                VariantNode.SampleCall call = getSampleCallForAllele(
                    variant, entry.getKey(), entry.getValue(), alt);
                if (call != null) {
                    variantList.addVariant(position, ref, alt, type, entry.getValue(), call);
                }
            }
        }
    }
    
    private VariantNode.SampleCall getSampleCallForAllele(Object variant, String sampleName,
                                                           int trackIndex, String altAllele) {
        Map<String, Object> gtMap = null;

        if (variant instanceof VcfSnvIndel snvIndel) {
            gtMap = snvIndel.getGenotype(sampleName);
        } else if (variant instanceof VcfStructuralVariant sv) {
            gtMap = sv.getGenotype(sampleName);
        }

        if (gtMap == null) return null;

        Boolean isHomRef = (Boolean) gtMap.get("isHomRef");
        Boolean isNoCall = (Boolean) gtMap.get("isNoCall");
        if (Boolean.TRUE.equals(isHomRef) || Boolean.TRUE.equals(isNoCall)) return null;

        String gt = (String) gtMap.get("GT");
        // GT contains allele bases (e.g. "G/A"); skip if this alt is not present
        if (gt != null && !gtContainsAlt(gt, altAllele)) return null;

        double gq = gtMap.containsKey("GQ") ? ((Number) gtMap.get("GQ")).doubleValue() : -1;
        int dp = gtMap.containsKey("DP") ? ((Number) gtMap.get("DP")).intValue() : -1;
        return new VariantNode.SampleCall(trackIndex, gt, gq, dp);
    }

    /** Returns true if the GT string (allele-base form, e.g. "G/A") contains the given alt allele. */
    private static boolean gtContainsAlt(String gt, String altAllele) {
        for (String a : gt.split("[/|]")) {
            if (a.equals(altAllele)) return true;
        }
        return false;
    }
    
    /**
     * Get the number of VCF samples successfully mapped to tracks.
     */
    public int getMappedSampleCount() {
        return vcfSampleToTrackIndex.size();
    }
    
    /**
     * Get the total number of VCF samples.
     */
    public int getTotalVcfSampleCount() {
        return totalVcfSampleCount;
    }
    
    /**
     * Get the list of VCF samples that don't have matching sample tracks.
     * These samples should have tracks created for them.
     */
    public List<String> getUnmappedSamples() {
        return new ArrayList<>(unmappedSamples);
    }
    
    /**
     * Get the detected normal sample name if this is a somatic VCF.
     * @return Normal sample name, or null if not a somatic VCF or no normal sample detected
     */
    public String getDetectedNormalSample() {
        return detectedNormalSample;
    }
    
    /**
     * Check if this is a detected somatic VCF file.
     * @return true if a normal sample was detected (indicating somatic calling)
     */
    public boolean isSomaticVcf() {
        return detectedNormalSample != null;
    }
    
    /**
     * Update the mapping after new sample tracks have been created.
     * Call this after creating tracks for unmapped samples.
     */
    public void updateMapping() {
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        
        // Re-map previously unmapped samples
        List<String> stillUnmapped = new ArrayList<>();
        for (String vcfSample : unmappedSamples) {
            boolean found = false;
            
            // Try to find matching sample track
            for (int i = 0; i < registry.getSampleTracks().size(); i++) {
                SampleTrack track = registry.getSampleTracks().get(i);
                String trackName = track.getDisplayName();
                
                // Match by exact name
                if (trackName.equals(vcfSample)) {
                    vcfSampleToTrackIndex.put(vcfSample, i);
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                stillUnmapped.add(vcfSample);
            }
        }
        
        unmappedSamples.clear();
        unmappedSamples.addAll(stillUnmapped);
    }
    
    /**
     * Get the VCF sample to track index mapping (for debugging).
     */
    public Map<String, Integer> getSampleMapping() {
        return Map.copyOf(vcfSampleToTrackIndex);
    }

    /** Returns the track indices this VCF maps to (no copy — read-only view). */
    public Collection<Integer> getTrackIndices() {
        return vcfSampleToTrackIndex.values();
    }
}
