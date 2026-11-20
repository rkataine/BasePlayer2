package org.baseplayer.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.utils.ChromosomeNames;

/**
 * Public gene-overlap helpers extracted from variant annotation.
 * Gene lists are start-sorted; queries use inclusive 1-based coordinates.
 */
public final class GeneOverlapIndex {

  private GeneOverlapIndex() {}

  /** Prefer protein-coding gene overlapping an inclusive point; null if none. */
  public static Gene bestGeneAt(String chromosome, long position1Based) {
    List<Gene> genes = genesFor(chromosome);
    Gene best = null;
    for (Gene gene : genes) {
      if (gene.start() > position1Based) {
        break;
      }
      if (gene.end() < position1Based) {
        continue;
      }
      if (best == null
          || ("protein_coding".equals(gene.biotype())
              && !"protein_coding".equals(best.biotype()))) {
        best = gene;
      }
    }
    return best;
  }

  /**
   * Prefer protein-coding gene overlapping inclusive interval [start1, end1].
   * When several overlap, prefers protein_coding then earliest start.
   */
  public static Gene bestGeneOverlapping(String chromosome, long start1Based, long end1Based) {
    long qStart = Math.min(start1Based, end1Based);
    long qEnd = Math.max(start1Based, end1Based);
    List<Gene> genes = genesFor(chromosome);
    Gene best = null;
    for (Gene gene : genes) {
      if (gene.start() > qEnd) {
        break;
      }
      if (gene.end() < qStart) {
        continue;
      }
      boolean geneCoding = "protein_coding".equals(gene.biotype());
      boolean bestCoding = best != null && "protein_coding".equals(best.biotype());
      if (best == null
          || (geneCoding && !bestCoding)
          || (geneCoding == bestCoding && gene.start() < best.start())) {
        best = gene;
      }
    }
    return best;
  }

  /** All distinct gene names overlapping inclusive [start1, end1]. */
  public static List<String> overlappingGeneNames(
      String chromosome, long start1Based, long end1Based) {
    long qStart = Math.min(start1Based, end1Based);
    long qEnd = Math.max(start1Based, end1Based);
    List<Gene> genes = genesFor(chromosome);
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

  /**
   * Resolve a BED name column as a gene symbol or Ensembl id when possible.
   * Returns null when the name is not a known gene.
   */
  public static Gene resolveByName(String name) {
    if (name == null || name.isBlank() || ".".equals(name)) {
      return null;
    }
    GeneLocation loc = AnnotationData.getGeneLocation(name);
    if (loc == null) {
      return null;
    }
    List<Gene> genes = genesFor(loc.chrom());
    for (Gene gene : genes) {
      if (name.equalsIgnoreCase(gene.name()) || name.equalsIgnoreCase(gene.id())) {
        return gene;
      }
    }
    String biotype = AnnotationData.getGeneBiotype(name);
    String description = AnnotationData.getGeneDescription(name);
    return new Gene(
        loc.chrom(),
        loc.start(),
        loc.end(),
        name,
        name,
        "+",
        biotype,
        description,
        List.of(),
        List.of());
  }

  public static List<Gene> genesFor(String chromosome) {
    if (chromosome == null) {
      return List.of();
    }
    Map<String, List<Gene>> byChrom = AnnotationData.getGenesByChrom();
    if (byChrom == null || byChrom.isEmpty()) {
      return List.of();
    }
    String key = ChromosomeNames.strip(chromosome);
    List<Gene> genes = byChrom.get(key);
    return genes != null ? genes : List.of();
  }
}
