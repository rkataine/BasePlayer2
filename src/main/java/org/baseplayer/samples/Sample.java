package org.baseplayer.samples;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

import org.baseplayer.samples.alignment.AlignmentFile;
import org.baseplayer.features.BedTrack;

/**
 * Represents a single loaded data file (BAM, BED, VCF, etc.) under an individual.
 * This is the generic wrapper — data-type-specific logic lives in
 * {@link AlignmentFile} (BAM/CRAM) or {@link BedTrack} (BED).
 * <p>
 * All data files under a {@link SampleTrack} are treated equally — there is
 * no "primary" vs "overlay" distinction.
 */
public class Sample implements Closeable {

  /** Data type of this file. */
  public enum DataType {
    BAM,      // BAM/CRAM alignment files
    BED,      // BED annotation files
    VCF       // VCF variant files (for future)
  }

  private final String name;
  private final Path path;
  private final DataType dataType;

  /** Visibility toggle for this individual file. */
  public boolean visible = true;

  /** When true, renders with transparent/overlay colors. */
  public boolean overlay = false;

  /** BAM/CRAM file handle (non-null for BAM type). */
  private final AlignmentFile bamFile;

  /** BED track data (non-null for BED type). */
  private final BedTrack bedTrack;

  /**
   * Create a Sample from a BAM/CRAM alignment file.
   */
  public Sample(Path filePath) throws IOException {
    this.path = filePath;
    this.dataType = DataType.BAM;
    this.bedTrack = null;
    this.bamFile = new AlignmentFile(filePath);
    this.name = bamFile.getName();
  }

  /**
   * Create a Sample from a BED annotation file.
   */
  public Sample(Path filePath, BedTrack bedTrack) {
    this.path = filePath;
    this.dataType = DataType.BED;
    this.bamFile = null;
    this.bedTrack = bedTrack;
    this.name = bedTrack.getName();
    this.overlay = true; // BED tracks are transparent by default
  }

  // ── Generic accessors ──

  public String getName() { return name; }
  public Path getPath() { return path; }
  public DataType getDataType() { return dataType; }

  // ── BAM delegation ──

  /** Get the underlying AlignmentFile (BAM/CRAM). Null for non-BAM types. */
  public AlignmentFile getBamFile() { return bamFile; }

  /** Whether this file contains methylation data. */
  public boolean isMethylationData() {
    return bamFile != null && bamFile.isMethylationData();
  }

  /** Whether this file contains haplotype/phasing data. */
  public boolean isHaplotypeData() {
    return bamFile != null && bamFile.isHaplotypeData();
  }

  /** Get current status message for display in the track. */
  public String getStatusMessage() {
    return bamFile != null ? bamFile.getStatusMessage() : null;
  }

  // ── BED delegation ──

  /** Get the BED track (only for BED files). */
  public BedTrack getBedTrack() { return bedTrack; }

  @Override
  public void close() throws IOException {
    if (bamFile != null) bamFile.close();
  }
}
