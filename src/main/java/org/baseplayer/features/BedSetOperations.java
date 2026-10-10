package org.baseplayer.features;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.baseplayer.io.readers.BedFileReader.BedFeature;
import javafx.scene.paint.Color;

/**
 * Chromosome-wise BED interval set operations for feature-track aggregate ops.
 */
public final class BedSetOperations {

  public enum Op {
    UNION("Union"),
    INTERSECT("Intersect"),
    SUBTRACT("Subtract");

    private final String label;

    Op(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  private BedSetOperations() {}

  /**
   * Apply a set operation using each track's <em>currently loaded</em> features
   * (full genome for materialized tracks; viewport cache for tabix tracks).
   */
  public static BedTrack apply(Op op, List<BedTrack> sources) {
    if (sources == null || sources.size() < 2) {
      throw new IllegalArgumentException("Need at least two BED tracks");
    }
    for (BedTrack source : sources) {
      if (source == null) {
        throw new IllegalArgumentException("BED track is null");
      }
    }
    Map<String, List<BedFeature>> result = switch (op) {
      case UNION -> union(sources);
      case INTERSECT -> intersect(sources);
      case SUBTRACT -> subtract(sources);
    };
    String chromPrefix = sources.get(0).getChromPrefix();
    String name = buildResultName(op, sources);
    return BedTrack.derived(name, op.label(), result, chromPrefix, sources);
  }

  public static String buildResultName(Op op, List<BedTrack> sources) {
    StringBuilder sb = new StringBuilder(op.label()).append('(');
    for (int i = 0; i < sources.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      String part = sources.get(i).getName();
      if (part == null) {
        part = "?";
      }
      if (part.length() > 18) {
        part = part.substring(0, 16) + "…";
      }
      sb.append(part);
      if (sb.length() > 72 && i < sources.size() - 1) {
        sb.append(",…");
        break;
      }
    }
    sb.append(')');
    return sb.toString();
  }

  static Map<String, List<BedFeature>> union(List<BedTrack> sources) {
    Set<String> chroms = collectLoadedChromosomes(sources);
    Map<String, List<BedFeature>> out = new HashMap<>();
    for (String chrom : chroms) {
      List<Interval> intervals = new ArrayList<>();
      for (BedTrack track : sources) {
        for (BedFeature f : track.getLoadedFeatures(chrom)) {
          intervals.add(Interval.from(f));
        }
      }
      out.put(chrom, mergeOverlapping(chrom, intervals));
    }
    return out;
  }

  static Map<String, List<BedFeature>> intersect(List<BedTrack> sources) {
    Set<String> chroms = collectLoadedChromosomes(sources);
    Map<String, List<BedFeature>> out = new HashMap<>();
    for (String chrom : chroms) {
      List<Interval> current = toIntervals(sources.get(0).getLoadedFeatures(chrom));
      for (int t = 1; t < sources.size() && !current.isEmpty(); t++) {
        current = intersectLists(current, toIntervals(sources.get(t).getLoadedFeatures(chrom)));
      }
      out.put(chrom, toFeatures(chrom, current));
    }
    return out;
  }

  /** First track minus union of the rest (loaded features only). */
  static Map<String, List<BedFeature>> subtract(List<BedTrack> sources) {
    Set<String> chroms = collectLoadedChromosomes(sources);
    Map<String, List<BedFeature>> out = new HashMap<>();
    BedTrack base = sources.get(0);
    List<BedTrack> others = sources.subList(1, sources.size());
    for (String chrom : chroms) {
      List<Interval> baseIntervals = toIntervals(base.getLoadedFeatures(chrom));
      List<Interval> subtractors = new ArrayList<>();
      for (BedTrack track : others) {
        for (BedFeature f : track.getLoadedFeatures(chrom)) {
          subtractors.add(Interval.from(f));
        }
      }
      List<Interval> mergedSub = mergeOverlappingIntervals(subtractors);
      out.put(chrom, toFeatures(chrom, subtractLists(baseIntervals, mergedSub)));
    }
    return out;
  }

  /** Chromosomes that have at least one loaded feature in any source track. */
  private static Set<String> collectLoadedChromosomes(List<BedTrack> sources) {
    Set<String> chroms = new TreeSet<>();
    for (BedTrack track : sources) {
      chroms.addAll(track.getLoadedChromosomes());
    }
    return chroms;
  }

  private static List<Interval> toIntervals(List<BedFeature> features) {
    List<Interval> list = new ArrayList<>(features.size());
    for (BedFeature f : features) {
      list.add(Interval.from(f));
    }
    list.sort(Comparator.comparingLong((Interval i) -> i.start).thenComparingLong(i -> i.end));
    return list;
  }

  private static List<BedFeature> mergeOverlapping(String chrom, List<Interval> intervals) {
    return toFeatures(chrom, mergeOverlappingIntervals(intervals));
  }

  private static List<Interval> mergeOverlappingIntervals(List<Interval> intervals) {
    if (intervals.isEmpty()) {
      return List.of();
    }
    List<Interval> sorted = new ArrayList<>(intervals);
    sorted.sort(Comparator.comparingLong((Interval i) -> i.start).thenComparingLong(i -> i.end));
    List<Interval> merged = new ArrayList<>();
    Interval cur = sorted.get(0);
    for (int i = 1; i < sorted.size(); i++) {
      Interval next = sorted.get(i);
      if (next.start <= cur.end) {
        cur = cur.merge(next);
      } else {
        merged.add(cur);
        cur = next;
      }
    }
    merged.add(cur);
    return merged;
  }

  private static List<Interval> intersectLists(List<Interval> a, List<Interval> b) {
    List<Interval> out = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < a.size() && j < b.size()) {
      Interval x = a.get(i);
      Interval y = b.get(j);
      long start = Math.max(x.start, y.start);
      long end = Math.min(x.end, y.end);
      if (start < end) {
        Color color = x.color != null ? x.color : y.color;
        out.add(new Interval(
            start, end, joinNames(x.name, y.name), joinStrands(x.strand, y.strand), color));
      }
      if (x.end < y.end) {
        i++;
      } else if (y.end < x.end) {
        j++;
      } else {
        i++;
        j++;
      }
    }
    return out;
  }

  private static List<Interval> subtractLists(List<Interval> base, List<Interval> cutters) {
    if (base.isEmpty()) {
      return List.of();
    }
    if (cutters.isEmpty()) {
      return base;
    }
    List<Interval> out = new ArrayList<>();
    int j = 0;
    for (Interval b : base) {
      long cursor = b.start;
      while (j < cutters.size() && cutters.get(j).end <= cursor) {
        j++;
      }
      int k = j;
      while (k < cutters.size() && cutters.get(k).start < b.end) {
        Interval c = cutters.get(k);
        if (c.start > cursor) {
          out.add(new Interval(
              cursor, Math.min(c.start, b.end), b.name, b.strand, b.color));
        }
        cursor = Math.max(cursor, c.end);
        if (cursor >= b.end) {
          break;
        }
        k++;
      }
      if (cursor < b.end) {
        out.add(new Interval(cursor, b.end, b.name, b.strand, b.color));
      }
    }
    return out;
  }

  private static List<BedFeature> toFeatures(String chrom, List<Interval> intervals) {
    List<BedFeature> features = new ArrayList<>(intervals.size());
    for (Interval i : intervals) {
      // Keep source itemRgb when present; otherwise leave null for name-hash fallback.
      Color color = i.color;
      features.add(new BedFeature(chrom, i.start, i.end, i.name, 0, i.strand, color));
    }
    return features;
  }

  private static String joinNames(String a, String b) {
    if (a == null || a.isEmpty()) {
      return b == null ? "" : b;
    }
    if (b == null || b.isEmpty() || a.equals(b)) {
      return a;
    }
    Set<String> parts = new LinkedHashSet<>();
    for (String p : a.split(";")) {
      if (!p.isBlank()) {
        parts.add(p.trim());
      }
    }
    for (String p : b.split(";")) {
      if (!p.isBlank()) {
        parts.add(p.trim());
      }
    }
    return String.join(";", parts);
  }

  private static String joinStrands(String a, String b) {
    if (a == null || a.isEmpty() || ".".equals(a)) {
      return b == null || b.isEmpty() ? "." : b;
    }
    if (b == null || b.isEmpty() || ".".equals(b) || a.equals(b)) {
      return a;
    }
    return ".";
  }

  private static final class Interval {
    final long start;
    final long end;
    final String name;
    final String strand;
    final Color color;

    Interval(long start, long end, String name, String strand, Color color) {
      this.start = start;
      this.end = end;
      this.name = name == null ? "" : name;
      this.strand = strand == null || strand.isEmpty() ? "." : strand;
      this.color = color;
    }

    static Interval from(BedFeature f) {
      return new Interval(f.start(), f.end(), f.name(), f.strand(), f.color());
    }

    Interval merge(Interval other) {
      Color mergedColor = color != null ? color : other.color;
      return new Interval(
          Math.min(start, other.start),
          Math.max(end, other.end),
          joinNames(name, other.name),
          joinStrands(strand, other.strand),
          mergedColor);
    }
  }
}
