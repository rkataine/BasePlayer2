package org.baseplayer.io;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Client for UCSC Genome Browser REST API.
 * Provides access to genomic tracks including conservation scores.
 * 
 * Features:
 * - Per-base data for small regions (<10kb)
 * - Smart caching that reuses overlapping fetched regions
 * - File-based caching for persistence across sessions (~/.BasePlayer/cache/conservation/)
 * - Binned data for larger regions
 * 
 * API documentation: https://api.genome.ucsc.edu/
 */
public class UcscApiClient {
  
  private static final String API_BASE = "https://api.genome.ucsc.edu";
  private static final int TIMEOUT_SECONDS = 30;
  private static final int MAX_REGION_SIZE = 100_000; // Max bases per request
  private static final int BASE_LEVEL_THRESHOLD = 10_000; // Fetch per-base data when view < this
  private static final String CACHE_TYPE = "conservation";
  
  private static final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();
  
  // Range-based cache for per-base conservation data per chromosome (in-memory)
  private static final Map<String, ChromosomeCache> chromosomeCache = new ConcurrentHashMap<>();
  
  // Cache for binned data when zoomed out (in-memory)
  private static final Map<String, ConservationData> binnedCache = new ConcurrentHashMap<>();
  private static final int MAX_BINNED_CACHE_ENTRIES = 100;
  
  private UcscApiClient() {} // Utility class
  
  /**
   * Fetch PhyloP conservation scores for a region.
   * Returns asynchronously to avoid blocking the UI.
   * 
   * For small regions (<10kb), fetches per-base data and caches it.
   * For larger regions, uses binned data.
   * 
   * @param chrom Chromosome (e.g., "1" or "chr1")
   * @param start Start position (1-based)
   * @param end End position (1-based)
   * @param bins Number of bins to summarize data into (for large regions)
   * @return CompletableFuture with conservation data
   */
  public static CompletableFuture<ConservationData> fetchConservation(
      String chrom, long start, long end, int bins) {
    
    // Normalize chromosome name
    String chr = chrom.startsWith("chr") ? chrom : "chr" + chrom;
    long regionSize = end - start;
    
    // For small regions, use per-base data with smart caching
    if (regionSize <= BASE_LEVEL_THRESHOLD) {
      return fetchBaseLevelData(chr, start, end);
    }
    
    // For large regions, use binned data
    return fetchBinnedData(chr, start, end, bins);
  }
  
  /**
   * Fetch per-base conservation scores with smart caching.
   * Cached data is reused if overlapping regions were previously fetched.
   * Also checks file cache for persistence across sessions.
   */
  private static CompletableFuture<ConservationData> fetchBaseLevelData(
      String chr, long start, long end) {
    
    ChromosomeCache cache = chromosomeCache.computeIfAbsent(chr, k -> new ChromosomeCache());
    
    // 1. Check in-memory cache first (fastest)
    ConservationData memCached = cache.getDataForRegion(start, end);
    if (memCached != null) {
      return CompletableFuture.completedFuture(memCached);
    }
    
    // 2. Check file cache for this exact region or a containing region
    String cacheKey = DataCacheManager.getCacheKey(chr, start, end, "base");
    Optional<JsonObject> fileCached = DataCacheManager.loadFromCache(CACHE_TYPE, cacheKey);
    if (fileCached.isPresent()) {
      ConservationData data = parseConservationDataFromCache(fileCached.get(), start, end);
      if (data != null && !data.hasError()) {
        // Also populate memory cache
        Map<Long, Double> rawData = extractRawDataFromCache(fileCached.get());
        if (!rawData.isEmpty()) {
          cache.addData(start, end, rawData);
        }
        System.out.println("Conservation: Loaded from file cache: " + cacheKey);
        return CompletableFuture.completedFuture(data);
      }
    }
    
    // Fetch with buffer to avoid many small requests
    long buffer = Math.min(10000, (end - start));
    long fetchStart = Math.max(1, start - buffer);
    long fetchEnd = Math.min(end + buffer, start + MAX_REGION_SIZE);
    
    // Build API URL
    String url = String.format(
        "%s/getData/track?genome=hg38&track=phyloP100way&chrom=%s&start=%d&end=%d",
        API_BASE, chr, fetchStart - 1, fetchEnd);
    
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .GET()
        .build();
    
    final long fStart = fetchStart;
    final long fEnd = fetchEnd;
    final String finalCacheKey = DataCacheManager.getCacheKey(chr, fStart, fEnd, "base");
    
    System.out.println("Conservation: Fetching from API: " + chr + ":" + fStart + "-" + fEnd);
    
    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() != 200) {
            System.err.println("UCSC API error: " + response.statusCode());
            return ConservationData.error(start, end, (int)(end - start), "API error: " + response.statusCode());
          }
          
          // Parse and cache the raw data
          Map<Long, Double> rawData = parseRawConservationData(response.body(), chr);
          cache.addData(fStart, fEnd, rawData);
          
          // Save to file cache
          saveBaseLevelToFileCache(finalCacheKey, fStart, fEnd, rawData);
          
          // Return data for requested region
          ConservationData result = cache.getDataForRegion(start, end);
          return result != null ? result : ConservationData.empty(start, end, (int)(end - start));
        })
        .exceptionally(e -> {
          String errorMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
          System.err.println("Failed to fetch conservation data: " + errorMsg);
          // Provide user-friendly error messages
          String displayMsg = "Connection error";
          if (errorMsg != null) {
            if (errorMsg.contains("UnresolvedAddressException") || errorMsg.contains("UnknownHost")) {
              displayMsg = "Network offline";
            } else if (errorMsg.contains("timed out") || errorMsg.contains("Timeout")) {
              displayMsg = "Request timed out";
            }
          }
          return ConservationData.error(start, end, (int)(end - start), displayMsg);
        });
  }
  
  /**
   * Save base-level conservation data to file cache.
   */
  private static void saveBaseLevelToFileCache(String cacheKey, long start, long end, Map<Long, Double> rawData) {
    JsonObject cacheJson = new JsonObject();
    cacheJson.addProperty("start", start);
    cacheJson.addProperty("end", end);
    
    JsonArray dataArray = new JsonArray();
    for (Map.Entry<Long, Double> entry : rawData.entrySet()) {
      JsonObject point = new JsonObject();
      point.addProperty("pos", entry.getKey());
      point.addProperty("value", entry.getValue());
      dataArray.add(point);
    }
    cacheJson.add("data", dataArray);
    
    DataCacheManager.saveToCache(CACHE_TYPE, cacheKey, cacheJson);
  }
  
  /**
   * Parse conservation data from file cache.
   */
  private static ConservationData parseConservationDataFromCache(JsonObject json, long start, long end) {
    try {
      int bins = (int)(end - start);
      double[] scores = new double[bins];
      double minScore = Double.MAX_VALUE;
      double maxScore = Double.MIN_VALUE;
      boolean hasAnyData = false;
      
      JsonArray dataArray = json.getAsJsonArray("data");
      if (dataArray != null) {
        for (JsonElement elem : dataArray) {
          JsonObject point = elem.getAsJsonObject();
          long pos = point.get("pos").getAsLong();
          double value = point.get("value").getAsDouble();
          
          if (pos >= start && pos < end) {
            int index = (int)(pos - start);
            if (index >= 0 && index < scores.length) {
              scores[index] = value;
              minScore = Math.min(minScore, value);
              maxScore = Math.max(maxScore, value);
              hasAnyData = true;
            }
          }
        }
      }
      
      if (!hasAnyData) {
        return new ConservationData(start, end, scores, 0, 0, false, null);
      }
      
      return new ConservationData(start, end, scores, 
          Math.max(-14, minScore), Math.min(6, maxScore), true, null);
    } catch (Exception e) {
      System.err.println("Failed to parse cached conservation data: " + e.getMessage());
      return null;
    }
  }
  
  /**
   * Extract raw position->value map from cached JSON.
   */
  private static Map<Long, Double> extractRawDataFromCache(JsonObject json) {
    Map<Long, Double> data = new HashMap<>();
    try {
      JsonArray dataArray = json.getAsJsonArray("data");
      if (dataArray != null) {
        for (JsonElement elem : dataArray) {
          JsonObject point = elem.getAsJsonObject();
          long pos = point.get("pos").getAsLong();
          double value = point.get("value").getAsDouble();
          data.put(pos, value);
        }
      }
    } catch (Exception e) {
      System.err.println("Failed to extract raw data from cache: " + e.getMessage());
    }
    return data;
  }
  
  /**
   * Parse raw conservation data from UCSC API (per-base positions).
   */
  private static Map<Long, Double> parseRawConservationData(String json, String chrom) {
    Map<Long, Double> data = new HashMap<>();
    
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      String chromKey = chrom.startsWith("chr") ? chrom : "chr" + chrom;
      
      if (!root.has(chromKey)) {
        return data;
      }
      
      JsonArray dataArray = root.getAsJsonArray(chromKey);
      if (dataArray == null) {
        return data;
      }
      
      for (JsonElement elem : dataArray) {
        JsonObject entry = elem.getAsJsonObject();
        // UCSC returns start, end, value for bigWig data (0-based start)
        long entryStart = entry.get("start").getAsLong() + 1; // Convert to 1-based
        long entryEnd = entry.get("end").getAsLong();
        double value = entry.get("value").getAsDouble();
        
        // Store value for each position in the range
        for (long pos = entryStart; pos <= entryEnd; pos++) {
          data.put(pos, value);
        }
      }
    } catch (Exception e) {
      System.err.println("Failed to parse raw conservation data: " + e.getMessage());
    }
    
    return data;
  }
  
  /**
   * Fetch binned conservation data for larger regions.
   * Uses file cache for persistence across sessions.
   */
  private static CompletableFuture<ConservationData> fetchBinnedData(
      String chr, long start, long end, int bins) {
    
    long regionSize = end - start;
    if (regionSize > MAX_REGION_SIZE) {
      bins = Math.min(bins, 500);
    }
    
    // 1. Check in-memory cache first
    String memoryCacheKey = chr + ":" + start + "-" + end + ":" + bins;
    ConservationData memCached = binnedCache.get(memoryCacheKey);
    if (memCached != null) {
      return CompletableFuture.completedFuture(memCached);
    }
    
    // 2. Check file cache
    String fileCacheKey = DataCacheManager.getCacheKey(chr, start, end, "binned_" + bins);
    Optional<JsonObject> fileCached = DataCacheManager.loadFromCache(CACHE_TYPE, fileCacheKey);
    if (fileCached.isPresent()) {
      ConservationData data = parseBinnedDataFromCache(fileCached.get(), start, end, bins);
      if (data != null && !data.hasError()) {
        binnedCache.put(memoryCacheKey, data);
        System.out.println("Conservation: Loaded binned from file cache: " + fileCacheKey);
        return CompletableFuture.completedFuture(data);
      }
    }
    
    // Build API URL
    String url = String.format(
        "%s/getData/track?genome=hg38&track=phyloP100way&chrom=%s&start=%d&end=%d",
        API_BASE, chr, start - 1, end);
    
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .GET()
        .build();
    
    int finalBins = bins;
    String finalChr = chr;
    String finalFileCacheKey = fileCacheKey;
    String finalMemoryCacheKey = memoryCacheKey;
    
    System.out.println("Conservation: Fetching binned from API: " + chr + ":" + start + "-" + end);
    
    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() != 200) {
            System.err.println("UCSC API error: " + response.statusCode());
            return ConservationData.error(start, end, finalBins, "API error: " + response.statusCode());
          }
          
          ConservationData data = parseBinnedConservationResponse(response.body(), start, end, finalBins, finalChr);
          
          // Cache the result (don't cache errors)
          if (!data.hasError()) {
            // Memory cache
            if (binnedCache.size() < MAX_BINNED_CACHE_ENTRIES) {
              binnedCache.put(finalMemoryCacheKey, data);
            }
            // File cache
            saveBinnedToFileCache(finalFileCacheKey, data);
          }
          
          return data;
        })
        .exceptionally(e -> {
          String errorMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
          System.err.println("Failed to fetch conservation data: " + errorMsg);
          // Provide user-friendly error messages
          String displayMsg = "Connection error";
          if (errorMsg != null) {
            if (errorMsg.contains("UnresolvedAddressException") || errorMsg.contains("UnknownHost")) {
              displayMsg = "Network offline";
            } else if (errorMsg.contains("timed out") || errorMsg.contains("Timeout")) {
              displayMsg = "Request timed out";
            }
          }
          return ConservationData.error(start, end, finalBins, displayMsg);
        });
  }
  
  /**
   * Save binned conservation data to file cache.
   */
  private static void saveBinnedToFileCache(String cacheKey, ConservationData data) {
    JsonObject cacheJson = new JsonObject();
    cacheJson.addProperty("start", data.start());
    cacheJson.addProperty("end", data.end());
    cacheJson.addProperty("minScore", data.minScore());
    cacheJson.addProperty("maxScore", data.maxScore());
    cacheJson.addProperty("hasData", data.hasData());
    
    JsonArray scoresArray = new JsonArray();
    for (double score : data.scores()) {
      scoresArray.add(score);
    }
    cacheJson.add("scores", scoresArray);
    
    DataCacheManager.saveToCache(CACHE_TYPE, cacheKey, cacheJson);
  }
  
  /**
   * Parse binned conservation data from file cache.
   */
  private static ConservationData parseBinnedDataFromCache(JsonObject json, long start, long end, int bins) {
    try {
      double minScore = json.get("minScore").getAsDouble();
      double maxScore = json.get("maxScore").getAsDouble();
      boolean hasData = json.get("hasData").getAsBoolean();
      
      JsonArray scoresArray = json.getAsJsonArray("scores");
      double[] scores = new double[scoresArray.size()];
      for (int i = 0; i < scoresArray.size(); i++) {
        scores[i] = scoresArray.get(i).getAsDouble();
      }
      
      return new ConservationData(start, end, scores, minScore, maxScore, hasData, null);
    } catch (Exception e) {
      System.err.println("Failed to parse cached binned data: " + e.getMessage());
      return null;
    }
  }
  
  /**
   * Parse binned conservation data from UCSC API.
   */
  private static ConservationData parseBinnedConservationResponse(
      String json, long start, long end, int bins, String chrom) {
    
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      
      // The data is stored under the chromosome key (e.g., "chr1"), not the track name
      String chromKey = chrom.startsWith("chr") ? chrom : "chr" + chrom;
      if (!root.has(chromKey)) {
        System.err.println("No data found for chromosome: " + chromKey);
        return ConservationData.empty(start, end, bins);
      }
      
      JsonArray dataArray = root.getAsJsonArray(chromKey);
      if (dataArray == null || dataArray.isEmpty()) {
        return ConservationData.empty(start, end, bins);
      }
      
      // Bin the values
      double[] binValues = new double[bins];
      int[] binCounts = new int[bins];
      double binSize = (double)(end - start) / bins;
      
      double minScore = Double.MAX_VALUE;
      double maxScore = Double.MIN_VALUE;
      
      for (JsonElement elem : dataArray) {
        JsonObject entry = elem.getAsJsonObject();
        // UCSC returns start, end, value for bigWig data (0-based)
        long pos = entry.get("start").getAsLong() + 1; // Convert to 1-based
        double value = entry.get("value").getAsDouble();
        
        // Determine which bin this position falls into
        int binIndex = (int)((pos - start) / binSize);
        if (binIndex >= 0 && binIndex < bins) {
          binValues[binIndex] += value;
          binCounts[binIndex]++;
        }
        
        minScore = Math.min(minScore, value);
        maxScore = Math.max(maxScore, value);
      }
      
      // Calculate averages
      double[] scores = new double[bins];
      for (int i = 0; i < bins; i++) {
        if (binCounts[i] > 0) {
          scores[i] = binValues[i] / binCounts[i];
        }
      }
      
      // PhyloP scores typically range from -14 to +6
      // Positive = conserved, Negative = fast-evolving
      return new ConservationData(start, end, scores, 
          Math.max(-14, minScore), Math.min(6, maxScore), true, null);
      
    } catch (Exception e) {
      System.err.println("Failed to parse conservation data: " + e.getMessage());
      e.printStackTrace();
      return ConservationData.empty(start, end, bins);
    }
  }
  
  /**
   * Clear all caches (memory and file).
   */
  public static void clearCache() {
    chromosomeCache.clear();
    binnedCache.clear();
    DataCacheManager.clearCache(CACHE_TYPE);
  }
  
  /**
   * Clear only in-memory caches.
   */
  public static void clearMemoryCache() {
    chromosomeCache.clear();
    binnedCache.clear();
  }
  
  /**
   * Check if a region is suitable for API fetch (not too large).
   */
  public static boolean isRegionFetchable(long start, long end) {
    return (end - start) <= MAX_REGION_SIZE;
  }
  
  /**
   * Get the maximum region size that can be fetched.
   */
  public static int getMaxRegionSize() {
    return MAX_REGION_SIZE;
  }
  
  /**
   * Get the threshold for base-level data fetching.
   */
  public static int getBaseLevelThreshold() {
    return BASE_LEVEL_THRESHOLD;
  }
  
  /**
   * Cache for per-base conservation data for a single chromosome.
   * Stores data in ranges and can satisfy queries from cached data.
   */
  private static class ChromosomeCache {
    // Stores per-base scores: position -> score
    private final NavigableMap<Long, Double> scores = new TreeMap<>();
    // Tracks which ranges have been fetched: start -> end
    private final NavigableMap<Long, Long> fetchedRanges = new TreeMap<>();
    private static final int MAX_CACHED_POSITIONS = 500_000;
    
    synchronized void addData(long start, long end, Map<Long, Double> data) {
      // Trim cache if too large
      if (scores.size() + data.size() > MAX_CACHED_POSITIONS) {
        // Remove oldest entries (lowest positions)
        int toRemove = scores.size() / 2;
        for (int i = 0; i < toRemove && !scores.isEmpty(); i++) {
          Long firstKey = scores.firstKey();
          scores.remove(firstKey);
        }
        // Clear range tracking and rebuild
        fetchedRanges.clear();
      }
      
      // Add new data
      scores.putAll(data);
      
      // Merge this range with existing ranges
      mergeRange(start, end);
    }
    
    private void mergeRange(long start, long end) {
      // Find overlapping or adjacent ranges and merge
      Long floorKey = fetchedRanges.floorKey(start);
      if (floorKey != null && fetchedRanges.get(floorKey) >= start - 1) {
        // Merge with previous range
        start = floorKey;
        end = Math.max(end, fetchedRanges.get(floorKey));
        fetchedRanges.remove(floorKey);
      }
      
      // Check for following ranges to merge
      while (true) {
        Long higherKey = fetchedRanges.higherKey(start);
        if (higherKey == null || higherKey > end + 1) break;
        end = Math.max(end, fetchedRanges.get(higherKey));
        fetchedRanges.remove(higherKey);
      }
      
      fetchedRanges.put(start, end);
    }
    
    synchronized ConservationData getDataForRegion(long start, long end) {
      // Check if we have all data for this region
      Long rangeStart = fetchedRanges.floorKey(start);
      if (rangeStart == null || fetchedRanges.get(rangeStart) < end) {
        return null; // Cache miss
      }
      
      // Build ConservationData from cached scores
      int bins = (int)(end - start);
      double[] regionScores = new double[bins];
      double minScore = Double.MAX_VALUE;
      double maxScore = Double.MIN_VALUE;
      boolean hasAnyData = false;
      
      for (int i = 0; i < bins; i++) {
        long pos = start + i;
        Double score = scores.get(pos);
        if (score != null) {
          regionScores[i] = score;
          minScore = Math.min(minScore, score);
          maxScore = Math.max(maxScore, score);
          hasAnyData = true;
        }
      }
      
      if (!hasAnyData) {
        return new ConservationData(start, end, regionScores, 0, 0, false, null);
      }
      
      return new ConservationData(start, end, regionScores, 
          Math.max(-14, minScore), Math.min(6, maxScore), true, null);
    }
  }
  
  /**
   * Conservation score data for a genomic region.
   */
  public record ConservationData(
      long start,
      long end,
      double[] scores,
      double minScore,
      double maxScore,
      boolean hasData,
      String errorMessage
  ) {
    public static ConservationData empty(long start, long end, int bins) {
      return new ConservationData(start, end, new double[bins], 0, 0, false, null);
    }
    
    public static ConservationData error(long start, long end, int bins, String message) {
      return new ConservationData(start, end, new double[bins], 0, 0, false, message);
    }
    
    public boolean hasError() {
      return errorMessage != null;
    }
    
    public int getBinCount() {
      return scores.length;
    }
    
    /**
     * Check if this data is per-base level (one score per position).
     */
    public boolean isBaseLevelData() {
      return scores.length == (end - start);
    }
  }
}
