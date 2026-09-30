package org.baseplayer.annotation;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.genome.Cytoband;
import org.baseplayer.io.Settings;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.genome.gene.Transcript;

/**
 * Loads annotation data (cytobands, genes) from files.
 */
public final class AnnotationLoader {
  
  // v8: invalidate caches that may contain duplicated genes from concurrent loads
  private static final int CACHE_VERSION = 8;
  private static final int TRANSCRIPT_CACHE_VERSION = 2;  // Version for non-MANE transcript cache (added CDS bounds)
  private static final Path FALLBACK_GENE_ANNOTATION =
      Path.of("genomes/GRCh38/annotation/Homo_sapiens.GRCh38.115.chr.gff3.gz");
  private static final Object GENE_LOAD_LOCK = new Object();
  private static final AtomicInteger geneLoadGeneration = new AtomicInteger();
  
  private AnnotationLoader() {} // Utility class
  
  /**
   * Load cytobands from bands.txt for the current genome (if present).
   */
  public static void loadCytobands() {
    AnnotationData.getCytobands().clear();
    AnnotationData.setCytobandsLoaded(false);

    Path bandsFile = resolveBandsFile();
    if (bandsFile == null || !Files.exists(bandsFile)) return;
    
    try {
      List<String> lines = Files.readAllLines(bandsFile);
      for (String line : lines) {
        String[] parts = line.split("\t");
        if (parts.length >= 5) {
          AnnotationData.getCytobands().add(new Cytoband(
            ChromosomeNames.strip(parts[0]),
            Long.parseLong(parts[1]),
            Long.parseLong(parts[2]),
            parts[3],
            parts[4]
          ));
        }
      }
      AnnotationData.setCytobandsLoaded(true);
    } catch (IOException e) {
      System.err.println("Failed to load cytobands: " + e.getMessage());
    }
  }

  private static Path resolveBandsFile() {
    String genome = Settings.get().getLastGenome();
    if (genome != null && !genome.isBlank()) {
      Path candidate = Path.of("genomes", genome, "bands.txt");
      // Never fall back to another assembly's bands — wrong ideogram.
      return Files.exists(candidate) ? candidate : null;
    }
    Path fallback = Path.of("genomes/GRCh38/bands.txt");
    return Files.exists(fallback) ? fallback : null;
  }
  
  /**
   * Load genes in background thread for the currently selected genome/annotation.
   * Concurrent calls are serialized; a newer request supersedes an older one.
   */
  public static void loadGenesBackground() {
    final int generation = geneLoadGeneration.incrementAndGet();
    AnnotationData.setGenesLoading(true);
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
    
    Thread loadThread = new Thread(() -> {
      synchronized (GENE_LOAD_LOCK) {
        if (generation != geneLoadGeneration.get()) {
          return; // superseded by a newer load request
        }
        loadGenes();
      }

      if (generation != geneLoadGeneration.get()) {
        return;
      }
      
      javafx.application.Platform.runLater(() -> {
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      });
      
      // Then load additional data in background
      CosmicGenes.load();
      loadNonManeTranscripts();
    });
    loadThread.setDaemon(true);
    loadThread.setName("gene-annotation-load");
    loadThread.start();
  }
  
  /**
   * Load genes from the selected annotation file (or GRCh38 fallback) / cache.
   * Must be called under {@link #GENE_LOAD_LOCK} (or from a single thread).
   * Builds into local maps and publishes atomically to avoid CME with the UI.
   */
  public static void loadGenes() {
    AnnotationData.setGenesLoaded(false);
    AnnotationData.setGenesLoading(true);

    Path gff3File = resolveGeneAnnotationFile();
    if (gff3File == null || !Files.exists(gff3File)) {
      System.err.println("Gene annotation file not found"
          + (gff3File != null ? ": " + gff3File : " (no genome/annotation selected)"));
      AnnotationData.clearGenes();
      AnnotationData.setGenesLoading(false);
      return;
    }

    Path cacheFile = Path.of(gff3File.toString() + ".cache");
    Path txCacheFile = Path.of(gff3File.toString() + ".transcripts.cache");
    
    // Try to load from cache if it's newer than the GFF3 file
    if (Files.exists(cacheFile)) {
      try {
        if (!Files.exists(gff3File) || 
            Files.getLastModifiedTime(cacheFile).compareTo(Files.getLastModifiedTime(gff3File)) >= 0) {
          if (loadGenesFromCache(cacheFile)) {
            AnnotationData.setGenesLoading(false);
            return;
          }
        }
      } catch (IOException e) {
        System.err.println("Cache check failed: " + e.getMessage());
      }
    }
    
    // Temporary structures for parsing
    Map<String, GeneBuilder> geneBuilders = new HashMap<>();
    Map<String, TranscriptBuilder> transcriptBuilders = new HashMap<>();
    Map<String, List<long[]>> exonsByTranscript = new HashMap<>();
    Map<String, long[]> cdsBoundsByTranscript = new HashMap<>();  // CDS start/end per transcript
    
    try (BufferedReader reader = openAnnotationReader(gff3File)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("#")) continue;
        
        String[] parts = line.split("\t");
        if (parts.length < 9) continue;
        
        String chrom = ChromosomeNames.strip(parts[0]);
        String type = parts[2];
        long start = Long.parseLong(parts[3]);
        long end = Long.parseLong(parts[4]);
        String strand = parts[6];
        String attributes = parts[8];
        
        if (type.equals("gene") || type.endsWith("_gene") || type.equals("pseudogene")) {
          parseGene(chrom, start, end, strand, attributes, geneBuilders);
        } else if (type.equals("mRNA") || type.contains("transcript") || type.endsWith("RNA")) {
          parseTranscript(start, end, attributes, transcriptBuilders);
        } else if (type.equals("exon")) {
          parseExon(start, end, attributes, exonsByTranscript);
        } else if (type.equals("CDS")) {
          parseCDS(start, end, attributes, cdsBoundsByTranscript);
        }
      }
      
      GeneLoadSnapshot snapshot = buildGenes(
          geneBuilders, transcriptBuilders, exonsByTranscript, cdsBoundsByTranscript);
      AnnotationData.replaceGeneAnnotation(
          snapshot.byChrom, snapshot.searchMap, snapshot.biotypeMap, snapshot.names);
      
      // Save to cache for faster subsequent loads
      saveGenesToCache(cacheFile);
      
      // Save non-MANE transcripts to separate cache
      saveNonManeTranscriptsToCache(txCacheFile, transcriptBuilders, exonsByTranscript, cdsBoundsByTranscript);
      
    } catch (IOException e) {
      System.err.println("Failed to load genes from " + gff3File + ": " + e.getMessage());
      AnnotationData.clearGenes();
    } finally {
      AnnotationData.setGenesLoading(false);
    }
  }

  /**
   * Resolve {@code genomes/<genome>/annotation/<file>} from Settings.
   * Falls back to the bundled GRCh38 annotation when nothing is selected.
   */
  static Path resolveGeneAnnotationFile() {
    String genome = Settings.get().getLastGenome();
    String annotation = Settings.get().getLastAnnotation();
    if (genome != null && !genome.isBlank()
        && annotation != null && !annotation.isBlank()) {
      Path selected = Path.of("genomes", genome, "annotation", annotation);
      if (Files.exists(selected)) {
        return selected;
      }
      System.err.println("Selected annotation missing: " + selected);
    }
    return Files.exists(FALLBACK_GENE_ANNOTATION) ? FALLBACK_GENE_ANNOTATION : null;
  }

  private static BufferedReader openAnnotationReader(Path annotationFile) throws IOException {
    String name = annotationFile.getFileName().toString().toLowerCase();
    if (name.endsWith(".gz")) {
      return new BufferedReader(new InputStreamReader(
          new GZIPInputStream(Files.newInputStream(annotationFile)), StandardCharsets.UTF_8));
    }
    return Files.newBufferedReader(annotationFile, StandardCharsets.UTF_8);
  }
  
  private static void parseGene(String chrom, long start, long end, String strand, 
                                 String attributes, Map<String, GeneBuilder> geneBuilders) {
    String geneId = extractAttribute(attributes, "ID");
    String name = extractAttribute(attributes, "Name");
    String biotype = extractAttribute(attributes, "biotype");
    String description = extractAttribute(attributes, "description");
    
    // URL decode description (GFF3 uses percent encoding)
    if (description != null) {
      description = URLDecoder.decode(description, StandardCharsets.UTF_8);
    }
    
    if (geneId != null && name != null) {
      GeneBuilder builder = new GeneBuilder();
      builder.chrom = chrom;
      builder.start = start;
      builder.end = end;
      builder.name = name;
      builder.id = geneId;
      builder.strand = strand;
      builder.biotype = biotype;
      builder.description = description;
      geneBuilders.put(geneId, builder);
    }
  }
  
  private static void parseTranscript(long start, long end, String attributes,
                                       Map<String, TranscriptBuilder> transcriptBuilders) {
    String transcriptId = extractAttribute(attributes, "ID");
    String parentId = extractAttribute(attributes, "Parent");
    String name = extractAttribute(attributes, "Name");
    String biotype = extractAttribute(attributes, "biotype");
    String tag = extractAttribute(attributes, "tag");
    
    if (transcriptId != null && parentId != null) {
      TranscriptBuilder builder = new TranscriptBuilder();
      builder.id = transcriptId;
      builder.parentGeneId = parentId;
      builder.name = name;
      builder.start = start;
      builder.end = end;
      builder.biotype = biotype;
      builder.isManeSelect = tag != null && tag.contains("MANE_Select");
      builder.isManeClinic = tag != null && tag.contains("MANE_Plus_Clinical");
      transcriptBuilders.put(transcriptId, builder);
    }
  }
  
  private static void parseExon(long start, long end, String attributes,
                                 Map<String, List<long[]>> exonsByTranscript) {
    String parentId = extractAttribute(attributes, "Parent");
    if (parentId != null) {
      exonsByTranscript.computeIfAbsent(parentId, k -> new ArrayList<>())
                       .add(new long[]{start, end});
    }
  }
  
  private static void parseCDS(long start, long end, String attributes,
                                Map<String, long[]> cdsBoundsByTranscript) {
    String parentId = extractAttribute(attributes, "Parent");
    if (parentId != null) {
      // Update CDS bounds for this transcript - track min start and max end
      long[] bounds = cdsBoundsByTranscript.get(parentId);
      if (bounds == null) {
        cdsBoundsByTranscript.put(parentId, new long[]{start, end});
      } else {
        bounds[0] = Math.min(bounds[0], start);
        bounds[1] = Math.max(bounds[1], end);
      }
    }
  }
  
  private static GeneLoadSnapshot buildGenes(Map<String, GeneBuilder> geneBuilders,
                                  Map<String, TranscriptBuilder> transcriptBuilders,
                                  Map<String, List<long[]>> exonsByTranscript,
                                  Map<String, long[]> cdsBoundsByTranscript) {
    
    // Track longest non-MANE transcript per gene (for noncoding fallback display)
    Map<String, Transcript> longestNonMane = new HashMap<>();
    
    // Assign transcripts to genes
    for (TranscriptBuilder tb : transcriptBuilders.values()) {
      GeneBuilder gb = geneBuilders.get(tb.parentGeneId);
      if (gb != null) {
        // Build transcript with its exons and CDS bounds
        List<long[]> txExons = exonsByTranscript.getOrDefault(tb.id, new ArrayList<>());
        long[] cdsBounds = cdsBoundsByTranscript.get(tb.id);
        long cdsStart = cdsBounds != null ? cdsBounds[0] : 0;
        long cdsEnd = cdsBounds != null ? cdsBounds[1] : 0;
        
        Transcript transcript = new Transcript(
            tb.id, tb.name, tb.start, tb.end, tb.biotype,
            tb.isManeSelect, tb.isManeClinic, txExons, cdsStart, cdsEnd
        );
        
        // Only add MANE transcripts to the gene object (others saved separately)
        if (transcript.isMane()) {
          gb.transcripts.add(transcript);
        } else {
          // Track longest non-MANE transcript per gene for noncoding display
          Transcript current = longestNonMane.get(tb.parentGeneId);
          if (current == null || (transcript.end() - transcript.start()) > (current.end() - current.start())) {
            longestNonMane.put(tb.parentGeneId, transcript);
          }
        }
        
        // Collect exons into gene's combined exon list (from all transcripts)
        for (long[] exon : txExons) {
          gb.exons.add(exon);
        }
      }
    }
    
    // For genes without MANE transcripts (e.g., lncRNA, miRNA), add the longest
    // transcript as a representative so getDisplayExons() uses its exons instead
    // of the merged exon list from all transcripts
    for (Map.Entry<String, GeneBuilder> entry : geneBuilders.entrySet()) {
      GeneBuilder gb = entry.getValue();
      if (gb.transcripts.isEmpty()) {
        Transcript longest = longestNonMane.get(entry.getKey());
        if (longest != null) {
          // Ensure exons are sorted by position
          longest.exons().sort((a, b) -> Long.compare(a[0], b[0]));
          gb.transcripts.add(longest);
        }
      }
    }

    Map<String, List<Gene>> byChrom = new HashMap<>();
    Map<String, GeneLocation> searchMap = new HashMap<>();
    Map<String, String> biotypeMap = new HashMap<>();
    List<String> names = new ArrayList<>(geneBuilders.size());
    
    // Create final Gene objects into local maps (not published yet)
    for (GeneBuilder gb : geneBuilders.values()) {
      // Properly merge overlapping exons from all transcripts
      List<long[]> mergedExons = mergeOverlappingExons(gb.exons);
      
      Gene gene = new Gene(
          gb.chrom, gb.start, gb.end, gb.name, gb.id, gb.strand, gb.biotype,
          gb.description, gb.transcripts, mergedExons
      );
      
      byChrom.computeIfAbsent(gb.chrom, k -> new ArrayList<>()).add(gene);
      searchMap.put(gb.name.toLowerCase(), new GeneLocation(gb.chrom, gb.start, gb.end));
      if (gb.biotype != null) {
        biotypeMap.put(gb.name.toLowerCase(), gb.biotype);
      }
      names.add(gb.name);
    }
    
    sortGenesByStart(byChrom);
    names.sort(String.CASE_INSENSITIVE_ORDER);
    return new GeneLoadSnapshot(byChrom, searchMap, biotypeMap, names);
  }

  private static void sortGenesByStart(Map<String, List<Gene>> byChrom) {
    for (List<Gene> genes : byChrom.values()) {
      genes.sort((a, b) -> {
        int cmp = Long.compare(a.start(), b.start());
        if (cmp != 0) return cmp;
        cmp = Long.compare(a.end(), b.end());
        if (cmp != 0) return cmp;
        return a.name().compareToIgnoreCase(b.name());
      });
    }
  }

  private record GeneLoadSnapshot(
      Map<String, List<Gene>> byChrom,
      Map<String, GeneLocation> searchMap,
      Map<String, String> biotypeMap,
      List<String> names) {}
  
  /**
   * Merge overlapping exon intervals into non-overlapping sorted intervals.
   * Adjacent exons (gap <= 1bp) are also merged.
   */
  private static List<long[]> mergeOverlappingExons(List<long[]> exons) {
    if (exons.isEmpty()) return new ArrayList<>();
    if (exons.size() == 1) return new ArrayList<>(List.of(new long[]{exons.get(0)[0], exons.get(0)[1]}));
    
    List<long[]> sorted = new ArrayList<>(exons);
    sorted.sort((a, b) -> Long.compare(a[0], b[0]));
    
    List<long[]> merged = new ArrayList<>();
    long[] current = new long[]{sorted.get(0)[0], sorted.get(0)[1]};
    for (int i = 1; i < sorted.size(); i++) {
      if (sorted.get(i)[0] <= current[1] + 1) {
        current[1] = Math.max(current[1], sorted.get(i)[1]);
      } else {
        merged.add(current);
        current = new long[]{sorted.get(i)[0], sorted.get(i)[1]};
      }
    }
    merged.add(current);
    return merged;
  }
  
  // Builder classes for mutable construction
  private static class GeneBuilder {
    String chrom, name, id, strand, biotype, description;
    long start, end;
    List<Transcript> transcripts = new ArrayList<>();
    List<long[]> exons = new ArrayList<>();
  }
  
  private static class TranscriptBuilder {
    String id, parentGeneId, name, biotype;
    long start, end;
    boolean isManeSelect, isManeClinic;
  }
  
  private static void saveGenesToCache(Path cacheFile) {
    try (DataOutputStream out = new DataOutputStream(
           new GZIPOutputStream(Files.newOutputStream(cacheFile)))) {
      // Write magic bytes and version
      out.writeInt(0x47454E45); // "GENE" magic
      out.writeInt(CACHE_VERSION);
      
      // Collect all genes
      List<Gene> allGenes = new ArrayList<>();
      for (List<Gene> genes : AnnotationData.getGenesByChrom().values()) {
        allGenes.addAll(genes);
      }
      out.writeInt(allGenes.size());
      
      for (Gene gene : allGenes) {
        out.writeUTF(gene.chrom());
        out.writeLong(gene.start());
        out.writeLong(gene.end());
        out.writeUTF(gene.name());
        out.writeUTF(gene.id());
        out.writeUTF(gene.strand());
        out.writeUTF(gene.biotype() != null ? gene.biotype() : "");
        out.writeUTF(gene.description() != null ? gene.description() : "");
        
        // Write MANE transcripts only
        List<Transcript> transcripts = gene.transcripts() != null ? gene.transcripts() : List.of();
        out.writeInt(transcripts.size());
        for (Transcript tx : transcripts) {
          out.writeUTF(tx.id());
          out.writeUTF(tx.name() != null ? tx.name() : "");
          out.writeLong(tx.start());
          out.writeLong(tx.end());
          out.writeUTF(tx.biotype() != null ? tx.biotype() : "");
          out.writeBoolean(tx.isManeSelect());
          out.writeBoolean(tx.isManeClinic());
          out.writeLong(tx.cdsStart());
          out.writeLong(tx.cdsEnd());
          
          // Write transcript exons
          out.writeInt(tx.exons().size());
          for (long[] exon : tx.exons()) {
            out.writeLong(exon[0]);
            out.writeLong(exon[1]);
          }
        }
        
        // Write merged exons
        out.writeInt(gene.exons().size());
        for (long[] exon : gene.exons()) {
          out.writeLong(exon[0]);
          out.writeLong(exon[1]);
        }
      }
    } catch (IOException e) {
      System.err.println("Failed to save gene cache: " + e.getMessage());
    }
  }
  
  private static boolean loadGenesFromCache(Path cacheFile) {
    try (DataInputStream in = new DataInputStream(
           new GZIPInputStream(Files.newInputStream(cacheFile)))) {
      // Verify magic and version
      int magic = in.readInt();
      if (magic != 0x47454E45) {
        System.err.println("Invalid cache magic");
        return false;
      }
      int version = in.readInt();
      if (version != CACHE_VERSION) {
        System.err.println("Outdated cache version: " + version + ", need " + CACHE_VERSION);
        return false;
      }
      
      int geneCount = in.readInt();
      Map<String, List<Gene>> byChrom = new HashMap<>();
      Map<String, GeneLocation> searchMap = new HashMap<>();
      Map<String, String> biotypeMap = new HashMap<>();
      List<String> names = new ArrayList<>(geneCount);
      
      for (int i = 0; i < geneCount; i++) {
        String chrom = ChromosomeNames.strip(in.readUTF());
        long start = in.readLong();
        long end = in.readLong();
        String name = in.readUTF();
        String id = in.readUTF();
        String strand = in.readUTF();
        String biotype = in.readUTF();
        if (biotype.isEmpty()) biotype = null;
        String description = in.readUTF();
        if (description.isEmpty()) description = null;
        
        // Read MANE transcripts
        int txCount = in.readInt();
        List<Transcript> transcripts = new ArrayList<>(txCount);
        for (int t = 0; t < txCount; t++) {
          String txId = in.readUTF();
          String txName = in.readUTF();
          if (txName.isEmpty()) txName = null;
          long txStart = in.readLong();
          long txEnd = in.readLong();
          String txBiotype = in.readUTF();
          if (txBiotype.isEmpty()) txBiotype = null;
          boolean isManeSelect = in.readBoolean();
          boolean isManeClinic = in.readBoolean();
          long cdsStart = in.readLong();
          long cdsEnd = in.readLong();
          
          // Read transcript exons
          int txExonCount = in.readInt();
          List<long[]> txExons = new ArrayList<>(txExonCount);
          for (int e = 0; e < txExonCount; e++) {
            txExons.add(new long[]{in.readLong(), in.readLong()});
          }
          
          transcripts.add(new Transcript(txId, txName, txStart, txEnd, txBiotype, 
                                          isManeSelect, isManeClinic, txExons, cdsStart, cdsEnd));
        }
        
        // Read merged exons
        int exonCount = in.readInt();
        List<long[]> exons = new ArrayList<>(exonCount);
        for (int j = 0; j < exonCount; j++) {
          exons.add(new long[]{in.readLong(), in.readLong()});
        }
        
        Gene gene = new Gene(chrom, start, end, name, id, strand, biotype, 
                             description, transcripts, exons);
        
        byChrom.computeIfAbsent(chrom, k -> new ArrayList<>()).add(gene);
        searchMap.put(name.toLowerCase(), new GeneLocation(chrom, start, end));
        if (biotype != null && !biotype.isEmpty()) {
          biotypeMap.put(name.toLowerCase(), biotype);
        }
        names.add(name);
      }
      
      sortGenesByStart(byChrom);
      names.sort(String.CASE_INSENSITIVE_ORDER);
      AnnotationData.replaceGeneAnnotation(byChrom, searchMap, biotypeMap, names);
      
      return true;
      
    } catch (IOException e) {
      System.err.println("Failed to load gene cache: " + e.getMessage());
      return false;
    }
  }
  
  /**
   * Save non-MANE transcripts to a separate cache file.
   */
  private static void saveNonManeTranscriptsToCache(Path txCacheFile, 
      Map<String, TranscriptBuilder> transcriptBuilders,
      Map<String, List<long[]>> exonsByTranscript,
      Map<String, long[]> cdsBoundsByTranscript) {
    
    try (DataOutputStream out = new DataOutputStream(
           new GZIPOutputStream(Files.newOutputStream(txCacheFile)))) {
      out.writeInt(0x54584E4D); // "TXNM" magic (transcripts non-mane)
      out.writeInt(TRANSCRIPT_CACHE_VERSION);
      
      // Build map of geneId -> non-MANE transcripts
      Map<String, List<Transcript>> nonManeByGene = new HashMap<>();
      for (TranscriptBuilder tb : transcriptBuilders.values()) {
        if (!tb.isManeSelect && !tb.isManeClinic) {
          List<long[]> txExons = exonsByTranscript.getOrDefault(tb.id, new ArrayList<>());
          long[] cdsBounds = cdsBoundsByTranscript.get(tb.id);
          long cdsStart = cdsBounds != null ? cdsBounds[0] : 0;
          long cdsEnd = cdsBounds != null ? cdsBounds[1] : 0;
          Transcript transcript = new Transcript(
              tb.id, tb.name, tb.start, tb.end, tb.biotype,
              false, false, txExons, cdsStart, cdsEnd
          );
          nonManeByGene.computeIfAbsent(tb.parentGeneId, k -> new ArrayList<>()).add(transcript);
        }
      }
      
      // Write count of genes with non-MANE transcripts
      out.writeInt(nonManeByGene.size());
      
      for (Map.Entry<String, List<Transcript>> entry : nonManeByGene.entrySet()) {
        out.writeUTF(entry.getKey()); // gene ID
        List<Transcript> transcripts = entry.getValue();
        out.writeInt(transcripts.size());
        
        for (Transcript tx : transcripts) {
          out.writeUTF(tx.id());
          out.writeUTF(tx.name() != null ? tx.name() : "");
          out.writeLong(tx.start());
          out.writeLong(tx.end());
          out.writeUTF(tx.biotype() != null ? tx.biotype() : "");
          out.writeLong(tx.cdsStart());
          out.writeLong(tx.cdsEnd());
          
          out.writeInt(tx.exons().size());
          for (long[] exon : tx.exons()) {
            out.writeLong(exon[0]);
            out.writeLong(exon[1]);
          }
        }
      }
    } catch (IOException e) {
      System.err.println("Failed to save non-MANE transcript cache: " + e.getMessage());
    }
  }
  
  /**
   * Load non-MANE transcripts on demand (when user wants to see all transcripts).
   */
  public static void loadNonManeTranscripts() {
    if (AnnotationData.isNonManeTranscriptsLoaded()) return;
    
    Path annotationFile = resolveGeneAnnotationFile();
    if (annotationFile == null) {
      return;
    }
    Path txCacheFile = Path.of(annotationFile.toString() + ".transcripts.cache");
    if (!Files.exists(txCacheFile)) return;
    
    try (DataInputStream in = new DataInputStream(
           new GZIPInputStream(Files.newInputStream(txCacheFile)))) {
      int magic = in.readInt();
      if (magic != 0x54584E4D) {
        System.err.println("Invalid transcript cache magic");
        return;
      }
      int version = in.readInt();
      if (version != TRANSCRIPT_CACHE_VERSION) {
        System.err.println("Outdated transcript cache version");
        return;
      }
      
      int geneCount = in.readInt();
      Map<String, List<Transcript>> nonManeTranscripts = new HashMap<>(geneCount);
      
      for (int i = 0; i < geneCount; i++) {
        String geneId = in.readUTF();
        int txCount = in.readInt();
        List<Transcript> transcripts = new ArrayList<>(txCount);
        
        for (int t = 0; t < txCount; t++) {
          String txId = in.readUTF();
          String txName = in.readUTF();
          if (txName.isEmpty()) txName = null;
          long txStart = in.readLong();
          long txEnd = in.readLong();
          String txBiotype = in.readUTF();
          if (txBiotype.isEmpty()) txBiotype = null;
          long cdsStart = in.readLong();
          long cdsEnd = in.readLong();
          
          int exonCount = in.readInt();
          List<long[]> exons = new ArrayList<>(exonCount);
          for (int e = 0; e < exonCount; e++) {
            exons.add(new long[]{in.readLong(), in.readLong()});
          }
          
          transcripts.add(new Transcript(txId, txName, txStart, txEnd, txBiotype, 
                                          false, false, exons, cdsStart, cdsEnd));
        }
        
        nonManeTranscripts.put(geneId, transcripts);
      }
      
      AnnotationData.setNonManeTranscripts(nonManeTranscripts);
      
    } catch (IOException e) {
      System.err.println("Failed to load non-MANE transcripts: " + e.getMessage());
    }
  }
  
  private static String extractAttribute(String attributes, String key) {
    for (String attr : attributes.split(";")) {
      if (attr.startsWith(key + "=")) {
        return attr.substring(key.length() + 1);
      }
    }
    return null;
  }
}
