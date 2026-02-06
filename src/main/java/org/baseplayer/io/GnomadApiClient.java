package org.baseplayer.io;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Client for gnomAD GraphQL API.
 * Fetches population variant frequency data for genomic regions.
 * 
 * API: https://gnomad.broadinstitute.org/api
 * 
 * Features:
 * - Fetches variants by region with file-based caching (~/.BasePlayer/cache/gnomad/)
 * - Returns allele frequency, consequence, and annotation
 * - Rate-limited with debouncing
 */
public class GnomadApiClient {
  
  private static final String API_URL = "https://gnomad.broadinstitute.org/api";
  private static final int TIMEOUT_SECONDS = 30;
  private static final int MAX_REGION_SIZE = 50_000; // Max bases per request
  private static final String CACHE_TYPE = "gnomad";
  
  private static final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .build();
  
  private static final Gson gson = new Gson();
  
  // In-memory cache for variant data by region key (fast access)
  private static final Map<String, VariantData> memoryCache = new ConcurrentHashMap<>();
  private static final int MAX_MEMORY_CACHE_ENTRIES = 50;
  
  // Track failed regions to limit retries (max 2 attempts)
  private static final Map<String, Integer> failedRegions = new ConcurrentHashMap<>();
  private static final int MAX_RETRIES = 2;
  
  private GnomadApiClient() {} // Utility class
  
  /**
   * A variant from gnomAD.
   */
  public record Variant(
      long position,
      String ref,
      String alt,
      double alleleFrequency,
      int alleleCount,
      int alleleNumber,
      String consequence,     // e.g., "missense_variant", "stop_gained"
      String impact,          // "HIGH", "MODERATE", "LOW", "MODIFIER"
      String geneSymbol,
      String hgvsc,           // Coding change notation
      String hgvsp            // Protein change notation
  ) {
    /**
     * Check if this is a loss-of-function variant.
     */
    public boolean isLoF() {
      return "HIGH".equals(impact) || 
             (consequence != null && (
                 consequence.contains("stop_gained") ||
                 consequence.contains("frameshift") ||
                 consequence.contains("splice_donor") ||
                 consequence.contains("splice_acceptor") ||
                 consequence.contains("start_lost")
             ));
    }
    
    /**
     * Check if this is a missense variant.
     */
    public boolean isMissense() {
      return consequence != null && consequence.contains("missense");
    }
    
    /**
     * Check if this is a synonymous variant.
     */
    public boolean isSynonymous() {
      return consequence != null && consequence.contains("synonymous");
    }
    
    /**
     * Get a display label for this variant.
     */
    public String getLabel() {
      if (hgvsp != null && !hgvsp.isEmpty()) {
        return hgvsp;
      }
      if (hgvsc != null && !hgvsc.isEmpty()) {
        return hgvsc;
      }
      return ref + ">" + alt;
    }
    
    /**
     * Get formatted allele frequency string.
     */
    public String getAfString() {
      if (alleleFrequency == 0) return "0";
      if (alleleFrequency < 0.0001) return String.format("%.2e", alleleFrequency);
      if (alleleFrequency < 0.01) return String.format("%.4f", alleleFrequency);
      return String.format("%.3f", alleleFrequency);
    }
  }
  
  /**
   * Container for variant data in a region.
   */
  public record VariantData(
      long start,
      long end,
      List<Variant> variants,
      boolean hasData,
      String errorMessage
  ) {
    public boolean hasError() {
      return errorMessage != null && !errorMessage.isEmpty();
    }
    
    public static VariantData empty(long start, long end) {
      return new VariantData(start, end, List.of(), false, null);
    }
    
    public static VariantData error(long start, long end, String message) {
      return new VariantData(start, end, List.of(), false, message);
    }
  }
  
  /**
   * Get maximum region size for requests.
   */
  public static int getMaxRegionSize() {
    return MAX_REGION_SIZE;
  }
  
  /**
   * Fetch variants for a region from gnomAD.
   * 
   * @param chrom Chromosome (e.g., "1" or "chr1")
   * @param start Start position (1-based)
   * @param end End position (1-based)
   * @param dataset Dataset to query (default: "gnomad_r4")
   * @return CompletableFuture with variant data
   */
  public static CompletableFuture<VariantData> fetchVariants(
      String chrom, long start, long end, String dataset) {
    
    // Normalize chromosome name (gnomAD uses "1" not "chr1")
    String chr = chrom.startsWith("chr") ? chrom.substring(3) : chrom;
    long regionSize = end - start;
    
    if (regionSize > MAX_REGION_SIZE) {
      return CompletableFuture.completedFuture(
          VariantData.error(start, end, "Region too large (max " + MAX_REGION_SIZE / 1000 + "kb)"));
    }
    
    // Generate cache key
    String cacheKey = DataCacheManager.getCacheKey(chr, start, end, dataset);
    
    // For failure tracking, round to 10kb boundaries so nearby regions are grouped together
    long roundedStart = (start / 10000) * 10000;
    long roundedEnd = ((end / 10000) + 1) * 10000;
    String regionKey = chr + ":" + roundedStart + "-" + roundedEnd;
    
    // Check if we've already failed too many times for this region
    int failCount = failedRegions.getOrDefault(regionKey, 0);
    if (failCount >= MAX_RETRIES) {
      // Don't log - just silently return error to avoid spamming
      return CompletableFuture.completedFuture(
          VariantData.error(start, end, "Region unavailable"));
    }
    
    // 1. Check memory cache first (fastest)
    VariantData memCached = memoryCache.get(cacheKey);
    if (memCached != null) {
      return CompletableFuture.completedFuture(memCached);
    }
    
    // 2. Check file cache (persistent across sessions)
    Optional<JsonObject> fileCached = DataCacheManager.loadFromCache(CACHE_TYPE, cacheKey);
    if (fileCached.isPresent()) {
      VariantData data = parseVariantDataFromCache(fileCached.get(), start, end);
      if (data != null && !data.hasError()) {
        memoryCache.put(cacheKey, data);
        System.out.println("gnomAD: Loaded from file cache: " + cacheKey);
        return CompletableFuture.completedFuture(data);
      }
    }
    
    // 3. Fetch from API
    System.out.println("gnomAD: Fetching from API: chr" + chr + ":" + start + "-" + end);
    String query = buildGraphQLQuery(chr, start, end, dataset);
    
    // Create HTTP request with proper headers for GraphQL
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(API_URL))
        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header("User-Agent", "BasePlayer/2.0")
        .POST(HttpRequest.BodyPublishers.ofString(query))
        .build();
    
    final String finalCacheKey = cacheKey;
    final String finalRegionKey = regionKey;
    
    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() != 200) {
            System.err.println("gnomAD API HTTP error: " + response.statusCode());
            // Track failure
            failedRegions.merge(finalRegionKey, 1, Integer::sum);
            // Print response body for debugging 400 errors
            if (response.statusCode() == 400) {
              String body = response.body();
              System.err.println("gnomAD 400 error response: " + body.substring(0, Math.min(500, body.length())));
            }
            return VariantData.error(start, end, "API error: " + response.statusCode());
          }
          
          try {
            VariantData data = parseVariantResponse(response.body(), start, end);
            
            // Cache in memory
            if (memoryCache.size() >= MAX_MEMORY_CACHE_ENTRIES) {
              memoryCache.keySet().stream().findFirst().ifPresent(memoryCache::remove);
            }
            memoryCache.put(finalCacheKey, data);
            
            // Cache to file (only if no error and has data)
            if (!data.hasError()) {
              saveToFileCache(finalCacheKey, data);
            }
            
            return data;
          } catch (Exception e) {
            System.err.println("Failed to parse gnomAD response: " + e.getMessage());
            e.printStackTrace();
            return VariantData.error(start, end, "Parse error: " + e.getMessage());
          }
        })
        .exceptionally(e -> {
          String message = e.getMessage();
          System.err.println("gnomAD fetch error: " + message);
          // Track failure
          failedRegions.merge(finalRegionKey, 1, Integer::sum);
          if (message != null && message.contains("UnknownHostException")) {
            return VariantData.error(start, end, "Network offline");
          }
          if (message != null && message.contains("timed out")) {
            return VariantData.error(start, end, "Request timed out");
          }
          return VariantData.error(start, end, "Connection error");
        });
  }
  
  /**
   * Save variant data to file cache.
   */
  private static void saveToFileCache(String cacheKey, VariantData data) {
    JsonObject cacheJson = new JsonObject();
    cacheJson.addProperty("start", data.start());
    cacheJson.addProperty("end", data.end());
    cacheJson.addProperty("hasData", data.hasData());
    
    JsonArray variantsArray = new JsonArray();
    for (Variant v : data.variants()) {
      JsonObject vJson = new JsonObject();
      vJson.addProperty("position", v.position());
      vJson.addProperty("ref", v.ref());
      vJson.addProperty("alt", v.alt());
      vJson.addProperty("alleleFrequency", v.alleleFrequency());
      vJson.addProperty("alleleCount", v.alleleCount());
      vJson.addProperty("alleleNumber", v.alleleNumber());
      vJson.addProperty("consequence", v.consequence());
      vJson.addProperty("impact", v.impact());
      vJson.addProperty("geneSymbol", v.geneSymbol());
      vJson.addProperty("hgvsc", v.hgvsc());
      vJson.addProperty("hgvsp", v.hgvsp());
      variantsArray.add(vJson);
    }
    cacheJson.add("variants", variantsArray);
    
    DataCacheManager.saveToCache(CACHE_TYPE, cacheKey, cacheJson);
  }
  
  /**
   * Parse variant data from file cache.
   */
  private static VariantData parseVariantDataFromCache(JsonObject json, long start, long end) {
    try {
      boolean hasData = json.get("hasData").getAsBoolean();
      JsonArray variantsArray = json.getAsJsonArray("variants");
      
      List<Variant> variants = new ArrayList<>();
      if (variantsArray != null) {
        for (JsonElement elem : variantsArray) {
          JsonObject v = elem.getAsJsonObject();
          variants.add(new Variant(
              v.get("position").getAsLong(),
              getStringOrNull(v, "ref"),
              getStringOrNull(v, "alt"),
              getDoubleOrZero(v, "alleleFrequency"),
              getIntOrZero(v, "alleleCount"),
              getIntOrZero(v, "alleleNumber"),
              getStringOrNull(v, "consequence"),
              getStringOrNull(v, "impact"),
              getStringOrNull(v, "geneSymbol"),
              getStringOrNull(v, "hgvsc"),
              getStringOrNull(v, "hgvsp")
          ));
        }
      }
      
      return new VariantData(start, end, variants, hasData, null);
    } catch (Exception e) {
      System.err.println("Failed to parse cached variant data: " + e.getMessage());
      return null;
    }
  }
  
  /**
   * Fetch variants using default gnomAD v3 dataset (more stable).
   */
  public static CompletableFuture<VariantData> fetchVariants(String chrom, long start, long end) {
    // Use gnomad_r3 (v3.1.2) which is stable and widely used
    return fetchVariants(chrom, start, end, "gnomad_r3");
  }
  
  /**
   * Build GraphQL query for region variants.
   * Uses gnomAD API format.
   */
  private static String buildGraphQLQuery(String chr, long start, long end, String dataset) {
    // GraphQL query for variants in region
    // Note: gnomAD uses "transcript_consequence" (singular)
    String graphqlQuery = """
        query VariantsInRegion {
          region(chrom: \"%s\", start: %d, stop: %d, reference_genome: GRCh38) {
            variants(dataset: %s) {
              variant_id
              pos
              ref
              alt
              exome {
                ac
                an
                af
              }
              genome {
                ac
                an
                af
              }
              transcript_consequence {
                gene_symbol
                major_consequence
                hgvsc
                hgvsp
                lof
              }
            }
          }
        }
        """.formatted(chr, start, end, dataset);
    
    // Build the request body (inline query, no variables)
    JsonObject requestBody = new JsonObject();
    requestBody.addProperty("query", graphqlQuery);
    
    return requestBody.toString();
  }
  
  /**
   * Parse GraphQL response into VariantData.
   */
  private static VariantData parseVariantResponse(String responseBody, long start, long end) {
    JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
    
    // Check for errors
    if (json.has("errors")) {
      JsonArray errors = json.getAsJsonArray("errors");
      if (!errors.isEmpty()) {
        String errorMsg = errors.get(0).getAsJsonObject().get("message").getAsString();
        System.err.println("gnomAD API returned error: " + errorMsg);
        // Print full response for debugging
        System.err.println("Full response: " + responseBody.substring(0, Math.min(500, responseBody.length())));
        return VariantData.error(start, end, "API: " + errorMsg);
      }
    }
    
    // Extract variants
    JsonObject data = json.getAsJsonObject("data");
    if (data == null || data.isJsonNull()) {
      System.out.println("gnomAD: No data in response");
      return VariantData.empty(start, end);
    }
    
    JsonObject region = data.getAsJsonObject("region");
    if (region == null || region.isJsonNull()) {
      System.out.println("gnomAD: No region data in response");
      return VariantData.empty(start, end);
    }
    
    JsonArray variantsArray = region.getAsJsonArray("variants");
    if (variantsArray == null || variantsArray.isEmpty()) {
      System.out.println("gnomAD: No variants found in region");
      return new VariantData(start, end, List.of(), true, null);
    }
    
    System.out.println("gnomAD: Found " + variantsArray.size() + " variants");
    List<Variant> variants = new ArrayList<>();
    
    for (JsonElement elem : variantsArray) {
      JsonObject v = elem.getAsJsonObject();
      
      long pos = v.get("pos").getAsLong();
      String ref = getStringOrNull(v, "ref");
      String alt = getStringOrNull(v, "alt");
      
      // Get allele frequency - prefer exome data, fallback to genome
      double af = 0;
      int ac = 0;
      int an = 0;
      
      if (v.has("exome") && !v.get("exome").isJsonNull()) {
        JsonObject exome = v.getAsJsonObject("exome");
        af = getDoubleOrZero(exome, "af");
        ac = getIntOrZero(exome, "ac");
        an = getIntOrZero(exome, "an");
      }
      if (af == 0 && v.has("genome") && !v.get("genome").isJsonNull()) {
        JsonObject genome = v.getAsJsonObject("genome");
        af = getDoubleOrZero(genome, "af");
        ac = getIntOrZero(genome, "ac");
        an = getIntOrZero(genome, "an");
      }
      
      // Get consequence from transcript_consequence (singular in gnomAD API)
      String consequence = null;
      String impact = "MODIFIER";
      String geneSymbol = null;
      String hgvsc = null;
      String hgvsp = null;
      
      if (v.has("transcript_consequence") && !v.get("transcript_consequence").isJsonNull()) {
        JsonObject tc = v.getAsJsonObject("transcript_consequence");
        consequence = getStringOrNull(tc, "major_consequence");
        geneSymbol = getStringOrNull(tc, "gene_symbol");
        hgvsc = getStringOrNull(tc, "hgvsc");
        hgvsp = getStringOrNull(tc, "hgvsp");
        
        String lof = getStringOrNull(tc, "lof");
        if ("HC".equals(lof) || "LC".equals(lof)) {
          impact = "HIGH";
        } else if (consequence != null) {
          impact = getImpactFromConsequence(consequence);
        }
      }
      
      variants.add(new Variant(pos, ref, alt, af, ac, an, consequence, impact, geneSymbol, hgvsc, hgvsp));
    }
    
    return new VariantData(start, end, variants, true, null);
  }
  
  /**
   * Determine impact level from consequence term.
   */
  private static String getImpactFromConsequence(String consequence) {
    if (consequence == null) return "MODIFIER";
    
    // HIGH impact
    if (consequence.contains("stop_gained") || 
        consequence.contains("stop_lost") ||
        consequence.contains("frameshift") ||
        consequence.contains("splice_acceptor") ||
        consequence.contains("splice_donor") ||
        consequence.contains("start_lost") ||
        consequence.contains("transcript_ablation")) {
      return "HIGH";
    }
    
    // MODERATE impact
    if (consequence.contains("missense") ||
        consequence.contains("inframe_insertion") ||
        consequence.contains("inframe_deletion") ||
        consequence.contains("protein_altering")) {
      return "MODERATE";
    }
    
    // LOW impact
    if (consequence.contains("synonymous") ||
        consequence.contains("stop_retained") ||
        consequence.contains("splice_region")) {
      return "LOW";
    }
    
    return "MODIFIER";
  }
  
  private static String getStringOrNull(JsonObject obj, String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull()) {
      return obj.get(key).getAsString();
    }
    return null;
  }
  
  private static double getDoubleOrZero(JsonObject obj, String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull()) {
      return obj.get(key).getAsDouble();
    }
    return 0;
  }
  
  private static int getIntOrZero(JsonObject obj, String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull()) {
      return obj.get(key).getAsInt();
    }
    return 0;
  }
  
  /**
   * Clear the variant cache (both memory and file cache).
   */
  public static void clearCache() {
    memoryCache.clear();
    DataCacheManager.clearCache(CACHE_TYPE);
  }
  
  /**
   * Clear only the in-memory cache.
   */
  public static void clearMemoryCache() {
    memoryCache.clear();
  }
}
