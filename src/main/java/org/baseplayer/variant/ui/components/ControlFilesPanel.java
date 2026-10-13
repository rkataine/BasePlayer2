package org.baseplayer.variant.ui.components;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.baseplayer.components.ColoredToggle;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.io.VcfManager;
import org.baseplayer.project.ProjectDocument;
import org.baseplayer.project.SessionDocumentSync;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator.ControlFileStats;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator.PopulationOption;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Pair;

/**
 * Control Files tab: load a local gnomAD-style sites VCF, pick population AF fields,
 * and optionally filter sample variants by control allele frequency.
 */
public class ControlFilesPanel {

    private static final int STATS_SAMPLE_ROWS = 10_000;

    public record Nodes(
        Label controlFilePathLabel,
        Button addControlVcfButton,
        Button removeControlFileButton,
        ColoredToggle filterByPopFreqCheckBox,
        TextField maxPopFreqField,
        ComboBox<PopulationOption> populationCombo,
        Label controlFileStatsLabel
    ) {}

    private Nodes nodes;
    private final ControlFrequencyAnnotator annotator = new ControlFrequencyAnnotator();

    private Supplier<Window> windowSupplier = () -> null;
    private Supplier<List<VcfManager.CachedChromosomeVariants>> sourcesSupplier = List::of;
    /** Called after annotate/clear so the controller can rebuild tables / apply filters. */
    private Consumer<Boolean> afterAnnotate = ignored -> {};
    /** Pop-freq checkbox / max-freq field changed — apply filter without re-annotating. */
    private Runnable onFilterSettingsChanged = () -> {};
    /**
     * Optional: keep a sibling Control Files panel (Point ↔ SV) in sync so both
     * modes share the same durable settings.
     */
    private Consumer<ControlFilesPanel> onSettingsMirrored = ignored -> {};
    /** Suppress mirror/listener side-effects while applying programmatic UI updates. */
    private boolean suppressSettingsPersist;
    /**
     * When set, annotation work is handed to the controller (background + loading modal)
     * instead of running on the FX thread.
     */
    private Consumer<Runnable> annotateExecutor;

    public void install(Nodes nodes) {
        this.nodes = nodes;
        wireButtons();
        bindImmediateFilterApply();
        refreshFileUi();
    }

    public void setWindowSupplier(Supplier<Window> windowSupplier) {
        this.windowSupplier = windowSupplier != null ? windowSupplier : () -> null;
    }

    public void setSourcesSupplier(
            Supplier<List<VcfManager.CachedChromosomeVariants>> sourcesSupplier) {
        this.sourcesSupplier = sourcesSupplier != null ? sourcesSupplier : List::of;
    }

    /**
     * @param afterAnnotate receives {@code true} when annotation ran (or cleared) and
     *        the controller should apply filters / rebuild
     */
    public void setAfterAnnotate(Consumer<Boolean> afterAnnotate) {
        this.afterAnnotate = afterAnnotate != null ? afterAnnotate : ignored -> {};
    }

    public void setOnFilterSettingsChanged(Runnable onFilterSettingsChanged) {
        this.onFilterSettingsChanged =
            onFilterSettingsChanged != null ? onFilterSettingsChanged : () -> {};
    }

    public void setOnSettingsMirrored(Consumer<ControlFilesPanel> onSettingsMirrored) {
        this.onSettingsMirrored = onSettingsMirrored != null ? onSettingsMirrored : ignored -> {};
    }

    /**
     * Run heavy annotate work via {@code executor.accept(work)}. When null, annotate
     * runs synchronously on the calling thread.
     */
    public void setAnnotateExecutor(Consumer<Runnable> annotateExecutor) {
        this.annotateExecutor = annotateExecutor;
    }

    public ControlFrequencyAnnotator annotator() {
        return annotator;
    }

    public Nodes getNodes() {
        return nodes;
    }

    public void writeTo(VariantFilter filter) {
        if (filter == null || nodes == null) {
            return;
        }
        filter.setFilterByControlAf(isFilterByPopFreqSelected());
        filter.setMaxControlAf(readMaxPopFreq());
    }

    public void loadFrom(VariantFilter filter) {
        if (filter == null || nodes == null) {
            return;
        }
        boolean previous = suppressSettingsPersist;
        suppressSettingsPersist = true;
        try {
            if (nodes.filterByPopFreqCheckBox() != null) {
                nodes.filterByPopFreqCheckBox().setSelected(filter.isFilterByControlAf());
            }
            if (nodes.maxPopFreqField() != null) {
                nodes.maxPopFreqField().setText(String.valueOf(filter.getMaxControlAf()));
            }
        } finally {
            suppressSettingsPersist = previous;
        }
    }

    /** Persist path + pop-freq toggles + population into the project document. */
    public void writeToDocument(ProjectDocument.ControlFilesSpec spec) {
        if (spec == null) {
            return;
        }
        Path open = annotator.path();
        spec.controlVcfPath = open != null ? open.toAbsolutePath().normalize().toString() : null;
        spec.filterByControlAf = isFilterByPopFreqSelected();
        spec.maxControlAf = readMaxPopFreq();
        String pop = annotator.populationId();
        spec.controlPopulation = pop == null || pop.isBlank() ? null : pop;
    }

    /**
     * Restore control VCF from a saved project. Opens the reader when the file exists;
     * does <b>not</b> re-annotate — chromosome caches already hold {@code controlAf}
     * when {@link VariantList#getControlAfSourcePath()} matches.
     *
     * @return true when a control file was opened
     */
    public boolean restoreFromDocument(ProjectDocument.ControlFilesSpec spec) {
        if (spec == null || nodes == null) {
            return false;
        }
        boolean previous = suppressSettingsPersist;
        suppressSettingsPersist = true;
        try {
            if (nodes.filterByPopFreqCheckBox() != null) {
                nodes.filterByPopFreqCheckBox().setSelected(spec.filterByControlAf);
            }
            if (nodes.maxPopFreqField() != null) {
                nodes.maxPopFreqField().setText(String.valueOf(
                    spec.maxControlAf > 0 ? spec.maxControlAf : 0.01));
            }
            String pathText = spec.controlVcfPath;
            if (pathText == null || pathText.isBlank()) {
                refreshFileUi();
                return false;
            }
            Path path = Path.of(pathText);
            if (!java.nio.file.Files.isRegularFile(path)) {
                refreshFileUi();
                return false;
            }
            try {
                annotator.open(path);
            } catch (IOException e) {
                System.err.println("Could not restore control VCF: " + e.getMessage());
                refreshFileUi();
                return false;
            }
            refreshPopulations(spec.controlPopulation);
            refreshFileUi();
            refreshStatsAsync();
            return true;
        } finally {
            suppressSettingsPersist = previous;
        }
    }

    /** Copy pop-freq toggles and population from another panel without persisting. */
    public void mirrorFilterSettingsFrom(ControlFilesPanel other) {
        if (other == null || other == this || nodes == null) {
            return;
        }
        boolean previous = suppressSettingsPersist;
        suppressSettingsPersist = true;
        try {
            if (nodes.filterByPopFreqCheckBox() != null) {
                nodes.filterByPopFreqCheckBox().setSelected(other.isFilterByPopFreqSelected());
            }
            if (nodes.maxPopFreqField() != null) {
                nodes.maxPopFreqField().setText(String.valueOf(other.readMaxPopFreq()));
            }
            String pop = other.annotator.populationId();
            annotator.setPopulationId(pop);
            refreshPopulations(pop);
            if (other.annotator.isOpen() && other.annotator.path() != null) {
                Path path = other.annotator.path();
                if (!annotator.isOpen() || !path.equals(annotator.path())) {
                    try {
                        annotator.open(path);
                    } catch (IOException e) {
                        System.err.println("Could not mirror control VCF: " + e.getMessage());
                    }
                }
            } else if (!other.annotator.isOpen() && annotator.isOpen()) {
                annotator.close();
            }
            refreshFileUi();
            if (nodes.controlFileStatsLabel() != null && other.nodes != null
                    && other.nodes.controlFileStatsLabel() != null) {
                nodes.controlFileStatsLabel().setText(other.nodes.controlFileStatsLabel().getText());
            }
        } finally {
            suppressSettingsPersist = previous;
        }
    }

    private boolean isFilterByPopFreqSelected() {
        return nodes != null
            && nodes.filterByPopFreqCheckBox() != null
            && nodes.filterByPopFreqCheckBox().isSelected();
    }

    private double readMaxPopFreq() {
        double maxAf = 0.01;
        if (nodes != null && nodes.maxPopFreqField() != null) {
            String text = nodes.maxPopFreqField().getText();
            if (text != null && !text.isBlank()) {
                try {
                    maxAf = Double.parseDouble(text.trim());
                } catch (NumberFormatException ignored) {
                    maxAf = 0.01;
                }
            }
        }
        return maxAf;
    }

    public void handleApplyControlSettings() {
        runAnnotateLoadedSources(true);
    }

    public void handleAddControlVcf() {
        if (nodes == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add control VCF (gnomAD sites)");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Compressed VCF", "*.vcf.gz"),
            new FileChooser.ExtensionFilter("VCF", "*.vcf", "*.vcf.gz"));
        File initialDir = UserPreferences.getLastDirectory("CONTROL_VCF");
        if (initialDir == null) {
            Path open = annotator.path();
            if (open != null && open.getParent() != null) {
                initialDir = open.getParent().toFile();
            }
        }
        if (initialDir == null) {
            initialDir = UserPreferences.getLastDirectory("VCF");
        }
        if (initialDir != null) {
            chooser.setInitialDirectory(initialDir);
        }
        Window window = windowSupplier.get();
        File chosen = window != null ? chooser.showOpenDialog(window) : chooser.showOpenDialog(null);
        if (chosen == null) {
            return;
        }
        UserPreferences.setLastDirectory("CONTROL_VCF", chosen.getParentFile());
        Path path = chosen.toPath();
        try {
            annotator.open(path);
        } catch (IOException e) {
            showError("Could not open control VCF", e.getMessage());
            return;
        }
        refreshPopulations(annotator.populationId());
        refreshFileUi();
        SessionDocumentSync.writeControlVcfPath(path.toAbsolutePath().normalize().toString());
        persistFilterSettingsToDocument();
        refreshStatsAsync();
        onSettingsMirrored.accept(this);
        runAnnotateLoadedSources(true);
    }

    public void handleRemoveControlFile() {
        annotator.close();
        clearControlAfOnSources();
        refreshPopulations(null);
        refreshFileUi();
        setStatsText("No control file loaded.");
        SessionDocumentSync.writeControlVcfPath(null);
        persistFilterSettingsToDocument();
        onSettingsMirrored.accept(this);
        afterAnnotate.accept(true);
    }

    private void wireButtons() {
        if (nodes == null) {
            return;
        }
        if (nodes.addControlVcfButton() != null) {
            nodes.addControlVcfButton().setOnAction(e -> handleAddControlVcf());
        }
        if (nodes.removeControlFileButton() != null) {
            nodes.removeControlFileButton().setOnAction(e -> handleRemoveControlFile());
        }
        if (nodes.populationCombo() != null) {
            nodes.populationCombo().setOnAction(e -> onPopulationChanged());
        }
    }

    private void bindImmediateFilterApply() {
        if (nodes == null) {
            return;
        }
        if (nodes.filterByPopFreqCheckBox() != null) {
            nodes.filterByPopFreqCheckBox().selectedProperty().addListener(
                (obs, was, is) -> onControlFilterUiChanged());
        }
        if (nodes.maxPopFreqField() != null) {
            nodes.maxPopFreqField().textProperty().addListener(
                (obs, oldText, newText) -> onControlFilterUiChanged());
        }
    }

    private void onControlFilterUiChanged() {
        if (suppressSettingsPersist) {
            return;
        }
        persistFilterSettingsToDocument();
        onSettingsMirrored.accept(this);
        onFilterSettingsChanged.run();
    }

    private void onPopulationChanged() {
        if (suppressSettingsPersist || nodes == null || nodes.populationCombo() == null) {
            return;
        }
        PopulationOption selected = nodes.populationCombo().getSelectionModel().getSelectedItem();
        String popId = selected != null ? selected.id() : ControlFrequencyAnnotator.GLOBAL_POPULATION;
        String previous = annotator.populationId();
        annotator.setPopulationId(popId);
        if (previous.equals(annotator.populationId())) {
            return;
        }
        SessionDocumentSync.writeControlPopulation(annotator.populationId());
        persistFilterSettingsToDocument();
        onSettingsMirrored.accept(this);
        refreshStatsAsync();
        if (annotator.isOpen()) {
            runAnnotateLoadedSources(true);
        }
    }

    /** Write pop-freq toggles + population into the live project document immediately. */
    public void persistFilterSettingsToDocument() {
        if (nodes == null) {
            return;
        }
        SessionDocumentSync.writeControlFilterSettings(
            isFilterByPopFreqSelected(),
            readMaxPopFreq(),
            annotator.populationId());
    }

    private void runAnnotateLoadedSources(boolean notify) {
        if (annotateExecutor != null) {
            annotateExecutor.accept(() -> annotateLoadedSources(false));
        } else {
            annotateLoadedSources(notify);
        }
    }

    /** Annotate every loaded chromosome list with the current control VCF. */
    public void annotateLoadedSources(boolean notify) {
        if (!annotator.isOpen()) {
            if (notify) {
                afterAnnotate.accept(true);
            }
            return;
        }
        List<VcfManager.CachedChromosomeVariants> sources = sourcesSupplier.get();
        if (sources != null) {
            for (VcfManager.CachedChromosomeVariants cached : sources) {
                if (cached == null || cached.variants() == null) {
                    continue;
                }
                try {
                    annotator.annotate(cached.variants());
                } catch (IOException e) {
                    showError("Control annotation failed",
                        cached.chromosome() + ": " + e.getMessage());
                    break;
                }
            }
        }
        if (notify) {
            afterAnnotate.accept(true);
        }
    }

    /** Annotate one list quietly (e.g. after gene annotation finishes). */
    public void annotateIfOpen(VariantList list) {
        if (!annotator.isOpen() || list == null) {
            return;
        }
        try {
            annotator.annotate(list);
        } catch (IOException e) {
            System.err.println("Control AF annotation failed for "
                + list.getChromosome() + ": " + e.getMessage());
        }
    }

    private void clearControlAfOnSources() {
        List<VcfManager.CachedChromosomeVariants> sources = sourcesSupplier.get();
        if (sources == null) {
            return;
        }
        for (VcfManager.CachedChromosomeVariants cached : sources) {
            if (cached != null) {
                ControlFrequencyAnnotator.clear(cached.variants());
            }
        }
    }

    private void refreshFileUi() {
        if (nodes == null) {
            return;
        }
        Path open = annotator.path();
        if (nodes.controlFilePathLabel() != null) {
            if (open != null) {
                nodes.controlFilePathLabel().setText(open.toAbsolutePath().normalize().toString());
            } else {
                nodes.controlFilePathLabel().setText("No control file loaded");
            }
        }
        if (nodes.removeControlFileButton() != null) {
            nodes.removeControlFileButton().setDisable(open == null);
        }
        if (nodes.populationCombo() != null) {
            nodes.populationCombo().setDisable(open == null);
        }
    }

    private void refreshPopulations(String preferredId) {
        if (nodes == null || nodes.populationCombo() == null) {
            return;
        }
        boolean previous = suppressSettingsPersist;
        suppressSettingsPersist = true;
        try {
            List<PopulationOption> options = annotator.listPopulations();
            nodes.populationCombo().setItems(FXCollections.observableArrayList(options));
            String want = ControlFrequencyAnnotator.normalizePopulationId(preferredId);
            PopulationOption match = null;
            for (PopulationOption opt : options) {
                if (opt.id().equals(want)) {
                    match = opt;
                    break;
                }
            }
            if (match == null && !options.isEmpty()) {
                match = options.get(0);
            }
            if (match != null) {
                nodes.populationCombo().getSelectionModel().select(match);
                annotator.setPopulationId(match.id());
            }
        } finally {
            suppressSettingsPersist = previous;
        }
    }

    private void refreshStatsAsync() {
        if (!annotator.isOpen()) {
            setStatsText("No control file loaded.");
            return;
        }
        setStatsText("Sampling control file…");
        Path path = annotator.path();
        String pop = annotator.populationId();
        Thread t = new Thread(() -> {
            ControlFileStats stats;
            try (ControlFrequencyAnnotator peek = new ControlFrequencyAnnotator()) {
                peek.open(path);
                peek.setPopulationId(pop);
                stats = peek.sampleStats(STATS_SAMPLE_ROWS);
            } catch (IOException e) {
                Platform.runLater(() -> setStatsText("Could not sample control file: " + e.getMessage()));
                return;
            }
            ControlFileStats finalStats = stats;
            Platform.runLater(() -> {
                if (!annotator.isOpen() || path == null || !path.equals(annotator.path())) {
                    return;
                }
                setStatsText(finalStats.summaryText());
            });
        }, "control-vcf-stats");
        t.setDaemon(true);
        t.start();
    }

    private void setStatsText(String text) {
        if (nodes != null && nodes.controlFileStatsLabel() != null) {
            nodes.controlFileStatsLabel().setText(text != null ? text : "");
        }
    }

    private void showError(String title, String detail) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(detail != null ? detail : "");
        Window window = windowSupplier.get();
        if (window != null) {
            alert.initOwner(window);
        }
        alert.showAndWait();
    }

    /**
     * Build a Control Files UI tree programmatically (for the SV mode workspace).
     */
    public static Pair<Node, Nodes> buildUi() {
        Label pathLabel = new Label("No control file loaded");
        pathLabel.getStyleClass().add("value-label");
        pathLabel.setWrapText(true);

        Button addVcf = new Button("Add VCF");
        addVcf.getStyleClass().add("secondary-button");
        Button remove = new Button("Remove");
        remove.getStyleClass().add("secondary-button");
        remove.setDisable(true);

        ColoredToggle filterByPop = new ColoredToggle("Filter by control allele frequency");
        TextField maxFreq = new TextField("0.01");
        maxFreq.setPrefWidth(80);
        maxFreq.getStyleClass().add("filter-field");

        ComboBox<PopulationOption> populationCombo = new ComboBox<>();
        populationCombo.setPrefWidth(260);
        populationCombo.setDisable(true);
        populationCombo.getStyleClass().add("filter-field");

        Label statsLabel = new Label("No control file loaded.");
        statsLabel.getStyleClass().add("value-label");
        statsLabel.setWrapText(true);

        VBox left = new VBox(12);
        left.getStyleClass().add("filter-panel");
        left.setPadding(new Insets(16));

        Label fileTitle = new Label("Control file");
        fileTitle.getStyleClass().add("section-header");
        HBox fileButtons = new HBox(8, addVcf, remove);
        fileButtons.setAlignment(Pos.CENTER_LEFT);

        Label filterTitle = new Label("Allele frequency filter");
        filterTitle.getStyleClass().add("section-header");
        HBox filterRow = new HBox(8, filterByPop,
            new Label("Max AF:") {{ getStyleClass().add("subsection-label"); }},
            maxFreq);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        Label popTitle = new Label("Population");
        popTitle.getStyleClass().add("section-header");
        Label popHint = new Label("Uses AF / AC+AN, or population fields such as AF_fin when present.");
        popHint.getStyleClass().add("subsection-label");
        popHint.setWrapText(true);

        left.getChildren().addAll(
            fileTitle, pathLabel, fileButtons,
            filterTitle, filterRow,
            popTitle, popHint, populationCombo);

        VBox right = new VBox(12);
        right.getStyleClass().add("filter-panel");
        right.setPadding(new Insets(16));
        Label statsTitle = new Label("File statistics");
        statsTitle.getStyleClass().add("section-header");
        right.getChildren().addAll(statsTitle, statsLabel);

        ScrollPane leftScroll = new ScrollPane(left);
        leftScroll.setFitToWidth(true);
        leftScroll.getStyleClass().add("filter-scroll");
        ScrollPane rightScroll = new ScrollPane(right);
        rightScroll.setFitToWidth(true);
        rightScroll.getStyleClass().add("filter-scroll");

        SplitPane split = new SplitPane(leftScroll, rightScroll);
        split.setDividerPositions(0.55);
        split.getStyleClass().add("tab-content-split");
        VBox.setVgrow(split, Priority.ALWAYS);

        Button apply = new Button("Apply");
        apply.getStyleClass().add("primary-button");
        HBox applyRow = new HBox(apply);
        applyRow.setAlignment(Pos.CENTER_RIGHT);
        applyRow.getStyleClass().add("filter-panel");
        applyRow.setPadding(new Insets(8, 16, 12, 16));

        VBox root = new VBox(split, applyRow);

        Nodes built = new Nodes(
            pathLabel, addVcf, remove, filterByPop, maxFreq, populationCombo, statsLabel);

        apply.setOnAction(e -> {
            Object panel = root.getUserData();
            if (panel instanceof ControlFilesPanel c) {
                c.handleApplyControlSettings();
            }
        });

        return new Pair<>(root, built);
    }
}
