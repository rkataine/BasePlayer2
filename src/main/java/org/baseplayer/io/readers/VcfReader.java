package org.baseplayer.io.readers;

import htsjdk.tribble.AbstractFeatureReader;
import htsjdk.tribble.readers.LineIterator;
import htsjdk.tribble.readers.TabixReader;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFCodec;
import htsjdk.variant.vcf.VCFCompoundHeaderLine;
import htsjdk.variant.vcf.VCFFilterHeaderLine;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLineCount;
import htsjdk.variant.vcf.VCFHeaderLineType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.baseplayer.variant.BreakendAlt;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfHeaderFieldDef;
import org.baseplayer.variant.VcfSnvIndel;
import org.baseplayer.variant.VcfStructuralVariant;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.utils.ChromosomeNames;

public class VcfReader implements AutoCloseable {
    
    private final Path vcfPath;
    private final AbstractFeatureReader<VariantContext, LineIterator> reader;
    private final VCFHeader header;
    private final List<String> sampleNames;
    /** True when the VCF header has no sample columns (site-only CNV / segment files). */
    private final boolean siteOnlySamples;
    private final boolean canTabixQuery;
    private final boolean hasTbiOrCsiIndex;
    private final String chromPrefix;
    /** Contigs from ##contig, or peeked from records when the header omits them. */
    private final List<String> availableChromosomes;

    public VcfReader(Path vcfPath) throws IOException {
        this.vcfPath = vcfPath;
        
        if (!Files.exists(vcfPath)) {
            throw new IOException("VCF file not found: " + vcfPath);
        }

        Path tabixIndex = findSiblingIndex(vcfPath, ".tbi");
        Path csiIndex = findSiblingIndex(vcfPath, ".csi");
        // CSI counts as "indexed" for open/load gates (same as SV .csi files), but must not
        // be passed into AbstractFeatureReader: isTabix() treats any existing index path beside
        // a .gz as Tabix and TabixReader OOMs on CSI. Indexed queries only for real .tbi.
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
        List<String> headerSamples = header.getSampleNamesInOrder();
        if (headerSamples == null || headerSamples.isEmpty()) {
            // FACETS-style CNV VCFs often omit FORMAT/sample columns.
            this.sampleNames = List.of(stemSampleName(vcfPath));
            this.siteOnlySamples = true;
        } else {
            this.sampleNames = headerSamples;
            this.siteOnlySamples = false;
        }

        List<String> headerContigs = header.getContigLines().stream()
            .map(line -> line.getID())
            .collect(Collectors.toList());
        // FACETS CNV VCFs often omit ##contig; peek records so chr-prefix mapping matches SV flow.
        this.availableChromosomes = headerContigs.isEmpty()
            ? peekContigNames(64)
            : List.copyOf(headerContigs);
        this.chromPrefix = ChromosomeNames.detectPrefix(this.availableChromosomes);
    }

    private List<String> peekContigNames(int limit) throws IOException {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        try (var iterator = reader.iterator()) {
            while (iterator.hasNext() && names.size() < limit) {
                String contig = iterator.next().getContig();
                if (contig != null && !contig.isBlank()) {
                    names.add(contig);
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to peek VCF contigs: " + e.getMessage(), e);
        }
        return List.copyOf(names);
    }

    private static String stemSampleName(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".vcf.gz")) {
            name = name.substring(0, name.length() - ".vcf.gz".length());
        } else if (name.endsWith(".vcf")) {
            name = name.substring(0, name.length() - ".vcf".length());
        } else if (name.endsWith(".gz")) {
            name = name.substring(0, name.length() - 3);
        }
        return name.isBlank() ? "CNV" : name;
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

    /** Immutable ##INFO definitions from the header (ID-sorted). */
    public List<VcfHeaderFieldDef> getInfoHeaderDefs() {
        return extractCompoundDefs(header.getInfoHeaderLines(), VcfHeaderFieldDef.Kind.INFO);
    }

    /**
     * Full-file iterator in file order. Caller must close the returned iterator.
     * Used for light sampling (e.g. control-file AN peek), not for full loads.
     */
    public htsjdk.tribble.CloseableTribbleIterator<VariantContext> rawIterator() throws IOException {
        try {
            return reader.iterator();
        } catch (Exception e) {
            throw new IOException("Failed to open VCF iterator: " + e.getMessage(), e);
        }
    }

    /** Immutable ##FILTER definitions from the header (ID-sorted). */
    public List<VcfHeaderFieldDef> getFilterHeaderDefs() {
        Collection<VCFFilterHeaderLine> lines = header.getFilterLines();
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<VcfHeaderFieldDef> out = new ArrayList<>(lines.size());
        for (VCFFilterHeaderLine line : lines) {
            if (line == null || line.getID() == null || line.getID().isBlank()) {
                continue;
            }
            out.add(new VcfHeaderFieldDef(
                VcfHeaderFieldDef.Kind.FILTER,
                line.getID(),
                null,
                null,
                line.getDescription() != null ? line.getDescription() : ""));
        }
        out.sort(Comparator.comparing(VcfHeaderFieldDef::id, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    /** Immutable ##FORMAT definitions from the header (ID-sorted). */
    public List<VcfHeaderFieldDef> getFormatHeaderDefs() {
        return extractCompoundDefs(header.getFormatHeaderLines(), VcfHeaderFieldDef.Kind.FORMAT);
    }

    private static List<VcfHeaderFieldDef> extractCompoundDefs(
        Collection<? extends VCFCompoundHeaderLine> lines,
        VcfHeaderFieldDef.Kind kind) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<VcfHeaderFieldDef> out = new ArrayList<>(lines.size());
        for (VCFCompoundHeaderLine line : lines) {
            if (line == null || line.getID() == null || line.getID().isBlank()) {
                continue;
            }
            out.add(new VcfHeaderFieldDef(
                kind,
                line.getID(),
                formatHeaderNumber(line),
                formatHeaderType(line.getType()),
                line.getDescription() != null ? line.getDescription() : ""));
        }
        out.sort(Comparator.comparing(VcfHeaderFieldDef::id, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    private static String formatHeaderType(VCFHeaderLineType type) {
        return type != null ? type.name() : null;
    }

    private static String formatHeaderNumber(VCFCompoundHeaderLine line) {
        if (line == null) {
            return null;
        }
        VCFHeaderLineCount countType = line.getCountType();
        if (countType == null) {
            return null;
        }
        return switch (countType) {
            case INTEGER -> String.valueOf(line.getCount());
            case A -> "A";
            case R -> "R";
            case G -> "G";
            case UNBOUNDED -> ".";
        };
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
                if (!ChromosomeNames.equals(ctx.getContig(), chromosome)) {
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
                if (ChromosomeNames.equals(ctx.getContig(), dataChrom)) {
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
        visitSnvsAndIndels(chromosome, start, end, variants::add);
        return variants;
    }

    /** Stream overlapping SNVs/indels without materializing a full list. */
    public void visitSnvsAndIndels(
            String chromosome, long start, long end, Consumer<VcfSnvIndel> consumer)
            throws IOException {
        if (consumer == null) {
            return;
        }
        visitOverlappingVariants(chromosome, start, end, ctx -> {
            if (!isStructuralVariant(ctx)) {
                consumer.accept(parseSnvIndel(ctx, classifySnvIndel(ctx)));
            }
        });
    }

    /**
     * Stream raw VCF body lines overlapping {@code [start, end]} (1-based inclusive)
     * via tabix — no {@link VariantContext} decode. Caller parses POS/alleles/INFO.
     * No-op (does not invoke consumer) when no {@code .tbi} is available.
     */
    public void visitRawVariantLines(
            String chromosome, long start, long end, Consumer<String> consumer)
            throws IOException {
        if (consumer == null || !canTabixQuery) {
            return;
        }
        Path tabixIndex = findSiblingIndex(vcfPath, ".tbi");
        if (tabixIndex == null) {
            return;
        }
        String dataChrom = toDataChromosome(chromosome);
        // TabixReader.query is 0-based half-open; VCF coordinates are 1-based inclusive.
        int beg = (int) Math.max(0, start - 1);
        int queryEnd = (int) Math.min(Integer.MAX_VALUE, Math.max(start, end));
        try (TabixReader tabix = new TabixReader(vcfPath.toString(), tabixIndex.toString())) {
            TabixReader.Iterator it = tabix.query(dataChrom, beg, queryEnd);
            if (it == null) {
                return;
            }
            String line;
            while ((line = it.next()) != null) {
                consumer.accept(line);
            }
        } catch (Exception e) {
            throw new IOException("Failed to query VCF lines: " + e.getMessage(), e);
        }
    }

    /**
     * Stream raw overlapping {@link VariantContext}s (tabix when available).
     * Prefer this for control-AF merge-joins — avoids allocating SNV/INFO/genotype wrappers.
     */
    public void visitVariantContexts(
            String chromosome, long start, long end, Consumer<VariantContext> consumer)
            throws IOException {
        if (consumer == null) {
            return;
        }
        visitOverlappingVariants(chromosome, start, end, consumer);
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
                if (ChromosomeNames.equals(ctx.getContig(), dataChrom)) {
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
        // Check for symbolic alleles (e.g., <DEL>, <INS>, <DUP>, <CNV>)
        if (ctx.isSymbolic()) {
            return true;
        }
        
        // Check for SVTYPE in INFO field
        if (ctx.hasAttribute("SVTYPE")) {
            return true;
        }

        // DRAGEN CNV segments: INFO/END (+ REFLEN) with FORMAT/SM|CN|BC, including
        // diploid REF rows that lack SVTYPE (those used to fall through as COMPLEX).
        if (ctx.hasAttribute("END")
            && (ctx.hasAttribute("REFLEN")
                || hasFormatAttribute(ctx, "SM")
                || hasFormatAttribute(ctx, "CN")
                || hasFormatAttribute(ctx, "BC"))) {
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
        // FACETS / DRAGEN segment VCFs: prefer Gain/Loss/Neutral from copy-ratio metrics
        // (FORMAT/SM, FORMAT/CN, …) over classic SV labels or COMPLEX.
        if (hasCnvSegmentMetrics(ctx) || looksLikeDragenCnvRecord(ctx)) {
            return classifyCnvSegment(ctx);
        }

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
        
        if (svType != null) {
            switch (svType.toUpperCase()) {
                case "DEL": 
                    return VcfVariantType.SV_DELETION;
                case "INS": 
                    return VcfVariantType.SV_INSERTION;
                case "DUP": 
                    return VcfVariantType.SV_DUPLICATION;
                case "INV": 
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
                    return VcfVariantType.SV_TRANSLOCATION;
                case "CNV":
                    return classifyCnvSegment(ctx);
            }
        }
        
        // Infer from symbolic alleles
        for (var allele : ctx.getAlternateAlleles()) {
            String alt = allele.getDisplayString();
            if (alt.startsWith("<")) {
                String symbolic = alt.substring(1, alt.length() - 1).toUpperCase();
                if (symbolic.startsWith("DEL")) {
                    return VcfVariantType.SV_DELETION;
                }
                if (symbolic.startsWith("INS")) {
                    return VcfVariantType.SV_INSERTION;
                }
                if (symbolic.startsWith("DUP")) {
                    return VcfVariantType.SV_DUPLICATION;
                }
                if (symbolic.startsWith("INV")) {
                    return VcfVariantType.SV_INVERSION;
                }
                if (symbolic.startsWith("CNV")) {
                    return classifyCnvSegment(ctx);
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

    /** Clear loss / gain cutoffs for FACETS {@code TCN_EM} when logR is absent. */
    private static final int CNV_LOSS_MAX_TCN = 1;
    private static final int CNV_GAIN_MIN_TCN = 4;
    /**
     * Prefer FACETS log-ratio for somatic gain/loss. Absolute TCN over-calls gains in
     * aneuploid tumors (TCN≈ploidy with near-zero logR).
     */
    private static final double CNV_LOGR_BUFFER = 0.2;

    /**
     * True when the record carries FACETS-style INFO metrics or DRAGEN FORMAT
     * {@code SM}/{@code CN} (sample columns — not INFO).
     */
    private static boolean hasCnvSegmentMetrics(VariantContext ctx) {
        return doubleInfoOrFormat(ctx, "CNLR_MEDIAN") != null
            || doubleInfoOrFormat(ctx, "CNLR_MEDIAN_CLUST") != null
            || intInfoOrFormat(ctx, "TCN_EM") != null
            || intInfoOrFormat(ctx, "TCN") != null
            || doubleInfoOrFormat(ctx, "SM") != null
            || (ctx.hasAttribute("END") && intInfoOrFormat(ctx, "CN") != null);
    }

    /** DRAGEN {@code *.cnv.vcf}: END/REFLEN and/or symbolic CNV ALT / SVTYPE=CNV. */
    private static boolean looksLikeDragenCnvRecord(VariantContext ctx) {
        if (ctx.hasAttribute("END") && ctx.hasAttribute("REFLEN")) {
            return true;
        }
        String svType = ctx.getAttributeAsString("SVTYPE", null);
        if (svType != null && "CNV".equalsIgnoreCase(svType)) {
            return true;
        }
        for (var allele : ctx.getAlternateAlleles()) {
            String alt = allele.getDisplayString().toUpperCase();
            if ("<CNV>".equals(alt) || "<DEL>".equals(alt) || "<DUP>".equals(alt)) {
                return true;
            }
        }
        return false;
    }

    /**
     * FACETS INFO logR / TCN, or DRAGEN FORMAT/SM (linear copy ratio ≈ 1 diploid)
     * and FORMAT/CN. Falls back to symbolic {@code <DEL>}/{@code <DUP>} when metrics
     * are missing.
     */
    private static VcfVariantType classifyCnvSegment(VariantContext ctx) {
        Double logr = doubleInfoOrFormat(ctx, "CNLR_MEDIAN");
        if (logr == null) {
            logr = doubleInfoOrFormat(ctx, "CNLR_MEDIAN_CLUST");
        }
        if (logr != null) {
            if (logr > CNV_LOGR_BUFFER) {
                return VcfVariantType.SV_CNV_GAIN;
            }
            if (logr < -CNV_LOGR_BUFFER) {
                return VcfVariantType.SV_CNV_LOSS;
            }
            return VcfVariantType.SV_CNV_NEUTRAL;
        }
        Double sm = doubleInfoOrFormat(ctx, "SM");
        if (sm != null && sm > 0) {
            double smLog2 = Math.log(Math.max(1e-3, sm)) / Math.log(2.0);
            if (smLog2 > CNV_LOGR_BUFFER) {
                return VcfVariantType.SV_CNV_GAIN;
            }
            if (smLog2 < -CNV_LOGR_BUFFER) {
                return VcfVariantType.SV_CNV_LOSS;
            }
            return VcfVariantType.SV_CNV_NEUTRAL;
        }
        Integer tcn = intInfoOrFormat(ctx, "TCN_EM");
        if (tcn == null) {
            tcn = intInfoOrFormat(ctx, "CN");
        }
        if (tcn != null) {
            if (tcn <= CNV_LOSS_MAX_TCN) {
                return VcfVariantType.SV_CNV_LOSS;
            }
            if (tcn >= CNV_GAIN_MIN_TCN) {
                return VcfVariantType.SV_CNV_GAIN;
            }
            return VcfVariantType.SV_CNV_NEUTRAL;
        }
        // No numeric metric: use symbolic ALT / diploid REF.
        for (var allele : ctx.getAlternateAlleles()) {
            String symbolic = allele.getDisplayString().toUpperCase();
            if (symbolic.startsWith("<DEL")) {
                return VcfVariantType.SV_CNV_LOSS;
            }
            if (symbolic.startsWith("<DUP")) {
                return VcfVariantType.SV_CNV_GAIN;
            }
        }
        if (!ctx.isVariant() || ctx.getAlternateAlleles().isEmpty()) {
            return VcfVariantType.SV_CNV_NEUTRAL;
        }
        return VcfVariantType.SV_CNV;
    }

    private static boolean hasFormatAttribute(VariantContext ctx, String key) {
        if (ctx.getNSamples() <= 0) {
            return false;
        }
        for (Genotype gt : ctx.getGenotypes()) {
            if (gt != null && gt.hasExtendedAttribute(key)) {
                return true;
            }
        }
        return false;
    }

    private static Integer intInfoOrFormat(VariantContext ctx, String key) {
        Integer fromInfo = parseIntValue(ctx.hasAttribute(key) ? ctx.getAttribute(key) : null);
        if (fromInfo != null) {
            return fromInfo;
        }
        return parseIntValue(firstFormatValue(ctx, key));
    }

    private static Double doubleInfoOrFormat(VariantContext ctx, String key) {
        Double fromInfo = parseDoubleValue(ctx.hasAttribute(key) ? ctx.getAttribute(key) : null);
        if (fromInfo != null) {
            return fromInfo;
        }
        return parseDoubleValue(firstFormatValue(ctx, key));
    }

    private static Object firstFormatValue(VariantContext ctx, String key) {
        if (ctx.getNSamples() <= 0) {
            return null;
        }
        for (Genotype gt : ctx.getGenotypes()) {
            if (gt == null || !gt.hasExtendedAttribute(key)) {
                continue;
            }
            return gt.getExtendedAttribute(key);
        }
        return null;
    }

    private static Integer parseIntValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || ".".equals(text) || "NA".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDoubleValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || ".".equals(text) || "NA".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
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
                || type == VcfVariantType.SV_DUPLICATION || type == VcfVariantType.SV_INVERSION
                || VariantTypeVisuals.isCnv(type))
                && end2 != null) {
            long pos = ctx.getStart();
            end = end2 > pos ? end2 : pos;
        }

        if (svType == null && VariantTypeVisuals.isCnv(type)) {
            svType = switch (type) {
                case SV_CNV_GAIN -> "CNV_GAIN";
                case SV_CNV_LOSS -> "CNV_LOSS";
                case SV_CNV_NEUTRAL -> "CNV_NEUTRAL";
                default -> "CNV";
            };
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
            if (!siteOnlySamples || sampleNames.isEmpty()) {
                return Map.of();
            }
            // Attribute every site-only record to the synthetic file sample.
            Map<String, Object> gtMap = new HashMap<>();
            gtMap.put("GT", "NA");
            gtMap.put("isHomRef", false);
            gtMap.put("isHet", false);
            gtMap.put("isHomVar", true);
            gtMap.put("isNoCall", false);
            if (ctx.hasAttribute("CF_EM")) {
                try {
                    gtMap.put("AF", Double.parseDouble(ctx.getAttributeAsString("CF_EM", "")));
                } catch (NumberFormatException ignored) {
                }
            }
            return Map.of(sampleNames.get(0), gtMap);
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
     * INFO + FORMAT string maps for one VCF site (for UI dumps / detail popups).
     */
    public record SiteFields(
            Map<String, String> fixed,
            Map<String, String> info,
            Map<String, String> format,
            String matchedSample) {
    }

    /**
     * Look up the VCF record at {@code chromosome}:{@code position} (optionally matching
     * REF/ALT) and return all fixed / INFO / FORMAT fields as display strings.
     *
     * @param preferredSample VCF sample column name when known; otherwise first usable genotype
     */
    public SiteFields findSiteFields(
            String chromosome,
            long position,
            long endHint,
            String ref,
            String alt,
            String preferredSample) throws IOException {
        long queryEnd = Math.max(position, endHint > 0 ? endHint : position);
        VariantContext[] match = {null};
        visitOverlappingVariants(chromosome, position, queryEnd, ctx -> {
            if (match[0] != null) {
                return;
            }
            if (ctx.getStart() != position) {
                return;
            }
            if (!alleleMatches(ctx, ref, alt)) {
                return;
            }
            match[0] = ctx;
        });
        if (match[0] == null) {
            // Fall back: first record at this start position regardless of allele.
            visitOverlappingVariants(chromosome, position, queryEnd, ctx -> {
                if (match[0] == null && ctx.getStart() == position) {
                    match[0] = ctx;
                }
            });
        }
        if (match[0] == null) {
            return null;
        }
        return toSiteFields(match[0], preferredSample);
    }

    private static boolean alleleMatches(VariantContext ctx, String ref, String alt) {
        if (ref != null && !ref.isBlank()) {
            String ctxRef = ctx.getReference() != null ? ctx.getReference().getDisplayString() : null;
            if (ctxRef != null && !ctxRef.equalsIgnoreCase(ref.trim())) {
                // Still allow ALT-only match for symbolic SVs where REF can differ by padding.
                if (alt == null || alt.isBlank() || !alt.trim().startsWith("<")) {
                    return false;
                }
            }
        }
        if (alt == null || alt.isBlank()) {
            return true;
        }
        String want = alt.trim();
        for (var allele : ctx.getAlternateAlleles()) {
            if (allele == null) {
                continue;
            }
            String display = allele.getDisplayString();
            if (display != null && display.equalsIgnoreCase(want)) {
                return true;
            }
        }
        return false;
    }

    private SiteFields toSiteFields(VariantContext ctx, String preferredSample) {
        Map<String, String> fixed = new java.util.LinkedHashMap<>();
        fixed.put("CHROM", ChromosomeNames.strip(ctx.getContig()));
        fixed.put("POS", String.valueOf(ctx.getStart()));
        fixed.put("ID", ctx.hasID() ? ctx.getID() : ".");
        fixed.put("REF", ctx.getReference() != null ? ctx.getReference().getDisplayString() : ".");
        String alts = ctx.getAlternateAlleles().stream()
            .map(a -> a.getDisplayString())
            .collect(Collectors.joining(","));
        fixed.put("ALT", alts.isEmpty() ? "." : alts);
        fixed.put("QUAL", ctx.hasLog10PError()
            ? formatAttributeValue(ctx.getPhredScaledQual())
            : ".");
        fixed.put("FILTER", ctx.isFiltered()
            ? String.join(";", ctx.getFilters())
            : (ctx.filtersWereApplied() ? "PASS" : "."));

        Map<String, String> info = new java.util.LinkedHashMap<>();
        for (String key : ctx.getAttributes().keySet()) {
            info.put(key, formatAttributeValue(ctx.getAttribute(key)));
        }

        String matchedSample = null;
        Map<String, String> format = new java.util.LinkedHashMap<>();
        if (ctx.hasGenotypes()) {
            Genotype gt = pickGenotype(ctx, preferredSample);
            if (gt != null) {
                matchedSample = gt.getSampleName();
                format.put("GT", gt.getGenotypeString());
                if (gt.hasDP()) {
                    format.put("DP", String.valueOf(gt.getDP()));
                }
                if (gt.hasGQ()) {
                    format.put("GQ", String.valueOf(gt.getGQ()));
                }
                if (gt.hasAD()) {
                    format.put("AD", formatAttributeValue(gt.getAD()));
                }
                if (gt.hasPL()) {
                    format.put("PL", formatAttributeValue(gt.getPL()));
                }
                for (String key : gt.getExtendedAttributes().keySet()) {
                    format.putIfAbsent(key, formatAttributeValue(gt.getExtendedAttribute(key)));
                }
            }
        } else if (siteOnlySamples && !sampleNames.isEmpty()) {
            matchedSample = sampleNames.get(0);
        }

        return new SiteFields(
            Map.copyOf(fixed),
            Map.copyOf(info),
            Map.copyOf(format),
            matchedSample);
    }

    private static Genotype pickGenotype(VariantContext ctx, String preferredSample) {
        if (preferredSample != null && !preferredSample.isBlank()) {
            Genotype exact = ctx.getGenotype(preferredSample);
            if (exact != null) {
                return exact;
            }
            for (Genotype gt : ctx.getGenotypes()) {
                if (gt.getSampleName() != null
                        && gt.getSampleName().equalsIgnoreCase(preferredSample.trim())) {
                    return gt;
                }
            }
        }
        for (Genotype gt : ctx.getGenotypes()) {
            if (gt != null && !gt.isNoCall() && !gt.isHomRef()) {
                return gt;
            }
        }
        return ctx.getGenotypes().isEmpty() ? null : ctx.getGenotype(0);
    }

    private static String formatAttributeValue(Object value) {
        if (value == null) {
            return ".";
        }
        if (value instanceof int[] arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(arr[i]);
            }
            return sb.toString();
        }
        if (value instanceof double[] arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(formatNumeric(arr[i]));
            }
            return sb.toString();
        }
        if (value instanceof float[] arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(formatNumeric(arr[i]));
            }
            return sb.toString();
        }
        if (value instanceof Object[] arr) {
            return java.util.Arrays.stream(arr)
                .map(VcfReader::formatAttributeValue)
                .collect(Collectors.joining(","));
        }
        if (value instanceof Collection<?> coll) {
            return coll.stream()
                .map(VcfReader::formatAttributeValue)
                .collect(Collectors.joining(","));
        }
        if (value instanceof Number number) {
            return formatNumeric(number.doubleValue());
        }
        return String.valueOf(value);
    }

    private static String formatNumeric(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return String.valueOf(value);
        }
        if (Math.rint(value) == value) {
            return String.format("%.0f", value);
        }
        return String.format("%.6g", value);
    }

    /**
     * Get list of chromosomes (contigs) available in the VCF file.
     */
    public List<String> getAvailableChromosomes() {
        return availableChromosomes;
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
        if (availableChromosomes.isEmpty()) {
            // No ##contig and peek found nothing — trust naming (linear scan uses strip-equality).
            return dataChrom;
        }
        if (availableChromosomes.contains(dataChrom)) {
            return dataChrom;
        }
        // Contig list may use a different casing / M vs MT — fall back to strip-equality.
        for (String available : availableChromosomes) {
            if (ChromosomeNames.equals(available, chromosome)
                    || ChromosomeNames.equals(available, dataChrom)) {
                return available;
            }
        }
        // Peeked / partial contig lists (e.g. FACETS) may omit later chromosomes.
        return dataChrom;
    }
}
