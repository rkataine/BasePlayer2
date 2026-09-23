package org.baseplayer.variant.draw;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.annotation.CosmicCensusEntry;
import org.baseplayer.components.InfoPopup;
import org.baseplayer.components.PopupContent;
import org.baseplayer.components.PopupContent.Badge;
import org.baseplayer.io.Settings;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.VariantAnnotation;

import javafx.scene.paint.Color;
import javafx.stage.Window;

public class VariantInfoPopup extends InfoPopup {

  private static final double TOP_RIGHT_MARGIN_X = 16;
  private static final double TOP_RIGHT_MARGIN_Y = 6;
  private static final double POPUP_ESTIMATED_WIDTH = 460;

  public VariantInfoPopup() {
    super(440, 560, true);
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
    c.badges(badges);
    c.separator();

    c.section("Variant");
    c.row("Chromosome", ChromosomeNames.forDisplay(chromosome));
    c.row("Position", formatPosition(node));
    c.row("REF", nullToDash(node.ref));
    c.row("ALT", nullToDash(node.alt));
    c.row("Type", typeLabel(node.type));
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

    if (call != null) {
      c.separator();
      c.section("Sample call");
      String sampleName = sampleName(call);
      if (sampleName != null) {
        c.row("Sample", sampleName);
      }
      if (call.gt != null && !call.gt.isBlank()) {
        c.row("Genotype", call.gt);
        if (VariantNode.isHetGt(call.gt)) {
          c.row("Zygosity", "Heterozygous");
        } else if (isHomAlt(node, call)) {
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
      if (ann.geneName() != null && !ann.geneName().isBlank()) {
        c.row("Gene", ann.geneName());
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

    return c;
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

  private static boolean isHomAlt(VariantNode node, VariantNode.SampleCall call) {
    if (call.gt == null || node.alt == null) {
      return false;
    }
    String[] a = call.gt.split("[/|]");
    return a.length >= 2 && a[0].equals(node.alt) && a[1].equals(node.alt);
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
    };
  }

  private static Color colorForType(VcfVariantType type) {
    if (type == null) {
      return Color.web("#B8E986");
    }
    return switch (type) {
      case SNV -> Color.web("#4A90E2");
      case INSERTION -> Color.web("#7ED321");
      case DELETION -> Color.rgb(200, 100, 100);
      case MNV -> Color.web("#BD10E0");
      case COMPLEX -> Color.web("#B8E986");
      case SV_DELETION -> Color.rgb(200, 100, 100);
      case SV_INVERSION -> Color.web("#4488ff");
      case SV_DUPLICATION -> Color.web("#c0c0d0");
      case SV_INSERTION -> Color.web("#33cc66");
      case SV_TRANSLOCATION -> Color.web("#ffdd00");
      case SV_BREAKEND -> Color.web("#c0c0c0");
    };
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
