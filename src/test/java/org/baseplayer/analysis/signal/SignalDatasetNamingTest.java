package org.baseplayer.analysis.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SignalDatasetNamingTest {

  @Test
  void salmonSfSuggestsRna() {
    Optional<String> name = SignalDatasetNaming.suggest(
        List.of(Path.of("/data/sample/quant.sf")),
        SignalValueKind.TABLE_VALUES,
        List.of());
    assertEquals(Optional.of("RNA"), name);
  }

  @Test
  void ascatPathSuggestsAlleleCn() {
    Optional<String> name = SignalDatasetNaming.suggest(
        List.of(Path.of("/data/tumor.ascat.tsv")),
        SignalValueKind.TABLE_VALUES,
        List.of());
    assertEquals(Optional.of("Allele CN"), name);
  }

  @Test
  void legacyTableLohKindSuggestsLoh() {
    Optional<String> name = SignalDatasetNaming.suggest(
        List.of(),
        SignalValueKind.TABLE_LOH,
        List.of());
    assertEquals(Optional.of("LOH"), name);
  }

  @Test
  void alleleCnChannelsSuggestAlleleCn() {
    SignalChannel minor = new SignalChannel(
        "minor", "Minor", SignalValueType.ALLELE_CN, null, new SignalValueThresholds());
    SignalChannel major = new SignalChannel(
        "major", "Major", SignalValueType.ALLELE_CN, null, new SignalValueThresholds());
    Optional<String> name = SignalDatasetNaming.suggest(
        List.of(),
        SignalValueKind.TABLE_VALUES,
        List.of(minor, major));
    assertEquals(Optional.of("Allele CN"), name);
  }

  @Test
  void genericTablePrompts() {
    Optional<String> name = SignalDatasetNaming.suggest(
        List.of(Path.of("/data/mystery_values.tsv")),
        SignalValueKind.TABLE_VALUES,
        List.of());
    assertTrue(name.isEmpty());
  }

  @Test
  void expressionChannelsSuggestRna() {
    SignalChannel tpm = new SignalChannel(
        "tpm", "TPM", SignalValueType.EXPRESSION, null, new SignalValueThresholds());
    Optional<String> name = SignalDatasetNaming.suggest(
        List.of(),
        SignalValueKind.TABLE_VALUES,
        List.of(tpm));
    assertEquals(Optional.of("RNA"), name);
  }
}
