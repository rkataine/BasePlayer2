package org.baseplayer.sample;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.baseplayer.alignment.AlignmentFile;

/**
 * Represents an individual (person/sample) in the sample tracks panel.
 * Contains a name/ID and a flat list of {@link Sample} data files
 * (BAM, BED, VCF, etc.) — all treated equally under this individual.
 * The individual's name is what is visible in the track header.
 */
public class SampleTrack implements Closeable {

  private String name;
  private String customName;
  private final List<Sample> samples = new ArrayList<>();

  /**
   * Create a track for an individual with an initial data file.
   * The track name defaults to the first sample's name.
   */
  public SampleTrack(Sample initialSample) {
    this.name = initialSample.getName();
    samples.add(initialSample);
  }

  /**
   * Create a track for an individual with an explicit name.
   */
  public SampleTrack(String name) {
    this.name = name;
  }

  // ── Name ──

  /** Get the display name (custom name if set, otherwise individual name). */
  public String getDisplayName() {
    return customName != null ? customName : name;
  }

  /** Set a custom display name for this individual. */
  public void setCustomName(String name) { this.customName = name; }

  /** Get the raw name. */
  public String getName() { return name; }

  /** Set the raw name. */
  public void setName(String name) { this.name = name; }

  // ── Samples (data files) ──

  /** Get all data files under this individual. */
  public List<Sample> getSamples() { return samples; }

  /** Add a data file to this individual. */
  public void addSample(Sample sample) {
    samples.add(sample);
  }

  /** Remove and close a data file by index. */
  public void removeSample(int index) {
    if (index < 0 || index >= samples.size()) return;
    try {
      samples.get(index).close();
    } catch (IOException e) {
      System.err.println("Error closing sample: " + e.getMessage());
    }
    samples.remove(index);
  }

  /** Get the number of data files. */
  public int getSampleCount() { return samples.size(); }

  // ── Convenience accessors ──

  /**
   * Find the first BAM/CRAM sample in this track, or null if none.
   * Useful for BAM-specific operations like scroll offset, coverage cache, etc.
   */
  public AlignmentFile getFirstBam() {
    for (Sample s : samples) {
      if (s.getBamFile() != null) return s.getBamFile();
    }
    return null;
  }

  /** Whether any sample in this track has methylation data. */
  public boolean hasMethylationData() {
    for (Sample s : samples) {
      if (s.isMethylationData()) return true;
    }
    return false;
  }

  /** Whether any sample in this track has haplotype data. */
  public boolean hasHaplotypeData() {
    for (Sample s : samples) {
      if (s.isHaplotypeData()) return true;
    }
    return false;
  }

  /** Whether any sample in this track is visible. */
  public boolean isVisible() {
    for (Sample s : samples) {
      if (s.visible) return true;
    }
    return !samples.isEmpty(); // default visible if has samples
  }

  @Override
  public void close() throws IOException {
    for (Sample sample : samples) {
      sample.close();
    }
    samples.clear();
  }
}
