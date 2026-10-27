package org.baseplayer.project;

/**
 * Read/write helpers for deprecated ClinVar filter fields kept for older project JSON.
 * Call sites use these instead of touching {@code @Deprecated} fields directly
 * (including from Control Files UI).
 */
public final class LegacyClinVarCompat {

  private LegacyClinVarCompat() {}

  @SuppressWarnings("deprecation")
  public static void writeControlCompat(
      ProjectDocument.ControlFilesSpec spec,
      boolean filterByClinVarSignificance,
      String clinVarSignificanceClasses) {
    if (spec == null) {
      return;
    }
    spec.filterRequireClinVar = filterByClinVarSignificance;
    spec.filterPathogenicClinVar = filterByClinVarSignificance
        && "PATHOGENIC".equals(clinVarSignificanceClasses);
  }

  @SuppressWarnings("deprecation")
  public static void writeClassCompat(ProjectDocument.ClassFilterSpec spec) {
    if (spec == null) {
      return;
    }
    spec.filterRequireClinVar = spec.filterByClinVarSignificance;
    spec.filterPathogenicClinVar = "PATHOGENIC".equals(spec.clinVarSignificanceClasses);
  }

  @SuppressWarnings("deprecation")
  public static boolean legacyRequire(ProjectDocument.ControlFilesSpec spec) {
    return spec != null && spec.filterRequireClinVar;
  }

  @SuppressWarnings("deprecation")
  public static boolean legacyPathogenic(ProjectDocument.ControlFilesSpec spec) {
    return spec != null && spec.filterPathogenicClinVar;
  }

  @SuppressWarnings("deprecation")
  public static boolean legacyRequire(ProjectDocument.ClassFilterSpec spec) {
    return spec != null && spec.filterRequireClinVar;
  }

  @SuppressWarnings("deprecation")
  public static boolean legacyPathogenic(ProjectDocument.ClassFilterSpec spec) {
    return spec != null && spec.filterPathogenicClinVar;
  }
}
