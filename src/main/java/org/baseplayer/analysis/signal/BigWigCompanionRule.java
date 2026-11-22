package org.baseplayer.analysis.signal;

import org.baseplayer.features.BigWigTrack;
import org.baseplayer.features.SignalDisplayMode;

/**
 * How to pick a BigWig among several companions on one sample track.
 */
public enum BigWigCompanionRule {
  FIRST("First BigWig"),
  COPY_RATIO("Copy-ratio mode (e.g. seg.bw)"),
  BAF("BAF mode"),
  HISTOGRAM("Histogram mode"),
  NAME_CONTAINS("Name contains…");

  private final String label;

  BigWigCompanionRule(String label) {
    this.label = label;
  }

  @Override
  public String toString() {
    return label;
  }

  public boolean matches(BigWigTrack track, String nameContains) {
    if (track == null) {
      return false;
    }
    return switch (this) {
      case FIRST -> true;
      case COPY_RATIO -> track.getDisplayMode() == SignalDisplayMode.COPY_RATIO
          || BigWigTrack.looksLikeSegFile(track.getFilePath());
      case BAF -> track.getDisplayMode() == SignalDisplayMode.BAF
          || BigWigTrack.looksLikeBafFile(track.getFilePath());
      case HISTOGRAM -> track.getDisplayMode() == SignalDisplayMode.HISTOGRAM;
      case NAME_CONTAINS -> {
        if (nameContains == null || nameContains.isBlank()) {
          yield false;
        }
        String path = track.getFilePath() != null
            ? track.getFilePath().getFileName().toString().toLowerCase()
            : track.getName().toLowerCase();
        yield path.contains(nameContains.trim().toLowerCase());
      }
    };
  }
}
