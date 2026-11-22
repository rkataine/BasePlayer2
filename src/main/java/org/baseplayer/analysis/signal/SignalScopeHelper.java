package org.baseplayer.analysis.signal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.analysis.GenomicScope;

/** Resolves chromosomes / intervals for a {@link GenomicScope}. */
public final class SignalScopeHelper {

  private SignalScopeHelper() {}

  public record Region(String chrom, long start1Based, long end1Based) {}

  public static DrawStack activeStack() {
    var mgr = ServiceRegistry.getInstance().getDrawStackManager();
    DrawStack stack = mgr.getHoverStack();
    if (stack == null) {
      List<DrawStack> stacks = mgr.getStacks();
      if (stacks != null && !stacks.isEmpty()) {
        stack = stacks.get(0);
      }
    }
    return stack;
  }

  public static String currentChrom() {
    DrawStack stack = activeStack();
    return stack != null ? ChromosomeNames.strip(stack.getChromosome()) : null;
  }

  public static Region currentView() {
    DrawStack stack = activeStack();
    if (stack == null) {
      return null;
    }
    String chrom = ChromosomeNames.strip(stack.getChromosome());
    long start = Math.max(1, (long) Math.floor(stack.getViewStart()));
    long end = Math.max(start, (long) Math.ceil(stack.getViewEnd()));
    return new Region(chrom, start, end);
  }

  /** Chromosome keys to iterate for gene / window scans. */
  public static List<String> chromosomes(GenomicScope scope, Set<String> available) {
    GenomicScope s = scope != null ? scope : GenomicScope.ALL_CHROMS;
    if (s == GenomicScope.CURRENT_VIEW || s == GenomicScope.CURRENT_CHROM) {
      String chrom = currentChrom();
      if (chrom == null) {
        return List.of();
      }
      if (available != null && !available.isEmpty() && !available.contains(chrom)
          && !available.contains("chr" + chrom)) {
        // Still return requested chrom; callers may remap.
      }
      return List.of(chrom);
    }
    if (available == null || available.isEmpty()) {
      Map<String, List<Gene>> byChrom = AnnotationData.getGenesByChrom();
      return byChrom != null ? new ArrayList<>(byChrom.keySet()) : List.of();
    }
    return new ArrayList<>(available);
  }

  public static List<Region> windowsForScope(GenomicScope scope, int windowBp, Set<String> available) {
    int w = Math.max(100, windowBp);
    List<Region> out = new ArrayList<>();
    if (scope == GenomicScope.CURRENT_VIEW) {
      Region view = currentView();
      if (view == null) {
        return out;
      }
      for (long start = view.start1Based(); start <= view.end1Based() && out.size() < SignalSourceSpec.MAX_WINDOW_UNITS; start += w) {
        long end = Math.min(view.end1Based(), start + w - 1);
        out.add(new Region(view.chrom(), start, end));
      }
      return out;
    }
    List<String> chroms = chromosomes(scope, available);
    Map<String, List<Gene>> byChrom = AnnotationData.getGenesByChrom();
    for (String chrom : chroms) {
      long chromEnd = chromLength(chrom, byChrom);
      if (chromEnd <= 0) {
        Region view = currentView();
        if (view != null && chrom.equals(view.chrom())) {
          chromEnd = view.end1Based();
        } else {
          continue;
        }
      }
      for (long start = 1; start <= chromEnd && out.size() < SignalSourceSpec.MAX_WINDOW_UNITS; start += w) {
        long end = Math.min(chromEnd, start + w - 1);
        out.add(new Region(chrom, start, end));
      }
      if (out.size() >= SignalSourceSpec.MAX_WINDOW_UNITS) {
        break;
      }
    }
    return out;
  }

  private static long chromLength(String chrom, Map<String, List<Gene>> byChrom) {
    if (byChrom == null) {
      return 0;
    }
    List<Gene> genes = byChrom.get(ChromosomeNames.strip(chrom));
    if (genes == null || genes.isEmpty()) {
      genes = byChrom.get(chrom);
    }
    if (genes == null || genes.isEmpty()) {
      return 0;
    }
    long max = 0;
    for (Gene g : genes) {
      if (g.end() > max) {
        max = g.end();
      }
    }
    return max;
  }

  public static String windowUnitId(Region region) {
    return region.chrom() + ":" + region.start1Based() + "-" + region.end1Based();
  }

  @SafeVarargs
  public static Set<String> unionChromKeys(Set<String>... sets) {
    Set<String> out = new LinkedHashSet<>();
    if (sets == null) {
      return out;
    }
    for (Set<String> set : sets) {
      if (set != null) {
        out.addAll(set);
      }
    }
    return out;
  }
}
