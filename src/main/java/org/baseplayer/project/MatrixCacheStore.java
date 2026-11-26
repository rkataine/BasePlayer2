package org.baseplayer.project;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.components.MatrixSampleMappingDialog;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.io.table.MappedTableColumnMapping;
import org.baseplayer.io.table.MappedTableColumnRole;
import org.baseplayer.io.table.MappedTableData;
import org.baseplayer.io.table.MatrixKeyMode;
import org.baseplayer.io.table.MatrixTableOpen;
import org.baseplayer.io.table.SharedMatrixLoci;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Session sidecar for compact gene/region matrices and Salmon tables under
 * {@code <projectStem>.bpcache/matrix-*.bpmx.zst}. Values are not stored in the
 * project JSON — only paths + open options live there.
 */
public final class MatrixCacheStore {

  public static final int SCHEMA_VERSION = 1;
  private static final int MAGIC = 0x42504D58; // "BPMX"
  private static final String INDEX_FILE = "matrices.json";
  private static final String SUFFIX = ".bpmx.zst";

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private MatrixCacheStore() {}

  public static Path cacheDirectory(Path projectFile) {
    return VariantCacheStore.cacheDirectory(projectFile);
  }

  /** Write all compact in-memory matrices next to the project. */
  public static void writeSessionCache(Path projectFile) throws IOException {
    if (projectFile == null) {
      return;
    }
    Path dir = cacheDirectory(projectFile);
    Files.createDirectories(dir);

    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    Map<SharedMatrixLoci, List<MappedTableData>> byLoci = new IdentityHashMap<>();
    if (registry != null) {
      for (SampleTrack track : registry.getSampleTracks()) {
        if (track == null) {
          continue;
        }
        for (Sample sample : track.getSamples()) {
          if (sample == null || sample.getDataType() != Sample.DataType.TABLE) {
            continue;
          }
          MappedTableData data = sample.getMappedTable();
          if (data == null || data.sharedLoci() == null || data.compactValues() == null) {
            continue;
          }
          byLoci.computeIfAbsent(data.sharedLoci(), k -> new ArrayList<>()).add(data);
        }
      }
    }

    MatrixIndex index = new MatrixIndex();
    index.schemaVersion = SCHEMA_VERSION;
    List<String> writtenFiles = new ArrayList<>();

    int total = Math.max(1, byLoci.size());
    org.baseplayer.services.LoadingManager.get().setProgress(0, total);
    int step = 0;
    for (Map.Entry<SharedMatrixLoci, List<MappedTableData>> entry : byLoci.entrySet()) {
      SharedMatrixLoci loci = entry.getKey();
      List<MappedTableData> samples = entry.getValue();
      if (loci == null || samples == null || samples.isEmpty()) {
        continue;
      }
      MatrixArtifactSpec spec = buildArtifactSpec(samples);
      if (spec == null) {
        continue;
      }
      String fileName = "matrix-" + shortHash(spec.sourceKey) + SUFFIX;
      Path out = dir.resolve(fileName);
      writeArtifact(out, loci, samples, spec);
      writtenFiles.add(fileName);
      index.artifacts.add(spec);
      spec.fileName = fileName;
      step++;
      org.baseplayer.services.LoadingManager.get().setProgress(step, total);
    }

    removeStale(dir, writtenFiles);

    try (Writer writer = Files.newBufferedWriter(dir.resolve(INDEX_FILE), StandardCharsets.UTF_8)) {
      GSON.toJson(index, writer);
    }
  }

  /**
   * Try to install a wide matrix from cache. Returns true when attached without
   * re-parsing the source TSV.
   */
  public static boolean tryInstallMatrix(
      Path projectFile,
      Path matrixPath,
      MatrixTableOpen.MatrixOpenSpec openSpec,
      List<String> warnings) {
    if (projectFile == null || matrixPath == null) {
      return false;
    }
    String key = matrixPath.toAbsolutePath().normalize().toString();
    return installBySourceKey(projectFile, key, openSpec, warnings);
  }

  /**
   * Try to install a Salmon multi-file set from cache. Source key is a stable
   * join of absolute paths.
   */
  public static boolean tryInstallSalmon(
      Path projectFile, List<Path> salmonPaths, List<String> warnings) {
    if (projectFile == null || salmonPaths == null || salmonPaths.isEmpty()) {
      return false;
    }
    List<String> abs = new ArrayList<>();
    for (Path p : salmonPaths) {
      if (p != null) {
        abs.add(p.toAbsolutePath().normalize().toString());
      }
    }
    abs.sort(String::compareToIgnoreCase);
    String key = "salmon:" + String.join("|", abs);
    MatrixTableOpen.MatrixOpenSpec openSpec = new MatrixTableOpen.MatrixOpenSpec(
        MatrixKeyMode.GENE_ID, true, false, 0, 1, MatrixSampleMappingDialog.Policy.MERGE);
    return installBySourceKey(projectFile, key, openSpec, warnings);
  }

  private static boolean installBySourceKey(
      Path projectFile,
      String sourceKey,
      MatrixTableOpen.MatrixOpenSpec openSpec,
      List<String> warnings) {
    Path dir = cacheDirectory(projectFile);
    Path indexPath = dir.resolve(INDEX_FILE);
    if (!Files.isRegularFile(indexPath)) {
      return false;
    }
    MatrixIndex index;
    try (Reader reader = Files.newBufferedReader(indexPath, StandardCharsets.UTF_8)) {
      index = GSON.fromJson(reader, MatrixIndex.class);
    } catch (Exception e) {
      if (warnings != null) {
        warnings.add("Failed to read matrix cache index: " + e.getMessage());
      }
      return false;
    }
    if (index == null || index.schemaVersion != SCHEMA_VERSION || index.artifacts == null) {
      return false;
    }
    MatrixArtifactSpec match = null;
    for (MatrixArtifactSpec art : index.artifacts) {
      if (art != null && sourceKey.equals(art.sourceKey)) {
        match = art;
        break;
      }
    }
    if (match == null || match.fileName == null) {
      return false;
    }
    if (!fingerprintsMatch(match)) {
      if (warnings != null) {
        warnings.add("Matrix cache fingerprint mismatch; re-parsing "
            + (match.label != null ? match.label : sourceKey));
      }
      return false;
    }
    Path file = dir.resolve(match.fileName);
    if (!Files.isRegularFile(file)) {
      if (warnings != null) {
        warnings.add("Missing matrix cache file: " + match.fileName);
      }
      return false;
    }
    try {
      CachedMatrix cached = readArtifact(file);
      if (cached == null || cached.tables == null || cached.tables.isEmpty()) {
        return false;
      }
      MatrixSampleMappingDialog.Policy policy = openSpec != null
          ? openSpec.mappingPolicyOrMerge()
          : MatrixSampleMappingDialog.Policy.MERGE;
      Path attachPath = match.sourcePath != null
          ? Path.of(match.sourcePath)
          : projectFile;
      boolean ok = SampleDataManager.installCompactMatrixTables(
          attachPath, cached.tables, policy, openSpec, warnings);
      return ok;
    } catch (Exception e) {
      boolean deleted = false;
      try {
        deleted = Files.deleteIfExists(file);
      } catch (IOException ignored) {
        // keep warning below
      }
      if (warnings != null) {
        warnings.add(
            "Failed to read matrix cache "
                + match.fileName
                + ": "
                + e.getMessage()
                + (deleted ? " (corrupt file removed)" : ""));
      }
      return false;
    }
  }

  private static MatrixArtifactSpec buildArtifactSpec(List<MappedTableData> samples) {
    if (samples == null || samples.isEmpty()) {
      return null;
    }
    // Prefer OpenedMatrixRegistry path when sample paths look like matrix#col.
    for (OpenedMatrixRegistry.Entry entry : OpenedMatrixRegistry.get().entries()) {
      if (entry == null || entry.path() == null) {
        continue;
      }
      String fileName = entry.path().getFileName() != null
          ? entry.path().getFileName().toString()
          : null;
      if (fileName == null) {
        continue;
      }
      String marker = fileName + "#";
      for (MappedTableData data : samples) {
        Path p = data.getPath();
        if (p != null && p.getFileName() != null
            && p.getFileName().toString().startsWith(marker)) {
          MatrixArtifactSpec spec = new MatrixArtifactSpec();
          spec.kind = "matrix";
          spec.sourcePath = entry.path().toAbsolutePath().normalize().toString();
          spec.sourceKey = spec.sourcePath;
          spec.label = fileName;
          spec.openMode = entry.spec() != null && entry.spec().mode() != null
              ? entry.spec().mode().name()
              : MatrixKeyMode.GENE_ID.name();
          spec.headerRow = entry.spec() == null || entry.spec().headerRow();
          spec.startZeroBased = entry.spec() != null && entry.spec().startZeroBased();
          spec.keyColumnStart = entry.spec() != null ? entry.spec().keyColumnStart() : 0;
          spec.firstSampleColumn = entry.spec() != null ? entry.spec().firstSampleColumn() : 1;
          spec.sampleMappingPolicy = entry.spec() != null && entry.spec().sampleMappingPolicy() != null
              ? entry.spec().sampleMappingPolicy().name()
              : MatrixSampleMappingDialog.Policy.MERGE.name();
          spec.fingerprints = List.of(FileFingerprint.from(entry.path()));
          return spec;
        }
      }
    }

    // Salmon / standalone compact tables: key by sorted absolute sample paths.
    List<String> paths = new ArrayList<>();
    for (MappedTableData data : samples) {
      if (data.getPath() != null) {
        paths.add(data.getPath().toAbsolutePath().normalize().toString());
      }
    }
    if (paths.isEmpty()) {
      return null;
    }
    paths.sort(String::compareToIgnoreCase);
    MatrixArtifactSpec spec = new MatrixArtifactSpec();
    boolean salmon = paths.stream().anyMatch(p -> p.endsWith(".sf") || p.contains("quant.sf"));
    spec.kind = salmon ? "salmon" : "table";
    spec.sourceKey = salmon ? "salmon:" + String.join("|", paths) : paths.get(0);
    spec.sourcePath = paths.get(0);
    spec.label = salmon ? "salmon-" + samples.size() : Path.of(paths.get(0)).getFileName().toString();
    spec.openMode = MatrixKeyMode.GENE_ID.name();
    spec.headerRow = true;
    spec.sampleMappingPolicy = MatrixSampleMappingDialog.Policy.MERGE.name();
    List<FileFingerprint> fps = new ArrayList<>();
    for (String p : paths) {
      fps.add(FileFingerprint.from(Path.of(p)));
    }
    spec.fingerprints = fps;
    return spec;
  }

  private static void writeArtifact(
      Path file,
      SharedMatrixLoci loci,
      List<MappedTableData> samples,
      MatrixArtifactSpec spec) throws IOException {
    try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(file));
         ZstdOutputStream zstd = new ZstdOutputStream(raw);
         DataOutputStream out = new DataOutputStream(zstd)) {
      out.writeInt(MAGIC);
      out.writeInt(SCHEMA_VERSION);
      writeString(out, spec != null ? spec.kind : "matrix");
      writeString(out, spec != null ? spec.sourceKey : "");
      writeString(out, spec != null ? spec.sourcePath : "");

      int n = loci.size();
      out.writeInt(n);
      boolean hasUnitIds = loci.hasUnitIds();
      out.writeBoolean(hasUnitIds);
      for (int i = 0; i < n; i++) {
        writeString(out, loci.chrom(i));
        out.writeLong(loci.start1Based(i));
        out.writeLong(loci.end1Based(i));
        if (hasUnitIds) {
          writeNullableString(out, loci.unitId(i));
        }
      }

      out.writeInt(samples.size());
      for (MappedTableData data : samples) {
        writeString(out, data.getName() != null ? data.getName() : "");
        writeString(out, data.getPath() != null
            ? data.getPath().toAbsolutePath().normalize().toString()
            : "");
        boolean hasSecondary = data.hasCompactSecondary();
        out.writeBoolean(hasSecondary);
        float[] primary = data.compactValues();
        writeFloatArray(out, primary);
        if (hasSecondary) {
          writeFloatArray(out, data.compactSecondary());
        }
      }
    }
  }

  private static CachedMatrix readArtifact(Path file) throws IOException {
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(file));
         ZstdInputStream zstd = new ZstdInputStream(raw);
         DataInputStream in = new DataInputStream(zstd)) {
      int magic = in.readInt();
      if (magic != MAGIC) {
        throw new IOException("Bad matrix cache magic");
      }
      int version = in.readInt();
      if (version != SCHEMA_VERSION) {
        throw new IOException("Unsupported matrix cache version: " + version);
      }
      readString(in); // kind
      readString(in); // sourceKey
      readString(in); // sourcePath

      int n = in.readInt();
      if (n < 0 || n > 50_000_000) {
        throw new IOException("Invalid locus count: " + n);
      }
      boolean hasUnitIds = in.readBoolean();
      String[] chrom = new String[n];
      long[] starts = new long[n];
      long[] ends = new long[n];
      String[] unitIds = hasUnitIds ? new String[n] : null;
      for (int i = 0; i < n; i++) {
        chrom[i] = readString(in);
        starts[i] = in.readLong();
        ends[i] = in.readLong();
        if (hasUnitIds) {
          unitIds[i] = readNullableString(in);
        }
      }
      SharedMatrixLoci loci = new SharedMatrixLoci(chrom, starts, ends, unitIds);

      int sampleCount = in.readInt();
      if (sampleCount < 0 || sampleCount > 100_000) {
        throw new IOException("Invalid sample count: " + sampleCount);
      }
      MappedTableColumnMapping mapping = syntheticValueMapping();
      String[] headers = { "chrom", "start", "end", "value" };
      List<MappedTableData> tables = new ArrayList<>(sampleCount);
      for (int s = 0; s < sampleCount; s++) {
        String name = readString(in);
        String pathStr = readString(in);
        boolean hasSecondary = in.readBoolean();
        float[] primary = readFloatArray(in, n);
        float[] secondary = hasSecondary ? readFloatArray(in, n) : null;
        Path samplePath = pathStr != null && !pathStr.isBlank() ? Path.of(pathStr) : Path.of(name);
        if (hasSecondary) {
          MappedTableColumnMapping dual = syntheticTpmNumReadsMapping();
          String[] dualHeaders = { "chrom", "start", "end", "value", "score" };
          tables.add(MappedTableData.geneMatrixSample(
              samplePath, name, dual, dualHeaders, loci, primary, secondary));
        } else {
          tables.add(MappedTableData.geneMatrixSample(
              samplePath, name, mapping, headers, loci, primary));
        }
      }
      return new CachedMatrix(tables);
    }
  }

  private static MappedTableColumnMapping syntheticValueMapping() {
    Map<MappedTableColumnRole, Integer> indexes = new java.util.EnumMap<>(MappedTableColumnRole.class);
    indexes.put(MappedTableColumnRole.CHROM, 0);
    indexes.put(MappedTableColumnRole.START, 1);
    indexes.put(MappedTableColumnRole.END, 2);
    indexes.put(MappedTableColumnRole.VALUE, 3);
    return new MappedTableColumnMapping(indexes, false, true);
  }

  private static MappedTableColumnMapping syntheticTpmNumReadsMapping() {
    Map<MappedTableColumnRole, Integer> indexes = new java.util.EnumMap<>(MappedTableColumnRole.class);
    indexes.put(MappedTableColumnRole.CHROM, 0);
    indexes.put(MappedTableColumnRole.START, 1);
    indexes.put(MappedTableColumnRole.END, 2);
    indexes.put(MappedTableColumnRole.VALUE, 3);
    indexes.put(MappedTableColumnRole.SCORE, 4);
    return new MappedTableColumnMapping(indexes, false, true);
  }

  private static boolean fingerprintsMatch(MatrixArtifactSpec spec) {
    if (spec == null || spec.fingerprints == null || spec.fingerprints.isEmpty()) {
      return true;
    }
    for (FileFingerprint fp : spec.fingerprints) {
      if (fp == null || fp.path == null || fp.path.isBlank()) {
        continue;
      }
      Path path = Path.of(fp.path);
      if (!Files.isRegularFile(path)) {
        return false;
      }
      FileFingerprint now = FileFingerprint.from(path);
      if (fp.size != now.size || fp.mtime != now.mtime) {
        return false;
      }
    }
    return true;
  }

  private static void removeStale(Path dir, List<String> keepNames) throws IOException {
    if (!Files.isDirectory(dir)) {
      return;
    }
    java.util.Set<String> keep = new java.util.HashSet<>(keepNames);
    try (var stream = Files.list(dir)) {
      for (Path path : stream.toList()) {
        String name = path.getFileName() != null ? path.getFileName().toString() : "";
        if (name.endsWith(SUFFIX) && !keep.contains(name)) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static String shortHash(String text) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] dig = md.digest((text != null ? text : "").getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(dig, 0, 8);
    } catch (Exception e) {
      return Integer.toHexString(text != null ? text.hashCode() : 0);
    }
  }

  private static void writeFloatArray(DataOutputStream out, float[] values) throws IOException {
    if (values == null) {
      out.writeInt(0);
      return;
    }
    out.writeInt(values.length);
    for (float v : values) {
      out.writeFloat(v);
    }
  }

  private static float[] readFloatArray(DataInputStream in, int expected) throws IOException {
    int len = in.readInt();
    if (len != expected) {
      throw new IOException("float[] length " + len + " != loci " + expected);
    }
    float[] values = new float[len];
    for (int i = 0; i < len; i++) {
      values[i] = in.readFloat();
    }
    return values;
  }

  private static void writeString(DataOutputStream out, String value) throws IOException {
    byte[] bytes = (value != null ? value : "").getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static void writeNullableString(DataOutputStream out, String value) throws IOException {
    if (value == null) {
      out.writeBoolean(false);
      return;
    }
    out.writeBoolean(true);
    writeString(out, value);
  }

  private static String readString(DataInputStream in) throws IOException {
    int len = in.readInt();
    if (len < 0 || len > 50_000_000) {
      throw new IOException("Invalid string length: " + len);
    }
    byte[] bytes = in.readNBytes(len);
    if (bytes.length != len) {
      throw new IOException("Truncated string");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static String readNullableString(DataInputStream in) throws IOException {
    if (!in.readBoolean()) {
      return null;
    }
    return readString(in);
  }

  private record CachedMatrix(List<MappedTableData> tables) {}

  static final class MatrixIndex {
    int schemaVersion;
    List<MatrixArtifactSpec> artifacts = new ArrayList<>();
  }

  static final class MatrixArtifactSpec {
    String kind;
    String sourceKey;
    String sourcePath;
    String label;
    String fileName;
    String openMode;
    boolean headerRow = true;
    boolean startZeroBased;
    int keyColumnStart;
    int firstSampleColumn;
    String sampleMappingPolicy;
    List<FileFingerprint> fingerprints = new ArrayList<>();
  }

  static final class FileFingerprint {
    String path;
    long size;
    long mtime;

    static FileFingerprint from(Path file) {
      FileFingerprint fp = new FileFingerprint();
      if (file == null) {
        return fp;
      }
      Path abs = file.toAbsolutePath().normalize();
      fp.path = abs.toString();
      try {
        if (Files.isRegularFile(abs)) {
          fp.size = Files.size(abs);
          fp.mtime = Files.getLastModifiedTime(abs).toMillis();
        }
      } catch (IOException ignored) {
        // leave zeros
      }
      return fp;
    }
  }
}
