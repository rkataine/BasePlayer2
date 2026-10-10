package org.baseplayer.features.motif;

import java.util.Objects;

/**
 * Position frequency matrix (counts) for one motif: 4 rows (A,C,G,T) × length.
 */
public final class MotifMatrix {

  private final String id;
  private final String name;
  /** counts[base][pos] with base order A=0, C=1, G=2, T=3. */
  private final int[][] counts;

  public MotifMatrix(String id, String name, int[][] counts) {
    this.id = Objects.requireNonNull(id, "id");
    this.name = name != null && !name.isBlank() ? name : id;
    if (counts == null || counts.length != 4) {
      throw new IllegalArgumentException("counts must be 4×L");
    }
    int len = counts[0].length;
    for (int b = 1; b < 4; b++) {
      if (counts[b] == null || counts[b].length != len) {
        throw new IllegalArgumentException("counts rows must share length");
      }
    }
    this.counts = new int[4][len];
    for (int b = 0; b < 4; b++) {
      System.arraycopy(counts[b], 0, this.counts[b], 0, len);
    }
  }

  public String id() {
    return id;
  }

  public String name() {
    return name;
  }

  public int length() {
    return counts[0].length;
  }

  /** Defensive copy of count matrix. */
  public int[][] counts() {
    int len = length();
    int[][] copy = new int[4][len];
    for (int b = 0; b < 4; b++) {
      System.arraycopy(counts[b], 0, copy[b], 0, len);
    }
    return copy;
  }

  /** Direct read (do not mutate). */
  public int count(int base, int pos) {
    return counts[base][pos];
  }

  /** Reverse-complement count matrix (A↔T, C↔G, reverse positions). */
  public MotifMatrix reverseComplement() {
    int len = length();
    int[][] rc = new int[4][len];
    for (int i = 0; i < len; i++) {
      int j = len - 1 - i;
      rc[0][i] = counts[3][j]; // A ← T
      rc[1][i] = counts[2][j]; // C ← G
      rc[2][i] = counts[1][j]; // G ← C
      rc[3][i] = counts[0][j]; // T ← A
    }
    return new MotifMatrix(id, name, rc);
  }

  public String displayLabel() {
    if (name.equals(id)) {
      return id;
    }
    return id + "  " + name;
  }
}
