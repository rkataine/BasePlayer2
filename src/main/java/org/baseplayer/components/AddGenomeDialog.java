package org.baseplayer.components;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.baseplayer.MainApp;
import org.baseplayer.genome.download.EnsemblGenomeCatalog;
import org.baseplayer.genome.download.GenomeInstallService;
import org.baseplayer.genome.download.GenomeInstallService.Phase;
import org.baseplayer.ui.theme.AppTheme;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Modal for curated Ensembl genome download (expandable rows), local FASTA import,
 * and adding GFF/GTF annotations to installed genomes.
 */
public final class AddGenomeDialog {

  @FunctionalInterface
  private interface InstallWork {
    String run(GenomeInstallService service) throws Exception;
  }

  private AddGenomeDialog() {}

  /**
   * @param owner              owner window
   * @param onInstalledFolder  called on the FX thread with the installed/updated folder id
   */
  public static void show(Window owner, Consumer<String> onInstalledFolder) {
    Path genomesRoot = Path.of("genomes");

    Stage stage = new Stage(StageStyle.UTILITY);
    stage.initModality(Modality.APPLICATION_MODAL);
    if (owner != null) {
      stage.initOwner(owner);
    }
    stage.setTitle("Add reference genome");
    stage.setResizable(true);

    Label title = AppDialog.titleLabel("Reference genomes");
    Label hint = AppDialog.hintLabel(
        "Click a genome to expand download links. Use Download to install automatically, "
            + "or open / Copy URL for a manual download. Local import can be FASTA-only; "
            + "add GFF/GTF annotations later to any installed genome.");

    ProgressBar progressBar = new ProgressBar(0);
    progressBar.setMaxWidth(Double.MAX_VALUE);
    progressBar.setVisible(false);
    Label progressLabel = AppDialog.messageLabel(" ");
    progressLabel.setMinHeight(32);

    AtomicReference<GenomeInstallService> activeService = new AtomicReference<>();
    AtomicReference<Future<?>> activeFuture = new AtomicReference<>();
    ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "genome-install");
      t.setDaemon(true);
      return t;
    });

    List<GenomeRow> rows = new ArrayList<>();
    VBox listBox = new VBox(4);
    listBox.getStyleClass().add("genome-catalog-list");

    ComboBox<String> installedCombo = new ComboBox<>();
    installedCombo.getStyleClass().add("genome-catalog-field");
    installedCombo.setMaxWidth(Double.MAX_VALUE);
    installedCombo.setPromptText("Select installed genome…");
    refreshInstalledCombo(genomesRoot, installedCombo);

    Runnable refreshBusy = () -> {
      boolean busy = activeFuture.get() != null && !activeFuture.get().isDone();
      for (GenomeRow row : rows) {
        row.setBusy(busy);
      }
    };

    Consumer<String> afterSuccess = folderId -> {
      for (GenomeRow row : rows) {
        row.refreshInstalled(genomesRoot);
      }
      refreshInstalledCombo(genomesRoot, installedCombo);
      if (folderId != null) {
        installedCombo.getSelectionModel().select(folderId);
      }
      refreshBusy.run();
      if (onInstalledFolder != null) {
        onInstalledFolder.accept(folderId);
      }
    };

    Consumer<EnsemblGenomeCatalog.Entry> startDownload = entry -> {
      if (GenomeInstallService.isInstalled(genomesRoot, entry)) {
        return;
      }
      runJob(
          executor,
          activeService,
          activeFuture,
          progressBar,
          progressLabel,
          refreshBusy,
          stage,
          "Starting " + entry.folderId() + "…",
          "Genome download failed",
          "Check network access and disk space, then try again.",
          service -> {
            service.install(entry, (phase, bytes, total, message) -> Platform.runLater(() -> {
              progressLabel.setText(formatProgress(phase, bytes, total, message));
              if (total > 0
                  && (phase == Phase.FASTA || phase == Phase.GFF3 || phase == Phase.BANDS)) {
                progressBar.setProgress(Math.min(0.99, (double) bytes / (double) total));
              } else {
                progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
              }
            }));
            return entry.folderId();
          },
          afterSuccess);
    };

    int index = 0;
    for (EnsemblGenomeCatalog.Entry entry : EnsemblGenomeCatalog.entries()) {
      GenomeRow row = new GenomeRow(entry, genomesRoot, startDownload, index % 2 == 1);
      rows.add(row);
      listBox.getChildren().add(row.root);
      index++;
    }

    ScrollPane scroll = new ScrollPane(listBox);
    scroll.getStyleClass().addAll("variant-nested-scroll", "genome-catalog-scroll");
    scroll.setFitToWidth(true);
    scroll.setPrefHeight(300);
    VBox.setVgrow(scroll, Priority.ALWAYS);

    // ── Manual local import (FASTA required, annotation optional) ──────────
    Label manualTitle = AppDialog.titleLabel("Import reference from local files");
    TextField folderField = new TextField();
    folderField.setPromptText("Genome folder name (e.g. GRCh38)");
    folderField.getStyleClass().add("genome-catalog-field");

    Label fastaPathLabel = AppDialog.hintLabel("No FASTA selected");
    Label gffPathLabel = AppDialog.hintLabel("Annotation optional — none selected");
    AtomicReference<Path> fastaPath = new AtomicReference<>();
    AtomicReference<Path> gffPath = new AtomicReference<>();

    Button pickFasta = AppDialog.secondaryButton("Choose FASTA…");
    pickFasta.setOnAction(e -> {
      FileChooser chooser = new FileChooser();
      chooser.setTitle("Select reference FASTA");
      chooser.getExtensionFilters().addAll(
          new FileChooser.ExtensionFilter("FASTA", "*.fa", "*.fasta", "*.fa.gz", "*.fasta.gz"),
          new FileChooser.ExtensionFilter("All files", "*.*"));
      File file = chooser.showOpenDialog(stage);
      if (file != null) {
        fastaPath.set(file.toPath());
        fastaPathLabel.setText(file.getAbsolutePath());
        if (folderField.getText() == null || folderField.getText().isBlank()) {
          folderField.setText(stripExtensions(file.getName()));
        }
      }
    });

    Button pickGff = AppDialog.secondaryButton("Choose GFF/GTF (optional)…");
    pickGff.setOnAction(e -> {
      FileChooser chooser = new FileChooser();
      chooser.setTitle("Select gene annotation (optional)");
      chooser.getExtensionFilters().addAll(
          new FileChooser.ExtensionFilter(
              "GFF/GTF", "*.gff3", "*.gff3.gz", "*.gff", "*.gff.gz", "*.gtf", "*.gtf.gz"),
          new FileChooser.ExtensionFilter("All files", "*.*"));
      File file = chooser.showOpenDialog(stage);
      if (file != null) {
        gffPath.set(file.toPath());
        gffPathLabel.setText(file.getAbsolutePath());
      }
    });

    Button importBtn = AppDialog.primaryButton("Import local genome");
    importBtn.setOnAction(e -> {
      String folderId = folderField.getText() != null ? folderField.getText().trim() : "";
      Path fa = fastaPath.get();
      Path gff = gffPath.get();
      if (folderId.isEmpty() || fa == null) {
        AppDialog.info(
            stage,
            "Missing files",
            "Folder name and FASTA are required.",
            "Annotation (GFF/GTF) is optional and can be added later.");
        return;
      }
      if (GenomeInstallService.isInstalled(genomesRoot, folderId)) {
        AppDialog.info(
            stage,
            "Already installed",
            "genomes/" + folderId + " already has an indexed FASTA.",
            "Use “Add annotation” below to attach GFF/GTF files.");
        return;
      }
      runJob(
          executor,
          activeService,
          activeFuture,
          progressBar,
          progressLabel,
          refreshBusy,
          stage,
          "Importing " + folderId + "…",
          "Local import failed",
          null,
          service -> {
            service.installFromLocal(folderId, fa, gff, (phase, bytes, total, message) ->
                Platform.runLater(() -> {
                  progressLabel.setText(message != null ? message : phase.label());
                  progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                }));
            return folderId;
          },
          afterSuccess);
    });

    HBox pickRow = new HBox(8, pickFasta, pickGff, importBtn);
    pickRow.setAlignment(Pos.CENTER_LEFT);
    VBox manualBox = new VBox(8, manualTitle, folderField, fastaPathLabel, gffPathLabel, pickRow);
    manualBox.getStyleClass().add("genome-catalog-manual");

    // ── Add annotations to installed genome ────────────────────────────────
    Label annTitle = AppDialog.titleLabel("Add annotation to installed genome");
    Label annFilesLabel = AppDialog.hintLabel("No annotation files selected");
    AtomicReference<List<Path>> annFiles = new AtomicReference<>(List.of());

    Button pickAnnFiles = AppDialog.secondaryButton("Choose GFF/GTF file(s)…");
    pickAnnFiles.setOnAction(e -> {
      FileChooser chooser = new FileChooser();
      chooser.setTitle("Select gene annotation file(s)");
      chooser.getExtensionFilters().addAll(
          new FileChooser.ExtensionFilter(
              "GFF/GTF", "*.gff3", "*.gff3.gz", "*.gff", "*.gff.gz", "*.gtf", "*.gtf.gz"),
          new FileChooser.ExtensionFilter("All files", "*.*"));
      List<File> files = chooser.showOpenMultipleDialog(stage);
      if (files != null && !files.isEmpty()) {
        List<Path> paths = files.stream().map(File::toPath).toList();
        annFiles.set(paths);
        annFilesLabel.setText(paths.size() == 1
            ? paths.get(0).toString()
            : paths.size() + " files selected");
      }
    });

    Button addAnnBtn = AppDialog.primaryButton("Add annotation(s)");
    addAnnBtn.setOnAction(e -> {
      String folderId = installedCombo.getValue();
      List<Path> files = annFiles.get();
      if (folderId == null || folderId.isBlank()) {
        AppDialog.info(stage, "Select a genome", "Choose an installed reference genome.", null);
        return;
      }
      if (files == null || files.isEmpty()) {
        AppDialog.info(stage, "Select files", "Choose one or more GFF/GTF files to add.", null);
        return;
      }
      runJob(
          executor,
          activeService,
          activeFuture,
          progressBar,
          progressLabel,
          refreshBusy,
          stage,
          "Adding annotation to " + folderId + "…",
          "Add annotation failed",
          null,
          service -> {
            service.addAnnotations(folderId, files, (phase, bytes, total, message) ->
                Platform.runLater(() -> {
                  progressLabel.setText(message != null ? message : phase.label());
                  if (total > 0) {
                    progressBar.setProgress((double) bytes / (double) total);
                  } else {
                    progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                  }
                }));
            return folderId;
          },
          afterSuccess);
    });

    HBox annPickRow = new HBox(8, pickAnnFiles, addAnnBtn);
    annPickRow.setAlignment(Pos.CENTER_LEFT);
    VBox annBox = new VBox(8, annTitle, installedCombo, annFilesLabel, annPickRow);
    annBox.getStyleClass().add("genome-catalog-manual");

    Button closeBtn = AppDialog.secondaryButton("Close");
    closeBtn.setOnAction(e -> {
      Future<?> future = activeFuture.get();
      GenomeInstallService service = activeService.get();
      if (future != null && !future.isDone()) {
        if (service != null) {
          service.cancel();
        }
        future.cancel(true);
        progressLabel.setText("Cancelling…");
        closeBtn.setText("Cancel");
        return;
      }
      executor.shutdownNow();
      stage.close();
    });

    stage.setOnCloseRequest(e -> {
      Future<?> future = activeFuture.get();
      GenomeInstallService service = activeService.get();
      if (future != null && !future.isDone()) {
        e.consume();
        if (service != null) {
          service.cancel();
        }
        future.cancel(true);
        progressLabel.setText("Cancelling…");
      } else {
        executor.shutdownNow();
      }
    });

    HBox footer = new HBox(8);
    footer.setAlignment(Pos.CENTER_RIGHT);
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    footer.getChildren().addAll(spacer, closeBtn);

    VBox root = new VBox(
        12, title, hint, scroll, progressBar, progressLabel, manualBox, annBox, footer);
    root.setPadding(new Insets(18, 20, 16, 20));
    root.setStyle(AppDialog.PANEL_STYLE);
    root.setPrefWidth(660);
    root.setPrefHeight(780);
    root.getStyleClass().add("genome-catalog-root");

    Scene scene = new Scene(root);
    scene.setFill(javafx.scene.paint.Color.web(AppTheme.CHROME_DARK.surfaceHex()));
    if (MainApp.getResource("theme-dark.css") != null) {
      scene.getStylesheets().add(MainApp.getResource("theme-dark.css").toExternalForm());
    }
    if (MainApp.getResource("application.css") != null) {
      scene.getStylesheets().add(MainApp.getResource("application.css").toExternalForm());
    }
    stage.setScene(scene);
    stage.show();
  }

  private static void runJob(
      ExecutorService executor,
      AtomicReference<GenomeInstallService> activeService,
      AtomicReference<Future<?>> activeFuture,
      ProgressBar progressBar,
      Label progressLabel,
      Runnable refreshBusy,
      Stage stage,
      String startMessage,
      String failTitle,
      String failHint,
      InstallWork work,
      Consumer<String> onSuccess) {
    GenomeInstallService service = new GenomeInstallService(Path.of("genomes"));
    activeService.set(service);
    progressBar.setVisible(true);
    progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
    progressLabel.setText(startMessage);
    refreshBusy.run();

    Future<?> future = executor.submit(() -> {
      try {
        String folderId = work.run(service);
        Platform.runLater(() -> {
          progressBar.setProgress(1);
          progressLabel.setText("Done: " + folderId);
          activeFuture.set(null);
          activeService.set(null);
          onSuccess.accept(folderId);
        });
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        Platform.runLater(() -> {
          progressLabel.setText("Cancelled.");
          progressBar.setVisible(false);
          activeFuture.set(null);
          activeService.set(null);
          refreshBusy.run();
        });
      } catch (Exception ex) {
        Platform.runLater(() -> {
          progressBar.setVisible(false);
          progressLabel.setText("Failed.");
          activeFuture.set(null);
          activeService.set(null);
          refreshBusy.run();
          AppDialog.info(
              stage,
              failTitle,
              ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName(),
              failHint);
        });
      }
    });
    activeFuture.set(future);
    refreshBusy.run();
  }

  private static void refreshInstalledCombo(Path genomesRoot, ComboBox<String> combo) {
    if (combo == null) {
      return;
    }
    String selected = combo.getValue();
    List<String> ids = GenomeInstallService.listInstalledFolderIds(genomesRoot);
    combo.setItems(FXCollections.observableArrayList(ids));
    if (selected != null && ids.contains(selected)) {
      combo.getSelectionModel().select(selected);
    } else if (!ids.isEmpty() && combo.getValue() == null) {
      // leave unselected so user chooses
    }
  }

  private static String stripExtensions(String name) {
    String n = name;
    String lower = n.toLowerCase();
    if (lower.endsWith(".gz")) {
      n = n.substring(0, n.length() - 3);
      lower = n.toLowerCase();
    }
    if (lower.endsWith(".fasta")) {
      return n.substring(0, n.length() - 6);
    }
    if (lower.endsWith(".fa")) {
      return n.substring(0, n.length() - 3);
    }
    return n;
  }

  private static String formatProgress(Phase phase, long bytes, long total, String message) {
    if (bytes <= 0 || total <= 0) {
      return message != null ? message : phase.label();
    }
    return phase.label() + " — " + formatBytes(bytes) + " / " + formatBytes(total);
  }

  private static String formatBytes(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    if (bytes < 1024 * 1024) {
      return String.format("%.1f KB", bytes / 1024.0);
    }
    if (bytes < 1024L * 1024 * 1024) {
      return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }

  private static void openUrl(String url) {
    if (url == null || url.isBlank()) {
      return;
    }
    try {
      if (MainApp.getHostServicesInstance() != null) {
        MainApp.getHostServicesInstance().showDocument(url);
      }
    } catch (Exception ignored) {
      // ignore
    }
  }

  private static void copyUrl(String url) {
    if (url == null || url.isBlank()) {
      return;
    }
    ClipboardContent content = new ClipboardContent();
    content.putString(url);
    Clipboard.getSystemClipboard().setContent(content);
  }

  /** Expandable genome catalog row (header + detail links). */
  private static final class GenomeRow {
    private final EnsemblGenomeCatalog.Entry entry;
    private final VBox root = new VBox();
    private final VBox detail = new VBox(6);
    private final Label statusLabel = new Label();
    private final Button downloadBtn = AppDialog.primaryButton("Download");
    private boolean expanded;
    private boolean installed;
    private boolean busy;

    GenomeRow(
        EnsemblGenomeCatalog.Entry entry,
        Path genomesRoot,
        Consumer<EnsemblGenomeCatalog.Entry> onDownload,
        boolean odd) {
      this.entry = entry;
      root.getStyleClass().add("genome-catalog-row");
      if (odd) {
        root.getStyleClass().add("genome-catalog-row-odd");
      }

      Label name = new Label(entry.label());
      name.getStyleClass().add("genome-catalog-name");

      Label assembly = new Label(entry.assembly());
      assembly.getStyleClass().add("genome-catalog-assembly");

      Label size = new Label(entry.sizeHint());
      size.getStyleClass().add("genome-catalog-size");

      statusLabel.getStyleClass().add("genome-catalog-status");
      refreshInstalled(genomesRoot);

      Region spacer = new Region();
      HBox.setHgrow(spacer, Priority.ALWAYS);

      HBox header = new HBox(10, name, assembly, size, spacer, statusLabel);
      header.setAlignment(Pos.CENTER_LEFT);
      header.setPadding(new Insets(8, 10, 8, 10));
      header.getStyleClass().add("genome-catalog-header");
      header.setOnMouseClicked(e -> toggle());

      detail.setPadding(new Insets(0, 12, 10, 12));
      detail.getStyleClass().add("genome-catalog-detail");
      detail.setVisible(false);
      detail.setManaged(false);

      detail.getChildren().add(linkRow("FASTA", entry.fastaUrl()));
      detail.getChildren().add(linkRow("Genes (GFF3)", entry.gff3Url()));
      if (entry.ucscCytoBandUrl() != null) {
        String bandsLabel = entry.ucscCytoBandUrl().contains("human-pangenomics")
            ? "Cytobands (HPRC)"
            : "Cytobands (UCSC)";
        detail.getChildren().add(linkRow(bandsLabel, entry.ucscCytoBandUrl()));
      } else {
        Label noBands = new Label("Cytobands: not available for this assembly");
        noBands.getStyleClass().add("genome-catalog-muted");
        detail.getChildren().add(noBands);
      }

      downloadBtn.setOnAction(e -> {
        if (!busy && !installed) {
          onDownload.accept(entry);
        }
      });
      HBox actions = new HBox(8, downloadBtn);
      actions.setAlignment(Pos.CENTER_LEFT);
      detail.getChildren().add(actions);

      root.getChildren().addAll(header, detail);
    }

    void refreshInstalled(Path genomesRoot) {
      installed = GenomeInstallService.isInstalled(genomesRoot, entry);
      statusLabel.setText(installed ? "Installed" : "Not installed");
      if (installed) {
        statusLabel.getStyleClass().setAll("genome-catalog-status", "genome-catalog-status-ok");
      } else {
        statusLabel.getStyleClass().setAll("genome-catalog-status");
      }
      downloadBtn.setDisable(busy || installed);
      downloadBtn.setText(installed ? "Installed" : "Download");
    }

    void setBusy(boolean value) {
      busy = value;
      downloadBtn.setDisable(busy || installed);
    }

    private void toggle() {
      expanded = !expanded;
      detail.setVisible(expanded);
      detail.setManaged(expanded);
      if (expanded) {
        root.getStyleClass().add("genome-catalog-row-expanded");
      } else {
        root.getStyleClass().remove("genome-catalog-row-expanded");
      }
    }

    private HBox linkRow(String label, String url) {
      Label kind = new Label(label + ":");
      kind.getStyleClass().add("genome-catalog-link-label");
      kind.setMinWidth(120);

      Hyperlink link = new Hyperlink(shortenUrl(url));
      link.getStyleClass().add("genome-catalog-link");
      link.setTooltip(new Tooltip("Open in browser:\n" + url));
      link.setOnAction(e -> openUrl(url));
      link.setMaxWidth(Double.MAX_VALUE);
      HBox.setHgrow(link, Priority.ALWAYS);

      Button copy = new Button("Copy URL");
      copy.getStyleClass().add("genome-catalog-copy-btn");
      copy.setTooltip(new Tooltip("Copy download URL to clipboard"));
      copy.setMinWidth(Region.USE_PREF_SIZE);
      copy.setMaxWidth(Region.USE_PREF_SIZE);
      copy.setOnAction(e -> {
        copyUrl(url);
        copy.setText("Copied");
        javafx.animation.PauseTransition pause =
            new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.2));
        pause.setOnFinished(ev -> copy.setText("Copy URL"));
        pause.play();
      });

      HBox row = new HBox(8, kind, link, copy);
      row.setAlignment(Pos.CENTER_LEFT);
      return row;
    }

    private static String shortenUrl(String url) {
      if (url == null) {
        return "";
      }
      if (url.length() <= 64) {
        return url;
      }
      return url.substring(0, 30) + "…" + url.substring(url.length() - 30);
    }
  }
}
