package org.baseplayer.io.dragen;

import java.io.File;
import java.util.List;

/** Deduplicated files to open for a DRAGEN folder selection. */
public record DragenLoadPlan(
    String stem,
    List<File> bamFiles,
    List<File> vcfFiles,
    List<File> bigWigFiles) {

  public DragenLoadPlan {
    stem = stem != null ? stem : "";
    bamFiles = bamFiles != null ? List.copyOf(bamFiles) : List.of();
    vcfFiles = vcfFiles != null ? List.copyOf(vcfFiles) : List.of();
    bigWigFiles = bigWigFiles != null ? List.copyOf(bigWigFiles) : List.of();
  }

  public boolean isEmpty() {
    return bamFiles.isEmpty() && vcfFiles.isEmpty() && bigWigFiles.isEmpty();
  }
}
