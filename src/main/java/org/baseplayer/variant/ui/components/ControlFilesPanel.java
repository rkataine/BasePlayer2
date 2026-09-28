package org.baseplayer.variant.ui.components;

import org.baseplayer.variant.VariantFilter;

import javafx.scene.control.CheckBox;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

/**
 * Control Files tab UI. Mostly stubs until control-file filtering is wired.
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

    public void install(Nodes nodes) {
        this.nodes = nodes;
    }

    /**
     * Control-file settings are not currently part of {@link VariantFilter} /
     * buildFilterFromUI; stub for future wiring.
     */
    public void writeTo(VariantFilter filter) {
        // no-op until control file settings are modeled on VariantFilter
    }

    /**
     * Stub for restoring pop-freq / clinvar checkbox state when supported.
     */
    public void loadFrom(VariantFilter filter) {
        // no-op until control file settings are modeled on VariantFilter
    }

    public void handleApplyControlSettings() {
        System.out.println("Control file settings not yet implemented");
    }

    public void handleAddControlVcf() {
        System.out.println("Add control VCF not yet implemented");
    }

    public void handleAddControlBed() {
        System.out.println("Add control BED not yet implemented");
    }

    public void handleRemoveControlFile() {
        System.out.println("Remove control file not yet implemented");
    }

    public Nodes getNodes() {
        return nodes;
    }

    /**
     * Build a Control Files UI tree programmatically (for the SV mode workspace).
     */
    public static javafx.util.Pair<javafx.scene.Node, Nodes> buildUi() {
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

        javafx.scene.layout.VBox left = new javafx.scene.layout.VBox(12);
        left.getStyleClass().add("filter-panel");
        left.setPadding(new javafx.geometry.Insets(16));
        javafx.scene.control.Label popTitle = new javafx.scene.control.Label("Population Frequency Databases");
        popTitle.getStyleClass().add("section-header");
        javafx.scene.layout.HBox popRow = new javafx.scene.layout.HBox(8, filterByPop,
            new javafx.scene.control.Label("Max frequency:") {{ getStyleClass().add("subsection-label"); }},
            maxFreq);
        popRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        javafx.scene.layout.HBox dbRow = new javafx.scene.layout.HBox(8, gnomad, genomes, exac);
        javafx.scene.control.Label clinTitle = new javafx.scene.control.Label("ClinVar Pathogenicity");
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

        javafx.scene.control.Button addVcf = new javafx.scene.control.Button("Add VCF");
        addVcf.getStyleClass().add("secondary-button");
        javafx.scene.control.Button addBed = new javafx.scene.control.Button("Add BED");
        addBed.getStyleClass().add("secondary-button");
        javafx.scene.control.Button remove = new javafx.scene.control.Button("Remove Selected");
        remove.getStyleClass().add("secondary-button");
        javafx.scene.control.Button apply = new javafx.scene.control.Button("Apply Settings");
        apply.getStyleClass().add("primary-button");

        javafx.scene.layout.VBox right = new javafx.scene.layout.VBox(12);
        right.getStyleClass().add("filter-panel");
        right.setPadding(new javafx.geometry.Insets(16));
        javafx.scene.control.Label customTitle = new javafx.scene.control.Label("Custom Control Files");
        customTitle.getStyleClass().add("section-header");
        javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(8, addVcf, addBed, remove);
        javafx.scene.layout.HBox applyRow = new javafx.scene.layout.HBox(apply);
        applyRow.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        javafx.scene.layout.VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        right.getChildren().addAll(customTitle, table, buttons, applyRow);

        javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane(
            new javafx.scene.control.ScrollPane(left) {{
                setFitToWidth(true);
                getStyleClass().add("filter-scroll");
            }},
            new javafx.scene.control.ScrollPane(right) {{
                setFitToWidth(true);
                getStyleClass().add("filter-scroll");
            }});
        split.setDividerPositions(0.5);
        split.getStyleClass().add("tab-content-split");

        Nodes built = new Nodes(
            filterByPop, gnomad, genomes, exac, maxFreq, pathogenic, hideBenign,
            table, enabledCol, nameCol, typeCol, actionsCol);
        return new javafx.util.Pair<>(split, built);
    }

    public static class ControlFileEntry {
        private boolean enabled;
        private String fileName;
        private String fileType;

        public ControlFileEntry(boolean enabled, String fileName, String fileType) {
            this.enabled = enabled;
            this.fileName = fileName;
            this.fileType = fileType;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getFileType() { return fileType; }
        public void setFileType(String fileType) { this.fileType = fileType; }
    }
}
