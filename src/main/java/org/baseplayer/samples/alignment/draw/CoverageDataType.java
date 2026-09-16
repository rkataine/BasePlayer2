package org.baseplayer.samples.alignment.draw;

/**
 * Type of coverage data needed for a read color mode.
 */
public enum CoverageDataType {
  /** No special coverage data needed (just mismatches). */
  NONE,
  /** Numeric signal tag data (UC, UD, UL, etc.). */
  SIGNAL,
  /** Base modification data (MM/ML tags). */
  MODIFICATION,
  /** Base quality scores. */
  QUALITY
}
