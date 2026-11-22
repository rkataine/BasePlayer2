package org.baseplayer.analysis.signal;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import org.baseplayer.samples.SampleTag;
import org.baseplayer.variant.VariantFilter;

/**
 * Sample Comparison settings for Signal events (gene / flanking / tag roles / shared N).
 * Parallel to the Sample Comparison fields on {@link VariantFilter}, owned by Signal.
 */
public final class SignalComparisonSettings {

  private int minSharedSamples = 1;
  private int maxSharedSamples = Integer.MAX_VALUE;
  private boolean geneLevel = true;
  private int comparisonWindowBp = 0;
  private VariantFilter.PresentMatchMode presentMatchMode = VariantFilter.PresentMatchMode.ALL;
  private Map<SampleTag, VariantFilter.GroupRole> tagRoles = new EnumMap<>(SampleTag.class);

  public int getMinSharedSamples() {
    return minSharedSamples;
  }

  public void setMinSharedSamples(int minSharedSamples) {
    this.minSharedSamples = Math.max(1, minSharedSamples);
  }

  public int getMaxSharedSamples() {
    return maxSharedSamples;
  }

  public void setMaxSharedSamples(int maxSharedSamples) {
    this.maxSharedSamples = maxSharedSamples < 1 ? Integer.MAX_VALUE : maxSharedSamples;
  }

  public boolean isGeneLevel() {
    return geneLevel;
  }

  public void setGeneLevel(boolean geneLevel) {
    this.geneLevel = geneLevel;
  }

  public int getComparisonWindowBp() {
    return comparisonWindowBp;
  }

  public void setComparisonWindowBp(int comparisonWindowBp) {
    this.comparisonWindowBp = Math.max(0, comparisonWindowBp);
  }

  public VariantFilter.PresentMatchMode getPresentMatchMode() {
    return presentMatchMode;
  }

  public void setPresentMatchMode(VariantFilter.PresentMatchMode presentMatchMode) {
    this.presentMatchMode = presentMatchMode != null
        ? presentMatchMode
        : VariantFilter.PresentMatchMode.ALL;
  }

  public Map<SampleTag, VariantFilter.GroupRole> getTagRoles() {
    return Collections.unmodifiableMap(tagRoles);
  }

  public void setTagRoles(Map<SampleTag, VariantFilter.GroupRole> tagRoles) {
    this.tagRoles = new EnumMap<>(SampleTag.class);
    if (tagRoles != null) {
      for (Map.Entry<SampleTag, VariantFilter.GroupRole> e : tagRoles.entrySet()) {
        if (e.getKey() != null
            && e.getValue() != null
            && e.getValue() != VariantFilter.GroupRole.IGNORE) {
          this.tagRoles.put(e.getKey(), e.getValue());
        }
      }
    }
  }

  /** True when any constraint beyond the default “≥1 sample” floor is set. */
  public boolean hasActiveConstraints() {
    if (minSharedSamples > 1) {
      return true;
    }
    if (maxSharedSamples != Integer.MAX_VALUE) {
      return true;
    }
    if (!geneLevel && comparisonWindowBp > 0) {
      return true;
    }
    return !tagRoles.isEmpty();
  }

  public SignalComparisonSettings copy() {
    SignalComparisonSettings c = new SignalComparisonSettings();
    c.minSharedSamples = minSharedSamples;
    c.maxSharedSamples = maxSharedSamples;
    c.geneLevel = geneLevel;
    c.comparisonWindowBp = comparisonWindowBp;
    c.presentMatchMode = presentMatchMode;
    c.tagRoles = tagRoles.isEmpty() ? new EnumMap<>(SampleTag.class) : new EnumMap<>(tagRoles);
    return c;
  }
}
