package org.baseplayer.utils;

import com.google.gson.JsonObject;

/**
 * Common helpers for extracting values from Gson {@link JsonObject}s.
 * Provides null-safe accessors that return sensible defaults when a key is
 * missing or its value is {@code JsonNull}.
 */
public final class JsonUtils {

  /**
   * Return the string value for {@code key}, or {@code null} if absent / null.
   */
  public static String getStringOrNull(JsonObject obj, String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull()) {
      return obj.get(key).getAsString();
    }
    return null;
  }

  /**
   * Return the double value for {@code key}, or {@code 0.0} if absent / null.
   */
  public static double getDoubleOrZero(JsonObject obj, String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull()) {
      return obj.get(key).getAsDouble();
    }
    return 0;
  }

  /**
   * Return the int value for {@code key}, or {@code 0} if absent / null.
   */
  public static int getIntOrZero(JsonObject obj, String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull()) {
      return obj.get(key).getAsInt();
    }
    return 0;
  }

  private JsonUtils() {} // Utility class
}
