package org.baseplayer.variant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

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

    public void setVcfReader(VcfReader reader) {
        this.vcfReader = reader;
    }
    
    private void detectSomaticVcf() {
        List<String> vcfSamples = vcfReader.getSampleNames();
        // Only check if we have exactly 2 samples (typical for somatic calling)
        if (vcfSamples.size() != 2) {
            return;
        }
        
        String sample1 = vcfSamples.get(0);
        String sample2 = vcfSamples.get(1);
        
        String sample1Lower = sample1.toLowerCase();
        String sample2Lower = sample2.toLowerCase();
        
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
            detectedNormalSample = sample2;
        } else if (sample2IsTumor && !sample1IsTumor) {
            detectedNormalSample = sample1;
        }
    }
    
    private Map<String, Integer> buildSampleMapping() {
        Map<String, Integer> mapping = new HashMap<>();
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        List<String> vcfSamples = vcfReader.getSampleNames();
        
        for (String vcfSample : vcfSamples) {
            // Skip normal sample in somatic VCF
            if (detectedNormalSample != null && vcfSample.equals(detectedNormalSample)) {
                continue;
            }
						// TODO why track index?
            int trackIndex = -1;

            // Try to find matching sample track
            for (int i = 0; i < registry.getSampleTracks().size(); i++) {
                SampleTrack track = registry.getSampleTracks().get(i);
                String trackName = track.getDisplayName();

                if (trackName != null && (trackName.equals(vcfSample) || trackName.contains(vcfSample))) {
                    trackIndex = i;
                    break;
                }

                // Also check individual sample names within the track
                for (Sample sample : track.getSamples()) {
                    String sampleName = sample.getName();
                    if (sampleName != null && (sampleName.equals(vcfSample) || sampleName.contains(vcfSample))) {
                        trackIndex = i;
                        break;
                    }
                }

                if (trackIndex >= 0) {
                    break;
                }
            }

            if (trackIndex >= 0) {
                mapping.put(vcfSample, trackIndex);
            } else {
                unmappedSamples.add(vcfSample);
            }
        }

        return mapping;
    }

    public VariantNode streamChromosomeVariantsToList(String chromosome, VariantList target,
            VariantNode startCursor, long chromosomeLength) throws IOException {
        return streamChromosomeVariantsToList(chromosome, target, startCursor, null, null, chromosomeLength);
    }

    public VariantNode streamChromosomeVariantsToList(String chromosome, VariantList target,
            VariantNode startCursor, java.util.function.BiConsumer<Integer, Integer> onProgress, long chromosomeLength) throws IOException {
        return streamChromosomeVariantsToList(chromosome, target, startCursor, onProgress, null, chromosomeLength);
        }

        /**
         * Stream variants for a chromosome directly into {@code target}, applying load-time filters.
         *
         * @param startCursor hint node to begin scanning from (null = scan from head)
         * @param onProgress optional callback called with (currentCount, totalSamples)
         * @param loadFilter optional load-time filter snapshot (null = no load-time filtering)
         * @return the last inserted/updated node – pass it as startCursor for the next VCF
         */
        public VariantNode streamChromosomeVariantsToList(String chromosome, VariantList target,
            VariantNode startCursor, java.util.function.BiConsumer<Integer, Integer> onProgress,
            VariantFilter loadFilter, long chromosomeLength) throws IOException {
        
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
                double siteQual = snv.getQuality();
                List<String> alts = snv.getAlt();
                for (String alt : alts) {
                    for (Map.Entry<String, Integer> entry : vcfSampleToTrackIndex.entrySet()) {
                        VariantNode.SampleCall call = getSampleCallForAllele(
                            snv, entry.getKey(), entry.getValue(), alt);
                        if (call != null) {
                            if (loadFilter != null && !loadFilter.passesLoadTime(snv.getType(), siteQual, call)) {
                                continue;
                            }
                            int trackIdx = entry.getValue();
                            cursor[0] = target.addVariantWithCursor(cursor[0], snv.getPosition(),
                                snv.getRef(), alt, snv.getType(), call);
                            if (siteQual >= 0 && cursor[0].siteQuality < 0) cursor[0].siteQuality = siteQual;
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
                
                List<String> alts = sv.getAlt();
                Long svEnd = sv.getEnd();
                double siteQual = sv.getQuality();
                for (String alt : alts) {
                    for (Map.Entry<String, Integer> entry : vcfSampleToTrackIndex.entrySet()) {
                        VariantNode.SampleCall call = getSampleCallForAllele(
                            sv, entry.getKey(), entry.getValue(), alt);
                        if (call != null) {
                            if (loadFilter != null && !loadFilter.passesLoadTime(sv.getType(), siteQual, call)) {
                                continue;
                            }
                            int trackIdx = entry.getValue();
                            cursor[0] = target.addVariantWithCursor(cursor[0], sv.getPosition(),
                                sv.getRef(), alt, sv.getType(), call);
                            if (svEnd != null && cursor[0].svEnd < 0) cursor[0].svEnd = svEnd;
                            if (siteQual >= 0 && cursor[0].siteQuality < 0) cursor[0].siteQuality = siteQual;
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
            },
            chromosomeLength
        );
        
        if (onProgress != null && lastProgressCount[0] < totalSamples) {
            onProgress.accept(totalSamples, totalSamples);
        }
        return cursor[0];
    }

    /**
     * Stream variants for a specific genomic region directly into {@code target}.
     * Uses VCF index (.tbi/.csi) for efficient region seeking instead of scanning entire chromosome.
     * Useful for loading specific regions like gene coordinates or narrowly-focused searches.
     *
     * @param chromosome chromosome name
     * @param start region start (1-based inclusive)
     * @param end region end (1-based inclusive)
     * @param target VariantList to accumulate variants into
     * @param startCursor hint node to begin scanning from (null = scan from head)
     * @return the last inserted/updated node – pass it as startCursor for the next VCF
     */
    public VariantNode streamRegionVariantsToList(String chromosome, long start, long end,
            VariantList target, VariantNode startCursor) throws IOException {
        return streamRegionVariantsToList(chromosome, start, end, target, startCursor, null, null);
    }

    /**
     * Stream region variants with progress callback.
     */
    public VariantNode streamRegionVariantsToList(String chromosome, long start, long end,
            VariantList target, VariantNode startCursor,
            java.util.function.BiConsumer<Integer, Integer> onProgress) throws IOException {
        return streamRegionVariantsToList(chromosome, start, end, target, startCursor, onProgress, null);
    }

    public VariantNode streamRegionVariantsToList(String chromosome, long start, long end,
            VariantList target, VariantNode startCursor,
            java.util.function.BiConsumer<Integer, Integer> onProgress,
            VariantFilter loadFilter) throws IOException {
        
        VariantNode[] cursor = {startCursor};
        int[] svProcessed = {0};
        java.util.Set<Integer> samplesWithVariants = new java.util.HashSet<>();
        int[] variantCount = {0};
        int[] lastProgressCount = {0};
        int[] maxTrackRankSeen = {0};
        int totalSamples = Math.max(1, getMappedSampleCount());
        java.util.Map<Integer, Integer> progressRankByTrackIndex = new java.util.HashMap<>();
        int rank = 1;
        for (Integer trackIndex : new java.util.TreeSet<>(vcfSampleToTrackIndex.values())) {
            progressRankByTrackIndex.put(trackIndex, rank++);
        }

        if (onProgress != null) {
            onProgress.accept(0, totalSamples);
        }
        
        // Query region using VCF index for efficient seeking
        Map<String, Object> regionVariants = vcfReader.queryAllVariants(chromosome, start, end);
        if (regionVariants == null) {
            // No variants in region
            if (onProgress != null && lastProgressCount[0] < totalSamples) {
                onProgress.accept(totalSamples, totalSamples);
            }
            return cursor[0];
        }
        
        // Process SNVs/Indels
        @SuppressWarnings("unchecked")
        List<VcfSnvIndel> snvs = (List<VcfSnvIndel>) regionVariants.get("snvs");
        if (snvs != null) {
            for (VcfSnvIndel snv : snvs) {
                double siteQual = snv.getQuality();
                List<String> alts = snv.getAlt();
                for (String alt : alts) {
                    for (Map.Entry<String, Integer> entry : vcfSampleToTrackIndex.entrySet()) {
                        VariantNode.SampleCall call = getSampleCallForAllele(
                            snv, entry.getKey(), entry.getValue(), alt);
                        if (call != null) {
                            if (loadFilter != null && !loadFilter.passesLoadTime(snv.getType(), siteQual, call)) {
                                continue;
                            }
                            int trackIdx = entry.getValue();
                            cursor[0] = target.addVariantWithCursor(cursor[0], snv.getPosition(),
                                snv.getRef(), alt, snv.getType(), call);
                            if (siteQual >= 0 && cursor[0].siteQuality < 0) cursor[0].siteQuality = siteQual;
                            variantCount[0]++;
                            
                            Integer mappedRank = progressRankByTrackIndex.get(trackIdx);
                            if (mappedRank != null && mappedRank > maxTrackRankSeen[0]) {
                                maxTrackRankSeen[0] = mappedRank;
                            }
                            
                            if (samplesWithVariants.add(trackIdx)) {
                                int count = samplesWithVariants.size();
                                if (onProgress != null) {
                                if (totalSamples <= 100 || count == 1 || count % 10 == 0 || count == totalSamples) {
                                    onProgress.accept(count, totalSamples);
                                    lastProgressCount[0] = count;
                                }
                                }
                            } else if (onProgress != null && variantCount[0] % 50 == 0) {
                                int progressValue = Math.min(maxTrackRankSeen[0], totalSamples);
                                if (progressValue > lastProgressCount[0]) {
                                    onProgress.accept(progressValue, totalSamples);
                                    lastProgressCount[0] = progressValue;
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Process structural variants
        @SuppressWarnings("unchecked")
        List<VcfStructuralVariant> svs = (List<VcfStructuralVariant>) regionVariants.get("svs");
        if (svs != null) {
            for (VcfStructuralVariant sv : svs) {
                svProcessed[0]++;
                List<String> alts = sv.getAlt();
                Long svEnd = sv.getEnd();
                double siteQual = sv.getQuality();
                for (String alt : alts) {
                    for (Map.Entry<String, Integer> entry : vcfSampleToTrackIndex.entrySet()) {
                        VariantNode.SampleCall call = getSampleCallForAllele(
                            sv, entry.getKey(), entry.getValue(), alt);
                        if (call != null) {
                            if (loadFilter != null && !loadFilter.passesLoadTime(sv.getType(), siteQual, call)) {
                                continue;
                            }
                            int trackIdx = entry.getValue();
                            cursor[0] = target.addVariantWithCursor(cursor[0], sv.getPosition(),
                                sv.getRef(), alt, sv.getType(), call);
                            if (svEnd != null && cursor[0].svEnd < 0) cursor[0].svEnd = svEnd;
                            if (siteQual >= 0 && cursor[0].siteQuality < 0) cursor[0].siteQuality = siteQual;
                            variantCount[0]++;
                            
                            Integer mappedRank = progressRankByTrackIndex.get(trackIdx);
                            if (mappedRank != null && mappedRank > maxTrackRankSeen[0]) {
                                maxTrackRankSeen[0] = mappedRank;
                            }
                            
                            if (samplesWithVariants.add(trackIdx)) {
                                int count = samplesWithVariants.size();
                                if (onProgress != null) {
                                if (totalSamples <= 100 || count == 1 || count % 10 == 0 || count == totalSamples) {
                                    onProgress.accept(count, totalSamples);
                                    lastProgressCount[0] = count;
                                }
                                }
                            } else if (onProgress != null && variantCount[0] % 50 == 0) {
                                int progressValue = Math.min(maxTrackRankSeen[0], totalSamples);
                                if (progressValue > lastProgressCount[0]) {
                                    onProgress.accept(progressValue, totalSamples);
                                    lastProgressCount[0] = progressValue;
                                }
                            }
                        }
                    }
                }
            }
        }
        
        if (onProgress != null && lastProgressCount[0] < totalSamples) {
            onProgress.accept(totalSamples, totalSamples);
        }
        return cursor[0];
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
            int dp = gtMap.containsKey("DP") ? ((Number) gtMap.get("DP")).intValue() : calculateDepthFromAd(gtMap);
            double af = calculateAlleleFraction(gtMap, altAllele);
            // System.err.println("[VariantLoader.getSampleCallForAllele] Creating SampleCall for sample '" + sampleName + "', gt=" + gt + ", gq=" + gq + ", dp=" + dp + ", af=" + af);
            return new VariantNode.SampleCall(trackIndex, gt, gq, dp, af);
        }
        
        // GT contains allele bases (e.g. "G/A") or indices (e.g. "0/1"); skip if this alt is not present
        if (gt != null && !gtContainsAlt(gt, altAllele)) {
            // System.err.println("[VariantLoader.getSampleCallForAllele]   Alt not in GT, skipping");
            return null;
        }

        double gq = gtMap.containsKey("GQ") ? ((Number) gtMap.get("GQ")).doubleValue() : -1;
        int dp = gtMap.containsKey("DP") ? ((Number) gtMap.get("DP")).intValue() : calculateDepthFromAd(gtMap);
        double af = calculateAlleleFraction(gtMap, altAllele);
        // System.err.println("[VariantLoader.getSampleCallForAllele] Creating SampleCall for sample '" + sampleName + "', gt=" + gt + ", gq=" + gq + ", dp=" + dp + ", af=" + af);
        return new VariantNode.SampleCall(trackIndex, gt, gq, dp, af);
    }
    
    /**
     * If DP field is missing, calculate depth from AD (Allele Depth) field.
     * AD format: [ref_count, alt1_count, alt2_count, ...]
     * Returns sum of all counts, or -1 if AD not available.
     */
    private static int calculateDepthFromAd(Map<String, Object> gtMap) {
        if (!gtMap.containsKey("AD")) {
            return -1;
        }
        
        Object adObj = gtMap.get("AD");
        if (!(adObj instanceof int[])) {
            return -1;
        }
        
        int[] ad = (int[]) adObj;
        int totalDepth = 0;
        for (int count : ad) {
            totalDepth += count;
        }
        
        return totalDepth > 0 ? totalDepth : -1;
    }
    
    /**
     * Calculate allele fraction from AD (Allele Depth) field.
     * AD format: [ref_count, alt1_count, alt2_count, ...]
     * Returns alt_count / (ref_count + alt_count), or -1 if AD not available.
     */
    private static double calculateAlleleFraction(Map<String, Object> gtMap, String altAllele) {
        if (!gtMap.containsKey("AD")) {
            return -1.0;
        }
        
        Object adObj = gtMap.get("AD");
        if (!(adObj instanceof int[])) {
            return -1.0;
        }
        
        int[] ad = (int[]) adObj;
        if (ad.length < 2) {
            return -1.0;  // Need at least ref and one alt
        }
        
        int refCount = ad[0];
        int altCount = ad[1];  // Assuming first alt allele (multi-allelic sites split earlier)
        int totalCount = refCount + altCount;
        
        if (totalCount == 0) {
            return 0.0;
        }
        
        return (double) altCount / totalCount;
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

                if (trackName != null && (trackName.equals(vcfSample) || trackName.contains(vcfSample))) {
                    vcfSampleToTrackIndex.put(vcfSample, i);
                    found = true;
                    break;
                }

                for (Sample sample : track.getSamples()) {
                    String sampleName = sample.getName();
                    if (sampleName != null && (sampleName.equals(vcfSample) || sampleName.contains(vcfSample))) {
                        vcfSampleToTrackIndex.put(vcfSample, i);
                        found = true;
                        break;
                    }
                }

                if (found) {
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
