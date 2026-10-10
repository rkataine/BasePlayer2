package org.baseplayer.io.readers;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.baseplayer.features.motif.MotifMatrix;

/**
 * Parses multi-matrix JASPAR PFM text ({@code >ID\tName} + {@code A [ ... ]}}).
 * Ported from BasePlayer v1 {@code Updater} motif loading.
 */
public final class JasparPfmReader {

  private JasparPfmReader() {}

  public static List<MotifMatrix> read(Path path) throws IOException {
    if (path == null || !Files.isRegularFile(path)) {
      throw new IOException("JASPAR PFM file not found: " + path);
    }
    List<MotifMatrix> matrices = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith(" ")) {
          continue;
        }
        if (!line.startsWith(">")) {
          continue;
        }
        MotifMatrix matrix = readOneMatrix(line, reader);
        if (matrix != null) {
          matrices.add(matrix);
        }
      }
    }
    if (matrices.isEmpty()) {
      throw new IOException("No JASPAR matrices found in: " + path);
    }
    return matrices;
  }

  private static MotifMatrix readOneMatrix(String headerLine, BufferedReader reader)
      throws IOException {
    String[] header = headerLine.trim().split("\\s+");
    if (header.length < 1 || header[0].length() < 2) {
      return null;
    }
    String id = header[0].substring(1);
    String name = header.length > 1 ? header[1] : id;

    int[][] counts = null;
    int row = 0;
    String line;
    while (row < 4 && (line = reader.readLine()) != null) {
      line = line.trim();
      if (line.isEmpty() || line.startsWith(" ") || line.startsWith(">")) {
        // Unexpected; let outer loop re-handle a new header if needed.
        break;
      }
      int open = line.indexOf('[');
      int close = line.indexOf(']');
      if (open < 0 || close <= open) {
        continue;
      }
      String[] values = line.substring(open + 1, close).trim().split("\\s+");
      if (counts == null) {
        counts = new int[4][values.length];
      } else if (values.length != counts[0].length) {
        throw new IOException(
            "Inconsistent column count for motif " + id + " at row " + row);
      }
      for (int j = 0; j < values.length; j++) {
        counts[row][j] = Integer.parseInt(values[j]);
      }
      row++;
    }
    if (counts == null || row != 4) {
      return null;
    }
    return new MotifMatrix(id, name, counts);
  }
}
