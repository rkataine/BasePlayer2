package org.baseplayer.io.readers;

import htsjdk.tribble.AbstractFeatureReader;
import htsjdk.tribble.readers.LineIterator;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFCodec;
import htsjdk.variant.vcf.VCFHeader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.baseplayer.variant.BreakendAlt;
import org.baseplayer.variant.VcfSnvIndel;
import org.baseplayer.variant.VcfStructuralVariant;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.utils.ChromosomeNames;

public class VcfReader implements AutoCloseable {
    
    private final Path vcfPath;
    private final AbstractFeatureReader<VariantContext, LineIterator> reader;
    private final VCFHeader header;
    private final List<String> sampleNames;
    private final boolean canTabixQuery;
    private final boolean hasTbiOrCsiIndex;
    private final String chromPrefix;

    public VcfReader(Path vcfPath) throws IOException {
        this.vcfPath = vcfPath;
        
        if (!Files.exists(vcfPath)) {
            throw new IOException("VCF file not found: " + vcfPath);
        }

        Path tabixIndex = findSiblingIndex(vcfPath, ".tbi");
        Path csiIndex = findSiblingIndex(vcfPath, ".csi");
        this.hasTbiOrCsiIndex = tabixIndex != null || csiIndex != null;

        if (tabixIndex != null) {
            this.reader = AbstractFeatureReader.getFeatureReader(
                vcfPath.toString(),
                tabixIndex.toString(),
                new VCFCodec(),
                true);
            this.canTabixQuery = true;
        } else {
            this.reader = AbstractFeatureReader.getFeatureReader(
                vcfPath.toString(),
                new VCFCodec(),
                false);
            this.canTabixQuery = false;
        }
        
        this.header = (VCFHeader) reader.getHeader();
        this.sampleNames = header.getSampleNamesInOrder();
        this.chromPrefix = ChromosomeNames.detectPrefix(getAvailableChromosomes());
    }

    /** Contig prefix in this VCF ({@code ""} or {@code "chr"}). */
    public String getChromPrefix() {
        return chromPrefix;
    }

    /** Internal chrom → contig name used in this VCF file. */
    public String toDataChrom(String chromosome) {
        return ChromosomeNames.forData(chromosome, chromPrefix);
    }

    private static Path findSiblingIndex(Path vcfPath, String extension) {
        Path direct = Path.of(vcfPath.toString() + extension);
        if (Files.exists(direct)) {
            return direct;
        }
        String name = vcfPath.toString();
        if (name.endsWith(".gz")) {
            Path withoutGz = Path.of(name.substring(0, name.length() - 3) + extension);
            if (Files.exists(withoutGz)) {
                return withoutGz;
            }
        }
        return null;
    }

    public boolean canTabixQuery() {
        return canTabixQuery;
    }

    public boolean hasTbiOrCsiIndex() {
        return hasTbiOrCsiIndex;
    }

    public VCFHeader getHeader() {
        return header;
    }

    public List<String> getSampleNames() {
        return sampleNames;
    }

    private void visitOverlappingVariants(String chromosome, long start, long end,
            Consumer<VariantContext> consumer) throws IOException {
        String dataChrom = toDataChromosome(chromosome);
        int queryStart = (int) Math.max(1, start);
        int queryEnd = (int) Math.min(Integer.MAX_VALUE, Math.max(queryStart, end));

        if (canTabixQuery) {
            visitWithTabixQuery(dataChrom, queryStart, queryEnd, consumer);
        } else {
            visitWithSortedScan(dataChrom, queryStart, queryEnd, consumer);
        }
    }

    private void visitWithTabixQuery(String chromosome, int start, int end,
            Consumer<VariantContext> consumer) throws IOException {
        try (var iterator = reader.query(chromosome, start, end)) {
            while (iterator.hasNext()) {
                consumer.accept(iterator.next());
            }
        } catch (Exception e) {
            throw new IOException("Failed to query VCF: " + e.getMessage(), e);
        }
    }

    private void visitWithSortedScan(String chromosome, int start, int end,
            Consumer<VariantContext> consumer) throws IOException {
        try (var iterator = reader.iterator()) {
            boolean reachedChromosome = false;
            while (iterator.hasNext()) {
                VariantContext ctx = iterator.next();
                if (!ctx.getContig().equals(chromosome)) {
                    if (reachedChromosome) {
                        break;
                    }
                    continue;
                }
                reachedChromosome = true;
                if (ctx.getEnd() < start) {
                    continue;
                }
                if (ctx.getStart() > end) {
                    break;
                }
                consumer.accept(ctx);
            }
        } catch (Exception e) {
            throw new IOException("Failed to scan VCF: " + e.getMessage(), e);
        }
    }

    public List<VcfSnvIndel> querySnvsAndIndelsForChromosome(String chromosome) 
            throws IOException {
        List<VcfSnvIndel> variants = new ArrayList<>();
        
        // Map internal chrom to this VCF's contig naming
        String dataChrom = toDataChromosome(chromosome);
        
        // Iterate through entire VCF and collect variants for this chromosome
        try (var iterator = reader.iterator()) {
            boolean foundChromosome = false;
            
            while (iterator.hasNext()) {
                VariantContext ctx = iterator.next();
                
                // Check if we're on the target chromosome
                if (ctx.getContig().equals(dataChrom)) {
                    foundChromosome = true;
                    
                    // Skip structural variants
                    if (!isStructuralVariant(ctx)) {
                        VcfVariantType type = classifySnvIndel(ctx);
                        VcfSnvIndel variant = parseSnvIndel(ctx, type);
                        variants.add(variant);
                    }
                    
                } else if (foundChromosome) {
                    // We've passed the target chromosome (VCF is sorted), stop
                    break;
                }
            }
        } catch (Exception e) {
            // System.err.println("VCF iteration error for " + chromosome);
            // System.err.println("  Error: " + e.getMessage());
            throw new IOException("Failed to iterate VCF: " + e.getMessage(), e);
        }
        
        return variants;
    }
    
    public List<VcfSnvIndel> querySnvsAndIndels(String chromosome, long start, long end) 
            throws IOException {
        List<VcfSnvIndel> variants = new ArrayList<>();
        visitOverlappingVariants(chromosome, start, end, ctx -> {
            if (!isStructuralVariant(ctx)) {
                variants.add(parseSnvIndel(ctx, classifySnvIndel(ctx)));
            }
        });
        return variants;
    }

    public List<VcfStructuralVariant> queryStructuralVariantsForChromosome(String chromosome) 
            throws IOException {
        // System.err.println("[VcfReader.queryStructuralVariantsForChromosome] Querying SVs for: " + chromosome);
        List<VcfStructuralVariant> variants = new ArrayList<>();
        
        // Map internal chrom to this VCF's contig naming
        String dataChrom = toDataChromosome(chromosome);
        
        // Iterate through entire VCF and collect structural variants for this chromosome
        try (var iterator = reader.iterator()) {
            boolean foundChromosome = false;
            
            while (iterator.hasNext()) {
                VariantContext ctx = iterator.next();
                
                // Check if we're on the target chromosome
                if (ctx.getContig().equals(dataChrom)) {
                    foundChromosome = true;
                    
                    // Only include structural variants
                    if (isStructuralVariant(ctx)) {
                        // System.err.println("[VcfReader.queryStructuralVariantsForChromosome] Found SV #" + svCount + ": pos=" + ctx.getStart() + ", alt=" + ctx.getAlternateAlleles() + ", SVTYPE=" + ctx.getAttributeAsString("SVTYPE", "?"));
                        VcfVariantType type = classifyStructuralVariant(ctx);
                        VcfStructuralVariant variant = parseStructuralVariant(ctx, type);
                        variants.add(variant);
                    }
                } else if (foundChromosome) {
                    // We've passed the target chromosome (VCF is sorted), stop
                    // System.err.println("[VcfReader.queryStructuralVariantsForChromosome] Passed chromosome, stopping iteration");
                    break;
                }
            }
            // System.err.println("[VcfReader.queryStructuralVariantsForChromosome] Scanned " + totalVariants + " total variants, found " + svCount + " SVs for " + normalizedChrom);
        }
        
        // System.err.println("[VcfReader.queryStructuralVariantsForChromosome] Returning " + variants.size() + " structural variants");
        return variants;
    }

    public void iterateChromosomeVariants(String chromosome,
            Consumer<VcfSnvIndel> snvConsumer,
            Consumer<VcfStructuralVariant> svConsumer,
            long chromosomeLength) throws IOException {
        long end = chromosomeLength > 0 ? chromosomeLength : Integer.MAX_VALUE;
        visitOverlappingVariants(chromosome, 1, end, ctx -> {
            if (isStructuralVariant(ctx)) {
                if (svConsumer != null) {
                    svConsumer.accept(parseStructuralVariant(ctx, classifyStructuralVariant(ctx)));
                }
            } else if (snvConsumer != null) {
                snvConsumer.accept(parseSnvIndel(ctx, classifySnvIndel(ctx)));
            }
        });
    }

    public List<VcfStructuralVariant> queryStructuralVariants(String chromosome, long start, long end) 
            throws IOException {
        List<VcfStructuralVariant> variants = new ArrayList<>();
        visitOverlappingVariants(chromosome, start, end, ctx -> {
            if (isStructuralVariant(ctx)) {
                variants.add(parseStructuralVariant(ctx, classifyStructuralVariant(ctx)));
            }
        });
        return variants;
    }

    public Map<String, Object> queryAllVariants(String chromosome, long start, long end) 
            throws IOException {
        List<VcfSnvIndel> snvs = new ArrayList<>();
        List<VcfStructuralVariant> svs = new ArrayList<>();
        visitOverlappingVariants(chromosome, start, end, ctx -> {
            if (isStructuralVariant(ctx)) {
                svs.add(parseStructuralVariant(ctx, classifyStructuralVariant(ctx)));
            } else {
                snvs.add(parseSnvIndel(ctx, classifySnvIndel(ctx)));
            }
        });
        Map<String, Object> result = new HashMap<>();
        result.put("snvs", snvs);
        result.put("svs", svs);
        return result;
    }

    private boolean isStructuralVariant(VariantContext ctx) {
        // Check for symbolic alleles (e.g., <DEL>, <INS>, <DUP>)
        if (ctx.isSymbolic()) {
            return true;
        }
        
        // Check for SVTYPE in INFO field
        if (ctx.hasAttribute("SVTYPE")) {
            return true;
        }
        
        // Check for breakend notation (brackets in ALT)
        for (var allele : ctx.getAlternateAlleles()) {
            String alt = allele.getDisplayString();
            if (alt.contains("[") || alt.contains("]")) {
                return true;
            }
        }
        
        // Large indels (>50bp) can be considered SVs in some contexts
        // but for now we'll keep them as regular indels
        return false;
    }

    private VcfVariantType classifySnvIndel(VariantContext ctx) {
        // Check if it's a simple variant
        if (ctx.isSNP()) {
            return VcfVariantType.SNV;
        }
        
        if (ctx.isSimpleInsertion()) {
            return VcfVariantType.INSERTION;
        }
        
        if (ctx.isSimpleDeletion()) {
            return VcfVariantType.DELETION;
        }
        
        if (ctx.isMNP()) {
            return VcfVariantType.MNV;
        }
        
        return VcfVariantType.COMPLEX;
    }

    /**
     * Classify a structural variant based on SVTYPE or ALT allele.
     * Special handling for BND (breakend) types: classify as INV if mate is on same chromosome,
     * or TRA if mate is on different chromosome.
     */
    private VcfVariantType classifyStructuralVariant(VariantContext ctx) {
        // Check for SVCLASS first (if provided by caller like Manta)
        String svClass = ctx.getAttributeAsString("SVCLASS", null);
        if (svClass != null) {
            switch (svClass.toUpperCase()) {
                case "INVERSION":
                    return VcfVariantType.SV_INVERSION;
                case "TRANSLOCATION":
                    return VcfVariantType.SV_TRANSLOCATION;
                case "DELETION":
                    return VcfVariantType.SV_DELETION;
                case "INSERTION":
                    return VcfVariantType.SV_INSERTION;
                case "DUPLICATION":
                case "TANDEM-DUPLICATION":
                case "TANDEM_DUPLICATION":
                    return VcfVariantType.SV_DUPLICATION;
                // If SVCLASS is something else, fall through to SVTYPE check
            }
        }
        
        String svType = ctx.getAttributeAsString("SVTYPE", null);
        // System.err.println("[VcfReader.classifyStructuralVariant] pos=" + ctx.getStart() + ", SVTYPE=" + svType);
        
        if (svType != null) {
            switch (svType.toUpperCase()) {
                case "DEL": 
                    // System.err.println("[VcfReader.classifyStructuralVariant]   -> SV_DELETION");
                    return VcfVariantType.SV_DELETION;
                case "INS": 
                    // System.err.println("[VcfReader.classifyStructuralVariant]   -> SV_INSERTION");
                    return VcfVariantType.SV_INSERTION;
                case "DUP": 
                    // System.err.println("[VcfReader.classifyStructuralVariant]   -> SV_DUPLICATION");
                    return VcfVariantType.SV_DUPLICATION;
                case "INV": 
                    // System.err.println("[VcfReader.classifyStructuralVariant]   -> SV_INVERSION");
                    return VcfVariantType.SV_INVERSION;
                case "BND":
                    // Breakend: classify as translocation if mate is on different chromosome
                    String chr2 = ctx.getAttributeAsString("CHR2", null);
                    if (chr2 != null) {
                        if (!sameChromosome(chr2, ctx.getContig())) {
                            return VcfVariantType.SV_TRANSLOCATION;
                        }
                    } else {
                        for (var allele : ctx.getAlternateAlleles()) {
                            BreakendAlt.Mate mate = BreakendAlt.parse(allele.getDisplayString());
                            if (mate != null && !sameChromosome(mate.chrom(), ctx.getContig())) {
                                return VcfVariantType.SV_TRANSLOCATION;
                            }
                        }
                    }
                    // Same chromosome or CHR2 not available: keep as breakend
                    return VcfVariantType.SV_BREAKEND;
                case "TRA": case "CTX": 
                    // System.err.println("[VcfReader.classifyStructuralVariant]   -> SV_TRANSLOCATION");
                    return VcfVariantType.SV_TRANSLOCATION;
            }
        }
        
        // Infer from symbolic alleles
        for (var allele : ctx.getAlternateAlleles()) {
            String alt = allele.getDisplayString();
            if (alt.startsWith("<")) {
                String symbolic = alt.substring(1, alt.length() - 1).toUpperCase();
                if (symbolic.startsWith("DEL")) {
                    // System.err.println("[VcfReader.classifyStructuralVariant]   -> SV_DELETION (from ALT)");
                    return VcfVariantType.SV_DELETION;
                }
                if (symbolic.startsWith("INS")) {
                    // System.err.println("[VcfReader.classifyStructuralVariant]   -> SV_INSERTION (from ALT)");
                    return VcfVariantType.SV_INSERTION;
                }
                if (symbolic.startsWith("DUP")) {
                    // System.err.println("[VcfReader.classifyStructuralVariant]   -> SV_DUPLICATION (from ALT)");
                    return VcfVariantType.SV_DUPLICATION;
                }
                if (symbolic.startsWith("INV")) {
                    // System.err.println("[VcfReader.classifyStructuralVariant]   -> SV_INVERSION (from ALT)");
                    return VcfVariantType.SV_INVERSION;
                }
            }
            
            // Breakend notation (fallback if not caught above)
            if (alt.contains("[") || alt.contains("]")) {
                // Keep BND as-is without trying to deduce INV/TRA
                // BND classification is too unreliable - many may be noise or from homologous regions
                return VcfVariantType.SV_BREAKEND;
            }
        }
        
        return VcfVariantType.COMPLEX;
    }
    
    private static boolean sameChromosome(String a, String b) {
        return ChromosomeNames.equals(a, b);
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    /**
     * Parse a SNV/indel variant.
     */
    private VcfSnvIndel parseSnvIndel(VariantContext ctx, VcfVariantType type) {
        return new VcfSnvIndel(
            ChromosomeNames.strip(ctx.getContig()),
            ctx.getStart(),
            ctx.getID(),
            ctx.getReference().getDisplayString(),
            ctx.getAlternateAlleles().stream()
                .map(a -> a.getDisplayString())
                .collect(Collectors.toList()),
            ctx.getPhredScaledQual(),
            new ArrayList<>(ctx.getFilters()),
            parseInfoFields(ctx),
            type,
            parseGenotypes(ctx)
        );
    }

    /**
     * Parse a structural variant.
     */
    private VcfStructuralVariant parseStructuralVariant(VariantContext ctx, VcfVariantType type) {
        // Extract SV-specific fields
        Long end = ctx.hasAttribute("END") ? 
            Long.parseLong(ctx.getAttributeAsString("END", null)) : null;
        
        Integer svLen = ctx.hasAttribute("SVLEN") ? 
            ctx.getAttributeAsInt("SVLEN", 0) : null;

        String svType = ctx.getAttributeAsString("SVTYPE", null);
        String chr2 = ctx.getAttributeAsString("CHR2", null);

        Long end2 = null;
        if (ctx.hasAttribute("END2")) {
            end2 = Long.parseLong(ctx.getAttributeAsString("END2", null));
        } else if (ctx.hasAttribute("POS2")) {
            end2 = Long.parseLong(ctx.getAttributeAsString("POS2", null));
        }

        for (var allele : ctx.getAlternateAlleles()) {
            BreakendAlt.Mate mate = BreakendAlt.parse(allele.getDisplayString());
            if (mate == null) {
                continue;
            }
            if (chr2 == null) {
                chr2 = mate.chrom();
            }
            if (end2 == null) {
                end2 = mate.pos();
            }
            break;
        }

        boolean interChrom = chr2 != null && !sameChromosome(chr2, ctx.getContig());
        if (interChrom) {
            // Delly-style TRA: INFO/END is the mate coordinate on CHR2, not a same-chrom span.
            if (end2 == null && end != null) {
                end2 = end;
            }
            end = null;
        } else if (end == null && (type == VcfVariantType.SV_DELETION || type == VcfVariantType.SV_INSERTION
                || type == VcfVariantType.SV_DUPLICATION || type == VcfVariantType.SV_INVERSION)
                && end2 != null) {
            long pos = ctx.getStart();
            end = end2 > pos ? end2 : pos;
        }
        
        return new VcfStructuralVariant(
            ChromosomeNames.strip(ctx.getContig()),
            ctx.getStart(),
            ctx.getID(),
            ctx.getReference().getDisplayString(),
            ctx.getAlternateAlleles().stream()
                .map(a -> a.getDisplayString())
                .collect(Collectors.toList()),
            ctx.getPhredScaledQual(),
            new ArrayList<>(ctx.getFilters()),
            parseInfoFields(ctx),
            type,
            end,
            svLen,
            svType,
            chr2 != null ? ChromosomeNames.strip(chr2) : null,
            end2,
            parseGenotypes(ctx)
        );
    }

    /**
     * Parse INFO fields into a map.
     */
    private Map<String, Object> parseInfoFields(VariantContext ctx) {
        Map<String, Object> info = new HashMap<>();
        
        for (String key : ctx.getAttributes().keySet()) {
            Object value = ctx.getAttribute(key);
            info.put(key, value);
        }
        
        return info;
    }

    /**
     * Parse genotype information for all samples.
     */
    private Map<String, Map<String, Object>> parseGenotypes(VariantContext ctx) {
        if (!ctx.hasGenotypes()) {
            return Map.of();
        }
        
        Map<String, Map<String, Object>> genotypes = new HashMap<>();
        boolean structural = isStructuralVariant(ctx);
        
        for (Genotype gt : ctx.getGenotypes()) {
            Map<String, Object> gtMap = new HashMap<>();
            
            gtMap.put("GT", gt.getGenotypeString());
            
            // Add genotype type flags for easier filtering
            gtMap.put("isHomRef", gt.isHomRef());      // Homozygous reference (e.g., C/C when ref=C)
            gtMap.put("isHet", gt.isHet());            // Heterozygous (e.g., C/T)
            gtMap.put("isHomVar", gt.isHomVar());      // Homozygous variant (e.g., T/T when ref=C)
            gtMap.put("isNoCall", gt.isNoCall());      // Missing genotype (./.)
            
            if (gt.hasDP()) {
                gtMap.put("DP", gt.getDP());
            }
            
            if (gt.hasGQ()) {
                gtMap.put("GQ", gt.getGQ());
            }
            
            if (gt.hasAD()) {
                gtMap.put("AD", gt.getAD());
            }
            
            if (gt.hasPL()) {
                gtMap.put("PL", gt.getPL());
            }
            
            for (String key : gt.getExtendedAttributes().keySet()) {
                gtMap.put(key, gt.getExtendedAttribute(key));
            }

            if (structural && (Boolean.TRUE.equals(gtMap.get("isNoCall")) || gt.isHomRef())
                    && hasPositiveSvEvidenceCount(gtMap)) {
                gtMap.put("isNoCall", false);
                gtMap.put("isHomRef", false);
                gtMap.put("GT", "NA");
            }
            
            genotypes.put(gt.getSampleName(), gtMap);
        }
        
        return genotypes;
    }

    private static boolean hasPositiveSvEvidenceCount(Map<String, Object> gtMap) {
        String[] evidenceFields = {"PS", "RC", "PR", "SR", "DV", "VR", "DR", "RR", "ASRQ", "PE", "BE"};
        for (String field : evidenceFields) {
            Object value = gtMap.get(field);
            if (value == null) {
                continue;
            }
            if (value instanceof Number number && number.doubleValue() > 0) {
                return true;
            }
            if (value instanceof String text) {
                try {
                    if (Double.parseDouble(text.trim()) > 0) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
    }

    /**
     * Get the path to the VCF file.
     */
    public Path getVcfPath() {
        return vcfPath;
    }
    
    /**
     * Get list of chromosomes (contigs) available in the VCF file.
     */
    public List<String> getAvailableChromosomes() {
        return header.getContigLines().stream()
            .map(line -> line.getID())
            .collect(Collectors.toList());
    }
    
    /**
     * Get the length of a chromosome from the VCF header.
     * Returns null if the chromosome is not found or has no length specified.
     * 
     * @param chromosome Chromosome name (must match exactly what's in the VCF)
     * @return Chromosome length, or null if not available
     */
    public Long getChromosomeLength(String chromosome) {
        return header.getContigLines().stream()
            .filter(line -> line.getID().equals(chromosome))
            .findFirst()
            .map(line -> {
                // Try to get length from SAMSequenceRecord
                Integer length = line.getSAMSequenceRecord() != null ? 
                    line.getSAMSequenceRecord().getSequenceLength() : null;
                return length != null ? length.longValue() : null;
            })
            .orElse(null);
    }
    
    /**
     * Map an internal (unprefixed) chromosome name to this VCF's contig naming.
     */
    private String toDataChromosome(String chromosome) throws IOException {
        String dataChrom = toDataChrom(chromosome);
        List<String> availableChromosomes = getAvailableChromosomes();
        if (availableChromosomes.contains(dataChrom)) {
            return dataChrom;
        }
        // Contig list may use a different casing / M vs MT — fall back to strip-equality.
        for (String available : availableChromosomes) {
            if (ChromosomeNames.equals(available, chromosome)) {
                return available;
            }
        }
        throw new IOException("Chromosome '" + chromosome + "' not found in VCF. " +
            "Available chromosomes: " + availableChromosomes);
    }
}
