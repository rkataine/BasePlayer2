package org.baseplayer.components;

import java.util.List;

import org.baseplayer.utils.AppFonts;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;
import javafx.stage.Window;

/**
 * Generic, data-driven popup window.
 *
 * <p>Feed it a {@link PopupContent} that describes what to display and call
 * {@link #show} — the popup builds its UI on the fly from the model.  A single
 * {@code InfoPopup} instance is reusable; every call to {@code show} replaces the
 * previous content.
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * // Create once (e.g. as a field)
 * private final InfoPopup popup = new InfoPopup();
 *
 * // Show with data
 * PopupContent content = new PopupContent()
 *     .title("BRCA1", Color.ORANGE)
 *     .separator()
 *     .row("Location", "chr17:43,044,295")
 *     .link("Ensembl", "ENSG00000012048", () -> openEnsembl())
 *     .section("Transcripts")
 *     .scrollList(transcriptNodes, 150);
 *
 * popup.show(content, ownerWindow, screenX, screenY);
 * }</pre>
 *
 * <h3>Customising dimensions</h3>
 * Use the three-argument constructor to control width, scroll height, and whether
 * a {@link ScrollPane} wrapper is added:
 * <pre>{@code
 * InfoPopup narrow = new InfoPopup(320, 300, false);
 * }</pre>
 */
public class InfoPopup {

    // ── Constants ────────────────────────────────────────────────────────────

    /** Default maximum content width in pixels. */
    public static final double DEFAULT_MAX_WIDTH  = 450;

    /** Default maximum scroll-pane height in pixels. */
    public static final double DEFAULT_MAX_HEIGHT = 450;

    /** Minimum label-column width for key–value rows. */
    private static final double LABEL_MIN_WIDTH = 90;

    private static final String POPUP_STYLE =
            "-fx-background-color: rgba(30, 30, 30, 0.98);" +
            "-fx-background-radius: 8;"                      +
            "-fx-border-color: #555555;"                     +
            "-fx-border-radius: 8;"                          +
            "-fx-border-width: 1;";

    private static final String SCROLL_STYLE =
            "-fx-background-color: transparent;" +
            "-fx-background: transparent;"       +
            "-fx-border-color: transparent;";

    // ── State ────────────────────────────────────────────────────────────────

    private final Popup  popup;
    private final VBox   content;
    private final double maxWidth;

    // ── Constructors ─────────────────────────────────────────────────────────

    /**
     * Create an {@code InfoPopup} with default dimensions ({@value #DEFAULT_MAX_WIDTH} ×
     * {@value #DEFAULT_MAX_HEIGHT} px) and a scroll wrapper.
     */
    public InfoPopup() {
        this(DEFAULT_MAX_WIDTH, DEFAULT_MAX_HEIGHT, true);
    }

    /**
     * Create an {@code InfoPopup} with custom dimensions.
     *
     * @param maxWidth   maximum width of the content pane in pixels
     * @param maxHeight  maximum height of the optional scroll wrapper in pixels
     * @param scrollable {@code true} to wrap the content in a {@link ScrollPane}
     */
    public InfoPopup(double maxWidth, double maxHeight, boolean scrollable) {
        this.maxWidth = maxWidth;

        popup = new Popup();
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        content = new VBox(6);
        content.setPadding(new Insets(12));
        content.setMaxWidth(maxWidth);
        content.setStyle(POPUP_STYLE);

        if (scrollable) {
            ScrollPane sp = new ScrollPane(content);
            sp.setMaxWidth(maxWidth + 20);
            sp.setMaxHeight(maxHeight);
            sp.setFitToWidth(true);
            sp.setStyle(SCROLL_STYLE);
            popup.getContent().add(sp);
        } else {
            popup.getContent().add(content);
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Build and display the popup from the given content model.
     *
     * <p>The popup's previous content is discarded and the new content is
     * rendered before the popup is shown at ({@code x}, {@code y}).
     *
     * @param popupContent the description of what to display (must not be {@code null})
     * @param owner        the owner window used for screen-relative positioning
     * @param x            screen X coordinate
     * @param y            screen Y coordinate
     */
    public void show(PopupContent popupContent, Window owner, double x, double y) {
        content.getChildren().clear();
        for (PopupContent.Item item : popupContent.items()) {
            render(item);
        }
        popup.show(owner, x, y);
    }

    /** Hide the popup. */
    public void hide() {
        popup.hide();
    }

    /** Returns {@code true} if the popup is currently visible. */
    public boolean isShowing() {
        return popup.isShowing();
    }

    // ── Item dispatching ─────────────────────────────────────────────────────

    /**
     * Dispatch a {@link PopupContent.Item} to its concrete renderer.
     * The {@code switch} is exhaustive over all permitted subtypes.
     */
    private void render(PopupContent.Item item) {
        switch (item) {

            case PopupContent.HeaderItem h ->
                renderHeader(h.text(), h.color());

            case PopupContent.SubtitleHeaderItem h ->
                renderSubtitleHeader(h.title(), h.subtitle(), h.titleColor());

            case PopupContent.SeparatorItem _ ->
                content.getChildren().add(new Separator());

            case PopupContent.TextItem t ->
                renderText(t.text());

            case PopupContent.SectionTitleItem s ->
                renderSectionTitle(s.title());

            case PopupContent.InfoRowItem r ->
                renderInfoRow(r.label(), r.value(), r.valueColor());

            case PopupContent.ClickableRowItem c ->
                renderClickableRow(c.label(), c.value(), c.action());

            case PopupContent.BadgeRowItem b ->
                renderBadgeRow(b.badges());

            case PopupContent.CheckboxItem cb ->
                renderCheckbox(cb.label(), cb.selected());

            case PopupContent.InputRowItem ir ->
                renderInputRow(ir.label(), ir.value(), ir.disabled());

            case PopupContent.ActionButtonsItem ab ->
                renderActionButtons(ab.buttons());

            case PopupContent.CustomNodeItem n ->
                content.getChildren().add(n.node());

            case PopupContent.ScrollListItem s ->
                renderScrollList(s.nodes(), s.maxHeight());
        }
    }

    // ── Concrete renderers ───────────────────────────────────────────────────

    private void renderHeader(String title, Color color) {
        Label label = new Label(title);
        label.setFont(AppFonts.getBoldFont(14));
        label.setTextFill(color);
        content.getChildren().add(label);
    }

    private void renderSubtitleHeader(String title, String subtitle, Color titleColor) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label(title);
        titleLabel.setFont(AppFonts.getBoldFont(14));
        titleLabel.setTextFill(titleColor);

        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.setFont(AppFonts.getUIFont());
        subtitleLabel.setTextFill(Color.LIGHTGRAY);
        subtitleLabel.setStyle(
                "-fx-background-color: rgba(80,80,80,0.5);" +
                "-fx-padding: 2 6;"                         +
                "-fx-background-radius: 3;");

        row.getChildren().addAll(titleLabel, subtitleLabel);
        content.getChildren().add(row);
    }

    private void renderText(String text) {
        TextFlow flow = new TextFlow();
        flow.setMaxWidth(maxWidth - 30);

        Text t = new Text(text);
        t.setFill(Color.LIGHTGRAY);
        t.setFont(AppFonts.getUIFont());
        flow.getChildren().add(t);

        content.getChildren().add(flow);
    }

    private void renderSectionTitle(String title) {
        Label label = new Label(title);
        label.setFont(AppFonts.getUIFont());
        label.setTextFill(Color.GRAY);
        label.setStyle("-fx-padding: 4 0 0 0;");
        content.getChildren().add(label);
    }

    private void renderInfoRow(String label, String value, Color valueColor) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        Label labelNode = new Label(label + ":");
        labelNode.setFont(AppFonts.getUIFont());
        labelNode.setTextFill(Color.GRAY);
        labelNode.setMinWidth(LABEL_MIN_WIDTH);

        Label valueNode = new Label(value);
        valueNode.setFont(AppFonts.getUIFont());
        valueNode.setTextFill(valueColor);
        valueNode.setWrapText(true);
        HBox.setHgrow(valueNode, Priority.ALWAYS);

        row.getChildren().addAll(labelNode, valueNode);
        content.getChildren().add(row);
    }

    private void renderClickableRow(String label, String value, Runnable action) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        Label labelNode = new Label(label + ":");
        labelNode.setFont(AppFonts.getUIFont());
        labelNode.setTextFill(Color.GRAY);
        labelNode.setMinWidth(LABEL_MIN_WIDTH);

        Hyperlink link = new Hyperlink(value);
        link.setFont(AppFonts.getUIFont());
        link.setTextFill(Color.rgb(100, 180, 255));
        link.setBorder(null);
        link.setPadding(Insets.EMPTY);
        link.setOnAction(e -> {
            action.run();
            hide();
        });

        row.getChildren().addAll(labelNode, link);
        content.getChildren().add(row);
    }

    private void renderBadgeRow(List<PopupContent.Badge> badges) {
        if (badges.isEmpty()) return;

        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);

        for (PopupContent.Badge badge : badges) {
            Label label = new Label(badge.text());
            label.setFont(AppFonts.getMonoFont(10));
            label.setTextFill(badge.textColor());
            label.setStyle(String.format(
                    "-fx-background-color: %s; -fx-padding: 2 6; -fx-background-radius: 3;",
                    toRgb(badge.bgColor())));
            row.getChildren().add(label);
        }

        content.getChildren().add(row);
    }

    private void renderScrollList(List<Node> nodes, double maxHeight) {
        VBox listBox = new VBox(4);
        listBox.getChildren().addAll(nodes);

        ScrollPane sp = new ScrollPane(listBox);
        sp.setFitToWidth(true);
        sp.setMaxHeight(maxHeight);
        sp.setStyle(SCROLL_STYLE);

        content.getChildren().add(sp);
    }

    // ── Popup control ────────────────────────────────────────────────────────

    /**
     * Render a labelled checkbox with a custom green SVG checkmark.
     * The {@link BooleanProperty} reflects and drives the checked state.
     */
    /**
     * Render a labelled text-input row with dark theme styling.
     * The field is bound bidirectionally to {@code value}; if {@code disabled}
     * is non-{@code null} the field's disable state tracks that property.
     */
    private void renderInputRow(String label, StringProperty value, BooleanProperty disabled) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        Label lbl = new Label(label + ":");
        lbl.setFont(AppFonts.getUIFont());
        lbl.setTextFill(Color.GRAY);
        lbl.setMinWidth(LABEL_MIN_WIDTH);

        TextField field = new TextField();
        field.setPrefWidth(80);
        field.setStyle("-fx-background-color: #333; -fx-text-fill: #ddd; -fx-border-color: #555;");
        field.textProperty().bindBidirectional(value);
        if (disabled != null) field.disableProperty().bind(disabled);

        row.getChildren().addAll(lbl, field);
        content.getChildren().add(row);
    }

    private void renderCheckbox(String label, BooleanProperty selected) {
        StackPane box = new StackPane();
        box.setPrefSize(16, 16);
        box.setStyle("-fx-border-color: #66cc66; -fx-border-width: 1.5; -fx-background-color: #333;");

        SVGPath check = new SVGPath();
        check.setContent("M 2 8 L 6 12 L 14 2");
        check.setStroke(Color.web("#66cc66"));
        check.setStrokeWidth(2.5);
        check.setFill(null);
        check.visibleProperty().bind(selected);
        box.getChildren().add(check);

        Label lbl = new Label(label);
        lbl.setFont(AppFonts.getUIFont());
        lbl.setTextFill(Color.LIGHTGRAY);

        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        box.setOnMouseClicked(e -> selected.set(!selected.get()));
        lbl.setOnMouseClicked(e -> selected.set(!selected.get()));
        row.getChildren().addAll(box, lbl);
        content.getChildren().add(row);
    }

    /**
     * Render a right-aligned row of action buttons.
     * Every button hides the popup after its action runs.
     */
    private void renderActionButtons(List<PopupContent.ActionButton> buttons) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_RIGHT);
        for (PopupContent.ActionButton ab : buttons) {
            Button btn = new Button(ab.label());
            if (ab.primary()) {
                btn.setStyle("-fx-background-color: #3a6ea5; -fx-text-fill: white;");
            } else {
                btn.setStyle("-fx-background-color: #555; -fx-text-fill: #ccc;");
            }
            btn.setOnAction(e -> { ab.action().run(); hide(); });
            row.getChildren().add(btn);
        }
        content.getChildren().add(row);
    }

    // ── Popup control ────────────────────────────────────────────────────────

    /**
     * Enable or disable auto-hide (click-outside-to-close) behaviour.
     * Useful when a secondary window (e.g. image viewer) must keep the popup open.
     */
    public void setAutoHide(boolean autoHide) {
        popup.setAutoHide(autoHide);
    }

    /** Returns the owner {@link Window} passed to the last {@link #show} call. */
    public Window getOwnerWindow() {
        return popup.getOwnerWindow();
    }

    /** Screen X of the popup (valid only while showing). */
    public double getPopupX() { return popup.getX(); }

    /** Screen Y of the popup (valid only while showing). */
    public double getPopupY() { return popup.getY(); }

    /** Pixel width of the rendered content pane. */
    public double getContentWidth() { return content.getWidth(); }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Convert a JavaFX {@link Color} to a CSS {@code rgb(r,g,b)} string. */
    private static String toRgb(Color c) {
        return String.format("rgb(%d,%d,%d)",
                (int) (c.getRed()   * 255),
                (int) (c.getGreen() * 255),
                (int) (c.getBlue()  * 255));
    }

    /**
     * Open a URL in the user's default browser.
     * Convenience method for popup action callbacks.
     */
    public static void openUrl(String url) {
        try {
            if (url != null && !url.isEmpty()) {
                javafx.application.HostServices hs = org.baseplayer.MainApp.getHostServicesInstance();
                if (hs != null) hs.showDocument(url);
            }
        } catch (Exception e) {
            System.err.println("Failed to open URL: " + url + " – " + e.getMessage());
        }
    }
}
