package org.baseplayer.io.dragen;

import java.io.File;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of scanning one DRAGEN output directory: sample stem, available analyses,
 * and the files resolved for each analysis type.
 */
public final class DragenFolderScan {

  private final File directory;
  private final String stem;
  private final boolean looksLikeDragen;
  private final EnumSet<DragenAnalysisKind> available;
  private final File hardFilteredVcf;
  private final File cnvVcf;
  private final File svVcf;
  private final File normalBam;
  private final File tumorBam;
  /** Default CNV BigWigs (BAF + seg). */
  private final List<File> cnvCompanionBigWigs;
  /** Secondary CNV tracks (tn, target counts, …) — optional in the open dialog. */
  private final List<File> optionalCnvBigWigs;
  private final List<File> multimodalBigWigs;
  private final Map<DragenAnalysisKind, String> previewByKind;

  public DragenFolderScan(
      File directory,
      String stem,
      boolean looksLikeDragen,
      EnumSet<DragenAnalysisKind> available,
      File hardFilteredVcf,
      File cnvVcf,
      File svVcf,
      File normalBam,
      File tumorBam,
      List<File> cnvCompanionBigWigs,
      List<File> optionalCnvBigWigs,
      List<File> multimodalBigWigs,
      Map<DragenAnalysisKind, String> previewByKind) {
    this.directory = directory;
    this.stem = stem != null ? stem : "";
    this.looksLikeDragen = looksLikeDragen;
    this.available = available != null && !available.isEmpty()
        ? EnumSet.copyOf(available)
        : EnumSet.noneOf(DragenAnalysisKind.class);
    this.hardFilteredVcf = hardFilteredVcf;
    this.cnvVcf = cnvVcf;
    this.svVcf = svVcf;
    this.normalBam = normalBam;
    this.tumorBam = tumorBam;
    this.cnvCompanionBigWigs = cnvCompanionBigWigs != null ? List.copyOf(cnvCompanionBigWigs) : List.of();
    this.optionalCnvBigWigs =
        optionalCnvBigWigs != null ? List.copyOf(optionalCnvBigWigs) : List.of();
    this.multimodalBigWigs =
        multimodalBigWigs != null ? List.copyOf(multimodalBigWigs) : List.of();
    this.previewByKind = previewByKind != null
        ? Map.copyOf(previewByKind)
        : Map.of();
  }

  public File directory() {
    return directory;
  }

  public String stem() {
    return stem;
  }

  public boolean looksLikeDragen() {
    return looksLikeDragen;
  }

  public EnumSet<DragenAnalysisKind> available() {
    return EnumSet.copyOf(available);
  }

  public File hardFilteredVcf() {
    return hardFilteredVcf;
  }

  public File cnvVcf() {
    return cnvVcf;
  }

  public File svVcf() {
    return svVcf;
  }

  public File normalBam() {
    return normalBam;
  }

  public File tumorBam() {
    return tumorBam;
  }

  public List<File> cnvCompanionBigWigs() {
    return cnvCompanionBigWigs;
  }

  public List<File> optionalCnvBigWigs() {
    return optionalCnvBigWigs;
  }

  public List<File> multimodalBigWigs() {
    return multimodalBigWigs;
  }

  public String preview(DragenAnalysisKind kind) {
    return previewByKind.getOrDefault(kind, "");
  }

  public Map<DragenAnalysisKind, String> previewByKind() {
    return new EnumMap<>(previewByKind);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DragenFolderScan other)) {
      return false;
    }
    return looksLikeDragen == other.looksLikeDragen
        && Objects.equals(directory, other.directory)
        && Objects.equals(stem, other.stem)
        && Objects.equals(available, other.available)
        && Objects.equals(hardFilteredVcf, other.hardFilteredVcf)
        && Objects.equals(cnvVcf, other.cnvVcf)
        && Objects.equals(svVcf, other.svVcf)
        && Objects.equals(normalBam, other.normalBam)
        && Objects.equals(tumorBam, other.tumorBam)
        && Objects.equals(cnvCompanionBigWigs, other.cnvCompanionBigWigs)
        && Objects.equals(optionalCnvBigWigs, other.optionalCnvBigWigs)
        && Objects.equals(multimodalBigWigs, other.multimodalBigWigs);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        directory,
        stem,
        looksLikeDragen,
        available,
        hardFilteredVcf,
        cnvVcf,
        svVcf,
        normalBam,
        tumorBam,
        cnvCompanionBigWigs,
        optionalCnvBigWigs,
        multimodalBigWigs);
  }
}
