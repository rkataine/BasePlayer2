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
