package org.baseplayer.io.table;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import org.baseplayer.utils.ChromosomeNames;

/** Loads genomic interval tables using a user/auto column mapping. */
public final class MappedTableReader {

  private MappedTableReader() {}

  /**
   * UTF-8 text reader; transparently gunzips {@code .gz} (and gzip-magic) files.
   * Caller must close the returned reader.
   */
  public static BufferedReader openTextReader(Path path) throws IOException {
    if (path == null) {
      throw new IllegalArgumentException("path required");
    }
    InputStream in = new BufferedInputStream(Files.newInputStream(path));
    try {
      if (looksGzip(path, in)) {
        in = new GZIPInputStream(in);
      }
      return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException ex) {
      try {
        in.close();
      } catch (IOException ignored) {
        // keep original
      }
      throw ex;
    }
  }

  /** True when the filename ends with {@code .gz} or the stream starts with gzip magic. */
  static boolean looksGzip(Path path, InputStream in) throws IOException {
    String name = path.getFileName() != null
        ? path.getFileName().toString().toLowerCase(Locale.ROOT)
        : "";
    if (name.endsWith(".gz") || name.endsWith(".gzip")) {
      return true;
    }
    in.mark(2);
    int b0 = in.read();
    int b1 = in.read();
    in.reset();
    return b0 == 0x1f && b1 == 0x8b;
  }

  /**
   * @param headers guessed header cells (first line), or synthetic {@code Col N} when no header
   * @param sampleRows following data rows for the preview table
   * @param firstLines first {@code maxDataRows} non-comment lines as-is (includes header line if present)
   */
  public record Preview(
      String[] headers,
      List<String[]> sampleRows,
      char delimiter,
      List<String[]> firstLines,
      boolean firstLineLooksLikeHeader) {}

  public static Preview preview(Path path, int maxDataRows) throws IOException {
    char delim = detectDelimiter(path);
    int limit = Math.max(1, maxDataRows);
    try (BufferedReader reader = openTextReader(path)) {
      List<String[]> firstLines = new ArrayList<>();
      String line;
      while (firstLines.size() < limit && (line = nextNonComment(reader)) != null) {
        firstLines.add(split(line, delim));
      }
      if (firstLines.isEmpty()) {
        return new Preview(new String[0], List.of(), delim, List.of(), true);
      }
      String[] first = firstLines.get(0);
      boolean looksLikeHeader = MappedTableColumnMapping.firstLineLooksLikeHeader(first);
      String[] headers;
      List<String[]> sampleRows;
      if (looksLikeHeader) {
        headers = first;
        sampleRows = firstLines.size() > 1
            ? firstLines.subList(1, firstLines.size())
            : List.of();
      } else {
        headers = syntheticHeaders(first.length);
        sampleRows = firstLines;
      }
      return new Preview(headers, sampleRows, delim, List.copyOf(firstLines), looksLikeHeader);
    }
  }

  public static String[] syntheticHeaders(int columnCount) {
    String[] headers = new String[Math.max(0, columnCount)];
    for (int i = 0; i < headers.length; i++) {
      headers[i] = "Col " + (i + 1);
    }
    return headers;
  }

  public static MappedTableData read(
      Path path,
      MappedTableColumnMapping mapping,
      String defaultSampleId) throws IOException {
    if (mapping == null || !mapping.hasRequired()) {
      throw new IllegalArgumentException("Column mapping missing required roles");
    }
    char delim = detectDelimiter(path);
    List<MappedTableRow> out = new ArrayList<>();
    String[] headers;
    try (BufferedReader reader = openTextReader(path)) {
      String firstLine = nextNonComment(reader);
      if (firstLine == null) {
        headers = new String[0];
      } else if (mapping.headerRow()) {
        headers = split(firstLine, delim);
        String line;
        while ((line = nextNonComment(reader)) != null) {
          MappedTableRow row = parseRow(split(line, delim), mapping, defaultSampleId);
          if (row != null) {
            out.add(row);
          }
        }
      } else {
        // No header — first line is data; keep synthetic names for display.
        String[] firstCols = split(firstLine, delim);
        headers = syntheticHeaders(firstCols.length);
        MappedTableRow first = parseRow(firstCols, mapping, defaultSampleId);
        if (first != null) {
          out.add(first);
        }
        String line;
        while ((line = nextNonComment(reader)) != null) {
          MappedTableRow row = parseRow(split(line, delim), mapping, defaultSampleId);
          if (row != null) {
            out.add(row);
          }
        }
      }
    }
    String name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
    return new MappedTableData(path, name, mapping, headers, out);
  }

  public static char detectDelimiter(Path path) throws IOException {
    try (BufferedReader reader = openTextReader(path)) {
      String line = nextNonComment(reader);
      if (line == null) {
        return '\t';
      }
      int tabs = count(line, '\t');
      int commas = count(line, ',');
      int semis = count(line, ';');
      if (tabs >= commas && tabs >= semis && tabs > 0) {
        return '\t';
      }
      if (commas >= semis && commas > 0) {
        return ',';
      }
      if (semis > 0) {
        return ';';
      }
      return '\t';
    }
  }

  public static String fileStem(Path path) {
    if (path == null || path.getFileName() == null) {
      return "sample";
    }
    String name = path.getFileName().toString();
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".gz") || lower.endsWith(".gzip")) {
      int cut = lower.endsWith(".gzip") ? name.length() - 5 : name.length() - 3;
      name = name.substring(0, cut);
      lower = name.toLowerCase(Locale.ROOT);
    }
    int dot = name.lastIndexOf('.');
    if (dot > 0) {
      name = name.substring(0, dot);
      lower = name.toLowerCase(Locale.ROOT);
    }
    for (String tag : List.of(".segments", ".ascat", ".allelecn", ".cn", ".table")) {
      int idx = lower.indexOf(tag);
      if (idx > 0) {
        return name.substring(0, idx);
      }
    }
    return name;
  }

  static MappedTableRow parseRow(
      String[] cols,
      MappedTableColumnMapping mapping,
      String defaultSampleId) {
    if (cols == null || mapping == null) {
      return null;
    }
    String chrom = cell(cols, mapping.indexOf(MappedTableColumnRole.CHROM));
    String startRaw = cell(cols, mapping.indexOf(MappedTableColumnRole.START));
    String endRaw = cell(cols, mapping.indexOf(MappedTableColumnRole.END));
    if (chrom == null || startRaw == null || endRaw == null) {
      return null;
    }
    long start;
    long end;
    try {
      start = Math.round(Double.parseDouble(startRaw.trim()));
      end = Math.round(Double.parseDouble(endRaw.trim()));
    } catch (NumberFormatException ex) {
      return null;
    }
    if (mapping.startZeroBased()) {
      start = start + 1;
    }
    if (end < start) {
      return null;
    }
    String sample = cell(cols, mapping.indexOf(MappedTableColumnRole.SAMPLE));
    if (sample == null || sample.isBlank()) {
      sample = defaultSampleId;
    }
    Double value = parseOptionalDouble(cell(cols, mapping.indexOf(MappedTableColumnRole.VALUE)));
    Double minor = parseOptionalDouble(cell(cols, mapping.indexOf(MappedTableColumnRole.MINOR_CN)));
    Double major = parseOptionalDouble(cell(cols, mapping.indexOf(MappedTableColumnRole.MAJOR_CN)));
    Double total = parseOptionalDouble(cell(cols, mapping.indexOf(MappedTableColumnRole.TOTAL_CN)));
    Double score = parseOptionalDouble(cell(cols, mapping.indexOf(MappedTableColumnRole.SCORE)));
    return new MappedTableRow(
        ChromosomeNames.strip(chrom.trim()),
        start,
        end,
        sample,
        value,
        minor,
        major,
        total,
        score);
  }

  private static String nextNonComment(BufferedReader reader) throws IOException {
    String line;
    while ((line = reader.readLine()) != null) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
        continue;
      }
      return line;
    }
    return null;
  }

  private static String[] split(String line, char delim) {
    if (delim == '\t') {
      return line.split("\t", -1);
    }
    if (delim == ';') {
      return line.split(";", -1);
    }
    return line.split(",", -1);
  }

  private static String cell(String[] cols, Integer index) {
    if (cols == null || index == null || index < 0 || index >= cols.length) {
      return null;
    }
    return cols[index];
  }

  private static Double parseOptionalDouble(String raw) {
    if (raw == null || raw.isBlank() || ".".equals(raw.trim()) || "NA".equalsIgnoreCase(raw.trim())) {
      return null;
    }
    try {
      return Double.parseDouble(raw.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static int count(String s, char c) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == c) {
        n++;
      }
    }
    return n;
  }
}
