package org.baseplayer.variant.annotation;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.CosmicCensusEntry;
import org.baseplayer.annotation.CosmicGenes;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.genome.gene.Transcript;
import org.baseplayer.utils.AminoAcids;
import org.baseplayer.utils.BaseUtils;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.variant.ComparisonProgress;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Annotates variants against loaded gene models.
 * Performs a 3-base reference fetch only for coding SNVs.
 * Structural variants are annotated with cancer-census genes only (type-specific rules).
 */
public class VariantAnnotator {

    private enum Direction { UPSTREAM, DOWNSTREAM }

    private final ReferenceGenomeService refService;

    public VariantAnnotator(ReferenceGenomeService refService) {
        this.refService = refService;
    }

    /** Annotate all variants in the list, setting node.annotation on each node directly. */
    public void annotate(VariantList variants, String chromosome) {
        if (variants == null) return;

        Map<String, List<Gene>> byChrom = AnnotationData.getGenesByChrom();
        String chromKey = ChromosomeNames.strip(chromosome);
        List<Gene> genes = byChrom.getOrDefault(chromKey, List.of());

        if (!variants.isEmpty()) {
            VariantNode node = variants.getFirst();
            while (node != null) {
                node.annotation = annotateVariant(node, chromKey, genes, byChrom);
                node = node.next;
            }
        }
        annotateLohRegions(variants, chromKey, genes, byChrom);
    }

    /**
     * Annotate synthetic LOH region nodes (outside the VCF linked list).
     * Safe to call after a visible-chain rebuild when the chromosome is already annotated.
     */
    public void annotateLohRegions(VariantList variants, String chromosome) {
        annotateLohRegions(variants, chromosome, null);
    }

    public void annotateLohRegions(
            VariantList variants, String chromosome, ComparisonProgress progress) {
        if (variants == null) {
            return;
        }
        Map<String, List<Gene>> byChrom = AnnotationData.getGenesByChrom();
        String chromKey = ChromosomeNames.strip(chromosome);
        List<Gene> genes = byChrom.getOrDefault(chromKey, List.of());
        annotateLohRegions(variants, chromKey, genes, byChrom, progress);
    }

    private void annotateLohRegions(
            VariantList variants,
            String chromKey,
            List<Gene> genes,
            Map<String, List<Gene>> byChrom) {
        annotateLohRegions(variants, chromKey, genes, byChrom, null);
    }

    private void annotateLohRegions(
            VariantList variants,
            String chromKey,
            List<Gene> genes,
            Map<String, List<Gene>> byChrom,
            ComparisonProgress progress) {
        List<VariantNode> regions = variants.getLohRegions();
        if (regions == null || regions.isEmpty()) {
            return;
        }
        int total = regions.size();
        int processed = 0;
        for (VariantNode node : regions) {
            if (node != null) {
                node.annotation = annotateVariant(node, chromKey, genes, byChrom);
            }
            processed++;
            if (progress != null && ((processed & 0xFF) == 0 || processed == total)) {
                progress.reportStageProgress(processed, total);
            }
        }
    }

    private VariantAnnotation annotateVariant(
            VariantNode node,
            String chromosome,
            List<Gene> genes,
            Map<String, List<Gene>> byChrom) {
        if (VariantTypeVisuals.isLohRegion(node.type)) {
            return annotateLohRegion(node, chromosome, genes);
        }
        if (VariantTypeVisuals.isStructural(node.type)) {
            return annotateStructural(node, chromosome, genes, byChrom);
        }
        return annotatePoint(node, chromosome, genes);
    }

    /**
     * LOH spans: every overlapping gene (not census-filtered), like DEL but all genes.
     */
    private VariantAnnotation annotateLohRegion(
            VariantNode node,
            String chromosome,
            List<Gene> genes) {
        long end = node.svEnd > node.position ? node.svEnd : node.position;
        List<String> overlapping = overlappingGenes(genes, node.position, end);
        String primary = overlapping.isEmpty() ? null : overlapping.get(0);
        boolean isCancer = primary != null && CosmicGenes.isCosmicGene(primary);
        // Prefer a census gene as primary when present among overlaps.
        if (!overlapping.isEmpty()) {
            for (String name : overlapping) {
                if (CosmicGenes.isCosmicGene(name)) {
                    primary = name;
                    isCancer = true;
                    break;
                }
            }
        }
        CosmicCensusEntry cosmic = primary != null && isCancer ? CosmicGenes.getEntry(primary) : null;
        VariantEffect effect = overlapping.isEmpty()
            ? VariantEffect.INTERGENIC
            : VariantEffect.NONCODING_GENE;

        return new VariantAnnotation(
            chromosome, node.position, effect,
            primary, null, null, null, 0,
            isCancer, cosmic, overlapping);
    }

    private VariantAnnotation annotateStructural(
            VariantNode node,
            String chromosome,
            List<Gene> genes,
            Map<String, List<Gene>> byChrom) {
        List<String> censusGenes = switch (node.type) {
            case SV_DELETION -> {
                long end = node.svEnd > node.position ? node.svEnd : node.position;
                yield overlappingCensusGenes(genes, node.position, end, CosmicGenes::isTumorSuppressor);
            }
            case SV_DUPLICATION -> {
                long end = node.svEnd > node.position ? node.svEnd : node.position;
                yield overlappingCensusGenes(genes, node.position, end, CosmicGenes::isOncogene);
            }
            case SV_INVERSION -> inversionNearestCensusGenes(genes, node);
            case SV_TRANSLOCATION, SV_BREAKEND -> translocationNearestCensusGenes(node, genes, byChrom);
            case SV_INSERTION -> nearestCensusAtLocus(genes, node.position);
            case LOH_AA, LOH_BB -> {
                // Routed via annotateLohRegion; keep switch exhaustive.
                long end = node.svEnd > node.position ? node.svEnd : node.position;
                yield overlappingGenes(genes, node.position, end);
            }
            default -> List.of();
        };

        String primary = censusGenes.isEmpty() ? null : censusGenes.get(0);
        boolean isCancer = !censusGenes.isEmpty();
        CosmicCensusEntry cosmic = primary != null ? CosmicGenes.getEntry(primary) : null;
        VariantEffect effect = isCancer ? VariantEffect.NONCODING_GENE : VariantEffect.INTERGENIC;

        return new VariantAnnotation(
            chromosome, node.position, effect,
            primary, null, null, null, 0,
            isCancer, cosmic, censusGenes);
    }

    private VariantAnnotation annotatePoint(VariantNode node, String chromosome, List<Gene> genes) {
        // Find the best overlapping gene (prefer protein-coding, prefer MANE transcript)
        Gene bestGene = null;
        Transcript bestTx = null;

        for (Gene gene : genes) {
            if (gene.start() > node.position) {
                break;
            }
            if (gene.start() > node.position || gene.end() < node.position) continue;

            Transcript tx = gene.getManeSelectTranscript();
            if (tx == null && gene.transcripts() != null && !gene.transcripts().isEmpty()) {
                tx = gene.transcripts().get(0);
            }
            if (tx == null) continue;

            // Prefer protein-coding genes
            if (bestGene == null || ("protein_coding".equals(gene.biotype())
                    && !"protein_coding".equals(bestGene.biotype()))) {
                bestGene = gene;
                bestTx = tx;
            }
        }

        if (bestGene == null) {
            return new VariantAnnotation(chromosome, node.position, VariantEffect.INTERGENIC,
                null, null, null, null, 0, false, null, List.of());
        }

        boolean isReverse = "-".equals(bestGene.strand());
        VariantEffect effect = classifyEffect(node, bestTx, isReverse, bestGene.biotype());

        String aaChange = null;
        String codonChange = null;
        int codonNumber = 0;

        // Compute AA change for SNVs in CDS
        if (effect.isCoding() && node.type == VcfVariantType.SNV
                && refService != null && refService.hasGenome()) {
            CodingPos pos = computeCodingPosition(node.position, bestTx, isReverse);
            if (pos != null) {
                codonNumber = pos.codonNumber;
                try {
                    String cdsSequence = TranscriptCdsCache.getInstance()
                            .getCodingSequence(bestGene, bestTx, refService);
                    if (cdsSequence != null) {
                        int codonStartIndex = (int) pos.posInCds - pos.posInCodon;
                        if (codonStartIndex >= 0 && codonStartIndex + 2 < cdsSequence.length()) {
                            String refCodon = cdsSequence.substring(codonStartIndex, codonStartIndex + 3);
                            char altBase = transcriptAltBase(node.alt, isReverse);
                            StringBuilder altCodonBuilder = new StringBuilder(refCodon);
                            altCodonBuilder.setCharAt(pos.posInCodon, altBase);
                            String altCodon = altCodonBuilder.toString();

                            char refAA = AminoAcids.translateCodon(refCodon);
                            char altAA = AminoAcids.translateCodon(altCodon);

                            // Reclassify based on actual AA change
                            if (refAA == '*') {
                                effect = VariantEffect.CODING_STOP_LOSS;
                            } else if (altAA == '*') {
                                effect = VariantEffect.CODING_STOP_GAIN;
                            } else if (refAA == altAA) {
                                effect = VariantEffect.CODING_SYNONYMOUS;
                            } else {
                                effect = VariantEffect.CODING_MISSENSE;
                            }

                            String refThree = AminoAcids.getThreeLetter(refAA);
                            String altThree = AminoAcids.getThreeLetter(altAA);
                            aaChange = "p." + refThree + codonNumber + altThree;

                            String refBase = node.ref.isEmpty() ? "?" : node.ref;
                            String altStr = node.alt.isEmpty() ? "?" : node.alt;
                            int cdsPos = (codonNumber - 1) * 3 + pos.posInCodon + 1;
                            codonChange = "c." + cdsPos + refBase + ">" + altStr;
                        }
                    }
                } catch (Exception e) {
                    // Reference access failed — keep effect as CODING_OTHER
                }
            }
        }

        boolean isCancerGene = CosmicGenes.isCosmicGene(bestGene.name());
        CosmicCensusEntry cosmicEntry = isCancerGene ? CosmicGenes.getEntry(bestGene.name()) : null;
        List<String> overlapping = isCancerGene ? List.of(bestGene.name()) : List.of();

        return new VariantAnnotation(chromosome, node.position, effect,
            bestGene.name(), bestTx.id(), aaChange, codonChange, codonNumber,
            isCancerGene, cosmicEntry, overlapping);
    }

    // ── SV / LOH gene helpers ─────────────────────────────────────────────────

    /** All genes overlapping inclusive [start, end] on a start-sorted gene list. */
    static List<String> overlappingGenes(List<Gene> genes, long start, long end) {
        if (genes == null || genes.isEmpty()) {
            return List.of();
        }
        long qStart = Math.min(start, end);
        long qEnd = Math.max(start, end);
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Gene gene : genes) {
            if (gene.start() > qEnd) {
                break;
            }
            if (gene.end() < qStart) {
                continue;
            }
            if (gene.name() == null || gene.name().isBlank()) {
                continue;
            }
            if (seen.add(gene.name())) {
                out.add(gene.name());
            }
        }
        return out;
    }

    /** All CGC genes overlapping inclusive [start, end] on a start-sorted gene list. */
    static List<String> overlappingCensusGenes(List<Gene> genes, long start, long end) {
        return overlappingCensusGenes(genes, start, end, CosmicGenes::isCosmicGene);
    }

    /**
     * CGC genes overlapping inclusive [start, end], kept only when {@code roleFilter} accepts the symbol.
     * Use {@link CosmicGenes#isTumorSuppressor} for deletions and {@link CosmicGenes#isOncogene} for duplications
     * (dual-role genes pass both filters).
     */
    static List<String> overlappingCensusGenes(
            List<Gene> genes,
            long start,
            long end,
            java.util.function.Predicate<String> roleFilter) {
        if (genes == null || genes.isEmpty() || roleFilter == null) {
            return List.of();
        }
        long qStart = Math.min(start, end);
        long qEnd = Math.max(start, end);
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Gene gene : genes) {
            if (gene.start() > qEnd) {
                break;
            }
            if (gene.end() < qStart) {
                continue;
            }
            if (gene.name() == null || gene.name().isBlank()) {
                continue;
            }
            if (!roleFilter.test(gene.name())) {
                continue;
            }
            if (seen.add(gene.name())) {
                out.add(gene.name());
            }
        }
        return out;
    }

    /**
     * INV: at each breakpoint, nearest CGC gene upstream and downstream (deduped, order preserved).
     */
    private static List<String> inversionNearestCensusGenes(List<Gene> genes, VariantNode node) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        long left = node.position;
        long right = node.svEnd > node.position ? node.svEnd : node.position;
        addNearestPair(genes, left, out, seen);
        if (right != left) {
            addNearestPair(genes, right, out, seen);
        }
        return out;
    }

    /** TRA/BND: nearest CGC at primary and mate breakpoints. */
    private static List<String> translocationNearestCensusGenes(
            VariantNode node,
            List<Gene> primaryGenes,
            Map<String, List<Gene>> byChrom) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();

        String primaryNearest = nearestCensusGeneAnySide(primaryGenes, node.position);
        if (primaryNearest != null) {
            seen.add(primaryNearest);
            out.add(primaryNearest);
        }

                String mateChrom = node.mateChromosome();
        long matePos = node.matePosition();
        if (mateChrom != null && !mateChrom.isBlank() && matePos >= 0) {
            String mateKey = ChromosomeNames.strip(mateChrom);
            List<Gene> mateGenes = byChrom.getOrDefault(mateKey, List.of());
            String mateNearest = nearestCensusGeneAnySide(mateGenes, matePos);
            if (mateNearest != null && seen.add(mateNearest)) {
                out.add(mateNearest);
            }
        }
        return out;
    }

    private static List<String> nearestCensusAtLocus(List<Gene> genes, long pos) {
        String name = nearestCensusGeneAnySide(genes, pos);
        return name != null ? List.of(name) : List.of();
    }

    private static void addNearestPair(List<Gene> genes, long pos, List<String> out, Set<String> seen) {
        String up = nearestCensusGene(genes, pos, Direction.UPSTREAM);
        String down = nearestCensusGene(genes, pos, Direction.DOWNSTREAM);
        if (up != null && seen.add(up)) {
            out.add(up);
        }
        if (down != null && seen.add(down)) {
            out.add(down);
        }
    }

    /**
     * Nearest CGC gene: genes containing {@code pos} win (distance 0);
     * otherwise closest by genomic distance in the requested direction.
     * UPSTREAM = gene entirely to the left (gene.end &lt; pos) or overlapping left edge.
     * DOWNSTREAM = gene entirely to the right (gene.start &gt; pos) or overlapping right edge.
     * Overlapping genes are accepted for both directions (distance 0).
     */
    static String nearestCensusGene(List<Gene> genes, long pos, Direction direction) {
        if (genes == null || genes.isEmpty()) {
            return null;
        }
        String best = null;
        long bestDist = Long.MAX_VALUE;

        for (Gene gene : genes) {
            if (!CosmicGenes.isCosmicGene(gene.name())) {
                continue;
            }
            boolean overlaps = gene.start() <= pos && gene.end() >= pos;
            long dist;
            if (overlaps) {
                dist = 0;
            } else if (direction == Direction.UPSTREAM) {
                if (gene.end() >= pos) {
                    continue; // not upstream
                }
                dist = pos - gene.end();
            } else {
                if (gene.start() <= pos) {
                    continue; // not downstream
                }
                dist = gene.start() - pos;
            }
            if (dist < bestDist
                    || (dist == bestDist && (best == null || gene.name().compareTo(best) < 0))) {
                bestDist = dist;
                best = gene.name();
            }
            // Genes are sorted by start; once past and looking downstream we can stop early
            // only when we've already found a non-overlapping downstream hit and gene.start keeps growing.
            if (direction == Direction.DOWNSTREAM && !overlaps && gene.start() > pos && dist > bestDist) {
                // still need to scan — overlapping later genes won't happen; but a closer start won't either
                // once gene.start - pos >= bestDist for non-overlap. Safe break when gene.start - pos >= bestDist.
                if (gene.start() - pos >= bestDist && bestDist < Long.MAX_VALUE) {
                    break;
                }
            }
        }
        return best;
    }

    /** Closest CGC on either side (or overlapping). */
    static String nearestCensusGeneAnySide(List<Gene> genes, long pos) {
        String up = nearestCensusGene(genes, pos, Direction.UPSTREAM);
        String down = nearestCensusGene(genes, pos, Direction.DOWNSTREAM);
        if (up == null) {
            return down;
        }
        if (down == null) {
            return up;
        }
        long upDist = censusDistance(genes, up, pos);
        long downDist = censusDistance(genes, down, pos);
        if (upDist <= downDist) {
            return up;
        }
        return down;
    }

    private static long censusDistance(List<Gene> genes, String name, long pos) {
        for (Gene gene : genes) {
            if (!name.equals(gene.name())) {
                continue;
            }
            if (gene.start() <= pos && gene.end() >= pos) {
                return 0;
            }
            if (gene.end() < pos) {
                return pos - gene.end();
            }
            return gene.start() - pos;
        }
        return Long.MAX_VALUE;
    }

    private VariantEffect classifyEffect(VariantNode node, Transcript tx, boolean isReverse, String biotype) {
        if (tx == null) return VariantEffect.INTRONIC;

        long pos = node.position;

        // Check exon overlap
        boolean inAnyExon = false;
        if (tx.exons() != null) {
            for (long[] exon : tx.exons()) {
                if (pos >= exon[0] && pos <= exon[1]) {
                    inAnyExon = true;
                    break;
                }
            }
        }

        if (inAnyExon) {
            // Inside exon — check UTR vs CDS
            if (!tx.hasCDS()) {
                // For non-coding genes (lincRNA, etc.), use NONCODING_GENE; otherwise CODING_OTHER
                return !"protein_coding".equals(biotype) ? VariantEffect.NONCODING_GENE : VariantEffect.CODING_OTHER;
            }

            if (pos < tx.cdsStart()) {
                return isReverse ? VariantEffect.UTR3 : VariantEffect.UTR5;
            }
            if (pos > tx.cdsEnd()) {
                return isReverse ? VariantEffect.UTR5 : VariantEffect.UTR3;
            }

            // In CDS — classify by variant type
            return switch (node.type) {
                case INSERTION, DELETION -> {
                    // Simple length-based check for frameshift
                    int refLen = node.ref.length();
                    int altLen = node.alt.isEmpty() ? 0 : node.alt.length();
                    int diff = Math.abs(altLen - refLen);
                    yield (diff % 3 == 0) ? VariantEffect.CODING_INFRAME : VariantEffect.CODING_FRAMESHIFT;
                }
                case SNV, MNV -> VariantEffect.CODING_OTHER; // refined later for SNVs
                default -> VariantEffect.CODING_OTHER;
            };
        }

        // Not in exon — check if near splice site (within 2 bases of exon boundary)
        if (tx.exons() != null) {
            for (long[] exon : tx.exons()) {
                // Check donor site (end of exon): positions [exon[1], exon[1]+2]
                if (pos > exon[1] && pos <= exon[1] + 2) {
                    return VariantEffect.SPLICE_SITE;
                }
                // Check acceptor site (start of exon): positions [exon[0]-2, exon[0]]
                if (pos >= exon[0] - 2 && pos < exon[0]) {
                    return VariantEffect.SPLICE_SITE;
                }
            }
        }

        return VariantEffect.INTRONIC;
    }

    /**
     * Compute the codon's genomic start position and offset-within-codon for a CDS variant.
     * Mirrors the cdsOffsets logic in DrawGene.
     */
    private CodingPos computeCodingPosition(long variantPos, Transcript tx, boolean isReverse) {
        if (tx == null || tx.exons() == null || !tx.hasCDS()) return null;

        List<long[]> exons = tx.exons();
        long cdsStart = tx.cdsStart();
        long cdsEnd = tx.cdsEnd();

        // Accumulate CDS bases exon by exon (strand-aware order)
        long cdsOffset = 0;
        boolean found = false;
        long posInCds = 0;

        if (isReverse) {
            for (int i = exons.size() - 1; i >= 0; i--) {
                long[] exon = exons.get(i);
                long regionStart = Math.max(exon[0], cdsStart);
                long regionEnd = Math.min(exon[1], cdsEnd);
                if (regionStart > regionEnd) continue;

                if (variantPos >= regionStart && variantPos <= regionEnd) {
                    // variantPos is in this exon; distance from regionEnd (reverse strand)
                    posInCds = cdsOffset + (regionEnd - variantPos);
                    found = true;
                    break;
                }
                cdsOffset += regionEnd - regionStart + 1;
            }
        } else {
            for (long[] exon : exons) {
                long regionStart = Math.max(exon[0], cdsStart);
                long regionEnd = Math.min(exon[1], cdsEnd);
                if (regionStart > regionEnd) continue;

                if (variantPos >= regionStart && variantPos <= regionEnd) {
                    posInCds = cdsOffset + (variantPos - regionStart);
                    found = true;
                    break;
                }
                cdsOffset += regionEnd - regionStart + 1;
            }
        }

        if (!found) return null;

        int codonNumber = (int) (posInCds / 3) + 1;
        int posInCodon = (int) (posInCds % 3);

        // Genomic start of the codon (forward: subtract offset; reverse: add offset)
        return new CodingPos(codonNumber, posInCodon, posInCds);
    }

    private char transcriptAltBase(String alt, boolean isReverse) {
        if (alt == null || alt.isEmpty()) return 'N';
        char base = Character.toUpperCase(alt.charAt(0));
        if (!isReverse) {
            return base;
        }
        return BaseUtils.reverseComplement(String.valueOf(base)).charAt(0);
    }

    private record CodingPos(int codonNumber, int posInCodon, long posInCds) {}
}
