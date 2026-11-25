package org.baseplayer.analysis.export;

/**
 * Selectable Signal / comparison export products.
 */
public enum AnalysisExportKind {
  SIGNAL_MOFA_LONG(
      "Signal values (MOFA long TSV)",
      "sample × feature × view values for MOFA2 / multi-omics tools",
      "signal_mofa_long.tsv",
      "TSV",
      "*.tsv"),
  SIGNAL_ANNOTATION(
      "Signals listing (TSV)",
      "filtered Signals table rows (e.g. genes with LOH in ≥ N samples)",
      "signal_annotation.tsv",
      "TSV",
      "*.tsv"),
  COMPARISON_EFFECTS(
      "Comparison effects (TSV)",
      "feature, effect (fold-change / Δ), p, q per analysis mode",
      "comparison_effects.tsv",
      "TSV",
      "*.tsv"),
  COMPARISON_WIDE_EXCEL(
      "Comparison wide Excel",
      "full group-stat workbook (and annotation sheet when present)",
      "comparison_stats.xlsx",
      "Excel Workbook",
      "*.xlsx");

  private final String label;
  private final String description;
  private final String defaultFileName;
  private final String filterDescription;
  private final String filterExtension;

  AnalysisExportKind(
      String label,
      String description,
      String defaultFileName,
      String filterDescription,
      String filterExtension) {
    this.label = label;
    this.description = description;
    this.defaultFileName = defaultFileName;
    this.filterDescription = filterDescription;
    this.filterExtension = filterExtension;
  }

  public String label() {
    return label;
  }

  public String description() {
    return description;
  }

  public String defaultFileName() {
    return defaultFileName;
  }

  public String filterDescription() {
    return filterDescription;
  }

  public String filterExtension() {
    return filterExtension;
  }
}
