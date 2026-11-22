package org.baseplayer.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.AnnotationLoader;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.genome.gene.GeneLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GeneOverlapIndexTest {

  @BeforeEach
  @AfterEach
  void clearGenes() {
    AnnotationData.clearGenes();
  }

  @Test
  void stripEnsemblVersionRemovesTrailingDigits() {
    assertEquals("ENSG00000141510", AnnotationLoader.stripEnsemblVersion("ENSG00000141510.15"));
    assertEquals("ENSG00000141510", AnnotationLoader.stripEnsemblVersion("ENSG00000141510"));
    assertEquals("TP53", AnnotationLoader.stripEnsemblVersion("TP53"));
  }

  @Test
  void resolveByNamePrefersSymbolForVersionedEnsg() {
    GeneLocation loc = new GeneLocation("17", 7_668_421L, 7_687_538L);
    Gene tp53 = new Gene(
        "17", loc.start(), loc.end(), "TP53", "ENSG00000141510",
        "+", "protein_coding", "tumor protein p53", List.of(), List.of());
    Map<String, List<Gene>> byChrom = Map.of("17", List.of(tp53));
    Map<String, GeneLocation> search = new HashMap<>();
    AnnotationLoader.putGeneSearchKeys(search, "TP53", "ENSG00000141510", loc);
    AnnotationData.replaceGeneAnnotation(
        byChrom, search, Map.of("tp53", "protein_coding"), List.of("TP53"));

    Gene resolved = GeneOverlapIndex.resolveByName("ENSG00000141510.15");
    assertNotNull(resolved);
    assertEquals("TP53", resolved.name());
    assertEquals("ENSG00000141510", resolved.id());
  }

  @Test
  void resolveByNameReturnsNullWhenNoCatalogGene() {
    GeneLocation loc = new GeneLocation("1", 10, 20);
    Map<String, GeneLocation> search = new HashMap<>();
    search.put("ensg1", loc);
    AnnotationData.replaceGeneAnnotation(
        Map.of("1", List.of()), search, Map.of(), List.of());

    assertNull(GeneOverlapIndex.resolveByName("ENSG1"));
  }
}
