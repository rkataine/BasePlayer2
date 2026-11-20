package org.baseplayer.io.table;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Maps semantic {@link MappedTableColumnRole}s to 0-based table column indexes.
 */
public final class MappedTableColumnMapping {

  private final Map<MappedTableColumnRole, Integer> indexes;
  private final boolean startZeroBased;
  /** When true, the first non-comment line is a header (not data). */
  private final boolean headerRow;

  public MappedTableColumnMapping(Map<MappedTableColumnRole, Integer> indexes, boolean startZeroBased) {
    this(indexes, startZeroBased, true);
  }

  public MappedTableColumnMapping(
      Map<MappedTableColumnRole, Integer> indexes,
      boolean startZeroBased,
      boolean headerRow) {
    EnumMap<MappedTableColumnRole, Integer> copy = new EnumMap<>(MappedTableColumnRole.class);
    if (indexes != null) {
      copy.putAll(indexes);
    }
    this.indexes = Collections.unmodifiableMap(copy);
    this.startZeroBased = startZeroBased;
    this.headerRow = headerRow;
  }

  public Map<MappedTableColumnRole, Integer> indexes() {
    return indexes;
  }

  public boolean startZeroBased() {
    return startZeroBased;
  }

  public boolean headerRow() {
    return headerRow;
  }

  public Integer indexOf(MappedTableColumnRole role) {
    return indexes.get(role);
  }

  public boolean has(MappedTableColumnRole role) {
    return indexes.containsKey(role);
  }

  public boolean hasRequired() {
    for (MappedTableColumnRole role : MappedTableColumnRole.values()) {
      if (role.required() && !indexes.containsKey(role)) {
        return false;
      }
    }
    return true;
  }

  /** At least one usable numeric payload for analysis. */
  public boolean hasPayload() {
    return has(MappedTableColumnRole.VALUE)
        || has(MappedTableColumnRole.SCORE)
        || (has(MappedTableColumnRole.MINOR_CN) && has(MappedTableColumnRole.MAJOR_CN));
  }

  public boolean hasAlleleCn() {
    return has(MappedTableColumnRole.MINOR_CN) && has(MappedTableColumnRole.MAJOR_CN);
  }

  public boolean hasValueColumn() {
    return has(MappedTableColumnRole.VALUE) || has(MappedTableColumnRole.SCORE);
  }

  public static MappedTableColumnMapping autoDetect(String[] headers) {
    EnumMap<MappedTableColumnRole, Integer> map = new EnumMap<>(MappedTableColumnRole.class);
    if (headers == null) {
      return new MappedTableColumnMapping(map, false, true);
    }
    for (int i = 0; i < headers.length; i++) {
      String raw = headers[i] != null ? headers[i].trim() : "";
      if (raw.isEmpty()) {
        continue;
      }
      MappedTableColumnRole role = guessRole(normalize(raw));
      if (role != null && !map.containsKey(role)) {
        map.put(role, i);
      }
    }
    boolean looksLikeHeader = !map.isEmpty();
    return new MappedTableColumnMapping(map, false, looksLikeHeader || map.isEmpty());
  }

  /**
   * True when the first line looks more like a header than data
   * (has chrom/start/end-like tokens, or mostly non-numeric cells).
   */
  public static boolean firstLineLooksLikeHeader(String[] firstLine) {
    if (firstLine == null || firstLine.length == 0) {
      return true;
    }
    int roleHits = 0;
    int numeric = 0;
    int cells = 0;
    for (String raw : firstLine) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      cells++;
      String key = normalize(raw);
      if (guessRole(key) != null) {
        roleHits++;
      }
      try {
        Double.parseDouble(raw.trim());
        numeric++;
      } catch (NumberFormatException ignored) {
        // text
      }
    }
    if (roleHits >= 2) {
      return true;
    }
    if (cells > 0 && numeric * 2 >= cells) {
      // mostly numeric → likely data without header
      return roleHits > 0;
    }
    return true;
  }

  public static String fingerprint(String[] headers) {
    return fingerprint(headers, true);
  }

  /**
   * Stable key for prefs / memory. Uses {@code |} between normalized header tokens
   * (safe for {@link java.util.prefs.Preferences} XML — never use control chars).
   */
  public static String fingerprint(String[] headers, boolean headerRow) {
    if (!headerRow) {
      int n = headers != null ? headers.length : 0;
      return "cols:" + n;
    }
    if (headers == null || headers.length == 0) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < headers.length; i++) {
      if (i > 0) {
        sb.append('|');
      }
      sb.append(normalize(headers[i] != null ? headers[i] : ""));
    }
    return sb.toString();
  }

  /** Serialize: {@code zeroBased|headerRow|ROLE=idx;…} (legacy {@code zeroBased|ROLE=…} still loads). */
  public String serialize() {
    StringBuilder sb = new StringBuilder();
    sb.append(startZeroBased ? '1' : '0').append('|');
    sb.append(headerRow ? '1' : '0').append('|');
    boolean first = true;
    for (Map.Entry<MappedTableColumnRole, Integer> e : indexes.entrySet()) {
      if (!first) {
        sb.append(';');
      }
      first = false;
      sb.append(e.getKey().name()).append('=').append(e.getValue());
    }
    return sb.toString();
  }

  public static MappedTableColumnMapping deserialize(String raw) {
    EnumMap<MappedTableColumnRole, Integer> map = new EnumMap<>(MappedTableColumnRole.class);
    boolean zero = false;
    boolean header = true;
    if (raw == null || raw.isBlank()) {
      return new MappedTableColumnMapping(map, false, true);
    }
    String[] parts = raw.split("\\|", -1);
    String body;
    if (parts.length >= 3) {
      zero = "1".equals(parts[0]);
      header = !"0".equals(parts[1]);
      body = parts[2];
    } else if (parts.length == 2) {
      zero = "1".equals(parts[0]);
      body = parts[1];
    } else {
      body = raw;
    }
    if (!body.isBlank()) {
      for (String part : body.split(";")) {
        int eq = part.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        try {
          MappedTableColumnRole role = MappedTableColumnRole.valueOf(part.substring(0, eq).trim());
          int idx = Integer.parseInt(part.substring(eq + 1).trim());
          map.put(role, idx);
        } catch (RuntimeException ignored) {
          // skip bad token
        }
      }
    }
    return new MappedTableColumnMapping(map, zero, header);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MappedTableColumnMapping that)) {
      return false;
    }
    return startZeroBased == that.startZeroBased
        && headerRow == that.headerRow
        && Objects.equals(indexes, that.indexes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(indexes, startZeroBased, headerRow);
  }

  private static String normalize(String header) {
    return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
  }

  private static MappedTableColumnRole guessRole(String key) {
    if (key.isEmpty()) {
      return null;
    }
    if (key.equals("chrom") || key.equals("chromosome") || key.equals("chr") || key.equals("seqnames")) {
      return MappedTableColumnRole.CHROM;
    }
    if (key.equals("start") || key.equals("startpos") || key.equals("segstart") || key.equals("pos")) {
      return MappedTableColumnRole.START;
    }
    if (key.equals("end") || key.equals("endpos") || key.equals("segend") || key.equals("stop")) {
      return MappedTableColumnRole.END;
    }
    if (key.equals("nminor")
        || key.equals("minor")
        || key.equals("minorcn")
        || key.equals("minoralcn")
        || key.equals("mincn")
        || key.equals("cnminor")
        || key.equals("lcn")
        || key.equals("lcnem")) {
      return MappedTableColumnRole.MINOR_CN;
    }
    if (key.equals("nmajor")
        || key.equals("major")
        || key.equals("majorcn")
        || key.equals("majoralcn")
        || key.equals("majcn")
        || key.equals("cnmajor")) {
      return MappedTableColumnRole.MAJOR_CN;
    }
    if (key.equals("sample")
        || key.equals("sampleid")
        || key.equals("samplename")
        || key.equals("tumor")) {
      return MappedTableColumnRole.SAMPLE;
    }
    if (key.equals("totalcn")
        || key.equals("tcn")
        || key.equals("tcnem")
        || key.equals("copynumber")
        || key.equals("ntot")) {
      return MappedTableColumnRole.TOTAL_CN;
    }
    if (key.equals("value")
        || key.equals("score")
        || key.equals("signal")
        || key.equals("mean")
        || key.equals("avg")
        || key.equals("log2")
        || key.equals("logr")
        || key.equals("segmean")) {
      return MappedTableColumnRole.VALUE;
    }
    if (key.equals("conf") || key.equals("confidence") || key.equals("purity")) {
      return MappedTableColumnRole.SCORE;
    }
    return null;
  }
}
