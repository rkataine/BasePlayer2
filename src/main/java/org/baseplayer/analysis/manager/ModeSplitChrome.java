package org.baseplayer.analysis.manager;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Shared chrome for Point / SV / Signal mode workspaces: tool tabs above a results
 * node in a vertical {@link SplitPane}.
 */
final class ModeSplitChrome {

    private ModeSplitChrome() {}

    /** Tool tabs on top, results below; divider drag expands results, double-click maximizes. */
    static void mount(SplitPane split, TabPane tools, Node results, double divider) {
        tools.setMinHeight(48);
        tools.setPrefHeight(Region.USE_COMPUTED_SIZE);
        if (results instanceof Region resultsRegion) {
            resultsRegion.setMinHeight(120);
        }
        split.setOrientation(Orientation.VERTICAL);
        split.getItems().setAll(tools, results);
        split.setDividerPositions(divider);
        // Allow collapsing filters almost fully so the table can fill the window.
        SplitPane.setResizableWithParent(tools, true);
        SplitPane.setResizableWithParent(results, true);
        wireResultsExpandDivider(split);
    }

    /**
     * Make the vertical results divider easy to use: drag expands the table over filters;
     * double-click maximizes results.
     */
    static void wireResultsExpandDivider(SplitPane split) {
        if (split == null) {
            return;
        }
        if (!split.getStyleClass().contains("analysis-manager-split")) {
            split.getStyleClass().add("analysis-manager-split");
        }
        Runnable apply = () -> {
            for (Node child : split.lookupAll(".split-pane-divider")) {
                if (!child.getStyleClass().contains("variant-results-divider")) {
                    child.getStyleClass().add("variant-results-divider");
                }
                if (child instanceof Region region) {
                    // Must call setters — minHeight()/prefHeight() only return properties.
                    region.setMinHeight(18);
                    region.setPrefHeight(18);
                    region.setMaxHeight(18);
                }
                child.setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2) {
                        // Collapse tool/filter pane; results take nearly the full height.
                        split.setDividerPositions(0.06);
                        e.consume();
                    }
                });
            }
        };
        Platform.runLater(apply);
        // Skin/dividers may appear after first layout.
        split.skinProperty().addListener((obs, o, n) -> Platform.runLater(apply));
    }

    static Tab findTabByText(TabPane pane, String text) {
        if (pane == null || text == null) {
            return null;
        }
        for (Tab tab : pane.getTabs()) {
            if (text.equals(tab.getText())) {
                return tab;
            }
        }
        return null;
    }

    /**
     * Signal Control Files: same VCF control chrome for session sharing, with a clear note
     * that Signal group comparison uses sample groups — not AF/ClinVar controls.
     */
    static Node wrapSignalControlNote(Node controlUi) {
        Label note = new Label(
            "Control files filter VCF population AF / ClinVar. "
                + "Signal group comparison uses sample groups from Sample Organization; "
                + "value thresholds live under Filters.");
        note.setWrapText(true);
        note.getStyleClass().add("subsection-label");
        note.setPadding(new Insets(8, 12, 4, 12));
        VBox wrap = new VBox(4, note, controlUi);
        VBox.setVgrow(controlUi, Priority.ALWAYS);
        return wrap;
    }
}
