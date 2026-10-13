package org.baseplayer.features;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.baseplayer.io.readers.BedFileReader.BedFeature;
import javafx.scene.paint.Color;

/**
 * Chromosome-wise BED interval set operations for overlaying one BED onto another.
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

  /**
   * Which input track supplies name / strand / color on result intervals
   * (Intersect and Union). Subtract always keeps the base (first) track attrs.
   */
  public enum AttributeSource {
    FIRST,
    SECOND
  }

  /** Result of the overlay dialog: operation + which side’s attrs to keep. */
  public record OverlayChoice(Op op, AttributeSource attributeSource) {
    public OverlayChoice {
      if (op == null) {
        throw new IllegalArgumentException("op is null");
      }
      if (attributeSource == null) {
        attributeSource = AttributeSource.FIRST;
      }
    }
  }

  private BedSetOperations() {}

  /**
   * Overlay {@code second} onto {@code first} using currently loaded features
   * (full genome for materialized tracks; viewport cache for tabix tracks).
   */
  public static BedTrack apply(
      Op op, BedTrack first, BedTrack second, AttributeSource attributeSource) {
    if (op == null) {
      throw new IllegalArgumentException("op is null");
    }
    if (first == null || second == null) {
      throw new IllegalArgumentException("Need two BED tracks");
    }
    AttributeSource attrs =
        attributeSource != null ? attributeSource : AttributeSource.FIRST;
    List<BedTrack> sources = List.of(first, second);
    Map<String, List<BedFeature>> result = switch (op) {
      case UNION -> union(first, second, attrs);
      case INTERSECT -> intersect(first, second, attrs);
      case SUBTRACT -> subtract(first, second);
    };
    String chromPrefix = first.getChromPrefix();
    String name = buildResultName(op, sources);
    BedTrack derived = BedTrack.derived(name, op.label(), result, chromPrefix, sources);
    derived.setOverlayChoice(new OverlayChoice(op, attrs));
    return derived;
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

  static Map<String, List<BedFeature>> union(
      BedTrack first, BedTrack second, AttributeSource attrs) {
    Set<String> chroms = collectLoadedChromosomes(List.of(first, second));
    Map<String, List<BedFeature>> out = new HashMap<>();
    for (String chrom : chroms) {
      List<Interval> intervals = new ArrayList<>();
      for (BedFeature f : first.getLoadedFeatures(chrom)) {
        intervals.add(Interval.from(f, 0));
      }
      for (BedFeature f : second.getLoadedFeatures(chrom)) {
        intervals.add(Interval.from(f, 1));
      }
      out.put(chrom, toFeatures(chrom, mergeOverlappingIntervals(intervals, attrs)));
    }
    return out;
  }

  static Map<String, List<BedFeature>> intersect(
      BedTrack first, BedTrack second, AttributeSource attrs) {
    Set<String> chroms = collectLoadedChromosomes(List.of(first, second));
    Map<String, List<BedFeature>> out = new HashMap<>();
    for (String chrom : chroms) {
      List<Interval> a = toIntervals(first.getLoadedFeatures(chrom), 0);
      List<Interval> b = toIntervals(second.getLoadedFeatures(chrom), 1);
      out.put(chrom, toFeatures(chrom, intersectLists(a, b, attrs)));
    }
    return out;
  }

  /** First track minus second (loaded features only). */
  static Map<String, List<BedFeature>> subtract(BedTrack first, BedTrack second) {
    Set<String> chroms = collectLoadedChromosomes(List.of(first, second));
    Map<String, List<BedFeature>> out = new HashMap<>();
    for (String chrom : chroms) {
      List<Interval> baseIntervals = toIntervals(first.getLoadedFeatures(chrom), 0);
      List<Interval> subtractors = toIntervals(second.getLoadedFeatures(chrom), 1);
      List<Interval> mergedSub = mergeOverlappingIntervals(subtractors, AttributeSource.FIRST);
      out.put(chrom, toFeatures(chrom, subtractLists(baseIntervals, mergedSub)));
    }
    return out;
  }

  private static Set<String> collectLoadedChromosomes(List<BedTrack> sources) {
    Set<String> chroms = new TreeSet<>();
    for (BedTrack track : sources) {
      chroms.addAll(track.getLoadedChromosomes());
    }
    return chroms;
  }

  private static List<Interval> toIntervals(List<BedFeature> features, int sourceIndex) {
    List<Interval> list = new ArrayList<>(features.size());
    for (BedFeature f : features) {
      list.add(Interval.from(f, sourceIndex));
    }
    list.sort(Comparator.comparingLong((Interval i) -> i.start).thenComparingLong(i -> i.end));
    return list;
  }

  private static List<Interval> mergeOverlappingIntervals(
      List<Interval> intervals, AttributeSource attrs) {
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
        cur = cur.merge(next, attrs);
      } else {
        merged.add(cur);
        cur = next;
      }
    }
    merged.add(cur);
    return merged;
  }

  private static List<Interval> intersectLists(
      List<Interval> a, List<Interval> b, AttributeSource attrs) {
    List<Interval> out = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < a.size() && j < b.size()) {
      Interval x = a.get(i);
      Interval y = b.get(j);
      long start = Math.max(x.start, y.start);
      long end = Math.min(x.end, y.end);
      if (start < end) {
        Interval preferred = attrs == AttributeSource.SECOND ? y : x;
        out.add(new Interval(
            start, end, preferred.name, preferred.strand, preferred.color, preferred.sourceIndex));
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
              cursor, Math.min(c.start, b.end), b.name, b.strand, b.color, b.sourceIndex));
        }
        cursor = Math.max(cursor, c.end);
        if (cursor >= b.end) {
          break;
        }
        k++;
      }
      if (cursor < b.end) {
        out.add(new Interval(cursor, b.end, b.name, b.strand, b.color, b.sourceIndex));
      }
    }
    return out;
  }

  private static List<BedFeature> toFeatures(String chrom, List<Interval> intervals) {
    List<BedFeature> features = new ArrayList<>(intervals.size());
    for (Interval i : intervals) {
      // Keep source itemRgb when present; otherwise leave null for name-hash fallback.
      features.add(new BedFeature(chrom, i.start, i.end, i.name, 0, i.strand, i.color));
    }
    return features;
  }

  private static final class Interval {
    final long start;
    final long end;
    final String name;
    final String strand;
    final Color color;
    final int sourceIndex;

    Interval(long start, long end, String name, String strand, Color color, int sourceIndex) {
      this.start = start;
      this.end = end;
      this.name = name == null ? "" : name;
      this.strand = strand == null || strand.isEmpty() ? "." : strand;
      this.color = color;
      this.sourceIndex = sourceIndex;
    }

    static Interval from(BedFeature f, int sourceIndex) {
      return new Interval(f.start(), f.end(), f.name(), f.strand(), f.color(), sourceIndex);
    }

    Interval merge(Interval other, AttributeSource attrs) {
      Interval preferred;
      if (attrs == AttributeSource.SECOND) {
        preferred = other.sourceIndex == 1 ? other : (sourceIndex == 1 ? this : other);
      } else {
        preferred = sourceIndex == 0 ? this : (other.sourceIndex == 0 ? other : this);
      }
      return new Interval(
          Math.min(start, other.start),
          Math.max(end, other.end),
          preferred.name,
          preferred.strand,
          preferred.color,
          preferred.sourceIndex);
    }
  }
}
