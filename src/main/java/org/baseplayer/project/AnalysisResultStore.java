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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.pattern.SamplePatternEngine;
import org.baseplayer.analysis.signal.SignalChannel;
import org.baseplayer.analysis.signal.SignalValueThresholds;
import org.baseplayer.analysis.signal.SignalValueType;
import org.baseplayer.io.table.MappedTableColumnRole;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Session sidecar for finished Analysis Manager results under
 * {@code <projectStem>.bpcache/analysis-*.bpan.zst}. Settings stay in the project
 * JSON; this store restores tables without re-running Annotate / DE / enrichment.
 */
public final class AnalysisResultStore {

  public static final int SCHEMA_VERSION = 1;
  private static final int MAGIC = 0x4250414E; // "BPAN"
  private static final String INDEX_FILE = "analyses.json";
  private static final String SUFFIX = ".bpan.zst";

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private AnalysisResultStore() {}

  public static Path cacheDirectory(Path projectFile) {
    return VariantCacheStore.cacheDirectory(projectFile);
  }

  public static void writeSessionCache(Path projectFile) throws IOException {
    if (projectFile == null) {
      return;
    }
    Path dir = cacheDirectory(projectFile);
    Files.createDirectories(dir);

    AnalysisSession session = AnalysisSession.get();
    AnalysisIndex index = new AnalysisIndex();
    index.schemaVersion = SCHEMA_VERSION;
    List<String> written = new ArrayList<>();

    String previous = session.getActiveSignalDatasetId();
    List<String> datasetIds = new ArrayList<>(session.signalSliceIds());
    if (previous != null && !datasetIds.contains(previous)) {
      datasetIds.add(previous);
    }

    int total = Math.max(1, datasetIds.size() + 2);
    org.baseplayer.services.LoadingManager.get().setProgress(0, total);
    int step = 0;

    for (String datasetId : datasetIds) {
      if (datasetId == null || datasetId.isBlank()) {
        continue;
      }
      session.setActiveSignalDatasetId(datasetId);
      GroupStatResult annotation = session.getRawAnnotationResult() != null
          ? session.getRawAnnotationResult()
          : session.getAnnotationResult();
      GroupStatResult de = session.getResult(AnalysisMode.SIGNAL);
      FeatureValueMatrix matrix = session.getMatrix(AnalysisMode.SIGNAL);
      FeatureValueBundle bundle = session.getSignalBundle();
      SamplePatternEngine.Result pattern = session.getLastPatternResult();
      if (annotation == null && de == null && matrix == null && bundle == null && pattern == null) {
        continue;
      }
      String fileName = "analysis-signal-" + safeName(datasetId) + SUFFIX;
      Path out = dir.resolve(fileName);
      writeSignalBlob(out, datasetId, annotation, de, matrix, bundle, pattern);
      written.add(fileName);
      AnalysisArtifactSpec spec = new AnalysisArtifactSpec();
      spec.kind = "signal";
      spec.datasetId = datasetId;
      spec.fileName = fileName;
      index.artifacts.add(spec);
      step++;
      org.baseplayer.services.LoadingManager.get().setProgress(step, total);
    }
    if (previous != null) {
      session.setActiveSignalDatasetId(previous);
    }

    for (AnalysisMode mode : List.of(AnalysisMode.MUTATIONS_POINT, AnalysisMode.MUTATIONS_SV)) {
      GroupStatResult result = session.getResult(mode);
      FeatureValueMatrix matrix = session.getMatrix(mode);
      if (result == null && matrix == null) {
        continue;
      }
      String fileName = "analysis-" + mode.name().toLowerCase() + SUFFIX;
      Path out = dir.resolve(fileName);
      writeMutationBlob(out, mode, result, matrix);
      written.add(fileName);
      AnalysisArtifactSpec spec = new AnalysisArtifactSpec();
      spec.kind = "mutation";
      spec.mode = mode.name();
      spec.fileName = fileName;
      index.artifacts.add(spec);
      step++;
      org.baseplayer.services.LoadingManager.get().setProgress(step, total);
    }

    removeStale(dir, written);
    try (Writer writer = Files.newBufferedWriter(dir.resolve(INDEX_FILE), StandardCharsets.UTF_8)) {
      GSON.toJson(index, writer);
    }
  }

  /**
   * Install cached analysis results into {@link AnalysisSession}. Call after
   * matrices and Signal datasets are rematched. Returns true when anything installed.
   */
  public static boolean installSessionCache(Path projectFile, List<String> warnings) {
    if (projectFile == null) {
      return false;
    }
    Path dir = cacheDirectory(projectFile);
    Path indexPath = dir.resolve(INDEX_FILE);
    if (!Files.isRegularFile(indexPath)) {
      return false;
    }
    AnalysisIndex index;
    try (Reader reader = Files.newBufferedReader(indexPath, StandardCharsets.UTF_8)) {
      index = GSON.fromJson(reader, AnalysisIndex.class);
    } catch (Exception e) {
      if (warnings != null) {
        warnings.add("Failed to read analysis cache index: " + e.getMessage());
      }
      return false;
    }
    if (index == null || index.schemaVersion != SCHEMA_VERSION || index.artifacts == null) {
      return false;
    }
    boolean any = false;
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    AnalysisSession session = AnalysisSession.get();
    String previous = session.getActiveSignalDatasetId();

    for (AnalysisArtifactSpec art : index.artifacts) {
      if (art == null || art.fileName == null) {
        continue;
      }
      Path file = dir.resolve(art.fileName);
      if (!Files.isRegularFile(file)) {
        if (warnings != null) {
          warnings.add("Missing analysis cache file: " + art.fileName);
        }
        continue;
      }
      try {
        if ("signal".equals(art.kind)) {
          any |= installSignalBlob(file, art.datasetId, session, registry);
        } else if ("mutation".equals(art.kind)) {
          any |= installMutationBlob(file, session, registry);
        }
      } catch (Exception e) {
        boolean deleted = false;
        try {
          deleted = Files.deleteIfExists(file);
        } catch (IOException ignored) {
          // keep warning
        }
        if (warnings != null) {
          warnings.add(
              "Failed to read analysis cache "
                  + art.fileName
                  + ": "
                  + e.getMessage()
                  + (deleted ? " (corrupt file removed)" : ""));
        }
      }
    }
    if (previous != null) {
      session.setActiveSignalDatasetId(previous);
    } else if (!session.signalSliceIds().isEmpty()) {
      String first = session.signalSliceIds().iterator().next();
      session.setActiveSignalDatasetId(first);
    }
    return any;
  }

  private static void writeSignalBlob(
      Path file,
      String datasetId,
      GroupStatResult annotation,
      GroupStatResult de,
      FeatureValueMatrix matrix,
      FeatureValueBundle bundle,
      SamplePatternEngine.Result pattern) throws IOException {
    try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(file));
         ZstdOutputStream zstd = new ZstdOutputStream(raw);
         DataOutputStream out = new DataOutputStream(zstd)) {
      out.writeInt(MAGIC);
      out.writeInt(SCHEMA_VERSION);
      writeString(out, "signal");
      writeString(out, datasetId != null ? datasetId : "");
      writeNullableResult(out, annotation);
      writeNullableResult(out, de);
      writeNullableMatrix(out, matrix);
      writeNullableBundle(out, bundle);
      writeNullablePattern(out, pattern);
    }
  }

  private static void writeMutationBlob(
      Path file,
      AnalysisMode mode,
      GroupStatResult result,
      FeatureValueMatrix matrix) throws IOException {
    try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(file));
         ZstdOutputStream zstd = new ZstdOutputStream(raw);
         DataOutputStream out = new DataOutputStream(zstd)) {
      out.writeInt(MAGIC);
      out.writeInt(SCHEMA_VERSION);
      writeString(out, "mutation");
      writeString(out, mode != null ? mode.name() : "");
      writeNullableResult(out, result);
      writeNullableMatrix(out, matrix);
    }
  }

  private static boolean installSignalBlob(
      Path file,
      String datasetId,
      AnalysisSession session,
      SampleRegistry registry) throws IOException {
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(file));
         ZstdInputStream zstd = new ZstdInputStream(raw);
         DataInputStream in = new DataInputStream(zstd)) {
      requireMagic(in);
      String kind = readString(in);
      if (!"signal".equals(kind)) {
        throw new IOException("Expected signal blob, got " + kind);
      }
      String id = readString(in);
      if (datasetId != null && !datasetId.isBlank()) {
        id = datasetId;
      }
      if (id == null || id.isBlank()) {
        return false;
      }
      session.ensureSignalSlice(id);
      session.setActiveSignalDatasetId(id);

      GroupStatResult annotation = readNullableResult(in);
      GroupStatResult de = readNullableResult(in);
      FeatureValueMatrix matrix = readNullableMatrix(in, registry);
      FeatureValueBundle bundle = readNullableBundle(in, registry);
      SamplePatternEngine.Result pattern = readNullablePattern(in, registry);

      FeatureValueMatrix annotateMatrix = matrix;
      if (annotateMatrix == null && bundle != null) {
        annotateMatrix = bundle.primaryMatrix();
      }
      if (annotation != null || annotateMatrix != null || bundle != null) {
        session.putAnnotation(
            AnalysisMode.SIGNAL,
            annotation,
            annotateMatrix != null ? annotateMatrix : new FeatureValueMatrix(),
            null,
            bundle);
      }
      if (de != null || matrix != null) {
        session.putResult(AnalysisMode.SIGNAL, de, matrix != null ? matrix : annotateMatrix, null);
      }
      if (pattern != null) {
        session.setLastPatternResult(pattern);
      }
      return annotation != null || de != null || matrix != null || bundle != null || pattern != null;
    }
  }

  private static boolean installMutationBlob(
      Path file, AnalysisSession session, SampleRegistry registry) throws IOException {
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(file));
         ZstdInputStream zstd = new ZstdInputStream(raw);
         DataInputStream in = new DataInputStream(zstd)) {
      requireMagic(in);
      String kind = readString(in);
      if (!"mutation".equals(kind)) {
        throw new IOException("Expected mutation blob, got " + kind);
      }
      AnalysisMode mode = AnalysisMode.fromPersisted(readString(in));
      GroupStatResult result = readNullableResult(in);
      FeatureValueMatrix matrix = readNullableMatrix(in, registry);
      if (result == null && matrix == null) {
        return false;
      }
      session.putResult(mode, result, matrix, null);
      return true;
    }
  }

  private static void requireMagic(DataInputStream in) throws IOException {
    int magic = in.readInt();
    if (magic != MAGIC) {
      throw new IOException("Bad analysis cache magic");
    }
    int version = in.readInt();
    if (version != SCHEMA_VERSION) {
      throw new IOException("Unsupported analysis cache version: " + version);
    }
  }

  private static void writeNullableResult(DataOutputStream out, GroupStatResult result)
      throws IOException {
    out.writeBoolean(result != null);
    if (result == null) {
      return;
    }
    writeString(out, result.mode() != null ? result.mode().name() : "");
    writeIntList(out, result.groupIds());
    out.writeBoolean(result.baselineGroupId() != null);
    if (result.baselineGroupId() != null) {
      out.writeInt(result.baselineGroupId());
    }
    writeString(out, result.summary());
    writeString(out, result.methodStrip());
    writeChannels(out, result.signalChannels());
    writeChannelValues(out, result.channelValuesByUnit());
    List<GroupStatResult.Row> rows = result.rows();
    out.writeInt(rows.size());
    for (GroupStatResult.Row row : rows) {
      writeRow(out, row);
    }
  }

  private static GroupStatResult readNullableResult(DataInputStream in) throws IOException {
    if (!in.readBoolean()) {
      return null;
    }
    AnalysisMode mode = AnalysisMode.fromPersisted(readString(in));
    List<Integer> groupIds = readIntList(in);
    Integer baseline = in.readBoolean() ? in.readInt() : null;
    String summary = readString(in);
    String methodStrip = readString(in);
    List<SignalChannel> channels = readChannels(in);
    Map<String, Map<String, Double>> channelValues = readChannelValues(in);
    int rowCount = in.readInt();
    List<GroupStatResult.Row> rows = new ArrayList<>(Math.max(0, rowCount));
    for (int i = 0; i < rowCount; i++) {
      rows.add(readRow(in));
    }
    return new GroupStatResult(
        mode, groupIds, baseline, rows, summary, methodStrip, channels, channelValues);
  }

  private static void writeRow(DataOutputStream out, GroupStatResult.Row row) throws IOException {
    writeNullableString(out, row.unitId());
    writeNullableString(out, row.geneName());
    writeNullableString(out, row.chromosome());
    out.writeLong(row.start1Based());
    out.writeLong(row.end1Based());
    List<GroupStatResult.GroupSummary> groups =
        row.groups() != null ? row.groups() : List.of();
    out.writeInt(groups.size());
    for (GroupStatResult.GroupSummary g : groups) {
      writeGroupSummary(out, g);
    }
    writeNullableDouble(out, row.effectVsBaseline());
    writeNullableDouble(out, row.pValue());
    writeNullableDouble(out, row.qValue());
    writeNullableString(out, row.testName());
    writeNullableString(out, row.direction());
  }

  private static GroupStatResult.Row readRow(DataInputStream in) throws IOException {
    String unitId = readNullableString(in);
    String geneName = readNullableString(in);
    String chrom = readNullableString(in);
    long start = in.readLong();
    long end = in.readLong();
    int gCount = in.readInt();
    List<GroupStatResult.GroupSummary> groups = new ArrayList<>(Math.max(0, gCount));
    for (int i = 0; i < gCount; i++) {
      groups.add(readGroupSummary(in));
    }
    Double effect = readNullableDouble(in);
    Double p = readNullableDouble(in);
    Double q = readNullableDouble(in);
    String test = readNullableString(in);
    String direction = readNullableString(in);
    return new GroupStatResult.Row(
        unitId, geneName, chrom, start, end, groups, effect, p, q, test, direction);
  }

  private static void writeGroupSummary(DataOutputStream out, GroupStatResult.GroupSummary g)
      throws IOException {
    if (g == null) {
      out.writeBoolean(false);
      return;
    }
    out.writeBoolean(true);
    out.writeInt(g.groupId());
    writeString(out, g.groupName() != null ? g.groupName() : "");
    out.writeInt(g.nTotal());
    out.writeInt(g.nObserved());
    out.writeInt(g.nSignal());
    out.writeInt(g.nPos());
    out.writeInt(g.nNeg());
    out.writeDouble(g.mean());
    out.writeDouble(g.median());
    out.writeDouble(g.meanSignal());
    out.writeDouble(g.min());
    out.writeDouble(g.max());
    out.writeDouble(g.sd());
    out.writeDouble(g.p10());
    out.writeDouble(g.p90());
    out.writeDouble(g.p95());
    out.writeDouble(g.meanTop10Pct());
    out.writeDouble(g.meanTop5Pct());
    out.writeDouble(g.meanBottom10Pct());
    out.writeDouble(g.meanBottom5Pct());
  }

  private static GroupStatResult.GroupSummary readGroupSummary(DataInputStream in)
      throws IOException {
    if (!in.readBoolean()) {
      return null;
    }
    return new GroupStatResult.GroupSummary(
        in.readInt(),
        readString(in),
        in.readInt(),
        in.readInt(),
        in.readInt(),
        in.readInt(),
        in.readInt(),
        in.readDouble(),
        in.readDouble(),
        in.readDouble(),
        in.readDouble(),
        in.readDouble(),
        in.readDouble(),
        in.readDouble(),
        in.readDouble(),
        in.readDouble(),
        in.readDouble(),
        in.readDouble(),
        in.readDouble(),
        in.readDouble());
  }

  private static void writeNullableMatrix(DataOutputStream out, FeatureValueMatrix matrix)
      throws IOException {
    out.writeBoolean(matrix != null && !matrix.isEmpty());
    if (matrix == null || matrix.isEmpty()) {
      return;
    }
    writeNullableDouble(out, matrix.getMissingDefault());
    List<String> units = new ArrayList<>(matrix.unitIds());
    out.writeInt(units.size());
    for (String unitId : units) {
      writeString(out, unitId);
      FeatureValueMatrix.Locus locus = matrix.locusOf(unitId);
      out.writeBoolean(locus != null);
      if (locus != null) {
        writeString(out, locus.chromosome() != null ? locus.chromosome() : "");
        out.writeLong(locus.start1Based());
        out.writeLong(locus.end1Based());
      }
      List<SampleTrack> tracks = new ArrayList<>(matrix.tracks());
      int count = 0;
      for (SampleTrack track : tracks) {
        if (matrix.hasValue(unitId, track)) {
          count++;
        }
      }
      out.writeInt(count);
      for (SampleTrack track : tracks) {
        if (!matrix.hasValue(unitId, track)) {
          continue;
        }
        writeString(out, track.getDisplayName() != null ? track.getDisplayName() : "");
        Double v = matrix.get(unitId, track);
        out.writeDouble(v != null ? v : Double.NaN);
      }
    }
  }

  private static FeatureValueMatrix readNullableMatrix(DataInputStream in, SampleRegistry registry)
      throws IOException {
    if (!in.readBoolean()) {
      return null;
    }
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    Double missing = readNullableDouble(in);
    if (missing != null) {
      matrix.setMissingDefault(missing);
    }
    int unitCount = in.readInt();
    for (int u = 0; u < unitCount; u++) {
      String unitId = readString(in);
      matrix.touch(unitId);
      if (in.readBoolean()) {
        String chrom = readString(in);
        long start = in.readLong();
        long end = in.readLong();
        matrix.putLocus(unitId, chrom, start, end);
      }
      int cellCount = in.readInt();
      for (int c = 0; c < cellCount; c++) {
        String trackName = readString(in);
        double value = in.readDouble();
        SampleTrack track = resolveTrack(registry, trackName);
        if (track != null && Double.isFinite(value)) {
          matrix.put(unitId, track, value);
        }
      }
    }
    return matrix;
  }

  private static void writeNullableBundle(DataOutputStream out, FeatureValueBundle bundle)
      throws IOException {
    out.writeBoolean(bundle != null && !bundle.isEmpty());
    if (bundle == null || bundle.isEmpty()) {
      return;
    }
    writeChannels(out, bundle.channels());
    for (SignalChannel ch : bundle.channels()) {
      writeNullableMatrix(out, bundle.matrix(ch.id()));
    }
  }

  private static FeatureValueBundle readNullableBundle(DataInputStream in, SampleRegistry registry)
      throws IOException {
    if (!in.readBoolean()) {
      return null;
    }
    List<SignalChannel> channels = readChannels(in);
    FeatureValueBundle bundle = new FeatureValueBundle(channels);
    for (SignalChannel ch : channels) {
      FeatureValueMatrix m = readNullableMatrix(in, registry);
      if (m == null) {
        continue;
      }
      FeatureValueMatrix target = bundle.matrix(ch.id());
      if (target == null) {
        continue;
      }
      if (m.getMissingDefault() != null) {
        target.setMissingDefault(m.getMissingDefault());
      }
      for (String unitId : m.unitIds()) {
        FeatureValueMatrix.Locus locus = m.locusOf(unitId);
        if (locus != null) {
          target.putLocus(unitId, locus.chromosome(), locus.start1Based(), locus.end1Based());
        }
        for (SampleTrack track : m.tracks()) {
          if (m.hasValue(unitId, track)) {
            Double v = m.get(unitId, track);
            if (v != null) {
              target.put(unitId, track, v);
            }
          }
        }
      }
    }
    return bundle;
  }

  private static void writeNullablePattern(DataOutputStream out, SamplePatternEngine.Result pattern)
      throws IOException {
    out.writeBoolean(pattern != null);
    if (pattern == null) {
      return;
    }
    List<SampleTrack> tracks = pattern.tracks() != null ? pattern.tracks() : List.of();
    out.writeInt(tracks.size());
    for (SampleTrack track : tracks) {
      writeString(out, track != null && track.getDisplayName() != null
          ? track.getDisplayName()
          : "");
    }
    writeIntArray(out, pattern.clusterOfTrack());
    out.writeInt(pattern.k());
    writeStringList(out, pattern.selectedFeatures());
    writeNullableResult(out, pattern.markers());
    writeString(out, pattern.summary() != null ? pattern.summary() : "");
    writeIntArray(out, pattern.leafOrder());
    writeDoubleMatrix(out, pattern.sampleFeature());
    out.writeBoolean(pattern.zScored());
    writeStringList(out, pattern.clusterLabels());
  }

  private static SamplePatternEngine.Result readNullablePattern(
      DataInputStream in, SampleRegistry registry) throws IOException {
    if (!in.readBoolean()) {
      return null;
    }
    int trackCount = in.readInt();
    List<SampleTrack> tracks = new ArrayList<>(Math.max(0, trackCount));
    for (int i = 0; i < trackCount; i++) {
      SampleTrack track = resolveTrack(registry, readString(in));
      if (track != null) {
        tracks.add(track);
      }
    }
    int[] clusterOfTrack = readIntArray(in);
    int k = in.readInt();
    List<String> features = readStringList(in);
    GroupStatResult markers = readNullableResult(in);
    String summary = readString(in);
    int[] leafOrder = readIntArray(in);
    double[][] sampleFeature = readDoubleMatrix(in);
    boolean zScored = in.readBoolean();
    List<String> labels = readStringList(in);
    if (tracks.isEmpty()) {
      return null;
    }
    // Remap cluster/leaf arrays if some tracks failed to resolve — keep lengths aligned.
    if (clusterOfTrack != null && clusterOfTrack.length != tracks.size()) {
      clusterOfTrack = new int[tracks.size()];
    }
    if (leafOrder != null && leafOrder.length != tracks.size()) {
      leafOrder = new int[tracks.size()];
      for (int i = 0; i < leafOrder.length; i++) {
        leafOrder[i] = i;
      }
    }
    return new SamplePatternEngine.Result(
        tracks, clusterOfTrack, k, features, markers, summary, leafOrder, sampleFeature, zScored,
        labels);
  }

  private static void writeChannels(DataOutputStream out, List<SignalChannel> channels)
      throws IOException {
    List<SignalChannel> list = channels != null ? channels : List.of();
    out.writeInt(list.size());
    for (SignalChannel ch : list) {
      writeString(out, ch.id());
      writeString(out, ch.label());
      writeString(out, ch.type() != null ? ch.type().name() : SignalValueType.RAW.name());
      writeString(out, ch.sourceRole() != null ? ch.sourceRole().name() : "");
      SignalValueThresholds thr = ch.thresholds();
      out.writeBoolean(thr != null);
      if (thr != null) {
        out.writeBoolean(thr.isEnabled());
        writeNullableDouble(out, thr.getMinValue());
        writeNullableDouble(out, thr.getMaxValue());
        out.writeDouble(thr.getMiddle());
        out.writeDouble(thr.getMinAbsFromMiddle());
        out.writeInt(thr.getMaxRows());
        out.writeBoolean(thr.isLohOnly());
      }
    }
  }

  private static List<SignalChannel> readChannels(DataInputStream in) throws IOException {
    int n = in.readInt();
    List<SignalChannel> out = new ArrayList<>(Math.max(0, n));
    for (int i = 0; i < n; i++) {
      String id = readString(in);
      String label = readString(in);
      SignalValueType type = SignalValueType.valueOf(readString(in));
      String roleName = readString(in);
      MappedTableColumnRole role = null;
      if (roleName != null && !roleName.isBlank()) {
        try {
          role = MappedTableColumnRole.valueOf(roleName);
        } catch (IllegalArgumentException ignored) {
          // leave null
        }
      }
      SignalValueThresholds thr = null;
      if (in.readBoolean()) {
        thr = new SignalValueThresholds();
        thr.setEnabled(in.readBoolean());
        thr.setMinValue(readNullableDouble(in));
        thr.setMaxValue(readNullableDouble(in));
        thr.setMiddle(in.readDouble());
        thr.setMinAbsFromMiddle(in.readDouble());
        thr.setMaxRows(in.readInt());
        thr.setLohOnly(in.readBoolean());
      }
      out.add(new SignalChannel(id, label, type, role, thr));
    }
    return out;
  }

  private static void writeChannelValues(
      DataOutputStream out, Map<String, Map<String, Double>> values) throws IOException {
    Map<String, Map<String, Double>> map = values != null ? values : Map.of();
    out.writeInt(map.size());
    for (Map.Entry<String, Map<String, Double>> e : map.entrySet()) {
      writeString(out, e.getKey());
      Map<String, Double> inner = e.getValue() != null ? e.getValue() : Map.of();
      out.writeInt(inner.size());
      for (Map.Entry<String, Double> cell : inner.entrySet()) {
        writeString(out, cell.getKey());
        out.writeDouble(cell.getValue() != null ? cell.getValue() : Double.NaN);
      }
    }
  }

  private static Map<String, Map<String, Double>> readChannelValues(DataInputStream in)
      throws IOException {
    int n = in.readInt();
    Map<String, Map<String, Double>> out = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) {
      String unit = readString(in);
      int m = in.readInt();
      Map<String, Double> inner = new LinkedHashMap<>();
      for (int j = 0; j < m; j++) {
        inner.put(readString(in), in.readDouble());
      }
      out.put(unit, inner);
    }
    return out;
  }

  private static SampleTrack resolveTrack(SampleRegistry registry, String name) {
    if (registry == null || name == null || name.isBlank()) {
      return null;
    }
    return registry.findTrackMatchingName(name);
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

  private static String safeName(String id) {
    if (id == null || id.isBlank()) {
      return "dataset";
    }
    return id.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static void writeIntList(DataOutputStream out, List<Integer> values) throws IOException {
    List<Integer> list = values != null ? values : List.of();
    out.writeInt(list.size());
    for (Integer v : list) {
      out.writeInt(v != null ? v : 0);
    }
  }

  private static List<Integer> readIntList(DataInputStream in) throws IOException {
    int n = in.readInt();
    List<Integer> out = new ArrayList<>(Math.max(0, n));
    for (int i = 0; i < n; i++) {
      out.add(in.readInt());
    }
    return out;
  }

  private static void writeStringList(DataOutputStream out, List<String> values) throws IOException {
    List<String> list = values != null ? values : List.of();
    out.writeInt(list.size());
    for (String v : list) {
      writeString(out, v != null ? v : "");
    }
  }

  private static List<String> readStringList(DataInputStream in) throws IOException {
    int n = in.readInt();
    List<String> out = new ArrayList<>(Math.max(0, n));
    for (int i = 0; i < n; i++) {
      out.add(readString(in));
    }
    return out;
  }

  private static void writeIntArray(DataOutputStream out, int[] values) throws IOException {
    if (values == null) {
      out.writeInt(-1);
      return;
    }
    out.writeInt(values.length);
    for (int v : values) {
      out.writeInt(v);
    }
  }

  private static int[] readIntArray(DataInputStream in) throws IOException {
    int n = in.readInt();
    if (n < 0) {
      return null;
    }
    int[] values = new int[n];
    for (int i = 0; i < n; i++) {
      values[i] = in.readInt();
    }
    return values;
  }

  private static void writeDoubleMatrix(DataOutputStream out, double[][] matrix) throws IOException {
    if (matrix == null) {
      out.writeInt(-1);
      return;
    }
    out.writeInt(matrix.length);
    for (double[] row : matrix) {
      if (row == null) {
        out.writeInt(-1);
        continue;
      }
      out.writeInt(row.length);
      for (double v : row) {
        out.writeDouble(v);
      }
    }
  }

  private static double[][] readDoubleMatrix(DataInputStream in) throws IOException {
    int rows = in.readInt();
    if (rows < 0) {
      return null;
    }
    double[][] matrix = new double[rows][];
    for (int r = 0; r < rows; r++) {
      int cols = in.readInt();
      if (cols < 0) {
        matrix[r] = null;
        continue;
      }
      matrix[r] = new double[cols];
      for (int c = 0; c < cols; c++) {
        matrix[r][c] = in.readDouble();
      }
    }
    return matrix;
  }

  private static void writeString(DataOutputStream out, String value) throws IOException {
    byte[] bytes = (value != null ? value : "").getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static void writeNullableString(DataOutputStream out, String value) throws IOException {
    out.writeBoolean(value != null);
    if (value != null) {
      writeString(out, value);
    }
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

  private static void writeNullableDouble(DataOutputStream out, Double value) throws IOException {
    out.writeBoolean(value != null);
    if (value != null) {
      out.writeDouble(value);
    }
  }

  private static Double readNullableDouble(DataInputStream in) throws IOException {
    if (!in.readBoolean()) {
      return null;
    }
    return in.readDouble();
  }

  static final class AnalysisIndex {
    int schemaVersion;
    List<AnalysisArtifactSpec> artifacts = new ArrayList<>();
  }

  static final class AnalysisArtifactSpec {
    String kind;
    String datasetId;
    String mode;
    String fileName;
  }
}
