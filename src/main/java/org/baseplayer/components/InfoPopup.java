package org.baseplayer.components;

import java.util.List;

import org.baseplayer.utils.AppFonts;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
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
    private Runnable onHidden;

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
        popup.setOnHidden(e -> {
            if (onHidden != null) onHidden.run();
        });

        content = new VBox(6);
        content.setPadding(new Insets(12));
        content.setMaxWidth(maxWidth);
        content.setStyle(POPUP_STYLE);

        // ── Close button ──────────────────────────────────────────────────────
        Button closeBtn = new Button("\u00D7");
        closeBtn.setFocusTraversable(false);
        String closeBtnNormal =
                "-fx-background-color: rgba(80,80,80,0.0);" +
                "-fx-text-fill: #888888;"                   +
                "-fx-font-size: 14;"                        +
                "-fx-padding: 0 5 1 5;"                     +
                "-fx-background-radius: 4;"                 +
                "-fx-cursor: hand;";
        String closeBtnHover =
                "-fx-background-color: rgba(180,50,50,0.75);" +
                "-fx-text-fill: white;"                       +
                "-fx-font-size: 14;"                          +
                "-fx-padding: 0 5 1 5;"                       +
                "-fx-background-radius: 4;"                   +
                "-fx-cursor: hand;";
        closeBtn.setStyle(closeBtnNormal);
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle(closeBtnHover));
        closeBtn.setOnMouseExited (e -> closeBtn.setStyle(closeBtnNormal));
        closeBtn.setOnAction(e -> hide());

        StackPane root = new StackPane();
        StackPane.setAlignment(closeBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(closeBtn, new Insets(4, 4, 0, 0));

        if (scrollable) {
            ScrollPane sp = new ScrollPane(content);
            sp.setMaxWidth(maxWidth + 20);
            sp.setMaxHeight(maxHeight);
            sp.setFitToWidth(true);
            sp.setStyle(SCROLL_STYLE);
            root.getChildren().addAll(sp, closeBtn);
        } else {
            root.getChildren().addAll(content, closeBtn);
        }
        popup.getContent().add(root);
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

        // ── Single selectable TextArea with all text content ─────────────────
        String text = popupContent.toPlainText();
        if (!text.isEmpty()) {
            TextArea ta = new TextArea(text);
            ta.setEditable(false);
            ta.setWrapText(true);
            ta.setFont(AppFonts.getMonoFont(11));
            int lineCount = (int) text.chars().filter(c -> c == '\n').count() + 1;
            ta.setPrefRowCount(Math.min(lineCount, 22));
            ta.setMaxWidth(maxWidth - 24);
            ta.setStyle(
                    "-fx-control-inner-background: #1a1a1a;" +
                    "-fx-text-fill: #d3d3d3;"                +
                    "-fx-highlight-fill: #4477aa;"           +
                    "-fx-highlight-text-fill: white;"        +
                    "-fx-focus-color: #4477aa;"              +
                    "-fx-faint-focus-color: transparent;"    +
                    "-fx-background-color: #1a1a1a;"         +
                    "-fx-background-radius: 4;"              +
                    "-fx-border-color: #444;"                +
                    "-fx-border-radius: 4;");
            content.getChildren().add(ta);
        }

        // ── Interactive items (navigation links, buttons, inputs) ────────────
        boolean hasInteractive = popupContent.items().stream().anyMatch(
                i -> i instanceof PopupContent.ClickableRowItem  ||
                     i instanceof PopupContent.CustomNodeItem    ||
                     i instanceof PopupContent.ActionButtonsItem ||
                     i instanceof PopupContent.CheckboxItem      ||
                     i instanceof PopupContent.InputRowItem);
        if (hasInteractive) {
            content.getChildren().add(new Separator());
            for (PopupContent.Item item : popupContent.items()) {
                switch (item) {
                    case PopupContent.ClickableRowItem c ->
                        renderClickableRow(c.label(), c.value(), c.action());
                    case PopupContent.CustomNodeItem n ->
                        content.getChildren().add(n.node());
                    case PopupContent.ActionButtonsItem ab ->
                        renderActionButtons(ab.buttons());
                    case PopupContent.CheckboxItem cb ->
                        renderCheckbox(cb.label(), cb.selected());
                    case PopupContent.InputRowItem ir ->
                        renderInputRow(ir.label(), ir.value(), ir.disabled());
                    default -> { /* text items already in TextArea */ }
                }
            }
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

    /** Set callback invoked whenever the popup is hidden (auto-hide or explicit hide). */
    public void setOnHidden(Runnable onHidden) {
        this.onHidden = onHidden;
    }

    /** Screen X of the popup (valid only while showing). */
    public double getPopupX() { return popup.getX(); }

    /** Screen Y of the popup (valid only while showing). */
    public double getPopupY() { return popup.getY(); }

    /** Pixel width of the rendered content pane. */
    public double getContentWidth() { return content.getWidth(); }


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
