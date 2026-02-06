package org.baseplayer.annotation;

import java.util.List;

/**
 * Represents a transcript with its exons and MANE status.
 */
public record Transcript(
    String id,
    String name,
    long start,
    long end,
    String biotype,
    boolean isManeSelect,
    boolean isManeClinic,
    List<long[]> exons
) {
  /** Check if this is any MANE transcript */
  public boolean isMane() {
    return isManeSelect || isManeClinic;
  }
}
