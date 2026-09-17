package org.baseplayer.project;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.annotation.CosmicCensusEntry;
import org.baseplayer.annotation.CosmicGenes;
import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Session sidecar for annotated variant lists: {@code <projectStem>.bpcache/*.bpv.zst}.
 */
public final class VariantCacheStore {

  public static final int SCHEMA_VERSION = 1;
  private static final int MAGIC = 0x42505631; // "BPV1"
  private static final String META_FILE = "meta.json";
  private static final String CHROM_SUFFIX = ".bpv.zst";

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private VariantCacheStore() {}

  public static Path cacheDirectory(Path projectFile) {
    String fileName = projectFile.getFileName().toString();
    int dot = fileName.lastIndexOf('.');
    String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
    Path parent = projectFile.getParent();
    return (parent != null ? parent : Path.of(".")).resolve(stem + ".bpcache");
  }

  public static boolean exists(Path projectFile) {
    Path dir = cacheDirectory(projectFile);
    return Files.isDirectory(dir) && Files.isRegularFile(dir.resolve(META_FILE));
  }

  /** Write all in-memory chromosome variant lists next to the project file. */
  public static void writeSessionCache(Path projectFile) throws IOException {
    Path dir = cacheDirectory(projectFile);
    Files.createDirectories(dir);

    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    List<String> sampleNames = new ArrayList<>();
    for (SampleTrack track : registry.getSampleTracks()) {
      sampleNames.add(track.getDisplayName() != null ? track.getDisplayName() : "");
    }

    List<VcfFingerprint> fingerprints = new ArrayList<>();
    for (File vcf : VcfManager.getInstance().getLoadedVcfFiles()) {
      if (vcf == null || !vcf.exists()) continue;
      fingerprints.add(VcfFingerprint.from(vcf));
    }

    Map<String, VariantList> cached = VcfManager.getInstance().snapshotVariantCache();
    // One entry per list instance — alias keys (chr1 / 1) must not double-count.
    Map<VariantList, String> uniqueLists = new java.util.IdentityHashMap<>();
    for (Map.Entry<String, VariantList> entry : cached.entrySet()) {
      String chrom = entry.getKey();
      VariantList list = entry.getValue();
      if (chrom == null || list == null || list.isEmpty()) continue;
      String preferred = list.getChromosome();
      if (preferred == null || preferred.isBlank()) {
        preferred = chrom;
      }
      uniqueLists.merge(list, preferred, (existing, incoming) ->
          existing.length() >= incoming.length() ? existing : incoming);
    }

    List<Map.Entry<String, VariantList>> entries = new ArrayList<>();
    for (Map.Entry<VariantList, String> entry : uniqueLists.entrySet()) {
      entries.add(Map.entry(entry.getValue(), entry.getKey()));
    }
    entries.sort((a, b) -> a.getKey().compareToIgnoreCase(b.getKey()));

    // Stable VCF fingerprint order for signature comparison.
    fingerprints.sort((a, b) -> {
      String pa = a != null && a.path != null ? a.path : "";
      String pb = b != null && b.path != null ? b.path : "";
      return pa.compareToIgnoreCase(pb);
    });

    CacheMeta desired = buildMeta(sampleNames, fingerprints, entries);
    if (canSkipRewrite(dir, desired)) {
      org.baseplayer.services.LoadingManager.get().setProgress(1, 1);
      return;
    }

    int total = Math.max(1, entries.size());
    org.baseplayer.services.LoadingManager.get().setProgress(0, total);
    int index = 0;
    for (Map.Entry<String, VariantList> entry : entries) {
      String chrom = entry.getKey();
      VariantList list = entry.getValue();
      writeChromosomeFile(dir.resolve(safeChromFileName(chrom) + CHROM_SUFFIX), list, sampleNames);
      index++;
      org.baseplayer.services.LoadingManager.get().setProgress(index, total);
    }

    // Drop stale chromosome files that are no longer in the in-memory cache.
    removeStaleChromosomeFiles(dir, desired.chromosomes);

    try (Writer writer = Files.newBufferedWriter(dir.resolve(META_FILE), StandardCharsets.UTF_8)) {
      GSON.toJson(desired, writer);
    }
  }

  /**
   * True when on-disk cache already matches current in-memory variants
   * (same VCFs, samples, chromosomes, filters, annotation flags, and counts).
   */
  private static boolean canSkipRewrite(Path dir, CacheMeta desired) {
    Path metaPath = dir.resolve(META_FILE);
    if (!Files.isRegularFile(metaPath)) {
      return false;
    }
    try (Reader reader = Files.newBufferedReader(metaPath, StandardCharsets.UTF_8)) {
      CacheMeta existing = GSON.fromJson(reader, CacheMeta.class);
      if (existing == null || existing.schemaVersion != SCHEMA_VERSION) {
        return false;
      }
      if (!java.util.Objects.equals(desired.contentSignature, existing.contentSignature)) {
        return false;
      }
      if (desired.chromosomes == null) {
        return true;
      }
      for (String chrom : desired.chromosomes) {
        if (!Files.isRegularFile(dir.resolve(safeChromFileName(chrom) + CHROM_SUFFIX))) {
          return false;
        }
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static CacheMeta buildMeta(
      List<String> sampleNames,
      List<VcfFingerprint> fingerprints,
      List<Map.Entry<String, VariantList>> entries) {
    CacheMeta meta = new CacheMeta();
    meta.schemaVersion = SCHEMA_VERSION;
    // Defensive copy so later mutations can't affect signature stability.
    meta.sampleNames = sampleNames == null ? new ArrayList<>() : new ArrayList<>(sampleNames);
    meta.vcfFingerprints = fingerprints == null ? new ArrayList<>() : new ArrayList<>(fingerprints);
    meta.chromosomes = new ArrayList<>();
    meta.chromosomeDetails = new ArrayList<>();

    StringBuilder signature = new StringBuilder();
    signature.append("v").append(SCHEMA_VERSION).append('|');
    signature.append("samples:");
    for (String name : meta.sampleNames) {
      signature.append(name == null ? "" : name).append(';');
    }
    signature.append("|vcfs:");
    for (VcfFingerprint fp : meta.vcfFingerprints) {
      if (fp == null) continue;
      // Normalize path so absolute vs resolved forms don't bust the signature.
      String path = "";
      if (fp.path != null && !fp.path.isBlank()) {
        try {
          path = Path.of(fp.path).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
          path = fp.path;
        }
      }
      signature.append(path).append('@').append(fp.size).append('@').append(fp.mtime).append(';');
    }
    signature.append("|chroms:");

    for (Map.Entry<String, VariantList> entry : entries) {
      String chrom = entry.getKey();
      VariantList list = entry.getValue();
      ChromDetail detail = new ChromDetail();
      detail.name = chrom;
      detail.variantCount = list.size();
      detail.filterKey = list.getLoadedFilterKey();
      detail.annotated = list.isAnnotated();
      detail.vcfCount = list.getVcfCountWhenLoaded();
      meta.chromosomes.add(chrom);
      meta.chromosomeDetails.add(detail);

      signature.append(chrom)
          .append('#')
          .append(detail.variantCount)
          .append('#')
          .append(detail.filterKey == null ? "" : detail.filterKey)
          .append('#')
          .append(detail.annotated)
          .append('#')
          .append(detail.vcfCount)
          .append(';');
    }
    meta.contentSignature = signature.toString();
    return meta;
  }

  private static void removeStaleChromosomeFiles(Path dir, List<String> keepChromosomes) throws IOException {
    if (!Files.isDirectory(dir)) return;
    java.util.Set<String> keep = new java.util.HashSet<>();
    if (keepChromosomes != null) {
      for (String chrom : keepChromosomes) {
        keep.add(safeChromFileName(chrom) + CHROM_SUFFIX);
      }
    }
    try (var stream = Files.list(dir)) {
      for (Path file : stream.toList()) {
        String name = file.getFileName().toString();
        if (name.endsWith(CHROM_SUFFIX) && !keep.contains(name)) {
          Files.deleteIfExists(file);
        }
      }
    }
  }

  /**
   * Load chromosome caches if present and fingerprints match current VCFs.
   * @return map chrom → list (may be empty if nothing usable)
   */
  public static Map<String, VariantList> readSessionCache(Path projectFile, List<String> warnings)
      throws IOException {
    Map<String, VariantList> out = new LinkedHashMap<>();
    if (!exists(projectFile)) return out;

    Path dir = cacheDirectory(projectFile);
    CacheMeta meta;
    try (Reader reader = Files.newBufferedReader(dir.resolve(META_FILE), StandardCharsets.UTF_8)) {
      meta = GSON.fromJson(reader, CacheMeta.class);
    }
    if (meta == null || meta.schemaVersion != SCHEMA_VERSION) {
      if (warnings != null) warnings.add("Variant cache schema mismatch; ignoring cache");
      return out;
    }

    if (!fingerprintsMatch(meta.vcfFingerprints)) {
      if (warnings != null) {
        warnings.add("Variant cache VCF fingerprints differ from loaded VCFs; ignoring cache");
      }
      return out;
    }

    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    Map<String, Integer> nameToTrackIndex = new HashMap<>();
    for (int i = 0; i < registry.getSampleTracks().size(); i++) {
      SampleTrack track = registry.getSampleTracks().get(i);
      if (track.getDisplayName() != null) {
        nameToTrackIndex.put(track.getDisplayName(), i);
      }
    }

    List<String> chroms = meta.chromosomes != null ? meta.chromosomes : List.of();
    List<String> sampleNames = meta.sampleNames != null ? meta.sampleNames : List.of();

    int total = Math.max(1, chroms.size());
    org.baseplayer.services.LoadingManager.get().setProgress(0, total);
    int index = 0;
    for (String chrom : chroms) {
      Path file = dir.resolve(safeChromFileName(chrom) + CHROM_SUFFIX);
      if (!Files.isRegularFile(file)) {
        if (warnings != null) warnings.add("Missing variant cache file for chr" + chrom);
        index++;
        org.baseplayer.services.LoadingManager.get().setProgress(index, total);
        continue;
      }
      try {
        VariantList list = readChromosomeFile(file, chrom, sampleNames, nameToTrackIndex);
        if (list != null && !list.isEmpty()) {
          out.put(chrom, list);
        }
      } catch (Exception e) {
        if (warnings != null) {
          warnings.add("Failed to read variant cache for " + chrom + ": " + e.getMessage());
        }
      }
      index++;
      org.baseplayer.services.LoadingManager.get().setProgress(index, total);
    }
    return out;
  }

  private static boolean fingerprintsMatch(List<VcfFingerprint> saved) {
    List<File> current = VcfManager.getInstance().getLoadedVcfFiles();
    if (saved == null) saved = List.of();
    if (saved.size() != current.size()) return false;

    Map<String, VcfFingerprint> byPath = new HashMap<>();
    for (VcfFingerprint fp : saved) {
      if (fp != null && fp.path != null) {
        byPath.put(Path.of(fp.path).toAbsolutePath().normalize().toString(), fp);
      }
    }
    for (File file : current) {
      if (file == null) return false;
      String key = file.toPath().toAbsolutePath().normalize().toString();
      VcfFingerprint fp = byPath.get(key);
      if (fp == null) return false;
      VcfFingerprint now = VcfFingerprint.from(file);
      if (fp.size != now.size || fp.mtime != now.mtime) return false;
    }
    return true;
  }

  private static void writeChromosomeFile(Path file, VariantList list, List<String> sampleNames)
      throws IOException {
    try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(file));
         ZstdOutputStream zstd = new ZstdOutputStream(raw);
         DataOutputStream out = new DataOutputStream(zstd)) {

      out.writeInt(MAGIC);
      out.writeInt(SCHEMA_VERSION);
      writeString(out, list.getChromosome());
      writeNullableString(out, list.getLoadedFilterKey());
      out.writeBoolean(list.isAnnotated());
      out.writeInt(list.getVcfCountWhenLoaded());

      List<VariantList.LoadedRegion> regions = list.getLoadedRegions();
      out.writeInt(regions.size());
      for (VariantList.LoadedRegion region : regions) {
        out.writeLong(region.start);
        out.writeLong(region.end);
      }

      out.writeInt(sampleNames.size());
      for (String name : sampleNames) {
        writeString(out, name != null ? name : "");
      }

      // Count nodes first
      int nodeCount = list.size();
      out.writeInt(nodeCount);

      VariantNode node = list.getFirst();
      while (node != null) {
        writeNode(out, node);
        node = node.next;
      }
    }
  }

  private static VariantList readChromosomeFile(
      Path file,
      String expectedChrom,
      List<String> sampleNames,
      Map<String, Integer> nameToTrackIndex) throws IOException {

    try (InputStream raw = new BufferedInputStream(Files.newInputStream(file));
         ZstdInputStream zstd = new ZstdInputStream(raw);
         DataInputStream in = new DataInputStream(zstd)) {

      int magic = in.readInt();
      if (magic != MAGIC) {
        throw new IOException("Bad variant cache magic");
      }
      int version = in.readInt();
      if (version != SCHEMA_VERSION) {
        throw new IOException("Unsupported variant cache version: " + version);
      }

      String chrom = readString(in);
      if (expectedChrom != null && !expectedChrom.equals(chrom)) {
        // Prefer file meta chrom naming; still usable
        chrom = expectedChrom;
      }

      VariantList list = new VariantList(chrom);
      String filterKey = readNullableString(in);
      boolean annotated = in.readBoolean();
      int vcfCount = in.readInt();

      int regionCount = in.readInt();
      for (int i = 0; i < regionCount; i++) {
        long start = in.readLong();
        long end = in.readLong();
        list.addLoadedRegion(start, end);
      }

      int savedSampleCount = in.readInt();
      String[] savedNames = new String[savedSampleCount];
      for (int i = 0; i < savedSampleCount; i++) {
        savedNames[i] = readString(in);
      }
      // Prefer meta sampleNames if lengths match
      if (sampleNames != null && sampleNames.size() == savedSampleCount) {
        for (int i = 0; i < savedSampleCount; i++) {
          savedNames[i] = sampleNames.get(i);
        }
      }

      int nodeCount = in.readInt();
      for (int i = 0; i < nodeCount; i++) {
        VariantNode node = readNode(in, chrom, savedNames, nameToTrackIndex);
        if (node != null && node.getSampleCount() > 0) {
          list.appendNodeFromCache(node);
        }
      }

      list.setLoadedFilterKey(filterKey);
      list.setAnnotated(annotated);
      list.setVcfCountWhenLoaded(vcfCount > 0 ? vcfCount : VcfManager.getInstance().getLoadedVcfCount());
      return list;
    }
  }

  private static void writeNode(DataOutputStream out, VariantNode node) throws IOException {
    out.writeLong(node.position);
    writeString(out, node.ref != null ? node.ref : "");
    writeString(out, node.alt != null ? node.alt : "");
    out.writeInt(node.type != null ? node.type.ordinal() : VcfVariantType.COMPLEX.ordinal());
    out.writeLong(node.svEnd);
    out.writeDouble(node.siteQuality);

    List<VariantNode.SampleCall> calls = node.getSamples();
    out.writeInt(calls.size());
    for (VariantNode.SampleCall call : calls) {
      out.writeInt(call.getTrackIndex());
      writeNullableString(out, call.gt);
      out.writeDouble(call.quality);
      out.writeInt(call.depth);
      out.writeDouble(call.alleleFraction);
    }

    VariantAnnotation ann = node.annotation;
    out.writeBoolean(ann != null);
    if (ann != null) {
      out.writeInt(ann.effect() != null ? ann.effect().ordinal() : VariantEffect.INTERGENIC.ordinal());
      writeNullableString(out, ann.geneName());
      writeNullableString(out, ann.transcriptId());
      writeNullableString(out, ann.aaChange());
      writeNullableString(out, ann.codonChange());
      out.writeInt(ann.codonNumber());
      out.writeBoolean(ann.isCancerGene());
    }
  }

  private static VariantNode readNode(
      DataInputStream in,
      String chrom,
      String[] savedNames,
      Map<String, Integer> nameToTrackIndex) throws IOException {

    long position = in.readLong();
    String ref = readString(in);
    String alt = readString(in);
    VcfVariantType type = ordinalToType(in.readInt());
    long svEnd = in.readLong();
    double siteQuality = in.readDouble();

    VariantNode node = new VariantNode(position, ref, alt, type);
    node.svEnd = svEnd;
    node.siteQuality = siteQuality;

    int callCount = in.readInt();
    for (int i = 0; i < callCount; i++) {
      int savedTrackIndex = in.readInt();
      String gt = readNullableString(in);
      double quality = in.readDouble();
      int depth = in.readInt();
      double af = in.readDouble();

      int liveIndex = -1;
      if (savedTrackIndex >= 0 && savedTrackIndex < savedNames.length) {
        Integer mapped = nameToTrackIndex.get(savedNames[savedTrackIndex]);
        if (mapped != null) liveIndex = mapped;
      }
      if (liveIndex >= 0) {
        node.addSample(new VariantNode.SampleCall(liveIndex, gt, quality, depth, af));
      }
    }

    boolean hasAnn = in.readBoolean();
    if (hasAnn) {
      VariantEffect effect = ordinalToEffect(in.readInt());
      String geneName = readNullableString(in);
      String transcriptId = readNullableString(in);
      String aaChange = readNullableString(in);
      String codonChange = readNullableString(in);
      int codonNumber = in.readInt();
      boolean isCancerGene = in.readBoolean();
      CosmicCensusEntry cosmic = CosmicGenes.getEntry(geneName);
      node.annotation = new VariantAnnotation(
          chrom, position, effect, geneName, transcriptId,
          aaChange, codonChange, codonNumber, isCancerGene, cosmic);
    }

    return node;
  }

  private static VcfVariantType ordinalToType(int ordinal) {
    VcfVariantType[] values = VcfVariantType.values();
    if (ordinal < 0 || ordinal >= values.length) return VcfVariantType.COMPLEX;
    return values[ordinal];
  }

  private static VariantEffect ordinalToEffect(int ordinal) {
    VariantEffect[] values = VariantEffect.values();
    if (ordinal < 0 || ordinal >= values.length) return VariantEffect.INTERGENIC;
    return values[ordinal];
  }

  private static String safeChromFileName(String chrom) {
    if (chrom == null || chrom.isBlank()) return "unknown";
    return chrom.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static void writeString(DataOutputStream out, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static String readString(DataInputStream in) throws IOException {
    int len = in.readInt();
    if (len < 0 || len > 50_000_000) throw new IOException("Invalid string length: " + len);
    byte[] bytes = new byte[len];
    in.readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static void writeNullableString(DataOutputStream out, String value) throws IOException {
    out.writeBoolean(value != null);
    if (value != null) writeString(out, value);
  }

  private static String readNullableString(DataInputStream in) throws IOException {
    if (!in.readBoolean()) return null;
    return readString(in);
  }

  public static final class CacheMeta {
    public int schemaVersion;
    public List<String> sampleNames = new ArrayList<>();
    public List<String> chromosomes = new ArrayList<>();
    public List<VcfFingerprint> vcfFingerprints = new ArrayList<>();
    /** Stable fingerprint of cache contents; used to skip rewrite when unchanged. */
    public String contentSignature;
    public List<ChromDetail> chromosomeDetails = new ArrayList<>();
  }

  public static final class ChromDetail {
    public String name;
    public int variantCount;
    public String filterKey;
    public boolean annotated;
    public int vcfCount;
  }

  public static final class VcfFingerprint {
    public String path;
    public long size;
    public long mtime;

    public static VcfFingerprint from(File file) {
      VcfFingerprint fp = new VcfFingerprint();
      fp.path = file.getAbsolutePath();
      fp.size = file.length();
      fp.mtime = file.lastModified();
      return fp;
    }
  }
}
