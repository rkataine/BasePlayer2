package org.baseplayer.variant.ui.components;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.baseplayer.io.VcfManager;
import org.baseplayer.project.ProjectDocument;
import org.baseplayer.project.SessionDocumentSync;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Pair;

/**
 * Control Files tab: load a local gnomAD-style sites VCF and annotate population AF.
 */
public class ControlFilesPanel {

    public record Nodes(
        CheckBox filterByPopFreqCheckBox,
        CheckBox useGnomadCheckBox,
        CheckBox use1000GenomesCheckBox,
        CheckBox useExacCheckBox,
        TextField maxPopFreqField,
        CheckBox showPathogenicCheckBox,
        CheckBox hideBenignCheckBox,
        TableView<ControlFileEntry> controlFilesTable,
        TableColumn<ControlFileEntry, Boolean> controlFileEnabledColumn,
        TableColumn<ControlFileEntry, String> controlFileNameColumn,
        TableColumn<ControlFileEntry, String> controlFileTypeColumn,
        TableColumn<ControlFileEntry, String> controlFileActionsColumn
    ) {}

    private Nodes nodes;
    private final ObservableList<ControlFileEntry> entries = FXCollections.observableArrayList();
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
        bindTable();
        bindImmediateFilterApply();
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

    public void writeTo(VariantFilter filter) {
        if (filter == null || nodes == null) {
            return;
        }
        // Only the pop-freq checkbox/max field matter for filtering. No control file
        // open still writes false/default so load-time keys stay unaffected.
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

    /** Persist path + pop-freq toggles into the project document. */
    public void writeToDocument(ProjectDocument.ControlFilesSpec spec) {
        if (spec == null) {
            return;
        }
        Path open = annotator.path();
        spec.controlVcfPath = open != null ? open.toAbsolutePath().normalize().toString() : null;
        spec.filterByControlAf = isFilterByPopFreqSelected();
        spec.maxControlAf = readMaxPopFreq();
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
                return false;
            }
            Path path = Path.of(pathText);
            if (!java.nio.file.Files.isRegularFile(path)) {
                return false;
            }
            try {
                annotator.open(path);
            } catch (IOException e) {
                System.err.println("Could not restore control VCF: " + e.getMessage());
                return false;
            }
            entries.clear();
            entries.add(new ControlFileEntry(true, path.getFileName().toString(), "gnomAD", path));
            if (nodes.useGnomadCheckBox() != null) {
                nodes.useGnomadCheckBox().setSelected(true);
            }
            return true;
        } finally {
            suppressSettingsPersist = previous;
        }
    }

    /** Copy pop-freq toggles from another panel without persisting or re-applying filters. */
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
        if (nodes == null || nodes.controlFilesTable() == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add control VCF (gnomAD sites)");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Compressed VCF", "*.vcf.gz"),
            new FileChooser.ExtensionFilter("VCF", "*.vcf", "*.vcf.gz"));
        Window window = windowSupplier.get();
        File chosen = window != null ? chooser.showOpenDialog(window) : chooser.showOpenDialog(null);
        if (chosen == null) {
            return;
        }
        Path path = chosen.toPath();
        try {
            annotator.open(path);
        } catch (IOException e) {
            showError("Could not open control VCF", e.getMessage());
            return;
        }
        entries.clear();
        ControlFileEntry entry = new ControlFileEntry(true, chosen.getName(), "gnomAD", path);
        entries.add(entry);
        if (nodes.useGnomadCheckBox() != null) {
            nodes.useGnomadCheckBox().setSelected(true);
        }
        SessionDocumentSync.writeControlVcfPath(path.toAbsolutePath().normalize().toString());
        persistFilterSettingsToDocument();
        runAnnotateLoadedSources(true);
    }

    public void handleAddControlBed() {
        showError("Not implemented", "Control BED files are not supported yet.");
    }

    public void handleRemoveControlFile() {
        if (nodes == null || nodes.controlFilesTable() == null) {
            return;
        }
        ControlFileEntry selected = nodes.controlFilesTable().getSelectionModel().getSelectedItem();
        if (selected != null) {
            entries.remove(selected);
        } else {
            entries.clear();
        }
        annotator.close();
        clearControlAfOnSources();
        SessionDocumentSync.writeControlVcfPath(null);
        afterAnnotate.accept(true);
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

    /** Write pop-freq toggles into the live project document immediately. */
    public void persistFilterSettingsToDocument() {
        if (nodes == null) {
            return;
        }
        SessionDocumentSync.writeControlFilterSettings(
            isFilterByPopFreqSelected(), readMaxPopFreq());
    }

    private void runAnnotateLoadedSources(boolean notify) {
        if (annotateExecutor != null) {
            // Completion / applyFilters runs on the FX thread inside the executor.
            annotateExecutor.accept(() -> annotateLoadedSources(false));
        } else {
            annotateLoadedSources(notify);
        }
    }

    public Nodes getNodes() {
        return nodes;
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

    private void bindTable() {
        if (nodes == null || nodes.controlFilesTable() == null) {
            return;
        }
        TableView<ControlFileEntry> table = nodes.controlFilesTable();
        table.setItems(entries);
        table.setEditable(true);

        if (nodes.controlFileEnabledColumn() != null) {
            nodes.controlFileEnabledColumn().setCellValueFactory(cd -> {
                ControlFileEntry e = cd.getValue();
                SimpleBooleanProperty prop = new SimpleBooleanProperty(e.isEnabled());
                prop.addListener((obs, was, is) -> {
                    e.setEnabled(is);
                    onEnabledToggled(e);
                });
                return prop;
            });
            nodes.controlFileEnabledColumn().setCellFactory(
                CheckBoxTableCell.forTableColumn(nodes.controlFileEnabledColumn()));
            nodes.controlFileEnabledColumn().setEditable(true);
        }
        if (nodes.controlFileNameColumn() != null) {
            nodes.controlFileNameColumn().setCellValueFactory(
                cd -> new SimpleStringProperty(cd.getValue().getFileName()));
        }
        if (nodes.controlFileTypeColumn() != null) {
            nodes.controlFileTypeColumn().setCellValueFactory(
                cd -> new SimpleStringProperty(cd.getValue().getFileType()));
        }
        if (nodes.controlFileActionsColumn() != null) {
            nodes.controlFileActionsColumn().setCellValueFactory(
                cd -> new SimpleStringProperty(""));
        }
    }

    private void onEnabledToggled(ControlFileEntry entry) {
        if (entry == null) {
            return;
        }
                if (entry.isEnabled() && entry.getPath() != null) {
            try {
                if (!annotator.isOpen() || !entry.getPath().equals(annotator.path())) {
                    annotator.open(entry.getPath());
                }
                runAnnotateLoadedSources(true);
            } catch (IOException e) {
                entry.setEnabled(false);
                showError("Could not open control VCF", e.getMessage());
            }
        } else {
            annotator.close();
            clearControlAfOnSources();
            afterAnnotate.accept(true);
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
        CheckBox filterByPop = new CheckBox("Filter by population frequency");
        filterByPop.getStyleClass().add("filter-checkbox");
        TextField maxFreq = new TextField("0.01");
        maxFreq.setPrefWidth(80);
        maxFreq.getStyleClass().add("filter-field");
        CheckBox gnomad = new CheckBox("gnomAD");
        gnomad.getStyleClass().add("filter-checkbox");
        CheckBox genomes = new CheckBox("1000 Genomes");
        genomes.getStyleClass().add("filter-checkbox");
        CheckBox exac = new CheckBox("ExAC");
        exac.getStyleClass().add("filter-checkbox");
        CheckBox pathogenic = new CheckBox("Show only pathogenic/likely pathogenic variants");
        pathogenic.getStyleClass().add("filter-checkbox");
        CheckBox hideBenign = new CheckBox("Hide benign/likely benign variants");
        hideBenign.getStyleClass().add("filter-checkbox");

        VBox left = new VBox(12);
        left.getStyleClass().add("filter-panel");
        left.setPadding(new Insets(16));
        Label popTitle = new Label("Population Frequency Databases");
        popTitle.getStyleClass().add("section-header");
        HBox popRow = new HBox(8, filterByPop,
            new Label("Max frequency:") {{ getStyleClass().add("subsection-label"); }},
            maxFreq);
        popRow.setAlignment(Pos.CENTER_LEFT);
        HBox dbRow = new HBox(8, gnomad, genomes, exac);
        Label clinTitle = new Label("ClinVar Pathogenicity");
        clinTitle.getStyleClass().add("section-header");
        left.getChildren().addAll(popTitle, popRow, dbRow, clinTitle, pathogenic, hideBenign);

        TableView<ControlFileEntry> table = new TableView<>();
        table.getStyleClass().add("control-files-table");
        table.setPrefHeight(150);
        TableColumn<ControlFileEntry, Boolean> enabledCol = new TableColumn<>("Enabled");
        enabledCol.setPrefWidth(70);
        TableColumn<ControlFileEntry, String> nameCol = new TableColumn<>("File Name");
        nameCol.setPrefWidth(250);
        TableColumn<ControlFileEntry, String> typeCol = new TableColumn<>("Type");
        typeCol.setPrefWidth(100);
        TableColumn<ControlFileEntry, String> actionsCol = new TableColumn<>("Actions");
        actionsCol.setPrefWidth(100);
        table.getColumns().add(enabledCol);
        table.getColumns().add(nameCol);
        table.getColumns().add(typeCol);
        table.getColumns().add(actionsCol);

        Button addVcf = new Button("Add VCF");
        addVcf.getStyleClass().add("secondary-button");
        Button addBed = new Button("Add BED");
        addBed.getStyleClass().add("secondary-button");
        Button remove = new Button("Remove Selected");
        remove.getStyleClass().add("secondary-button");
        Button apply = new Button("Apply Settings");
        apply.getStyleClass().add("primary-button");

        VBox right = new VBox(12);
        right.getStyleClass().add("filter-panel");
        right.setPadding(new Insets(16));
        Label customTitle = new Label("Custom Control Files");
        customTitle.getStyleClass().add("section-header");
        HBox buttons = new HBox(8, addVcf, addBed, remove);
        HBox applyRow = new HBox(apply);
        applyRow.setAlignment(Pos.CENTER_RIGHT);
        VBox.setVgrow(table, Priority.ALWAYS);
        right.getChildren().addAll(customTitle, table, buttons, applyRow);

        SplitPane split = new SplitPane(
            new ScrollPane(left) {{
                setFitToWidth(true);
                getStyleClass().add("filter-scroll");
            }},
            new ScrollPane(right) {{
                setFitToWidth(true);
                getStyleClass().add("filter-scroll");
            }});
        split.setDividerPositions(0.5);
        split.getStyleClass().add("tab-content-split");

        Nodes built = new Nodes(
            filterByPop, gnomad, genomes, exac, maxFreq, pathogenic, hideBenign,
            table, enabledCol, nameCol, typeCol, actionsCol);

        // Wire after the panel that owns this UI is installed; store handlers on the nodes via userData.
        addVcf.setOnAction(e -> {
            Object panel = split.getUserData();
            if (panel instanceof ControlFilesPanel c) {
                c.handleAddControlVcf();
            }
        });
        addBed.setOnAction(e -> {
            Object panel = split.getUserData();
            if (panel instanceof ControlFilesPanel c) {
                c.handleAddControlBed();
            }
        });
        remove.setOnAction(e -> {
            Object panel = split.getUserData();
            if (panel instanceof ControlFilesPanel c) {
                c.handleRemoveControlFile();
            }
        });
        apply.setOnAction(e -> {
            Object panel = split.getUserData();
            if (panel instanceof ControlFilesPanel c) {
                c.handleApplyControlSettings();
            }
        });

        return new Pair<>(split, built);
    }

    public static class ControlFileEntry {
        private boolean enabled;
        private String fileName;
        private String fileType;
        private Path path;

        public ControlFileEntry(boolean enabled, String fileName, String fileType) {
            this(enabled, fileName, fileType, null);
        }

        public ControlFileEntry(boolean enabled, String fileName, String fileType, Path path) {
            this.enabled = enabled;
            this.fileName = fileName;
            this.fileType = fileType;
            this.path = path;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getFileType() { return fileType; }
        public void setFileType(String fileType) { this.fileType = fileType; }
        public Path getPath() { return path; }
        public void setPath(Path path) { this.path = path; }
    }
}
