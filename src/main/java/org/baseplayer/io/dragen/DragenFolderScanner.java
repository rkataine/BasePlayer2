package org.baseplayer.io.dragen;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Discovers DRAGEN analysis products in a flat output directory using Illumina
 * suffix conventions (e.g. {@code *.hard-filtered.vcf.gz}, {@code *.cnv.vcf.gz},
 * {@code *_tumor.bam}).
 */
public final class DragenFolderScanner {

  /** Longest-first so compound suffixes win over shorter ones. */
  private static final String[] STEM_SUFFIXES = {
      ".sv.small_indel_dedup.vcf.gz",
      ".hard-filtered.vcf.gz",
      ".hard-filtered.baf.bw",
      ".cnv.excluded_intervals.bed.gz",
      ".tumor.target.counts.gc-corrected.gz",
      ".wgs_somatic_callable_regions.bed",
      ".tumor.target.counts.bw",
      ".tumor.improper.pairs.bw",
      ".tumor.baf.bedgraph.gz",
      ".tumor.ballele.counts.gz",
      ".tumor.target.counts.gz",
      ".cnv.purity.coverage.models.tsv",
      ".cnv_metrics.csv",
      ".cnv.igv_session.xml",
      ".cnv.gff3",
      ".ploidy_estimation_metrics.csv",
      ".ploidy.vcf.gz",
      ".mapping_metrics.csv",
      ".fastqc_metrics.csv",
      ".fragment_length_hist.csv",
      ".insert-stats.tab",
      ".time_metrics.csv",
      ".trimmer_metrics.csv",
      ".sv_metrics.csv",
      ".vc_metrics.csv",
      ".baf.seg.bw",
      ".baf.seg",
      ".baf.bw",
      ".seg.bw",
      ".tn.tsv.gz",
      ".tn.bw",
      ".cnv.vcf.gz",
      ".sv.vcf.gz",
      ".vcf.gz",
      "_tumor.bam",
      "_tumor.cram",
      "-replay.json",
      ".bam",
      ".cram",
      ".seg",
  };

  private DragenFolderScanner() {}

  public static DragenFolderScan scan(File directory) {
    if (directory == null || !directory.isDirectory()) {
      return emptyScan(directory);
    }
    File[] listed = directory.listFiles(File::isFile);
    if (listed == null || listed.length == 0) {
      return emptyScan(directory);
    }

    Map<String, File> byName = new LinkedHashMap<>();
    for (File file : listed) {
      if (file != null && file.getName() != null) {
        byName.put(file.getName(), file);
      }
    }

    String stem = inferStem(byName.keySet());
    boolean looksLike = looksLikeDragen(byName.keySet());

    File hardFiltered = findBySuffix(byName, stem, ".hard-filtered.vcf.gz");
    File cnvVcf = findBySuffix(byName, stem, ".cnv.vcf.gz");
    File svVcf = findBySuffix(byName, stem, ".sv.vcf.gz");
    File tumorBam = findTumorBam(byName, stem);
    File normalBam = findNormalBam(byName, stem, tumorBam);

    List<File> cnvCompanions = resolveCnvCompanions(byName, stem);
    List<File> optionalCnv = resolveOptionalCnvBigWigs(byName, stem, cnvCompanions);
    Set<String> claimedBw = new LinkedHashSet<>();
    for (File bw : cnvCompanions) {
      claimedBw.add(bw.getName());
    }
    for (File bw : optionalCnv) {
      claimedBw.add(bw.getName());
    }
    // Prefer hard-filtered BAF: never surface raw .baf.bw as multimodal leftover.
    if (claimedBw.stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).endsWith(".hard-filtered.baf.bw"))) {
      File rawBaf = findBySuffix(byName, stem, ".baf.bw");
      if (rawBaf != null) {
        claimedBw.add(rawBaf.getName());
      }
    }
    List<File> multimodal = resolveMultimodalBigWigs(byName, claimedBw);

    EnumSet<DragenAnalysisKind> available = EnumSet.noneOf(DragenAnalysisKind.class);
    if (hardFiltered != null) {
      available.add(DragenAnalysisKind.GERMLINE_VARIANTS);
    }
    if (tumorBam != null && hardFiltered != null) {
      available.add(DragenAnalysisKind.SOMATIC_POINT_MUTATIONS);
    }
    if (cnvVcf != null) {
      available.add(DragenAnalysisKind.CNV);
    }
    if (svVcf != null) {
      available.add(DragenAnalysisKind.SV);
    }
    if (!multimodal.isEmpty()) {
      available.add(DragenAnalysisKind.MULTIMODAL);
    }

    Map<DragenAnalysisKind, String> previews = buildPreviews(
        hardFiltered, cnvVcf, svVcf, normalBam, tumorBam, cnvCompanions, multimodal);

    return new DragenFolderScan(
        directory,
        stem,
        looksLike,
        available,
        hardFiltered,
        cnvVcf,
        svVcf,
        normalBam,
        tumorBam,
        cnvCompanions,
        optionalCnv,
        multimodal,
        previews);
  }

  /**
   * Build a deduplicated load plan for the selected analysis kinds
   * (no optional CNV BigWigs).
   */
  public static DragenLoadPlan resolveLoadPlan(
      DragenFolderScan scan, Set<DragenAnalysisKind> selected) {
    return resolveLoadPlan(scan, selected, List.of());
  }

  /**
   * Build a deduplicated load plan for the selected analysis kinds plus any
   * optional CNV companion BigWigs the user checked.
   */
  public static DragenLoadPlan resolveLoadPlan(
      DragenFolderScan scan,
      Set<DragenAnalysisKind> selected,
      List<File> optionalBigWigs) {
    if (scan == null || selected == null || selected.isEmpty()) {
      return new DragenLoadPlan("", List.of(), List.of(), List.of());
    }

    LinkedHashSet<File> bams = new LinkedHashSet<>();
    LinkedHashSet<File> vcfs = new LinkedHashSet<>();
    LinkedHashSet<File> bigWigs = new LinkedHashSet<>();

    if (selected.contains(DragenAnalysisKind.GERMLINE_VARIANTS)) {
      addIfPresent(vcfs, scan.hardFilteredVcf());
      addIfPresent(bams, scan.normalBam());
    }
    if (selected.contains(DragenAnalysisKind.SOMATIC_POINT_MUTATIONS)) {
      addIfPresent(vcfs, scan.hardFilteredVcf());
      addIfPresent(bams, scan.tumorBam());
      addIfPresent(bams, scan.normalBam());
    }
    if (selected.contains(DragenAnalysisKind.CNV)) {
      addIfPresent(vcfs, scan.cnvVcf());
      addPreferredBam(bams, scan);
      bigWigs.addAll(scan.cnvCompanionBigWigs());
      if (optionalBigWigs != null && !optionalBigWigs.isEmpty()) {
        Set<String> wantedNames = new LinkedHashSet<>();
        for (File optional : optionalBigWigs) {
          if (optional != null) {
            wantedNames.add(optional.getName());
          }
        }
        for (File candidate : scan.optionalCnvBigWigs()) {
          if (candidate != null && wantedNames.contains(candidate.getName())) {
            bigWigs.add(candidate);
          }
        }
      }
    }
    if (selected.contains(DragenAnalysisKind.SV)) {
      addIfPresent(vcfs, scan.svVcf());
      addPreferredBam(bams, scan);
    }
    if (selected.contains(DragenAnalysisKind.MULTIMODAL)) {
      bigWigs.addAll(scan.multimodalBigWigs());
      if (bams.isEmpty()) {
        addPreferredBam(bams, scan);
      }
    }

    return new DragenLoadPlan(scan.stem(), List.copyOf(bams), List.copyOf(vcfs), List.copyOf(bigWigs));
  }

  static boolean looksLikeDragen(Set<String> names) {
    for (String name : names) {
      String lower = name.toLowerCase(Locale.ROOT);
      if (lower.endsWith("-replay.json")
          || lower.endsWith(".hard-filtered.vcf.gz")
          || lower.endsWith(".cnv.vcf.gz")
          || lower.endsWith(".sv.vcf.gz")
          || lower.endsWith(".mapping_metrics.csv")) {
        return true;
      }
    }
    return false;
  }

  static String inferStem(Set<String> names) {
    if (names == null || names.isEmpty()) {
      return "";
    }
    // Prefer replay.json basename when present.
    for (String name : names) {
      if (name.toLowerCase(Locale.ROOT).endsWith("-replay.json")) {
        return name.substring(0, name.length() - "-replay.json".length());
      }
    }

    Map<String, Integer> counts = new HashMap<>();
    for (String name : names) {
      String stem = stripKnownSuffix(name);
      if (stem == null || stem.isBlank() || stem.equals(name)) {
        continue;
      }
      counts.merge(stem, 1, Integer::sum);
    }
    if (counts.isEmpty()) {
      return "";
    }
    return counts.entrySet().stream()
        .sorted(Comparator
            .<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
            .thenComparing(e -> e.getKey().length())
            .thenComparing(Map.Entry::getKey))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse("");
  }

  static String stripKnownSuffix(String fileName) {
    if (fileName == null || fileName.isBlank()) {
      return null;
    }
    String lower = fileName.toLowerCase(Locale.ROOT);
    for (String suffix : STEM_SUFFIXES) {
      if (lower.endsWith(suffix)) {
        return fileName.substring(0, fileName.length() - suffix.length());
      }
    }
    // Metrics with stem_normal / stem_tumor mid-suffixes (coverage reports).
    int normalIdx = lower.indexOf(".wgs_");
    if (normalIdx > 0) {
      return fileName.substring(0, normalIdx);
    }
    return null;
  }

  private static File findBySuffix(Map<String, File> byName, String stem, String suffix) {
    if (stem != null && !stem.isBlank()) {
      File exact = byName.get(stem + suffix);
      if (exact != null) {
        return exact;
      }
    }
    String lowerSuffix = suffix.toLowerCase(Locale.ROOT);
    File fallback = null;
    for (Map.Entry<String, File> entry : byName.entrySet()) {
      String lower = entry.getKey().toLowerCase(Locale.ROOT);
      if (!lower.endsWith(lowerSuffix)) {
        continue;
      }
      // Avoid matching ".baf.seg.bw" when looking for ".seg.bw".
      int cut = lower.length() - lowerSuffix.length();
      if (cut > 0 && lower.charAt(cut - 1) == '.') {
        continue;
      }
      if (fallback != null) {
        return null; // ambiguous without stem
      }
      fallback = entry.getValue();
    }
    return fallback;
  }

  private static File findTumorBam(Map<String, File> byName, String stem) {
    if (stem != null && !stem.isBlank()) {
      File bam = byName.get(stem + "_tumor.bam");
      if (bam != null) {
        return bam;
      }
      File cram = byName.get(stem + "_tumor.cram");
      if (cram != null) {
        return cram;
      }
    }
    for (Map.Entry<String, File> entry : byName.entrySet()) {
      String lower = entry.getKey().toLowerCase(Locale.ROOT);
      if (lower.endsWith("_tumor.bam") || lower.endsWith("_tumor.cram")) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static File findNormalBam(Map<String, File> byName, String stem, File tumorBam) {
    if (stem != null && !stem.isBlank()) {
      File bam = byName.get(stem + ".bam");
      if (bam != null) {
        return bam;
      }
      File cram = byName.get(stem + ".cram");
      if (cram != null) {
        return cram;
      }
    }
    String tumorName = tumorBam != null ? tumorBam.getName() : null;
    for (Map.Entry<String, File> entry : byName.entrySet()) {
      String lower = entry.getKey().toLowerCase(Locale.ROOT);
      if (!(lower.endsWith(".bam") || lower.endsWith(".cram"))) {
        continue;
      }
      if (lower.endsWith("_tumor.bam") || lower.endsWith("_tumor.cram")) {
        continue;
      }
      if (tumorName != null && entry.getKey().equals(tumorName)) {
        continue;
      }
      return entry.getValue();
    }
    return null;
  }

  /**
   * Default CNV tracks: hard-filtered BAF (else raw BAF) + copy-ratio seg.
   */
  private static List<File> resolveCnvCompanions(Map<String, File> byName, String stem) {
    List<File> companions = new ArrayList<>();
    File hardBaf = findBySuffix(byName, stem, ".hard-filtered.baf.bw");
    if (hardBaf != null) {
      companions.add(hardBaf);
    } else {
      addCompanion(companions, byName, stem, ".baf.bw");
    }
    addCompanion(companions, byName, stem, ".seg.bw");
    return companions;
  }

  /**
   * Secondary CNV BigWigs shown unchecked in the dialog: tn, target counts,
   * improper pairs, baf.seg.
   */
  private static List<File> resolveOptionalCnvBigWigs(
      Map<String, File> byName, String stem, List<File> defaultCompanions) {
    Set<String> claimed = new LinkedHashSet<>();
    for (File file : defaultCompanions) {
      claimed.add(file.getName());
    }
    List<File> optional = new ArrayList<>();
    addOptional(optional, claimed, byName, stem, ".tn.bw");
    addOptional(optional, claimed, byName, stem, ".tumor.target.counts.bw");
    addOptional(optional, claimed, byName, stem, ".target.counts.bw");
    addOptional(optional, claimed, byName, stem, ".tumor.improper.pairs.bw");
    addOptional(optional, claimed, byName, stem, ".improper.pairs.bw");
    addOptional(optional, claimed, byName, stem, ".baf.seg.bw");
    return optional;
  }

  private static void addOptional(
      List<File> optional,
      Set<String> claimed,
      Map<String, File> byName,
      String stem,
      String suffix) {
    File file = findBySuffix(byName, stem, suffix);
    if (file != null && claimed.add(file.getName())) {
      optional.add(file);
    }
  }

  private static void addCompanion(
      List<File> companions, Map<String, File> byName, String stem, String suffix) {
    File file = findBySuffix(byName, stem, suffix);
    if (file != null) {
      companions.add(file);
    }
  }

  private static List<File> resolveMultimodalBigWigs(
      Map<String, File> byName, Set<String> claimedNames) {
    List<File> extras = new ArrayList<>();
    for (Map.Entry<String, File> entry : byName.entrySet()) {
      String name = entry.getKey();
      String lower = name.toLowerCase(Locale.ROOT);
      if (!(lower.endsWith(".bw") || lower.endsWith(".bigwig"))) {
        continue;
      }
      if (claimedNames.contains(name)) {
        continue;
      }
      extras.add(entry.getValue());
    }
    extras.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
    return extras;
  }

  private static Map<DragenAnalysisKind, String> buildPreviews(
      File hardFiltered,
      File cnvVcf,
      File svVcf,
      File normalBam,
      File tumorBam,
      List<File> cnvCompanions,
      List<File> multimodal) {
    Map<DragenAnalysisKind, String> previews = new EnumMap<>(DragenAnalysisKind.class);
    previews.put(
        DragenAnalysisKind.GERMLINE_VARIANTS,
        joinPreview(hardFiltered, normalBam));
    previews.put(
        DragenAnalysisKind.SOMATIC_POINT_MUTATIONS,
        joinPreview(hardFiltered, tumorBam, normalBam));
    List<File> cnvFiles = new ArrayList<>();
    if (cnvVcf != null) {
      cnvFiles.add(cnvVcf);
    }
    if (tumorBam != null) {
      cnvFiles.add(tumorBam);
    } else if (normalBam != null) {
      cnvFiles.add(normalBam);
    }
    cnvFiles.addAll(cnvCompanions);
    previews.put(DragenAnalysisKind.CNV, joinPreview(cnvFiles.toArray(File[]::new)));
    previews.put(
        DragenAnalysisKind.SV,
        joinPreview(svVcf, tumorBam != null ? tumorBam : normalBam));
    previews.put(
        DragenAnalysisKind.MULTIMODAL,
        joinPreview(multimodal.toArray(File[]::new)));
    return previews;
  }

  private static String joinPreview(File... files) {
    List<String> names = new ArrayList<>();
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    if (files != null) {
      for (File file : files) {
        if (file == null) {
          continue;
        }
        if (seen.add(file.getName())) {
          names.add(file.getName());
        }
      }
    }
    if (names.isEmpty()) {
      return "";
    }
    if (names.size() <= 3) {
      return String.join(" + ", names);
    }
    return names.get(0) + " + " + names.get(1) + " + " + (names.size() - 2) + " more";
  }

  private static void addIfPresent(Set<File> set, File file) {
    if (file != null) {
      set.add(file);
    }
  }

  private static void addPreferredBam(Set<File> bams, DragenFolderScan scan) {
    if (scan.tumorBam() != null) {
      bams.add(scan.tumorBam());
    } else if (scan.normalBam() != null) {
      bams.add(scan.normalBam());
    }
  }

  private static DragenFolderScan emptyScan(File directory) {
    return new DragenFolderScan(
        directory,
        "",
        false,
        EnumSet.noneOf(DragenAnalysisKind.class),
        null,
        null,
        null,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        Map.of());
  }
}
