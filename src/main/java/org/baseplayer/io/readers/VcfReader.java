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
        
        // Validate that file exists and is bgzipped
        if (!Files.exists(vcfPath)) {
            throw new IOException("VCF file not found: " + vcfPath);
        }
        
        // Check for index (.tbi or .csi)
        Path tbiPath = Path.of(vcfPath.toString() + ".tbi");
        Path csiPath = Path.of(vcfPath.toString() + ".csi");
        
        if (!Files.exists(tbiPath) && !Files.exists(csiPath)) {
            throw new IOException("VCF index not found (.tbi or .csi): " + vcfPath);
        }
        
        // Open with htsjdk
        this.reader = AbstractFeatureReader.getFeatureReader(
            vcfPath.toString(),
            new VCFCodec(),
            true  // require index
        );
        
        this.header = (VCFHeader) reader.getHeader();
        this.sampleNames = header.getSampleNamesInOrder();
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
            System.err.println("VCF iteration error for " + chromosome);
            System.err.println("  Error: " + e.getMessage());
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
            System.err.println("VCF query error for " + chromosome + ":" + start + "-" + end);
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
        List<VcfStructuralVariant> variants = new ArrayList<>();
        
        // Normalize chromosome name to match VCF file
        String normalizedChrom = normalizeChromosomeName(chromosome);
        
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
                        VcfVariantType type = classifyStructuralVariant(ctx);
                        VcfStructuralVariant variant = parseStructuralVariant(ctx, type);
                        variants.add(variant);
                    }
                } else if (foundChromosome) {
                    // We've passed the target chromosome (VCF is sorted), stop
                    break;
                }
            }
        }
        
        return variants;
    }

    /**
     * Stream all variants for a chromosome, yielding each record to the appropriate consumer.
     * Avoids materialising an intermediate List – variants are processed as they are read.
     */
    public void iterateChromosomeVariants(String chromosome,
            Consumer<VcfSnvIndel> snvConsumer,
            Consumer<VcfStructuralVariant> svConsumer) throws IOException {
        String normalizedChrom = normalizeChromosomeName(chromosome);
        try (var iterator = reader.iterator()) {
            boolean foundChromosome = false;
            while (iterator.hasNext()) {
                VariantContext ctx = iterator.next();
                if (ctx.getContig().equals(normalizedChrom)) {
                    foundChromosome = true;
                    if (isStructuralVariant(ctx)) {
                        if (svConsumer != null) {
                            svConsumer.accept(parseStructuralVariant(ctx, classifyStructuralVariant(ctx)));
                        }
                    } else {
                        if (snvConsumer != null) {
                            snvConsumer.accept(parseSnvIndel(ctx, classifySnvIndel(ctx)));
                        }
                    }
                } else if (foundChromosome) {
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
     */
    private VcfVariantType classifyStructuralVariant(VariantContext ctx) {
        String svType = ctx.getAttributeAsString("SVTYPE", null);
        
        if (svType != null) {
            switch (svType.toUpperCase()) {
                case "DEL": return VcfVariantType.SV_DELETION;
                case "INS": return VcfVariantType.SV_INSERTION;
                case "DUP": return VcfVariantType.SV_DUPLICATION;
                case "INV": return VcfVariantType.SV_INVERSION;
                case "BND": return VcfVariantType.SV_BREAKEND;
                case "TRA": case "CTX": return VcfVariantType.SV_TRANSLOCATION;
            }
        }
        
        // Infer from symbolic alleles
        for (var allele : ctx.getAlternateAlleles()) {
            String alt = allele.getDisplayString();
            if (alt.startsWith("<")) {
                String symbolic = alt.substring(1, alt.length() - 1).toUpperCase();
                if (symbolic.startsWith("DEL")) return VcfVariantType.SV_DELETION;
                if (symbolic.startsWith("INS")) return VcfVariantType.SV_INSERTION;
                if (symbolic.startsWith("DUP")) return VcfVariantType.SV_DUPLICATION;
                if (symbolic.startsWith("INV")) return VcfVariantType.SV_INVERSION;
            }
            
            // Breakend notation
            if (alt.contains("[") || alt.contains("]")) {
                return VcfVariantType.SV_BREAKEND;
            }
        }
        
        return VcfVariantType.COMPLEX;
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
