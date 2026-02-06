package org.baseplayer.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Manages file-based caching of API data in the user's home directory.
 * 
 * Cache location: ~/.BasePlayer/cache/
 * 
 * Structure:
 *   ~/.BasePlayer/cache/conservation/chr1_123456_234567.json
 *   ~/.BasePlayer/cache/gnomad/chr1_123456_234567.json
 * 
 * Features:
 * - Automatic directory creation
 * - TTL-based expiration (default 7 days)
 * - Query overlap detection for efficient cache reuse
 */
public class DataCacheManager {
  
  private static final String BASEPLAYER_FOLDER = ".BasePlayer";
  private static final String CACHE_FOLDER = "cache";
  private static final long DEFAULT_TTL_DAYS = 7;
  
  private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  
  private static Path cacheRoot = null;
  
  private DataCacheManager() {} // Utility class
  
  /**
   * Get the cache root directory, creating it if necessary.
   */
  public static synchronized Path getCacheRoot() {
    if (cacheRoot != null) {
      return cacheRoot;
    }
    
    try {
      String userHome = System.getProperty("user.home");
      Path basePlayerDir = Paths.get(userHome, BASEPLAYER_FOLDER);
      Path cacheDir = basePlayerDir.resolve(CACHE_FOLDER);
      
      // Create directories if they don't exist
      if (!Files.exists(basePlayerDir)) {
        Files.createDirectories(basePlayerDir);
        System.out.println("Created BasePlayer folder: " + basePlayerDir);
      }
      if (!Files.exists(cacheDir)) {
        Files.createDirectories(cacheDir);
        System.out.println("Created cache folder: " + cacheDir);
      }
      
      cacheRoot = cacheDir;
      return cacheRoot;
    } catch (IOException e) {
      System.err.println("Failed to create cache directory: " + e.getMessage());
      return null;
    }
  }
  
  /**
   * Get the directory for a specific data type (e.g., "conservation", "gnomad").
   */
  public static Path getDataTypeDir(String dataType) {
    Path root = getCacheRoot();
    if (root == null) return null;
    
    Path typeDir = root.resolve(dataType);
    try {
      if (!Files.exists(typeDir)) {
        Files.createDirectories(typeDir);
      }
      return typeDir;
    } catch (IOException e) {
      System.err.println("Failed to create data type directory: " + e.getMessage());
      return null;
    }
  }
  
  /**
   * Generate a cache key for a genomic region.
   */
  public static String getCacheKey(String chrom, long start, long end) {
    // Normalize chromosome name
    String chr = chrom.startsWith("chr") ? chrom : "chr" + chrom;
    return String.format("%s_%d_%d", chr, start, end);
  }
  
  /**
   * Generate a cache key with additional suffix (e.g., bin count).
   */
  public static String getCacheKey(String chrom, long start, long end, String suffix) {
    String chr = chrom.startsWith("chr") ? chrom : "chr" + chrom;
    return String.format("%s_%d_%d_%s", chr, start, end, suffix);
  }
  
  /**
   * Save data to the file cache.
   * 
   * @param dataType Data type (e.g., "conservation", "gnomad")
   * @param cacheKey Cache key for the file name
   * @param data JSON data to save
   */
  public static void saveToCache(String dataType, String cacheKey, JsonObject data) {
    Path typeDir = getDataTypeDir(dataType);
    if (typeDir == null) return;
    
    Path cacheFile = typeDir.resolve(cacheKey + ".json");
    try {
      // Add timestamp for expiration checking
      data.addProperty("_cachedAt", Instant.now().toEpochMilli());
      
      String json = gson.toJson(data);
      Files.writeString(cacheFile, json);
    } catch (IOException e) {
      System.err.println("Failed to save cache file: " + e.getMessage());
    }
  }
  
  /**
   * Load data from the file cache.
   * 
   * @param dataType Data type (e.g., "conservation", "gnomad")
   * @param cacheKey Cache key for the file name
   * @return Optional containing the cached data, or empty if not found or expired
   */
  public static Optional<JsonObject> loadFromCache(String dataType, String cacheKey) {
    return loadFromCache(dataType, cacheKey, DEFAULT_TTL_DAYS);
  }
  
  /**
   * Load data from the file cache with custom TTL.
   */
  public static Optional<JsonObject> loadFromCache(String dataType, String cacheKey, long ttlDays) {
    Path typeDir = getDataTypeDir(dataType);
    if (typeDir == null) return Optional.empty();
    
    Path cacheFile = typeDir.resolve(cacheKey + ".json");
    
    if (!Files.exists(cacheFile)) {
      return Optional.empty();
    }
    
    try {
      String json = Files.readString(cacheFile);
      JsonObject data = gson.fromJson(json, JsonObject.class);
      
      // Check expiration
      if (data.has("_cachedAt")) {
        long cachedAt = data.get("_cachedAt").getAsLong();
        Instant cachedTime = Instant.ofEpochMilli(cachedAt);
        Instant expireTime = cachedTime.plus(ttlDays, ChronoUnit.DAYS);
        
        if (Instant.now().isAfter(expireTime)) {
          // Cache expired, delete file
          Files.deleteIfExists(cacheFile);
          return Optional.empty();
        }
      }
      
      return Optional.of(data);
    } catch (Exception e) {
      System.err.println("Failed to load cache file: " + e.getMessage());
      return Optional.empty();
    }
  }
  
  /**
   * Check if a cached region contains the requested region.
   * This enables reuse of larger cached regions for smaller queries.
   * 
   * @param dataType Data type directory to search
   * @param chrom Chromosome
   * @param start Requested start position
   * @param end Requested end position
   * @return Optional containing matching cache file data
   */
  public static Optional<CachedRegion> findContainingCache(String dataType, String chrom, long start, long end) {
    Path typeDir = getDataTypeDir(dataType);
    if (typeDir == null) return Optional.empty();
    
    String chr = chrom.startsWith("chr") ? chrom : "chr" + chrom;
    
    try {
      // List all cache files for this chromosome
      return Files.list(typeDir)
          .filter(p -> p.getFileName().toString().startsWith(chr + "_"))
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> parseCacheFilename(p, chr))
          .filter(Optional::isPresent)
          .map(Optional::get)
          .filter(cr -> cr.start <= start && cr.end >= end)
          .findFirst()
          .flatMap(cr -> loadFromCache(dataType, getCacheKey(chr, cr.start, cr.end))
              .map(data -> new CachedRegion(cr.start, cr.end, data)));
    } catch (IOException e) {
      return Optional.empty();
    }
  }
  
  /**
   * Parse a cache filename to extract region coordinates.
   */
  private static Optional<RegionBounds> parseCacheFilename(Path file, String chrom) {
    try {
      String name = file.getFileName().toString();
      // Remove .json extension
      name = name.substring(0, name.length() - 5);
      // Parse: chr1_123456_234567
      String[] parts = name.split("_");
      if (parts.length >= 3) {
        long start = Long.parseLong(parts[1]);
        long end = Long.parseLong(parts[2]);
        return Optional.of(new RegionBounds(start, end));
      }
    } catch (Exception ignored) {}
    return Optional.empty();
  }
  
  /**
   * Clear all cached data for a specific data type.
   */
  public static void clearCache(String dataType) {
    Path typeDir = getDataTypeDir(dataType);
    if (typeDir == null) return;
    
    try {
      Files.list(typeDir)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .forEach(p -> {
            try {
              Files.delete(p);
            } catch (IOException ignored) {}
          });
    } catch (IOException e) {
      System.err.println("Failed to clear cache: " + e.getMessage());
    }
  }
  
  /**
   * Clear all cached data.
   */
  public static void clearAllCaches() {
    clearCache("conservation");
    clearCache("gnomad");
  }
  
  /**
   * Get cache statistics.
   */
  public static String getCacheStats() {
    Path root = getCacheRoot();
    if (root == null) return "Cache unavailable";
    
    try {
      long totalFiles = 0;
      long totalSize = 0;
      
      if (Files.exists(root)) {
        var files = Files.walk(root)
            .filter(Files::isRegularFile)
            .toList();
        totalFiles = files.size();
        for (Path file : files) {
          totalSize += Files.size(file);
        }
      }
      
      return String.format("Cache: %d files, %.2f MB", totalFiles, totalSize / (1024.0 * 1024.0));
    } catch (IOException e) {
      return "Cache stats unavailable";
    }
  }
  
  /**
   * Region bounds helper class.
   */
  private record RegionBounds(long start, long end) {}
  
  /**
   * Cached region with data.
   */
  public record CachedRegion(long start, long end, JsonObject data) {}
}
