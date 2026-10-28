package org.baseplayer.variant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One advanced INFO-field constraint. Encoded into {@link VariantFilter#getInfoFieldFilters()}
 * values for session persistence; plain values (no prefix) remain exact-match for compatibility.
 */
public final class InfoFieldRule {

  public enum Mode {
    /** Exact string equality on the whole stored INFO value. */
    EQUALS,
    /** At least one numeric token falls within {@code [min, max]} (unbounded when null). */
    NUMBER_RANGE,
    /**
     * Comma-separated numeric values (e.g. FORMAT {@code PR}): tokens are summed, then
     * the sum must fall in {@code [min, max]} (unbounded when null).
     */
    NUMBER_PAIR,
    /** INFO key must be present (Flag or any typed field). */
    FLAG_PRESENT,
    /** At least one comma/semicolon token equals an expected token. */
    CSV_ANY,
    /** Every expected token appears among actual tokens. */
    CSV_ALL
  }

  /** UI control kind inferred from the VCF header definition. */
  public enum UiKind {
    NUMBER,
    /** One threshold applied to the sum of comma-separated numeric values. */
    NUMBER_PAIR,
    TEXT,
    FLAG,
    CSV,
    FILTER
  }

  private final String fieldId;
  private final Mode mode;
  private final String textValue;
  private final Double min;
  private final Double max;

  private InfoFieldRule(String fieldId, Mode mode, String textValue, Double min, Double max) {
    this.fieldId = fieldId != null ? fieldId.trim() : "";
    this.mode = mode != null ? mode : Mode.EQUALS;
    this.textValue = textValue != null ? textValue : "";
    this.min = min;
    this.max = max;
  }

  public static InfoFieldRule equals(String fieldId, String value) {
    return new InfoFieldRule(fieldId, Mode.EQUALS, value, null, null);
  }

  public static InfoFieldRule numberRange(String fieldId, Double min, Double max) {
    return new InfoFieldRule(fieldId, Mode.NUMBER_RANGE, "", min, max);
  }

  /** Thresholds on the sum of comma-separated numeric tokens. */
  public static InfoFieldRule numberPair(String fieldId, Double minSum, Double maxSum) {
    return new InfoFieldRule(fieldId, Mode.NUMBER_PAIR, "", minSum, maxSum);
  }

  public static InfoFieldRule flagPresent(String fieldId) {
    return new InfoFieldRule(fieldId, Mode.FLAG_PRESENT, "1", null, null);
  }

  public static InfoFieldRule csvAny(String fieldId, String csv) {
    return new InfoFieldRule(fieldId, Mode.CSV_ANY, csv, null, null);
  }

  public static InfoFieldRule csvAll(String fieldId, String csv) {
    return new InfoFieldRule(fieldId, Mode.CSV_ALL, csv, null, null);
  }

  public String fieldId() {
    return fieldId;
  }

  public Mode mode() {
    return mode;
  }

  public String textValue() {
    return textValue;
  }

  public Double min() {
    return min;
  }

  public Double max() {
    return max;
  }

  public boolean matches(String actual) {
    return switch (mode) {
      case FLAG_PRESENT -> actual != null;
      case EQUALS -> actual != null && actual.equals(textValue);
      case NUMBER_RANGE -> matchesNumberRange(actual);
      case NUMBER_PAIR -> matchesNumberPair(actual);
      case CSV_ANY -> matchesCsvAny(actual);
      case CSV_ALL -> matchesCsvAll(actual);
    };
  }

  public String encode() {
    return switch (mode) {
      case EQUALS -> textValue;
      case FLAG_PRESENT -> "flag:1";
      case NUMBER_RANGE -> "num:"
          + (min != null ? formatNum(min) : "")
          + ":"
          + (max != null ? formatNum(max) : "");
      case NUMBER_PAIR -> "pair:"
          + (min != null ? formatNum(min) : "")
          + ":"
          + (max != null ? formatNum(max) : "");
      case CSV_ANY -> "csv:any:" + textValue;
      case CSV_ALL -> "csv:all:" + textValue;
    };
  }

  public static InfoFieldRule decode(String fieldId, String encoded) {
    if (encoded == null) {
      return equals(fieldId, "");
    }
    if (encoded.equals("flag:1") || encoded.equals("flag:true")) {
      return flagPresent(fieldId);
    }
    if (encoded.startsWith("pair:")) {
      String rest = encoded.substring(5);
      int colon = rest.indexOf(':');
      String first;
      String second;
      if (colon < 0) {
        first = rest;
        second = "";
      } else {
        first = rest.substring(0, colon);
        second = rest.substring(colon + 1);
      }
      return numberPair(fieldId, parseOptionalDouble(first), parseOptionalDouble(second));
    }
    if (encoded.startsWith("num:")) {
      String rest = encoded.substring(4);
      int colon = rest.indexOf(':');
      String minPart;
      String maxPart;
      if (colon < 0) {
        minPart = rest;
        maxPart = "";
      } else {
        minPart = rest.substring(0, colon);
        maxPart = rest.substring(colon + 1);
      }
      return numberRange(fieldId, parseOptionalDouble(minPart), parseOptionalDouble(maxPart));
    }
    if (encoded.startsWith("csv:any:")) {
      return csvAny(fieldId, encoded.substring("csv:any:".length()));
    }
    if (encoded.startsWith("csv:all:")) {
      return csvAll(fieldId, encoded.substring("csv:all:".length()));
    }
    if (encoded.startsWith("eq:")) {
      return equals(fieldId, encoded.substring(3));
    }
    return equals(fieldId, encoded);
  }

  public static UiKind uiKindFor(VcfHeaderFieldDef def) {
    if (def == null) {
      return UiKind.TEXT;
    }
    if (def.kind() == VcfHeaderFieldDef.Kind.FILTER) {
      return UiKind.FILTER;
    }
    if (isFlagType(def.type())) {
      return UiKind.FLAG;
    }
    if (isNumericType(def.type())) {
      return isMultiNumber(def.number()) ? UiKind.NUMBER_PAIR : UiKind.NUMBER;
    }
    if (isMultiNumber(def.number())) {
      return UiKind.CSV;
    }
    return UiKind.TEXT;
  }

  public static boolean isNumericType(String type) {
    if (type == null) {
      return false;
    }
    String t = type.trim().toLowerCase(Locale.ROOT);
    return t.equals("integer") || t.equals("float") || t.equals("double");
  }

  public static boolean isFlagType(String type) {
    return type != null && type.trim().equalsIgnoreCase("Flag");
  }

  /** Number=A/R/G/. or any integer &gt; 1 means multi-value / CSV-oriented. */
  public static boolean isMultiNumber(String number) {
    if (number == null || number.isBlank()) {
      return false;
    }
    String n = number.trim();
    if (n.equals(".") || n.equalsIgnoreCase("A") || n.equalsIgnoreCase("R") || n.equalsIgnoreCase("G")) {
      return true;
    }
    try {
      return Integer.parseInt(n) > 1;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private boolean matchesNumberRange(String actual) {
    if (actual == null || actual.isBlank()) {
      return false;
    }
    List<Double> values = parseNumericTokens(actual);
    if (values.isEmpty()) {
      return false;
    }
    // Comma-separated numerics (e.g. PR strand counts): filter on the sum.
    if (values.size() > 1) {
      return sumInRange(values);
    }
    double v = values.get(0);
    if (min != null && v < min) {
      return false;
    }
    if (max != null && v > max) {
      return false;
    }
    return true;
  }

  private boolean matchesNumberPair(String actual) {
    if (actual == null || actual.isBlank()) {
      return false;
    }
    if (min == null && max == null) {
      return true;
    }
    List<Double> values = parseNumericTokens(actual);
    if (values.isEmpty()) {
      return false;
    }
    return sumInRange(values);
  }

  private boolean sumInRange(List<Double> values) {
    double sum = 0;
    for (double v : values) {
      sum += v;
    }
    if (min != null && sum < min) {
      return false;
    }
    if (max != null && sum > max) {
      return false;
    }
    return true;
  }

  private boolean matchesCsvAny(String actual) {
    if (actual == null) {
      return false;
    }
    List<String> expected = splitTokens(textValue);
    if (expected.isEmpty()) {
      return true;
    }
    List<String> actualTokens = splitTokens(actual);
    for (String exp : expected) {
      if (actualTokens.contains(exp)) {
        return true;
      }
    }
    return false;
  }

  private boolean matchesCsvAll(String actual) {
    if (actual == null) {
      return false;
    }
    List<String> expected = splitTokens(textValue);
    if (expected.isEmpty()) {
      return true;
    }
    List<String> actualTokens = splitTokens(actual);
    return actualTokens.containsAll(expected);
  }

  static List<String> splitTokens(String raw) {
    List<String> out = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return out;
    }
    for (String part : raw.split("[,;|]")) {
      String t = part.trim();
      if (!t.isEmpty()) {
        out.add(t);
      }
    }
    return out;
  }

  private static List<Double> parseNumericTokens(String raw) {
    List<Double> out = new ArrayList<>();
    for (String token : splitTokens(raw)) {
      try {
        out.add(Double.parseDouble(token));
      } catch (NumberFormatException ignored) {
        // skip non-numeric tokens
      }
    }
    return out;
  }

  private static Double parseOptionalDouble(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Double.parseDouble(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String formatNum(double v) {
    if (Math.rint(v) == v && Math.abs(v) < 1e12) {
      return Long.toString((long) v);
    }
    return Double.toString(v);
  }
}
