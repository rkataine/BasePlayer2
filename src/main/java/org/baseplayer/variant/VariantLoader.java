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
        // System.err.println("[VariantLoader] Created with " + vcfReader.getSampleNames().size() + " samples");
        detectSomaticVcf();
        this.vcfSampleToTrackIndex = buildSampleMapping();
        this.totalVcfSampleCount = vcfReader.getSampleNames().size();
        // System.err.println("[VariantLoader] Sample mapping: " + vcfSampleToTrackIndex);
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
        // System.err.println("[VariantLoader.detectSomaticVcf] Checking " + vcfSamples.size() + " samples: " + vcfSamples);
        
        // Only check if we have exactly 2 samples (typical for somatic calling)
        if (vcfSamples.size() != 2) {
            // System.err.println("[VariantLoader.detectSomaticVcf] Not exactly 2 samples, skipping somatic detection");
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
        
        // System.err.println("[VariantLoader.detectSomaticVcf] Sample1: " + sample1 + " (isNormal=" + sample1IsNormal + ", isTumor=" + sample1IsTumor + ")");
        // System.err.println("[VariantLoader.detectSomaticVcf] Sample2: " + sample2 + " (isNormal=" + sample2IsNormal + ", isTumor=" + sample2IsTumor + ")");
        
        if (sample1IsNormal && !sample2IsNormal) {
            detectedNormalSample = sample1;
            // System.err.println("[VariantLoader.detectSomaticVcf] Detected normal sample: " + detectedNormalSample);
        } else if (sample2IsNormal && !sample1IsNormal) {
            detectedNormalSample = sample2;
            // System.err.println("[VariantLoader.detectSomaticVcf] Detected normal sample: " + detectedNormalSample);
        } else if (sample1IsTumor && !sample2IsTumor) {
            // If only one is explicitly marked as tumor, the other is probably normal
            detectedNormalSample = sample2;
            // System.err.println("[VariantLoader.detectSomaticVcf] Detected normal sample (by tumor exclusion): " + detectedNormalSample);
        } else if (sample2IsTumor && !sample1IsTumor) {
            detectedNormalSample = sample1;
            // System.err.println("[VariantLoader.detectSomaticVcf] Detected normal sample (by tumor exclusion): " + detectedNormalSample);
        } else {
            // System.err.println("[VariantLoader.detectSomaticVcf] Could not identify normal/tumor - treating as non-somatic");
        }
    }
    
    /**
     * Build mapping from VCF sample names to sample track indices.
     * Matches VCF sample names to track display names or sample names.
     * Tracks unmapped samples so they can be added later.
     * Automatically excludes detected normal samples in somatic VCFs.
     */
    private Map<String, Integer> buildSampleMapping() {
        // System.err.println("[VariantLoader.buildSampleMapping] Building sample mapping (normal=" + detectedNormalSample + ")");
        Map<String, Integer> mapping = new HashMap<>();
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        List<String> vcfSamples = vcfReader.getSampleNames();
        // System.err.println("[VariantLoader.buildSampleMapping] VCF samples: " + vcfSamples);
        // System.err.println("[VariantLoader.buildSampleMapping] Registry tracks: " + registry.getSampleTracks().size());
        
        for (String vcfSample : vcfSamples) {
            // Skip normal sample in somatic VCF
            if (detectedNormalSample != null && vcfSample.equals(detectedNormalSample)) {
                // System.err.println("[VariantLoader.buildSampleMapping] Skipping normal sample: " + vcfSample);
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
                    // System.err.println("[VariantLoader.buildSampleMapping] Mapped '" + vcfSample + "' -> track index " + i + " (track name: " + trackName + ")");
                    found = true;
                    break;
                }
                
                // Also check individual sample names within the track
                for (var sample : track.getSamples()) {
                    if (sample.getName().equals(vcfSample) || sample.getName().contains(vcfSample)) {
                        mapping.put(vcfSample, i);
                        // System.err.println("[VariantLoader.buildSampleMapping] Mapped '" + vcfSample + "' -> track index " + i + " (sample name in track: " + sample.getName() + ")");
                        found = true;
                        break;
                    }
                }
                
                if (found) break;
            }
            
            // Track unmapped samples (but not the skipped normal)
            if (!found) {
                unmappedSamples.add(vcfSample);
                // System.err.println("[VariantLoader.buildSampleMapping] Unmapped sample: " + vcfSample);
            }
        }
        
        // System.err.println("[VariantLoader.buildSampleMapping] Final mapping: " + mapping);
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
     * Convenience overload without progress callback.
     *
     * @param startCursor hint node to begin scanning from (null = scan from head)
     * @return the last inserted/updated node – pass it as startCursor for the next VCF
     */
    public VariantNode streamChromosomeVariantsToList(String chromosome, VariantList target,
            VariantNode startCursor) throws IOException {
        return streamChromosomeVariantsToList(chromosome, target, startCursor, null);
    }

    /**
     * Stream variants for a chromosome directly into {@code target}, using a forward cursor to
     * avoid scanning the list from head on every insertion.
     *
     * @param startCursor hint node to begin scanning from (null = scan from head)
     * @param onProgress optional callback called with (currentCount, totalSamples) as variants are processed
     * @return the last inserted/updated node – pass it as startCursor for the next VCF
     */
    public VariantNode streamChromosomeVariantsToList(String chromosome, VariantList target,
            VariantNode startCursor, java.util.function.BiConsumer<Integer, Integer> onProgress) throws IOException {
        // System.err.println("[VariantLoader.streamChromosomeVariantsToList] Loading variants for chromosome: " + chromosome);
        // System.err.println("[VariantLoader.streamChromosomeVariantsToList] Sample mapping: " + vcfSampleToTrackIndex);
        
        VariantNode[] cursor = {startCursor};
        int[] svProcessed = {0};
        java.util.Set<Integer> samplesWithVariants = new java.util.HashSet<>();
        int[] variantCount = {0};  // Count all variants processed for real-time progress feedback
        int[] lastProgressCount = {0};
        int[] maxTrackRankSeen = {0};
        // Use per-VCF mapped sample count so progress is independent of UI filter/visibility state.
        int totalSamples = Math.max(1, getMappedSampleCount());
        java.util.Map<Integer, Integer> progressRankByTrackIndex = new java.util.HashMap<>();
        int rank = 1;
        for (Integer trackIndex : new java.util.TreeSet<>(vcfSampleToTrackIndex.values())) {
            progressRankByTrackIndex.put(trackIndex, rank++);
        }

        if (onProgress != null) {
            onProgress.accept(0, totalSamples);
        }
        
        vcfReader.iterateChromosomeVariants(chromosome,
            snv -> {
                List<String> alts = snv.getAlt();
                for (String alt : alts) {
                    for (Map.Entry<String, Integer> entry : vcfSampleToTrackIndex.entrySet()) {
                        VariantNode.SampleCall call = getSampleCallForAllele(
                            snv, entry.getKey(), entry.getValue(), alt);
                        if (call != null) {
                            int trackIdx = entry.getValue();
                            cursor[0] = target.addVariantWithCursor(cursor[0], snv.getPosition(),
                                snv.getRef(), alt, snv.getType(), trackIdx, call);
                            variantCount[0]++;
                            
                            // Update highest mapped track rank seen for continuous progress
                            Integer mappedRank = progressRankByTrackIndex.get(trackIdx);
                            if (mappedRank != null && mappedRank > maxTrackRankSeen[0]) {
                                maxTrackRankSeen[0] = mappedRank;
                            }
                            
                            // Track unique samples and report progress frequently for UI feedback
                            if (samplesWithVariants.add(trackIdx)) {
                                int count = samplesWithVariants.size();
                                if (onProgress != null) {
                                // Report every sample, or at least every 10 samples for large datasets
                                if (totalSamples <= 100 || count == 1 || count % 10 == 0 || count == totalSamples) {
                                    onProgress.accept(count, totalSamples);
                                    lastProgressCount[0] = count;
                                }
                                }
                            }
                            // Also report progress based on track scanning for continuous activity
                            else if (onProgress != null && variantCount[0] % 50 == 0) {
                                int progressValue = Math.min(maxTrackRankSeen[0], totalSamples);
                                if (progressValue > lastProgressCount[0]) {
                                    onProgress.accept(progressValue, totalSamples);
                                    lastProgressCount[0] = progressValue;
                                }
                            }
                        }
                    }
                }
            },
            sv -> {
                svProcessed[0]++;
                // System.err.println("[VariantLoader.streamChromosomeVariantsToList] Processing SV #" + svProcessed[0] + ": pos=" + sv.getPosition() + ", type=" + sv.getType() + ", end=" + sv.getEnd());
                
                List<String> alts = sv.getAlt();
                // System.err.println("[VariantLoader.streamChromosomeVariantsToList]   Alts: " + alts);
                Long svEnd = sv.getEnd();
                for (String alt : alts) {
                    // System.err.println("[VariantLoader.streamChromosomeVariantsToList]   Processing alt: " + alt);
                    for (Map.Entry<String, Integer> entry : vcfSampleToTrackIndex.entrySet()) {
                        // System.err.println("[VariantLoader.streamChromosomeVariantsToList]     Checking sample '" + entry.getKey() + "' (track " + entry.getValue() + ")");
                        VariantNode.SampleCall call = getSampleCallForAllele(
                            sv, entry.getKey(), entry.getValue(), alt);
                        if (call != null) {
                            int trackIdx = entry.getValue();
                            cursor[0] = target.addVariantWithCursor(cursor[0], sv.getPosition(),
                                sv.getRef(), alt, sv.getType(), trackIdx, call);
                            if (svEnd != null && cursor[0].svEnd < 0) cursor[0].svEnd = svEnd;
                            variantCount[0]++;
                            
                            // Update highest mapped track rank seen for continuous progress
                            Integer mappedRank = progressRankByTrackIndex.get(trackIdx);
                            if (mappedRank != null && mappedRank > maxTrackRankSeen[0]) {
                                maxTrackRankSeen[0] = mappedRank;
                            }
                            
                            // Track unique samples and report progress frequently for UI feedback
                            if (samplesWithVariants.add(trackIdx)) {
                                int count = samplesWithVariants.size();
                                if (onProgress != null) {
                                // Report every sample, or at least every 10 samples for large datasets
                                if (totalSamples <= 100 || count == 1 || count % 10 == 0 || count == totalSamples) {
                                    onProgress.accept(count, totalSamples);
                                    lastProgressCount[0] = count;
                                }
                                }
                            }
                            // Also report progress based on track scanning for continuous activity
                            else if (onProgress != null && variantCount[0] % 50 == 0) {
                                int progressValue = Math.min(maxTrackRankSeen[0], totalSamples);
                                if (progressValue > lastProgressCount[0]) {
                                    onProgress.accept(progressValue, totalSamples);
                                    lastProgressCount[0] = progressValue;
                                }
                            }
                        } else {
                            // System.err.println("[VariantLoader.streamChromosomeVariantsToList]     Skipped (getSampleCallForAllele returned null)");
                        }
                    }
                }
            }
        );
        // System.err.println("[VariantLoader.streamChromosomeVariantsToList] Processed " + svProcessed[0] + " structural variants");
        // Final progress update: force completion for deterministic bar finish.
        if (onProgress != null && lastProgressCount[0] < totalSamples) {
            onProgress.accept(totalSamples, totalSamples);
        }
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
        Long svEndPos = (variant instanceof VcfStructuralVariant sv && sv.getEnd() != null)
            ? sv.getEnd() : null;
        for (String alt : alts) {
            for (Map.Entry<String, Integer> entry : vcfSampleToTrackIndex.entrySet()) {
                VariantNode.SampleCall call = getSampleCallForAllele(
                    variant, entry.getKey(), entry.getValue(), alt);
                if (call != null) {
                    VariantNode node = variantList.addVariant(position, ref, alt, type, entry.getValue(), call);
                    if (svEndPos != null && node.svEnd < 0) node.svEnd = svEndPos;
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

        if (gtMap == null) {
            // System.err.println("[VariantLoader.getSampleCallForAllele] No genotype map for sample '" + sampleName + "', alt=" + altAllele);
            return null;
        }

        Boolean isHomRef = (Boolean) gtMap.get("isHomRef");
        Boolean isNoCall = (Boolean) gtMap.get("isNoCall");
        
        // Skip if HomRef or NoCall
        if (Boolean.TRUE.equals(isNoCall)) {
            // System.err.println("[VariantLoader.getSampleCallForAllele] Sample '" + sampleName + "' is NoCall");
            return null;
        }
        
        if (Boolean.TRUE.equals(isHomRef)) {
            // System.err.println("[VariantLoader.getSampleCallForAllele] Sample '" + sampleName + "' is HomRef");
            return null;
        }

        String gt = (String) gtMap.get("GT");
        // System.err.println("[VariantLoader.getSampleCallForAllele] Sample '" + sampleName + "', GT=" + gt + ", altAllele=" + altAllele);
        
        // For SV breakends with GT=NA, accept them as present (NA = variant found)
        if ("NA".equalsIgnoreCase(gt)) {
            // System.err.println("[VariantLoader.getSampleCallForAllele]   GT=NA (breakend with no explicit genotype) -> ACCEPT");
            double gq = gtMap.containsKey("GQ") ? ((Number) gtMap.get("GQ")).doubleValue() : -1;
            int dp = gtMap.containsKey("DP") ? ((Number) gtMap.get("DP")).intValue() : -1;
            // System.err.println("[VariantLoader.getSampleCallForAllele] Creating SampleCall for sample '" + sampleName + "', gt=" + gt + ", gq=" + gq + ", dp=" + dp);
            return new VariantNode.SampleCall(trackIndex, gt, gq, dp);
        }
        
        // GT contains allele bases (e.g. "G/A") or indices (e.g. "0/1"); skip if this alt is not present
        if (gt != null && !gtContainsAlt(gt, altAllele)) {
            // System.err.println("[VariantLoader.getSampleCallForAllele]   Alt not in GT, skipping");
            return null;
        }

        double gq = gtMap.containsKey("GQ") ? ((Number) gtMap.get("GQ")).doubleValue() : -1;
        int dp = gtMap.containsKey("DP") ? ((Number) gtMap.get("DP")).intValue() : -1;
        // System.err.println("[VariantLoader.getSampleCallForAllele] Creating SampleCall for sample '" + sampleName + "', gt=" + gt + ", gq=" + gq + ", dp=" + dp);
        return new VariantNode.SampleCall(trackIndex, gt, gq, dp);
    }

    /** Returns true if the GT string (allele-base form, e.g. "G/A") contains the given alt allele. */
    private static boolean gtContainsAlt(String gt, String altAllele) {
        // System.err.println("[VariantLoader.gtContainsAlt] Checking if GT=" + gt + " contains altAllele=" + altAllele);
        
        // Handle NA/missing GT - for SVs, assume present if NA
        if (gt == null || "NA".equalsIgnoreCase(gt) || gt.equals(".") || gt.equals("./.")) {
            boolean isSvAlt = (altAllele != null && 
                (altAllele.startsWith("<") || altAllele.contains("[") || altAllele.contains("]")));
            if (isSvAlt) {
                // System.err.println("[VariantLoader.gtContainsAlt]   SV with NA/missing GT -> PRESENT");
                return true;
            }
            // System.err.println("[VariantLoader.gtContainsAlt]   Non-SV with NA/missing GT -> ABSENT");
            return false;
        }
        
        // For symbolic alleles (e.g., <DEL>) or breakends (e.g., C[chr6:123[), GT is in numeric form (0/1, 1/1, etc.)
        if (altAllele != null && (altAllele.startsWith("<") || altAllele.contains("[") || altAllele.contains("]"))) {
            // SV/Breakend - GT should be in numeric form
            // Any non-homozygous-ref GT means variant is present
            boolean hasVariant = !gt.equals("0/0");
            // System.err.println("[VariantLoader.gtContainsAlt]   SV/breakend allele -> " + (hasVariant ? "PRESENT" : "ABSENT"));
            return hasVariant;
        }
        
        // For regular alleles (SNVs/indels), check if altAllele is in the GT string
        for (String a : gt.split("[/|]")) {
            if (a.equals(altAllele)) {
                // System.err.println("[VariantLoader.gtContainsAlt]   Regular allele -> PRESENT");
                return true;
            }
        }
        // System.err.println("[VariantLoader.gtContainsAlt]   Regular allele -> ABSENT");
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
