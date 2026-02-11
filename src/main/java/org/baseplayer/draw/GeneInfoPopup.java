package org.baseplayer.draw;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.CancerTypeMapper;
import org.baseplayer.annotation.CosmicCensusEntry;
import org.baseplayer.annotation.CosmicGenes;
import org.baseplayer.annotation.Gene;
import org.baseplayer.annotation.Transcript;
import org.baseplayer.io.AlphaFoldApiClient;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.BaseUtils;
import org.baseplayer.utils.GeneColors;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;
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
    
    // AlphaFold Structure section (async loading)
    buildAlphaFoldSection(gene.name());

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
   * Build the AlphaFold Structure section with async loading.
   */
  private void buildAlphaFoldSection(String geneName) {
    // Create placeholder section
    VBox alphaFoldBox = new VBox(4);
    alphaFoldBox.setVisible(false);
    alphaFoldBox.setManaged(false);
    
    Separator separator = new Separator();
    separator.setVisible(false);
    separator.setManaged(false);
    
    // Insert separator and box - will show when data loads
    content.getChildren().add(separator);
    content.getChildren().add(alphaFoldBox);
    
    System.out.println("AlphaFold: Fetching data for gene: " + geneName);
    
    // Async fetch AlphaFold data
    AlphaFoldApiClient.getAlphaFoldForGene(geneName).thenAccept(entry -> {
      System.out.println("AlphaFold: Got response for " + geneName + ": " + (entry != null ? entry.uniprotId() : "null"));
      if (entry == null) return;

      Runnable uiUpdate = () -> {
        // Show the section
        separator.setVisible(true);
        separator.setManaged(true);
        alphaFoldBox.setVisible(true);
        alphaFoldBox.setManaged(true);

        // Header
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("AlphaFold Structure");
        title.setFont(AppFonts.getBoldFont(12));
        title.setTextFill(Color.web("#4fc3f7"));

        // pLDDT confidence badge
        double plddt = entry.globalMetricValue();
        String plddtText = String.format("pLDDT: %.1f", plddt);
        Label plddtLabel = new Label(plddtText);
        plddtLabel.setFont(AppFonts.getMonoFont(10));
        plddtLabel.setTextFill(Color.WHITE);
        String plddtColor = plddt >= 90 ? "#1565c0" : plddt >= 70 ? "#4caf50" : plddt >= 50 ? "#ff9800" : "#f44336";
        plddtLabel.setStyle("-fx-background-color: " + plddtColor + "; -fx-padding: 2 6; -fx-background-radius: 3;");

        header.getChildren().addAll(title, plddtLabel);
        alphaFoldBox.getChildren().add(header);
          
          // UniProt ID
          addAlphaFoldRow(alphaFoldBox, "UniProt ID", entry.uniprotId(), () -> openUniProt(entry.uniprotId()));
          
        // UniProt / links
        HBox links = new HBox(8);
        links.setAlignment(Pos.CENTER_LEFT);
        Hyperlink view = new Hyperlink("View in AlphaFold DB");
        view.setOnAction(e -> openUrl(entry.getAlphaFoldUrl()));
        view.setFocusTraversable(false);
        view.setStyle(view.getStyle() + "; -fx-focus-color: transparent; -fx-faint-focus-color: transparent;");
        links.getChildren().add(view);
        alphaFoldBox.getChildren().add(links);

        // Description
        if (entry.uniprotDescription() != null && !entry.uniprotDescription().isEmpty()) {
          Label desc = new Label(cleanDescription(entry.uniprotDescription()));
          desc.setWrapText(true);
          desc.setFont(AppFonts.getUIFont(11));
          alphaFoldBox.getChildren().add(desc);
        }

        // Model images (only create Image if URL is present)
        HBox images = new HBox(8);
        images.setAlignment(Pos.CENTER_LEFT);
        boolean hasAnyImage = false;
        String paeUrl = entry.paeImageUrl();
        if (paeUrl != null && !paeUrl.isBlank()) {
          try {
            ImageView pae = new ImageView(new Image(paeUrl, 180, 80, true, true, true));
            pae.setSmooth(true);
            pae.setPreserveRatio(true);
            pae.setCursor(javafx.scene.Cursor.HAND);
            pae.setOnMouseClicked(ev -> openImageWindow(paeUrl, "PAE: " + entry.uniprotId()));
            images.getChildren().add(pae);
            hasAnyImage = true;
          } catch (Exception e) {
            System.err.println("AlphaFold: failed to load PAE image: " + e.getMessage());
          }
        }

        String modelImageUrl = entry.modelUrl();
        if (modelImageUrl != null && !modelImageUrl.isBlank()) {
          try {
            ImageView modelImg = new ImageView(new Image(modelImageUrl, 180, 80, true, true, true));
            modelImg.setSmooth(true);
            modelImg.setPreserveRatio(true);
            modelImg.setCursor(javafx.scene.Cursor.HAND);
            modelImg.setOnMouseClicked(ev -> openImageWindow(modelImageUrl, "Model: " + entry.uniprotId()));
            images.getChildren().add(modelImg);
            hasAnyImage = true;
          } catch (Exception e) {
            System.err.println("AlphaFold: failed to load model image: " + e.getMessage());
          }
        }

        if (hasAnyImage) {
          alphaFoldBox.getChildren().add(images);
        }
      };

      if (Platform.isFxApplicationThread()) {
        uiUpdate.run();
      } else {
        Platform.runLater(uiUpdate);
      }
    });
  }
  
  private void addAlphaFoldRow(VBox container, String label, String value, Runnable onClick) {
    HBox row = new HBox(8);
    row.setAlignment(Pos.CENTER_LEFT);
    
    Label labelNode = new Label(label + ":");
    labelNode.setFont(AppFonts.getUIFont());
    labelNode.setTextFill(Color.GRAY);
    labelNode.setMinWidth(80);
    
    Label valueNode = new Label(value);
    valueNode.setFont(AppFonts.getUIFont());
    valueNode.setTextFill(Color.LIGHTBLUE);
    valueNode.setStyle("-fx-cursor: hand; -fx-underline: true;");
    valueNode.setOnMouseClicked(e -> {
      onClick.run();
      hide();
    });
    
    row.getChildren().addAll(labelNode, valueNode);
    container.getChildren().add(row);
  }
  
  private void addAlphaFoldInfoRow(VBox container, String label, String value) {
    HBox row = new HBox(8);
    row.setAlignment(Pos.CENTER_LEFT);
    
    Label labelNode = new Label(label + ":");
    labelNode.setFont(AppFonts.getUIFont());
    labelNode.setTextFill(Color.GRAY);
    labelNode.setMinWidth(80);
    
    Label valueNode = new Label(value);
    valueNode.setFont(AppFonts.getUIFont());
    valueNode.setTextFill(Color.LIGHTGRAY);
    
    row.getChildren().addAll(labelNode, valueNode);
    container.getChildren().add(row);
  }
  
  private void openAlphaFold(String uniprotId) {
    try {
      String url = "https://alphafold.ebi.ac.uk/entry/" + uniprotId;
      javafx.application.HostServices hostServices = org.baseplayer.MainApp.getHostServicesInstance();
      if (hostServices != null) {
        hostServices.showDocument(url);
      }
    } catch (Exception e) {
      System.err.println("Failed to open AlphaFold: " + e.getMessage());
    }
  }
  
  private void openUniProt(String uniprotId) {
    try {
      String url = "https://www.uniprot.org/uniprotkb/" + uniprotId;
      javafx.application.HostServices hostServices = org.baseplayer.MainApp.getHostServicesInstance();
      if (hostServices != null) {
        hostServices.showDocument(url);
      }
    } catch (Exception e) {
      System.err.println("Failed to open UniProt: " + e.getMessage());
    }
  }
  
  private void openPdbDownload(String pdbUrl) {
    try {
      if (pdbUrl != null && !pdbUrl.isEmpty()) {
        javafx.application.HostServices hostServices = org.baseplayer.MainApp.getHostServicesInstance();
        if (hostServices != null) {
          hostServices.showDocument(pdbUrl);
        }
      }
    } catch (Exception e) {
      System.err.println("Failed to download PDB: " + e.getMessage());
    }
  }

  private void openEmbeddedViewer(String url, String title) {
    // Embedded viewer removed — open external URL instead
    openUrl(url);
  }

  private void openImageWindow(String imageUrl, String title) {
    try {
      if (imageUrl == null || imageUrl.isBlank()) return;
      Image img = new Image(imageUrl, true);
      ImageView iv = new ImageView(img);
      iv.setPreserveRatio(true);
      iv.setSmooth(true);
      iv.setFitWidth(980);

      StackPane root = new StackPane(iv);
      root.setStyle("-fx-background-color: #000000;");
      // Provide an explicit initial scene size so window is visible before image finishes loading
      Scene scene = new Scene(root, 1000, 800);
      Stage stage = new Stage();
      stage.setTitle(title);
      stage.setScene(scene);

      // Try to set owner so the stage stacks relative to the main window
      try {
        Window owner = popup.getOwnerWindow();
        if (owner != null) {
          stage.initOwner(owner);
        }
      } catch (Exception ignored) {}

      // Position the image stage beside the popup (right side if possible,
      // otherwise place to the left). Keep within visual screen bounds.
      try {
        double gap = 8;
        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        double popupX = popup.getX();
        double popupY = popup.getY();
        double popupW = content.getWidth() > 0 ? content.getWidth() : 300;
        double popupH = content.getHeight() > 0 ? content.getHeight() : 200;

        double stageW = scene.getWidth();
        double stageH = scene.getHeight();

        double targetX = popupX + popupW + gap; // try right
        double targetY = popupY; // align top

        // If placing to the right would go off-screen, place to the left.
        if (targetX + stageW > vb.getMaxX()) {
          targetX = popupX - stageW - gap;
        }

        // Clamp into visual bounds
        if (targetX < vb.getMinX() + gap) targetX = vb.getMinX() + gap;
        if (targetY + stageH > vb.getMaxY() - gap) targetY = Math.max(vb.getMinY() + gap, vb.getMaxY() - stageH - gap);

        stage.setX(targetX);
        stage.setY(targetY);
      } catch (Exception ignored) {}

      // Keep the gene popup open while the image window is shown.
      // Temporarily disable autoHide and restore when image window is closed.
      try {
        popup.setAutoHide(false);
      } catch (Exception ignored) {}

      stage.setOnHidden(evt -> {
        try { popup.setAutoHide(true); } catch (Exception ignored) {}
      });
      stage.setOnCloseRequest(evt -> {
        try { popup.setAutoHide(true); } catch (Exception ignored) {}
      });

      // When image metadata loads, adjust window to fit (but cap to reasonable max)
      img.widthProperty().addListener((obs, oldVal, newVal) -> {
        try {
          double iw = newVal.doubleValue();
          double ih = img.getHeight();
          if (iw > 0 && ih > 0) {
            double maxW = 1200;
            double maxH = 900;
            double targetW = Math.min(iw, maxW);
            double targetH = Math.min(ih, maxH);
            iv.setFitWidth(targetW);
            stage.setWidth(targetW + 20);
            stage.setHeight(targetH + 60);
          }
        } catch (Exception ignored) {}
      });

      stage.show();

      // Ensure the image stage appears above the popup: briefly bring to front
      Platform.runLater(() -> {
        try {
          stage.setAlwaysOnTop(true);
          stage.toFront();
          stage.requestFocus();
          // briefly ensure it's top, then allow normal stacking
          stage.setAlwaysOnTop(false);
        } catch (Exception ignored) {}
      });
    } catch (Exception e) {
      System.err.println("Failed to open image window: " + e.getMessage());
    }
  }

  private void openUrl(String url) {
    try {
      if (url != null && !url.isEmpty()) {
        javafx.application.HostServices hostServices = org.baseplayer.MainApp.getHostServicesInstance();
        if (hostServices != null) {
          hostServices.showDocument(url);
        }
      }
    } catch (Exception e) {
      System.err.println("Failed to open URL: " + e.getMessage());
    }
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
    
    // Split by comma, normalize and deduplicate while keeping order
    String[] tumours = tumourTypes.split(",\\s*");
    java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
    for (String tumour : tumours) {
      tumour = tumour.trim();
      if (tumour.isEmpty()) continue;
      // Map to standardized cancer type name
      String displayName = CancerTypeMapper.mapCancerType(tumour);
      if (displayName == null || displayName.isEmpty()) continue;
      seen.add(displayName);
    }

    for (String displayName : seen) {
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
