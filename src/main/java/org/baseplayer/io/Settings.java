package org.baseplayer.io;

import java.util.prefs.Preferences;

/**
 * Application-wide settings with persistence via java.util.prefs.
 * Singleton — access via Settings.get().
 *
 * All numeric values have sensible defaults matching the original hardcoded constants.
 * Changes are written to prefs immediately and take effect on next draw/fetch.
 */
public class Settings {

  private static final Preferences prefs = Preferences.userNodeForPackage(Settings.class);
  private static final Settings INSTANCE = new Settings();

  // ── Keys ──────────────────────────────────────────────────────────────

  private static final String KEY_MAX_READ_VIEW_LENGTH    = "maxReadViewLength";
  private static final String KEY_MAX_COVERAGE_VIEW_LENGTH = "maxCoverageViewLength";
  private static final String KEY_SAMPLED_COVERAGE_POINTS = "sampledCoveragePoints";
  private static final String KEY_COVERAGE_FRACTION       = "coverageFraction";
  private static final String KEY_READ_GAP                = "readGap";
  private static final String KEY_MIN_READ_HEIGHT         = "minReadHeight";
  private static final String KEY_SMOOTH_SMALL_FILES      = "smoothSmallFiles";

  // ── Defaults (matching original hardcoded values) ─────────────────────

  public static final int    DEF_MAX_READ_VIEW_LENGTH     = 60_000;
  public static final int    DEF_MAX_COVERAGE_VIEW_LENGTH = 2_000_000;
  public static final int    DEF_SAMPLED_COVERAGE_POINTS  = 20;
  public static final double DEF_COVERAGE_FRACTION        = 0.30;
  public static final double DEF_READ_GAP                 = 2.5;
  public static final double DEF_MIN_READ_HEIGHT          = 3.0;
  public static final boolean DEF_SMOOTH_SMALL_FILES      = false;

  // ── Cached values (read from prefs once, written on set) ──────────────

  private int    maxReadViewLength;
  private int    maxCoverageViewLength;
  private int    sampledCoveragePoints;
  private double coverageFraction;
  private double readGap;
  private double minReadHeight;
  private boolean smoothSmallFiles;

  private Settings() {
    load();
  }

  public static Settings get() { return INSTANCE; }

  /** Reload all values from persistent storage. */
  public void load() {
    maxReadViewLength     = prefs.getInt(KEY_MAX_READ_VIEW_LENGTH, DEF_MAX_READ_VIEW_LENGTH);
    maxCoverageViewLength = prefs.getInt(KEY_MAX_COVERAGE_VIEW_LENGTH, DEF_MAX_COVERAGE_VIEW_LENGTH);
    sampledCoveragePoints = prefs.getInt(KEY_SAMPLED_COVERAGE_POINTS, DEF_SAMPLED_COVERAGE_POINTS);
    coverageFraction      = prefs.getDouble(KEY_COVERAGE_FRACTION, DEF_COVERAGE_FRACTION);
    readGap               = prefs.getDouble(KEY_READ_GAP, DEF_READ_GAP);
    minReadHeight         = prefs.getDouble(KEY_MIN_READ_HEIGHT, DEF_MIN_READ_HEIGHT);
    smoothSmallFiles      = prefs.getBoolean(KEY_SMOOTH_SMALL_FILES, DEF_SMOOTH_SMALL_FILES);
  }

  // ── Getters ───────────────────────────────────────────────────────────

  /** Max view length (bp) for showing individual reads (below this: reads view). */
  public int getMaxReadViewLength() { return maxReadViewLength; }

  /** Max view length (bp) for coverage-only mode (above this: sampled coverage). */
  public int getMaxCoverageViewLength() { return maxCoverageViewLength; }

  /** Number of sample points for chromosome-level sampled coverage. */
  public int getSampledCoveragePoints() { return sampledCoveragePoints; }

  /** Fraction of track height used for coverage area (0.0–1.0). */
  public double getCoverageFraction() { return coverageFraction; }

  /** Vertical gap between read rows in pixels. */
  public double getReadGap() { return readGap; }

  /** Minimum read height in pixels. */
  public double getMinReadHeight() { return minReadHeight; }

  /** Whether to apply smoothing to small-file sampled coverage. */
  public boolean isSmoothSmallFiles() { return smoothSmallFiles; }

  // ── Setters (persist immediately) ─────────────────────────────────────

  public void setMaxReadViewLength(int bp)            { this.maxReadViewLength = bp; prefs.putInt(KEY_MAX_READ_VIEW_LENGTH, bp); }
  public void setMaxCoverageViewLength(int bp)        { this.maxCoverageViewLength = bp; prefs.putInt(KEY_MAX_COVERAGE_VIEW_LENGTH, bp); }
  public void setSampledCoveragePoints(int n)         { this.sampledCoveragePoints = n; prefs.putInt(KEY_SAMPLED_COVERAGE_POINTS, n); }
  public void setCoverageFraction(double f)           { this.coverageFraction = f; prefs.putDouble(KEY_COVERAGE_FRACTION, f); }
  public void setReadGap(double px)                   { this.readGap = px; prefs.putDouble(KEY_READ_GAP, px); }
  public void setMinReadHeight(double px)             { this.minReadHeight = px; prefs.putDouble(KEY_MIN_READ_HEIGHT, px); }
  public void setSmoothSmallFiles(boolean smooth)     { this.smoothSmallFiles = smooth; prefs.putBoolean(KEY_SMOOTH_SMALL_FILES, smooth); }

  /** Reset all settings to defaults. */
  public void resetDefaults() {
    setMaxReadViewLength(DEF_MAX_READ_VIEW_LENGTH);
    setMaxCoverageViewLength(DEF_MAX_COVERAGE_VIEW_LENGTH);
    setSampledCoveragePoints(DEF_SAMPLED_COVERAGE_POINTS);
    setCoverageFraction(DEF_COVERAGE_FRACTION);
    setReadGap(DEF_READ_GAP);
    setMinReadHeight(DEF_MIN_READ_HEIGHT);
    setSmoothSmallFiles(DEF_SMOOTH_SMALL_FILES);
  }
}
