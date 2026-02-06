package org.baseplayer.draw;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.CancerTypeMapper;
import org.baseplayer.annotation.CosmicCensusEntry;
import org.baseplayer.annotation.CosmicGenes;
import org.baseplayer.annotation.Gene;
import org.baseplayer.annotation.Transcript;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.BaseUtils;
import org.baseplayer.utils.GeneColors;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;
import javafx.stage.Window;

/**
 * Popup window that displays detailed gene information.
 */
public class GeneInfoPopup {
  
  private static final double MAX_WIDTH = 450;
  private static final double MAX_HEIGHT = 400;
  
  private final Popup popup;
  private final VBox content;
  private DrawStack drawStack;
  
  public GeneInfoPopup() {
    popup = new Popup();
    popup.setAutoHide(true);
    popup.setHideOnEscape(true);
    
    content = new VBox(8);
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
  
  public void show(Gene gene, DrawStack drawStack, Window owner, double x, double y) {
    this.drawStack = drawStack;
    content.getChildren().clear();
    buildContent(gene);
    popup.show(owner, x, y);
  }
  
  public void hide() {
    popup.hide();
  }
  
  public boolean isShowing() {
    return popup.isShowing();
  }
  
  private void buildContent(Gene gene) {
    // Header with gene name and close hint
    HBox header = new HBox(10);
    header.setAlignment(Pos.CENTER_LEFT);
    
    Label nameLabel = new Label(gene.name());
    nameLabel.setFont(AppFonts.getBoldFont(16));
    Color geneColor = GeneColors.getGeneColor(gene.name(), gene.biotype());
    nameLabel.setTextFill(geneColor);
    nameLabel.setStyle("-fx-cursor: hand; -fx-underline: true;");
    nameLabel.setOnMouseClicked(e -> navigateToGene(gene));
    
    Label biotypeLabel = new Label(formatBiotype(gene.biotype()));
    biotypeLabel.setFont(AppFonts.getUIFont());
    biotypeLabel.setTextFill(Color.LIGHTGRAY);
    biotypeLabel.setStyle("-fx-background-color: rgba(80,80,80,0.5); -fx-padding: 2 6; -fx-background-radius: 3;");
    
    HBox spacer = new HBox();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    
    if (gene.hasManeSelect()) {
      Label maneLabel = new Label("MANE");
      maneLabel.setFont(AppFonts.getMonoFont(10));
      maneLabel.setTextFill(Color.WHITE);
      maneLabel.setStyle("-fx-background-color: #2e7d32; -fx-padding: 2 6; -fx-background-radius: 3;");
      header.getChildren().addAll(nameLabel, biotypeLabel, spacer, maneLabel);
    } else {
      header.getChildren().addAll(nameLabel, biotypeLabel);
    }
    
    content.getChildren().add(header);
    
    // Gene ID (clickable - opens Ensembl)
    addClickableInfoRow("Ensembl ID", gene.id().replace("gene:", ""), () -> openEnsembl(gene.id()));
    
    // GeneCards link
    addClickableInfoRow("GeneCards", gene.name(), () -> openGeneCards(gene.name()));
    
    // Location (clickable - navigates to gene)
    String location = String.format("chr%s:%s-%s (%s)", 
        gene.chrom(), 
        BaseUtils.formatNumber(gene.start()),
        BaseUtils.formatNumber(gene.end()),
        gene.strand().equals("+") ? "forward" : "reverse");
    addClickableInfoRow("Location", location, () -> navigateToGene(gene));
    
    // Size
    long size = gene.end() - gene.start();
    addInfoRow("Size", formatSize(size));
    
    // Description
    String description = gene.description();
    if (description != null && !description.isEmpty()) {
      content.getChildren().add(new Separator());
      
      Label descTitle = new Label("Description");
      descTitle.setFont(AppFonts.getUIFont());
      descTitle.setTextFill(Color.GRAY);
      content.getChildren().add(descTitle);
      
      TextFlow descFlow = new TextFlow();
      descFlow.setMaxWidth(MAX_WIDTH - 30);
      Text descText = new Text(cleanDescription(description));
      descText.setFill(Color.LIGHTGRAY);
      descText.setFont(AppFonts.getUIFont());
      descFlow.getChildren().add(descText);
      content.getChildren().add(descFlow);
    }
    
    // COSMIC Cancer Gene Census information
    CosmicCensusEntry cosmic = CosmicGenes.getEntry(gene.name());
    if (cosmic != null) {
      content.getChildren().add(new Separator());
      buildCosmicSection(cosmic);
    }
    
    // Transcripts - combine MANE (from gene) and non-MANE (loaded on demand)
    List<Transcript> allTranscripts = new ArrayList<>();
    if (gene.transcripts() != null) {
      allTranscripts.addAll(gene.transcripts());
    }
    // Add non-MANE transcripts if loaded
    List<Transcript> nonMane = AnnotationData.getNonManeTranscripts(gene.id());
    allTranscripts.addAll(nonMane);
    
    if (!allTranscripts.isEmpty()) {
      content.getChildren().add(new Separator());
      
      Label txTitle = new Label("Transcripts (" + allTranscripts.size() + ")");
      txTitle.setFont(AppFonts.getUIFont());
      txTitle.setTextFill(Color.GRAY);
      content.getChildren().add(txTitle);
      
      VBox txBox = new VBox(4);
      txBox.setPadding(new Insets(0, 0, 0, 8));
      
      // Show MANE first, then limit others
      allTranscripts.stream()
          .sorted((a, b) -> {
            if (a.isManeSelect() != b.isManeSelect()) return a.isManeSelect() ? -1 : 1;
            if (a.isManeClinic() != b.isManeClinic()) return a.isManeClinic() ? -1 : 1;
            return a.name() != null && b.name() != null ? a.name().compareTo(b.name()) : 0;
          })
          .limit(10)  // Show max 10 transcripts
          .forEach(tx -> txBox.getChildren().add(createTranscriptRow(tx)));
      
      if (allTranscripts.size() > 10) {
        Label moreLabel = new Label("... and " + (allTranscripts.size() - 10) + " more");
        moreLabel.setTextFill(Color.GRAY);
        moreLabel.setFont(AppFonts.getUIFont());
        txBox.getChildren().add(moreLabel);
      }
      
      ScrollPane scrollPane = new ScrollPane(txBox);
      scrollPane.setFitToWidth(true);
      scrollPane.setMaxHeight(150);
      scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
      
      content.getChildren().add(scrollPane);
    }
  }
  
  private HBox createTranscriptRow(Transcript tx) {
    HBox row = new HBox(8);
    row.setAlignment(Pos.CENTER_LEFT);
    
    String name = tx.name() != null ? tx.name() : tx.id().replace("transcript:", "");
    Label nameLabel = new Label(name);
    nameLabel.setFont(AppFonts.getMonoFont(11));
    nameLabel.setTextFill(Color.LIGHTGRAY);
    
    row.getChildren().add(nameLabel);
    
    if (tx.isManeSelect()) {
      Label maneLabel = new Label("MANE Select");
      maneLabel.setFont(AppFonts.getMonoFont(9));
      maneLabel.setTextFill(Color.WHITE);
      maneLabel.setStyle("-fx-background-color: #2e7d32; -fx-padding: 1 4; -fx-background-radius: 2;");
      row.getChildren().add(maneLabel);
    } else if (tx.isManeClinic()) {
      Label maneLabel = new Label("MANE Plus Clinical");
      maneLabel.setFont(AppFonts.getMonoFont(9));
      maneLabel.setTextFill(Color.WHITE);
      maneLabel.setStyle("-fx-background-color: #1565c0; -fx-padding: 1 4; -fx-background-radius: 2;");
      row.getChildren().add(maneLabel);
    }
    
    Label exonLabel = new Label(tx.exons().size() + " exons");
    exonLabel.setFont(AppFonts.getUIFont());
    exonLabel.setTextFill(Color.GRAY);
    row.getChildren().add(exonLabel);
    
    return row;
  }
  
  private void addInfoRow(String label, String value) {
    HBox row = new HBox(10);
    row.setAlignment(Pos.CENTER_LEFT);
    
    Label labelNode = new Label(label + ":");
    labelNode.setFont(AppFonts.getUIFont());
    labelNode.setTextFill(Color.GRAY);
    labelNode.setMinWidth(80);
    
    Label valueNode = new Label(value);
    valueNode.setFont(AppFonts.getMonoFont(11));
    valueNode.setTextFill(Color.LIGHTGRAY);
    
    row.getChildren().addAll(labelNode, valueNode);
    content.getChildren().add(row);
  }  
  private void addClickableInfoRow(String label, String value, Runnable onClick) {
    HBox row = new HBox(8);
    row.setAlignment(Pos.CENTER_LEFT);
    
    Label labelNode = new Label(label + ":");
    labelNode.setFont(AppFonts.getUIFont());
    labelNode.setTextFill(Color.GRAY);
    labelNode.setMinWidth(100);
    
    Label valueNode = new Label(value);
    valueNode.setFont(AppFonts.getUIFont());
    valueNode.setTextFill(Color.LIGHTBLUE);
    valueNode.setStyle("-fx-cursor: hand; -fx-underline: true;");
    valueNode.setOnMouseClicked(e -> {
      onClick.run();
      hide();
    });
    
    row.getChildren().addAll(labelNode, valueNode);
    content.getChildren().add(row);
  }
  
  private void navigateToGene(Gene gene) {
    if (drawStack != null && drawStack.drawCanvas != null) {
      // Add 10% padding around the gene
      long geneLength = gene.end() - gene.start();
      long padding = geneLength / 10;
      long start = Math.max(1, gene.start() - padding);
      long end = Math.min((long)drawStack.chromSize, gene.end() + padding);
      drawStack.drawCanvas.zoomAnimation(start, end);
    }
  }
  
  private void openEnsembl(String geneId) {
    try {
      String ensemblId = geneId.replace("gene:", "");
      String url = "https://www.ensembl.org/Homo_sapiens/Gene/Summary?g=" + ensemblId;
      
      // Use JavaFX HostServices to open browser
      javafx.application.HostServices hostServices = org.baseplayer.MainApp.getHostServicesInstance();
      if (hostServices != null) {
        hostServices.showDocument(url);
      }
    } catch (Exception e) {
      System.err.println("Failed to open Ensembl: " + e.getMessage());
    }
  }
  
  private void openGeneCards(String geneName) {
    try {
      String url = "https://www.genecards.org/cgi-bin/carddisp.pl?gene=" + geneName;
      
      javafx.application.HostServices hostServices = org.baseplayer.MainApp.getHostServicesInstance();
      if (hostServices != null) {
        hostServices.showDocument(url);
      }
    } catch (Exception e) {
      System.err.println("Failed to open GeneCards: " + e.getMessage());
    }
  }  
  private String formatBiotype(String biotype) {
    if (biotype == null) return "unknown";
    return biotype.replace("_", " ");
  }
  
  private String formatSize(long size) {
    if (size >= 1_000_000) {
      return String.format("%.2f Mb", size / 1_000_000.0);
    } else if (size >= 1_000) {
      return String.format("%.2f kb", size / 1_000.0);
    }
    return size + " bp";
  }
  
  private String cleanDescription(String desc) {
    // Remove source annotation like [Source:HGNC Symbol;Acc:HGNC:12345]
    return desc.replaceAll("\\s*\\[Source:.*?\\]", "").trim();
  }
  
  /**
   * Build the COSMIC Cancer Gene Census section.
   */
  private void buildCosmicSection(CosmicCensusEntry cosmic) {
    // Header
    HBox header = new HBox(8);
    header.setAlignment(Pos.CENTER_LEFT);
    
    Label title = new Label("COSMIC Cancer Gene Census");
    title.setFont(AppFonts.getBoldFont(12));
    title.setTextFill(Color.web("#ff6b6b"));
    
    // Tier badge
    Label tierLabel = new Label("Tier " + cosmic.tier());
    tierLabel.setFont(AppFonts.getMonoFont(10));
    tierLabel.setTextFill(Color.WHITE);
    String tierColor = cosmic.isTier1() ? "#c62828" : "#e65100"; // Red for T1, orange for T2
    tierLabel.setStyle("-fx-background-color: " + tierColor + "; -fx-padding: 2 6; -fx-background-radius: 3;");
    
    header.getChildren().addAll(title, tierLabel);
    
    // Hallmark badge if applicable
    if (cosmic.hallmark()) {
      Label hallmarkLabel = new Label("Hallmark");
      hallmarkLabel.setFont(AppFonts.getMonoFont(10));
      hallmarkLabel.setTextFill(Color.WHITE);
      hallmarkLabel.setStyle("-fx-background-color: #6a1b9a; -fx-padding: 2 6; -fx-background-radius: 3;");
      header.getChildren().add(hallmarkLabel);
    }
    
    content.getChildren().add(header);
    
    // Role in cancer
    if (cosmic.roleInCancer() != null && !cosmic.roleInCancer().isEmpty()) {
      addCosmicRow("Role", formatRoleInCancer(cosmic.roleInCancer()));
    }
    
    // Molecular genetics (Dom/Rec)
    if (cosmic.molecularGenetics() != null && !cosmic.molecularGenetics().isEmpty()) {
      addCosmicRow("Genetics", formatMolecularGenetics(cosmic.molecularGenetics()));
    }
    
    // Mutation types
    if (cosmic.mutationTypes() != null && !cosmic.mutationTypes().isEmpty()) {
      addCosmicRow("Mutations", formatMutationTypes(cosmic.mutationTypes()));
    }
    
    // Somatic/Germline with tumour types
    VBox associationBox = new VBox(4);
    associationBox.setPadding(new Insets(4, 0, 0, 0));
    
    if (cosmic.somatic() && cosmic.tumourTypesSomatic() != null && !cosmic.tumourTypesSomatic().isEmpty()) {
      Label somaticTitle = new Label("Somatic tumours:");
      somaticTitle.setFont(AppFonts.getUIFont());
      somaticTitle.setTextFill(Color.GRAY);
      associationBox.getChildren().add(somaticTitle);
      
      FlowPane tumours = createTumourBadges(cosmic.tumourTypesSomatic(), "#455a64");
      associationBox.getChildren().add(tumours);
    }
    
    if (cosmic.germline() && cosmic.tumourTypesGermline() != null && !cosmic.tumourTypesGermline().isEmpty()) {
      Label germlineTitle = new Label("Germline tumours:");
      germlineTitle.setFont(AppFonts.getUIFont());
      germlineTitle.setTextFill(Color.GRAY);
      associationBox.getChildren().add(germlineTitle);
      
      FlowPane tumours = createTumourBadges(cosmic.tumourTypesGermline(), "#37474f");
      associationBox.getChildren().add(tumours);
    }
    
    if (!associationBox.getChildren().isEmpty()) {
      content.getChildren().add(associationBox);
    }
    
    // Cancer syndrome
    if (cosmic.hasCancerSyndrome()) {
      addCosmicRow("Syndrome", cosmic.cancerSyndrome());
    }
    
    // Translocation partners
    if (cosmic.translocationPartner() != null && !cosmic.translocationPartner().isEmpty()) {
      addCosmicRow("Transloc. partners", cosmic.translocationPartner());
    }
  }
  
  private void addCosmicRow(String label, String value) {
    HBox row = new HBox(8);
    row.setAlignment(Pos.CENTER_LEFT);
    
    Label labelNode = new Label(label + ":");
    labelNode.setFont(AppFonts.getUIFont());
    labelNode.setTextFill(Color.GRAY);
    labelNode.setMinWidth(100);
    
    Label valueNode = new Label(value);
    valueNode.setFont(AppFonts.getUIFont());
    valueNode.setTextFill(Color.LIGHTGRAY);
    valueNode.setWrapText(true);
    valueNode.setMaxWidth(MAX_WIDTH - 130);
    
    row.getChildren().addAll(labelNode, valueNode);
    content.getChildren().add(row);
  }
  
  private FlowPane createTumourBadges(String tumourTypes, String bgColor) {
    FlowPane pane = new FlowPane(4, 4);
    pane.setPadding(new Insets(0, 0, 0, 8));
    pane.setMaxWidth(MAX_WIDTH - 30);
    
    // Split by comma and create badges
    String[] tumours = tumourTypes.split(",\\s*");
    for (String tumour : tumours) {
      tumour = tumour.trim();
      if (tumour.isEmpty()) continue;
      
      // Map to standardized cancer type name
      String displayName = CancerTypeMapper.mapCancerType(tumour);
      
      Label badge = new Label(displayName);
      badge.setFont(AppFonts.getUIFont(10));
      badge.setTextFill(Color.WHITE);
      badge.setStyle("-fx-background-color: " + bgColor + "; -fx-padding: 2 6; -fx-background-radius: 3;");
      pane.getChildren().add(badge);
    }
    
    return pane;
  }
  
  private String formatMutationTypes(String types) {
    // Expand abbreviations
    return types
        .replace("Mis", "Missense")
        .replace("N", "Nonsense")
        .replace("F", "Frameshift")
        .replace("S", "Splice")
        .replace("D", "Deletion")
        .replace("A", "Amplification")
        .replace("T", "Translocation")
        .replace("O", "Other");
  }
  
  /**
   * Expand Role in Cancer abbreviations.
   */
  private String formatRoleInCancer(String role) {
    return role
        .replace("TSG", "Tumor Suppressor Gene")
        .replace("oncogene", "Oncogene")
        .replace("fusion", "Fusion");
  }
  
  /**
   * Expand Molecular Genetics abbreviations.
   */
  private String formatMolecularGenetics(String genetics) {
    return genetics
        .replace("Dom", "Dominant")
        .replace("Rec", "Recessive");
  }
}
