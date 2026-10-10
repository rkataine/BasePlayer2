package org.baseplayer.io.readers;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.baseplayer.io.readers.BedFileReader.BedFeature;
import org.baseplayer.utils.ChromosomeNames;

import htsjdk.samtools.util.BlockCompressedInputStream;
import htsjdk.tribble.AbstractFeatureReader;
import htsjdk.tribble.bed.BEDCodec;
import htsjdk.tribble.bed.BEDFeature;
import htsjdk.tribble.readers.LineIterator;
import htsjdk.tribble.readers.TabixReader;

/**
 * Tabix-backed BED reader for bgzip + {@code .tbi} files. Queries only the
 * requested genomic window instead of materializing the whole genome.
 */
public final class IndexedBedReader implements AutoCloseable {

  private final Path bedPath;
  private final AbstractFeatureReader<BEDFeature, LineIterator> reader;
  private final String chromPrefix;
  private final List<String> sequenceNames;

  public IndexedBedReader(Path bedPath) throws IOException {
    this.bedPath = bedPath.toAbsolutePath().normalize();
    if (!Files.exists(this.bedPath)) {
      throw new IOException("BED file not found: " + this.bedPath);
    }
    Path tbi = findTabixIndex(this.bedPath);
    if (tbi == null) {
      throw new IOException("Tabix index (.tbi) not found for: " + this.bedPath);
    }
    if (!isBgzf(this.bedPath)) {
      throw new IOException("Tabix BED requires BGZF compression: " + this.bedPath);
    }

    this.sequenceNames = readSequenceNames(this.bedPath, tbi);
    this.chromPrefix = ChromosomeNames.detectPrefix(this.sequenceNames);
    // ZERO keeps BED 0-based half-open coords in Feature.getStart()/getEnd().
    this.reader = AbstractFeatureReader.getFeatureReader(
        this.bedPath.toString(),
        tbi.toString(),
        new BEDCodec(BEDCodec.StartOffset.ZERO),
        true);
  }

  public static Path findTabixIndex(Path bedPath) {
    if (bedPath == null) {
      return null;
    }
    Path tbi = bedPath.resolveSibling(bedPath.getFileName().toString() + ".tbi");
    if (Files.isRegularFile(tbi)) {
      return tbi;
    }
    String name = bedPath.getFileName().toString();
    if (name.endsWith(".gz")) {
      Path alt = bedPath.resolveSibling(name.substring(0, name.length() - 3) + ".tbi");
      if (Files.isRegularFile(alt)) {
        return alt;
      }
    }
    return null;
  }

  public static boolean hasTabixIndex(Path bedPath) {
    return findTabixIndex(bedPath) != null;
  }

  public String getChromPrefix() {
    return chromPrefix;
  }

  public List<String> getSequenceNames() {
    return sequenceNames;
  }

  public Path getBedPath() {
    return bedPath;
  }

  public static final int DEFAULT_MAX_FEATURES = 25_000;

  /**
   * Query overlapping features for an internal (unprefixed) chromosome and
   * 0-based half-open interval [{@code start0}, {@code end0}).
   * Stops after {@link #DEFAULT_MAX_FEATURES} hits to bound memory.
   */
  public List<BedFeature> query(String internalChromosome, long start0, long end0)
      throws IOException {
    return query(internalChromosome, start0, end0, DEFAULT_MAX_FEATURES);
  }

  /**
   * Tabix / AbstractFeatureReader is not thread-safe — serialize all queries
   * on this reader instance.
   */
  public List<BedFeature> query(
      String internalChromosome, long start0, long end0, int maxFeatures)
      throws IOException {
    if (internalChromosome == null || end0 <= start0) {
      return List.of();
    }
    int limit = Math.max(1, maxFeatures);
    String dataChrom = ChromosomeNames.forData(internalChromosome, chromPrefix);
    // Tribble/Tabix query API uses 1-based closed coordinates.
    int qStart = (int) Math.max(1, Math.min(Integer.MAX_VALUE, start0 + 1));
    int qEnd = (int) Math.max(qStart, Math.min(Integer.MAX_VALUE, end0));
    synchronized (this) {
      List<BedFeature> out = new ArrayList<>();
      try (var iterator = reader.query(dataChrom, qStart, qEnd)) {
        while (iterator.hasNext() && out.size() < limit) {
          BedFeature converted = toBedFeature(iterator.next());
          if (converted != null) {
            out.add(converted);
          }
        }
      } catch (Exception e) {
        throw new IOException("Failed to query BED: " + e.getMessage(), e);
      }
      out.sort(java.util.Comparator
          .comparingLong(BedFeature::start)
          .thenComparingLong(BedFeature::end));
      return out;
    }
  }

  private static BedFeature toBedFeature(BEDFeature feature) {
    if (feature == null) {
      return null;
    }
    String chrom = ChromosomeNames.strip(feature.getContig());
    long start = feature.getStart(); // 0-based with StartOffset.ZERO
    long end = feature.getEnd();
    if (end < start) {
      return null;
    }
    String name = feature.getName() != null ? feature.getName() : "";
    double score = feature.getScore();
    String strand = ".";
    if (feature.getStrand() != null) {
      strand = switch (feature.getStrand()) {
        case POSITIVE -> "+";
        case NEGATIVE -> "-";
        default -> ".";
      };
    }
    javafx.scene.paint.Color color = null;
    Color awt = feature.getColor();
    if (awt != null && !(awt.getRed() == 0 && awt.getGreen() == 0 && awt.getBlue() == 0)) {
      color = javafx.scene.paint.Color.rgb(awt.getRed(), awt.getGreen(), awt.getBlue());
    }
    return new BedFeature(chrom, start, end, name, score, strand, color);
  }

  private static List<String> readSequenceNames(Path bedPath, Path tbi) throws IOException {
    try (TabixReader tabix = new TabixReader(bedPath.toString(), tbi.toString())) {
      List<String> names = new ArrayList<>();
      for (String chrom : tabix.getChromosomes()) {
        if (chrom != null && !chrom.isBlank()) {
          names.add(chrom);
        }
      }
      return List.copyOf(names);
    } catch (Exception e) {
      throw new IOException("Failed to read tabix sequences: " + e.getMessage(), e);
    }
  }

  private static boolean isBgzf(Path path) {
    try (var in = new java.io.BufferedInputStream(Files.newInputStream(path))) {
      return BlockCompressedInputStream.isValidFile(in);
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public void close() {
    try {
      reader.close();
    } catch (IOException ignored) {
    }
  }
}
