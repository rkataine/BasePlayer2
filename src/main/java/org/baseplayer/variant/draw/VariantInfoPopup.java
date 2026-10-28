package org.baseplayer.variant.draw;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.baseplayer.annotation.CosmicCensusEntry;
import org.baseplayer.components.InfoPopup;
import org.baseplayer.components.PopupContent;
import org.baseplayer.components.PopupContent.Badge;
import org.baseplayer.io.Settings;
import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.ClinVarAnnotator;
import org.baseplayer.variant.annotation.VariantAnnotation;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;

public class VariantInfoPopup extends InfoPopup {

  private static final double TOP_RIGHT_MARGIN_X = 16;
  private static final double TOP_RIGHT_MARGIN_Y = 6;
  private static final double POPUP_ESTIMATED_WIDTH = 460;

  /** Highlight these INFO keys in the summary when present (CNV / FACETS first). */
  private static final String[] HIGHLIGHT_INFO_KEYS = {
      "TCN_EM", "TCN", "LCN_EM", "CNLR_MEDIAN", "CF_EM", "MAF_EM",
      "NUM_MARK", "SVTYPE", "SVLEN", "END", "IMPRECISE"
  };

  public VariantInfoPopup() {
    super(440, 620, true);
  }

  public void show(VariantNode node, VariantNode.SampleCall call, String chromosome,
                   Window owner, double x, double y) {
    if (node == null || owner == null) {
      return;
    }
    PopupContent content = buildContent(node, call, chromosome);
    Settings.ReadInfoPopupPosition position = Settings.get().getReadInfoPopupPosition();
    if (position == Settings.ReadInfoPopupPosition.TOP_RIGHT) {
      double anchoredX = owner.getX() + owner.getWidth() - POPUP_ESTIMATED_WIDTH - TOP_RIGHT_MARGIN_X;
      double anchoredY = owner.getY() + TOP_RIGHT_MARGIN_Y;
      anchoredX = Math.max(owner.getX() + TOP_RIGHT_MARGIN_X, anchoredX);
      show(content, owner, anchoredX, anchoredY);
    } else {
      show(content, owner, x, y);
    }
  }

  // ── Content builder ────────────────────────────────────────────────────────

  private PopupContent buildContent(VariantNode node, VariantNode.SampleCall call, String chromosome) {
    PopupContent c = new PopupContent();

    String title = formatAllele(node);
    Color typeColor = colorForType(node.type);
    c.title(title, typeColor);

    List<Badge> badges = new ArrayList<>();
    badges.add(new Badge(typeLabel(node.type), toHex(typeColor)));
    if (node.annotation != null && node.annotation.effect() != null) {
      badges.add(new Badge(node.annotation.effect().displayName(), "#666688"));
    }
    if (node.annotation != null && node.annotation.isCancerGene()) {
      badges.add(new Badge("Cancer gene", "#aa4444"));
    }
    if (node.clinVarClnsig != null && !node.clinVarClnsig.isBlank()) {
      badges.add(new Badge(
          node.clinVarClnsig.replace('_', ' '),
          ClinVarAnnotator.looksPathogenic(node.clinVarClnsig) ? "#aa4444" : "#666688"));
    }
    c.badges(badges);
    c.separator();

    c.section("Variant");
    c.row("Chromosome", ChromosomeNames.forDisplay(chromosome));
    c.row("Position", formatPosition(node));
    c.row("REF", nullToDash(node.ref));
    c.row("ALT", nullToDash(node.alt));
    c.row("Type", typeLabel(node.type));
    if (node.vcfId != null && !node.vcfId.isBlank()) {
      c.row("ID", node.vcfId);
    }
    if (node.vcfFilter != null && !node.vcfFilter.isBlank()) {
      c.row("FILTER", node.vcfFilter);
    }
    if (node.siteQuality >= 0) {
      c.row("Site QUAL", formatNumber(node.siteQuality));
    }
    if (node.svEnd > node.position) {
      c.row("SV END", String.format("%,d", node.svEnd));
      c.row("SV length", String.format("%,d bp", Math.max(0, node.svEnd - node.position)));
    }
    String mateChrom = node.mateChromosome();
    long matePos = node.matePosition();
    if (mateChrom != null || matePos >= 0) {
      c.row("Mate", formatMate(mateChrom, matePos));
    }

    if (node.clinVarClnsig != null || node.clinVarVariationId != null) {
      c.separator();
      c.section("ClinVar");
      if (node.clinVarClnsig != null && !node.clinVarClnsig.isBlank()) {
        c.row("Significance", node.clinVarClnsig.replace('_', ' '));
      }
      String clinVarUrl = ClinVarAnnotator.variationUrl(node.clinVarVariationId);
      if (clinVarUrl != null) {
        String idLabel = node.clinVarVariationId.trim();
        int semi = idLabel.indexOf(';');
        if (semi >= 0) {
          idLabel = idLabel.substring(0, semi).trim();
        }
        c.link("ClinVar", idLabel, () -> InfoPopup.openUrl(clinVarUrl));
      }
    }

    Map<String, String> info = node.getInfoFields();
    if (!info.isEmpty()) {
      boolean anyHighlight = false;
      for (String key : HIGHLIGHT_INFO_KEYS) {
        String value = info.get(key);
        if (value != null && !value.isBlank()) {
          if (!anyHighlight) {
            c.separator();
            c.section(VariantTypeVisuals.isCnv(node.type) ? "CNV metrics" : "Key INFO");
            anyHighlight = true;
          }
          c.row(key, value);
        }
      }
    }

    if (call != null) {
      c.separator();
      c.section("Sample call");
      String sampleName = sampleName(call);
      if (sampleName != null) {
        c.row("Sample", sampleName);
      }
      if (call.gt != null && !call.gt.isBlank()) {
        c.row("Genotype", call.gt);
        if (node.isHeterozygous(call)) {
          c.row("Zygosity", "Heterozygous");
        } else if (node.isHomozygousAlt(call)) {
          c.row("Zygosity", "Homozygous ALT");
        }
      }
      if (call.isPhased) {
        c.row("Phased", "Yes");
      }
      if (call.quality >= 0) {
        c.row("GQ / quality", formatNumber(call.quality));
      }
      if (call.depth >= 0) {
        c.row("Depth", String.valueOf(call.depth));
      }
      if (call.alleleFraction >= 0) {
        c.row("Allele fraction", String.format("%.3f", call.alleleFraction));
      }
    }

    VariantAnnotation ann = node.annotation;
    c.separator();
    c.section("Annotation");
    if (ann == null) {
      c.text("Not annotated yet. Run annotation in Variant Manager for this chromosome.");
    } else {
      String genes = ann.genesDisplay();
      if (genes != null && !genes.isBlank()) {
        c.row(ann.overlappingGenes() != null && ann.overlappingGenes().size() > 1
            ? "Genes" : "Gene", genes);
      }
      if (ann.effect() != null) {
        c.row("Effect", ann.effect().displayName());
      }
      if (ann.transcriptId() != null && !ann.transcriptId().isBlank()) {
        c.row("Transcript", ann.transcriptId());
      }
      if (ann.aaChange() != null && !ann.aaChange().isBlank()) {
        c.row("AA change", ann.aaChange());
      }
      if (ann.codonChange() != null && !ann.codonChange().isBlank()) {
        c.row("Codon / HGVS", ann.codonChange());
      }
      if (ann.codonNumber() > 0) {
        c.row("Codon number", String.valueOf(ann.codonNumber()));
      }
      c.row("Summary", ann.summary());

      CosmicCensusEntry cosmic = ann.cosmicEntry();
      if (cosmic != null) {
        c.separator();
        c.section("COSMIC Cancer Gene Census");
        if (cosmic.geneSymbol() != null) {
          c.row("Gene", cosmic.geneSymbol());
        }
        if (cosmic.name() != null && !cosmic.name().isBlank()) {
          c.row("Name", cosmic.name());
        }
        if (cosmic.tier() != null && !cosmic.tier().isBlank()) {
          c.row("Tier", cosmic.tier());
        }
        if (cosmic.roleInCancer() != null && !cosmic.roleInCancer().isBlank()) {
          c.row("Role", cosmic.roleInCancer());
        }
        if (cosmic.tumourTypesSomatic() != null && !cosmic.tumourTypesSomatic().isBlank()) {
          c.row("Tumours (somatic)", cosmic.tumourTypesSomatic());
        }
        if (cosmic.tumourTypesGermline() != null && !cosmic.tumourTypesGermline().isBlank()) {
          c.row("Tumours (germline)", cosmic.tumourTypesGermline());
        }
        if (cosmic.cancerSyndrome() != null && !cosmic.cancerSyndrome().isBlank()) {
          c.row("Syndrome", cosmic.cancerSyndrome());
        }
        if (cosmic.mutationTypes() != null && !cosmic.mutationTypes().isBlank()) {
          c.row("Mutation types", cosmic.mutationTypes());
        }
      }
    }

    // Expandable dumps live below the summary TextArea (InfoPopup interactive section).
    c.node(buildExpandableAllVcfFields(node, call, chromosome));
    if (node.getSampleCount() > 1) {
      c.node(buildExpandableOtherSamples(node, call));
    }

    return c;
  }

  /**
   * Collapsed by default; on first expand, loads every fixed/INFO/FORMAT field from the
   * sample VCF (falls back to whatever was already cached on the node/call).
   */
  private static Node buildExpandableAllVcfFields(
      VariantNode node, VariantNode.SampleCall call, String chromosome) {
    VBox body = new VBox(3);
    body.setPadding(new Insets(4, 0, 2, 4));
    Label hint = new Label("Expand to list all VCF fields…");
    hint.setFont(AppFonts.getUIFont());
    hint.setTextFill(Color.GRAY);
    body.getChildren().add(hint);

    TitledPane pane = titledPane("All VCF fields", body);
    AtomicBoolean loading = new AtomicBoolean(false);
    pane.expandedProperty().addListener((obs, wasExpanded, expanded) -> {
      if (!expanded || loading.get()) {
        return;
      }
      if (Boolean.TRUE.equals(body.getProperties().get("vcfFieldsLoaded"))) {
        return;
      }
      loading.set(true);
      body.getChildren().setAll(statusLabel("Loading VCF fields…"));

      Path vcfPath = resolveVcfPath(call);
      String preferredSample = sampleName(call);
      Map<String, String> cachedInfo = node.getInfoFields();
      Map<String, String> cachedFormat = call != null && call.formatFields != null
          ? call.formatFields
          : Map.of();

      Thread loader = new Thread(() -> {
        VcfReader.SiteFields fromFile = null;
        String error = null;
        if (vcfPath != null) {
          try (VcfReader reader = new VcfReader(vcfPath)) {
            fromFile = reader.findSiteFields(
                chromosome,
                node.position,
                node.svEnd,
                node.ref,
                node.alt,
                preferredSample);
          } catch (Exception ex) {
            error = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
          }
        }
        VcfReader.SiteFields site = fromFile;
        String err = error;
        Platform.runLater(() -> {
          body.getChildren().clear();
          if (site != null) {
            appendSection(body, "Fixed", site.fixed());
            appendSection(body, "INFO", site.info());
            appendSection(body, "FORMAT"
                + (site.matchedSample() != null ? " (" + site.matchedSample() + ")" : ""),
                site.format());
            int total = site.fixed().size() + site.info().size() + site.format().size();
            pane.setText("All VCF fields (" + total + ")");
          } else {
            if (!cachedInfo.isEmpty() || !cachedFormat.isEmpty()) {
              appendSection(body, "INFO (cached)", cachedInfo);
              appendSection(body, "FORMAT (cached)", cachedFormat);
              if (err != null) {
                body.getChildren().add(statusLabel("Could not re-read VCF: " + err));
              } else if (vcfPath == null) {
                body.getChildren().add(statusLabel("No VCF path on this sample — showing cached fields only."));
              } else {
                body.getChildren().add(statusLabel("No matching VCF record found at this locus."));
              }
              pane.setText("All VCF fields (" + (cachedInfo.size() + cachedFormat.size()) + ")");
            } else {
              body.getChildren().add(statusLabel(
                  err != null ? "Could not load VCF fields: " + err
                      : (vcfPath == null
                          ? "No VCF path available for this call."
                          : "No matching VCF record found at this locus.")));
            }
          }
          body.getProperties().put("vcfFieldsLoaded", Boolean.TRUE);
          loading.set(false);
        });
      }, "variant-info-vcf-fields");
      loader.setDaemon(true);
      loader.start();
    });
    return pane;
  }

  private static Path resolveVcfPath(VariantNode.SampleCall call) {
    if (call == null) {
      return null;
    }
    Sample sample = call.sample;
    if (sample != null && sample.getPath() != null) {
      return sample.getPath();
    }
    SampleTrack track = call.getTrack();
    if (track == null) {
      return null;
    }
    for (Sample s : track.getSamples()) {
      if (s != null && s.getDataType() == Sample.DataType.VCF && s.getPath() != null) {
        return s.getPath();
      }
    }
    return null;
  }

  private static void appendSection(VBox body, String title, Map<String, String> fields) {
    if (fields == null || fields.isEmpty()) {
      return;
    }
    Label header = new Label(title);
    header.setFont(AppFonts.getUIFont());
    header.setTextFill(Color.web("#9aa0a6"));
    header.setPadding(new Insets(body.getChildren().isEmpty() ? 0 : 6, 0, 0, 0));
    body.getChildren().add(header);
    for (Map.Entry<String, String> entry : fields.entrySet()) {
      body.getChildren().add(kvRow(entry.getKey(), nullToDash(entry.getValue())));
    }
  }

  private static Label statusLabel(String text) {
    Label label = new Label(text);
    label.setFont(AppFonts.getUIFont());
    label.setTextFill(Color.GRAY);
    label.setWrapText(true);
    return label;
  }

  private static Node buildExpandableOtherSamples(VariantNode node, VariantNode.SampleCall clicked) {
    VBox body = new VBox(4);
    body.setPadding(new Insets(4, 0, 2, 4));
    for (VariantNode.SampleCall other : node.getSamples()) {
      if (other == null || other == clicked) {
        continue;
      }
      String name = sampleName(other);
      if (name == null) {
        name = "Sample";
      }
      StringBuilder summary = new StringBuilder();
      if (other.gt != null && !other.gt.isBlank()) {
        summary.append(other.gt);
      }
      if (other.alleleFraction >= 0) {
        if (summary.length() > 0) {
          summary.append("  ");
        }
        summary.append(String.format("AF=%.3f", other.alleleFraction));
      }
      if (other.depth >= 0) {
        if (summary.length() > 0) {
          summary.append("  ");
        }
        summary.append("DP=").append(other.depth);
      }
      if (other.quality >= 0) {
        if (summary.length() > 0) {
          summary.append("  ");
        }
        summary.append("GQ=").append(formatNumber(other.quality));
      }
      body.getChildren().add(kvRow(name, summary.length() > 0 ? summary.toString() : "—"));

      if (other.formatFields != null && !other.formatFields.isEmpty()) {
        Map<String, String> extras = new LinkedHashMap<>(other.formatFields);
        for (Map.Entry<String, String> entry : extras.entrySet()) {
          body.getChildren().add(kvRow("  " + entry.getKey(), nullToDash(entry.getValue())));
        }
      }
    }
    int otherCount = Math.max(0, node.getSampleCount() - (clicked != null ? 1 : 0));
    return titledPane("Other samples (" + otherCount + ")", body);
  }

  private static TitledPane titledPane(String title, Node content) {
    TitledPane pane = new TitledPane(title, content);
    pane.setExpanded(false);
    pane.setAnimated(false);
    pane.setCollapsible(true);
    pane.setMaxWidth(Double.MAX_VALUE);
    pane.getStyleClass().add("variant-info-expand");
    pane.setStyle(
        "-fx-text-fill: #d3d3d3;"
            + "-fx-font-size: 11;"
            + "-fx-background-color: transparent;");
    // Skin applies after attach — style the title bar once it exists.
    pane.skinProperty().addListener((obs, oldSkin, newSkin) -> {
      if (newSkin == null) {
        return;
      }
      Node titleNode = pane.lookup(".title");
      if (titleNode != null) {
        titleNode.setStyle(
            "-fx-background-color: #2a2a2a;"
                + "-fx-background-radius: 4;"
                + "-fx-padding: 4 6 4 6;");
      }
    });
    return pane;
  }

  private static HBox kvRow(String key, String value) {
    HBox row = new HBox(8);
    row.setAlignment(Pos.TOP_LEFT);
    Label keyLabel = new Label(key);
    keyLabel.setFont(AppFonts.getUIFont());
    keyLabel.setTextFill(Color.GRAY);
    keyLabel.setMinWidth(110);
    keyLabel.setMaxWidth(140);
    keyLabel.setWrapText(true);

    Label valueLabel = new Label(value);
    valueLabel.setFont(AppFonts.getMonoFont(11));
    valueLabel.setTextFill(Color.LIGHTGRAY);
    valueLabel.setWrapText(true);
    valueLabel.setTextAlignment(TextAlignment.LEFT);
    HBox.setHgrow(valueLabel, Priority.ALWAYS);
    valueLabel.setMaxWidth(Double.MAX_VALUE);

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.SOMETIMES);
    row.getChildren().addAll(keyLabel, valueLabel);
    return row;
  }

  private static String formatAllele(VariantNode node) {
    return nullToDash(node.ref) + " → " + nullToDash(node.alt);
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
    return switch (type) {
      case SNV -> "SNV";
      case INSERTION -> "Insertion";
      case DELETION -> "Deletion";
      case MNV -> "MNV";
      case COMPLEX -> "Complex";
      case SV_DELETION -> "SV deletion";
      case SV_INSERTION -> "SV insertion";
      case SV_DUPLICATION -> "SV duplication";
      case SV_INVERSION -> "SV inversion";
      case SV_TRANSLOCATION -> "Translocation";
      case SV_BREAKEND -> "Breakend";
      case SV_CNV -> "CNV";
      case SV_CNV_GAIN -> "CNV gain";
      case SV_CNV_LOSS -> "CNV loss";
      case SV_CNV_NEUTRAL -> "CNV copy-neutral";
      case LOH_AA -> "LOH AA";
      case LOH_BB -> "LOH BB";
    };
  }

  private static Color colorForType(VcfVariantType type) {
    return VariantTypeVisuals.color(type);
  }

  private static String toHex(Color color) {
    int r = (int) Math.round(color.getRed() * 255);
    int g = (int) Math.round(color.getGreen() * 255);
    int b = (int) Math.round(color.getBlue() * 255);
    return String.format("#%02x%02x%02x", r, g, b);
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
