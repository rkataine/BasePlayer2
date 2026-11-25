package org.baseplayer.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.AnnotationLoader;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.utils.GeneBiotypeVisibility;

/**
 * Public gene-overlap helpers extracted from variant annotation.
 * Gene lists are start-sorted; queries use inclusive 1-based coordinates.
 * Respects {@link GeneBiotypeVisibility} (Genome sidebar gene-type legend).
 */
public final class GeneOverlapIndex {

  private GeneOverlapIndex() {}

  /** Prefer protein-coding gene overlapping an inclusive point; null if none. */
  public static Gene bestGeneAt(String chromosome, long position1Based) {
    List<Gene> genes = genesFor(chromosome);
    GeneBiotypeVisibility visibility = GeneBiotypeVisibility.get();
    Gene best = null;
    for (Gene gene : genes) {
      if (gene.start() > position1Based) {
        break;
      }
      if (gene.end() < position1Based) {
        continue;
      }
      if (!visibility.isVisible(gene)) {
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
    GeneBiotypeVisibility visibility = GeneBiotypeVisibility.get();
    Gene best = null;
    for (Gene gene : genes) {
      if (gene.start() > qEnd) {
        break;
      }
      if (gene.end() < qStart) {
        continue;
      }
      if (!visibility.isVisible(gene)) {
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

  /**
   * Inclusive overlap in base pairs between a segment and a gene.
   * Uses the segment length when {@code gene} is null or the intervals do not meet.
   */
  public static long overlapBp(long segStart, long segEnd, Gene gene) {
    long a0 = Math.min(segStart, segEnd);
    long a1 = Math.max(segStart, segEnd);
    long segLen = a1 - a0 + 1;
    if (gene == null) {
      return segLen;
    }
    long b0 = Math.min(gene.start(), gene.end());
    long b1 = Math.max(gene.start(), gene.end());
    long start = Math.max(a0, b0);
    long end = Math.min(a1, b1);
    if (end < start) {
      return segLen;
    }
    return end - start + 1;
  }

  /** All distinct gene names overlapping inclusive [start1, end1]. */
  public static List<String> overlappingGeneNames(
      String chromosome, long start1Based, long end1Based) {
    long qStart = Math.min(start1Based, end1Based);
    long qEnd = Math.max(start1Based, end1Based);
    List<Gene> genes = genesFor(chromosome);
    GeneBiotypeVisibility visibility = GeneBiotypeVisibility.get();
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
      if (!visibility.isVisible(gene)) {
        continue;
      }
      if (seen.add(gene.name())) {
        out.add(gene.name());
      }
    }
    return out;
  }

  /**
   * Resolve a BED/matrix name as a catalog gene symbol or Ensembl id.
   * Prefers the annotation {@link Gene#name()} (HGNC-style symbol). Versioned
   * Ensembl ids ({@code ENSG….15}) match unversioned GFF ids.
   * Returns null when unknown or hidden by biotype visibility — never a synthetic
   * gene whose display name is the raw ENSG string.
   */
  public static Gene resolveByName(String name) {
    if (name == null || name.isBlank() || ".".equals(name)) {
      return null;
    }
    String needle = name.trim();
    String needleId = stripGenePrefix(needle);
    String needleVersionless = AnnotationLoader.stripEnsemblVersion(needleId);
    GeneLocation loc = AnnotationData.getGeneLocation(needle);
    if (loc == null && !needleId.equalsIgnoreCase(needle)) {
      loc = AnnotationData.getGeneLocation(needleId);
    }
    if (loc == null && !needleVersionless.equalsIgnoreCase(needleId)) {
      loc = AnnotationData.getGeneLocation(needleVersionless);
    }
    if (loc == null) {
      return null;
    }
    List<Gene> genes = genesFor(loc.chrom());
    GeneBiotypeVisibility visibility = GeneBiotypeVisibility.get();
    for (Gene gene : genes) {
      if (idsMatch(needle, needleId, needleVersionless, gene.name())
          || idsMatch(needle, needleId, needleVersionless, gene.id())) {
        return visibility.isVisible(gene) ? gene : null;
      }
    }
    // Locus known but no catalog Gene matched — do not invent an ENSG-named Gene.
    return null;
  }

  private static String stripGenePrefix(String id) {
    if (id != null && id.regionMatches(true, 0, "gene:", 0, 5)) {
      return id.substring(5);
    }
    return id != null ? id : "";
  }

  private static boolean idsMatch(
      String needle, String needleId, String needleVersionless, String candidate) {
    if (candidate == null || candidate.isBlank()) {
      return false;
    }
    if (needle.equalsIgnoreCase(candidate)) {
      return true;
    }
    String candId = stripGenePrefix(candidate);
    String candVersionless = AnnotationLoader.stripEnsemblVersion(candId);
    return needleId.equalsIgnoreCase(candId)
        || needle.equalsIgnoreCase(candId)
        || needleVersionless.equalsIgnoreCase(candId)
        || needleId.equalsIgnoreCase(candVersionless)
        || needleVersionless.equalsIgnoreCase(candVersionless);
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
