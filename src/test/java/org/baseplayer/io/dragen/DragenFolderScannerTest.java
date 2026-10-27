package org.baseplayer.io.dragen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DragenFolderScannerTest {

  private static final String STEM = "Fam_c174_1_6764_T_LP6005798-DNA_F03";

  private static final List<String> EXAMPLE_NAMES = List.of(
      "2210019CK09F_139849_usage.txt",
      STEM + ".baf.bw",
      STEM + ".baf.seg",
      STEM + ".baf.seg.bw",
      STEM + ".bam",
      STEM + ".bam.bai",
      STEM + ".bam.md5sum",
      STEM + ".cnv.excluded_intervals.bed.gz",
      STEM + ".cnv.gff3",
      STEM + ".cnv.igv_session.xml",
      STEM + ".cnv_metrics.csv",
      STEM + ".cnv.purity.coverage.models.tsv",
      STEM + ".cnv.vcf.gz",
      STEM + ".cnv.vcf.gz.md5sum",
      STEM + ".cnv.vcf.gz.tbi",
      STEM + ".fastqc_metrics.csv",
      STEM + ".fragment_length_hist.csv",
      STEM + ".hard-filtered.baf.bw",
      STEM + ".hard-filtered.vcf.gz",
      STEM + ".hard-filtered.vcf.gz.md5sum",
      STEM + ".hard-filtered.vcf.gz.tbi",
      STEM + ".insert-stats.tab",
      STEM + ".mapping_metrics.csv",
      STEM + ".match_log.small_indel_dedup.txt",
      STEM + ".pcr-model-0.log",
      STEM + ".pcr-model-1.log",
      STEM + ".pcr-model.log",
      STEM + ".ploidy_estimation_metrics.csv",
      STEM + ".ploidy.vcf.gz",
      STEM + ".ploidy.vcf.gz.md5sum",
      STEM + ".ploidy.vcf.gz.tbi",
      STEM + "-replay.json",
      STEM + ".seg",
      STEM + ".seg.bw",
      STEM + ".snperror-sampler.log",
      STEM + ".sv_metrics.csv",
      STEM + ".sv.small_indel_dedup.vcf.gz",
      STEM + ".sv.small_indel_dedup.vcf.gz.md5sum",
      STEM + ".sv.small_indel_dedup.vcf.gz.tbi",
      STEM + ".sv.vcf.gz",
      STEM + ".sv.vcf.gz.tbi",
      STEM + ".time_metrics.csv",
      STEM + ".tn.bw",
      STEM + ".tn.tsv.gz",
      STEM + ".trimmer_metrics.csv",
      STEM + ".tumor.baf.bedgraph.gz",
      STEM + ".tumor.ballele.counts.gz",
      STEM + "_tumor.bam",
      STEM + "_tumor.bam.bai",
      STEM + "_tumor.bam.md5sum",
      STEM + ".tumor.improper.pairs.bw",
      STEM + ".tumor.target.counts.bw",
      STEM + ".tumor.target.counts.gc-corrected.gz",
      STEM + ".tumor.target.counts.gz",
      STEM + ".vcf.gz",
      STEM + ".vcf.gz.md5sum",
      STEM + ".vcf.gz.tbi",
      STEM + ".vc_metrics.csv",
      STEM + ".wgs_contig_mean_cov_normal.csv",
      STEM + ".wgs_contig_mean_cov_tumor.csv",
      STEM + ".wgs_coverage_metrics_normal.csv",
      STEM + ".wgs_coverage_metrics_tumor.csv",
      STEM + ".wgs_fine_hist_normal.csv",
      STEM + ".wgs_fine_hist_tumor.csv",
      STEM + ".wgs_hist_normal.csv",
      STEM + ".wgs_hist_tumor.csv",
      STEM + ".wgs_overall_mean_cov_normal.csv",
      STEM + ".wgs_overall_mean_cov_tumor.csv",
      STEM + ".wgs_somatic_callable_regions.bed");

  @TempDir
  Path tempDir;

  @Test
  void scanDetectsAnalysesAndStemFromExampleListing() throws IOException {
    populateExampleDir();

    DragenFolderScan scan = DragenFolderScanner.scan(tempDir.toFile());

    assertTrue(scan.looksLikeDragen());
    assertEquals(STEM, scan.stem());
    assertTrue(scan.available().containsAll(EnumSet.of(
        DragenAnalysisKind.GERMLINE_VARIANTS,
        DragenAnalysisKind.SOMATIC_POINT_MUTATIONS,
        DragenAnalysisKind.CNV,
        DragenAnalysisKind.SV)));
    // Example folder's leftover BigWigs are optional CNV tracks, not multimodal.
    assertFalse(scan.available().contains(DragenAnalysisKind.MULTIMODAL));

    assertEquals(STEM + ".hard-filtered.vcf.gz", scan.hardFilteredVcf().getName());
    assertEquals(STEM + ".cnv.vcf.gz", scan.cnvVcf().getName());
    assertEquals(STEM + ".sv.vcf.gz", scan.svVcf().getName());
    assertEquals(STEM + ".bam", scan.normalBam().getName());
    assertEquals(STEM + "_tumor.bam", scan.tumorBam().getName());

    List<String> cnvBw = scan.cnvCompanionBigWigs().stream().map(f -> f.getName()).toList();
    assertEquals(2, cnvBw.size());
    assertTrue(cnvBw.contains(STEM + ".hard-filtered.baf.bw"));
    assertTrue(cnvBw.contains(STEM + ".seg.bw"));
    assertFalse(cnvBw.contains(STEM + ".tn.bw"));
    assertFalse(cnvBw.contains(STEM + ".baf.bw"));
    assertFalse(cnvBw.contains(STEM + ".baf.seg.bw"));

    List<String> optional = scan.optionalCnvBigWigs().stream().map(f -> f.getName()).toList();
    assertTrue(optional.contains(STEM + ".tn.bw"));
    assertTrue(optional.contains(STEM + ".tumor.improper.pairs.bw"));
    assertTrue(optional.contains(STEM + ".tumor.target.counts.bw"));
    assertTrue(optional.contains(STEM + ".baf.seg.bw"));
    assertFalse(optional.contains(STEM + ".baf.bw"));
    assertFalse(optional.contains(STEM + ".hard-filtered.baf.bw"));

    // Raw baf is suppressed when hard-filtered BAF exists; optional files are claimed.
    List<String> multi = scan.multimodalBigWigs().stream().map(f -> f.getName()).toList();
    assertFalse(multi.contains(STEM + ".baf.bw"));
    assertFalse(multi.contains(STEM + ".tn.bw"));
    assertFalse(multi.contains(STEM + ".baf.seg.bw"));
    assertFalse(multi.contains(STEM + ".tumor.target.counts.bw"));
  }

  @Test
  void germlineBundleUsesHardFilteredAndNormalBam() throws IOException {
    populateExampleDir();
    DragenFolderScan scan = DragenFolderScanner.scan(tempDir.toFile());

    DragenLoadPlan plan = DragenFolderScanner.resolveLoadPlan(
        scan, EnumSet.of(DragenAnalysisKind.GERMLINE_VARIANTS));

    assertEquals(STEM, plan.stem());
    assertEquals(List.of(scan.hardFilteredVcf()), plan.vcfFiles());
    assertEquals(List.of(scan.normalBam()), plan.bamFiles());
    assertTrue(plan.bigWigFiles().isEmpty());
  }

  @Test
  void somaticBundleIncludesTumorAndNormalBam() throws IOException {
    populateExampleDir();
    DragenFolderScan scan = DragenFolderScanner.scan(tempDir.toFile());

    DragenLoadPlan plan = DragenFolderScanner.resolveLoadPlan(
        scan, EnumSet.of(DragenAnalysisKind.SOMATIC_POINT_MUTATIONS));

    assertEquals(1, plan.vcfFiles().size());
    assertEquals(scan.hardFilteredVcf(), plan.vcfFiles().get(0));
    assertEquals(2, plan.bamFiles().size());
    assertTrue(plan.bamFiles().contains(scan.tumorBam()));
    assertTrue(plan.bamFiles().contains(scan.normalBam()));
  }

  @Test
  void cnvBundleIncludesCompanionsAndDedupesVcfWhenCombinedWithGermline() throws IOException {
    populateExampleDir();
    DragenFolderScan scan = DragenFolderScanner.scan(tempDir.toFile());

    DragenLoadPlan plan = DragenFolderScanner.resolveLoadPlan(
        scan,
        EnumSet.of(DragenAnalysisKind.GERMLINE_VARIANTS, DragenAnalysisKind.CNV));

    assertEquals(2, plan.vcfFiles().size());
    assertTrue(plan.vcfFiles().contains(scan.hardFilteredVcf()));
    assertTrue(plan.vcfFiles().contains(scan.cnvVcf()));
    assertTrue(plan.bamFiles().contains(scan.normalBam()));
    assertTrue(plan.bamFiles().contains(scan.tumorBam()));
    assertEquals(2, plan.bigWigFiles().size());
    assertTrue(plan.bigWigFiles().stream().anyMatch(f -> f.getName().endsWith(".hard-filtered.baf.bw")));
    assertTrue(plan.bigWigFiles().stream().anyMatch(f -> f.getName().endsWith(".seg.bw")));
    assertFalse(plan.bigWigFiles().stream().anyMatch(f -> f.getName().endsWith(".tn.bw")));
  }

  @Test
  void cnvOptionalBigWigsOnlyLoadWhenSelected() throws IOException {
    populateExampleDir();
    DragenFolderScan scan = DragenFolderScanner.scan(tempDir.toFile());
    File tn = scan.optionalCnvBigWigs().stream()
        .filter(f -> f.getName().endsWith(".tn.bw"))
        .findFirst()
        .orElseThrow();

    DragenLoadPlan without = DragenFolderScanner.resolveLoadPlan(
        scan, EnumSet.of(DragenAnalysisKind.CNV));
    assertEquals(2, without.bigWigFiles().size());

    DragenLoadPlan withTn = DragenFolderScanner.resolveLoadPlan(
        scan, EnumSet.of(DragenAnalysisKind.CNV), List.of(tn));
    assertEquals(3, withTn.bigWigFiles().size());
    assertTrue(withTn.bigWigFiles().contains(tn));
  }

  @Test
  void somaticAndGermlineShareHardFilteredVcfOnce() throws IOException {
    populateExampleDir();
    DragenFolderScan scan = DragenFolderScanner.scan(tempDir.toFile());

    DragenLoadPlan plan = DragenFolderScanner.resolveLoadPlan(
        scan,
        EnumSet.of(
            DragenAnalysisKind.GERMLINE_VARIANTS,
            DragenAnalysisKind.SOMATIC_POINT_MUTATIONS));

    assertEquals(1, plan.vcfFiles().size());
    assertEquals(scan.hardFilteredVcf(), plan.vcfFiles().get(0));
  }

  @Test
  void ignoresIndexesAndMetricsForAvailability() throws IOException {
    Files.writeString(tempDir.resolve("sample.bam.bai"), "x");
    Files.writeString(tempDir.resolve("sample.vcf.gz.tbi"), "x");
    Files.writeString(tempDir.resolve("sample.mapping_metrics.csv"), "x");

    DragenFolderScan scan = DragenFolderScanner.scan(tempDir.toFile());
    assertTrue(scan.looksLikeDragen());
    assertTrue(scan.available().isEmpty());
  }

  @Test
  void inferStemPrefersReplayJson() {
    assertEquals(
        STEM,
        DragenFolderScanner.inferStem(Set.of(
            STEM + "-replay.json",
            STEM + ".hard-filtered.vcf.gz",
            "other.bam")));
  }

  @Test
  void looksLikeDragenRequiresKnownMarkers() {
    assertFalse(DragenFolderScanner.looksLikeDragen(Set.of("readme.txt", "data.bam")));
    assertTrue(DragenFolderScanner.looksLikeDragen(Set.of(STEM + ".cnv.vcf.gz")));
  }

  private void populateExampleDir() throws IOException {
    for (String name : EXAMPLE_NAMES) {
      Path path = tempDir.resolve(name);
      Files.writeString(path, "placeholder");
    }
  }
}
