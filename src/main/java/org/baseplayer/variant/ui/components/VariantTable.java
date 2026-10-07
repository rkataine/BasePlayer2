package org.baseplayer.variant.ui.components;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.variant.VariantNode;

import javafx.scene.control.Tab;
import javafx.scene.control.TableView;

/**
 * Point-variant results (Gene / Intronic / Intergenic) using the shared nested table look.
 */
public class VariantTable extends AbstractNestedVariantTable {

    public VariantTable(
            TableView<VariantNode> codingTable,
            TableView<VariantNode> intronicTable,
            TableView<VariantNode> intergenicTable,
            Tab codingTab,
            Tab intronicTab,
            Tab intergenicTab,
            BiConsumer<String, List<SampleTrack>> onGeneDoubleClick,
            Consumer<TableRow> onPositionClick) {
        super(
            codingTable,
            intronicTable,
            intergenicTable,
            codingTab,
            intronicTab,
            intergenicTab,
            onGeneDoubleClick,
            onPositionClick);
    }
}
