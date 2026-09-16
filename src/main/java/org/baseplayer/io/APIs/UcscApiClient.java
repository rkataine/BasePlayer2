package org.baseplayer.io.APIs;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.baseplayer.io.UcscTrackInfo;
import org.baseplayer.io.cache.DataCacheManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

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
  private static final String TRACKS_LIST_CACHE_TYPE = "ucsc_tracks_list";
  
  private static final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();
  
  // Range-based cache for per-base conservation data per chromosome (in-memory)
  private static final Map<String, ChromosomeCache> chromosomeCache = new ConcurrentHashMap<>();
  
  // Cache for binned data when zoomed out (in-memory)
  private static final Map<String, ConservationData> binnedCache = new ConcurrentHashMap<>();
  private static final int MAX_BINNED_CACHE_ENTRIES = 100;
  
  // Cache for tracks list (in-memory)
  private static final Map<String, java.util.List<UcscTrackInfo>> tracksListCache = new ConcurrentHashMap<>();
  
  private UcscApiClient() {} // Utility class
  
  public static CompletableFuture<java.util.List<UcscTrackInfo>> fetchAvailableTracks(String genome) {
    // 1. Check file cache first (persists across sessions)
    Optional<JsonObject> fileCached = DataCacheManager.loadFromCache(TRACKS_LIST_CACHE_TYPE, genome);
    if (fileCached.isPresent()) {
      try {
        java.util.List<UcscTrackInfo> tracks = parseTracksFromCache(fileCached.get());
        if (!tracks.isEmpty()) {
          // Store in memory cache for faster subsequent access
          tracksListCache.put(genome, tracks);
          return CompletableFuture.completedFuture(tracks);
        }
      } catch (Exception e) {
        // Continue to fetch from API
      }
    }
    
    // 2. Check in-memory cache
    java.util.List<UcscTrackInfo> cached = tracksListCache.get(genome);
    if (cached != null) {
      return CompletableFuture.completedFuture(cached);
    }
    
    // 3. Fetch from API
    String url = String.format("%s/list/tracks?genome=%s", API_BASE, genome);
    
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .GET()
        .build();
    
    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() != 200) {
            java.util.List<UcscTrackInfo> empty = new java.util.ArrayList<>();
            return empty;
          }
          
          try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject tracksObj = json.getAsJsonObject(genome);
            if (tracksObj == null) {
              java.util.List<UcscTrackInfo> empty = new java.util.ArrayList<>();
              return empty;
            }
            
            java.util.List<UcscTrackInfo> tracks = new java.util.ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : tracksObj.entrySet()) {
              String trackName = entry.getKey();
              JsonObject trackData = entry.getValue().getAsJsonObject();
              
              String shortLabel = trackData.has("shortLabel") ? trackData.get("shortLabel").getAsString() : trackName;
              String longLabel = trackData.has("longLabel") ? trackData.get("longLabel").getAsString() : shortLabel;
              String type = trackData.has("type") ? trackData.get("type").getAsString() : "unknown";
              String group = trackData.has("group") ? trackData.get("group").getAsString() : "other";
              
              tracks.add(new UcscTrackInfo(trackName, shortLabel, longLabel, type, group));
            }
            
            // Cache the result in memory
            tracksListCache.put(genome, tracks);
            
            // Save to file cache for persistence across sessions
            saveTracksToFileCache(genome, tracks);
            
            
            return tracks;
          } catch (JsonSyntaxException e) {
            java.util.List<UcscTrackInfo> empty = new java.util.ArrayList<>();
            return empty;
          }
        })
        .exceptionally(e -> {
          java.util.List<UcscTrackInfo> empty = new java.util.ArrayList<>();
          return empty;
        });
  }
  
  public static CompletableFuture<java.util.List<UcscTrackInfo>> fetchAvailableTracksForceRefresh(String genome) {
    // Clear in-memory cache for this genome
    tracksListCache.remove(genome);
    
    // Clear file cache for this genome by deleting the specific cache file
    try {
      Path cacheRoot = DataCacheManager.getCacheRoot();
      Path typeDir = cacheRoot.resolve(TRACKS_LIST_CACHE_TYPE);
      Path cacheFile = typeDir.resolve(genome + ".json");
      if (Files.exists(cacheFile)) {
        Files.delete(cacheFile);
      }
    } catch (IOException e) {
    }
    
    
    // Now fetch (will not use cache since we just cleared it)
    return fetchAvailableTracks(genome);
  }
  
  private static void saveTracksToFileCache(String genome, java.util.List<UcscTrackInfo> tracks) {
    JsonObject cacheJson = new JsonObject();
    cacheJson.addProperty("genome", genome);
    cacheJson.addProperty("trackCount", tracks.size());
    
    JsonArray tracksArray = new JsonArray();
    for (UcscTrackInfo track : tracks) {
      JsonObject trackJson = new JsonObject();
      trackJson.addProperty("trackName", track.trackName());
      trackJson.addProperty("shortLabel", track.shortLabel());
      trackJson.addProperty("longLabel", track.longLabel());
      trackJson.addProperty("type", track.type());
      trackJson.addProperty("group", track.group());
      tracksArray.add(trackJson);
    }
    cacheJson.add("tracks", tracksArray);
    
    DataCacheManager.saveToCache(TRACKS_LIST_CACHE_TYPE, genome, cacheJson);
  }
  
  private static java.util.List<UcscTrackInfo> parseTracksFromCache(JsonObject json) {
    java.util.List<UcscTrackInfo> tracks = new java.util.ArrayList<>();
    
    if (!json.has("tracks")) {
      return tracks;
    }
    
    JsonArray tracksArray = json.getAsJsonArray("tracks");
    for (JsonElement elem : tracksArray) {
      JsonObject trackJson = elem.getAsJsonObject();
      
      String trackName = trackJson.has("trackName") ? trackJson.get("trackName").getAsString() : "";
      String shortLabel = trackJson.has("shortLabel") ? trackJson.get("shortLabel").getAsString() : trackName;
      String longLabel = trackJson.has("longLabel") ? trackJson.get("longLabel").getAsString() : shortLabel;
      String type = trackJson.has("type") ? trackJson.get("type").getAsString() : "unknown";
      String group = trackJson.has("group") ? trackJson.get("group").getAsString() : "other";
      
      tracks.add(new UcscTrackInfo(trackName, shortLabel, longLabel, type, group));
    }
    
    return tracks;
  }
  
  public static CompletableFuture<ConservationData> fetchTrack(
      String trackName, String chrom, long start, long end, int bins) {
    
    // Normalize chromosome name
    String chr = chrom.startsWith("chr") ? chrom : "chr" + chrom;
    long regionSize = end - start;
    
    // For small regions, use per-base data with smart caching
    if (regionSize <= BASE_LEVEL_THRESHOLD) {
      return fetchBaseLevelData(trackName, chr, start, end);
    }
    
    // For large regions, use binned data
    return fetchBinnedData(trackName, chr, start, end, bins);
  }
  
  private static CompletableFuture<ConservationData> fetchBaseLevelData(
      String trackName, String chr, long start, long end) {
    
    String cacheId = trackName + "|" + chr;
    ChromosomeCache cache = chromosomeCache.computeIfAbsent(cacheId, k -> new ChromosomeCache());
    
    // 1. Check in-memory cache first (fastest)
    ConservationData memCached = cache.getDataForRegion(start, end);
    if (memCached != null) {
      return CompletableFuture.completedFuture(memCached);
    }
    
    // 2. Check file cache for this exact region or a containing region
    String cacheKey = DataCacheManager.getCacheKey(chr, start, end, trackName + "_base");
    Optional<JsonObject> fileCached = DataCacheManager.loadFromCache(CACHE_TYPE, cacheKey);
    if (fileCached.isPresent()) {
      ConservationData data = parseConservationDataFromCache(fileCached.get(), start, end);
      if (data != null && data.hasData() && !data.hasError()) {
        // Also populate memory cache
        Map<Long, Double> rawData = extractRawDataFromCache(fileCached.get());
        if (!rawData.isEmpty()) {
          cache.addData(start, end, rawData);
        }
        return CompletableFuture.completedFuture(data);
      }
    }
    
    // Fetch with buffer to avoid many small requests
    long buffer = Math.min(10000, (end - start));
    long fetchStart = Math.max(1, start - buffer);
    long fetchEnd = Math.min(end + buffer, start + MAX_REGION_SIZE);
    
    // Build API URL
    String url = String.format(
        "%s/getData/track?genome=hg38&track=%s&chrom=%s&start=%d&end=%d",
        API_BASE, trackName, chr, fetchStart - 1, fetchEnd);
    
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .GET()
        .build();
    
    final long fStart = fetchStart;
    final long fEnd = fetchEnd;
    final String finalCacheKey = DataCacheManager.getCacheKey(chr, fStart, fEnd, trackName + "_base");
    
    
    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() != 200) {
            String msg = response.statusCode() == 403 ? "Track access restricted"
                : "API error: " + response.statusCode();
            return ConservationData.error(start, end, (int)(end - start), msg);
          }
          
          // Parse and cache the raw data
          Map<Long, Double> rawData = parseRawConservationData(response.body(), chr);
          if (rawData.isEmpty()) {
            return ConservationData.empty(start, end, (int) (end - start));
          }
          cache.addData(fStart, fEnd, rawData);
          
          // Save to file cache
          saveBaseLevelToFileCache(finalCacheKey, fStart, fEnd, rawData);
          
          // Return data for requested region
          ConservationData result = cache.getDataForRegion(start, end);
          return result != null ? result : ConservationData.empty(start, end, (int)(end - start));
        })
        .exceptionally(e -> {
          Throwable cause = e.getCause();
          String errorMsg = cause != null ? cause.getMessage() : e.getMessage();
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
  
  private static ConservationData parseConservationDataFromCache(JsonObject json, long start, long end) {
    try {
      int bins = (int)(end - start);
      double[] scores = new double[bins];
      double minScore = Double.MAX_VALUE;
      double maxScore = Double.NEGATIVE_INFINITY;
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
      
      return new ConservationData(start, end, scores, minScore, maxScore, true, null);
    } catch (Exception e) {
      return null;
    }
  }
  
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
    }
    return data;
  }
  
  private static JsonArray extractTrackDataArray(JsonObject root, String chrom) {
    if (root == null) {
      return null;
    }
    if (root.has("error")) {
      return null;
    }

    if (root.has("track") && root.get("track").isJsonPrimitive()) {
      String trackKey = root.get("track").getAsString();
      if (root.has(trackKey) && root.get(trackKey).isJsonArray()) {
        return root.getAsJsonArray(trackKey);
      }
    }

    String chromKey = chrom != null && chrom.startsWith("chr") ? chrom : "chr" + chrom;
    if (root.has(chromKey) && root.get(chromKey).isJsonArray()) {
      return root.getAsJsonArray(chromKey);
    }

    // Fallback: first array field that looks like score data
    for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
      if (!entry.getValue().isJsonArray()) {
        continue;
      }
      String key = entry.getKey();
      if (key.equals("downloadTime") || key.equals("statusMessage")) {
        continue;
      }
      JsonArray arr = entry.getValue().getAsJsonArray();
      if (!arr.isEmpty() && arr.get(0).isJsonObject() && arr.get(0).getAsJsonObject().has("value")) {
        return arr;
      }
    }
    return null;
  }

  private static Map<Long, Double> parseRawConservationData(String json, String chrom) {
    Map<Long, Double> data = new HashMap<>();
    
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      JsonArray dataArray = extractTrackDataArray(root, chrom);
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
    } catch (JsonSyntaxException e) {
    }
    
    return data;
  }
  
  private static CompletableFuture<ConservationData> fetchBinnedData(
      String trackName, String chr, long start, long end, int bins) {
    
    long regionSize = end - start;
    if (regionSize > MAX_REGION_SIZE) {
      bins = Math.min(bins, 500);
    }
    
    // 1. Check in-memory cache first
    String memoryCacheKey = trackName + ":" + chr + ":" + start + "-" + end + ":" + bins;
    ConservationData memCached = binnedCache.get(memoryCacheKey);
    if (memCached != null) {
      return CompletableFuture.completedFuture(memCached);
    }
    
    // 2. Check file cache
    String fileCacheKey = DataCacheManager.getCacheKey(chr, start, end, trackName + "_binned_" + bins);
    Optional<JsonObject> fileCached = DataCacheManager.loadFromCache(CACHE_TYPE, fileCacheKey);
    if (fileCached.isPresent()) {
      ConservationData data = parseBinnedDataFromCache(fileCached.get(), start, end);
      if (data != null && data.hasData() && !data.hasError()) {
        binnedCache.put(memoryCacheKey, data);
        return CompletableFuture.completedFuture(data);
      }
    }
    
    // Build API URL
    String url = String.format(
        "%s/getData/track?genome=hg38&track=%s&chrom=%s&start=%d&end=%d",
        API_BASE, trackName, chr, start - 1, end);
    
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .GET()
        .build();
    
    int finalBins = bins;
    String finalChr = chr;
    String finalFileCacheKey = fileCacheKey;
    String finalMemoryCacheKey = memoryCacheKey;
    
    
    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() != 200) {
            String msg = response.statusCode() == 403 ? "Track access restricted"
                : "API error: " + response.statusCode();
            return ConservationData.error(start, end, finalBins, msg);
          }
          
          ConservationData data = parseBinnedConservationResponse(response.body(), start, end, finalBins, finalChr);
          
          // Cache only real data (empty parses used to poison the cache)
          if (data.hasData() && !data.hasError()) {
            if (binnedCache.size() < MAX_BINNED_CACHE_ENTRIES) {
              binnedCache.put(finalMemoryCacheKey, data);
            }
            saveBinnedToFileCache(finalFileCacheKey, data);
          }
          
          return data;
        })
        .exceptionally(e -> {
          Throwable cause = e.getCause();
          String errorMsg = cause != null ? cause.getMessage() : e.getMessage();
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
  
  private static ConservationData parseBinnedDataFromCache(JsonObject json, long start, long end) {
    try {
      boolean hasData = json.get("hasData").getAsBoolean();
      
      JsonArray scoresArray = json.getAsJsonArray("scores");
      double[] scores = new double[scoresArray.size()];
      double minScore = Double.MAX_VALUE;
      double maxScore = Double.NEGATIVE_INFINITY;
      boolean hasValues = false;
      for (int i = 0; i < scoresArray.size(); i++) {
        scores[i] = scoresArray.get(i).getAsDouble();
        if (scores[i] != 0) {
          minScore = Math.min(minScore, scores[i]);
          maxScore = Math.max(maxScore, scores[i]);
          hasValues = true;
        }
      }
      if (!hasData || !hasValues) {
        return new ConservationData(start, end, scores, 0, 0, false, null);
      }
      
      return new ConservationData(start, end, scores, minScore, maxScore, true, null);
    } catch (JsonSyntaxException e) {
      return null;
    }
  }
  
  private static ConservationData parseBinnedConservationResponse(
      String json, long start, long end, int bins, String chrom) {
    
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      JsonArray dataArray = extractTrackDataArray(root, chrom);
      if (dataArray == null || dataArray.isEmpty()) {
        return ConservationData.empty(start, end, bins);
      }
      
      // Bin the values
      double[] binValues = new double[bins];
      int[] binCounts = new int[bins];
      double binSize = (double)(end - start) / bins;
      
      double minScore = Double.MAX_VALUE;
      double maxScore = Double.NEGATIVE_INFINITY;
      
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
      }
      
      // Calculate averages
      double[] scores = new double[bins];
      boolean hasAny = false;
      for (int i = 0; i < bins; i++) {
        if (binCounts[i] > 0) {
          scores[i] = binValues[i] / binCounts[i];
          minScore = Math.min(minScore, scores[i]);
          maxScore = Math.max(maxScore, scores[i]);
          hasAny = true;
        }
      }
      if (!hasAny) {
        return ConservationData.empty(start, end, bins);
      }
      
      // Auto-scale to the values actually drawn in this region
      return new ConservationData(start, end, scores, minScore, maxScore, true, null);
      
    } catch (JsonSyntaxException e) {
      return ConservationData.empty(start, end, bins);
    }
  }
  
  public static void clearCache() {
    chromosomeCache.clear();
    binnedCache.clear();
    tracksListCache.clear();
    DataCacheManager.clearCache(CACHE_TYPE);
  }
  
  public static void clearMemoryCache() {
    chromosomeCache.clear();
    binnedCache.clear();
    tracksListCache.clear();
  }
  
  public static boolean isRegionFetchable(long start, long end) {
    return (end - start) <= MAX_REGION_SIZE;
  }
  
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
      double maxScore = Double.NEGATIVE_INFINITY;
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
      
      return new ConservationData(start, end, regionScores, minScore, maxScore, true, null);
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
