package org.baseplayer.io.cache;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.google.gson.JsonObject;

/**
 * Generic three-layer cache: memory → file → network.
 *
 * <p>Callers drive the lookup sequence explicitly, which keeps the policy
 * for each kind of data (what counts as valid, when to skip file, etc.) in
 * the caller rather than baked into the cache itself.
 *
 * <p>Typical usage pattern:
 * <pre>
 *   private static final ThreeLayerCache&lt;MyData&gt; cache =
 *       new ThreeLayerCache&lt;&gt;("cacheType", 50);
 *
 *   // In the fetch method:
 *   MyData m = cache.getFromMemory(key);
 *   if (m != null) return CompletableFuture.completedFuture(m);
 *
 *   MyData f = cache.getFromFile(key, json -> parseMyData(json, ctx));
 *   if (f != null) return CompletableFuture.completedFuture(f);
 *
 *   // ... fetch from network ...
 *   if (!result.hasError()) {
 *     cache.put(key, result, GnomadApiClient::serialize);
 *   } else {
 *     cache.putInMemory(key, result); // don't persist errors
 *   }
 * </pre>
 *
 * @param <V> the value type stored in the cache
 */
public class ThreeLayerCache<V> {

  private final ConcurrentHashMap<String, V> memoryCache = new ConcurrentHashMap<>();
  private final String cacheType;
  private final int maxMemoryEntries;

  public ThreeLayerCache(String cacheType, int maxMemoryEntries) {
    this.cacheType = cacheType;
    this.maxMemoryEntries = maxMemoryEntries;
  }

  /**
   * Returns the cached value for {@code key}, or {@code null} if absent from memory.
   */
  public V getFromMemory(String key) {
    return memoryCache.get(key);
  }

  /**
   * Checks the file cache. If present, deserialises with {@code deserializer},
   * stores the result in memory, and returns it.
   * Returns {@code null} on a miss or when {@code deserializer} returns {@code null}.
   */
  public V getFromFile(String key, Function<JsonObject, V> deserializer) {
    Optional<JsonObject> opt = DataCacheManager.loadFromCache(cacheType, key);
    if (opt.isEmpty()) return null;
    V value = deserializer.apply(opt.get());
    if (value != null) memoryCache.put(key, value);
    return value;
  }

  /**
   * Stores {@code value} in both memory and file cache (serialised with {@code serializer}).
   * Evicts the oldest memory entry when the memory limit is reached.
   */
  public void put(String key, V value, Function<V, JsonObject> serializer) {
    evictIfFull();
    memoryCache.put(key, value);
    DataCacheManager.saveToCache(cacheType, key, serializer.apply(value));
  }

  /**
   * Stores {@code value} in memory only — useful for errors that should not be persisted to disk.
   */
  public void putInMemory(String key, V value) {
    evictIfFull();
    memoryCache.put(key, value);
  }

  /** Removes all entries from the in-memory cache. */
  public void clearMemory() {
    memoryCache.clear();
  }

  /** Removes all entries from both in-memory and file caches. */
  public void clearAll() {
    memoryCache.clear();
    DataCacheManager.clearCache(cacheType);
  }

  private void evictIfFull() {
    if (memoryCache.size() >= maxMemoryEntries) {
      memoryCache.keySet().stream().findFirst().ifPresent(memoryCache::remove);
    }
  }
}
