package org.baseplayer.variant.annotation;

import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.genome.gene.Transcript;
import org.baseplayer.io.cache.DataCacheManager;
import org.baseplayer.utils.BaseUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lazily caches transcript coding sequences in transcript orientation.
 *
 * The cached payload is only the CDS string, which keeps memory use small while
 * avoiding repeated FASTA random access for every coding SNV.
 */
public final class TranscriptCdsCache {

  private static final TranscriptCdsCache INSTANCE = new TranscriptCdsCache();
  private static final int CACHE_VERSION = 1;
  private static final AtomicLong HIT_COUNT = new AtomicLong();
  private static final AtomicLong MISS_COUNT = new AtomicLong();
  private static final AtomicLong BUILD_COUNT = new AtomicLong();

  // Memory cache has no size limit; access-ordered (LRU-style tracking but no eviction).
  // This ensures all cached CDS sequences remain in memory for fast reuse during
  // variant annotation and gene-level visualization.
  private final Map<String, String> memoryCache = new LinkedHashMap<>(64, 0.75f, true);

  private final Path cacheDir;

  private TranscriptCdsCache() {
    Path root = DataCacheManager.getCacheRoot();
    cacheDir = root != null ? root.resolve("transcript_cds") : null;
    if (cacheDir != null) {
      try {
        Files.createDirectories(cacheDir);
      } catch (IOException e) {
        System.err.println("Failed to create transcript CDS cache directory: " + e.getMessage());
      }
    }
  }

  public static TranscriptCdsCache getInstance() {
    return INSTANCE;
  }

  public synchronized void clearMemory() {
    memoryCache.clear();
  }

  public long getHitCount() {
    return HIT_COUNT.get();
  }

  public long getMissCount() {
    return MISS_COUNT.get();
  }

  public long getBuildCount() {
    return BUILD_COUNT.get();
  }

  /**
   * Gets the coding sequence for a transcript, cached.
   * Uses three-level lookup: memory → disk → build+cache.
   */
  public synchronized String getCodingSequence(Gene gene, Transcript transcript, ReferenceGenomeService refService) {
    if (gene == null || transcript == null || refService == null || !refService.hasGenome() || !transcript.hasCDS()) {
      return null;
    }

    String key = buildKey(gene, transcript, refService);

    String cached = memoryCache.get(key);
    if (cached != null) {
      HIT_COUNT.incrementAndGet();
      return cached;
    }

    if (cacheDir != null) {
      Path cacheFile = cacheDir.resolve(hashKey(key) + ".txt");
      if (Files.exists(cacheFile)) {
        try {
          String seq = Files.readString(cacheFile, StandardCharsets.UTF_8);
          if (!seq.isEmpty()) {
            HIT_COUNT.incrementAndGet();
            memoryCache.put(key, seq);
            return seq;
          }
        } catch (IOException e) {
          System.err.println("Failed to read CDS cache: " + e.getMessage());
        }
      }
    }

    String built = buildCodingSequence(gene, transcript, refService);
    if (built == null || built.isEmpty()) {
      MISS_COUNT.incrementAndGet();
      return null;
    }

    MISS_COUNT.incrementAndGet();
    BUILD_COUNT.incrementAndGet();

    memoryCache.put(key, built);
    if (cacheDir != null) {
      Path cacheFile = cacheDir.resolve(hashKey(key) + ".txt");
      try {
        Files.writeString(cacheFile, built, StandardCharsets.UTF_8);
      } catch (IOException e) {
        System.err.println("Failed to save CDS cache: " + e.getMessage());
      }
    }
    return built;
  }

  /**
   * Extracts bases for a genomic region from the cached CDS.
   * Useful for gene-level visualization (amino acid rendering) to avoid re-fetching FASTA.
   *
   * @param gene              the gene
   * @param transcript        the transcript (should have CDS)
   * @param regionStart       genomic start of the region (1-based inclusive)
   * @param regionEnd         genomic end of the region (1-based inclusive)
   * @param refService        reference service
   * @return bases in transcript orientation (reverse-complemented if on minus strand), or null if not cached
   */
  public synchronized String getBasesForGenomicRegion(Gene gene, Transcript transcript,
                                                       long regionStart, long regionEnd,
                                                       ReferenceGenomeService refService) {
    if (gene == null || transcript == null || regionStart > regionEnd) {
      return null;
    }

    String cds = getCodingSequence(gene, transcript, refService);
    if (cds == null || cds.isEmpty()) {
      return null;
    }

    // Map genomic region to CDS positions
    boolean isReverse = "-".equals(gene.strand());
    long cdsStart = transcript.cdsStart();
    long cdsEnd = transcript.cdsEnd();
    List<long[]> exons = transcript.exons();
    if (exons == null || exons.isEmpty()) {
      return null;
    }

    // Calculate which part of the CDS corresponds to this genomic region
    StringBuilder result = new StringBuilder();
    long cdsPosition = 0;

    List<long[]> orderedExons = exons.stream()
        .map(exon -> new long[]{exon[0], exon[1]})
        .sorted((a, b) -> Long.compare(a[0], b[0]))
        .toList();

    if (isReverse) {
      // For reverse strand, iterate exons backwards
      for (int i = orderedExons.size() - 1; i >= 0; i--) {
        long[] exon = orderedExons.get(i);
        long exonCdsStart = Math.max(exon[0], cdsStart);
        long exonCdsEnd = Math.min(exon[1], cdsEnd);

        if (exonCdsStart > exonCdsEnd) {
          continue;
        }

        long exonCdsLen = exonCdsEnd - exonCdsStart + 1;
        long exonCdsEnd_query = exonCdsEnd;

        // Check if region overlaps this exon's CDS segment
        if (regionEnd >= exonCdsStart && regionStart <= exonCdsEnd_query) {
          long overlapStart = Math.max(regionStart, exonCdsStart);
          long overlapEnd = Math.min(regionEnd, exonCdsEnd_query);
          // On reverse, map to CDS indices (reverse orientation)
          long cdsIdxStart = cdsPosition + (exonCdsEnd_query - overlapEnd);
          long cdsIdxEnd = cdsPosition + (exonCdsEnd_query - overlapStart) + 1;
          if (cdsIdxEnd <= cds.length()) {
            result.insert(0, cds.substring((int) cdsIdxStart, (int) cdsIdxEnd));
          }
        }
        cdsPosition += exonCdsLen;
      }
    } else {
      // For forward strand, iterate exons normally
      for (long[] exon : orderedExons) {
        long exonCdsStart = Math.max(exon[0], cdsStart);
        long exonCdsEnd = Math.min(exon[1], cdsEnd);

        if (exonCdsStart > exonCdsEnd) {
          continue;
        }

        long exonCdsLen = exonCdsEnd - exonCdsStart + 1;

        // Check if region overlaps this exon's CDS segment
        if (regionEnd >= exonCdsStart && regionStart <= exonCdsEnd) {
          long overlapStart = Math.max(regionStart, exonCdsStart);
          long overlapEnd = Math.min(regionEnd, exonCdsEnd);
          long cdsIdxStart = cdsPosition + (overlapStart - exonCdsStart);
          long cdsIdxEnd = cdsPosition + (overlapEnd - exonCdsStart) + 1;
          if (cdsIdxEnd <= cds.length()) {
            result.append(cds.substring((int) cdsIdxStart, (int) cdsIdxEnd));
          }
        }
        cdsPosition += exonCdsLen;
      }
    }

    return result.length() > 0 ? result.toString() : null;
  }

  private String buildCodingSequence(Gene gene, Transcript transcript, ReferenceGenomeService refService) {
    List<long[]> exons = transcript.exons();
    if (exons == null || exons.isEmpty()) {
      return null;
    }

    List<long[]> orderedExons = exons.stream()
        .map(exon -> new long[]{exon[0], exon[1]})
        .sorted((a, b) -> Long.compare(a[0], b[0]))
        .toList();

    boolean isReverse = "-".equals(gene.strand());
    long cdsStart = transcript.cdsStart();
    long cdsEnd = transcript.cdsEnd();
    StringBuilder cds = new StringBuilder();

    if (isReverse) {
      for (int i = orderedExons.size() - 1; i >= 0; i--) {
        appendCodingSegment(cds, gene.chrom(), orderedExons.get(i), cdsStart, cdsEnd, refService, true);
      }
    } else {
      for (long[] exon : orderedExons) {
        appendCodingSegment(cds, gene.chrom(), exon, cdsStart, cdsEnd, refService, false);
      }
    }

    return cds.length() > 0 ? cds.toString() : null;
  }

  private void appendCodingSegment(StringBuilder out, String chrom, long[] exon,
                                   long cdsStart, long cdsEnd, ReferenceGenomeService refService,
                                   boolean reverseComplementSegment) {
    long regionStart = Math.max(exon[0], cdsStart);
    long regionEnd = Math.min(exon[1], cdsEnd);
    if (regionStart > regionEnd) {
      return;
    }

    String bases = refService.getBases(chrom, (int) regionStart, (int) regionEnd);
    if (bases == null || bases.isEmpty()) {
      return;
    }

    if (reverseComplementSegment) {
      bases = BaseUtils.reverseComplement(bases);
    }
    out.append(bases);
  }

  private String buildKey(Gene gene, Transcript transcript, ReferenceGenomeService refService) {
    String genomeName = refService.getCurrentGenome() != null ? refService.getCurrentGenome().getName() : "unknown";
    StringBuilder key = new StringBuilder();
    key.append(CACHE_VERSION).append('|')
        .append(genomeName).append('|')
        .append(gene.chrom()).append('|')
        .append(gene.strand()).append('|')
        .append(transcript.id()).append('|')
        .append(transcript.start()).append('|')
        .append(transcript.end()).append('|')
        .append(transcript.cdsStart()).append('|')
        .append(transcript.cdsEnd()).append('|');

    List<long[]> exons = transcript.exons();
    if (exons != null) {
      for (long[] exon : exons) {
        key.append(exon[0]).append('-').append(exon[1]).append(';');
      }
    }
    return key.toString();
  }

  private String hashKey(String key) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}