package org.baseplayer.draw;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.alignment.BAMRecord;
import org.baseplayer.ui.InfoPopup;
import org.baseplayer.ui.PopupContent;
import org.baseplayer.ui.PopupContent.Badge;
import org.baseplayer.utils.AppFonts;

import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.stage.Window;

/**
 * Popup displaying detailed BAM record information when user clicks on a read.
 * Delegates all rendering to {@link InfoPopup} via {@link PopupContent}.
 */
public class ReadInfoPopup {

  /** Callback to navigate to a mate read's location (chromosome, 0-based position). */
  @FunctionalInterface
  public interface MateNavigator {
    void goToMate(String chromosome, int position);
  }

  private final InfoPopup infoPopup = new InfoPopup(420, 450, true);

  public void show(BAMRecord read, String chromosome, String mateChromName,
                   Window owner, double x, double y, MateNavigator mateNavigator) {
    PopupContent content = buildContent(read, chromosome, mateChromName, mateNavigator);
    infoPopup.show(content, owner, x, y);
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
        Label goToMate = new Label("\u25B6 Go to mate");
        goToMate.setFont(AppFonts.getUIFont());
        goToMate.setTextFill(Color.web("#66bbff"));
        goToMate.setStyle(
          "-fx-cursor: hand; -fx-padding: 4 10; -fx-background-color: rgba(60,80,120,0.5); -fx-background-radius: 4;"
        );
        goToMate.setOnMouseEntered(e -> goToMate.setStyle(
          "-fx-cursor: hand; -fx-padding: 4 10; -fx-background-color: rgba(80,110,160,0.7); -fx-background-radius: 4;"
        ));
        goToMate.setOnMouseExited(e -> goToMate.setStyle(
          "-fx-cursor: hand; -fx-padding: 4 10; -fx-background-color: rgba(60,80,120,0.5); -fx-background-radius: 4;"
        ));
        goToMate.setOnMouseClicked(e -> {
          infoPopup.hide();
          mateNavigator.goToMate(targetChr, read.matePos);
        });
        c.node(goToMate);
      }
    }

    // Tags
    boolean hasTags = read.hasMethylTag || read.haplotype > 0 || read.readGroup != null;
    if (hasTags) {
      c.separator();
      c.section("Tags");
      if (read.haplotype > 0) c.row("HP", String.valueOf(read.haplotype));
      if (read.phaseSet > 0) c.row("PS", String.valueOf(read.phaseSet));
      if (read.readGroup != null) c.row("RG", read.readGroup);
      if (read.hasMethylTag) c.row("Methylation", "Yes", Color.web("#88cccc"));
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
      Label mmLabel = new Label(sb.toString());
      mmLabel.setFont(AppFonts.getMonoFont(10));
      mmLabel.setTextFill(Color.web("#cc8888"));
      mmLabel.setWrapText(true);
      c.node(mmLabel);
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
