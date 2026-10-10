package org.baseplayer.io;

import java.io.File;
import java.util.Locale;

/** Sample / feature file kinds discovered when opening a directory. */
public enum SampleFileKind {
  BAM("BAM/CRAM"),
  VCF("VCF"),
  BED("BED"),
  BIGWIG("BigWig"),
  AB1("AB1");

  private final String label;

  SampleFileKind(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public static SampleFileKind fromFile(File file) {
    if (file == null) {
      return null;
    }
    String name = file.getName().toLowerCase(Locale.ROOT);
    if (name.endsWith(".bam") || name.endsWith(".cram")) {
      return BAM;
    }
    if (name.endsWith(".vcf.gz") || name.endsWith(".vcf")) {
      return VCF;
    }
    if (name.endsWith(".bed") || name.endsWith(".bed.gz")) {
      return BED;
    }
    if (name.endsWith(".bw") || name.endsWith(".bigwig")) {
      return BIGWIG;
    }
    if (name.endsWith(".ab1")) {
      return AB1;
    }
    return null;
  }
}
