package org.baseplayer.analysis.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.signal.SignalChannel;
import org.baseplayer.analysis.signal.SignalValueThresholds;
import org.baseplayer.analysis.signal.SignalValueType;
import org.baseplayer.io.table.MappedTableColumnRole;
import org.baseplayer.samples.SampleTrack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisLongTsvExportTest {

  @BeforeEach
  @AfterEach
  void clearSession() {
    AnalysisSession.get().clear();
  }

  @Test
  void signalMofaLongOmitsMissingAndWritesHeader() throws IOException {
    SampleTrack a = new SampleTrack("A");
    SampleTrack b = new SampleTrack("B");
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.putLocus("GENEA", "chr1", 100, 200);
    matrix.put("GENEA", a, 1.5);
    matrix.put("GENEA", b, 2.5);
    matrix.putLocus("GENEB", "chr1", 300, 400);
    matrix.put("GENEB", a, 0.5);
    // GENEB / B intentionally missing

    AnalysisSession session = AnalysisSession.get();
    session.replaceSignalGrid(matrix);

    StringWriter w = new StringWriter();
    AnalysisLongTsvExport.writeSignalMofaLong(w, session);
    String text = w.toString();
    String[] lines = text.split("\n", -1);
    assertTrue(text.startsWith(
        "sample\tfeature\tgene_name\tview\tvalue\tchrom\tstart\tend\n"));
    assertTrue(text.contains("A\tGENEA\tGENEA\tsignal\t1.5\tchr1\t100\t200\n"));
    assertTrue(text.contains("B\tGENEA\tGENEA\tsignal\t2.5\tchr1\t100\t200\n"));
    assertTrue(text.contains("A\tGENEB\tGENEB\tsignal\t0.5\tchr1\t300\t400\n"));
    assertFalse(text.contains("\nB\tGENEB\t"));
    int nonEmpty = 0;
    for (int i = 1; i < lines.length; i++) {
      if (!lines[i].isBlank()) {
        nonEmpty++;
      }
    }
    assertEquals(3, nonEmpty);
  }

  @Test
  void signalMofaLongMultiChannelUsesViewLabels() throws IOException {
    SampleTrack a = new SampleTrack("A");
    SignalChannel tpm = new SignalChannel(
        "tpm", "TPM", SignalValueType.EXPRESSION, MappedTableColumnRole.VALUE,
        SignalValueThresholds.defaultsFor(SignalValueType.EXPRESSION));
    SignalChannel reads = new SignalChannel(
        "numReads", "NumReads", SignalValueType.EXPRESSION, MappedTableColumnRole.SCORE,
        SignalValueThresholds.defaultsFor(SignalValueType.EXPRESSION));
    FeatureValueBundle bundle = new FeatureValueBundle(List.of(tpm, reads));
    bundle.putLocus("GENEA", "chr1", 10, 20);
    bundle.put("tpm", "GENEA", a, 3.0);
    bundle.put("numReads", "GENEA", a, 100.0);

    AnalysisSession.get().putAnnotation(
        AnalysisMode.SIGNAL,
        null,
        bundle.primaryMatrix(),
        null,
        bundle);

    StringWriter w = new StringWriter();
    AnalysisLongTsvExport.writeSignalMofaLong(w, AnalysisSession.get());
    String text = w.toString();
    assertTrue(text.contains("A\tGENEA\tGENEA\tTPM\t3.0\tchr1\t10\t20\n"));
    assertTrue(text.contains("A\tGENEA\tGENEA\tNumReads\t100.0\tchr1\t10\t20\n"));
  }

  @Test
  void comparisonEffectsIncludesModeAndSkipsAnnotation() throws IOException {
    GroupStatResult.Row row = new GroupStatResult.Row(
        "GENEA",
        "GENEA",
        "chr1",
        100,
        200,
        List.of(GroupStatResult.GroupSummary.basic(0, "G0", 2, 1.0, 1.0)),
        1.25,
        0.01,
        0.02,
        "Welch",
        "up");
    GroupStatResult comparison = new GroupStatResult(
        AnalysisMode.SIGNAL,
        List.of(0, 1),
        0,
        List.of(row),
        "Compared 1",
        "Welch");
    AnalysisSession.get().putResult(AnalysisMode.SIGNAL, comparison, null);

    GroupStatResult annotation = new GroupStatResult(
        AnalysisMode.SIGNAL,
        List.of(),
        null,
        List.of(row),
        "Annotated",
        "thr");
    AnalysisSession.get().putAnnotation(
        AnalysisMode.SIGNAL, annotation, new FeatureValueMatrix(), null);

    // putAnnotation clears SIGNAL result — put comparison again after
    AnalysisSession.get().putResult(AnalysisMode.SIGNAL, comparison, null);

    StringWriter w = new StringWriter();
    AnalysisLongTsvExport.writeComparisonEffects(w, AnalysisSession.get());
    String text = w.toString();
    assertTrue(text.startsWith(
        "feature\tgene_name\tchrom\tstart\tend\teffect\tp\tq\tdirection\ttest\tmode\n"));
    assertTrue(text.contains(
        "GENEA\tGENEA\tchr1\t100\t200\t1.25\t0.01\t0.02\tup\tWelch\tSIGNAL\n"));
    // Only one data row (annotation not exported here)
    int nonEmpty = 0;
    for (String line : text.split("\n")) {
      if (!line.isBlank()) {
        nonEmpty++;
      }
    }
    assertEquals(2, nonEmpty);
  }

  @Test
  void exportServiceWritesSelectedKinds(@TempDir Path dir) throws IOException {
    SampleTrack a = new SampleTrack("A");
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.putLocus("G", "chr2", 1, 2);
    matrix.put("G", a, 9.0);
    AnalysisSession.get().replaceSignalGrid(matrix);

    GroupStatResult.Row row = new GroupStatResult.Row(
        "G", "G", "chr2", 1, 2,
        List.of(GroupStatResult.GroupSummary.basic(0, "G0", 1, 9.0, 9.0)),
        2.0, 0.1, 0.2, "AUTO", "up");
    AnalysisSession.get().putResult(
        AnalysisMode.SIGNAL,
        new GroupStatResult(AnalysisMode.SIGNAL, List.of(0, 1), 0, List.of(row), "ok"),
        matrix);

    EnumSet<AnalysisExportKind> kinds = EnumSet.of(
        AnalysisExportKind.SIGNAL_MOFA_LONG,
        AnalysisExportKind.COMPARISON_EFFECTS);
    List<Path> written = AnalysisExportService.export(kinds, dir);
    assertEquals(2, written.size());
    assertTrue(Files.exists(dir.resolve("signal_mofa_long.tsv")));
    assertTrue(Files.exists(dir.resolve("comparison_effects.tsv")));
    String signal = Files.readString(dir.resolve("signal_mofa_long.tsv"));
    assertTrue(signal.contains("A\tG\tG\tsignal\t9.0\tchr2\t1\t2\n"));
  }

  @Test
  void availableKindsReflectSession() {
    assertTrue(AnalysisExportService.availableKinds().isEmpty());

    SampleTrack a = new SampleTrack("A");
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.put("G", a, 1.0);
    AnalysisSession.get().replaceSignalGrid(matrix);
    assertTrue(
        AnalysisExportService.availableKinds().contains(AnalysisExportKind.SIGNAL_MOFA_LONG));
    assertFalse(
        AnalysisExportService.availableKinds().contains(AnalysisExportKind.COMPARISON_EFFECTS));
  }

  @Test
  void signalAnnotationExportListsFilteredLohGenes() throws IOException {
    SampleTrack a = new SampleTrack("A");
    SampleTrack b = new SampleTrack("B");
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.setMissingDefault(0.0);
    matrix.putLocus("SHARED", "chr1", 100, 200);
    matrix.put("SHARED", a, 1.0);
    matrix.put("SHARED", b, 1.0);

    GroupStatResult annotated = org.baseplayer.analysis.signal.SignalAnnotator.annotate(
        AnalysisMode.SIGNAL,
        matrix,
        List.of(a, b),
        org.baseplayer.analysis.signal.SignalValueThresholds.defaultsFor(
            org.baseplayer.analysis.signal.SignalValueKind.TABLE_LOH),
        "Signal TABLE_LOH / MEAN");
    AnalysisSession.get().putAnnotation(
        AnalysisMode.SIGNAL, annotated, matrix,
        org.baseplayer.analysis.signal.SignalValueThresholds.defaultsFor(
            org.baseplayer.analysis.signal.SignalValueKind.TABLE_LOH));

    StringWriter w = new StringWriter();
    AnalysisLongTsvExport.writeSignalAnnotation(w, AnalysisSession.get());
    String text = w.toString();
    assertTrue(text.startsWith(
        "feature\tgene_name\tchrom\tstart\tend\tvalue\tn_signal\tsamples\tdirection\n"));
    assertTrue(text.contains("SHARED\tSHARED\tchr1\t100\t200\t"));
    assertTrue(text.contains("\t2\t"));
    assertTrue(
        AnalysisExportService.availableKinds().contains(AnalysisExportKind.SIGNAL_ANNOTATION));
  }
}
