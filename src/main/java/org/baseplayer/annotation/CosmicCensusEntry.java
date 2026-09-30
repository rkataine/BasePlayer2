package org.baseplayer.annotation;

/**
 * COSMIC Cancer Gene Census entry with all available information.
 */
public record CosmicCensusEntry(
    String geneSymbol,
    String name,
    String entrezGeneId,
    String genomeLocation,
    String tier,
    boolean hallmark,
    String chrBand,
    boolean somatic,
    boolean germline,
    String tumourTypesSomatic,
    String tumourTypesGermline,
    String cancerSyndrome,
    String tissueType,
    String molecularGenetics,
    String roleInCancer,
    String mutationTypes,
    String translocationPartner,
    String otherGermlineMut,
    String otherSyndrome,
    String synonyms
) {
  
  /**
   * Check if this is a Tier 1 gene (strongest evidence).
   */
  public boolean isTier1() {
    return "1".equals(tier);
  }
  
  /**
   * Check if this gene has any germline association.
   */
  public boolean hasGermlineAssociation() {
    return germline || (tumourTypesGermline != null && !tumourTypesGermline.isEmpty());
  }
  
  /**
   * Check if this gene is associated with a cancer syndrome.
   */
  public boolean hasCancerSyndrome() {
    return cancerSyndrome != null && !cancerSyndrome.isEmpty();
  }

  /**
   * True when Role in Cancer lists TSG (tumor suppressor), including dual-role genes.
   */
  public boolean isTumorSuppressor() {
    return roleContains("tsg");
  }

  /**
   * True when Role in Cancer lists oncogene, including dual-role genes.
   */
  public boolean isOncogene() {
    return roleContains("oncogene");
  }

  private boolean roleContains(String token) {
    if (roleInCancer == null || roleInCancer.isBlank()) {
      return false;
    }
    String lower = roleInCancer.toLowerCase();
    int idx = 0;
    while (idx < lower.length()) {
      int found = lower.indexOf(token, idx);
      if (found < 0) {
        return false;
      }
      boolean startOk = found == 0 || !Character.isLetterOrDigit(lower.charAt(found - 1));
      int end = found + token.length();
      boolean endOk = end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
      if (startOk && endOk) {
        return true;
      }
      idx = found + 1;
    }
    return false;
  }
}
