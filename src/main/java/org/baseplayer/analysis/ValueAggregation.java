package org.baseplayer.analysis;

/** How multiple feature values that map to one gene/site are collapsed per sample. */
public enum ValueAggregation {
  MEAN,
  MAX,
  MEDIAN
}
