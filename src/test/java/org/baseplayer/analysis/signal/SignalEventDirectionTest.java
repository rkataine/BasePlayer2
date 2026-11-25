package org.baseplayer.analysis.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.baseplayer.io.table.MappedTableColumnRole;
import org.junit.jupiter.api.Test;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.groupstat.GroupStatResult;

class SignalEventDirectionTest {

  @Test
  void diploidIsNone() {
    assertEquals(SignalEventDirection.NONE, SignalEventDirection.fromAlleleCn(1.0, 1.0));
  }

  @Test
  void copyNeutralLoh() {
    assertEquals(SignalEventDirection.LOH, SignalEventDirection.fromAlleleCn(0.0, 2.0));
  }

  @Test
  void gainFromElevatedTotal() {
    assertEquals(SignalEventDirection.GAIN, SignalEventDirection.fromAlleleCn(1.0, 2.0));
  }

  @Test
  void hemizygousZeroOneIsLoh() {
    // Same as LohFromAlleleCn.binary() / aggregate band: minor lost, major remains.
    assertEquals(SignalEventDirection.LOH, SignalEventDirection.fromAlleleCn(0.0, 1.0));
  }

  @Test
  void homozygousDeletionIsLoss() {
    assertEquals(SignalEventDirection.LOSS, SignalEventDirection.fromAlleleCn(0.0, 0.0));
  }

  @Test
  void nullsAreNone() {
    assertEquals(SignalEventDirection.NONE, SignalEventDirection.fromAlleleCn(null, null));
  }

  @Test
  void resultHasAlleleCnDetectsChannels() {
    SignalChannel minor = new SignalChannel(
        "minor", "Minor", SignalValueType.ALLELE_CN,
        MappedTableColumnRole.MINOR_CN, null);
    SignalChannel major = new SignalChannel(
        "major", "Major", SignalValueType.ALLELE_CN,
        MappedTableColumnRole.MAJOR_CN, null);
    GroupStatResult with = new GroupStatResult(
        AnalysisMode.SIGNAL, List.of(), null, List.of(), "ann", "strip",
        List.of(minor, major), java.util.Map.of());
    assertTrue(SignalEventDirection.resultHasAlleleCn(with));

    SignalChannel raw = new SignalChannel(
        "v", "Value", SignalValueType.RAW, null, null);
    GroupStatResult without = new GroupStatResult(
        AnalysisMode.SIGNAL, List.of(), null, List.of(), "ann", "strip",
        List.of(raw), java.util.Map.of());
    assertFalse(SignalEventDirection.resultHasAlleleCn(without));
  }

  @Test
  void fromResultRowUsesMinorMajorChannels() {
    SignalChannel minor = new SignalChannel(
        "minor", "Minor", SignalValueType.ALLELE_CN,
        MappedTableColumnRole.MINOR_CN, null);
    SignalChannel major = new SignalChannel(
        "major", "Major", SignalValueType.ALLELE_CN,
        MappedTableColumnRole.MAJOR_CN, null);
    GroupStatResult.Row row = new GroupStatResult.Row(
        "g1", "GENE", "chr1", 1L, 100L,
        List.of(), null, null, null, null, null);
    GroupStatResult result = new GroupStatResult(
        AnalysisMode.SIGNAL, List.of(), null, List.of(row), "ann", "strip",
        List.of(minor, major),
        java.util.Map.of("g1", java.util.Map.of("minor", 0.0, "major", 2.0)));
    assertEquals(SignalEventDirection.LOH, SignalEventDirection.fromResultRow(result, row));
  }
}
