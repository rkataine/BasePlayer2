package org.baseplayer.samples;

import javafx.scene.paint.Color;

/**
 * Named sample group with a sidebar accent color.
 * Membership lives on {@link SampleTrack#getGroupIds()} (multi-group allowed).
 * Groups are flat (no subgroups); within-group roles use {@link SampleTag}.
 * When {@link #isIncludedInProject()} is false, the group (and tracks that belong
 * only to excluded groups) are omitted from drawing and analysis.
 */
public final class SampleGroup {

  private final int id;
  private String name;
  private Color color;
  private boolean includedInProject = true;

  public SampleGroup(int id, String name, Color color) {
    this.id = id;
    this.name = name != null && !name.isBlank() ? name.trim() : ("Group " + id);
    this.color = color != null ? color : Color.web("#4db8ff");
  }

  public int getId() {
    return id;
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

  /** When false, the group is kept in Sample Groups UI but left out of the project. */
  public boolean isIncludedInProject() {
    return includedInProject;
  }

  public void setIncludedInProject(boolean includedInProject) {
    this.includedInProject = includedInProject;
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
