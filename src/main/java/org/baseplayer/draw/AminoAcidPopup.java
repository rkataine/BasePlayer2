package org.baseplayer.draw;

import java.util.List;
import java.util.Map;

import org.baseplayer.io.AlphaFoldApiClient;
import org.baseplayer.io.AlphaFoldApiClient.MissensePrediction;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.GeneColors;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.stage.Window;

/**
 * Popup displaying detailed amino acid information when user clicks on an amino acid.
 */
public class AminoAcidPopup {
  
  private static final double MAX_WIDTH = 350;
  
  private final Popup popup;
  private final VBox content;
  
  // Amino acid data
  private char aminoAcidChar;
  private String codon;
  private int aminoAcidNumber;  // 1-based position in protein
  private long genomicStart;
  private long cdsPosition;  // Position in CDS (1-based)
  private String geneName;
  private boolean isReverse;
  
  // Amino acid full names
  private static final Map<Character, String> AMINO_ACID_NAMES = Map.ofEntries(
      Map.entry('A', "Alanine"),
      Map.entry('C', "Cysteine"),
      Map.entry('D', "Aspartic Acid"),
      Map.entry('E', "Glutamic Acid"),
      Map.entry('F', "Phenylalanine"),
      Map.entry('G', "Glycine"),
      Map.entry('H', "Histidine"),
      Map.entry('I', "Isoleucine"),
      Map.entry('K', "Lysine"),
      Map.entry('L', "Leucine"),
      Map.entry('M', "Methionine"),
      Map.entry('N', "Asparagine"),
      Map.entry('P', "Proline"),
      Map.entry('Q', "Glutamine"),
      Map.entry('R', "Arginine"),
      Map.entry('S', "Serine"),
      Map.entry('T', "Threonine"),
      Map.entry('V', "Valine"),
      Map.entry('W', "Tryptophan"),
      Map.entry('Y', "Tyrosine"),
      Map.entry('*', "Stop Codon")
  );
  
  // Amino acid properties
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
  
  // Synonymous codons for each amino acid
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
  
  public AminoAcidPopup() {
    popup = new Popup();
    popup.setAutoHide(true);
    popup.setHideOnEscape(true);
    
    content = new VBox(6);
    content.setPadding(new Insets(12));
    content.setMaxWidth(MAX_WIDTH);
    content.setStyle(
      "-fx-background-color: rgba(30, 30, 30, 0.98);" +
      "-fx-background-radius: 8;" +
      "-fx-border-color: #555555;" +
      "-fx-border-radius: 8;" +
      "-fx-border-width: 1;"
    );
    
    popup.getContent().add(content);
  }
  
  /**
   * Set the amino acid data to display.
   */
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
  
  /**
   * Show the popup at the specified position.
   */
  public void show(Window owner, double x, double y) {
    content.getChildren().clear();
    buildContent();
    popup.show(owner, x, y);
  }
  
  /**
   * Hide the popup.
   */
  public void hide() {
    popup.hide();
  }
  
  /**
   * Check if popup is currently showing.
   */
  public boolean isShowing() {
    return popup.isShowing();
  }
  
  private void buildContent() {
    Color aaColor = GeneColors.getAminoAcidColor(aminoAcidChar);
    String threeLetterCode = GeneColors.getAminoAcidThreeLetter(aminoAcidChar);
    String fullName = AMINO_ACID_NAMES.getOrDefault(aminoAcidChar, "Unknown");
    
    // Header: Amino acid with colored badge
    HBox header = new HBox(10);
    header.setAlignment(Pos.CENTER_LEFT);
    
    // Amino acid badge
    Label aaBadge = new Label(threeLetterCode);
    aaBadge.setFont(AppFonts.getBoldFont(14));
    aaBadge.setTextFill(GeneColors.getContrastingTextColor(aaColor));
    aaBadge.setStyle(String.format(
      "-fx-background-color: %s; -fx-padding: 4 8; -fx-background-radius: 4;",
      GeneColors.toHexString(aaColor)
    ));
    header.getChildren().add(aaBadge);
    
    // Full name
    Label nameLabel = new Label(fullName + " (" + aminoAcidChar + ")");
    nameLabel.setFont(AppFonts.getBoldFont(13));
    nameLabel.setTextFill(Color.WHITE);
    header.getChildren().add(nameLabel);
    
    content.getChildren().add(header);
    
    // Gene name if available
    if (geneName != null && !geneName.isEmpty()) {
      addInfoRow("Gene", geneName);
    }
    
    addSeparator();
    addSectionTitle("Position");
    
    // Amino acid number (p. notation)
    addInfoRow("Protein position", String.format("p.%s%d", threeLetterCode, aminoAcidNumber));
    
    // Codon
    addInfoRow("Codon", codon);
    
    // cDNA positions for each base
    StringBuilder cNotation = new StringBuilder();
    for (int i = 0; i < 3; i++) {
      if (i > 0) cNotation.append(", ");
      long basePos = cdsPosition + i;
      char base = i < codon.length() ? codon.charAt(i) : '?';
      cNotation.append(String.format("c.%d%c", basePos, base));
    }
    addInfoRow("cDNA", cNotation.toString());
    
    // Genomic position
    addInfoRow("Genomic", String.format("%,d-%,d", genomicStart, genomicStart + 2));
    
    // Strand
    addInfoRow("Strand", isReverse ? "Reverse (-)" : "Forward (+)");
    
    addSeparator();
    addSectionTitle("Properties");
    
    // Properties
    String props = AMINO_ACID_PROPERTIES.getOrDefault(aminoAcidChar, "Unknown");
    Label propsLabel = new Label(props);
    propsLabel.setTextFill(Color.LIGHTGRAY);
    propsLabel.setFont(AppFonts.getUIFont(11));
    propsLabel.setWrapText(true);
    content.getChildren().add(propsLabel);
    
    addSeparator();
    addSectionTitle("Synonymous Codons");
    
    // All codons that code for this amino acid
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
        if (isCurrent) {
          codonLabel.setStyle("-fx-font-weight: bold;");
        }
        codonBox.getChildren().add(codonLabel);
      }
      content.getChildren().add(codonBox);
      
      // Degeneracy info
      int degeneracy = synonymousCodons.length;
      String degInfo = switch(degeneracy) {
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
      content.getChildren().add(degLabel);
    }
    
    // AlphaMissense predictions section (async)
    buildAlphaMissenseSection();
  }
  
  /**
   * Build the AlphaMissense pathogenicity prediction section.
   */
  private void buildAlphaMissenseSection() {
    // Create placeholder section - will be populated async
    VBox missenseBox = new VBox(4);
    missenseBox.setVisible(false);
    missenseBox.setManaged(false);
    
    Separator separator = new Separator();
    separator.setStyle("-fx-background-color: #444444;");
    separator.setVisible(false);
    separator.setManaged(false);
    
    content.getChildren().add(separator);
    content.getChildren().add(missenseBox);
    
    // Async fetch missense predictions
    if (geneName != null && !geneName.isEmpty()) {
      AlphaFoldApiClient.getMissensePredictions(geneName, aminoAcidNumber, aminoAcidChar)
          .thenAccept(predictions -> {
            if (predictions != null && !predictions.isEmpty() 
                && !predictions.stream().allMatch(p -> "unknown".equals(p.classification()))) {
              Platform.runLater(() -> {
                // Filter to valid predictions only
                List<MissensePrediction> validPredictions = predictions.stream()
                    .filter(p -> !"unknown".equals(p.classification()))
                    .toList();
                
                if (validPredictions.isEmpty()) return;
                
                // Show the section
                separator.setVisible(true);
                separator.setManaged(true);
                missenseBox.setVisible(true);
                missenseBox.setManaged(true);
                
                // Header
                Label title = new Label("AlphaMissense Predictions");
                title.setFont(AppFonts.getBoldFont(11));
                title.setTextFill(Color.web("#ff9800"));
                missenseBox.getChildren().add(title);
                
                // Description
                Label desc = new Label("Pathogenicity predictions for substitutions at this position:");
                desc.setTextFill(Color.GRAY);
                desc.setFont(AppFonts.getUIFont(10));
                desc.setWrapText(true);
                missenseBox.getChildren().add(desc);
                
                // Create scrollable prediction list
                VBox predList = new VBox(2);
                predList.setPadding(new Insets(4, 0, 0, 0));
                
                // Group by classification and sort
                List<MissensePrediction> pathogenic = validPredictions.stream()
                    .filter(MissensePrediction::isPathogenic)
                    .sorted((a, b) -> Double.compare(b.pathogenicity(), a.pathogenicity()))
                    .toList();
                
                List<MissensePrediction> benign = validPredictions.stream()
                    .filter(MissensePrediction::isBenign)
                    .sorted((a, b) -> Double.compare(a.pathogenicity(), b.pathogenicity()))
                    .toList();
                
                List<MissensePrediction> ambiguous = validPredictions.stream()
                    .filter(p -> !p.isPathogenic() && !p.isBenign())
                    .sorted((a, b) -> Double.compare(b.pathogenicity(), a.pathogenicity()))
                    .toList();
                
                // Add pathogenic predictions
                if (!pathogenic.isEmpty()) {
                  addMissenseGroup(predList, pathogenic, Color.web("#f44336"));
                }
                
                // Add ambiguous predictions
                if (!ambiguous.isEmpty()) {
                  addMissenseGroup(predList, ambiguous, Color.web("#ff9800"));
                }
                
                // Add benign predictions
                if (!benign.isEmpty()) {
                  addMissenseGroup(predList, benign, Color.web("#4caf50"));
                }
                
                if (predList.getChildren().size() > 4) {
                  ScrollPane scrollPane = new ScrollPane(predList);
                  scrollPane.setFitToWidth(true);
                  scrollPane.setMaxHeight(100);
                  scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
                  missenseBox.getChildren().add(scrollPane);
                } else {
                  missenseBox.getChildren().add(predList);
                }
              });
            }
          });
    }
  }
  
  private void addMissenseGroup(VBox container, List<MissensePrediction> predictions, Color color) {
    FlowPane flow = new FlowPane(4, 2);
    flow.setAlignment(Pos.CENTER_LEFT);
    flow.setPrefWrapLength(MAX_WIDTH - 40);
    
    for (MissensePrediction pred : predictions) {
      String text = String.format("%c→%c", pred.referenceAA(), pred.alternateAA());
      Label badge = new Label(text);
      badge.setFont(AppFonts.getMonoFont(9));
      badge.setTextFill(Color.WHITE);
      badge.setStyle(String.format(
        "-fx-background-color: %s; -fx-padding: 1 4; -fx-background-radius: 2;",
        GeneColors.toHexString(color)
      ));
      
      // Tooltip on hover
      String tooltip = String.format("p.%s%d%s (score: %.3f)",
          GeneColors.getAminoAcidThreeLetter(pred.referenceAA()),
          pred.position(),
          GeneColors.getAminoAcidThreeLetter(pred.alternateAA()),
          pred.pathogenicity());
      badge.setOnMouseEntered(e -> badge.setStyle(String.format(
        "-fx-background-color: %s; -fx-padding: 1 4; -fx-background-radius: 2; -fx-effect: dropshadow(gaussian, white, 4, 0.5, 0, 0);",
        GeneColors.toHexString(color)
      )));
      badge.setOnMouseExited(e -> badge.setStyle(String.format(
        "-fx-background-color: %s; -fx-padding: 1 4; -fx-background-radius: 2;",
        GeneColors.toHexString(color)
      )));
      
      javafx.scene.control.Tooltip tt = new javafx.scene.control.Tooltip(tooltip);
      javafx.scene.control.Tooltip.install(badge, tt);
      
      flow.getChildren().add(badge);
    }
    
    container.getChildren().add(flow);
  }
  
  private void addInfoRow(String label, String value) {
    HBox row = new HBox(10);
    row.setAlignment(Pos.CENTER_LEFT);
    
    Label labelNode = new Label(label + ":");
    labelNode.setTextFill(Color.GRAY);
    labelNode.setFont(AppFonts.getUIFont(11));
    labelNode.setMinWidth(90);
    
    Label valueNode = new Label(value);
    valueNode.setTextFill(Color.WHITE);
    valueNode.setFont(AppFonts.getUIFont(11));
    
    row.getChildren().addAll(labelNode, valueNode);
    content.getChildren().add(row);
  }
  
  private void addSectionTitle(String title) {
    Label titleLabel = new Label(title);
    titleLabel.setFont(AppFonts.getBoldFont(11));
    titleLabel.setTextFill(Color.web("#aaaaaa"));
    content.getChildren().add(titleLabel);
  }
  
  private void addSeparator() {
    Separator sep = new Separator();
    sep.setStyle("-fx-background-color: #444444;");
    content.getChildren().add(sep);
  }
}
