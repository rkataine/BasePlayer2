package org.baseplayer.samples;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BigWigTrack;
import org.baseplayer.io.Settings;
import org.baseplayer.io.table.MappedTableData;
import org.baseplayer.samples.alignment.AlignmentFile;
import org.baseplayer.samples.alignment.BamHandleCache;
import org.baseplayer.samples.alignment.draw.ReadColorMode;
import org.baseplayer.sanger.SangerTrace;

/**
 * Represents a single loaded data file (BAM, BED, VCF, AB1, BigWig, table, etc.) under an individual.
 * <p>
 * BAM/CRAM samples are path-only until {@link #ensureOpen()}. At most one BAM
 * reader/index is kept open ({@link BamHandleCache}); coverage matrices can be
 * parked here so the index can be closed after a fetch.
 */
public class Sample implements Closeable {

  public enum DataType {
    BAM, BED, VCF, AB1, BIGWIG, TABLE
  }

  private final String name;
  private final Path path;
  private final DataType dataType;
  private SampleTrack track;

  public boolean visible = true;
  public boolean overlay = false;

  private volatile AlignmentFile bamFile;
  private volatile String bamOpenError;
  private volatile AlignmentFile.CoverageCache parkedCoverageCache;
  private volatile Boolean knownMethylation;
  private volatile Boolean knownHaplotype;

  private volatile ReadColorMode pendingReadColorMode = Settings.get().getReadColorMode();
  private volatile AlignmentFile.ReadStackingMode pendingStackingMode =
      AlignmentFile.ReadStackingMode.AUTO;
  private volatile boolean pendingCoverageOnlyMode = false;
  private volatile Boolean pendingSuppressMethylMismatches = null;

  private final BedTrack bedTrack;
  private final BigWigTrack bigWigTrack;
  private final SangerTrace sangerTrace;
  private volatile MappedTableData mappedTable;

  /** Path-only BAM/CRAM — does not open the file or load the index. */
  public Sample(Path filePath) {
    this.path = filePath;
    this.dataType = DataType.BAM;
    this.bedTrack = null;
    this.bigWigTrack = null;
    this.sangerTrace = null;
    this.mappedTable = null;
    this.bamFile = null;
    this.name = filenameStem(filePath);
  }

  public Sample(Path filePath, BedTrack bedTrack) {
    this.path = filePath;
    this.dataType = DataType.BED;
    this.bamFile = null;
    this.bigWigTrack = null;
    this.sangerTrace = null;
    this.mappedTable = null;
    this.bedTrack = bedTrack;
    this.name = bedTrack.getName();
    this.overlay = true;
  }

  public Sample(Path filePath, BigWigTrack bigWigTrack) {
    this.path = filePath;
    this.dataType = DataType.BIGWIG;
    this.bamFile = null;
    this.bedTrack = null;
    this.sangerTrace = null;
    this.mappedTable = null;
    this.bigWigTrack = bigWigTrack;
    this.name = bigWigTrack != null ? bigWigTrack.getName()
        : (filePath.getFileName() != null ? filePath.getFileName().toString() : filePath.toString());
    this.overlay = true;
  }

  public Sample(Path filePath, SangerTrace sangerTrace) {
    this.path = filePath;
    this.dataType = DataType.AB1;
    this.bamFile = null;
    this.bedTrack = null;
    this.bigWigTrack = null;
    this.mappedTable = null;
    this.sangerTrace = sangerTrace;
    this.name = sangerTrace != null ? sangerTrace.getName()
        : (filePath.getFileName() != null ? filePath.getFileName().toString() : filePath.toString());
  }

  public Sample(Path filePath, MappedTableData mappedTable) {
    this.path = filePath;
    this.dataType = DataType.TABLE;
    this.bamFile = null;
    this.bedTrack = null;
    this.bigWigTrack = null;
    this.sangerTrace = null;
    this.mappedTable = mappedTable;
    this.name = mappedTable != null ? mappedTable.getName()
        : (filePath.getFileName() != null ? filePath.getFileName().toString() : filePath.toString());
    this.overlay = true;
  }

  public Sample(Path vcfPath, DataType type) {
    if (type != DataType.VCF) {
      throw new IllegalArgumentException("Use typed constructors for BAM/BED/AB1/BIGWIG/TABLE samples");
    }
    this.path = vcfPath;
    this.dataType = DataType.VCF;
    this.bamFile = null;
    this.bedTrack = null;
    this.bigWigTrack = null;
    this.sangerTrace = null;
    this.mappedTable = null;
    this.name = vcfPath.getFileName() != null
        ? vcfPath.getFileName().toString()
        : vcfPath.toString();
  }

  private static String filenameStem(Path filePath) {
    String raw = filePath.getFileName() != null
        ? filePath.getFileName().toString()
        : filePath.toString();
    String lower = raw.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".bam") || lower.endsWith(".cram")) {
      return raw.substring(0, raw.lastIndexOf('.'));
    }
    return raw;
  }

  public String getName() { return name; }
  public Path getPath() { return path; }
  public DataType getDataType() { return dataType; }
  public SampleTrack getTrack() { return track; }
  void setTrack(SampleTrack track) { this.track = track; }

  /**
   * Open the BAM/CRAM reader+index for interactive use (read piles).
   * Tracked in {@link BamHandleCache} (LRU, default max 8).
   */
  public synchronized AlignmentFile ensureOpen() {
    if (dataType != DataType.BAM) {
      return null;
    }
    if (bamFile != null) {
      BamHandleCache.get().touch(this);
      return bamFile;
    }
    if (bamOpenError != null) {
      return null;
    }
    try {
      AlignmentFile opened = new AlignmentFile(path);
      applyPendingSettings(opened);
      bamFile = opened;
      BamHandleCache.get().touch(this);
      return bamFile;
    } catch (IOException | RuntimeException ex) {
      bamOpenError = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
      System.err.println("Failed to open BAM " + path + ": " + bamOpenError);
      return null;
    }
  }

  /**
   * Open a temporary reader that is <b>not</b> stored on this Sample and not
   * registered in {@link BamHandleCache}. Caller must {@link AlignmentFile#close()}.
   * Used by background coverage fills so they cannot steal interactive handles.
   */
  public synchronized AlignmentFile openEphemeral() {
    if (dataType != DataType.BAM) {
      return null;
    }
    if (bamOpenError != null) {
      return null;
    }
    try {
      AlignmentFile opened = new AlignmentFile(path);
      applyPendingSettings(opened);
      return opened;
    } catch (IOException | RuntimeException ex) {
      bamOpenError = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
      System.err.println("Failed to open BAM " + path + ": " + bamOpenError);
      return null;
    }
  }

  /** Copy detection flags after an ephemeral coverage pass. */
  public synchronized void noteDetectionFrom(AlignmentFile source) {
    if (source == null) {
      return;
    }
    knownMethylation = source.isMethylationData();
    knownHaplotype = source.isHaplotypeData();
  }

  /** Close interactive reader/index; keep path, settings, and parked coverage. */
  public synchronized void releaseBam() {
    if (bamFile == null) {
      return;
    }
    knownMethylation = bamFile.isMethylationData();
    knownHaplotype = bamFile.isHaplotypeData();
    AlignmentFile.CoverageCache live = bamFile.detachAnyCoverageCache();
    if (live != null) {
      parkedCoverageCache = live;
    }
    try {
      bamFile.close();
    } catch (IOException ignored) {
      // best-effort
    }
    bamFile = null;
    BamHandleCache.get().remove(this);
  }

  public synchronized void parkCoverageCache(AlignmentFile.CoverageCache cache) {
    if (cache != null) {
      parkedCoverageCache = cache;
    }
  }

  public AlignmentFile.CoverageCache getParkedCoverageCache() {
    return parkedCoverageCache;
  }

  private void applyPendingSettings(AlignmentFile opened) {
    opened.setReadColorMode(pendingReadColorMode);
    opened.setReadStackingMode(pendingStackingMode);
    opened.setCoverageOnlyMode(pendingCoverageOnlyMode);
    opened.setSuppressMethylMismatches(pendingSuppressMethylMismatches);
  }

  public AlignmentFile getBamFile() { return bamFile; }

  public String getBamOpenError() { return bamOpenError; }

  public boolean isMethylationData() {
    if (bamFile != null) {
      return bamFile.isMethylationData();
    }
    if (pendingSuppressMethylMismatches != null) {
      return pendingSuppressMethylMismatches;
    }
    return knownMethylation != null && knownMethylation;
  }

  public boolean isHaplotypeData() {
    if (bamFile != null) {
      return bamFile.isHaplotypeData();
    }
    return knownHaplotype != null && knownHaplotype;
  }

  public String getStatusMessage() {
    if (bamOpenError != null) {
      return "Open failed: " + bamOpenError;
    }
    return bamFile != null ? bamFile.getStatusMessage() : null;
  }

  public ReadColorMode getReadColorMode() {
    return bamFile != null ? bamFile.getReadColorMode() : pendingReadColorMode;
  }

  public void setReadColorMode(ReadColorMode mode) {
    if (mode == null) {
      return;
    }
    pendingReadColorMode = mode;
    if (bamFile != null) {
      bamFile.setReadColorMode(mode);
    }
  }

  public AlignmentFile.ReadStackingMode getReadStackingMode() {
    return bamFile != null ? bamFile.getReadStackingMode() : pendingStackingMode;
  }

  public void setReadStackingMode(AlignmentFile.ReadStackingMode mode) {
    if (mode == null) {
      return;
    }
    pendingStackingMode = mode;
    if (bamFile != null) {
      bamFile.setReadStackingMode(mode);
    }
  }

  public boolean isCoverageOnlyMode() {
    return bamFile != null ? bamFile.isCoverageOnlyMode() : pendingCoverageOnlyMode;
  }

  public void setCoverageOnlyMode(boolean coverageOnly) {
    pendingCoverageOnlyMode = coverageOnly;
    if (bamFile != null) {
      bamFile.setCoverageOnlyMode(coverageOnly);
    }
  }

  public Boolean getSuppressMethylMismatches() {
    return bamFile != null ? bamFile.getSuppressMethylMismatches() : pendingSuppressMethylMismatches;
  }

  public void setSuppressMethylMismatches(Boolean value) {
    pendingSuppressMethylMismatches = value;
    if (bamFile != null) {
      bamFile.setSuppressMethylMismatches(value);
    }
  }

  public void setOnFirstLoadComplete(Runnable callback) {
    AlignmentFile open = ensureOpen();
    if (open != null) {
      open.setOnFirstLoadComplete(callback);
    } else if (dataType != DataType.BAM) {
      javafx.application.Platform.runLater(callback);
    } else if (callback != null) {
      javafx.application.Platform.runLater(callback);
    }
  }

  public void setOnFirstFetchStarted(Runnable callback) {
    AlignmentFile open = ensureOpen();
    if (open != null) {
      open.setOnFirstFetchStarted(callback);
    } else if (dataType != DataType.BAM) {
      javafx.application.Platform.runLater(callback);
    }
  }

  public boolean isSuspended() {
    return bamFile != null && bamFile.isSuspended();
  }

  public void cancelAndSuspend() {
    if (bamFile != null) {
      bamFile.cancelAndSuspend();
    }
  }

  public void resume() {
    if (bamFile != null) {
      bamFile.resume();
    }
  }

  public BedTrack getBedTrack() { return bedTrack; }
  public BigWigTrack getBigWigTrack() { return bigWigTrack; }
  public SangerTrace getSangerTrace() { return sangerTrace; }
  public MappedTableData getMappedTable() { return mappedTable; }

  /** Replace table data after remapping columns (TABLE samples only). */
  public void replaceMappedTable(MappedTableData data) {
    if (dataType != DataType.TABLE) {
      throw new IllegalStateException("Not a table sample");
    }
    this.mappedTable = data;
  }

  @Override
  public void close() throws IOException {
    releaseBam();
    if (bigWigTrack != null) {
      bigWigTrack.dispose();
    }
  }
}
