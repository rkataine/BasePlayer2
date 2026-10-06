package org.baseplayer.components;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.MainApp;
import org.baseplayer.ui.theme.AppTheme;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Window;

/**
 * Session log of sample open failures (missing index, broken file, …).
 * Entries accumulate so Tools → Sample open errors can reopen the list.
 * Display is grouped by failure reason, then sample paths.
 * Auto-popup is non-modal and must not run mid-load (that blocked FX / variant load).
 */
public final class SampleOpenFailuresDialog {

  private static final Object LOCK = new Object();
  private static final List<FailureEntry> SESSION_LOG = new ArrayList<>();
  private static int pendingToShow;
  private static FailureListDialog openDialog;

  private final List<FailureEntry> entries = new ArrayList<>();

  private record FailureEntry(String path, String reason) {}

  private SampleOpenFailuresDialog() {}

  public static SampleOpenFailuresDialog create() {
    return new SampleOpenFailuresDialog();
  }

  public SampleOpenFailuresDialog add(File file, String reason) {
    String path = file != null ? file.getAbsolutePath() : "(unknown)";
    return add(path, reason);
  }

  public SampleOpenFailuresDialog add(Path path, String reason) {
    String p = path != null ? path.toAbsolutePath().toString() : "(unknown)";
    return add(p, reason);
  }

  public SampleOpenFailuresDialog add(String path, String reason) {
    String location = path != null && !path.isBlank() ? path : "(unknown)";
    String detail = reason != null && !reason.isBlank() ? reason.trim() : "unknown error";
    entries.add(new FailureEntry(location, normalizeReason(detail, location)));
    return this;
  }

  public void addAll(SampleOpenFailuresDialog other) {
    if (other != null) {
      entries.addAll(other.entries);
    }
  }

  public boolean isEmpty() {
    return entries.isEmpty();
  }

  public int size() {
    return entries.size();
  }

  /**
   * Strip the file path out of exception messages so identical failures group together
   * (e.g. {@code BAI index not found for: /path/file.bam} → {@code BAI index not found}).
   */
  static String normalizeReason(String reason, String path) {
    if (reason == null || reason.isBlank()) {
      return "unknown error";
    }
    String r = reason.trim();
    if (path != null && !path.isBlank()) {
      r = r.replace(path, "");
      String forward = path.replace('\\', '/');
      if (!forward.equals(path)) {
        r = r.replace(forward, "");
      }
      String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
      if (!name.isBlank() && name.length() > 1) {
        r = r.replace(name, "");
      }
    }
    r = r.replaceAll("\\s+for:\\s*$", "")
        .replaceAll("\\s+for\\s*$", "")
        .replaceAll("\\s*:\\s*$", "")
        .replaceAll("[\\s─—-]+$", "")
        .replaceAll("\\s{2,}", " ")
        .trim();
    return r.isBlank() ? "unknown error" : r;
  }

  /** Group by reason; each group lists sample paths. Stable reason order = first seen. */
  static String formatGrouped(List<FailureEntry> failures) {
    if (failures == null || failures.isEmpty()) {
      return "";
    }
    Map<String, List<String>> byReason = new LinkedHashMap<>();
    for (FailureEntry entry : failures) {
      byReason.computeIfAbsent(entry.reason(), key -> new ArrayList<>()).add(entry.path());
    }
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Map.Entry<String, List<String>> group : byReason.entrySet()) {
      if (!first) {
        sb.append('\n');
      }
      first = false;
      List<String> paths = group.getValue();
      sb.append(group.getKey());
      if (paths.size() != 1) {
        sb.append(" (").append(paths.size()).append(')');
      }
      sb.append('\n');
      for (String path : paths) {
        sb.append("  ").append(path).append('\n');
      }
    }
    return sb.toString().stripTrailing();
  }

  /**
   * Append this batch to the session log. Does not show a dialog — call
   * {@link #showPendingNonModal()} after loading work has been scheduled.
   */
  public void commitToSessionLog() {
    if (entries.isEmpty()) {
      return;
    }
    synchronized (LOCK) {
      SESSION_LOG.addAll(entries);
      pendingToShow += entries.size();
    }
  }

  /**
   * Commit and schedule a non-modal popup (if anything new). Safe to call from
   * load completion: does not block the FX thread with {@code showAndWait}.
   */
  public void commitAndShowLater() {
    commitToSessionLog();
    showPendingNonModal();
  }

  /** @deprecated use {@link #commitAndShowLater()} */
  @Deprecated
  public void showIfAny() {
    commitAndShowLater();
  }

  /** Tools menu: open the full session log (or a short empty message). */
  public static void openFromTools() {
    List<FailureEntry> snapshot;
    synchronized (LOCK) {
      snapshot = List.copyOf(SESSION_LOG);
      pendingToShow = 0;
    }
    if (Platform.isFxApplicationThread()) {
      showLog(MainApp.stage, snapshot, false);
    } else {
      Platform.runLater(() -> showLog(MainApp.stage, snapshot, false));
    }
  }

  public static boolean hasEntries() {
    synchronized (LOCK) {
      return !SESSION_LOG.isEmpty();
    }
  }

  public static void clearSessionLog() {
    synchronized (LOCK) {
      SESSION_LOG.clear();
      pendingToShow = 0;
    }
  }

  /**
   * Non-modal popup for newly committed failures. Does not block load/variant pipelines.
   */
  public static void showPendingNonModal() {
    List<FailureEntry> snapshot;
    int pending;
    synchronized (LOCK) {
      pending = pendingToShow;
      if (pending <= 0) {
        return;
      }
      snapshot = List.copyOf(SESSION_LOG);
      pendingToShow = 0;
    }
    Runnable show = () -> showLog(MainApp.stage, snapshot, true);
    Platform.runLater(show);
  }

  private static void showLog(Window owner, List<FailureEntry> failures, boolean autoPopup) {
    if (failures == null || failures.isEmpty()) {
      if (!autoPopup) {
        AppDialog.info(
            owner,
            "Sample open errors",
            "No sample open errors",
            "No failed sample files have been recorded in this session yet.");
      }
      return;
    }
    if (openDialog != null && openDialog.stage.isShowing()) {
      openDialog.refresh(failures);
      openDialog.stage.toFront();
      return;
    }
    openDialog = new FailureListDialog(owner, failures);
    openDialog.stage.setOnHidden(e -> {
      if (openDialog != null && openDialog.stage == e.getSource()) {
        openDialog = null;
      }
    });
    openDialog.stage.show(); // non-modal — do not showAndWait
  }

  private static final class FailureListDialog extends AppDialog {
    private final TextArea area;
    private final Label titleLabel;

    FailureListDialog(Window owner, List<FailureEntry> failures) {
      super(owner, "Sample open errors", javafx.stage.Modality.NONE);
      root.setPrefWidth(560);
      root.setMinWidth(420);
      root.setMaxWidth(720);
      stage.setResizable(true);

      titleLabel = addTitle(titleFor(failures.size()));
      addHint(
          "Grouped by failure reason. Also available under Tools → Sample open errors. "
              + "Select text or use Copy list.");

      String text = formatGrouped(failures);
      area = new TextArea(text);
      area.setEditable(false);
      area.setWrapText(false);
      area.setPrefRowCount(Math.min(18, Math.max(6, text.lines().mapToInt(l -> 1).sum() + 1)));
      area.setStyle(
          "-fx-control-inner-background: " + AppTheme.CHROME_DARK.controlHex() + ";"
              + "-fx-text-fill: " + AppTheme.CHROME_DARK.textHex() + ";"
              + "-fx-font-family: monospace;"
              + "-fx-font-size: 11px;"
              + "-fx-highlight-fill: #2a4a6a;"
              + "-fx-highlight-text-fill: #d8e8ff;");
      area.setMaxWidth(Double.MAX_VALUE);
      area.setPrefWidth(520);
      addContent(area);

      Button copy = secondaryButton("Copy list");
      copy.setOnAction(e -> {
        ClipboardContent content = new ClipboardContent();
        content.putString(area.getText());
        Clipboard.getSystemClipboard().setContent(content);
      });
      buttonBar.getChildren().add(copy);

      Button clear = secondaryButton("Clear log");
      clear.setOnAction(e -> {
        clearSessionLog();
        stage.close();
      });
      buttonBar.getChildren().add(clear);

      addPrimaryButton("OK");
    }

    void refresh(List<FailureEntry> failures) {
      titleLabel.setText(titleFor(failures.size()));
      String text = formatGrouped(failures);
      area.setText(text);
      area.setPrefRowCount(Math.min(18, Math.max(6, text.lines().mapToInt(l -> 1).sum() + 1)));
    }

    private static String titleFor(int n) {
      return n == 1 ? "1 sample failed to open" : n + " samples failed to open";
    }
  }
}
