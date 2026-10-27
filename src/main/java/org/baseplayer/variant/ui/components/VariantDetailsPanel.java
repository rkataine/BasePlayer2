package org.baseplayer.variant.ui.components;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.baseplayer.annotation.CosmicCensusEntry;
import org.baseplayer.components.InfoPopup;
import org.baseplayer.genome.ReferenceGenome;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.ClinVarAnnotator;
import org.baseplayer.variant.annotation.ClinVarAnnotator.SignificanceClass;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Closable tool-tab view with full details for a clicked variant (allele, samples,
 * gene annotation, control AF / ClinVar, INFO, and external links).
 */
public final class VariantDetailsPanel {

  private static final int INFO_COLUMNS = 3;

  /** Match Variant Manager table / canvas popup emphasis. */
  private static final String COLOR_TRUNCATING = "#f07178";
  private static final String COLOR_MISSENSE = "#e0c040";
  private static final String COLOR_SYNONYMOUS = "#5ecf8a";
  private static final String COLOR_BENIGN = "#5ecf8a";
  private static final String COLOR_VUS = "#e0a040";
  private static final String COLOR_RARE_AF = "#5ecf8a";
  private static final String COLOR_COMMON_AF = "#e0a040";
  private static final String COLOR_MUTED = "#8a8886";
  private static final String COLOR_CANCER = "#f07178";

  private Tab tab;
  private final VBox root = new VBox(10);
  private final ScrollPane scroll = new ScrollPane();
  private Runnable gotoAction;

  public VariantDetailsPanel() {
    root.getStyleClass().add("filter-panel");
    root.setPadding(new Insets(12, 14, 14, 14));
    scroll.setFitToWidth(true);
    scroll.getStyleClass().add("filter-scroll");
    scroll.setContent(root);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
  }

  /**
   * Show or refresh the details tab on {@code toolTabs} (Variant Filters sibling).
   */
  public void show(TabPane toolTabs, VariantNode node, String chromosome) {
    show(toolTabs, node, chromosome, null);
  }

  /**
   * @param onGoto when non-null, shows a "Go to variant" button that navigates the genome browser
   */
  public void show(TabPane toolTabs, VariantNode node, String chromosome, Runnable onGoto) {
    if (toolTabs == null || node == null) {
      return;
    }
    this.gotoAction = onGoto;
    rebuildContent(node, chromosome);
    String title = shortTitle(chromosome, node);
    if (tab == null || !toolTabs.getTabs().contains(tab)) {
      tab = new Tab(title, scroll);
      tab.setClosable(true);
      tab.setOnClosed(e -> tab = null);
      // Keep Filters / Comparison / Control fixed; append details after them.
      toolTabs.getTabs().add(tab);
      if (toolTabs.getTabClosingPolicy() == TabPane.TabClosingPolicy.UNAVAILABLE) {
        toolTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
      }
    } else {
      tab.setText(title);
      tab.setContent(scroll);
    }
    toolTabs.getSelectionModel().select(tab);
  }

  public void close() {
    if (tab != null && tab.getTabPane() != null) {
      tab.getTabPane().getTabs().remove(tab);
    }
    tab = null;
  }

  private void rebuildContent(VariantNode node, String chromosome) {
    root.getChildren().clear();

    String chromDisplay = ChromosomeNames.forDisplay(chromosome);

    // Top row: Variant (+ Go to) | Gene annotation — no title/badge header
    Node gotoTrailing = null;
    if (gotoAction != null) {
      Button gotoBtn = new Button("Go to variant");
      gotoBtn.getStyleClass().add("primary-button");
      gotoBtn.setOnAction(e -> {
        if (gotoAction != null) {
          gotoAction.run();
        }
      });
      gotoTrailing = gotoBtn;
    }
    VBox variantCol = sectionColumn(
        "Variant", buildVariantGrid(node, chromDisplay), gotoTrailing);

    VBox annotationCol = new VBox(8);
    annotationCol.setAlignment(Pos.TOP_LEFT);
    annotationCol.getChildren().add(
        sectionColumn("Gene annotation", buildGeneContent(node)));
    boolean hasControl = node.controlAf != null;
    boolean hasClinVar = node.clinVarClnsig != null || node.clinVarVariationId != null;
    if (hasControl || hasClinVar) {
      annotationCol.getChildren().add(
          sectionColumn("Control annotations", buildControlGrid(node)));
    }

    HBox topRow = twoColumnRow(variantCol, annotationCol);
    root.getChildren().add(topRow);

    VariantAnnotation ann = node.annotation;

    // Links right under gene annotation so they are immediately visible
    root.getChildren().add(new Separator());
    addSection(root, "External links");
    FlowPane links = new FlowPane(12, 6);
    links.setAlignment(Pos.CENTER_LEFT);
    addExternalLinks(links, node, chromosome, ann);
    if (links.getChildren().isEmpty()) {
      Label noLinks = new Label("No external links available for this allele.");
      noLinks.getStyleClass().add("subsection-label");
      root.getChildren().add(noLinks);
    } else {
      root.getChildren().add(links);
    }

    CosmicCensusEntry cosmic = ann != null ? ann.cosmicEntry() : null;
    if (cosmic != null) {
      root.getChildren().add(new Separator());
      root.getChildren().add(sectionColumn("COSMIC Cancer Gene Census", buildCosmicGrid(cosmic)));
    }

    Map<String, String> info = node.getInfoFields();
    if (info != null && !info.isEmpty()) {
      root.getChildren().add(new Separator());
      addSection(root, "INFO fields");
      root.getChildren().add(buildMultiColumnMap(info, INFO_COLUMNS));
    }

    // Sample calls last
    List<VariantNode.SampleCall> samples = node.getSamples();
    root.getChildren().add(new Separator());
    addSection(root, "Sample calls (" + (samples != null ? samples.size() : 0) + ")");
    if (samples == null || samples.isEmpty()) {
      Label none = new Label("No sample calls on this allele.");
      none.getStyleClass().add("subsection-label");
      root.getChildren().add(none);
    } else {
      FlowPane sampleFlow = new FlowPane(10, 10);
      sampleFlow.setAlignment(Pos.TOP_LEFT);
      sampleFlow.setPrefWrapLength(900);
      for (VariantNode.SampleCall call : samples) {
        if (call == null) {
          continue;
        }
        Node card = buildSampleCard(node, call);
        if (card instanceof Region region) {
          region.setPrefWidth(280);
          region.setMaxWidth(340);
        }
        sampleFlow.getChildren().add(card);
      }
      root.getChildren().add(sampleFlow);
    }
  }

  private static GridPane buildVariantGrid(VariantNode node, String chromDisplay) {
    GridPane variantGrid = kvGrid();
    int r = 0;
    r = addKv(variantGrid, r, "Chromosome", chromDisplay);
    r = addKv(variantGrid, r, "Position", formatPosition(node));
    r = addKv(variantGrid, r, "REF", nullToDash(node.ref));
    r = addKv(variantGrid, r, "ALT", nullToDash(node.alt));
    r = addKv(variantGrid, r, "Type", typeLabel(node.type),
        toHex(VariantTypeVisuals.color(node.type)), true);
    if (node.vcfId != null && !node.vcfId.isBlank()) {
      r = addKv(variantGrid, r, "ID", node.vcfId);
    }
    if (node.vcfFilter != null && !node.vcfFilter.isBlank()) {
      boolean pass = "PASS".equalsIgnoreCase(node.vcfFilter) || ".".equals(node.vcfFilter);
      r = addKv(variantGrid, r, "FILTER", node.vcfFilter,
          pass ? COLOR_SYNONYMOUS : COLOR_TRUNCATING, !pass);
    }
    if (node.siteQuality >= 0) {
      r = addKv(variantGrid, r, "Site QUAL", formatNumber(node.siteQuality));
    }
    if (node.svEnd > node.position) {
      r = addKv(variantGrid, r, "SV END", String.format("%,d", node.svEnd));
      r = addKv(variantGrid, r, "SV length",
          String.format("%,d bp", Math.max(0, node.svEnd - node.position)));
    }
    String mateChrom = node.mateChromosome();
    long matePos = node.matePosition();
    if (mateChrom != null || matePos >= 0) {
      r = addKv(variantGrid, r, "Mate", formatMate(mateChrom, matePos));
    }
    return variantGrid;
  }

  private static GridPane buildControlGrid(VariantNode node) {
    GridPane annGrid = kvGrid();
    int ar = 0;
    if (node.controlAf != null) {
      ar = addKv(annGrid, ar, "Control AF", formatAf(node.controlAf),
          controlAfColor(node.controlAf), node.controlAf < 0.01);
    }
    if (node.clinVarClnsig != null && !node.clinVarClnsig.isBlank()) {
      String display = node.clinVarClnsig.replace('_', ' ');
      SignificanceClass cls = ClinVarAnnotator.classify(node.clinVarClnsig);
      ar = addKv(annGrid, ar, "ClinVar significance", display,
          clinVarColor(node.clinVarClnsig),
          cls == SignificanceClass.PATHOGENIC || cls == SignificanceClass.VUS);
    }
    if (node.clinVarVariationId != null && !node.clinVarVariationId.isBlank()) {
      ar = addKv(annGrid, ar, "ClinVar ID", node.clinVarVariationId);
    }
    return annGrid;
  }

  private static Node buildGeneContent(VariantNode node) {
    VariantAnnotation ann = node.annotation;
    if (ann == null) {
      Label missing = new Label(
          "Not annotated yet. Run annotation in Variant Manager for this chromosome.");
      missing.getStyleClass().add("subsection-label");
      missing.setWrapText(true);
      return missing;
    }
    GridPane geneGrid = kvGrid();
    int gr = 0;
    String genes = ann.genesDisplay();
    if (genes != null && !genes.isBlank()) {
      gr = addKv(geneGrid, gr,
          ann.overlappingGenes() != null && ann.overlappingGenes().size() > 1
              ? "Genes" : "Gene",
          genes, ann.isCancerGene() ? COLOR_CANCER : null, ann.isCancerGene());
    }
    if (ann.effect() != null) {
      String fxColor = effectColor(ann.effect());
      boolean emphasize = isHighImpactEffect(ann.effect());
      gr = addKv(geneGrid, gr, "Effect", ann.effect().displayName(), fxColor, emphasize);
    }
    if (ann.transcriptId() != null && !ann.transcriptId().isBlank()) {
      gr = addKv(geneGrid, gr, "Transcript", ann.transcriptId());
    }
    if (ann.aaChange() != null && !ann.aaChange().isBlank()) {
      gr = addKv(geneGrid, gr, "AA change", ann.aaChange(),
          effectColor(ann.effect()), isHighImpactEffect(ann.effect()));
    }
    if (ann.codonChange() != null && !ann.codonChange().isBlank()) {
      gr = addKv(geneGrid, gr, "Codon / HGVS", ann.codonChange());
    }
    if (ann.codonNumber() > 0) {
      gr = addKv(geneGrid, gr, "Codon number", String.valueOf(ann.codonNumber()));
    }
    gr = addKv(geneGrid, gr, "Summary", ann.summary());
    if (ann.isCancerGene()) {
      gr = addKv(geneGrid, gr, "Cancer gene", "Yes", COLOR_CANCER, true);
    }
    return geneGrid;
  }

  private static GridPane buildCosmicGrid(CosmicCensusEntry cosmic) {
    GridPane cosmicGrid = kvGrid();
    int cr = 0;
    if (cosmic.geneSymbol() != null) {
      cr = addKv(cosmicGrid, cr, "Gene", cosmic.geneSymbol());
    }
    if (cosmic.name() != null && !cosmic.name().isBlank()) {
      cr = addKv(cosmicGrid, cr, "Name", cosmic.name());
    }
    if (cosmic.tier() != null && !cosmic.tier().isBlank()) {
      cr = addKv(cosmicGrid, cr, "Tier", cosmic.tier());
    }
    if (cosmic.roleInCancer() != null && !cosmic.roleInCancer().isBlank()) {
      cr = addKv(cosmicGrid, cr, "Role", cosmic.roleInCancer());
    }
    if (cosmic.tumourTypesSomatic() != null && !cosmic.tumourTypesSomatic().isBlank()) {
      cr = addKv(cosmicGrid, cr, "Tumours (somatic)", cosmic.tumourTypesSomatic());
    }
    if (cosmic.tumourTypesGermline() != null && !cosmic.tumourTypesGermline().isBlank()) {
      cr = addKv(cosmicGrid, cr, "Tumours (germline)", cosmic.tumourTypesGermline());
    }
    if (cosmic.cancerSyndrome() != null && !cosmic.cancerSyndrome().isBlank()) {
      cr = addKv(cosmicGrid, cr, "Syndrome", cosmic.cancerSyndrome());
    }
    if (cosmic.mutationTypes() != null && !cosmic.mutationTypes().isBlank()) {
      cr = addKv(cosmicGrid, cr, "Mutation types", cosmic.mutationTypes());
    }
    return cosmicGrid;
  }

  private static Node buildSampleCard(VariantNode node, VariantNode.SampleCall call) {
    VBox card = new VBox(4);
    card.setPadding(new Insets(8, 10, 8, 10));
    card.setStyle(
        "-fx-background-color: rgba(255,255,255,0.04);"
            + "-fx-background-radius: 6;");

    String name = sampleName(call);
    Label nameLabel = new Label(name != null ? name : "Sample");
    nameLabel.getStyleClass().add("section-header");
    card.getChildren().add(nameLabel);

    GridPane grid = kvGrid();
    int r = 0;
    if (call.gt != null && !call.gt.isBlank()) {
      r = addKv(grid, r, "Genotype", call.gt);
      if (node.isHeterozygous(call)) {
        r = addKv(grid, r, "Zygosity", "Heterozygous", COLOR_MISSENSE, false);
      } else if (node.isHomozygousAlt(call)) {
        r = addKv(grid, r, "Zygosity", "Homozygous ALT", COLOR_TRUNCATING, true);
      } else if (node.isHomozygousRef(call)) {
        r = addKv(grid, r, "Zygosity", "Homozygous REF", COLOR_MUTED, false);
      }
    }
    if (call.isPhased) {
      r = addKv(grid, r, "Phased", "Yes");
    }
    if (call.quality >= 0) {
      boolean lowGq = call.quality < 20;
      r = addKv(grid, r, "GQ / quality", formatNumber(call.quality),
          lowGq ? COLOR_TRUNCATING : null, lowGq);
    }
    if (call.depth >= 0) {
      boolean lowDepth = call.depth < 10;
      r = addKv(grid, r, "Depth", String.valueOf(call.depth),
          lowDepth ? COLOR_VUS : null, lowDepth);
    }
    if (call.alleleFraction >= 0) {
      boolean extremeAf = call.alleleFraction < 0.15 || call.alleleFraction > 0.85;
      r = addKv(grid, r, "Allele fraction",
          String.format(Locale.ROOT, "%.3f", call.alleleFraction),
          extremeAf ? COLOR_VUS : null, extremeAf);
    }
    card.getChildren().add(grid);

    if (call.formatFields != null && !call.formatFields.isEmpty()) {
      card.getChildren().add(buildExpandableMap(
          "FORMAT (" + call.formatFields.size() + ")", call.formatFields));
    }
    return card;
  }

  private static void addExternalLinks(
      FlowPane links, VariantNode node, String chromosome, VariantAnnotation ann) {
    String chrom = ChromosomeNames.strip(chromosome != null ? chromosome : "");
    String ref = node.ref != null ? node.ref : "";
    String alt = node.alt != null ? node.alt : "";
    String gnomadId = node.position + "-" + ref + "-" + alt;
    String chromPosRefAlt = chrom + "-" + node.position + "-" + ref + "-" + alt;
    String build = resolveAssemblyBuild();
    String rsId = resolveRsId(node);

    String clinVarUrl = ClinVarAnnotator.variationUrl(node.clinVarVariationId);
    if (clinVarUrl != null) {
      addLink(links, "ClinVar", clinVarUrl);
    } else if (!ref.isBlank() && !alt.isBlank()) {
      addLink(links, "ClinVar search",
          "https://www.ncbi.nlm.nih.gov/clinvar/?term=" + encode(chromPosRefAlt));
    }

    // VarSome only when an rsID is available (chrom-pos-ref-alt links are unreliable).
    if (rsId != null) {
      addLink(links, "VarSome",
          "https://varsome.com/variant/" + build + "/" + rsId);
      addLink(links, "dbSNP", "https://www.ncbi.nlm.nih.gov/snp/" + encode(rsId));
    }

    if (!ref.isBlank() && !alt.isBlank() && !chrom.isBlank()
        && !ref.startsWith("<") && !alt.startsWith("<")) {
      addLink(links, "gnomAD",
          "https://gnomad.broadinstitute.org/variant/" + chrom + "-" + gnomadId
              + "?dataset=gnomad_r4");
    }

    String gene = ann != null ? ann.geneName() : null;
    if (gene != null && !gene.isBlank()) {
      addLink(links, "GeneCards",
          "https://www.genecards.org/cgi-bin/carddisp.pl?gene=" + encode(gene));
      addLink(links, "Ensembl gene search",
          "https://www.ensembl.org/Homo_sapiens/Search/Results?q=" + encode(gene)
              + ";site=ensembl;facet_species=Human");
    }

    if (!chrom.isBlank() && node.position > 0) {
      long end = node.svEnd > node.position ? node.svEnd : node.position;
      addLink(links, "UCSC Browser",
          "https://genome.ucsc.edu/cgi-bin/hgTracks?db=" + ucscDb(build)
              + "&position=chr" + chrom + "%3A" + Math.max(1, node.position - 50)
              + "-" + (end + 50));
      addLink(links, "Ensembl location",
          "https://www.ensembl.org/Homo_sapiens/Location/View?r="
              + chrom + "%3A" + Math.max(1, node.position - 50) + "-" + (end + 50));
    }
  }

  /** Normalize INFO RS / rsid / VCF ID into {@code rs…}, or null when absent. */
  private static String resolveRsId(VariantNode node) {
    String rs = firstInfo(node, "RS", "rsid", "RSID", "dbSNP");
    if (rs == null || rs.isBlank()) {
      // Some VCFs put the rsID in the ID column (e.g. rs746753722).
      if (node.vcfId != null && !node.vcfId.isBlank()) {
        String id = node.vcfId.trim();
        int semi = id.indexOf(';');
        if (semi >= 0) {
          id = id.substring(0, semi).trim();
        }
        if (id.regionMatches(true, 0, "rs", 0, 2)) {
          rs = id;
        }
      }
    }
    if (rs == null || rs.isBlank() || ".".equals(rs)) {
      return null;
    }
    rs = rs.trim();
    if (rs.regionMatches(true, 0, "rs", 0, 2)) {
      return "rs" + rs.substring(2);
    }
    // Numeric-only RS INFO values
    if (rs.chars().allMatch(Character::isDigit)) {
      return "rs" + rs;
    }
    return null;
  }

  private static String firstInfo(VariantNode node, String... keys) {
    Map<String, String> info = node.getInfoFields();
    if (info == null || info.isEmpty()) {
      return null;
    }
    for (String key : keys) {
      String v = info.get(key);
      if (v != null && !v.isBlank() && !".".equals(v)) {
        return v.trim();
      }
    }
    // Case-insensitive fallback
    for (Map.Entry<String, String> e : info.entrySet()) {
      if (e.getKey() == null || e.getValue() == null) {
        continue;
      }
      for (String key : keys) {
        if (e.getKey().equalsIgnoreCase(key) && !e.getValue().isBlank()) {
          return e.getValue().trim();
        }
      }
    }
    return null;
  }

  private static String resolveAssemblyBuild() {
    try {
      ReferenceGenome genome =
          ServiceRegistry.getInstance().getReferenceGenomeService().getCurrentGenome();
      if (genome != null && genome.getName() != null) {
        String name = genome.getName().toLowerCase(Locale.ROOT);
        if (name.contains("hg19") || name.contains("grch37") || name.contains("b37")) {
          return "hg19";
        }
        if (name.contains("hg38") || name.contains("grch38") || name.contains("b38")) {
          return "hg38";
        }
      }
    } catch (Throwable ignored) {
      // default
    }
    return "hg38";
  }

  private static String ucscDb(String build) {
    return "hg19".equals(build) ? "hg19" : "hg38";
  }

  private static void addLink(FlowPane links, String label, String url) {
    if (url == null || url.isBlank()) {
      return;
    }
    Hyperlink link = new Hyperlink(label);
    link.setOnAction(e -> InfoPopup.openUrl(url));
    links.getChildren().add(link);
  }

  private static String encode(String raw) {
    return URLEncoder.encode(raw != null ? raw : "", StandardCharsets.UTF_8);
  }

  private static void addSection(VBox root, String title) {
    Label label = new Label(title);
    label.getStyleClass().add("section-header");
    root.getChildren().add(label);
  }

  private static VBox sectionColumn(String title, Node content) {
    return sectionColumn(title, content, null);
  }

  private static VBox sectionColumn(String title, Node content, Node headerTrailing) {
    Label label = new Label(title);
    label.getStyleClass().add("section-header");
    Node header = label;
    if (headerTrailing != null) {
      Region spacer = new Region();
      HBox.setHgrow(spacer, Priority.ALWAYS);
      HBox headerRow = new HBox(10, label, spacer, headerTrailing);
      headerRow.setAlignment(Pos.CENTER_LEFT);
      headerRow.setMaxWidth(Double.MAX_VALUE);
      header = headerRow;
    }
    VBox col = new VBox(4, header, content);
    HBox.setHgrow(col, Priority.ALWAYS);
    col.setMaxWidth(Double.MAX_VALUE);
    return col;
  }

  private static HBox twoColumnRow(Node left, Node right) {
    HBox row = new HBox(24, left, right);
    row.setAlignment(Pos.TOP_LEFT);
    row.setFillHeight(false);
    row.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(left, Priority.ALWAYS);
    HBox.setHgrow(right, Priority.ALWAYS);
    if (left instanceof Region leftRegion) {
      leftRegion.setMaxWidth(Double.MAX_VALUE);
    }
    if (right instanceof Region rightRegion) {
      rightRegion.setMaxWidth(Double.MAX_VALUE);
    }
    if (left instanceof VBox leftBox) {
      leftBox.setAlignment(Pos.TOP_LEFT);
    }
    if (right instanceof VBox rightBox) {
      rightBox.setAlignment(Pos.TOP_LEFT);
    }
    return row;
  }

  private static GridPane kvGrid() {
    GridPane grid = new GridPane();
    grid.setHgap(10);
    grid.setVgap(3);
    grid.setMaxWidth(Double.MAX_VALUE);
    ColumnConstraints keyCol = new ColumnConstraints();
    keyCol.setMinWidth(100);
    keyCol.setPrefWidth(120);
    ColumnConstraints valCol = new ColumnConstraints();
    valCol.setHgrow(Priority.ALWAYS);
    grid.getColumnConstraints().addAll(keyCol, valCol);
    return grid;
  }

  private static int addKv(GridPane grid, int row, String key, String value) {
    return addKv(grid, row, key, value, null, false);
  }

  private static int addKv(
      GridPane grid, int row, String key, String value, String colorHex, boolean bold) {
    Label k = new Label(key);
    k.getStyleClass().add("subsection-label");
    Label v = new Label(value != null ? value : "—");
    v.getStyleClass().add("value-label");
    v.setWrapText(true);
    v.setMaxWidth(Double.MAX_VALUE);
    applyValueColor(v, colorHex, bold);
    GridPane.setHgrow(v, Priority.ALWAYS);
    grid.add(k, 0, row);
    grid.add(v, 1, row);
    return row + 1;
  }

  private static void applyValueColor(Label label, String colorHex, boolean bold) {
    if (label == null) {
      return;
    }
    StringBuilder style = new StringBuilder();
    if (colorHex != null && !colorHex.isBlank()) {
      style.append("-fx-text-fill: ").append(colorHex).append(';');
    }
    if (bold) {
      style.append("-fx-font-weight: bold;");
    }
    if (style.length() > 0) {
      label.setStyle(style.toString());
    }
  }

  private static String effectColor(VariantEffect effect) {
    if (effect == null) {
      return COLOR_MUTED;
    }
    return switch (effect) {
      case CODING_STOP_GAIN, CODING_STOP_LOSS, CODING_FRAMESHIFT, SPLICE_SITE
          -> COLOR_TRUNCATING;
      case CODING_MISSENSE, CODING_INFRAME -> COLOR_MISSENSE;
      case CODING_SYNONYMOUS -> COLOR_SYNONYMOUS;
      case CODING_OTHER, UTR5, UTR3, INTRONIC, NONCODING_GENE -> COLOR_MUTED;
      default -> null;
    };
  }

  private static boolean isHighImpactEffect(VariantEffect effect) {
    if (effect == null) {
      return false;
    }
    return switch (effect) {
      case CODING_STOP_GAIN, CODING_STOP_LOSS, CODING_FRAMESHIFT,
           SPLICE_SITE, CODING_MISSENSE -> true;
      default -> false;
    };
  }

  private static String clinVarColor(String clnsig) {
    SignificanceClass cls = ClinVarAnnotator.classify(clnsig);
    if (cls == null) {
      return COLOR_MUTED;
    }
    return switch (cls) {
      case PATHOGENIC -> COLOR_TRUNCATING;
      case VUS -> COLOR_VUS;
      case BENIGN -> COLOR_BENIGN;
      case OTHER -> COLOR_MUTED;
    };
  }

  private static String controlAfColor(double af) {
    if (af < 0.001) {
      return COLOR_RARE_AF;
    }
    if (af < 0.01) {
      return COLOR_MISSENSE;
    }
    return COLOR_COMMON_AF;
  }

  private static String toHex(Color color) {
    if (color == null) {
      return COLOR_MUTED;
    }
    return String.format(Locale.ROOT, "#%02x%02x%02x",
        Math.round(color.getRed() * 255),
        Math.round(color.getGreen() * 255),
        Math.round(color.getBlue() * 255));
  }

  /** Compact multi-column INFO layout (key/value pairs fill left→right, top→bottom). */
  private static Node buildMultiColumnMap(Map<String, String> fields, int columns) {
    int cols = Math.max(1, columns);
    List<Map.Entry<String, String>> entries = new ArrayList<>(fields.entrySet());
    GridPane grid = new GridPane();
    grid.setHgap(18);
    grid.setVgap(3);
    grid.setMaxWidth(Double.MAX_VALUE);
    for (int c = 0; c < cols; c++) {
      ColumnConstraints keyCol = new ColumnConstraints();
      keyCol.setMinWidth(72);
      keyCol.setPrefWidth(90);
      ColumnConstraints valCol = new ColumnConstraints();
      valCol.setHgrow(Priority.ALWAYS);
      valCol.setPercentWidth(100.0 / cols);
      grid.getColumnConstraints().addAll(keyCol, valCol);
    }
    int rows = (entries.size() + cols - 1) / cols;
    for (int i = 0; i < entries.size(); i++) {
      Map.Entry<String, String> entry = entries.get(i);
      int col = i / rows;
      int row = i % rows;
      Label k = new Label(entry.getKey());
      k.getStyleClass().add("subsection-label");
      Label v = new Label(nullToDash(entry.getValue()));
      v.getStyleClass().add("value-label");
      v.setWrapText(true);
      v.setMaxWidth(Double.MAX_VALUE);
      grid.add(k, col * 2, row);
      grid.add(v, col * 2 + 1, row);
    }
    return grid;
  }

  private static Node buildExpandableMap(String title, Map<String, String> fields) {
    VBox body = new VBox(2);
    body.setPadding(new Insets(4, 0, 2, 4));
    Map<String, String> ordered = new LinkedHashMap<>(fields);
    for (Map.Entry<String, String> entry : ordered.entrySet()) {
      HBox row = new HBox(8);
      Label k = new Label(entry.getKey());
      k.getStyleClass().add("subsection-label");
      k.setMinWidth(72);
      Label v = new Label(nullToDash(entry.getValue()));
      v.getStyleClass().add("value-label");
      v.setWrapText(true);
      HBox.setHgrow(v, Priority.ALWAYS);
      row.getChildren().addAll(k, v);
      body.getChildren().add(row);
    }
    TitledPane pane = new TitledPane(title, body);
    pane.setExpanded(false);
    pane.setAnimated(false);
    pane.setMaxWidth(Double.MAX_VALUE);
    return pane;
  }

  private static String shortTitle(String chromosome, VariantNode node) {
    String chrom = ChromosomeNames.forDisplay(chromosome);
    String allele = nullToDash(node.ref) + ">" + nullToDash(node.alt);
    if (allele.length() > 16) {
      allele = allele.substring(0, 14) + "…";
    }
    String title = chrom + ":" + node.position + " " + allele;
    if (title.length() > 28) {
      return title.substring(0, 26) + "…";
    }
    return title;
  }

  private static String formatPosition(VariantNode node) {
    if (node.svEnd > node.position) {
      return String.format("%,d – %,d", node.position, node.svEnd);
    }
    return String.format("%,d", node.position);
  }

  private static String formatMate(String chrom, long pos) {
    String c = chrom != null ? ChromosomeNames.forDisplay(chrom) : "?";
    if (pos >= 0) {
      return c + ":" + String.format("%,d", pos);
    }
    return c;
  }

  private static String formatAf(double af) {
    if (af == 0) {
      return "0";
    }
    if (af >= 0.01) {
      return String.format(Locale.ROOT, "%.4f", af);
    }
    if (af >= 0.0001) {
      return String.format(Locale.ROOT, "%.6f", af);
    }
    return String.format(Locale.ROOT, "%.2e", af);
  }

  private static String sampleName(VariantNode.SampleCall call) {
    SampleTrack track = call.getTrack();
    if (track != null && track.getName() != null && !track.getName().isBlank()) {
      return track.getName();
    }
    if (call.sample != null && call.sample.getName() != null) {
      return call.sample.getName();
    }
    return null;
  }

  private static String typeLabel(VcfVariantType type) {
    if (type == null) {
      return "Unknown";
    }
    String shortLabel = VariantTypeVisuals.shortLabel(type);
    return shortLabel != null ? shortLabel : type.name();
  }

  private static String formatNumber(double value) {
    if (Math.rint(value) == value) {
      return String.format("%.0f", value);
    }
    return String.format("%.2f", value);
  }

  private static String nullToDash(String value) {
    return value == null || value.isBlank() ? "—" : value;
  }
}
