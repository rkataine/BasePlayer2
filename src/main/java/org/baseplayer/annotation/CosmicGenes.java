package org.baseplayer.annotation;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * COSMIC Cancer Gene Census data loader and accessor.
 * Loads from TSV file and caches for fast subsequent loads.
 */
public final class CosmicGenes {
  
  private static final int CACHE_VERSION = 2;
  private static final Path COSMIC_DIR = Path.of("additions/COSMIC_CANCER_CENSUS");
  private static final Path CACHE_FILE = COSMIC_DIR.resolve("cosmic_census.cache");
  
  private static Map<String, CosmicCensusEntry> censusData = null;
  private static boolean loaded = false;
  
  private CosmicGenes() {} // Utility class
  
  /**
   * Load COSMIC census data (from cache or TSV file).
   */
  public static void load() {
    if (loaded) return;
    
    // Try cache first
    if (Files.exists(CACHE_FILE)) {
      if (loadFromCache()) {
        loaded = true;
        return;
      }
    }
    
    // Find and load TSV file
    Path tsvFile = findTsvFile();
    if (tsvFile != null) {
      loadFromTsv(tsvFile);
      saveToCache();
    } else {
      censusData = new HashMap<>();
    }
    loaded = true;
  }
  
  /**
   * Find the Census TSV file in the COSMIC directory.
   */
  private static Path findTsvFile() {
    if (!Files.exists(COSMIC_DIR)) return null;
    
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(COSMIC_DIR, "*.tsv")) {
      for (Path path : stream) {
        return path; // Return first TSV file found
      }
    } catch (IOException e) {
      System.err.println("Error scanning COSMIC directory: " + e.getMessage());
    }
    return null;
  }
  
  /**
   * Load census data from TSV file.
   */
  private static void loadFromTsv(Path tsvFile) {
    censusData = new HashMap<>();
    
    try (BufferedReader reader = Files.newBufferedReader(tsvFile)) {
      String header = reader.readLine(); // Skip header
      if (header == null) return;
      
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split("\t", -1); // -1 to keep empty trailing fields
        if (parts.length < 20) continue;
        
        CosmicCensusEntry entry = new CosmicCensusEntry(
            stripQuotes(parts[0].trim()),                           // geneSymbol
            stripQuotes(parts[1].trim()),                           // name
            stripQuotes(parts[2].trim()),                           // entrezGeneId
            stripQuotes(parts[3].trim()),                           // genomeLocation
            stripQuotes(parts[4].trim()),                           // tier
            "Yes".equalsIgnoreCase(parts[5].trim()),                // hallmark
            stripQuotes(parts[6].trim()),                           // chrBand
            "yes".equalsIgnoreCase(parts[7].trim()),                // somatic
            "yes".equalsIgnoreCase(parts[8].trim()),                // germline
            stripQuotes(parts[9].trim()),                           // tumourTypesSomatic
            stripQuotes(parts[10].trim()),                          // tumourTypesGermline
            stripQuotes(parts[11].trim()),                          // cancerSyndrome
            stripQuotes(parts[12].trim()),                          // tissueType
            stripQuotes(parts[13].trim()),                          // molecularGenetics
            stripQuotes(parts[14].trim()),                          // roleInCancer
            stripQuotes(parts[15].trim()),                          // mutationTypes
            stripQuotes(parts[16].trim()),                          // translocationPartner
            stripQuotes(parts[17].trim()),                          // otherGermlineMut
            stripQuotes(parts[18].trim()),                          // otherSyndrome
            parts.length > 19 ? stripQuotes(parts[19].trim()) : ""  // synonyms
        );
        
        censusData.put(entry.geneSymbol(), entry);
      }
      
      System.out.println("Loaded " + censusData.size() + " COSMIC census genes from TSV");
      
    } catch (IOException e) {
      System.err.println("Failed to load COSMIC census TSV: " + e.getMessage());
    }
  }
  
  /**
   * Remove surrounding quotes from a string.
   */
  private static String stripQuotes(String str) {
    if (str == null || str.isEmpty()) return str;
    if ((str.startsWith("\"") && str.endsWith("\"")) || 
        (str.startsWith("'") && str.endsWith("'"))) {
      return str.substring(1, str.length() - 1);
    }
    return str;
  }
  
  /**
   * Save census data to binary cache.
   */
  private static void saveToCache() {
    try {
      Files.createDirectories(CACHE_FILE.getParent());
      
      try (DataOutputStream out = new DataOutputStream(
             new GZIPOutputStream(Files.newOutputStream(CACHE_FILE)))) {
        out.writeInt(0x434F534D); // "COSM" magic
        out.writeInt(CACHE_VERSION);
        out.writeInt(censusData.size());
        
        for (CosmicCensusEntry entry : censusData.values()) {
          out.writeUTF(entry.geneSymbol());
          out.writeUTF(entry.name());
          out.writeUTF(entry.entrezGeneId());
          out.writeUTF(entry.genomeLocation());
          out.writeUTF(entry.tier());
          out.writeBoolean(entry.hallmark());
          out.writeUTF(entry.chrBand());
          out.writeBoolean(entry.somatic());
          out.writeBoolean(entry.germline());
          out.writeUTF(entry.tumourTypesSomatic());
          out.writeUTF(entry.tumourTypesGermline());
          out.writeUTF(entry.cancerSyndrome());
          out.writeUTF(entry.tissueType());
          out.writeUTF(entry.molecularGenetics());
          out.writeUTF(entry.roleInCancer());
          out.writeUTF(entry.mutationTypes());
          out.writeUTF(entry.translocationPartner());
          out.writeUTF(entry.otherGermlineMut());
          out.writeUTF(entry.otherSyndrome());
          out.writeUTF(entry.synonyms());
        }
      }
    } catch (IOException e) {
      System.err.println("Failed to save COSMIC cache: " + e.getMessage());
    }
  }
  
  /**
   * Load census data from binary cache.
   */
  private static boolean loadFromCache() {
    try (DataInputStream in = new DataInputStream(
           new GZIPInputStream(Files.newInputStream(CACHE_FILE)))) {
      int magic = in.readInt();
      if (magic != 0x434F534D) return false;
      
      int version = in.readInt();
      if (version != CACHE_VERSION) return false;
      
      int count = in.readInt();
      censusData = new HashMap<>(count);
      
      for (int i = 0; i < count; i++) {
        CosmicCensusEntry entry = new CosmicCensusEntry(
            in.readUTF(),    // geneSymbol
            in.readUTF(),    // name
            in.readUTF(),    // entrezGeneId
            in.readUTF(),    // genomeLocation
            in.readUTF(),    // tier
            in.readBoolean(), // hallmark
            in.readUTF(),    // chrBand
            in.readBoolean(), // somatic
            in.readBoolean(), // germline
            in.readUTF(),    // tumourTypesSomatic
            in.readUTF(),    // tumourTypesGermline
            in.readUTF(),    // cancerSyndrome
            in.readUTF(),    // tissueType
            in.readUTF(),    // molecularGenetics
            in.readUTF(),    // roleInCancer
            in.readUTF(),    // mutationTypes
            in.readUTF(),    // translocationPartner
            in.readUTF(),    // otherGermlineMut
            in.readUTF(),    // otherSyndrome
            in.readUTF()     // synonyms
        );
        censusData.put(entry.geneSymbol(), entry);
      }
      
      System.out.println("Loaded " + censusData.size() + " COSMIC census genes from cache");
      return true;
      
    } catch (IOException e) {
      return false;
    }
  }
  
  /**
   * Check if a gene is in the COSMIC Cancer Gene Census.
   */
  public static boolean isCosmicGene(String geneName) {
    if (!loaded) load();
    return geneName != null && censusData.containsKey(geneName);
  }
  
  /**
   * Get the COSMIC census entry for a gene.
   */
  public static CosmicCensusEntry getEntry(String geneName) {
    if (!loaded) load();
    return geneName != null ? censusData.get(geneName) : null;
  }
  
  /**
   * Get all COSMIC gene symbols.
   */
  public static Set<String> getAllGeneSymbols() {
    if (!loaded) load();
    return censusData.keySet();
  }
  
  /**
   * Check if data is loaded.
   */
  public static boolean isLoaded() {
    return loaded;
  }
}
