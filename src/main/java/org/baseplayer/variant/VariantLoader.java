package org.baseplayer.variant;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

public class VariantLoader {
    
    private VcfReader vcfReader; // non-final: released after header parse, set again for variant loading
    /** Absolute path of the VCF this loader streams (kept after the reader is closed). */
    private final Path vcfPath;
    private final List<String> vcfSampleNames;
    private final Map<String, Integer> vcfSampleToTrackIndex;
    private final List<String> unmappedSamples;
    private final int totalVcfSampleCount; // cached so reader can be released after construction
    private String detectedNormalSample = null;
    /** Notified for every variant record type seen while streaming (before load filters). */
    private Consumer<VcfVariantType> typeObserver;
    /**
     * When a type fails the load filter only because it was never allowed yet, admit it
     * if this predicate returns true (typically: not observed in the session before this load).
     */
    private Predicate<VcfVariantType> autoAdmitNewType;
    
    public VariantLoader(VcfReader vcfReader) {
        this.vcfReader = vcfReader;
        this.vcfPath = vcfReader != null ? vcfReader.getVcfPath() : null;
        this.vcfSampleNames = vcfReader != null
            ? List.copyOf(vcfReader.getSampleNames())
            : List.of();
        this.unmappedSamples = new ArrayList<>();
        detectSomaticVcf();
        this.vcfSampleToTrackIndex = buildSampleMapping();
        this.totalVcfSampleCount = this.vcfSampleNames.size();
    }

    public Path getVcfPath() {
        return vcfPath;
    }

    /**
     * Bind every eligible VCF sample column to {@code trackIndex} (skips detected normals).
     * Used when the user adds a VCF onto a specific track via the track "+" menu.
     */
    public void mapAllEligibleToTrack(int trackIndex) {
        vcfSampleToTrackIndex.clear();
        unmappedSamples.clear();
        if (trackIndex < 0) {
            return;
        }
        for (String vcfSample : vcfSampleNames) {
            if (detectedNormalSample != null && detectedNormalSample.equals(vcfSample)) {
                continue;
            }
            vcfSampleToTrackIndex.put(vcfSample, trackIndex);
        }
    }

    public void setTypeObserver(Consumer<VcfVariantType> typeObserver) {
        this.typeObserver = typeObserver;
    }

    public void setAutoAdmitNewType(Predicate<VcfVariantType> autoAdmitNewType) {
        this.autoAdmitNewType = autoAdmitNewType;
    }

    private void observeType(VcfVariantType type) {
        if (typeObserver != null && type != null) {
            typeObserver.accept(type);
        }
    }

    public void setVcfReader(VcfReader reader) {
        this.vcfReader = reader;
    }

    private boolean passesLoadFilter(
            VariantFilter loadFilter,
            VcfVariantType type,
            double siteQual,
            VariantNode.SampleCall call,
            long svLengthBp) {
        if (loadFilter == null) {
            return true;
        }
        if (!loadFilter.allowsType(type)
                && autoAdmitNewType != null
                && autoAdmitNewType.test(type)) {
            loadFilter.admitType(type);
        }
        return svLengthBp >= 0
            ? loadFilter.passesLoadTime(type, siteQual, call, svLengthBp)
            : loadFilter.passesLoadTime(type, siteQual, call);
    }
    
    private void detectSomaticVcf() {
        List<String> vcfSamples = vcfSampleNames;
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
        List<String> vcfSamples = vcfSampleNames;
        
        for (String vcfSample : vcfSamples) {
            // Skip normal sample in somatic VCF
            if (detectedNormalSample != null && vcfSample.equals(detectedNormalSample)) {
                continue;
            }
            int trackIndex = registry.findTrackIndexMatchingName(vcfSample);
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
                observeType(snv.getType());
                double siteQual = snv.getQuality();
                List<String> alts = snv.getAlt();
                for (String alt : alts) {
                    for (Map.Entry<String, Integer> entry : vcfSampleToTrackIndex.entrySet()) {
                        VariantNode.SampleCall call = getSampleCallForAllele(
                            snv, entry.getKey(), entry.getValue(), alt);
                        if (call != null) {
                            if (!passesLoadFilter(loadFilter, snv.getType(), siteQual, call, -1)) {
                                continue;
                            }
                            int trackIdx = entry.getValue();
                            cursor[0] = target.addVariantWithCursor(cursor[0], snv.getPosition(),
                                snv.getRef(), alt, snv.getType(), call);
                            applyRecordMeta(cursor[0], snv.getId(), snv.getFilters(), snv.getInfo());
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
                // FACETS tiles the whole genome with copy-neutral segments that share
                // SNP-bin edges across samples — skip those; keep somatic gain/loss only.
                if (VariantTypeVisuals.isCnv(sv.getType()) && !VariantTypeVisuals.isCnvEvent(sv.getType())) {
                    return;
                }
                observeType(sv.getType());
                svProcessed[0]++;
                
                List<String> alts = sv.getAlt();
                double siteQual = sv.getQuality();
                for (String alt : alts) {
                    for (Map.Entry<String, Integer> entry : vcfSampleToTrackIndex.entrySet()) {
                        VariantNode.SampleCall call = getSampleCallForAllele(
                            sv, entry.getKey(), entry.getValue(), alt);
                        if (call != null) {
                            if (!passesLoadFilter(loadFilter, sv.getType(), siteQual, call, svLengthBp(sv))) {
                                continue;
                            }
                            int trackIdx = entry.getValue();
                            cursor[0] = target.addVariantWithCursor(cursor[0], sv.getPosition(),
                                sv.getRef(), alt, sv.getType(), call);
                            applySvFields(cursor[0], sv, alt);
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
                observeType(snv.getType());
                double siteQual = snv.getQuality();
                List<String> alts = snv.getAlt();
                for (String alt : alts) {
                    for (Map.Entry<String, Integer> entry : vcfSampleToTrackIndex.entrySet()) {
                        VariantNode.SampleCall call = getSampleCallForAllele(
                            snv, entry.getKey(), entry.getValue(), alt);
                        if (call != null) {
                            if (!passesLoadFilter(loadFilter, snv.getType(), siteQual, call, -1)) {
                                continue;
                            }
                            int trackIdx = entry.getValue();
                            cursor[0] = target.addVariantWithCursor(cursor[0], snv.getPosition(),
                                snv.getRef(), alt, snv.getType(), call);
                            applyRecordMeta(cursor[0], snv.getId(), snv.getFilters(), snv.getInfo());
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
                if (VariantTypeVisuals.isCnv(sv.getType()) && !VariantTypeVisuals.isCnvEvent(sv.getType())) {
                    continue;
                }
                observeType(sv.getType());
                svProcessed[0]++;
                List<String> alts = sv.getAlt();
                double siteQual = sv.getQuality();
                for (String alt : alts) {
                    for (Map.Entry<String, Integer> entry : vcfSampleToTrackIndex.entrySet()) {
                        VariantNode.SampleCall call = getSampleCallForAllele(
                            sv, entry.getKey(), entry.getValue(), alt);
                        if (call != null) {
                            if (!passesLoadFilter(loadFilter, sv.getType(), siteQual, call, svLengthBp(sv))) {
                                continue;
                            }
                            int trackIdx = entry.getValue();
                            cursor[0] = target.addVariantWithCursor(cursor[0], sv.getPosition(),
                                sv.getRef(), alt, sv.getType(), call);
                            applySvFields(cursor[0], sv, alt);
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

    /** Copy SV span, mate coordinates, and site INFO onto the just-inserted node. */
    private static void applySvFields(VariantNode node, VcfStructuralVariant sv, String alt) {
        if (node == null || sv == null) {
            return;
        }
        applyRecordMeta(node, sv.getId(), sv.getFilters(), sv.getInfo());
        Long svEnd = sv.getEnd();
        if (svEnd != null && node.svEnd < 0) {
            node.svEnd = svEnd;
        }
        if (node.svChr2 == null || node.svChr2.isBlank()) {
            String chr2 = sv.getChr2();
            if (chr2 == null || chr2.isBlank()) {
                BreakendAlt.Mate mate = BreakendAlt.parse(alt);
                if (mate != null) {
                    chr2 = mate.chrom();
                }
            }
            if (chr2 != null && !chr2.isBlank()) {
                node.svChr2 = chr2;
            }
        }
        if (node.svEnd2 < 0) {
            Long end2 = sv.getEnd2();
            if (end2 == null) {
                BreakendAlt.Mate mate = BreakendAlt.parse(alt);
                if (mate != null) {
                    end2 = mate.pos();
                }
            }
            if (end2 != null && end2 >= 0) {
                node.svEnd2 = end2;
            }
        }
    }

    /** Copy VCF ID / FILTER / INFO onto the node once (first sample merge wins). */
    private static void applyRecordMeta(
        VariantNode node,
        String id,
        List<String> filters,
        Map<String, Object> info) {
        if (node == null) {
            return;
        }
        if (node.vcfId == null && id != null && !id.isBlank() && !".".equals(id)) {
            node.vcfId = id;
        }
        if (node.vcfFilter == null && filters != null && !filters.isEmpty()) {
            node.vcfFilter = String.join(";", filters);
        }
        node.setInfoFieldsIfAbsent(stringifyInfoMap(info));
    }

    private static final Set<String> SAMPLE_CALL_CORE_KEYS = Set.of(
        "GT", "GQ", "DP", "AF", "isHomRef", "isNoCall", "isHet", "isHomAlt", "alleles");

    private static Map<String, String> stringifyInfoMap(Map<String, Object> info) {
        if (info == null || info.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : info.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            out.put(entry.getKey(), formatInfoValue(entry.getValue()));
        }
        return out.isEmpty() ? Map.of() : out;
    }

    private static Map<String, String> extractExtraFormatFields(Map<String, Object> gtMap) {
        if (gtMap == null || gtMap.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : gtMap.entrySet()) {
            String key = entry.getKey();
            if (key == null || SAMPLE_CALL_CORE_KEYS.contains(key)) {
                continue;
            }
            out.put(key, formatInfoValue(entry.getValue()));
        }
        return out.isEmpty() ? Map.of() : out;
    }

    private static String formatInfoValue(Object value) {
        if (value == null) {
            return ".";
        }
        if (value instanceof Object[] arr) {
            return Arrays.stream(arr).map(VariantLoader::formatInfoValue).collect(Collectors.joining(","));
        }
        if (value instanceof int[] arr) {
            return Arrays.stream(arr).mapToObj(Integer::toString).collect(Collectors.joining(","));
        }
        if (value instanceof long[] arr) {
            return Arrays.stream(arr).mapToObj(Long::toString).collect(Collectors.joining(","));
        }
        if (value instanceof double[] arr) {
            return Arrays.stream(arr).mapToObj(VariantLoader::formatInfoNumber)
                .collect(Collectors.joining(","));
        }
        if (value instanceof float[] arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(formatInfoNumber(arr[i]));
            }
            return sb.toString();
        }
        if (value instanceof Collection<?> coll) {
            return coll.stream().map(VariantLoader::formatInfoValue).collect(Collectors.joining(","));
        }
        if (value instanceof Number number) {
            return formatInfoNumber(number.doubleValue());
        }
        return String.valueOf(value);
    }

    private static String formatInfoNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return String.valueOf(value);
        }
        if (Math.rint(value) == value) {
            return String.format("%.0f", value);
        }
        return String.format("%.6g", value);
    }

    private static long svLengthBp(VcfStructuralVariant sv) {
        if (sv == null) {
            return -1;
        }
        if (sv.getSvLen() != null) {
            return Math.abs(sv.getSvLen());
        }
        Long end = sv.getEnd();
        if (end != null && end > sv.getPosition()) {
            return end - sv.getPosition();
        }
        return -1;
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

        // Skip HomRef / NoCall at load. In LOH mode, missing calls at marker-het
        // sites are treated as implied AA at compare/display time (not stored).
        if (Boolean.TRUE.equals(isNoCall) || Boolean.TRUE.equals(isHomRef)) {
            return null;
        }

        String gt = (String) gtMap.get("GT");

        // For SV breakends with GT=NA, accept them as present (NA = variant found)
        if ("NA".equalsIgnoreCase(gt)) {
            double gq = gtMap.containsKey("GQ") ? ((Number) gtMap.get("GQ")).doubleValue() : -1;
            int dp = gtMap.containsKey("DP") ? ((Number) gtMap.get("DP")).intValue() : calculateDepthFromAd(gtMap);
            double af = calculateAlleleFraction(gtMap, altAllele);
            return new VariantNode.SampleCall(
                trackIndex, resolveVcfSample(trackIndex), gt, gq, dp, af,
                extractExtraFormatFields(gtMap));
        }
        
        // GT contains allele bases (e.g. "G/A") or indices (e.g. "0/1"); skip if this alt is not present
        if (gt != null && !gtContainsAlt(gt, altAllele)) {
            return null;
        }

        double gq = gtMap.containsKey("GQ") ? ((Number) gtMap.get("GQ")).doubleValue() : -1;
        int dp = gtMap.containsKey("DP") ? ((Number) gtMap.get("DP")).intValue() : calculateDepthFromAd(gtMap);
        double af = calculateAlleleFraction(gtMap, altAllele);
        return new VariantNode.SampleCall(
            trackIndex, resolveVcfSample(trackIndex), gt, gq, dp, af,
            extractExtraFormatFields(gtMap));
    }

    /**
     * Prefer the VCF {@link Sample} on the track that matches this loader's file path,
     * so visibility/transparent toggles stay per-file when multiple VCFs share a track.
     */
    private Sample resolveVcfSample(int trackIndex) {
        if (trackIndex < 0) {
            return null;
        }
        try {
            SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
            if (trackIndex >= registry.getSampleTracks().size()) {
                return null;
            }
            SampleTrack track = registry.getSampleTracks().get(trackIndex);
            String pathKey = normalizePathKey(vcfPath);
            Sample fallback = null;
            for (Sample sample : track.getSamples()) {
                if (sample == null || sample.getDataType() != Sample.DataType.VCF) {
                    continue;
                }
                if (fallback == null) {
                    fallback = sample;
                }
                if (pathKey != null && sample.getPath() != null
                    && pathKey.equals(normalizePathKey(sample.getPath()))) {
                    return sample;
                }
            }
            return fallback;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String normalizePathKey(Path path) {
        if (path == null) {
            return null;
        }
        try {
            return path.toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return path.toString();
        }
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
        // Site-only FACETS CNVs stash cellular fraction as AF (from CF_EM).
        Object afObj = gtMap.get("AF");
        if (afObj instanceof Number number) {
            return number.doubleValue();
        }
        if (afObj instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
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
            int trackIndex = registry.findTrackIndexMatchingName(vcfSample);
            if (trackIndex >= 0) {
                vcfSampleToTrackIndex.put(vcfSample, trackIndex);
            } else {
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
