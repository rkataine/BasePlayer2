package org.baseplayer.draw;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.CancerTypeMapper;
import org.baseplayer.annotation.CosmicCensusEntry;
import org.baseplayer.annotation.CosmicGenes;
import org.baseplayer.gene.Gene;
import org.baseplayer.gene.Transcript;
import org.baseplayer.ui.InfoPopup;
import org.baseplayer.ui.PopupContent;
import org.baseplayer.ui.PopupContent.Badge;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.BaseUtils;
import org.baseplayer.utils.GeneColors;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;

/**
 * Popup window that displays detailed gene information.
 * Delegates all rendering to {@link InfoPopup} via {@link PopupContent}.
 */
public class GeneInfoPopup {

  private static final double MAX_WIDTH = 450;

  private final InfoPopup infoPopup = new InfoPopup(MAX_WIDTH, 500, true);
  private DrawStack drawStack;

  public void show(Gene gene, DrawStack drawStack, Window owner, double x, double y) {
    this.drawStack = drawStack;
    PopupContent content = buildContent(gene);
    infoPopup.show(content, owner, x, y);
  }

  public void hide() { infoPopup.hide(); }

  public boolean isShowing() { return infoPopup.isShowing(); }

  // ── Content builder ────────────────────────────────────────────────────────

  private PopupContent buildContent(Gene gene) {
    PopupContent c = new PopupContent();

    // Header: gene name (clickable) + biotype badge + optional MANE badge
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

    if (gene.hasManeSelect()) {
      HBox spacer = new HBox();
      HBox.setHgrow(spacer, Priority.ALWAYS);
      Label maneLabel = new Label("MANE");
      maneLabel.setFont(AppFonts.getMonoFont(10));
      maneLabel.setTextFill(Color.WHITE);
      maneLabel.setStyle("-fx-background-color: #2e7d32; -fx-padding: 2 6; -fx-background-radius: 3;");
      header.getChildren().addAll(nameLabel, biotypeLabel, spacer, maneLabel);
    } else {
      header.getChildren().addAll(nameLabel, biotypeLabel);
    }
    c.node(header);

    // Info rows
    c.link("Ensembl ID", gene.id().replace("gene:", ""), () -> openEnsembl(gene.id()));
    c.link("GeneCards", gene.name(), () -> openGeneCards(gene.name()));

    String location = String.format("chr%s:%s-%s (%s)",
        gene.chrom(),
        BaseUtils.formatNumber(gene.start()),
        BaseUtils.formatNumber(gene.end()),
        gene.strand().equals("+") ? "forward" : "reverse");
    c.link("Location", location, () -> navigateToGene(gene));

    long size = gene.end() - gene.start();
    c.row("Size", formatSize(size));

    // Description
    String description = gene.description();
    if (description != null && !description.isEmpty()) {
      c.separator();
      c.section("Description");
      c.text(cleanDescription(description));
    }

    // COSMIC Cancer Gene Census
    CosmicCensusEntry cosmic = CosmicGenes.getEntry(gene.name());
    if (cosmic != null) {
      c.separator();
      buildCosmicSection(c, cosmic);
    }

    // Transcripts
    List<Transcript> allTranscripts = new ArrayList<>();
    if (gene.transcripts() != null) allTranscripts.addAll(gene.transcripts());
    List<Transcript> nonMane = AnnotationData.getNonManeTranscripts(gene.id());
    allTranscripts.addAll(nonMane);

    if (!allTranscripts.isEmpty()) {
      c.separator();
      c.section("Transcripts (" + allTranscripts.size() + ")");

      List<javafx.scene.Node> txNodes = allTranscripts.stream()
          .sorted((a, b) -> {
            if (a.isManeSelect() != b.isManeSelect()) return a.isManeSelect() ? -1 : 1;
            if (a.isManeClinic() != b.isManeClinic()) return a.isManeClinic() ? -1 : 1;
            return a.name() != null && b.name() != null ? a.name().compareTo(b.name()) : 0;
          })
          .limit(10)
          .map(this::createTranscriptRow)
          .collect(java.util.stream.Collectors.toList());

      if (allTranscripts.size() > 10) {
        Label moreLabel = new Label("... and " + (allTranscripts.size() - 10) + " more");
        moreLabel.setTextFill(Color.GRAY);
        moreLabel.setFont(AppFonts.getUIFont());
        txNodes.add(moreLabel);
      }

      c.scrollList(txNodes, 150);
    }

    return c;
  }

  // ── COSMIC section ─────────────────────────────────────────────────────────

  private void buildCosmicSection(PopupContent c, CosmicCensusEntry cosmic) {
    // Header with tier badge(s)
    List<Badge> cosmicBadges = new ArrayList<>();
    String tierColor = cosmic.isTier1() ? "#c62828" : "#e65100";
    cosmicBadges.add(new Badge("Tier " + cosmic.tier(), tierColor));
    if (cosmic.hallmark()) {
      cosmicBadges.add(new Badge("Hallmark", "#6a1b9a"));
    }

    // Custom header row
    HBox header = new HBox(8);
    header.setAlignment(Pos.CENTER_LEFT);
    Label title = new Label("COSMIC Cancer Gene Census");
    title.setFont(AppFonts.getBoldFont(12));
    title.setTextFill(Color.web("#ff6b6b"));
    header.getChildren().add(title);
    for (Badge b : cosmicBadges) {
      Label bl = new Label(b.text());
      bl.setFont(AppFonts.getMonoFont(10));
      bl.setTextFill(Color.WHITE);
      bl.setStyle(String.format("-fx-background-color: %s; -fx-padding: 2 6; -fx-background-radius: 3;",
          toHex(b.bgColor())));
      header.getChildren().add(bl);
    }
    c.node(header);

    if (cosmic.roleInCancer() != null && !cosmic.roleInCancer().isEmpty()) {
      c.row("Role", formatRoleInCancer(cosmic.roleInCancer()));
    }
    if (cosmic.molecularGenetics() != null && !cosmic.molecularGenetics().isEmpty()) {
      c.row("Genetics", formatMolecularGenetics(cosmic.molecularGenetics()));
    }
    if (cosmic.mutationTypes() != null && !cosmic.mutationTypes().isEmpty()) {
      c.row("Mutations", formatMutationTypes(cosmic.mutationTypes()));
    }

    // Tumour types
    VBox associationBox = new VBox(4);
    associationBox.setPadding(new Insets(4, 0, 0, 0));
    boolean hasAssociations = false;

    if (cosmic.somatic() && cosmic.tumourTypesSomatic() != null && !cosmic.tumourTypesSomatic().isEmpty()) {
      Label somaticTitle = new Label("Somatic tumours:");
      somaticTitle.setFont(AppFonts.getUIFont());
      somaticTitle.setTextFill(Color.GRAY);
      associationBox.getChildren().add(somaticTitle);
      associationBox.getChildren().add(createTumourBadges(cosmic.tumourTypesSomatic(), "#455a64"));
      hasAssociations = true;
    }
    if (cosmic.germline() && cosmic.tumourTypesGermline() != null && !cosmic.tumourTypesGermline().isEmpty()) {
      Label germlineTitle = new Label("Germline tumours:");
      germlineTitle.setFont(AppFonts.getUIFont());
      germlineTitle.setTextFill(Color.GRAY);
      associationBox.getChildren().add(germlineTitle);
      associationBox.getChildren().add(createTumourBadges(cosmic.tumourTypesGermline(), "#37474f"));
      hasAssociations = true;
    }
    if (hasAssociations) c.node(associationBox);

    if (cosmic.hasCancerSyndrome()) {
      c.row("Syndrome", cosmic.cancerSyndrome());
    }
    if (cosmic.translocationPartner() != null && !cosmic.translocationPartner().isEmpty()) {
      c.row("Transloc. partners", cosmic.translocationPartner());
    }
  }

  // ── Transcript row ─────────────────────────────────────────────────────────

  private HBox createTranscriptRow(Transcript tx) {
    HBox row = new HBox(8);
    row.setAlignment(Pos.CENTER_LEFT);

    String name = tx.name() != null ? tx.name() : tx.id().replace("transcript:", "");
    Label nameLabel = new Label(name);
    nameLabel.setFont(AppFonts.getMonoFont(11));
    nameLabel.setTextFill(Color.LIGHTGRAY);
    row.getChildren().add(nameLabel);

    if (tx.isManeSelect()) {
      Label mane = new Label("MANE Select");
      mane.setFont(AppFonts.getMonoFont(9));
      mane.setTextFill(Color.WHITE);
      mane.setStyle("-fx-background-color: #2e7d32; -fx-padding: 1 4; -fx-background-radius: 2;");
      row.getChildren().add(mane);
    } else if (tx.isManeClinic()) {
      Label mane = new Label("MANE Plus Clinical");
      mane.setFont(AppFonts.getMonoFont(9));
      mane.setTextFill(Color.WHITE);
      mane.setStyle("-fx-background-color: #1565c0; -fx-padding: 1 4; -fx-background-radius: 2;");
      row.getChildren().add(mane);
    }

    Label exonLabel = new Label(tx.exons().size() + " exons");
    exonLabel.setFont(AppFonts.getUIFont());
    exonLabel.setTextFill(Color.GRAY);
    row.getChildren().add(exonLabel);

    return row;
  }

  // ── Navigation / URL helpers ───────────────────────────────────────────────

  private void navigateToGene(Gene gene) {
    if (drawStack != null && drawStack.alignmentCanvas != null) {
      long geneLength = gene.end() - gene.start();
      long padding = geneLength / 10;
      long start = Math.max(1, gene.start() - padding);
      long end = Math.min((long) drawStack.chromSize, gene.end() + padding);
      drawStack.alignmentCanvas.zoomAnimation(start, end);
    }
  }

  private void openEnsembl(String geneId) {
    String ensemblId = geneId.replace("gene:", "");
    InfoPopup.openUrl("https://www.ensembl.org/Homo_sapiens/Gene/Summary?g=" + ensemblId);
  }

  private void openGeneCards(String geneName) {
    InfoPopup.openUrl("https://www.genecards.org/cgi-bin/carddisp.pl?gene=" + geneName);
  }

  // ── Formatting helpers ─────────────────────────────────────────────────────

  private String formatBiotype(String biotype) {
    return biotype == null ? "unknown" : biotype.replace("_", " ");
  }

  private String formatSize(long size) {
    if (size >= 1_000_000) return String.format("%.2f Mb", size / 1_000_000.0);
    if (size >= 1_000) return String.format("%.2f kb", size / 1_000.0);
    return size + " bp";
  }

  private String cleanDescription(String desc) {
    return desc.replaceAll("\\s*\\[Source:.*?\\]", "").trim();
  }

  private String formatMutationTypes(String types) {
    return types.replace("Mis", "Missense").replace("N", "Nonsense")
        .replace("F", "Frameshift").replace("S", "Splice")
        .replace("D", "Deletion").replace("A", "Amplification")
        .replace("T", "Translocation").replace("O", "Other");
  }

  private String formatRoleInCancer(String role) {
    return role.replace("TSG", "Tumor Suppressor Gene")
        .replace("oncogene", "Oncogene").replace("fusion", "Fusion");
  }

  private String formatMolecularGenetics(String genetics) {
    return genetics.replace("Dom", "Dominant").replace("Rec", "Recessive");
  }

  // ── UI utility ─────────────────────────────────────────────────────────────

  private FlowPane createTumourBadges(String tumourTypes, String bgColor) {
    FlowPane pane = new FlowPane(4, 4);
    pane.setPadding(new Insets(0, 0, 0, 8));
    pane.setMaxWidth(MAX_WIDTH - 30);

    String[] tumours = tumourTypes.split(",\\s*");
    java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
    for (String tumour : tumours) {
      tumour = tumour.trim();
      if (tumour.isEmpty()) continue;
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

  private static String toHex(Color c) {
    return String.format("#%02x%02x%02x",
        (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
  }
}
