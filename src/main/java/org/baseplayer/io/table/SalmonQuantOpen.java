package org.baseplayer.io.table;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.baseplayer.analysis.GeneOverlapIndex;
import org.baseplayer.annotation.AnnotationLoader;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.utils.ChromosomeNames;

/**
 * Loads one or more Salmon {@code quant.sf} files into the same compact gene-matrix
 * shape as {@link MatrixTableOpen}: shared loci + per-sample {@link MappedTableData}
 * with TPM (VALUE) and NumReads (SCORE).
 *
 * <p>After the first open, a {@link LociTemplate} caches ENSG→locus indexes on the
 * shared {@link SharedMatrixLoci}. Later opens reuse that template (no annotation
 * resolve) and can restrict value fills to the current chromosome.
 */
public final class SalmonQuantOpen {

  /** SharedMatrixLoci identity → template (cleared with sample wipe / genome change). */
  private static final Map<SharedMatrixLoci, LociTemplate> TEMPLATES =
      Collections.synchronizedMap(new IdentityHashMap<>());

  private SalmonQuantOpen() {}

  /**
   * Gene-matrix skeleton from a prior Salmon open: same {@link SharedMatrixLoci}
   * instance plus raw id → locus index for fast refill.
   */
  public static final class LociTemplate {
    private final SharedMatrixLoci loci;
    /** Lower-cased ENSG / symbol → locus index. */
    private final Map<String, Integer> keyToIndex;
    /** Chrom key → raw keys (lower-cased) that land on that chrom. */
    private final Map<String, Set<String>> keysByChrom;

    LociTemplate(
        SharedMatrixLoci loci,
        Map<String, Integer> keyToIndex,
        Map<String, Set<String>> keysByChrom) {
      this.loci = loci;
      this.keyToIndex = Map.copyOf(keyToIndex);
      Map<String, Set<String>> byChrom = new HashMap<>();
      for (Map.Entry<String, Set<String>> e : keysByChrom.entrySet()) {
        byChrom.put(e.getKey(), Set.copyOf(e.getValue()));
      }
      this.keysByChrom = Map.copyOf(byChrom);
    }

    public SharedMatrixLoci loci() {
      return loci;
    }

    public int size() {
      return loci.size();
    }

    int indexOf(String rawId) {
      if (rawId == null || rawId.isBlank()) {
        return -1;
      }
      String key = rawId.trim().toLowerCase(Locale.ROOT);
      Integer idx = keyToIndex.get(key);
      if (idx != null) {
        return idx;
      }
      String versionless = AnnotationLoader.stripEnsemblVersion(key);
      if (!versionless.equals(key)) {
        Integer v = keyToIndex.get(versionless);
        return v != null ? v : -1;
      }
      return -1;
    }

    /** Lower-cased raw ids known to resolve onto {@code chrom}, or empty. */
    Set<String> keysOnChrom(String chrom) {
      if (chrom == null || chrom.isBlank()) {
        return Set.of();
      }
      Set<String> keys = keysByChrom.get(ChromosomeNames.key(chrom));
      return keys != null ? keys : Set.of();
    }
  }

  public record SalmonOpenResult(
      List<MappedTableData> tables,
      int geneCount,
      int unresolvedKeys,
      String summary) {}

  /** True when the path looks like a Salmon quantification table. */
  public static boolean isSalmonQuantPath(Path path) {
    if (path == null || path.getFileName() == null) {
      return false;
    }
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".gz")) {
      name = name.substring(0, name.length() - 3);
    }
    return name.endsWith(".sf");
  }

  /** Drop cached templates (sample wipe / new genome). */
  public static void clearTemplates() {
    TEMPLATES.clear();
  }

  /** Template registered for this loci instance, if any. */
  public static LociTemplate templateFor(SharedMatrixLoci loci) {
    return loci != null ? TEMPLATES.get(loci) : null;
  }

  public static SalmonOpenResult load(
      List<Path> paths,
      Function<String, GeneLocation> geneResolver) throws IOException {
    return load(paths, geneResolver, null, null);
  }

  /**
   * @param reuse when non-null, skip ENSG resolve and fill into that shared loci
   * @param chromFilter when non-null with {@code reuse}, only populate genes on that
   *     chromosome (other loci stay {@link Float#NaN})
   */
  public static SalmonOpenResult load(
      List<Path> paths,
      Function<String, GeneLocation> geneResolver,
      LociTemplate reuse,
      String chromFilter) throws IOException {
    if (paths == null || paths.isEmpty()) {
      return new SalmonOpenResult(List.of(), 0, 0, "No Salmon files");
    }
    List<Path> files = new ArrayList<>();
    for (Path p : paths) {
      if (p != null) {
        files.add(p);
      }
    }
    if (files.isEmpty()) {
      return new SalmonOpenResult(List.of(), 0, 0, "No Salmon files");
    }
    if (reuse != null) {
      return loadOntoTemplate(files, reuse, chromFilter);
    }
    return loadFresh(files, geneResolver);
  }

  private static SalmonOpenResult loadFresh(
      List<Path> files,
      Function<String, GeneLocation> geneResolver) throws IOException {
    LinkedHashMap<String, float[]> tpmByGene = new LinkedHashMap<>();
    LinkedHashMap<String, float[]> readsByGene = new LinkedHashMap<>();
    String[] sampleNames = new String[files.size()];

    for (int s = 0; s < files.size(); s++) {
      Path path = files.get(s);
      sampleNames[s] = sampleName(path);
      ParsedSf parsed = parseOne(path, null);
      for (Map.Entry<String, float[]> e : parsed.values.entrySet()) {
        String gene = e.getKey();
        float[] pair = e.getValue();
        float[] tpms = tpmByGene.get(gene);
        float[] reads = readsByGene.get(gene);
        if (tpms == null) {
          tpms = new float[files.size()];
          reads = new float[files.size()];
          java.util.Arrays.fill(tpms, Float.NaN);
          java.util.Arrays.fill(reads, Float.NaN);
          tpmByGene.put(gene, tpms);
          readsByGene.put(gene, reads);
        }
        tpms[s] = pair[0];
        reads[s] = pair[1];
      }
    }

    Map<String, ResolvedGene> resolveCache = new HashMap<>();
    List<String> geneOrder = new ArrayList<>(tpmByGene.keySet());
    List<String> chrom = new ArrayList<>();
    List<Long> starts = new ArrayList<>();
    List<Long> ends = new ArrayList<>();
    List<String> unitIds = new ArrayList<>();
    List<float[]> tpmRows = new ArrayList<>();
    List<float[]> readRows = new ArrayList<>();
    List<String> rawIdsKept = new ArrayList<>();
    int unresolved = 0;

    for (String geneId : geneOrder) {
      ResolvedGene resolved = resolveCache.computeIfAbsent(
          geneId, id -> resolveGene(id, geneResolver));
      if (resolved == null) {
        unresolved++;
        continue;
      }
      chrom.add(resolved.chrom);
      starts.add(resolved.start);
      ends.add(resolved.end);
      unitIds.add(resolved.unitId);
      tpmRows.add(tpmByGene.get(geneId));
      readRows.add(readsByGene.get(geneId));
      rawIdsKept.add(geneId);
    }

    if (unitIds.isEmpty()) {
      return new SalmonOpenResult(
          List.of(),
          0,
          unresolved,
          "No genes resolved from " + files.size() + " Salmon file(s)");
    }

    int nLoci = unitIds.size();
    String[] chromArr = chrom.toArray(String[]::new);
    long[] startArr = new long[nLoci];
    long[] endArr = new long[nLoci];
    for (int i = 0; i < nLoci; i++) {
      startArr[i] = starts.get(i);
      endArr[i] = ends.get(i);
    }
    SharedMatrixLoci loci = new SharedMatrixLoci(
        chromArr, startArr, endArr, unitIds.toArray(String[]::new));

    Map<String, Integer> keyToIndex = new HashMap<>();
    Map<String, Set<String>> keysByChrom = new HashMap<>();
    for (int i = 0; i < nLoci; i++) {
      String raw = rawIdsKept.get(i);
      putKey(keyToIndex, raw, i);
      putKey(keyToIndex, unitIds.get(i), i);
      String versionless = AnnotationLoader.stripEnsemblVersion(raw);
      if (!versionless.equalsIgnoreCase(raw)) {
        putKey(keyToIndex, versionless, i);
      }
      String chromKey = ChromosomeNames.key(chromArr[i]);
      keysByChrom
          .computeIfAbsent(chromKey, k -> new HashSet<>())
          .add(raw.trim().toLowerCase(Locale.ROOT));
      if (!versionless.equalsIgnoreCase(raw)) {
        keysByChrom.get(chromKey).add(versionless.toLowerCase(Locale.ROOT));
      }
    }
    LociTemplate template = new LociTemplate(loci, keyToIndex, keysByChrom);
    TEMPLATES.put(loci, template);

    List<MappedTableData> tables = materializeSamples(files, sampleNames, loci, tpmRows, readRows);
    String summary = String.format(
        Locale.ROOT,
        "Salmon quant.sf: %d genes × %d samples (%d unresolved keys)",
        nLoci,
        tables.size(),
        unresolved);
    return new SalmonOpenResult(tables, nLoci, unresolved, summary);
  }

  /**
   * Fast path: parse TPM/NumReads into an existing shared loci (no annotation).
   * When {@code chromFilter} is set, only genes on that chrom get values.
   */
  private static SalmonOpenResult loadOntoTemplate(
      List<Path> files,
      LociTemplate template,
      String chromFilter) throws IOException {
    SharedMatrixLoci loci = template.loci();
    int nLoci = loci.size();
    Set<String> allowedKeys = null;
    String filterNote = "";
    if (chromFilter != null && !chromFilter.isBlank()) {
      allowedKeys = template.keysOnChrom(chromFilter);
      filterNote = " [chrom " + ChromosomeNames.strip(chromFilter) + " only]";
    }

    MappedTableColumnMapping mapping = syntheticTpmNumReadsMapping();
    String[] headers = { "chrom", "start", "end", "TPM", "NumReads" };
    List<MappedTableData> tables = new ArrayList<>(files.size());
    String[] sampleNames = new String[files.size()];

    for (int s = 0; s < files.size(); s++) {
      Path path = files.get(s);
      sampleNames[s] = sampleName(path);
      float[] tpm = new float[nLoci];
      float[] reads = new float[nLoci];
      java.util.Arrays.fill(tpm, Float.NaN);
      java.util.Arrays.fill(reads, Float.NaN);

      ParsedSf parsed = parseOne(path, allowedKeys);
      int hit = 0;
      for (Map.Entry<String, float[]> e : parsed.values.entrySet()) {
        int idx = template.indexOf(e.getKey());
        if (idx < 0) {
          continue;
        }
        float[] pair = e.getValue();
        tpm[idx] = pair[0];
        reads[idx] = pair[1];
        hit++;
      }
      tables.add(MappedTableData.geneMatrixSample(
          path, sampleNames[s], mapping, headers, loci, tpm, reads));
      if (hit == 0 && allowedKeys != null && !allowedKeys.isEmpty()) {
        // Still attach empty-on-chrom sample so track exists; values NaN.
      }
    }

    String summary = String.format(
        Locale.ROOT,
        "Salmon quant.sf (reuse loci): %d genes × %d samples%s",
        nLoci,
        tables.size(),
        filterNote);
    return new SalmonOpenResult(tables, nLoci, 0, summary);
  }

  private static List<MappedTableData> materializeSamples(
      List<Path> files,
      String[] sampleNames,
      SharedMatrixLoci loci,
      List<float[]> tpmRows,
      List<float[]> readRows) {
    MappedTableColumnMapping mapping = syntheticTpmNumReadsMapping();
    String[] headers = { "chrom", "start", "end", "TPM", "NumReads" };
    int nLoci = loci.size();
    List<MappedTableData> tables = new ArrayList<>(sampleNames.length);
    for (int s = 0; s < sampleNames.length; s++) {
      float[] tpm = new float[nLoci];
      float[] reads = new float[nLoci];
      for (int i = 0; i < nLoci; i++) {
        tpm[i] = tpmRows.get(i)[s];
        reads[i] = readRows.get(i)[s];
      }
      tables.add(MappedTableData.geneMatrixSample(
          files.get(s), sampleNames[s], mapping, headers, loci, tpm, reads));
    }
    return tables;
  }

  private static void putKey(Map<String, Integer> map, String raw, int index) {
    if (raw == null || raw.isBlank()) {
      return;
    }
    map.putIfAbsent(raw.trim().toLowerCase(Locale.ROOT), index);
  }

  private record ParsedSf(Map<String, float[]> values) {}

  /**
   * @param onlyKeys lower-cased ids to keep, or null to keep all finite rows
   */
  private static ParsedSf parseOne(Path path, Set<String> onlyKeys) throws IOException {
    Map<String, float[]> values = new HashMap<>();
    try (BufferedReader reader = MappedTableReader.openTextReader(path)) {
      String header = nextNonComment(reader);
      if (header == null) {
        return new ParsedSf(values);
      }
      String[] cols = header.split("\t", -1);
      int nameIdx = indexOf(cols, "Name");
      int tpmIdx = indexOf(cols, "TPM");
      int readsIdx = indexOf(cols, "NumReads");
      if (nameIdx < 0 || tpmIdx < 0 || readsIdx < 0) {
        if (cols.length >= 5) {
          nameIdx = 0;
          tpmIdx = 3;
          readsIdx = 4;
        } else {
          throw new IOException(
              "Not a Salmon quant.sf (need Name/TPM/NumReads): " + path.getFileName());
        }
      }
      String line;
      while ((line = nextNonComment(reader)) != null) {
        String[] cells = line.split("\t", -1);
        String name = cell(cells, nameIdx);
        if (name == null || name.isBlank()) {
          continue;
        }
        String trimmed = name.trim();
        if (onlyKeys != null) {
          String lower = trimmed.toLowerCase(Locale.ROOT);
          if (!onlyKeys.contains(lower)
              && !onlyKeys.contains(AnnotationLoader.stripEnsemblVersion(lower))) {
            continue;
          }
        }
        float tpm = parseFloat(cell(cells, tpmIdx));
        float reads = parseFloat(cell(cells, readsIdx));
        if (!Float.isFinite(tpm) && !Float.isFinite(reads)) {
          continue;
        }
        values.put(trimmed, new float[] { tpm, reads });
      }
    }
    return new ParsedSf(values);
  }

  private record ResolvedGene(String unitId, String chrom, long start, long end) {}

  private static ResolvedGene resolveGene(
      String geneId, Function<String, GeneLocation> geneResolver) {
    if (geneId == null || geneId.isBlank() || geneResolver == null) {
      return null;
    }
    String trimmed = geneId.trim();
    GeneLocation loc = geneResolver.apply(trimmed);
    if (loc == null || loc.chrom() == null) {
      String versionless = AnnotationLoader.stripEnsemblVersion(trimmed);
      if (!versionless.equalsIgnoreCase(trimmed)) {
        loc = geneResolver.apply(versionless);
      }
    }
    if (loc == null || loc.chrom() == null) {
      return null;
    }
    String unitId = trimmed;
    String chrom = ChromosomeNames.strip(loc.chrom());
    long start = loc.start();
    long end = loc.end();
    Gene gene = GeneOverlapIndex.resolveByName(trimmed);
    if (gene == null) {
      gene = GeneOverlapIndex.resolveByName(AnnotationLoader.stripEnsemblVersion(trimmed));
    }
    if (gene != null && gene.name() != null && !gene.name().isBlank()) {
      unitId = gene.name();
      chrom = ChromosomeNames.strip(gene.chrom());
      start = gene.start();
      end = gene.end();
    }
    return new ResolvedGene(unitId, chrom, start, end);
  }

  static MappedTableColumnMapping syntheticTpmNumReadsMapping() {
    Map<MappedTableColumnRole, Integer> indexes = new EnumMap<>(MappedTableColumnRole.class);
    indexes.put(MappedTableColumnRole.CHROM, 0);
    indexes.put(MappedTableColumnRole.START, 1);
    indexes.put(MappedTableColumnRole.END, 2);
    indexes.put(MappedTableColumnRole.VALUE, 3);
    indexes.put(MappedTableColumnRole.SCORE, 4);
    return new MappedTableColumnMapping(indexes, false, true);
  }

  static String sampleName(Path path) {
    String stem = MappedTableReader.fileStem(path);
    if (stem == null || stem.isBlank()) {
      return "sample";
    }
    if ("quant".equalsIgnoreCase(stem) && path.getParent() != null
        && path.getParent().getFileName() != null) {
      return path.getParent().getFileName().toString();
    }
    return stem;
  }

  private static String nextNonComment(BufferedReader reader) throws IOException {
    String line;
    while ((line = reader.readLine()) != null) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
        continue;
      }
      return line;
    }
    return null;
  }

  private static int indexOf(String[] headers, String want) {
    if (headers == null || want == null) {
      return -1;
    }
    for (int i = 0; i < headers.length; i++) {
      if (want.equalsIgnoreCase(headers[i] != null ? headers[i].trim() : "")) {
        return i;
      }
    }
    return -1;
  }

  private static String cell(String[] cols, int index) {
    if (cols == null || index < 0 || index >= cols.length) {
      return null;
    }
    return cols[index];
  }

  private static float parseFloat(String raw) {
    if (raw == null || raw.isBlank() || ".".equals(raw.trim())
        || "NA".equalsIgnoreCase(raw.trim())) {
      return Float.NaN;
    }
    try {
      return Float.parseFloat(raw.trim());
    } catch (NumberFormatException ex) {
      return Float.NaN;
    }
  }
}
