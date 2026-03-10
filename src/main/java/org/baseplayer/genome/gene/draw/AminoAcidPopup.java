package org.baseplayer.genome.gene.draw;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.components.InfoPopup;
import org.baseplayer.components.PopupContent;
import org.baseplayer.components.PopupContent.Badge;
import org.baseplayer.io.APIs.AlphaFoldApiClient;
import org.baseplayer.io.APIs.AlphaFoldApiClient.MissensePrediction;
import org.baseplayer.utils.AminoAcids;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.GeneColors;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;

/**
 * Popup displaying detailed amino acid information when user clicks on an amino acid.
 * Delegates all rendering to {@link InfoPopup} via {@link PopupContent}.
 */
public class AminoAcidPopup {

  private static final double MAX_WIDTH = 350;

  private final InfoPopup infoPopup = new InfoPopup(MAX_WIDTH, 450, true);

  // Amino acid data (set via setData, used in buildContent)
  private char aminoAcidChar;
  private String codon;
  private int aminoAcidNumber;
  private long genomicStart;
  private long cdsPosition;
  private String geneName;
  private boolean isReverse;

  // ── Public API ─────────────────────────────────────────────────────────────

  public void setData(char aminoAcid, String codon, int aminoAcidNumber,
                      long genomicStart, long cdsPosition, String geneName, boolean isReverse) {
    this.aminoAcidChar = aminoAcid;
    this.codon = codon;
    this.aminoAcidNumber = aminoAcidNumber;
    this.genomicStart = genomicStart;
    this.cdsPosition = cdsPosition;
    this.geneName = geneName;
    this.isReverse = isReverse;
  }

  public void show(Window owner, double x, double y) {
    PopupContent content = buildContent();
    infoPopup.show(content, owner, x, y);
  }

  public void hide() { infoPopup.hide(); }

  public boolean isShowing() { return infoPopup.isShowing(); }

  // ── Content builder ────────────────────────────────────────────────────────

  private PopupContent buildContent() {
    PopupContent c = new PopupContent();
    Color aaColor = AminoAcids.getColor(aminoAcidChar);
    String threeLetterCode = AminoAcids.getThreeLetter(aminoAcidChar);
    String fullName = AminoAcids.getName(aminoAcidChar);

    // Header
    c.title(threeLetterCode, fullName + " (" + aminoAcidChar + ")", aaColor);

    if (geneName != null && !geneName.isEmpty()) {
      c.row("Gene", geneName);
    }

    c.separator();
    c.section("Position");
    c.row("Protein position", String.format("p.%s%d", threeLetterCode, aminoAcidNumber));
    c.row("Codon", codon);

    // cDNA positions
    StringBuilder cNotation = new StringBuilder();
    for (int i = 0; i < 3; i++) {
      if (i > 0) cNotation.append(", ");
      long basePos = cdsPosition + i;
      char base = i < codon.length() ? codon.charAt(i) : '?';
      cNotation.append(String.format("c.%d%c", basePos, base));
    }
    c.row("cDNA", cNotation.toString());
    c.row("Genomic", String.format("%,d-%,d", genomicStart, genomicStart + 2));
    c.row("Strand", isReverse ? "Reverse (-)" : "Forward (+)");

    c.separator();
    c.section("Properties");
    c.text(AminoAcids.getProperties(aminoAcidChar));

    c.separator();
    c.section("Synonymous Codons");

    // Codon badges
    String[] synonymousCodons = AminoAcids.getSynonymousCodons(aminoAcidChar);
    if (synonymousCodons.length > 0) {
      List<Badge> codonBadges = new ArrayList<>();
      for (String syn : synonymousCodons) {
        boolean isCurrent = syn.equalsIgnoreCase(codon);
        Color bg = isCurrent ? aaColor : Color.rgb(60, 60, 60);
        Color text = isCurrent ? GeneColors.getContrastingTextColor(aaColor) : Color.LIGHTGRAY;
        codonBadges.add(new Badge(syn, bg, text));
      }
      c.badges(codonBadges);

      int degeneracy = synonymousCodons.length;
      String degInfo = switch (degeneracy) {
        case 1 -> "Unique codon (no degeneracy)";
        case 2 -> "2-fold degenerate";
        case 3 -> "3-fold degenerate";
        case 4 -> "4-fold degenerate";
        case 6 -> "6-fold degenerate";
        default -> degeneracy + "-fold degenerate";
      };
      c.text(degInfo);
    }

    // AlphaMissense section — lazy-loaded on button click
    if (geneName != null && !geneName.isEmpty()) {
      c.separator();
      VBox asyncSection = new VBox(4);
      Button loadBtn = new Button("Load AlphaMissense Predictions");
      loadBtn.setMaxWidth(Double.MAX_VALUE);
      loadBtn.setStyle("-fx-background-color: #3a5a7a; -fx-text-fill: #cce0ff; -fx-cursor: hand;");
      loadBtn.setOnAction(e -> {
        loadBtn.setDisable(true);
        fetchAlphaMissense(asyncSection);
      });
      c.node(loadBtn);
      c.node(asyncSection);
    }

    return c;
  }

  private void fetchAlphaMissense(VBox asyncSection) {
    Platform.runLater(() -> {
      asyncSection.getChildren().clear();
      Label loading = new Label("Loading\u2026");
      loading.setTextFill(Color.GRAY);
      loading.setFont(AppFonts.getUIFont(10));
      asyncSection.getChildren().add(loading);
    });

    AlphaFoldApiClient.getMissensePredictions(geneName, aminoAcidNumber)
        .thenAccept(predictions -> {
          List<MissensePrediction> valid = predictions == null ? List.of() : predictions.stream()
              .filter(p -> !"unknown".equals(p.classification())).toList();

          Platform.runLater(() -> {
            asyncSection.getChildren().clear();

            if (valid.isEmpty()) {
              Label noData = new Label("No predictions available for this position.");
              noData.setTextFill(Color.GRAY);
              noData.setFont(AppFonts.getUIFont(10));
              asyncSection.getChildren().add(noData);
              return;
            }

            Label title = new Label("AlphaMissense Predictions");
            title.setFont(AppFonts.getBoldFont(11));
            title.setTextFill(Color.web("#ff9800"));
            asyncSection.getChildren().add(title);

            Label desc = new Label("Pathogenicity predictions for substitutions at this position:");
            desc.setTextFill(Color.GRAY);
            desc.setFont(AppFonts.getUIFont(10));
            desc.setWrapText(true);
            asyncSection.getChildren().add(desc);

            VBox predList = new VBox(2);
            predList.setPadding(new Insets(4, 0, 0, 0));

            List<MissensePrediction> pathogenic = valid.stream()
                .filter(MissensePrediction::isPathogenic)
                .sorted((a, b) -> Double.compare(b.pathogenicity(), a.pathogenicity())).toList();
            List<MissensePrediction> benign = valid.stream()
                .filter(MissensePrediction::isBenign)
                .sorted((a, b) -> Double.compare(a.pathogenicity(), b.pathogenicity())).toList();
            List<MissensePrediction> ambiguous = valid.stream()
                .filter(p -> !p.isPathogenic() && !p.isBenign())
                .sorted((a, b) -> Double.compare(b.pathogenicity(), a.pathogenicity())).toList();

            if (!pathogenic.isEmpty()) addMissenseGroup(predList, pathogenic, Color.web("#f44336"));
            if (!ambiguous.isEmpty())  addMissenseGroup(predList, ambiguous,  Color.web("#ff9800"));
            if (!benign.isEmpty())     addMissenseGroup(predList, benign,     Color.web("#4caf50"));

            if (predList.getChildren().size() > 4) {
              ScrollPane sp = new ScrollPane(predList);
              sp.setFitToWidth(true);
              sp.setMaxHeight(100);
              sp.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
              asyncSection.getChildren().add(sp);
            } else {
              asyncSection.getChildren().add(predList);
            }
          });
        });
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private void addMissenseGroup(VBox container, List<MissensePrediction> predictions, Color color) {
    FlowPane flow = new FlowPane(4, 2);
    flow.setAlignment(Pos.CENTER_LEFT);
    flow.setPrefWrapLength(MAX_WIDTH - 40);

    for (MissensePrediction pred : predictions) {
      String text = String.format("%c\u2192%c", pred.referenceAA(), pred.alternateAA());
      Label badge = new Label(text);
      badge.setFont(AppFonts.getMonoFont(9));
      badge.setTextFill(Color.WHITE);
      String hex = GeneColors.toHexString(color);
      badge.setStyle(String.format(
          "-fx-background-color: %s; -fx-padding: 1 4; -fx-background-radius: 2;", hex));
      badge.setOnMouseEntered(e -> badge.setStyle(String.format(
          "-fx-background-color: %s; -fx-padding: 1 4; -fx-background-radius: 2; -fx-effect: dropshadow(gaussian, white, 4, 0.5, 0, 0);", hex)));
      badge.setOnMouseExited(e -> badge.setStyle(String.format(
          "-fx-background-color: %s; -fx-padding: 1 4; -fx-background-radius: 2;", hex)));

      String tooltip = String.format("p.%s%d%s (score: %.3f)",
          AminoAcids.getThreeLetter(pred.referenceAA()),
          pred.position(),
          AminoAcids.getThreeLetter(pred.alternateAA()),
          pred.pathogenicity());
      Tooltip.install(badge, new Tooltip(tooltip));

      flow.getChildren().add(badge);
    }
    container.getChildren().add(flow);
  }
}
