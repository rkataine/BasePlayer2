package org.baseplayer.io.dragen;

/** Analysis products selectable when opening a DRAGEN output folder. */
public enum DragenAnalysisKind {
  GERMLINE_VARIANTS("Germline variants"),
  SOMATIC_POINT_MUTATIONS("Somatic point mutations"),
  CNV("CNV analysis"),
  SV("SV analysis"),
  MULTIMODAL("Multimodal / other signals");

  private final String label;

  DragenAnalysisKind(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
