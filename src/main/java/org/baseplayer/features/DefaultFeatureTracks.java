package org.baseplayer.features;

import org.baseplayer.io.GnomadDataParser;

/**
 * Built-in feature tracks shown on a fresh untitled session.
 * Session load treats presence/absence in the project file as authoritative.
 */
public final class DefaultFeatureTracks {

  public static final String PHYLOP_UCSC_ID = "phyloP100way";

  private DefaultFeatureTracks() {}

  public static FeatureTrack createPhyloP() {
    FeatureTrack track = FeatureTrack.forUcscTrack(PHYLOP_UCSC_ID, "PhyloP Conservation");
    track.setVisible(false);
    return track;
  }

  public static FeatureTrack createGnomad() {
    GnomadDataParser gnomadParser = new GnomadDataParser();
    FeatureTrack track = new FeatureTrack(
        "gnomAD Variants", "gnomAD v4", gnomadParser::fetch);
    track.setCoordinateBase(1);
    track.setPopupContentBuilder(gnomadParser::buildPopupContent);
    track.setVisible(false);
    return track;
  }

  public static boolean isGnomad(Track track) {
    return track != null
        && track.getName() != null
        && track.getName().toLowerCase(java.util.Locale.ROOT).contains("gnomad");
  }
}
