package org.baseplayer.samples;

import javafx.scene.paint.Color;

/**
 * Named sample group with a sidebar accent color.
 * Membership lives on {@link SampleTrack#getGroupIds()} (multi-group allowed).
 */
public final class SampleGroup {

  public static final int NO_PARENT = -1;

  private final int id;
  private String name;
  private Color color;
  /** Parent group id, or {@link #NO_PARENT} for a top-level group. */
  private int parentGroupId = NO_PARENT;
  /** Display/name key of the parental track in this lineage; null if unset. */
  private String parentalTrackName;

  public SampleGroup(int id, String name, Color color) {
    this.id = id;
    this.name = name != null && !name.isBlank() ? name.trim() : ("Group " + id);
    this.color = color != null ? color : Color.web("#4db8ff");
  }

  public int getId() {
    return id;
  }

  /** Parent group id, or {@link #NO_PARENT} if this is a root group. */
  public int getParentGroupId() {
    return parentGroupId;
  }

  public void setParentGroupId(int parentGroupId) {
    this.parentGroupId = parentGroupId >= 0 ? parentGroupId : NO_PARENT;
  }

  public boolean isRoot() {
    return parentGroupId < 0;
  }

  public boolean isSubgroup() {
    return parentGroupId >= 0;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    if (name != null && !name.isBlank()) {
      this.name = name.trim();
    }
  }

  public Color getColor() {
    return color;
  }

  public void setColor(Color color) {
    if (color != null) {
      this.color = color;
    }
  }

  /** Parental track name/key for this lineage group, or {@code null}. */
  public String getParentalTrackName() {
    return parentalTrackName;
  }

  public void setParentalTrackName(String parentalTrackName) {
    if (parentalTrackName == null || parentalTrackName.isBlank()) {
      this.parentalTrackName = null;
    } else {
      this.parentalTrackName = parentalTrackName.trim();
    }
  }

  public void clearParentalTrackName() {
    this.parentalTrackName = null;
  }

  /** Whether {@code track} is the designated parental for this group. */
  public boolean isParental(SampleTrack track) {
    if (track == null || parentalTrackName == null || parentalTrackName.isBlank()) {
      return false;
    }
    String key = parentalTrackName;
    return key.equals(track.getName()) || key.equals(track.getDisplayName());
  }

  public String toCssHex() {
    int r = (int) Math.round(color.getRed() * 255);
    int g = (int) Math.round(color.getGreen() * 255);
    int b = (int) Math.round(color.getBlue() * 255);
    return String.format("#%02x%02x%02x", r, g, b);
  }

  @Override
  public String toString() {
    return name;
  }
}
