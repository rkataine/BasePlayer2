package org.baseplayer.draw;

import java.util.List;
import java.util.Map;

import org.baseplayer.io.APIs.AlphaFoldApiClient;
import org.baseplayer.io.APIs.AlphaFoldApiClient.MissensePrediction;
import org.baseplayer.ui.InfoPopup;
import org.baseplayer.ui.PopupContent;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.GeneColors;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
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

  // ── Static data tables ─────────────────────────────────────────────────────

  private static final Map<Character, String> AMINO_ACID_NAMES = Map.ofEntries(
      Map.entry('A', "Alanine"),      Map.entry('C', "Cysteine"),
      Map.entry('D', "Aspartic Acid"),Map.entry('E', "Glutamic Acid"),
      Map.entry('F', "Phenylalanine"),Map.entry('G', "Glycine"),
      Map.entry('H', "Histidine"),    Map.entry('I', "Isoleucine"),
      Map.entry('K', "Lysine"),       Map.entry('L', "Leucine"),
      Map.entry('M', "Methionine"),   Map.entry('N', "Asparagine"),
      Map.entry('P', "Proline"),      Map.entry('Q', "Glutamine"),
      Map.entry('R', "Arginine"),     Map.entry('S', "Serine"),
      Map.entry('T', "Threonine"),    Map.entry('V', "Valine"),
      Map.entry('W', "Tryptophan"),   Map.entry('Y', "Tyrosine"),
      Map.entry('*', "Stop Codon")
  );

  private static final Map<Character, String> AMINO_ACID_PROPERTIES = Map.ofEntries(
      Map.entry('A', "Hydrophobic, small"),
      Map.entry('C', "Polar, sulfur-containing"),
      Map.entry('D', "Charged negative (acidic)"),
      Map.entry('E', "Charged negative (acidic)"),
      Map.entry('F', "Hydrophobic, aromatic"),
      Map.entry('G', "Hydrophobic, smallest"),
      Map.entry('H', "Charged positive (basic), aromatic"),
      Map.entry('I', "Hydrophobic, branched-chain"),
      Map.entry('K', "Charged positive (basic)"),
      Map.entry('L', "Hydrophobic, branched-chain"),
      Map.entry('M', "Hydrophobic, sulfur-containing, start codon"),
      Map.entry('N', "Polar, amide"),
      Map.entry('P', "Hydrophobic, rigid (imino acid)"),
      Map.entry('Q', "Polar, amide"),
      Map.entry('R', "Charged positive (basic)"),
      Map.entry('S', "Polar, hydroxyl"),
      Map.entry('T', "Polar, hydroxyl"),
      Map.entry('V', "Hydrophobic, branched-chain"),
      Map.entry('W', "Hydrophobic, aromatic, largest"),
      Map.entry('Y', "Polar, aromatic"),
      Map.entry('*', "Translation termination")
  );

  private static final Map<Character, String[]> SYNONYMOUS_CODONS = Map.ofEntries(
      Map.entry('A', new String[]{"GCT", "GCC", "GCA", "GCG"}),
      Map.entry('C', new String[]{"TGT", "TGC"}),
      Map.entry('D', new String[]{"GAT", "GAC"}),
      Map.entry('E', new String[]{"GAA", "GAG"}),
      Map.entry('F', new String[]{"TTT", "TTC"}),
      Map.entry('G', new String[]{"GGT", "GGC", "GGA", "GGG"}),
      Map.entry('H', new String[]{"CAT", "CAC"}),
      Map.entry('I', new String[]{"ATT", "ATC", "ATA"}),
      Map.entry('K', new String[]{"AAA", "AAG"}),
      Map.entry('L', new String[]{"TTA", "TTG", "CTT", "CTC", "CTA", "CTG"}),
      Map.entry('M', new String[]{"ATG"}),
      Map.entry('N', new String[]{"AAT", "AAC"}),
      Map.entry('P', new String[]{"CCT", "CCC", "CCA", "CCG"}),
      Map.entry('Q', new String[]{"CAA", "CAG"}),
      Map.entry('R', new String[]{"CGT", "CGC", "CGA", "CGG", "AGA", "AGG"}),
      Map.entry('S', new String[]{"TCT", "TCC", "TCA", "TCG", "AGT", "AGC"}),
      Map.entry('T', new String[]{"ACT", "ACC", "ACA", "ACG"}),
      Map.entry('V', new String[]{"GTT", "GTC", "GTA", "GTG"}),
      Map.entry('W', new String[]{"TGG"}),
      Map.entry('Y', new String[]{"TAT", "TAC"}),
      Map.entry('*', new String[]{"TAA", "TAG", "TGA"})
  );

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
    Color aaColor = GeneColors.getAminoAcidColor(aminoAcidChar);
    String threeLetterCode = GeneColors.getAminoAcidThreeLetter(aminoAcidChar);
    String fullName = AMINO_ACID_NAMES.getOrDefault(aminoAcidChar, "Unknown");

    // Header: amino acid badge + name (custom node for badge styling)
    HBox header = new HBox(10);
    header.setAlignment(Pos.CENTER_LEFT);

    Label aaBadge = new Label(threeLetterCode);
    aaBadge.setFont(AppFonts.getBoldFont(14));
    aaBadge.setTextFill(GeneColors.getContrastingTextColor(aaColor));
    aaBadge.setStyle(String.format(
        "-fx-background-color: %s; -fx-padding: 4 8; -fx-background-radius: 4;",
        GeneColors.toHexString(aaColor)));
    header.getChildren().add(aaBadge);

    Label nameLabel = new Label(fullName + " (" + aminoAcidChar + ")");
    nameLabel.setFont(AppFonts.getBoldFont(13));
    nameLabel.setTextFill(Color.WHITE);
    header.getChildren().add(nameLabel);

    c.node(header);

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
    String props = AMINO_ACID_PROPERTIES.getOrDefault(aminoAcidChar, "Unknown");
    c.text(props);

    c.separator();
    c.section("Synonymous Codons");

    // Codon badges (custom FlowPane node)
    String[] synonymousCodons = SYNONYMOUS_CODONS.get(aminoAcidChar);
    if (synonymousCodons != null) {
      FlowPane codonBox = new FlowPane(6, 4);
      codonBox.setAlignment(Pos.CENTER_LEFT);
      codonBox.setPrefWrapLength(MAX_WIDTH - 24);

      for (String syn : synonymousCodons) {
        Label codonLabel = new Label(syn);
        codonLabel.setFont(AppFonts.getMonoFont(11));
        boolean isCurrent = syn.equalsIgnoreCase(codon);
        codonLabel.setTextFill(isCurrent ? aaColor : Color.LIGHTGRAY);
        if (isCurrent) codonLabel.setStyle("-fx-font-weight: bold;");
        codonBox.getChildren().add(codonLabel);
      }
      c.node(codonBox);

      int degeneracy = synonymousCodons.length;
      String degInfo = switch (degeneracy) {
        case 1 -> "Unique codon (no degeneracy)";
        case 2 -> "2-fold degenerate";
        case 3 -> "3-fold degenerate";
        case 4 -> "4-fold degenerate";
        case 6 -> "6-fold degenerate";
        default -> degeneracy + "-fold degenerate";
      };
      Label degLabel = new Label(degInfo);
      degLabel.setTextFill(Color.GRAY);
      degLabel.setFont(AppFonts.getUIFont(10));
      c.node(degLabel);
    }

    // AlphaMissense section (async, rendered into a placeholder VBox)
    VBox asyncSection = new VBox(4);
    asyncSection.setVisible(false);
    asyncSection.setManaged(false);
    c.node(asyncSection);

    if (geneName != null && !geneName.isEmpty()) {
      AlphaFoldApiClient.getMissensePredictions(geneName, aminoAcidNumber)
          .thenAccept(predictions -> {
            if (predictions == null || predictions.isEmpty()
                || predictions.stream().allMatch(p -> "unknown".equals(p.classification()))) {
              return;
            }
            List<MissensePrediction> valid = predictions.stream()
                .filter(p -> !"unknown".equals(p.classification())).toList();
            if (valid.isEmpty()) return;

            Platform.runLater(() -> {
              asyncSection.setVisible(true);
              asyncSection.setManaged(true);

              // Separator
              asyncSection.getChildren().add(new javafx.scene.control.Separator());

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

    return c;
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
          GeneColors.getAminoAcidThreeLetter(pred.referenceAA()),
          pred.position(),
          GeneColors.getAminoAcidThreeLetter(pred.alternateAA()),
          pred.pathogenicity());
      Tooltip.install(badge, new Tooltip(tooltip));

      flow.getChildren().add(badge);
    }
    container.getChildren().add(flow);
  }
}
