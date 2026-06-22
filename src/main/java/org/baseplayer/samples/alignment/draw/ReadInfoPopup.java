package org.baseplayer.samples.alignment.draw;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.components.InfoPopup;
import org.baseplayer.components.PopupContent;
import org.baseplayer.components.PopupContent.Badge;
import org.baseplayer.io.Settings;
import org.baseplayer.samples.alignment.BAMRecord;
import org.baseplayer.utils.AppFonts;

import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;
import javafx.stage.Window;

/**
 * Popup displaying detailed BAM record information when user clicks on a read.
 * Delegates all rendering to {@link InfoPopup} via {@link PopupContent}.
 */
public class ReadInfoPopup {

  private static final double TOP_RIGHT_MARGIN_X = 16;
  private static final double TOP_RIGHT_MARGIN_Y = 6;
  // InfoPopup max width is 440 px; include padding/shadow for safe right alignment.
  private static final double POPUP_ESTIMATED_WIDTH = 460;

  /** Callback to navigate to a mate read's location (chromosome, 0-based position). */
  @FunctionalInterface
  public interface MateNavigator {
    void goToMate(String chromosome, int position);
  }

  private final InfoPopup infoPopup = new InfoPopup(440, 560, true);

  public void show(BAMRecord read, String chromosome, String mateChromName,
                   Window owner, double x, double y, MateNavigator mateNavigator) {
    PopupContent content = buildContent(read, chromosome, mateChromName, mateNavigator);
    Settings.ReadInfoPopupPosition position = Settings.get().getReadInfoPopupPosition();
    if (position == Settings.ReadInfoPopupPosition.TOP_RIGHT) {
      if (owner != null) {
        double anchoredX = owner.getX() + owner.getWidth() - POPUP_ESTIMATED_WIDTH - TOP_RIGHT_MARGIN_X;
        double anchoredY = owner.getY() + TOP_RIGHT_MARGIN_Y;
        anchoredX = Math.max(owner.getX() + TOP_RIGHT_MARGIN_X, anchoredX);
        infoPopup.show(content, owner, anchoredX, anchoredY);
      } else {
        double anchoredX = x - POPUP_ESTIMATED_WIDTH - TOP_RIGHT_MARGIN_X;
        double anchoredY = y + TOP_RIGHT_MARGIN_Y;
        infoPopup.show(content, owner, anchoredX, anchoredY);
      }
    } else {
      infoPopup.show(content, owner, x, y);
    }
  }

  public void hide() { infoPopup.hide(); }

  public boolean isShowing() { return infoPopup.isShowing(); }

  // ── Content builder ────────────────────────────────────────────────────────

  private PopupContent buildContent(BAMRecord read, String chromosome,
                                    String mateChromName, MateNavigator mateNavigator) {
    PopupContent c = new PopupContent();

    // Header: read name
    if (read.readName != null) {
      c.title(read.readName, Color.web("#88ccff"));
    }

    // Flag badges
    List<Badge> flags = new ArrayList<>();
    if (read.isReverseStrand()) flags.add(new Badge("Reverse", "#cc7777"));
    else flags.add(new Badge("Forward", "#7799cc"));
    if (read.isPaired()) flags.add(new Badge("Paired", "#888888"));
    if (read.isProperPair()) flags.add(new Badge("Proper pair", "#669966"));
    if (read.isSecondary()) flags.add(new Badge("Secondary", "#cc9944"));
    if (read.isSupplementary()) flags.add(new Badge("Supplementary", "#cc9944"));
    if (read.isDuplicate()) flags.add(new Badge("Duplicate", "#cc4444"));
    if (read.isUnmapped()) flags.add(new Badge("Unmapped", "#cc4444"));
    if (!flags.isEmpty()) c.badges(flags);

    c.separator();

    // Alignment section
    c.section("Alignment");
    c.row("Chromosome", "chr" + chromosome);
    c.row("Position", String.format("%,d - %,d", read.pos + 1, read.end));
    c.row("Length", String.format("%,d bp", read.end - read.pos));
    c.row("Read length", String.format("%,d bp", read.readLength));
    c.row("MAPQ", String.valueOf(read.mapq), mapqColor(read.mapq));
    c.row("Flag", String.format("%d (0x%X)", read.flag, read.flag));

    if (read.cigarOps != null && read.cigarOps.length > 0) {
      c.row("CIGAR", formatCigar(read.cigarOps));
    }

    // Mate info
    if (read.isPaired() && read.matePos >= 0) {
      c.separator();
      c.section("Mate");
      String mateChr;
      if (read.mateRefID != read.refID && read.mateRefID >= 0) {
        mateChr = mateChromName;
        c.row("Mate chr", mateChr != null ? mateChr : "refID=" + read.mateRefID, Color.web("#cc8888"));
      } else {
        mateChr = chromosome;
        c.row("Mate chr", "chr" + chromosome);
      }
      c.row("Mate pos", String.format("%,d", read.matePos + 1));
      c.row("Insert size", String.format("%,d", read.insertSize));

      int dt = read.getDiscordantType();
      if (dt > 0) {
        String dtLabel = switch (dt) {
          case 1 -> "Inter-chromosomal";
          case 2 -> "Deletion (large insert)";
          case 3 -> "Inversion";
          case 4 -> "Duplication";
          default -> "Unknown";
        };
        c.row("Discordant", dtLabel, Color.web("#ffaa66"));
      }

      // "Go to mate" button (custom node)
      if (mateNavigator != null) {
        final String targetChr = mateChr;
        Button goToMate = new Button("\u25B6 Go to mate");
        goToMate.setFont(AppFonts.getUIFont());
        goToMate.setStyle(
          "-fx-background-color: rgba(60,80,120,0.5);" +
          "-fx-text-fill: #66bbff;"                    +
          "-fx-background-radius: 4;"                  +
          "-fx-padding: 4 10;"
        );
        goToMate.setOnMouseEntered(e -> goToMate.setStyle(
          "-fx-background-color: rgba(80,110,160,0.7);" +
          "-fx-text-fill: #66bbff;"                     +
          "-fx-background-radius: 4;"                   +
          "-fx-padding: 4 10;"
        ));
        goToMate.setOnMouseExited(e -> goToMate.setStyle(
          "-fx-background-color: rgba(60,80,120,0.5);" +
          "-fx-text-fill: #66bbff;"                    +
          "-fx-background-radius: 4;"                  +
          "-fx-padding: 4 10;"
        ));
        goToMate.setOnAction(e -> {
          infoPopup.hide();
          mateNavigator.goToMate(targetChr, read.matePos);
        });
        c.node(goToMate);
      }
    }

    // Split alignments (SA tag) — text rows (copyable) + clickable structure bar
    if (read.saTag != null && !read.saTag.isBlank()) {
      c.separator();
      String[] entries = read.saTag.split(";");
      c.section("Split Alignments (SA)  \u00D7" + entries.length);
      for (String entry : entries) {
        if (entry.isBlank()) continue;
        String[] parts = entry.split(",", -1);
        // SA format: rname,pos(1-based),strand,CIGAR,mapQ,NM
        String rname  = parts.length > 0 ? parts[0] : "?";
        String posStr = parts.length > 1 ? parts[1] : "?";
        String strand = parts.length > 2 ? parts[2] : "?";
        String cigar  = parts.length > 3 ? parts[3] : "?";
        String mapq   = parts.length > 4 ? parts[4] : "?";
        String nm     = parts.length > 5 ? parts[5] : null;

        String label = rname + ":" + posStr + " (" + strand + ")";
        String detail = cigar + "  MAPQ=" + mapq + (nm != null ? "  NM=" + nm : "");
        c.row(label, detail);
      }

      // Clickable read-structure diagram: click a segment to jump to its location.
      // Wrap navigator so the popup closes after navigation.
      c.node(new ReadStructureBar(read, chromosome,
          (chr, pos) -> {
            infoPopup.hide();
            if (mateNavigator != null) mateNavigator.goToMate(chr, pos);
          },
          410));
    }

    // Mismatches summary
    if (read.mismatches != null && read.mismatches.length > 0) {
      c.separator();
      int mmCount = read.mismatches.length / 3;
      c.section("Mismatches (" + mmCount + ")");

      int showCount = Math.min(mmCount, 10);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < showCount * 3; i += 3) {
        int refPos = read.mismatches[i];
        char readBase = (char) read.mismatches[i + 1];
        char refBase = (read.mismatches[i + 2] > 0) ? (char) read.mismatches[i + 2] : '?';
        if (!sb.isEmpty()) sb.append(", ");
        sb.append(String.format("%,d: %c>%c", refPos, refBase, readBase));
      }
      if (mmCount > showCount) {
        sb.append(String.format(" ... +%d more", mmCount - showCount));
      }
      TextField mmField = new TextField(sb.toString());
      mmField.setEditable(false);
      mmField.setFocusTraversable(false);
      mmField.setFont(AppFonts.getMonoFont(10));
      mmField.setStyle(
          "-fx-background-color: transparent;" +
          "-fx-border-color: transparent;"     +
          "-fx-background-insets: 0;"          +
          "-fx-background-radius: 0;"          +
          "-fx-text-fill: #cc8888;"            +
          "-fx-padding: 0;"                    +
          "-fx-focus-color: transparent;"      +
          "-fx-faint-focus-color: transparent;");
      mmField.setMaxWidth(Double.MAX_VALUE);
      c.node(mmField);
    }

    // Tags — show all known tags plus any extras collected during parsing
    boolean hasTags = read.hasMethylTag || read.haplotype > 0 || read.readGroup != null
        || read.extraTagsLine != null || (read.baseModifications != null && !read.baseModifications.isEmpty());
    if (hasTags) {
      c.separator();
      c.section("Tags");
      if (read.haplotype > 0) c.row("HP", String.valueOf(read.haplotype));
      if (read.phaseSet > 0)  c.row("PS", String.valueOf(read.phaseSet));
      if (read.readGroup != null) c.row("RG", read.readGroup);
      
      // Show detailed base modification information
      if (read.baseModifications != null && !read.baseModifications.isEmpty()) {
        for (org.baseplayer.samples.alignment.BaseModification mod : read.baseModifications) {
          String modName = mod.getModificationName();
          
          // Count modifications and calculate average probability
          int modCount = mod.deltaPositions != null ? mod.deltaPositions.length : 0;
          double totalProb = 0.0;
          int countWithProb = 0;
          
          if (modCount > 0) {
            for (int i = 0; i < modCount; i++) {
              double prob = mod.getProbability(i);
              if (prob >= 0) {
                countWithProb++;
                totalProb += prob;
              }
            }
          }
          
          if (modCount > 0) {
            String label = String.format("%c%s%s (%s)", 
                mod.baseType, mod.strand, mod.modCode, modName);
            
            if (countWithProb > 0) {
              double avgProb = totalProb / countWithProb;
              String value = String.format("%d site%s, avg %.2f", 
                  modCount, modCount == 1 ? "" : "s", avgProb);
              c.row(label, value, Color.web("#88cccc"));
            } else {
              // No probabilities available
              String value = String.format("%d site%s", 
                  modCount, modCount == 1 ? "" : "s");
              c.row(label, value, Color.web("#88cccc"));
            }
          }
        }
      } else if (read.hasMethylTag) {
        // Fallback for old methylation tag without MM/ML
        c.row("Methylation", "Yes", Color.web("#88cccc"));
      }
      
      if (read.extraTagsLine != null) {
        for (String entry : read.extraTagsLine.split(";")) {
          int eq = entry.indexOf('=');
          if (eq > 0) c.row(entry.substring(0, eq), entry.substring(eq + 1));
        }
      }
    }

    return c;
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private Color mapqColor(int mapq) {
    if (mapq >= 60) return Color.web("#88cc88");
    if (mapq >= 30) return Color.web("#cccc88");
    if (mapq >= 10) return Color.web("#ccaa66");
    return Color.web("#cc6666");
  }

  private static final char[] CIGAR_CHARS = {'M', 'I', 'D', 'N', 'S', 'H', 'P', '=', 'X'};

  private String formatCigar(int[] cigarOps) {
    StringBuilder sb = new StringBuilder();
    for (int cigarOp : cigarOps) {
      int op = cigarOp & 0xF;
      int len = cigarOp >>> 4;
      sb.append(len);
      sb.append(op < CIGAR_CHARS.length ? CIGAR_CHARS[op] : '?');
    }
    if (sb.length() > 80) return sb.substring(0, 77) + "...";
    return sb.toString();
  }
}
