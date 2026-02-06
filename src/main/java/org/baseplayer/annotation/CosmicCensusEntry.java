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
}
