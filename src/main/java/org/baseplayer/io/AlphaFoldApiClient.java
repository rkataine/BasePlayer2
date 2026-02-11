package org.baseplayer.io;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Client for AlphaFold and UniProt APIs.
 * Fetches protein structure predictions and AlphaMissense pathogenicity scores.
 * 
 * APIs:
 * - AlphaFold DB: https://alphafold.ebi.ac.uk/api/
 * - UniProt: https://rest.uniprot.org/
 * 
 * Features:
 * - Maps gene names to UniProt IDs
 * - Fetches AlphaFold structure metadata
 * - Fetches AlphaMissense pathogenicity predictions for amino acid positions
 */
public class AlphaFoldApiClient {
  
  private static final String ALPHAFOLD_API = "https://alphafold.ebi.ac.uk/api";
  private static final String UNIPROT_API = "https://rest.uniprot.org";
  private static final String ALPHAFOLD_FILES = "https://alphafold.ebi.ac.uk/files";
  private static final int TIMEOUT_SECONDS = 15;
  
  // Use HTTP/1.1 to avoid GOAWAY issues with HTTP/2
  private static final HttpClient httpClient = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(10))
      .build();
  
  // Cache gene name -> UniProt ID mapping
  private static final Map<String, String> geneToUniprotCache = new ConcurrentHashMap<>();
  
  // Cache UniProt ID -> AlphaFold metadata (null value = not found/cached as unavailable)
  private static final Map<String, AlphaFoldEntry> alphaFoldCache = new ConcurrentHashMap<>();
  
  // Track UniProt IDs that have no AlphaFold data (404)
  private static final Map<String, Boolean> alphaFoldNotFoundCache = new ConcurrentHashMap<>();
  
  
  // Cache for AlphaMissense full CSV data per protein (uniprotId -> all predictions)
  private static final Map<String, List<MissensePrediction>> alphaMissenseFullCache = new ConcurrentHashMap<>();
  
  // Track UniProt IDs that have no AlphaMissense data (404)
  private static final Map<String, Boolean> alphaMissenseNotFoundCache = new ConcurrentHashMap<>();
  
  // Cache for UniProt variants per protein (uniprotId -> all variants)
  private static final Map<String, List<MissensePrediction>> uniprotVariantCache = new ConcurrentHashMap<>();
  
  private AlphaFoldApiClient() {} // Utility class

  
  // Load AlphaFold entry from persistent cache using DataCacheManager
  private static AlphaFoldEntry loadAlphaFoldFromDisk(String uniprotId) {
    try {
      var opt = DataCacheManager.loadFromCache("alphafold", uniprotId);
      if (opt.isPresent()) {
        JsonObject obj = opt.get();
        String uid = getStringOrNull(obj, "uniprotId");
        if (uid != null && uid.equalsIgnoreCase(uniprotId)) {
          String desc = getStringOrNull(obj, "uniprotDescription");
          String gene = getStringOrNull(obj, "gene");
          int seqLen = obj.has("sequenceLength") ? obj.get("sequenceLength").getAsInt() : 0;
          String pdb = getStringOrNull(obj, "pdbUrl");
          String cif = getStringOrNull(obj, "cifUrl");
          String pae = getStringOrNull(obj, "paeImageUrl");
          String model = getStringOrNull(obj, "modelUrl");
          double gm = obj.has("globalMetricValue") ? obj.get("globalMetricValue").getAsDouble() : 0.0;
          return new AlphaFoldEntry(uid, desc, gene, seqLen, pdb, cif, pae, model, gm);
        }
      }

      // Backwards compatibility: check legacy cache directory ~/.baseplayer_cache/alphafold
      Path legacyDir = Paths.get(System.getProperty("user.home"), ".baseplayer_cache", "alphafold");
      if (Files.exists(legacyDir) && Files.isDirectory(legacyDir)) {
        try {
          for (Path file : Files.list(legacyDir).toList()) {
            if (!file.getFileName().toString().endsWith(".json")) continue;
            try {
              String s = Files.readString(file, StandardCharsets.UTF_8);
              JsonObject obj = JsonParser.parseString(s).getAsJsonObject();
              String uid = getStringOrNull(obj, "uniprotId");
              if (uid != null && uid.equalsIgnoreCase(uniprotId)) {
                // Migrate into DataCacheManager for future
                DataCacheManager.saveToCache("alphafold", uniprotId, obj);
                String desc = getStringOrNull(obj, "uniprotDescription");
                String gene = getStringOrNull(obj, "gene");
                int seqLen = obj.has("sequenceLength") ? obj.get("sequenceLength").getAsInt() : 0;
                String pdb = getStringOrNull(obj, "pdbUrl");
                String cif = getStringOrNull(obj, "cifUrl");
                String pae = getStringOrNull(obj, "paeImageUrl");
                String model = getStringOrNull(obj, "modelUrl");
                double gm = obj.has("globalMetricValue") ? obj.get("globalMetricValue").getAsDouble() : 0.0;
                return new AlphaFoldEntry(uid, desc, gene, seqLen, pdb, cif, pae, model, gm);
              }
            } catch (Exception ignored) {
            }
          }
        } catch (Exception ignored) {
        }
      }

      return null;
    } catch (Exception e) {
      return null;
    }
  }

  private static void saveAlphaFoldToDisk(AlphaFoldEntry entry) {
    try {
      JsonObject obj = new JsonObject();
      obj.addProperty("uniprotId", entry.uniprotId());
      obj.addProperty("uniprotDescription", entry.uniprotDescription());
      obj.addProperty("gene", entry.gene());
      obj.addProperty("sequenceLength", entry.sequenceLength());
      obj.addProperty("pdbUrl", entry.pdbUrl());
      obj.addProperty("cifUrl", entry.cifUrl());
      obj.addProperty("paeImageUrl", entry.paeImageUrl());
      obj.addProperty("modelUrl", entry.modelUrl());
      obj.addProperty("globalMetricValue", entry.globalMetricValue());
      DataCacheManager.saveToCache("alphafold", entry.uniprotId(), obj);
    } catch (Exception e) {
      // ignore
    }
  }

  // Load/save AlphaMissense full protein predictions as JSON array
  private static List<MissensePrediction> loadAlphaMissenseFromDisk(String uniprotId) {
    try {
      var opt = DataCacheManager.loadFromCache("alphafold_missense", uniprotId);
      JsonArray arr = null;
      if (opt.isPresent()) {
        JsonObject root = opt.get();
        arr = root.has("predictions") ? root.getAsJsonArray("predictions") : new JsonArray();
      } else {
        // Check legacy location ~/.baseplayer_cache/alphafold for missense-<id>.json
        Path legacy = Paths.get(System.getProperty("user.home"), ".baseplayer_cache", "alphafold", "missense-" + uniprotId + ".json");
        if (Files.exists(legacy)) {
          try {
            String s = Files.readString(legacy, StandardCharsets.UTF_8).trim();
            if (s.startsWith("[")) {
              arr = JsonParser.parseString(s).getAsJsonArray();
            } else {
              JsonObject root = JsonParser.parseString(s).getAsJsonObject();
              arr = root.has("predictions") ? root.getAsJsonArray("predictions") : new JsonArray();
            }
            // migrate
            JsonObject mig = new JsonObject();
            mig.add("predictions", arr);
            DataCacheManager.saveToCache("alphafold_missense", uniprotId, mig);
          } catch (Exception ignored) {
          }
        }
      }
      if (arr == null) return null;
      List<MissensePrediction> out = new ArrayList<>();
      for (JsonElement e : arr) {
        JsonObject o = e.getAsJsonObject();
        int pos = o.has("position") ? o.get("position").getAsInt() : 0;
        char ref = o.has("referenceAA") && o.get("referenceAA").getAsString().length() > 0
            ? o.get("referenceAA").getAsString().charAt(0) : 'X';
        char alt = o.has("alternateAA") && o.get("alternateAA").getAsString().length() > 0
            ? o.get("alternateAA").getAsString().charAt(0) : 'X';
        double ps = o.has("pathogenicity") ? o.get("pathogenicity").getAsDouble() : 0.0;
        String cls = getStringOrNull(o, "classification");
        out.add(new MissensePrediction(pos, ref, alt, ps, cls == null ? "ambiguous" : cls));
      }
      return out;
    } catch (Exception e) {
      return null;
    }
  }

  private static void saveAlphaMissenseToDisk(String uniprotId, List<MissensePrediction> list) {
    try {
      JsonArray arr = new JsonArray();
      for (MissensePrediction m : list) {
        JsonObject o = new JsonObject();
        o.addProperty("position", m.position());
        o.addProperty("referenceAA", String.valueOf(m.referenceAA()));
        o.addProperty("alternateAA", String.valueOf(m.alternateAA()));
        o.addProperty("pathogenicity", m.pathogenicity());
        o.addProperty("classification", m.classification());
        arr.add(o);
      }
      JsonObject root = new JsonObject();
      root.add("predictions", arr);
      DataCacheManager.saveToCache("alphafold_missense", uniprotId, root);
    } catch (Exception e) {
      // ignore
    }
  }

  // Load/save UniProt variant full cache
  private static List<MissensePrediction> loadUniProtVariantsFromDisk(String uniprotId) {
    try {
      var opt = DataCacheManager.loadFromCache("uniprot_variants", uniprotId);
      JsonArray arr = null;
      if (opt.isPresent()) {
        JsonObject root = opt.get();
        arr = root.has("variants") ? root.getAsJsonArray("variants") : new JsonArray();
      } else {
        // Check legacy location
        Path legacy = Paths.get(System.getProperty("user.home"), ".baseplayer_cache", "alphafold", "uniprot-" + uniprotId + ".json");
        if (Files.exists(legacy)) {
          try {
            String s = Files.readString(legacy, StandardCharsets.UTF_8).trim();
            if (s.startsWith("[")) {
              arr = JsonParser.parseString(s).getAsJsonArray();
            } else {
              JsonObject root = JsonParser.parseString(s).getAsJsonObject();
              arr = root.has("variants") ? root.getAsJsonArray("variants") : new JsonArray();
            }
            JsonObject mig = new JsonObject();
            mig.add("variants", arr);
            DataCacheManager.saveToCache("uniprot_variants", uniprotId, mig);
          } catch (Exception ignored) {}
        }
      }
      if (arr == null) return null;
      List<MissensePrediction> out = new ArrayList<>();
      for (JsonElement e : arr) {
        JsonObject o = e.getAsJsonObject();
        int pos = o.has("position") ? o.get("position").getAsInt() : 0;
        char ref = o.has("referenceAA") && o.get("referenceAA").getAsString().length() > 0
            ? o.get("referenceAA").getAsString().charAt(0) : 'X';
        char alt = o.has("alternateAA") && o.get("alternateAA").getAsString().length() > 0
            ? o.get("alternateAA").getAsString().charAt(0) : 'X';
        double ps = o.has("pathogenicity") ? o.get("pathogenicity").getAsDouble() : 0.0;
        String cls = getStringOrNull(o, "classification");
        out.add(new MissensePrediction(pos, ref, alt, ps, cls == null ? "ambiguous" : cls));
      }
      return out;
    } catch (Exception e) {
      return null;
    }
  }

  private static void saveUniProtVariantsToDisk(String uniprotId, List<MissensePrediction> list) {
    try {
      JsonArray arr = new JsonArray();
      for (MissensePrediction m : list) {
        JsonObject o = new JsonObject();
        o.addProperty("position", m.position());
        o.addProperty("referenceAA", String.valueOf(m.referenceAA()));
        o.addProperty("alternateAA", String.valueOf(m.alternateAA()));
        o.addProperty("pathogenicity", m.pathogenicity());
        o.addProperty("classification", m.classification());
        arr.add(o);
      }
      JsonObject root = new JsonObject();
      root.add("variants", arr);
      DataCacheManager.saveToCache("uniprot_variants", uniprotId, root);
    } catch (Exception e) {
      // ignore
    }
  }
  
  /**
   * AlphaFold structure metadata.
   */
  public record AlphaFoldEntry(
      String uniprotId,
      String uniprotDescription,
      String gene,
      int sequenceLength,
      String pdbUrl,
      String cifUrl,
      String paeImageUrl,
      String modelUrl,
      double globalMetricValue  // pLDDT average
  ) {
    /**
     * Get the AlphaFold database page URL.
     */
    public String getAlphaFoldUrl() {
      return "https://alphafold.ebi.ac.uk/entry/" + uniprotId;
    }
    
    /**
     * Get the structure visualization URL (Mol* viewer).
     */
    public String getViewerUrl() {
      return "https://alphafold.ebi.ac.uk/entry/" + uniprotId + "#view3d";
    }
  }
  
  /**
   * AlphaMissense pathogenicity prediction for a specific amino acid substitution.
   */
  public record MissensePrediction(
      int position,
      char referenceAA,
      char alternateAA,
      double pathogenicity,    // 0-1 score (higher = more pathogenic)
      String classification    // "benign", "ambiguous", "pathogenic"
  ) {
    /**
     * Get a human-readable pathogenicity description.
     */
    public String getDescription() {
      return String.format("%c%d%c: %s (%.3f)", 
          referenceAA, position, alternateAA, classification, pathogenicity);
    }
    
    /**
     * Check if this variant is predicted pathogenic.
     */
    public boolean isPathogenic() {
      return "pathogenic".equals(classification) || pathogenicity >= 0.564;
    }
    
    /**
     * Check if this variant is predicted benign.
     */
    public boolean isBenign() {
      return "benign".equals(classification) || pathogenicity <= 0.34;
    }
  }
  
  /**
   * Look up UniProt ID for a gene name (human genes only).
   * Returns cached result if available.
   */
  public static CompletableFuture<String> getUniprotId(String geneName) {
    // Check cache first
    String cached = geneToUniprotCache.get(geneName.toUpperCase());
    if (cached != null) {
      System.out.println("UniProt: Using cached ID for " + geneName + ": " + (cached.isEmpty() ? "not found" : cached));
      return CompletableFuture.completedFuture(cached.isEmpty() ? null : cached);
    }
    
    // Query UniProt
    String query = String.format(
        "%s/uniprotkb/search?query=gene_exact:%s+AND+organism_id:9606+AND+reviewed:true&format=json&size=1",
        UNIPROT_API, geneName);
    
    System.out.println("UniProt: Querying API for: " + geneName);
    
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(query))
        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .header("Accept", "application/json")
        .GET()
        .build();
    
    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          System.out.println("UniProt: Response status for " + geneName + ": " + response.statusCode());
          if (response.statusCode() != 200) {
            geneToUniprotCache.put(geneName.toUpperCase(), "");
            return null;
          }
          
          try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray results = json.getAsJsonArray("results");
            if (results != null && results.size() > 0) {
              String uniprotId = results.get(0).getAsJsonObject()
                  .get("primaryAccession").getAsString();
              System.out.println("UniProt: Found ID for " + geneName + ": " + uniprotId);
              geneToUniprotCache.put(geneName.toUpperCase(), uniprotId);
              return uniprotId;
            } else {
              System.out.println("UniProt: No results for " + geneName);
            }
          } catch (Exception e) {
            System.err.println("UniProt parse error: " + e.getMessage());
          }
          
          geneToUniprotCache.put(geneName.toUpperCase(), "");
          return null;
        })
        .exceptionally(e -> {
          System.err.println("UniProt API error: " + e.getMessage());
          return null;
        });
  }
  
  /**
   * Fetch AlphaFold structure metadata for a UniProt ID.
   */
  public static CompletableFuture<AlphaFoldEntry> getAlphaFoldEntry(String uniprotId) {
    if (uniprotId == null || uniprotId.isEmpty()) {
      System.out.println("AlphaFold: No UniProt ID provided");
      return CompletableFuture.completedFuture(null);
    }
    
    // Check if we already know this protein has no AlphaFold data
    if (alphaFoldNotFoundCache.containsKey(uniprotId)) {
      System.out.println("AlphaFold: Using cached 'not found' for " + uniprotId);
      return CompletableFuture.completedFuture(null);
    }
    
    // Check cache
    AlphaFoldEntry cached = alphaFoldCache.get(uniprotId);
    if (cached != null) {
      System.out.println("AlphaFold: Using cached entry for " + uniprotId);
      return CompletableFuture.completedFuture(cached);
    }

    // Check disk cache
    AlphaFoldEntry disk = loadAlphaFoldFromDisk(uniprotId);
    if (disk != null) {
      System.out.println("AlphaFold: Loaded entry from disk cache for " + uniprotId);
      alphaFoldCache.put(uniprotId, disk);
      return CompletableFuture.completedFuture(disk);
    }
    
    String url = ALPHAFOLD_API + "/prediction/" + uniprotId;
    System.out.println("AlphaFold: Querying API for: " + uniprotId);
    
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .header("Accept", "application/json")
        .GET()
        .build();
    
    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          System.out.println("AlphaFold: Response status for " + uniprotId + ": " + response.statusCode());
          if (response.statusCode() == 404) {
            System.out.println("AlphaFold: No prediction available for " + uniprotId + " (protein may be too large or not yet modeled)");
            alphaFoldNotFoundCache.put(uniprotId, true);  // Cache the 404
            return null;
          }
          if (response.statusCode() != 200) {
            return null;
          }
          
          try {
            JsonArray arr = JsonParser.parseString(response.body()).getAsJsonArray();
            if (arr.size() == 0) {
              System.out.println("AlphaFold: Empty response for " + uniprotId);
              return null;
            }
            
            JsonObject obj = arr.get(0).getAsJsonObject();
            
            String gene = obj.has("gene") ? obj.get("gene").getAsString() : "";
            String description = obj.has("uniprotDescription") 
                ? obj.get("uniprotDescription").getAsString() : "";
            // Use sequenceEnd (or uniprotEnd as fallback)
            int seqLength = 0;
            if (obj.has("sequenceEnd")) {
              seqLength = obj.get("sequenceEnd").getAsInt();
            } else if (obj.has("uniprotEnd")) {
              seqLength = obj.get("uniprotEnd").getAsInt();
            }
            
            String pdbUrl = obj.has("pdbUrl") ? obj.get("pdbUrl").getAsString() : "";
            String cifUrl = obj.has("cifUrl") ? obj.get("cifUrl").getAsString() : "";
            String paeImageUrl = obj.has("paeImageUrl") ? obj.get("paeImageUrl").getAsString() : "";
            String modelUrl = obj.has("modelUrl") ? obj.get("modelUrl").getAsString() : "";
            
            double plddt = 0;
            if (obj.has("globalMetricValue")) {
              plddt = obj.get("globalMetricValue").getAsDouble();
            }
            
            AlphaFoldEntry entry = new AlphaFoldEntry(
                uniprotId, description, gene, seqLength,
                pdbUrl, cifUrl, paeImageUrl, modelUrl, plddt
            );
            
            System.out.println("AlphaFold: Successfully parsed entry for " + uniprotId + " (pLDDT: " + plddt + ")");
            alphaFoldCache.put(uniprotId, entry);
            // Persist to disk for future runs
            saveAlphaFoldToDisk(entry);
            return entry;
            
          } catch (Exception e) {
            System.err.println("AlphaFold parse error: " + e.getMessage());
            return null;
          }
        })
        .exceptionally(e -> {
          System.err.println("AlphaFold API error: " + e.getMessage());
          return null;
        });
  }
  
  /**
   * Get AlphaFold entry for a gene name (combines UniProt lookup + AlphaFold query).
   */
  public static CompletableFuture<AlphaFoldEntry> getAlphaFoldForGene(String geneName) {
    return getUniprotId(geneName)
        .thenCompose(uniprotId -> {
          if (uniprotId == null) {
            return CompletableFuture.completedFuture(null);
          }
          return getAlphaFoldEntry(uniprotId);
        });
  }
  
  /**
   * Fetch AlphaMissense predictions for a specific amino acid position.
   * Returns all possible substitutions at that position with pathogenicity scores.
   * 
   * Note: AlphaMissense data is fetched from a precomputed TSV file.
   * For efficiency, this fetches by UniProt ID and position.
   */
  public static CompletableFuture<List<MissensePrediction>> getMissensePredictions(
      String geneName, int aminoAcidPosition, char referenceAA) {
    
    return getUniprotId(geneName)
        .thenCompose(uniprotId -> {
          if (uniprotId == null) {
            return CompletableFuture.completedFuture(List.of());
          }
          return fetchMissensePredictions(uniprotId, aminoAcidPosition, referenceAA);
        });
  }
  
  /**
   * Fetch AlphaMissense predictions from the AlphaFold DB API.
   * Caches the full protein's predictions and filters by position.
   */
  private static CompletableFuture<List<MissensePrediction>> fetchMissensePredictions(
      String uniprotId, int position, char referenceAA) {
    
    // Check if we already know this protein has no AlphaMissense data
    if (alphaMissenseNotFoundCache.containsKey(uniprotId)) {
      System.out.println("AlphaMissense: Using cached 'not found' for " + uniprotId);
      return CompletableFuture.completedFuture(fetchUniProtVariantsForPosition(uniprotId, position, referenceAA));
    }
    
    // Check if we have the full protein's data cached
    List<MissensePrediction> fullCache = alphaMissenseFullCache.get(uniprotId);
    if (fullCache == null) {
      // try disk
      List<MissensePrediction> diskMiss = loadAlphaMissenseFromDisk(uniprotId);
      if (diskMiss != null) {
        alphaMissenseFullCache.put(uniprotId, diskMiss);
        fullCache = diskMiss;
      }
    }
    if (fullCache != null) {
      System.out.println("AlphaMissense: Using full cache for " + uniprotId);
      List<MissensePrediction> filtered = fullCache.stream()
          .filter(p -> p.position() == position)
          .toList();
      if (filtered.isEmpty()) {
        return CompletableFuture.completedFuture(fetchUniProtVariantsForPosition(uniprotId, position, referenceAA));
      }
      return CompletableFuture.completedFuture(filtered);
    }
    
    // Fetch the full CSV file
    String amUrl = String.format(
        "https://alphafold.ebi.ac.uk/files/AF-%s-F1-aa-substitutions.csv",
        uniprotId);
    
    System.out.println("AlphaMissense: Fetching full CSV from " + amUrl);
    
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(amUrl))
        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .GET()
        .build();
    
    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() == 404) {
            System.out.println("AlphaMissense: No data for " + uniprotId + " (caching 404)");
            alphaMissenseNotFoundCache.put(uniprotId, true);
            return fetchUniProtVariantsForPosition(uniprotId, position, referenceAA);
          }
          
          if (response.statusCode() != 200) {
            System.out.println("AlphaMissense: Response status: " + response.statusCode());
            return fetchUniProtVariantsForPosition(uniprotId, position, referenceAA);
          }
          
          // Parse and cache ALL predictions from CSV
          List<MissensePrediction> allPredictions = new ArrayList<>();
          String[] lines = response.body().split("\n");
          System.out.println("AlphaMissense: Parsing " + lines.length + " lines for " + uniprotId);
          
          for (int i = 1; i < lines.length; i++) { // Skip header
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            
            String[] parts = line.split(",");
            if (parts.length < 3) continue;
            
            String variant = parts[0]; // e.g. "M1A"
            if (variant.length() < 3) continue;
            
            char refAA = variant.charAt(0);
            char altAA = variant.charAt(variant.length() - 1);
            String posStr = variant.substring(1, variant.length() - 1);
            
            try {
              int varPos = Integer.parseInt(posStr);
              double pathScore = Double.parseDouble(parts[1]);
              String amClass = parts[2].trim();
              
              String classification;
              if (amClass.equals("LPath") || amClass.equals("Path")) {
                classification = "pathogenic";
              } else if (amClass.equals("LBen") || amClass.equals("Ben")) {
                classification = "benign";
              } else {
                classification = "ambiguous";
              }
              
              allPredictions.add(new MissensePrediction(
                  varPos, refAA, altAA, pathScore, classification));
                  
            } catch (NumberFormatException e) {
              // Skip malformed lines
            }
          }
          
          // Cache the full protein data
          alphaMissenseFullCache.put(uniprotId, allPredictions);
          // Persist to disk
          saveAlphaMissenseToDisk(uniprotId, allPredictions);
          System.out.println("AlphaMissense: Cached " + allPredictions.size() + " total predictions for " + uniprotId);
          
          // Filter for the requested position
          List<MissensePrediction> filtered = allPredictions.stream()
              .filter(p -> p.position() == position)
              .toList();
          
          System.out.println("AlphaMissense: Found " + filtered.size() + " predictions for position " + position);
          
          if (filtered.isEmpty()) {
            return fetchUniProtVariantsForPosition(uniprotId, position, referenceAA);
          }
          return filtered;
        })
        .exceptionally(e -> {
          System.err.println("AlphaMissense error: " + e.getMessage());
          return fetchUniProtVariantsForPosition(uniprotId, position, referenceAA);
        });
  }
  
  /**
   * Get UniProt variants filtered for a specific position.
   * Uses cached data if available.
   */
  private static List<MissensePrediction> fetchUniProtVariantsForPosition(
      String uniprotId, int position, char referenceAA) {
    // Ensure we have the full variant data cached
    List<MissensePrediction> allVariants = uniprotVariantCache.get(uniprotId);
    if (allVariants == null) {
      allVariants = fetchAllUniProtVariants(uniprotId);
      uniprotVariantCache.put(uniprotId, allVariants);
    }
    
    // Filter for the requested position
    List<MissensePrediction> filtered = allVariants.stream()
        .filter(p -> p.position() == position)
        .toList();
    
    System.out.println("UniProt variants: Found " + filtered.size() + " variants for position " + position);
    return filtered;
  }
  
  /**
   * Fetch ALL variant data from UniProt Proteins API for a protein.
   * Caches the result for future position-specific queries.
   */
  private static List<MissensePrediction> fetchAllUniProtVariants(String uniprotId) {
    List<MissensePrediction> predictions = new ArrayList<>();
    // Try disk cache first
    try {
      List<MissensePrediction> disk = loadUniProtVariantsFromDisk(uniprotId);
      if (disk != null) {
        System.out.println("UniProt: Loaded variants from disk for " + uniprotId);
        return disk;
      }
    } catch (Exception e) {
      // ignore
    }
    
    try {
      String url = String.format(
          "https://www.ebi.ac.uk/proteins/api/variation/%s?format=json",
          uniprotId);
      
      System.out.println("UniProt: Fetching all variants for " + uniprotId);
      
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
          .header("Accept", "application/json")
          .GET()
          .build();
      
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      
      if (response.statusCode() == 200) {
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.has("features")) {
          JsonArray features = json.getAsJsonArray("features");
          for (JsonElement elem : features) {
            JsonObject feature = elem.getAsJsonObject();
            if (!"VARIANT".equals(getStringOrNull(feature, "type"))) continue;
            
            int pos = feature.has("begin") ? feature.get("begin").getAsInt() : 0;
            if (pos <= 0) continue;
            
            String altSeq = getStringOrNull(feature, "alternativeSequence");
            if (altSeq == null || altSeq.length() != 1) continue;
            
            char altAA = altSeq.charAt(0);
            
            // Get reference AA
            String wildType = getStringOrNull(feature, "wildType");
            char refAA = (wildType != null && wildType.length() == 1) ? wildType.charAt(0) : 'X';
            
            // Check for clinical significance
            String clinSig = "";
            if (feature.has("clinicalSignificances")) {
              JsonArray sigs = feature.getAsJsonArray("clinicalSignificances");
              if (sigs.size() > 0) {
                clinSig = sigs.get(0).getAsJsonObject().get("type").getAsString();
              }
            }
            
            // Classify based on clinical data
            double pathScore = 0.5;
            String classification = "ambiguous";
            if (clinSig.toLowerCase().contains("pathogenic")) {
              pathScore = 0.8;
              classification = "pathogenic";
            } else if (clinSig.toLowerCase().contains("benign")) {
              pathScore = 0.2;
              classification = "benign";
            }
            
            predictions.add(new MissensePrediction(
                pos, refAA, altAA, pathScore, classification));
          }
        }
      }
      
      System.out.println("UniProt: Cached " + predictions.size() + " total variants for " + uniprotId);
      // Persist to disk
      try {
        saveUniProtVariantsToDisk(uniprotId, predictions);
      } catch (Exception e) {
        // ignore
      }
      
    } catch (Exception e) {
      System.err.println("UniProt variants error: " + e.getMessage());
    }
    
    return predictions;
  }
  
  private static String getStringOrNull(JsonObject obj, String key) {
    return obj.has(key) && !obj.get(key).isJsonNull() 
        ? obj.get(key).getAsString() : null;
  }
  
  /**
   * Get the URL for the AlphaFold structure image (PAE plot).
   */
  public static String getPaeImageUrl(String uniprotId) {
    return ALPHAFOLD_FILES + "/AF-" + uniprotId + "-F1-predicted_aligned_error_v4.png";
  }
  
  /**
   * Get the URL for the AlphaFold model confidence image (pLDDT colored).
   */
  public static String getModelImageUrl(String uniprotId) {
    return ALPHAFOLD_FILES + "/AF-" + uniprotId + "-F1-model_v4.png";
  }
  
  /**
   * Get the 3D viewer URL for a specific residue position.
   */
  public static String getViewerUrlForResidue(String uniprotId, int position) {
    return String.format(
        "https://alphafold.ebi.ac.uk/entry/%s#residue-%d",
        uniprotId, position);
  }
}
