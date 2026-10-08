package org.baseplayer.samples;

import javafx.scene.paint.Color;

/**
 * Fixed sample tags for within-group comparison (trio inheritance, LOH markers).
 * A track may hold zero or more tags; comparison resolves members by tag inside each group.
 */
public enum SampleTag {
  MOTHER(
      "Mother",
      "M",
      Color.web("#e879a8"),
      "Maternal parent in a trio — use for de novo / inheritance filters."),
  FATHER(
      "Father",
      "F",
      Color.web("#5b9bd5"),
      "Paternal parent in a trio — use for de novo / inheritance filters."),
  CHILD(
      "Child",
      "C",
      Color.web("#7dcea0"),
      "Offspring / tumor / index sample in the group."),
  PARENTAL(
      "Parental",
      "P",
      Color.web("#f4d03f"),
      "Heterozygous reference for LOH — other group members become Child."),
  MARKER(
      "Marker",
      "K",
      Color.web("#af7ac5"),
      "Extra heterozygous marker sample for LOH comparison.");

  private final String displayName;
  private final String shortLabel;
  private final Color color;
  private final String description;

  SampleTag(String displayName, String shortLabel, Color color, String description) {
    this.displayName = displayName;
    this.shortLabel = shortLabel;
    this.color = color;
    this.description = description;
  }

  public String displayName() {
    return displayName;
  }

  /** One-letter sidebar / compact label. */
  public String shortLabel() {
    return shortLabel;
  }

  public Color color() {
    return color;
  }

  /** Short role explanation for dialogs and tooltips. */
  public String description() {
    return description;
  }

  public String toCssHex() {
    int r = (int) Math.round(color.getRed() * 255);
    int g = (int) Math.round(color.getGreen() * 255);
    int b = (int) Math.round(color.getBlue() * 255);
    return String.format("#%02x%02x%02x", r, g, b);
  }

  /**
   * {@code PARENTAL} and {@code MARKER} share LOH / heterozygous-reference semantics.
   */
  public boolean isMarkerLike() {
    return this == PARENTAL || this == MARKER;
  }

  public static SampleTag fromName(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String key = raw.trim().toUpperCase();
    for (SampleTag tag : values()) {
      if (tag.name().equals(key) || tag.displayName.equalsIgnoreCase(raw.trim())) {
        return tag;
      }
    }
    return null;
  }
}
