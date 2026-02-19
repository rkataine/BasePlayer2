package org.baseplayer.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.paint.Color;

/**
 * Data model for a generic {@link InfoPopup}.
 *
 * <p>Use the fluent builder methods to declare what the popup should show, then
 * hand the object to {@link InfoPopup#show}. The popup rebuilds its UI every time
 * {@code show} is called, so a single {@code InfoPopup} instance can be reused for
 * different content.
 *
 * <h3>Quick example</h3>
 * <pre>{@code
 * PopupContent content = new PopupContent()
 *     .title("BRCA1", Color.ORANGE)
 *     .subtitle("BRCA1", "protein coding", Color.ORANGE)   // title + badge variant
 *     .separator()
 *     .row("Location", "chr17:43,044,295–43,125,482")
 *     .link("Ensembl", "ENSG00000012048", () -> openEnsembl())
 *     .section("Transcripts")
 *     .scrollList(txNodes, 150);
 *
 * infoPopup.show(content, ownerWindow, screenX, screenY);
 * }</pre>
 */
public class PopupContent {

    // ── Item type hierarchy ──────────────────────────────────────────────────

    /**
     * A single displayable element within a popup. All concrete variants are
     * listed as permitted subtypes so the renderer can use exhaustive pattern
     * matching.
     */
    public sealed interface Item permits
            HeaderItem, SubtitleHeaderItem,
            SeparatorItem,
            TextItem, SectionTitleItem,
            InfoRowItem, ClickableRowItem,
            BadgeRowItem,
            CustomNodeItem, ScrollListItem {}

    /** A large, coloured title label. */
    public record HeaderItem(String text, Color color) implements Item {}

    /**
     * A title label followed by a small pill-shaped subtitle badge on the same
     * line (e.g. gene name + biotype).
     */
    public record SubtitleHeaderItem(String title, String subtitle, Color titleColor)
            implements Item {}

    /** A full-width {@link javafx.scene.control.Separator}. */
    public record SeparatorItem() implements Item {}

    /** Wrapping plain-text description. */
    public record TextItem(String text) implements Item {}

    /** A small grey section heading (e.g. "Population Frequency"). */
    public record SectionTitleItem(String title) implements Item {}

    /** A key : value row, with an optional custom value colour. */
    public record InfoRowItem(String label, String value, Color valueColor) implements Item {}

    /**
     * A key : value row where the value is rendered as a clickable hyperlink.
     * Clicking the link runs {@code action} and then closes the popup.
     */
    public record ClickableRowItem(String label, String value, Runnable action)
            implements Item {}

    // ── Badge ───────────────────────────────────────────────────────────────

    /**
     * A single pill-shaped badge that can be placed inside a {@link BadgeRowItem}.
     *
     * <h3>Convenience constructors</h3>
     * <ul>
     *   <li>{@code new Badge("MANE Select", "#2e7d32")} – white text on a hex colour</li>
     *   <li>{@code new Badge("LoF", Color.RED, Color.WHITE)} – full control</li>
     * </ul>
     */
    public record Badge(String text, Color bgColor, Color textColor) {

        /** Create a badge with white text on a CSS hex / named colour. */
        public Badge(String text, String cssColor) {
            this(text, Color.web(cssColor), Color.WHITE);
        }
    }

    /** A horizontal row of one or more {@link Badge} elements. */
    public record BadgeRowItem(List<Badge> badges) implements Item {}

    /**
     * An arbitrary JavaFX {@link Node} inserted directly into the layout.
     * Use this for custom widgets (images, progress bars, charts…) that do not
     * fit the standard row / section / badge model.
     */
    public record CustomNodeItem(Node node) implements Item {}

    /**
     * A vertically scrollable list of nodes (e.g. transcript rows, tag lines).
     *
     * @param nodes     child nodes to display
     * @param maxHeight maximum pixel height of the scroll area
     */
    public record ScrollListItem(List<Node> nodes, double maxHeight) implements Item {}

    // ── Builder state ────────────────────────────────────────────────────────

    private final List<Item> items = new ArrayList<>();

    // ── Fluent builder methods ───────────────────────────────────────────────

    /**
     * Add a large coloured title.
     *
     * @param text  display text
     * @param color title text colour
     */
    public PopupContent title(String text, Color color) {
        items.add(new HeaderItem(text, color));
        return this;
    }

    /**
     * Add a title with a subtitle pill badge on the same line.
     *
     * @param title    primary text
     * @param subtitle badge text (e.g. biotype)
     * @param color    title text colour
     */
    public PopupContent title(String title, String subtitle, Color color) {
        items.add(new SubtitleHeaderItem(title, subtitle, color));
        return this;
    }

    /** Add a horizontal separator line. */
    public PopupContent separator() {
        items.add(new SeparatorItem());
        return this;
    }

    /**
     * Add a wrapping plain-text description (e.g. gene functional description).
     *
     * @param text content text
     */
    public PopupContent text(String text) {
        items.add(new TextItem(text));
        return this;
    }

    /**
     * Add a grey section heading.
     *
     * @param sectionTitle heading text
     */
    public PopupContent section(String sectionTitle) {
        items.add(new SectionTitleItem(sectionTitle));
        return this;
    }

    /**
     * Add a key : value row with the default light-grey value colour.
     *
     * @param label field name (shown in grey on the left)
     * @param value field value (shown on the right)
     */
    public PopupContent row(String label, String value) {
        items.add(new InfoRowItem(label, value, Color.LIGHTGRAY));
        return this;
    }

    /**
     * Add a key : value row with a custom value colour.
     *
     * @param label      field name
     * @param value      field value
     * @param valueColor colour applied to the value label
     */
    public PopupContent row(String label, String value, Color valueColor) {
        items.add(new InfoRowItem(label, value, valueColor));
        return this;
    }

    /**
     * Add a key : value row where the value is a clickable hyperlink.
     * Activating the link calls {@code action} and then hides the popup.
     *
     * @param label  field name
     * @param value  hyperlink text
     * @param action callback invoked when the link is activated
     */
    public PopupContent link(String label, String value, Runnable action) {
        items.add(new ClickableRowItem(label, value, action));
        return this;
    }

    /**
     * Add a horizontal row of badge pills (varargs).
     *
     * @param badgeList one or more {@link Badge} objects to show
     */
    public PopupContent badges(Badge... badgeList) {
        items.add(new BadgeRowItem(List.of(badgeList)));
        return this;
    }

    /**
     * Add a horizontal row of badge pills (list).
     *
     * @param badgeList list of {@link Badge} objects
     */
    public PopupContent badges(List<Badge> badgeList) {
        items.add(new BadgeRowItem(List.copyOf(badgeList)));
        return this;
    }

    /**
     * Insert an arbitrary JavaFX node (e.g. a chart, an image view, a grid).
     *
     * @param node the node to embed
     */
    public PopupContent node(Node node) {
        items.add(new CustomNodeItem(node));
        return this;
    }

    /**
     * Add a vertically scrollable list of nodes.
     *
     * @param nodes     the child nodes
     * @param maxHeight maximum pixel height; a scroll bar appears when exceeded
     */
    public PopupContent scrollList(List<Node> nodes, double maxHeight) {
        items.add(new ScrollListItem(List.copyOf(nodes), maxHeight));
        return this;
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    /** Returns an unmodifiable snapshot of the item list. */
    public List<Item> items() {
        return Collections.unmodifiableList(items);
    }

    /** Returns {@code true} if no items have been added. */
    public boolean isEmpty() {
        return items.isEmpty();
    }
}
