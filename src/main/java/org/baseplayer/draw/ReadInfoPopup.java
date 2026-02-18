package org.baseplayer.draw;

import org.baseplayer.alignment.BAMRecord;
import org.baseplayer.utils.AppFonts;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.stage.Window;

/**
 * Popup displaying detailed BAM record information when user clicks on a read.
 */
public class ReadInfoPopup {

  /** Callback to navigate to a mate read's location (chromosome, 0-based position). */
  @FunctionalInterface
  public interface MateNavigator {
    void goToMate(String chromosome, int position);
  }

  private static final double MAX_WIDTH = 420;
  private static final double MAX_HEIGHT = 450;

  private final Popup popup;
  private final VBox content;

  public ReadInfoPopup() {
    popup = new Popup();
    popup.setAutoHide(true);
    popup.setHideOnEscape(true);

    content = new VBox(5);
    content.setPadding(new Insets(12));
    content.setMaxWidth(MAX_WIDTH);
    content.setStyle(
      "-fx-background-color: rgba(30, 30, 30, 0.98);" +
      "-fx-background-radius: 8;" +
      "-fx-border-color: #555555;" +
      "-fx-border-radius: 8;" +
      "-fx-border-width: 1;"
    );

    ScrollPane scrollPane = new ScrollPane(content);
    scrollPane.setMaxWidth(MAX_WIDTH + 20);
    scrollPane.setMaxHeight(MAX_HEIGHT);
    scrollPane.setFitToWidth(true);
    scrollPane.setStyle(
      "-fx-background-color: transparent;" +
      "-fx-background: transparent;" +
      "-fx-border-color: transparent;"
    );

    popup.getContent().add(scrollPane);
  }

  public void show(BAMRecord read, String chromosome, String mateChromName, Window owner, double x, double y, MateNavigator mateNavigator) {
    content.getChildren().clear();
    buildContent(read, chromosome, mateChromName, mateNavigator);
    popup.show(owner, x, y);
  }

  public void hide() {
    popup.hide();
  }

  public boolean isShowing() {
    return popup.isShowing();
  }

  private void buildContent(BAMRecord read, String chromosome, String mateChromName, MateNavigator mateNavigator) {
    // Header: read name
    if (read.readName != null) {
      Label nameLabel = new Label(read.readName);
      nameLabel.setFont(AppFonts.getBoldFont(13));
      nameLabel.setTextFill(Color.web("#88ccff"));
      nameLabel.setWrapText(true);
      content.getChildren().add(nameLabel);
    }

    // Flags badge row
    HBox flagBadges = new HBox(4);
    flagBadges.setAlignment(Pos.CENTER_LEFT);
    if (read.isReverseStrand()) addBadge(flagBadges, "Reverse", "#cc7777");
    else addBadge(flagBadges, "Forward", "#7799cc");
    if (read.isPaired()) addBadge(flagBadges, "Paired", "#888888");
    if (read.isProperPair()) addBadge(flagBadges, "Proper pair", "#669966");
    if (read.isSecondary()) addBadge(flagBadges, "Secondary", "#cc9944");
    if (read.isSupplementary()) addBadge(flagBadges, "Supplementary", "#cc9944");
    if (read.isDuplicate()) addBadge(flagBadges, "Duplicate", "#cc4444");
    if (read.isUnmapped()) addBadge(flagBadges, "Unmapped", "#cc4444");
    if (!flagBadges.getChildren().isEmpty()) {
      content.getChildren().add(flagBadges);
    }

    content.getChildren().add(new Separator());

    // Position
    addSectionTitle("Alignment");
    addInfoRow("Chromosome", "chr" + chromosome);
    addInfoRow("Position", String.format("%,d - %,d", read.pos + 1, read.end));
    addInfoRow("Length", String.format("%,d bp", read.end - read.pos));
    addInfoRow("Read length", String.format("%,d bp", read.readLength));
    addInfoRow("MAPQ", String.valueOf(read.mapq), mapqColor(read.mapq));
    addInfoRow("Flag", String.format("%d (0x%X)", read.flag, read.flag));

    // CIGAR
    if (read.cigarOps != null && read.cigarOps.length > 0) {
      addInfoRow("CIGAR", formatCigar(read.cigarOps));
    }

    // Mate info
    if (read.isPaired() && read.matePos >= 0) {
      content.getChildren().add(new Separator());
      addSectionTitle("Mate");
      String mateChr;
      if (read.mateRefID != read.refID && read.mateRefID >= 0) {
        mateChr = mateChromName;
        addInfoRow("Mate chr", mateChr != null ? mateChr : "refID=" + read.mateRefID, Color.web("#cc8888"));
      } else {
        mateChr = chromosome;
        addInfoRow("Mate chr", "chr" + chromosome);
      }
      addInfoRow("Mate pos", String.format("%,d", read.matePos + 1));
      addInfoRow("Insert size", String.format("%,d", read.insertSize));

      int dt = read.getDiscordantType();
      if (dt > 0) {
        String dtLabel = switch (dt) {
          case 1 -> "Inter-chromosomal";
          case 2 -> "Deletion (large insert)";
          case 3 -> "Inversion";
          case 4 -> "Duplication";
          default -> "Unknown";
        };
        addInfoRow("Discordant", dtLabel, Color.web("#ffaa66"));
      }

      // "Go to mate" button
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
          popup.hide();
          mateNavigator.goToMate(targetChr, read.matePos);
        });
        content.getChildren().add(goToMate);
      }
    }

    // Tags
    boolean hasTags = read.hasMethylTag || read.haplotype > 0 || read.readGroup != null;
    if (hasTags) {
      content.getChildren().add(new Separator());
      addSectionTitle("Tags");
      if (read.haplotype > 0) {
        addInfoRow("HP", String.valueOf(read.haplotype));
      }
      if (read.phaseSet > 0) {
        addInfoRow("PS", String.valueOf(read.phaseSet));
      }
      if (read.readGroup != null) {
        addInfoRow("RG", read.readGroup);
      }
      if (read.hasMethylTag) {
        addInfoRow("Methylation", "Yes", Color.web("#88cccc"));
      }
    }

    // Mismatches summary
    if (read.mismatches != null && read.mismatches.length > 0) {
      content.getChildren().add(new Separator());
      int mmCount = read.mismatches.length / 3;
      addSectionTitle("Mismatches (" + mmCount + ")");
      
      // Show first few mismatches (max 10)
      int showCount = Math.min(mmCount, 10);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < showCount * 3; i += 3) {
        int refPos = read.mismatches[i];
        char readBase = (char) read.mismatches[i + 1];
        char refBase = (read.mismatches[i + 2] > 0) ? (char) read.mismatches[i + 2] : '?';
        if (sb.length() > 0) sb.append(", ");
        sb.append(String.format("%,d: %c>%c", refPos, refBase, readBase));
      }
      if (mmCount > showCount) {
        sb.append(String.format(" ... +%d more", mmCount - showCount));
      }
      Label mmLabel = new Label(sb.toString());
      mmLabel.setFont(AppFonts.getMonoFont(10));
      mmLabel.setTextFill(Color.web("#cc8888"));
      mmLabel.setWrapText(true);
      content.getChildren().add(mmLabel);
    }
  }

  private void addSectionTitle(String title) {
    Label label = new Label(title);
    label.setFont(AppFonts.getUIFont());
    label.setTextFill(Color.GRAY);
    label.setStyle("-fx-padding: 2 0 0 0;");
    content.getChildren().add(label);
  }

  private void addInfoRow(String label, String value) {
    addInfoRow(label, value, Color.LIGHTGRAY);
  }

  private void addInfoRow(String label, String value, Color valueColor) {
    HBox row = new HBox(8);
    row.setAlignment(Pos.CENTER_LEFT);

    Label labelNode = new Label(label + ":");
    labelNode.setFont(AppFonts.getUIFont());
    labelNode.setTextFill(Color.GRAY);
    labelNode.setMinWidth(85);

    Label valueNode = new Label(value);
    valueNode.setFont(AppFonts.getMonoFont(11));
    valueNode.setTextFill(valueColor);
    valueNode.setWrapText(true);
    HBox.setHgrow(valueNode, Priority.ALWAYS);

    row.getChildren().addAll(labelNode, valueNode);
    content.getChildren().add(row);
  }

  private void addBadge(HBox container, String text, String bgColor) {
    Label badge = new Label(text);
    badge.setFont(AppFonts.getMonoFont(9));
    badge.setTextFill(Color.WHITE);
    badge.setStyle(String.format(
        "-fx-background-color: %s; -fx-padding: 1 5; -fx-background-radius: 3;", bgColor));
    container.getChildren().add(badge);
  }

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
    // Truncate if very long
    if (sb.length() > 80) {
      return sb.substring(0, 77) + "...";
    }
    return sb.toString();
  }
}
