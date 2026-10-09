package org.baseplayer.variant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.baseplayer.io.readers.VcfReader;

/**
 * Headless probe that opens a VCF through {@link VcfReader} — the same path the
 * app uses for SV and CNV files (TBI query, or CSI-gated linear scan).
 *
 * <pre>
 *   ./gradlew cnvProbe -Pvcf=/path/file.vcf.gz [-Pchrom=1]
 * </pre>
 */
public final class CnvVcfProbe {

  private CnvVcfProbe() {}

  public static void main(String[] args) throws Exception {
    Path vcf = null;
    String chrom = "1";
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--vcf" -> vcf = Path.of(args[++i]);
        case "--chrom" -> chrom = args[++i];
        default -> {
          System.err.println("Unknown arg: " + args[i]);
          usage();
          System.exit(2);
        }
      }
    }
    if (vcf == null) {
      usage();
      System.exit(2);
    }

    System.out.println("=== VCF probe (via VcfReader) ===");
    System.out.println("vcf:  " + vcf);
    System.out.println("exists: " + Files.exists(vcf)
        + (Files.exists(vcf) ? "  size=" + Files.size(vcf) : ""));
    Path tbi = Path.of(vcf + ".tbi");
    Path tbiNogz = vcf.toString().endsWith(".gz")
        ? Path.of(vcf.toString().substring(0, vcf.toString().length() - 3) + ".tbi")
        : null;
    Path csi = Path.of(vcf + ".csi");
    Path csiNogz = vcf.toString().endsWith(".gz")
        ? Path.of(vcf.toString().substring(0, vcf.toString().length() - 3) + ".csi")
        : null;
    System.out.println("tbi:  "
        + (Files.exists(tbi) ? tbi.getFileName()
            : (tbiNogz != null && Files.exists(tbiNogz) ? tbiNogz.getFileName() : "MISSING")));
    System.out.println("csi:  "
        + (Files.exists(csi) ? csi.getFileName()
            : (csiNogz != null && Files.exists(csiNogz) ? csiNogz.getFileName() : "MISSING")));

    try (VcfReader reader = new VcfReader(vcf)) {
      System.out.println("samples: " + reader.getSampleNames());
      System.out.println("prefix: '" + reader.getChromPrefix() + "'");
      System.out.println("contigs: " + reader.getAvailableChromosomes());
      System.out.println("canTabixQuery: " + reader.canTabixQuery()
          + "  hasTbiOrCsiIndex: " + reader.hasTbiOrCsiIndex());

      List<VcfStructuralVariant> svs =
          reader.queryStructuralVariants(chrom, 1, 300_000_000L);
      System.out.println("structural on " + chrom + ": " + svs.size());
      int gain = 0, loss = 0, neutral = 0, otherCnv = 0;
      for (VcfStructuralVariant sv : svs) {
        switch (sv.getType()) {
          case SV_CNV_GAIN -> gain++;
          case SV_CNV_LOSS -> loss++;
          case SV_CNV_NEUTRAL -> neutral++;
          case SV_CNV -> otherCnv++;
          default -> {
          }
        }
      }
      System.out.println("CNV gain/loss/neutral/other: "
          + gain + "/" + loss + "/" + neutral + "/" + otherCnv);
      for (int i = 0; i < Math.min(8, svs.size()); i++) {
        VcfStructuralVariant sv = svs.get(i);
        Object tcn = sv.getInfo() != null ? sv.getInfo().get("TCN_EM") : null;
        System.out.println("  " + sv.getType()
            + " " + sv.getChromosome() + ":" + sv.getPosition()
            + "-" + sv.getEnd()
            + " TCN_EM=" + tcn
            + " alt=" + sv.getAlt()
            + " samples=" + sv.getGenotypes().keySet());
      }
    }
  }

  private static void usage() {
    System.err.println("Usage: CnvVcfProbe --vcf /path/file.vcf.gz [--chrom 1]");
  }
}
