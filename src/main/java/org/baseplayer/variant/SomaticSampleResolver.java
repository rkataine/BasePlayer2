package org.baseplayer.variant;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.baseplayer.MainApp;
import org.baseplayer.components.SomaticSampleDialogs;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.project.ProjectSessionState.SomaticSampleMode;
import org.baseplayer.services.LoadingManager;

import javafx.application.Platform;

/**
 * Resolves which sample column(s) to map from a 2-column VCF before tracks are created.
 * Uses project somatic mode, name/call heuristics on {@link VariantLoader}, and optional
 * FX dialogs for ambiguous cases.
 */
public final class SomaticSampleResolver {

  private SomaticSampleResolver() {}

  /** Shared state for one multi-file open so prompts happen once. */
  public static final class BatchContext {
    /** Remembered tumor-pick rule for remaining ambiguous files in this open. */
    public BatchTumorRule batchTumorRule = BatchTumorRule.NONE;
  }

  public enum BatchTumorRule {
    NONE,
    /** Prefer the column whose name has an NK/TK-style {@code tk} token. */
    PREFER_TK,
    /** Prefer the column with more non-ref genotypes from the call probe. */
    PREFER_MORE_CALLS,
    PREFER_FIRST_COLUMN,
    PREFER_SECOND_COLUMN
  }

  /**
   * @param allowDialogs false for session restore (heuristics + saved preference only)
   * @param batch optional; when non-null, somatic-mode and tumor-pick answers apply across files
   */
  public static void resolve(VariantLoader loader, boolean allowDialogs, BatchContext batch) {
    if (loader == null) {
      return;
    }
    List<String> samples = loader.getVcfSampleNames();
    if (samples == null || samples.size() != 2) {
      return;
    }

    ProjectSessionState session = ProjectSessionState.get();
    SomaticSampleMode mode = session.getSomaticSampleMode();

    if (mode == SomaticSampleMode.UNSET) {
      if (!allowDialogs) {
        // Restore: keep constructor heuristics; do not invent a preference.
        return;
      }
      boolean somatic = askSomaticMode(samples.get(0), samples.get(1), loader);
      mode = somatic ? SomaticSampleMode.SOMATIC : SomaticSampleMode.NOT_SOMATIC;
      session.setSomaticSampleMode(mode);
    }

    if (mode == SomaticSampleMode.NOT_SOMATIC) {
      loader.clearDetectedNormalSample();
      return;
    }

    // SOMATIC
    if (loader.getDetectedNormalSample() != null) {
      return;
    }

    loader.redetectSomaticNormal();
    if (loader.getDetectedNormalSample() != null) {
      return;
    }

    if (batch != null && batch.batchTumorRule != BatchTumorRule.NONE) {
      applyBatchRule(loader, batch.batchTumorRule);
      return;
    }

    if (!allowDialogs) {
      return;
    }

    Map<String, Integer> callCounts = loader.probeNonRefCallCounts(4000);
    String defaultTumor = defaultTumorGuess(samples, callCounts);
    SomaticSampleDialogs.TumorPickResult pick = askTumorSample(
        samples.get(0), samples.get(1), defaultTumor, callCounts);
    if (pick == null || pick.tumorSample() == null) {
      // Cancelled: still skip a normal when the probe/default guess is clear.
      if (defaultTumor != null) {
        loader.applyTumorSample(defaultTumor);
      }
      return;
    }
    loader.applyTumorSample(pick.tumorSample());
    if (batch != null && pick.applyToRestOfBatch()) {
      batch.batchTumorRule = inferBatchRule(samples, pick.tumorSample(), callCounts);
    }
  }

  private static boolean askSomaticMode(String sample1, String sample2, VariantLoader loader) {
    String fileHint = loader.getVcfPath() != null
        ? loader.getVcfPath().getFileName().toString()
        : "this VCF";
    Boolean result = runOnFxAndWait(() ->
        SomaticSampleDialogs.askSomaticMode(MainApp.stage, fileHint, sample1, sample2));
    return Boolean.TRUE.equals(result);
  }

  private static SomaticSampleDialogs.TumorPickResult askTumorSample(
      String sample1,
      String sample2,
      String defaultTumor,
      Map<String, Integer> callCounts) {
    return runOnFxAndWait(() ->
        SomaticSampleDialogs.askTumorSample(
            MainApp.stage, sample1, sample2, defaultTumor, callCounts));
  }

  private static String defaultTumorGuess(List<String> samples, Map<String, Integer> callCounts) {
    if (samples == null || samples.size() != 2) {
      return null;
    }
    String a = samples.get(0);
    String b = samples.get(1);
    int ca = callCounts != null ? callCounts.getOrDefault(a, 0) : 0;
    int cb = callCounts != null ? callCounts.getOrDefault(b, 0) : 0;
    if (ca > cb) {
      return a;
    }
    if (cb > ca) {
      return b;
    }
    String al = a.toLowerCase();
    String bl = b.toLowerCase();
    if (VariantLoader.hasToken(al, "tk") && !VariantLoader.hasToken(bl, "tk")) {
      return a;
    }
    if (VariantLoader.hasToken(bl, "tk") && !VariantLoader.hasToken(al, "tk")) {
      return b;
    }
    return a;
  }

  private static BatchTumorRule inferBatchRule(
      List<String> samples, String tumor, Map<String, Integer> callCounts) {
    if (samples == null || samples.size() != 2 || tumor == null) {
      return BatchTumorRule.NONE;
    }
    String other = samples.get(0).equals(tumor) ? samples.get(1) : samples.get(0);
    String tumorLower = tumor.toLowerCase();
    String otherLower = other.toLowerCase();
    if (VariantLoader.hasToken(tumorLower, "tk")
        && VariantLoader.hasToken(otherLower, "nk")
        && !VariantLoader.hasToken(tumorLower, "nk")) {
      return BatchTumorRule.PREFER_TK;
    }
    int ct = callCounts != null ? callCounts.getOrDefault(tumor, 0) : 0;
    int co = callCounts != null ? callCounts.getOrDefault(other, 0) : 0;
    if (ct > co) {
      return BatchTumorRule.PREFER_MORE_CALLS;
    }
    return samples.get(0).equals(tumor)
        ? BatchTumorRule.PREFER_FIRST_COLUMN
        : BatchTumorRule.PREFER_SECOND_COLUMN;
  }

  private static void applyBatchRule(VariantLoader loader, BatchTumorRule rule) {
    List<String> samples = loader.getVcfSampleNames();
    if (samples.size() != 2 || rule == null || rule == BatchTumorRule.NONE) {
      return;
    }
    String tumor = switch (rule) {
      case PREFER_FIRST_COLUMN -> samples.get(0);
      case PREFER_SECOND_COLUMN -> samples.get(1);
      case PREFER_TK -> {
        String a = samples.get(0);
        String b = samples.get(1);
        boolean aTk = VariantLoader.hasToken(a.toLowerCase(), "tk");
        boolean bTk = VariantLoader.hasToken(b.toLowerCase(), "tk");
        if (aTk && !bTk) {
          yield a;
        }
        if (bTk && !aTk) {
          yield b;
        }
        yield defaultTumorGuess(samples, loader.probeNonRefCallCounts(4000));
      }
      case PREFER_MORE_CALLS ->
          defaultTumorGuess(samples, loader.probeNonRefCallCounts(4000));
      case NONE -> null;
    };
    if (tumor != null) {
      loader.applyTumorSample(tumor);
    }
  }

  private static <T> T runOnFxAndWait(Supplier<T> action) {
    if (Platform.isFxApplicationThread()) {
      return runWithLoadingPopupSuppressed(action);
    }
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<T> ref = new AtomicReference<>();
    Platform.runLater(() -> {
      try {
        ref.set(runWithLoadingPopupSuppressed(action));
      } finally {
        latch.countDown();
      }
    });
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return ref.get();
  }

  /**
   * Loading UI is a {@code Popup} that can stack above modal stages; hide it for
   * the duration of the somatic dialog so the user can interact.
   */
  private static <T> T runWithLoadingPopupSuppressed(Supplier<T> action) {
    LoadingManager loading = LoadingManager.get();
    loading.beginModalSuppress();
    try {
      return action.get();
    } finally {
      loading.endModalSuppress();
    }
  }
}
