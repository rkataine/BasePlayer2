package org.baseplayer.variant.annotation;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.CosmicCensusEntry;
import org.baseplayer.annotation.CosmicGenes;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.genome.gene.Transcript;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.utils.AminoAcids;

import java.util.List;
import java.util.Map;

/**
 * Annotates variants against loaded gene models.
 * Performs a 3-base reference fetch only for coding SNVs.
 */
public class VariantAnnotator {

    private final ReferenceGenomeService refService;

    public VariantAnnotator(ReferenceGenomeService refService) {
        this.refService = refService;
    }

    /** Annotate all variants in the list, setting node.annotation on each node directly. */
    public void annotate(VariantList variants, String chromosome) {
        if (variants == null || variants.isEmpty()) return;

        Map<String, List<Gene>> byChrom = AnnotationData.getGenesByChrom();
        String chromKey = chromosome;
        if (!byChrom.containsKey(chromKey) && chromKey.startsWith("chr")) {
            chromKey = chromKey.substring(3);
        } else if (!byChrom.containsKey(chromKey)) {
            chromKey = "chr" + chromKey;
        }
        List<Gene> genes = byChrom.getOrDefault(chromKey, List.of());

        VariantNode node = variants.getFirst();
        while (node != null) {
            node.annotation = annotateVariant(node, chromosome, genes);
            node = node.next;
        }
    }

    private VariantAnnotation annotateVariant(VariantNode node, String chromosome, List<Gene> genes) {
        // Find the best overlapping gene (prefer protein-coding, prefer MANE transcript)
        Gene bestGene = null;
        Transcript bestTx = null;

        for (Gene gene : genes) {
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
                null, null, null, null, 0, false, null);
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
                    String refCodon = refService.getBases(chromosome, (int) pos.codonGenomicStart,
                                                         (int) pos.codonGenomicStart + 2);
                    if (refCodon != null && refCodon.length() == 3) {
                        char[] altCodonChars = refCodon.toCharArray();
                        char altBase = node.alt.isEmpty() ? '?' : node.alt.charAt(0);
                        if (isReverse) altBase = complement(altBase);
                        altCodonChars[pos.posInCodon] = altBase;
                        String altCodon = new String(altCodonChars);

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
                } catch (Exception e) {
                    // Reference access failed — keep effect as CODING_OTHER
                }
            }
        }

        boolean isCancerGene = CosmicGenes.isCosmicGene(bestGene.name());
        CosmicCensusEntry cosmicEntry = isCancerGene ? CosmicGenes.getEntry(bestGene.name()) : null;

        return new VariantAnnotation(chromosome, node.position, effect,
            bestGene.name(), bestTx.id(), aaChange, codonChange, codonNumber,
            isCancerGene, cosmicEntry);
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
        long codonGenomicStart;
        if (isReverse) {
            // On reverse strand the codon runs right-to-left; first base is at highest genomic position
            codonGenomicStart = variantPos + posInCodon - 2;
        } else {
            codonGenomicStart = variantPos - posInCodon;
        }

        return new CodingPos(codonNumber, posInCodon, codonGenomicStart);
    }

    private static char complement(char base) {
        return switch (Character.toUpperCase(base)) {
            case 'A' -> 'T';
            case 'T' -> 'A';
            case 'C' -> 'G';
            case 'G' -> 'C';
            default  -> base;
        };
    }

    private record CodingPos(int codonNumber, int posInCodon, long codonGenomicStart) {}
}
