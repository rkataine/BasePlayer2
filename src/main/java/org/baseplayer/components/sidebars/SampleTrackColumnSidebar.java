package org.baseplayer.components.sidebars;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.components.LoadRegionButton;
import org.baseplayer.components.PopupComboBoxStyler;
import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.io.Settings;
import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.samples.alignment.AlignmentFile;
import org.baseplayer.samples.alignment.BAMRecord;
import org.baseplayer.samples.alignment.draw.CircosPlot;
import org.baseplayer.samples.alignment.draw.ReadColorMode;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfVariantType;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

public class SampleTrackColumnSidebar extends TrackColumnSidebar {

  public final LoadRegionButton loadRegionButton;
  public final SampleTrackListPanel sampleList;

  private final SampleRegistry sampleRegistry =
      ServiceRegistry.getInstance().getSampleRegistry();
  private final DrawStackManager stackManager =
      ServiceRegistry.getInstance().getDrawStackManager();
  /** Resolved lazily from stackManager via {@link #syncDrawStack()}. */
  private DrawStack masterTrackDrawStack;

  public SampleTrackColumnSidebar(StackPane parent) {
    super(parent, ServiceRegistry.getInstance().getSampleRegistry());
    sampleList = new SampleTrackListPanel(contentPane);
    loadRegionButton = new LoadRegionButton();
    Platform.runLater(loadRegionButton::attachRegionListener);
  }

  public void initializeLoadRegionButton() {
    Platform.runLater(loadRegionButton::attachRegionListener);
  }

  @Override
  protected TrackListPanel getTrackListPanel() {
    return sampleList;
  }

  @Override
  protected double estimateTrackBodyViewportHeightPixels() {
    if (!stackManager.isEmpty() && stackManager.getFirst().sampleTrackCanvas != null) {
      double canvasHeight = stackManager.getFirst().sampleTrackCanvas.getHeight();
      if (canvasHeight > 0) {
        return canvasHeight;
      }
    }

    return sampleRegistry.getSampleHeight()
        * Math.max(1, sampleRegistry.getVisibleSampleCount());
  }

  @Override
  protected ContextMenu buildAddTrackMenu() {
    return createAddDataMenu();
  }

  @Override
  protected ContextMenu buildSidebarSettingsMenu() {
    return buildGlobalSettingsMenu();
  }

  @Override
  protected void onSettingsClicked(double screenX, double screenY) {
    ContextMenu settingsMenu = buildGlobalSettingsMenu();
    if (getMasterHeaderCanvas().getScene() != null) {
      settingsMenu.show(getMasterHeaderCanvas().getScene().getWindow(), screenX, screenY);
    }
  }

  @Override
  protected boolean canShowReloadButton() {
    return hasAnySuspended();
  }

  @Override
  protected void onReloadButtonClicked() {
    resumeSuspendedReads();
  }

  @Override
  protected String getVisibleTracksRangeLabelTitle() {
    return "Visible samples";
  }

  @Override
  protected boolean highlightRangeLabelOnHover() {
    return sampleRegistry.hasActiveSubset();
  }

  @Override
  protected HitBox paintExpandedRangeLabelArea(
      GraphicsContext graphics,
      double panelWidth,
      double headerBarHeight,
      int trackCount,
      int firstVisibleSlot,
      int lastVisibleSlot) {
    graphics.setFont(Font.font("Segoe UI", 10));
    graphics.setFill(Color.web("#9ea7b3"));

    String focusedGene = sampleRegistry.getFocusedGeneName();
    if (focusedGene != null && !focusedGene.isBlank()) {
      graphics.fillText("Gene focus: " + focusedGene, 8, headerBarHeight + 14);
      graphics.setFill(Color.web("#ffa500"));
      graphics.fillText("[clear x]", panelWidth - 70, headerBarHeight + 14);
      return new HitBox(panelWidth - 72, headerBarHeight + 1, 70, 18);
    }

    graphics.fillText(getVisibleTracksRangeLabelTitle(), 8, headerBarHeight + 14);
    if (sampleRegistry.hasActiveSampleFilterQuery()) {
      graphics.setFill(Color.web("#ffa500"));
      graphics.fillText("Filter [clear x]", 96, headerBarHeight + 14);
      return new HitBox(90, headerBarHeight + 1, 110, 18);
    }

    graphics.setFill(Color.web("#7f8791"));
    String rangeText = firstVisibleSlot == lastVisibleSlot
        ? String.valueOf(firstVisibleSlot + 1)
        : (firstVisibleSlot + 1) + "-" + (lastVisibleSlot + 1);
    String label = rangeText + " / " + trackCount;
    double labelX = 96;
    graphics.fillText(label, labelX, headerBarHeight + 14);
    return new HitBox(labelX - 6, headerBarHeight + 1,
        Math.max(80, label.length() * 8.5), 18);
  }

  @Override
  protected boolean handleExpandedRangeLabelClick(double screenX, double screenY) {
    String focusedGene = sampleRegistry.getFocusedGeneName();
    if (sampleRegistry.hasFocusedTracks()
        && ((focusedGene != null && !focusedGene.isBlank())
            || !sampleRegistry.hasActiveSampleFilterQuery())) {
      clearSubsetSourceAndRefresh(SampleRegistry.SubsetSource.GENE_FOCUS);
      return true;
    }
    if (sampleRegistry.hasActiveSampleFilterQuery()) {
      clearSubsetSourceAndRefresh(SampleRegistry.SubsetSource.TEXT_FILTER);
      return true;
    }
    return false;
  }

  @Override
  protected void appendRangeInputPopupExtras(VBox panel) {
    HBox filterRow = new HBox(6);
    filterRow.setPadding(new Insets(0, 6, 6, 6));

    Label filterLabel = new Label("Filter");
    filterLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");

    TextField filterField = new TextField(sampleRegistry.getActiveSampleFilterQuery());
    filterField.setPromptText("sample name contains...");
    filterField.setPrefWidth(170);
    filterField.setStyle(
        "-fx-background-color: #333; -fx-text-fill: #cccccc; -fx-border-color: #555; -fx-font-size: 11;");

    Button clearButton = new Button("Clear");
    clearButton.setStyle(
        "-fx-background-color: #3c3c3c; -fx-text-fill: #bbbbbb; -fx-font-size: 11;"
            + "-fx-padding: 2 8 2 8; -fx-border-color: #666; -fx-cursor: hand;");

    Runnable applyFilter = () -> {
      String query = filterField.getText() == null ? "" : filterField.getText().trim();
      if (!query.isEmpty()) {
        sampleRegistry.applyTextSubsetQuery(query);
        int trackCount = sampleRegistry.getDisplayedTrackCount();
        applyVisibleTrackRange(0, Math.max(0, trackCount - 1));
      } else {
        clearSubsetSourceAndRefresh(SampleRegistry.SubsetSource.TEXT_FILTER);
      }
    };

    filterField.textProperty().addListener((observable, oldValue, newValue) -> applyFilter.run());
    filterField.setOnAction(event -> applyFilter.run());
    clearButton.setOnAction(event -> {
      filterField.clear();
      rangeControlsPopup.hide();
    });
    filterRow.getChildren().addAll(filterLabel, filterField, clearButton);
    panel.getChildren().add(filterRow);
    Platform.runLater(filterField::requestFocus);
  }

  @Override
  protected void handleMasterHeaderScroll(ScrollEvent event) {
    syncDrawStack();
    if (masterTrackDrawStack == null) {
      return;
    }
    masterTrackDrawStack.nav.navigating = true;
    if (event.isControlDown()) {
      zoomAt(event.getDeltaY(), event.getX());
      return;
    }

    double delta = event.getDeltaX() != 0 ? event.getDeltaX() : event.getDeltaY();
    panByGenomeDelta(delta * 0.3 * masterTrackDrawStack.scale);
  }

  @Override
  protected boolean shouldAutoExpandMasterHeader(int displayedTrackCount) {
    // Keep filter/group controls reachable even when only one sample matches a subset.
    return displayedTrackCount >= 1;
  }

  @Override
  protected void onMasterHeaderBarClicked(double screenX, double screenY) {
    // Fallback when controls are collapsed: open the range/filter popup from the title bar.
    if (!isControlsExpanded()) {
      showRangeInputPopup(screenX, screenY);
    }
  }

  @Override
  protected String getTitle() {
    return "Sample Tracks";
  }

  @Override
  protected int getItemCount() {
    return sampleRegistry.getSampleTracks().size();
  }

  private void clearSubsetSourceAndRefresh(SampleRegistry.SubsetSource source) {
    sampleRegistry.clearSubsetSource(source);
    int trackCount = sampleRegistry.getDisplayedTrackCount();
    applyVisibleTrackRange(0, Math.max(0, trackCount - 1));
  }

  private boolean hasAnySuspended() {
    for (SampleTrack sampleTrack : sampleRegistry.getSampleTracks()) {
      for (Sample sample : sampleTrack.getSamples()) {
        if (sample.isSuspended()) {
          return true;
        }
      }
    }
    return false;
  }

  private void resumeSuspendedReads() {
    for (SampleTrack sampleTrack : sampleRegistry.getSampleTracks()) {
      for (Sample sample : sampleTrack.getSamples()) {
        if (sample.isSuspended()) {
          sample.resume();
          ThreadRunner.RunnerTask readTask =
              ThreadRunner.get().track("Loading reads: " + sample.getName(), sample::cancelAndSuspend);
          sample.setOnFirstLoadComplete(readTask::complete);
        }
      }
    }
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }

  private void syncDrawStack() {
    DrawStack hoverStack = stackManager.getHoverStack();
    if (hoverStack != null) {
      masterTrackDrawStack = hoverStack;
    } else if (!stackManager.isEmpty()) {
      masterTrackDrawStack = stackManager.getFirst();
    }
  }

  private ContextMenu createAddDataMenu() {
    ContextMenu menu = new ContextMenu();

    MenuItem bamItem = new MenuItem("BAM/CRAM");
    bamItem.setOnAction(e -> SampleDataManager.addBamFiles());

    MenuItem vcfItem = new MenuItem("VCF");
    vcfItem.setOnAction(e -> SampleDataManager.addVcfFile());

    MenuItem variantManagerItem = new MenuItem("Variant Manager");
    variantManagerItem.setOnAction(e ->
        org.baseplayer.variant.ui.VariantManagerWindow.openVariantManager(
            getMasterHeaderCanvas().getScene().getWindow(),
            VcfManager.getInstance(),
            null));

    MenuItem bedItem = new MenuItem("BED");
    bedItem.setOnAction(e -> SampleDataManager.addBedSampleFile());

    MenuItem bigwigItem = new MenuItem("BigWig");
    bigwigItem.setOnAction(e -> SampleDataManager.addBigWigFile());

    menu.getItems().addAll(bamItem, vcfItem, variantManagerItem, new SeparatorMenuItem(), bedItem, bigwigItem);
    return menu;
  }

  private ContextMenu buildGlobalSettingsMenu() {
    ContextMenu settingsMenu = new ContextMenu();
    settingsMenu.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");

    Label titleLabel = new Label("Global Settings");
    titleLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 13; -fx-font-weight: bold; -fx-padding: 4 8 2 8;");
    settingsMenu.getItems().addAll(new CustomMenuItem(titleLabel, false), new SeparatorMenuItem());

    // Gather BAM files once for global controls.
    List<AlignmentFile> bamFiles = new ArrayList<>();
    for (SampleTrack strack : sampleRegistry.getSampleTracks()) {
      for (Sample s : strack.getSamples()) {
        if (s.getBamFile() != null) {
          bamFiles.add(s.getBamFile());
        }
      }
    }

    // Sampled coverage settings (BAM-specific)
    if (!bamFiles.isEmpty()) {
      VBox sampledCoverageBox = new VBox(4);
      sampledCoverageBox.setPadding(new Insets(4, 8, 4, 8));

      CheckBox enableCoverageCb = new CheckBox("Enable sampled coverage");
      enableCoverageCb.setSelected(Settings.get().isEnableSampledCoverage());
      enableCoverageCb.getStyleClass().add("dark-checkbox");
      enableCoverageCb.setStyle("-fx-font-size: 12;");
      enableCoverageCb.selectedProperty().addListener((obs, o, n) -> {
        Settings.get().setEnableSampledCoverage(n);
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      });

      HBox samplePointsBox = new HBox(6);
      Label samplePointsLabel = new Label("Sample points:");
      samplePointsLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
      TextField samplePointsField = new TextField(String.valueOf(Settings.get().getSampledCoveragePoints()));
      samplePointsField.setStyle(
          "-fx-background-color: #333; -fx-text-fill: #cccccc; -fx-border-color: #555; -fx-font-size: 11;");
      samplePointsField.setPrefWidth(80);
      Button refreshButton = new Button("Refresh");
      refreshButton.setStyle("-fx-background-color: #4a4a4a; -fx-text-fill: #cccccc; -fx-font-size: 11; "
          + "-fx-padding: 2 8 2 8; -fx-border-color: #666; -fx-cursor: hand;");
      refreshButton.setOnAction(e -> {
        try {
          int value = Integer.parseInt(samplePointsField.getText());
          if (value > 0 && value <= 10000) {
            Settings.get().setSampledCoveragePoints(value);
            for (SampleTrack strack : sampleRegistry.getSampleTracks()) {
              for (Sample s : strack.getSamples()) {
                if (s.getBamFile() != null) {
                  s.getBamFile().clearSampledCoverageCache();
                }
              }
            }
            GenomicCanvas.update.set(!GenomicCanvas.update.get());
            settingsMenu.hide();
          }
        } catch (NumberFormatException ex) {
          samplePointsField.setText(String.valueOf(Settings.get().getSampledCoveragePoints()));
        }
      });
      samplePointsBox.getChildren().addAll(samplePointsLabel, samplePointsField, refreshButton);
      sampledCoverageBox.getChildren().addAll(enableCoverageCb, samplePointsBox);
      settingsMenu.getItems().add(new CustomMenuItem(sampledCoverageBox, false));
    }

    // Mismatch filtering controls (apply to all BAM files)
    if (!bamFiles.isEmpty()) {
      settingsMenu.getItems().add(new SeparatorMenuItem());
      VBox mismatchBox = new VBox(4);
      mismatchBox.setPadding(new Insets(4, 8, 4, 8));

      Label mismatchLabel = new Label("Mismatch filtering (all tracks)");
      mismatchLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12; -fx-font-weight: bold;");

      CheckBox suppressMethylCb = new CheckBox("Hide bisulfite mismatches (C->T / G->A)");
      boolean anyMethylSuppressed = bamFiles.stream().anyMatch(AlignmentFile::isMethylationData);
      suppressMethylCb.setSelected(anyMethylSuppressed);
      suppressMethylCb.getStyleClass().add("dark-checkbox");
      suppressMethylCb.setStyle("-fx-font-size: 11;");
      suppressMethylCb.selectedProperty().addListener((obs, o, n) -> {
        for (AlignmentFile bam : bamFiles) {
          bam.setSuppressMethylMismatches(n);
        }
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      });

      HBox mmFractionRow = new HBox(6);
      Label mmFractionLabel = new Label("Min fraction (0-1):");
      mmFractionLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
      TextField mmFractionField = new TextField(String.valueOf(Settings.get().getMismatchMinFraction()));
      mmFractionField.setStyle(
          "-fx-background-color: #333; -fx-text-fill: #cccccc; -fx-border-color: #555; -fx-font-size: 11;");
      mmFractionField.setPrefWidth(80);
      mmFractionRow.getChildren().addAll(mmFractionLabel, mmFractionField);

      HBox mmCountRow = new HBox(6);
      Label mmCountLabel = new Label("Min read count:");
      mmCountLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
      TextField mmCountField = new TextField(String.valueOf(Settings.get().getMismatchMinCount()));
      mmCountField.setStyle(
          "-fx-background-color: #333; -fx-text-fill: #cccccc; -fx-border-color: #555; -fx-font-size: 11;");
      mmCountField.setPrefWidth(80);
      mmCountRow.getChildren().addAll(mmCountLabel, mmCountField);

      Button applyMismatchButton = new Button("Apply mismatch thresholds");
      applyMismatchButton.setStyle("-fx-background-color: #4a4a4a; -fx-text-fill: #cccccc; -fx-font-size: 11; "
          + "-fx-padding: 2 8 2 8; -fx-border-color: #666; -fx-cursor: hand;");
      Runnable applyMismatchThresholds = () -> {
        try {
          double frac = Double.parseDouble(mmFractionField.getText());
          int count = Integer.parseInt(mmCountField.getText());
          frac = Math.max(0.0, Math.min(1.0, frac));
          count = Math.max(1, Math.min(100, count));
          Settings.get().setMismatchMinFraction(frac);
          Settings.get().setMismatchMinCount(count);
          mmFractionField.setText(String.valueOf(frac));
          mmCountField.setText(String.valueOf(count));
          GenomicCanvas.update.set(!GenomicCanvas.update.get());
        } catch (NumberFormatException ex) {
          mmFractionField.setText(String.valueOf(Settings.get().getMismatchMinFraction()));
          mmCountField.setText(String.valueOf(Settings.get().getMismatchMinCount()));
        }
      };
      applyMismatchButton.setOnAction(e -> applyMismatchThresholds.run());
      mmFractionField.setOnAction(e -> applyMismatchThresholds.run());
      mmCountField.setOnAction(e -> applyMismatchThresholds.run());

      Label mismatchInfo = new Label("Use for emSeq/WGBS to suppress bisulfite-conversion mismatches.");
      mismatchInfo.setStyle("-fx-text-fill: #888888; -fx-font-size: 10;");

      mismatchBox.getChildren().addAll(mismatchLabel, suppressMethylCb, mmFractionRow, mmCountRow,
          applyMismatchButton, mismatchInfo);
      settingsMenu.getItems().add(new CustomMenuItem(mismatchBox, false));
    }

    // Read coloring + stacking controls (apply to all BAM files)
    if (!bamFiles.isEmpty()) {
      settingsMenu.getItems().add(new SeparatorMenuItem());
      VBox readRenderBox = new VBox(6);
      readRenderBox.setPadding(new Insets(4, 8, 4, 8));

      Label readRenderLabel = new Label("Read rendering (all tracks)");
      readRenderLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12; -fx-font-weight: bold;");

      HBox colorRow = new HBox(6);
      Label colorLabel = new Label("Read color:");
      colorLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
      ComboBox<ReadColorMode> colorCombo = new ComboBox<>();
      colorCombo.getItems().setAll(bamFiles.get(0).getAvailableColorModes());
      colorCombo.setValue(bamFiles.get(0).getReadColorMode());
      colorCombo.setPrefWidth(200);
      PopupComboBoxStyler.styleDarkComboBox(colorCombo, settingsMenu);
      colorCombo.valueProperty().addListener((obs, oldMode, newMode) -> {
        if (newMode == null) {
          return;
        }
        for (AlignmentFile bam : bamFiles) {
          bam.setReadColorMode(newMode);
        }
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      });
      colorRow.getChildren().addAll(colorLabel, colorCombo);

      HBox stackRow = new HBox(6);
      Label stackLabel = new Label("Stacking:");
      stackLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
      ComboBox<AlignmentFile.ReadStackingMode> stackCombo = new ComboBox<>();
      stackCombo.getItems().setAll(AlignmentFile.ReadStackingMode.values());
      stackCombo.setValue(bamFiles.get(0).getReadStackingMode());
      stackCombo.setPrefWidth(200);
      PopupComboBoxStyler.styleDarkComboBox(stackCombo, settingsMenu);
      stackCombo.valueProperty().addListener((obs, oldMode, newMode) -> {
        if (newMode == null) {
          return;
        }
        for (AlignmentFile bam : bamFiles) {
          bam.setReadStackingMode(newMode);
        }
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      });
      stackRow.getChildren().addAll(stackLabel, stackCombo);

      Label stackInfo = new Label("Stacking modes are mutually exclusive.");
      stackInfo.setStyle("-fx-text-fill: #888888; -fx-font-size: 10;");
      readRenderBox.getChildren().addAll(readRenderLabel, colorRow, stackRow, stackInfo);
      settingsMenu.getItems().add(new CustomMenuItem(readRenderBox, false));
    }

    boolean hasVcfData = VcfManager.getInstance().hasVcfLoaded();
    if (!bamFiles.isEmpty() || hasVcfData) {
      settingsMenu.getItems().add(new SeparatorMenuItem());
      MenuItem circosItem = new MenuItem("Circos plot (reads + VCF translocations)...");
      circosItem.setStyle("-fx-text-fill: #cccccc;");
      circosItem.setOnAction(e -> {
        settingsMenu.hide();
        openCircosPlot();
      });
      settingsMenu.getItems().add(circosItem);
    }

    return settingsMenu;
  }

  /**
   * Collect split-read (SA tag), inter-chromosomal discordant-pair, and VCF
   * translocation / inter-chrom breakend links across every visible sample and
   * open a {@link CircosPlot} window. Uses cached reads when available;
   * otherwise sweeps the BAM/CRAM at the current locus via a streaming query
   * (no caching). VCF links come from TRA/BND calls whose breakpoint is inside
   * a currently visible stack view, matching the read sweep.
   */
  private void openCircosPlot() {
    final List<DrawStack> stacks = new ArrayList<>(stackManager.getStacks());
    final List<SampleSweep> sweeps = new ArrayList<>();
    for (SampleTrack track : sampleRegistry.getSampleTracks()) {
      for (Sample sample : track.getSamples()) {
        AlignmentFile bamFile = sample.getBamFile();
        if (bamFile == null) {
          continue;
        }
        sweeps.add(new SampleSweep(bamFile, sample.getName(), bamFile.getReader().getRefNames()));
      }
    }

    final List<String> chromNames;
    final Map<String, Long> chromLengths = new LinkedHashMap<>();
    ReferenceGenomeService refSvc = ServiceRegistry.getInstance().getReferenceGenomeService();
    if (refSvc != null && refSvc.getCurrentGenome() != null) {
      chromNames = refSvc.getCurrentGenome().getStandardChromosomeNames();
      for (String n : chromNames) {
        try {
          chromLengths.put(n, refSvc.getChromosomeLength(n));
        } catch (Exception ignore) {
        }
      }
    } else {
      chromNames = new ArrayList<>();
    }

    final String currentChrom = masterTrackDrawStack != null ? masterTrackDrawStack.getChromosome() : null;

    final List<ViewWindow> viewWindows = new ArrayList<>();
    for (DrawStack stack : stacks) {
      String stackChrom = stack.getChromosome();
      if (stackChrom == null || stackChrom.isBlank()) {
        continue;
      }
      long viewStart = Math.max(0, (long) stack.getViewStart());
      long viewEnd = (long) stack.getViewEnd();
      if (viewEnd < viewStart) {
        continue;
      }
      viewWindows.add(new ViewWindow(stackChrom, viewStart, viewEnd));
    }

    VcfManager vcfManager = VcfManager.getInstance();
    final List<VcfManager.CachedChromosomeVariants> vcfLists = vcfManager.hasVcfLoaded()
        ? new ArrayList<>(vcfManager.getCachedVariantListsInOrder(chromNames))
        : List.of();
    final VariantFilter vcfFilter = vcfManager.hasVcfLoaded() ? vcfManager.getCurrentFilter().copy() : null;

    Thread t = new Thread(() -> {
      List<CircosPlot.Link> links = new ArrayList<>();
      for (SampleSweep sw : sweeps) {
        for (DrawStack stack : stacks) {
          String stackChrom = stack.getChromosome();
          if (stackChrom == null) {
            continue;
          }

          List<BAMRecord> cached = sw.file.getCachedReads(stack);
          if (cached != null && !cached.isEmpty()) {
            for (BAMRecord r : cached) {
              extractLinks(r, stackChrom, sw.refNames, sw.name, links);
            }
          } else {
            int start = (int) Math.max(0, stack.getViewStart());
            int end = (int) Math.max(start + 1, stack.getViewEnd());
            try {
              sw.file.getReader().queryStreaming(stackChrom, start, end, r -> {
                extractLinks(r, stackChrom, sw.refNames, sw.name, links);
                return true;
              });
            } catch (java.io.IOException ignore) {
            }
          }
        }
      }

      for (VcfManager.CachedChromosomeVariants entry : vcfLists) {
        extractVcfLinks(entry.chromosome(), entry.variants(), vcfFilter, viewWindows, links);
      }

      Platform.runLater(() -> {
        CircosPlot plot = new CircosPlot(
            links,
            chromNames,
            chromLengths,
            currentChrom,
            (chr, pos) -> Platform.runLater(() -> MainController.addStackAtPosition(chr, pos)));
        plot.show();
      });
    }, "circos-sweep");

    t.setDaemon(true);
    t.start();
  }

  /** Extract split-read (SA) and inter-chromosomal discordant-pair links from one record. */
  private static void extractLinks(BAMRecord r, String stackChrom, String[] refNames,
      String sampleName, List<CircosPlot.Link> out) {
    if (r.isSecondary() || r.isSupplementary() || r.isUnmapped()) {
      return;
    }

    if (r.saTag != null && !r.saTag.isBlank()) {
      for (String entry : r.saTag.split(";")) {
        if (entry.isBlank()) {
          continue;
        }
        String[] parts = entry.split(",", -1);
        if (parts.length < 2) {
          continue;
        }
        try {
          int saPos = Integer.parseInt(parts[1]);
          out.add(new CircosPlot.Link(
              stackChrom,
              r.pos + 1,
              parts[0],
              saPos,
              CircosPlot.LinkType.SPLIT_READ,
              sampleName));
        } catch (NumberFormatException ignore) {
        }
      }
    }

    if (r.isPaired() && r.mateRefID >= 0 && r.mateRefID != r.refID
        && r.mateRefID < refNames.length && r.matePos >= 0) {
      out.add(new CircosPlot.Link(
          stackChrom,
          r.pos + 1,
          refNames[r.mateRefID],
          r.matePos + 1,
          CircosPlot.LinkType.DISCORDANT_PAIR,
          sampleName));
    }
  }

  /** Snapshot of a sample's alignment file for off-thread sweeping. */
  private record SampleSweep(AlignmentFile file, String name, String[] refNames) {
  }

  /** Visible genomic interval of one draw stack at circos-open time. */
  private record ViewWindow(String chrom, long start, long end) {
  }

  /**
   * Emit circos links from VCF TRA and inter-chromosomal BND calls whose
   * on-chromosome breakpoint falls inside a visible stack view. Respects the
   * current variant filter without mutating the drawable skip chain (this
   * runs off the FX thread).
   */
  private static void extractVcfLinks(String chromosome, VariantList list,
      VariantFilter filter, List<ViewWindow> viewWindows, List<CircosPlot.Link> out) {
    if (chromosome == null || chromosome.isBlank() || list == null || list.isEmpty()
        || viewWindows == null || viewWindows.isEmpty()) {
      return;
    }
    VariantNode node = list.getFirst();
    while (node != null) {
      if (node.type == VcfVariantType.SV_TRANSLOCATION || node.type == VcfVariantType.SV_BREAKEND) {
        if (inVisibleView(chromosome, node.position, viewWindows)
            && (filter == null || filter.passesNodeLevel(node))) {
          String mateChr = node.mateChromosome();
          long matePos = node.matePosition();
          if (mateChr != null && !mateChr.isBlank() && matePos >= 0
              && !sameChromosomeName(chromosome, mateChr)) {
            int posA = clampGenomicPos(node.position);
            int posB = clampGenomicPos(matePos);
            for (VariantNode.SampleCall call : node.getSamples()) {
              if (filter != null && !filter.passesSampleThresholds(node, call)) {
                continue;
              }
              out.add(new CircosPlot.Link(
                  chromosome,
                  posA,
                  mateChr,
                  posB,
                  CircosPlot.LinkType.VCF_TRA,
                  circosSampleName(call)));
            }
          }
        }
      }
      node = node.next;
    }
  }

  private static boolean inVisibleView(String chromosome, long position, List<ViewWindow> viewWindows) {
    for (ViewWindow window : viewWindows) {
      if (sameChromosomeName(chromosome, window.chrom)
          && position >= window.start
          && position <= window.end) {
        return true;
      }
    }
    return false;
  }

  private static String circosSampleName(VariantNode.SampleCall call) {
    if (call == null) {
      return "(vcf)";
    }
    if (call.sample != null && call.sample.getName() != null && !call.sample.getName().isBlank()) {
      return call.sample.getName();
    }
    SampleTrack track = call.getTrack();
    if (track != null && track.getDisplayName() != null && !track.getDisplayName().isBlank()) {
      return track.getDisplayName();
    }
    return "(vcf)";
  }

  private static boolean sameChromosomeName(String a, String b) {
    return stripChrPrefix(a).equalsIgnoreCase(stripChrPrefix(b));
  }

  private static String stripChrPrefix(String chrom) {
    if (chrom == null) {
      return "";
    }
    if (chrom.length() > 3 && chrom.regionMatches(true, 0, "chr", 0, 3)) {
      return chrom.substring(3);
    }
    return chrom;
  }

  private static int clampGenomicPos(long pos) {
    if (pos <= 0) {
      return 1;
    }
    if (pos > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    return (int) pos;
  }

  private double getMasterTrackWidth() {
    return getMasterHeaderCanvas().getWidth();
  }

  private void zoomAt(double zoomDirection, double targetX) {
    if (masterTrackDrawStack == null
        || zoomDirection == 0.0
        || masterTrackDrawStack.sampleTrackCanvas == null) {
      return;
    }
    int direction = zoomDirection > 0 ? 1 : -1;
    double acceleration = Math.log(Math.abs(zoomDirection) + 1) / 4.0;
    double currentLength = masterTrackDrawStack.getViewEnd() - masterTrackDrawStack.getViewStart();
    double newSize = currentLength - GenomicCanvas.zoomFactor * acceleration * direction;
    double start = masterTrackDrawStack.getViewStart()
        + (currentLength - newSize) * (targetX / getMasterTrackWidth());
    masterTrackDrawStack.sampleTrackCanvas.setStartEnd(start, start + newSize);
  }

  private void panByGenomeDelta(double genomeDelta) {
    if (masterTrackDrawStack == null || masterTrackDrawStack.sampleTrackCanvas == null) {
      return;
    }
    masterTrackDrawStack.sampleTrackCanvas.setStart(
        masterTrackDrawStack.getViewStart() - genomeDelta);
  }
}
