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

import org.baseplayer.variant.VcfSnvIndel;
import org.baseplayer.variant.VcfStructuralVariant;
import org.baseplayer.variant.VcfVariantType;

/**
 * VCF 4.2 file reader for bgzipped and tabix/csi indexed VCF files.
 * Provides separate methods for reading SNVs/indels and structural variants.
 */
public class VcfReader implements AutoCloseable {
    
    private final Path vcfPath;
    private final AbstractFeatureReader<VariantContext, LineIterator> reader;
    private final VCFHeader header;
    private final List<String> sampleNames;

    /**
     * Open a VCF file. The file must be bgzipped and have a .tbi or .csi index.
     * 
     * @param vcfPath Path to the .vcf.gz file
     * @throws IOException if file cannot be opened or is not indexed
     */
    public VcfReader(Path vcfPath) throws IOException {
        this.vcfPath = vcfPath;
        // System.err.println("[VcfReader] Opening VCF file: " + vcfPath);
        
        // Validate that file exists and is bgzipped
        if (!Files.exists(vcfPath)) {
            throw new IOException("VCF file not found: " + vcfPath);
        }
        // System.err.println("[VcfReader] File exists: " + vcfPath);
        
        // Check for index (.tbi or .csi), looking in multiple locations
        // Try multiple naming conventions: file.vcf.gz.tbi, file.vcf.gz.csi, file.vcf.tbi, file.vcf.csi
        Path tbiPath = Path.of(vcfPath.toString() + ".tbi");
        Path csiPath = Path.of(vcfPath.toString() + ".csi");
        
        // For .gz files, also check without the .gz extension
        Path tbiPathNoGz = null;
        Path csiPathNoGz = null;
        if (vcfPath.toString().endsWith(".gz")) {
            String pathNoGz = vcfPath.toString().substring(0, vcfPath.toString().length() - 3);
            tbiPathNoGz = Path.of(pathNoGz + ".tbi");
            csiPathNoGz = Path.of(pathNoGz + ".csi");
        }
        
        boolean hasIndex = Files.exists(tbiPath) || Files.exists(csiPath) || 
                          (tbiPathNoGz != null && Files.exists(tbiPathNoGz)) ||
                          (csiPathNoGz != null && Files.exists(csiPathNoGz));
        
        if (!hasIndex) {
            // Index file not found - report which locations were checked
            String msg = "VCF index not found for " + vcfPath.getFileName() + ". Checked: " +
                         tbiPath.getFileName();
            if (csiPath != null) msg += ", " + csiPath.getFileName();
            if (tbiPathNoGz != null) msg += ", " + tbiPathNoGz.getFileName();
            if (csiPathNoGz != null) msg += ", " + csiPathNoGz.getFileName();
            throw new IOException(msg);
        }
        // System.err.println("[VcfReader] Index found (tbi=" + Files.exists(tbiPath) + ", csi=" + Files.exists(csiPath) + ")");
        
        // Open with htsjdk - don't require index format to be .idx specifically
        // htsjdk can work with .csi, .tbi, or even without an index for bgzipped files
        this.reader = AbstractFeatureReader.getFeatureReader(
            vcfPath.toString(),
            new VCFCodec(),
            false  // don't require index (htsjdk will use .csi/.tbi if available)
        );
        // System.err.println("[VcfReader] Reader opened successfully");
        
        this.header = (VCFHeader) reader.getHeader();
        this.sampleNames = header.getSampleNamesInOrder();
        // System.err.println("[VcfReader] VCF has " + sampleNames.size() + " samples: " + sampleNames);
    }

    /**
     * Get the VCF header.
     */
    public VCFHeader getHeader() {
        return header;
    }

    /**
     * Get sample names from the VCF.
     */
    public List<String> getSampleNames() {
        return sampleNames;
    }

    /**
     * Query all SNVs and small indels for an entire chromosome.
     * This method filters out structural variants.
     * More efficient than region queries when loading a whole chromosome.
     * 
     * @param chromosome Chromosome name (e.g., "chr1" or "1")
     * @return List of SNVs and indels
     * @throws IOException if query fails
     */
    public List<VcfSnvIndel> querySnvsAndIndelsForChromosome(String chromosome) 
            throws IOException {
        List<VcfSnvIndel> variants = new ArrayList<>();
        
        // Normalize chromosome name to match VCF file
        String normalizedChrom = normalizeChromosomeName(chromosome);
        
        // Iterate through entire VCF and collect variants for this chromosome
        try (var iterator = reader.iterator()) {
            boolean foundChromosome = false;
            
            while (iterator.hasNext()) {
                VariantContext ctx = iterator.next();
                
                // Check if we're on the target chromosome
                if (ctx.getContig().equals(normalizedChrom)) {
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
    
    /**
     * Query SNVs and small indels in a genomic region.
     * For loading entire chromosomes, use querySnvsAndIndelsForChromosome() instead.
     * 
     * @param chromosome Chromosome name (e.g., "chr1" or "1")
     * @param start Start position (1-based, inclusive)
     * @param end End position (1-based, inclusive)
     * @return List of SNVs and indels
     * @throws IOException if query fails
     */
    public List<VcfSnvIndel> querySnvsAndIndels(String chromosome, long start, long end) 
            throws IOException {
        List<VcfSnvIndel> variants = new ArrayList<>();
        
        // Normalize chromosome name to match VCF file
        String normalizedChrom = normalizeChromosomeName(chromosome);
        
        // VCF files use 1-based coordinates
        int queryStart = (int) start;
        int queryEnd = (int) end;
        
        try (var iterator = reader.query(normalizedChrom, queryStart, queryEnd)) {
            while (iterator.hasNext()) {
                VariantContext ctx = iterator.next();
                
                // Skip structural variants
                if (isStructuralVariant(ctx)) {
                    continue;
                }
                
                VcfVariantType type = classifySnvIndel(ctx);
                VcfSnvIndel variant = parseSnvIndel(ctx, type);
                variants.add(variant);
            }
        } catch (Exception e) {
            // System.err.println("VCF query error for " + chromosome + ":" + start + "-" + end);
            throw new IOException("Failed to query VCF: " + e.getMessage(), e);
        }
        
        return variants;
    }

    /**
     * Query all structural variants for an entire chromosome.
     * This method only returns structural variants.
     * More efficient than region queries when loading a whole chromosome.
     * 
     * @param chromosome Chromosome name (e.g., "chr1" or "1")
     * @return List of structural variants
     * @throws IOException if query fails
     */
    public List<VcfStructuralVariant> queryStructuralVariantsForChromosome(String chromosome) 
            throws IOException {
        // System.err.println("[VcfReader.queryStructuralVariantsForChromosome] Querying SVs for: " + chromosome);
        List<VcfStructuralVariant> variants = new ArrayList<>();
        
        // Normalize chromosome name to match VCF file
        String normalizedChrom = normalizeChromosomeName(chromosome);
        // System.err.println("[VcfReader.queryStructuralVariantsForChromosome] Normalized chrom: " + normalizedChrom);
        
        // Iterate through entire VCF and collect structural variants for this chromosome
        try (var iterator = reader.iterator()) {
            boolean foundChromosome = false;
            
            while (iterator.hasNext()) {
                VariantContext ctx = iterator.next();
                
                // Check if we're on the target chromosome
                if (ctx.getContig().equals(normalizedChrom)) {
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

    /**
     * Stream all variants for a chromosome, yielding each record to the appropriate consumer.
     * Avoids materialising an intermediate List – variants are processed as they are read.
     */
    public void iterateChromosomeVariants(String chromosome,
            Consumer<VcfSnvIndel> snvConsumer,
            Consumer<VcfStructuralVariant> svConsumer) throws IOException {
        // System.err.println("[VcfReader.iterateChromosomeVariants] Starting iteration for chromosome: " + chromosome);
        String normalizedChrom = normalizeChromosomeName(chromosome);
        // System.err.println("[VcfReader.iterateChromosomeVariants] Normalized: " + normalizedChrom);
        
        try (var iterator = reader.iterator()) {
            boolean foundChromosome = false;
            while (iterator.hasNext()) {
                VariantContext ctx = iterator.next();
                
                if (ctx.getContig().equals(normalizedChrom)) {
                    foundChromosome = true;
                    if (isStructuralVariant(ctx)) {
                        // System.err.println("[VcfReader.iterateChromosomeVariants] SV #" + svCount + ": pos=" + ctx.getStart() + ", SVTYPE=" + svType + ", END=" + ctx.getAttributeAsInt("END", -1));
                        if (svConsumer != null) {
                            svConsumer.accept(parseStructuralVariant(ctx, classifyStructuralVariant(ctx)));
                        }
                    } else {
                        if (snvConsumer != null) {
                            snvConsumer.accept(parseSnvIndel(ctx, classifySnvIndel(ctx)));
                        }
                    }
                } else if (foundChromosome) {
                    // System.err.println("[VcfReader.iterateChromosomeVariants] Passed chromosome, stopping");
                    break;
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to iterate VCF: " + e.getMessage(), e);
        }
    }

    /**
     * Query structural variants in a genomic region.
     * For loading entire chromosomes, use queryStructuralVariantsForChromosome() instead.
     * 
     * @param chromosome Chromosome name (e.g., "chr1" or "1")
     * @param start Start position (1-based, inclusive)
     * @param end End position (1-based, inclusive)
     * @return List of structural variants
     * @throws IOException if query fails
     */
    public List<VcfStructuralVariant> queryStructuralVariants(String chromosome, long start, long end) 
            throws IOException {
        List<VcfStructuralVariant> variants = new ArrayList<>();
        
        // Normalize chromosome name to match VCF file
        String normalizedChrom = normalizeChromosomeName(chromosome);
        
        // VCF files use 1-based coordinates
        int queryStart = (int) start;
        int queryEnd = (int) end;
        
        try (var iterator = reader.query(normalizedChrom, queryStart, queryEnd)) {
            while (iterator.hasNext()) {
                VariantContext ctx = iterator.next();
                
                // Only include structural variants
                if (!isStructuralVariant(ctx)) {
                    continue;
                }
                
                VcfVariantType type = classifyStructuralVariant(ctx);
                VcfStructuralVariant variant = parseStructuralVariant(ctx, type);
                variants.add(variant);
            }
        }
        
        return variants;
    }

    /**
     * Query all variants (both SNVs/indels and structural variants) in a region.
     * Returns two separate lists for cleaner handling.
     * 
     * @param chromosome Chromosome name
     * @param start Start position (1-based, inclusive)
     * @param end End position (1-based, inclusive)
     * @return Map with "snvs" and "svs" keys
     * @throws IOException if query fails
     */
    public Map<String, Object> queryAllVariants(String chromosome, long start, long end) 
            throws IOException {
        List<VcfSnvIndel> snvs = new ArrayList<>();
        List<VcfStructuralVariant> svs = new ArrayList<>();
        
        // Normalize chromosome name to match VCF file
        String normalizedChrom = normalizeChromosomeName(chromosome);
        
        // VCF files use 1-based coordinates
        int queryStart = (int) start;
        int queryEnd = (int) end;
        
        try (var iterator = reader.query(normalizedChrom, queryStart, queryEnd)) {
            while (iterator.hasNext()) {
                VariantContext ctx = iterator.next();
                
                if (isStructuralVariant(ctx)) {
                    VcfVariantType type = classifyStructuralVariant(ctx);
                    svs.add(parseStructuralVariant(ctx, type));
                } else {
                    VcfVariantType type = classifySnvIndel(ctx);
                    snvs.add(parseSnvIndel(ctx, type));
                }
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("snvs", snvs);
        result.put("svs", svs);
        return result;
    }

    // ── Classification ────────────────────────────────────────────────────────

    /**
     * Check if a variant is a structural variant.
     * SV criteria: symbolic alleles, SVTYPE in INFO, or large size.
     */
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

    /**
     * Classify a SNV/indel variant.
     */
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
                        String chrom = ctx.getContig();
                        if (!chr2.equals(chrom)) {
                            // Different chromosome = translocation
                            return VcfVariantType.SV_TRANSLOCATION;
                        }
                    } else {
                        // Try to extract CHR2 from ALT breakend notation
                        String alt = ctx.getAlternateAlleles().stream()
                            .map(a -> a.getDisplayString())
                            .filter(s -> s.contains("[") || s.contains("]"))
                            .findFirst()
                            .orElse(null);
                        if (alt != null) {
                            String altChr2 = extractChr2FromBreakendAlt(alt);
                            if (altChr2 != null) {
                                String chrom = ctx.getContig();
                                if (!altChr2.equals(chrom)) {
                                    // Different chromosome = translocation
                                    return VcfVariantType.SV_TRANSLOCATION;
                                }
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
    
    /**
     * Extract chromosome from breakend ALT notation (e.g., "C[chr6:123[" -> "chr6").
     */
    private String extractChr2FromBreakendAlt(String alt) {
        // Breakend format: REF[CHR:POS[ or ]CHR:POS]REF
        // Extract the chromosome:position part
        int openBracket = alt.indexOf('[');
        int closeBracket = alt.indexOf(']');
        int startIdx = -1, endIdx = -1;
        
        if (openBracket >= 0) {
            startIdx = openBracket + 1;
            endIdx = alt.indexOf('[', startIdx);
        } else if (closeBracket >= 0) {
            startIdx = alt.indexOf(']');
            if (startIdx >= 0) {
                startIdx++;
                endIdx = alt.indexOf(']', startIdx);
            }
        }
        
        if (startIdx > 0 && endIdx > startIdx) {
            String chrPosStr = alt.substring(startIdx, endIdx);
            int colonIdx = chrPosStr.indexOf(':');
            if (colonIdx > 0) {
                return chrPosStr.substring(0, colonIdx);
            }
        }
        
        return null;
    }
    
    /**
     * Extract mate position from breakend ALT notation (e.g., "C[chr6:123[" -> 123).
     * Works for both same-chromosome and different-chromosome mates.
     */
    private Long extractMatePosFromBreakendAlt(VariantContext ctx, String currentChromosome) {
        try {
            if (ctx.getAlternateAlleles().isEmpty()) {
                return null;
            }
            
            String alt = ctx.getAlternateAlleles().get(0).getDisplayString();
            
            // Breakend format: REF[CHR:POS[ or ]CHR:POS]REF
            int openBracket = alt.indexOf('[');
            int closeBracket = alt.indexOf(']');
            int startIdx = -1, endIdx = -1;
            
            if (openBracket >= 0) {
                startIdx = openBracket + 1;
                endIdx = alt.indexOf('[', startIdx);
            } else if (closeBracket >= 0) {
                startIdx = alt.indexOf(']');
                if (startIdx >= 0) {
                    startIdx++;
                    endIdx = alt.indexOf(']', startIdx);
                }
            }
            
            if (startIdx > 0 && endIdx > startIdx) {
                String chrPosStr = alt.substring(startIdx, endIdx);
                int colonIdx = chrPosStr.indexOf(':');
                if (colonIdx > 0) {
                    try {
                        return Long.parseLong(chrPosStr.substring(colonIdx + 1));
                    } catch (NumberFormatException e) {
                        // Ignore parse errors
                    }
                }
            }
            
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    /**
     * Parse a SNV/indel variant.
     */
    private VcfSnvIndel parseSnvIndel(VariantContext ctx, VcfVariantType type) {
        return new VcfSnvIndel(
            ctx.getContig(),
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
        
        Long end2 = ctx.hasAttribute("END2") ? 
            Long.parseLong(ctx.getAttributeAsString("END2", null)) : null;

        
        // For BND/breakend variants without END field, extract mate position from ALT notation
        if (end == null && (type == VcfVariantType.SV_DELETION || type == VcfVariantType.SV_INSERTION ||
                            type == VcfVariantType.SV_DUPLICATION || type == VcfVariantType.SV_INVERSION || 
                            type == VcfVariantType.SV_TRANSLOCATION || type == VcfVariantType.SV_BREAKEND)) {
            Long matePos = extractMatePosFromBreakendAlt(ctx, ctx.getContig());
            if (matePos != null) {
                // For intra-chromosomal SVs (DEL, INV, DUP, INS on same chromosome), use mate as END
                if (type == VcfVariantType.SV_DELETION || type == VcfVariantType.SV_INVERSION || 
                    type == VcfVariantType.SV_DUPLICATION || type == VcfVariantType.SV_INSERTION) {
                    // Ensure END > position for proper span rendering
                    long pos = ctx.getStart();
                    if (matePos > pos) {
                        end = matePos;
                    } else {
                        // Swap positions: use smaller as start, larger as end
                        end = pos;
                    }
                }
                // For TRA (different chromosomes), leave END as null - it's a point variant
            }
        }
        
        return new VcfStructuralVariant(
            ctx.getContig(),
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
            chr2,
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
            
            // Add any extended attributes
            for (String key : gt.getExtendedAttributes().keySet()) {
                gtMap.put(key, gt.getExtendedAttribute(key));
            }
            
            genotypes.put(gt.getSampleName(), gtMap);
        }
        
        return genotypes;
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
     * Normalize chromosome name to match VCF file conventions.
     * Tries the original name first, then adds/removes "chr" prefix if needed.
     * 
     * @param chromosome Original chromosome name
     * @return Normalized chromosome name that exists in VCF
     * @throws IOException if chromosome cannot be found in VCF
     */
    private String normalizeChromosomeName(String chromosome) throws IOException {
        List<String> availableChromosomes = getAvailableChromosomes();
        
        // Try original name first
        if (availableChromosomes.contains(chromosome)) {
            return chromosome;
        }
        
        // Try adding "chr" prefix if not present
        if (!chromosome.startsWith("chr")) {
            String withChr = "chr" + chromosome;
            if (availableChromosomes.contains(withChr)) {
                return withChr;
            }
        }
        
        // Try removing "chr" prefix if present
        if (chromosome.startsWith("chr")) {
            String withoutChr = chromosome.substring(3);
            if (availableChromosomes.contains(withoutChr)) {
                return withoutChr;
            }
        }
        
        // Chromosome not found - show helpful error
        throw new IOException("Chromosome '" + chromosome + "' not found in VCF. " +
            "Available chromosomes: " + availableChromosomes);
    }
}
