package org.baseplayer.project;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.io.VcfManager;
import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.MateBreakpointIndex;
import org.baseplayer.variant.VariantLoader;
import org.baseplayer.variant.VcfHeaderFieldDef;

/**
 * Build / apply durable VCF registry metadata for project save/load so restore
 * can register VCFs without HTSJDK open or mate rescans.
 */
public final class VcfMetaPersistence {

  private VcfMetaPersistence() {}

  /** Collect absolute VCF paths currently listed on sample tracks. */
  public static Set<String> listedVcfPaths() {
    Set<String> out = new LinkedHashSet<>();
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    if (registry == null) {
      return out;
    }
    for (SampleTrack track : registry.getSampleTracks()) {
      if (track == null) {
        continue;
      }
      for (Sample sample : track.getSamples()) {
        if (sample == null
            || sample.getDataType() != Sample.DataType.VCF
            || sample.getPath() == null) {
          continue;
        }
        out.add(sample.getPath().toAbsolutePath().normalize().toString());
      }
    }
    return out;
  }

  /**
   * Rewrite {@link ProjectDocument#vcfMetas} from loaded VCFs, keeping prior meta
   * for still-listed paths that are not currently loaded (e.g. unchecked).
   */
  public static void writeVcfMetasFromRuntime(Path projectFile, List<String> warnings) {
    ProjectDocument doc = ProjectSessionState.get().getDocument();
    if (doc == null) {
      return;
    }
    Set<String> listed = listedVcfPaths();
    Map<String, ProjectDocument.VcfMetaSpec> byPath = new LinkedHashMap<>();
    if (doc.vcfMetas != null) {
      for (ProjectDocument.VcfMetaSpec prior : doc.vcfMetas) {
        if (prior == null || prior.path == null || prior.path.isBlank()) {
          continue;
        }
        String key = Path.of(prior.path).toAbsolutePath().normalize().toString();
        if (listed.contains(key)) {
          byPath.put(key, prior);
        }
      }
    }

    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    Path cacheDir = projectFile != null ? VariantCacheStore.cacheDirectory(projectFile) : null;

    for (VcfManager.VcfData vcfData : VcfManager.getInstance().getLoadedVcfDataSnapshot()) {
      if (vcfData == null || vcfData.getFile() == null) {
        continue;
      }
      File file = vcfData.getFile();
      String key = file.toPath().toAbsolutePath().normalize().toString();
      if (!listed.contains(key)) {
        continue;
      }
      ProjectDocument.VcfMetaSpec meta = buildMeta(vcfData, projectFile, registry);
      // Mate sidecars only for single-sided SV TRA/BNDs (breakpoint reported once).
      MateBreakpointIndex mateIndex = vcfData.getMateBreakpointIndex();
      boolean singleSided =
          vcfData.getBreakendStyle() == VcfReader.InterChromBreakendStyle.SINGLE_SIDED;
      if (cacheDir != null
          && singleSided
          && mateIndex != null
          && !mateIndex.isEmpty()) {
        String mateFile = mateFileName(key);
        try {
          VariantCacheStore.writeMateIndex(
              cacheDir.resolve(mateFile), mateIndex, registry);
          meta.mateIndexSaved = true;
          meta.mateIndexFile = mateFile;
        } catch (Exception e) {
          meta.mateIndexSaved = false;
          meta.mateIndexFile = null;
          if (warnings != null) {
            warnings.add(
                "Failed to write mate index for " + file.getName() + ": " + e.getMessage());
          }
        }
      } else {
        meta.mateIndexSaved = false;
        meta.mateIndexFile = null;
        // Drop stale empty/non–single-sided mate files from earlier saves.
        if (cacheDir != null) {
          try {
            java.nio.file.Files.deleteIfExists(cacheDir.resolve(mateFileName(key)));
          } catch (Exception ignored) {
            // best-effort cleanup
          }
        }
      }
      byPath.put(key, meta);
    }
    doc.vcfMetas = new ArrayList<>(byPath.values());
  }

  public static ProjectDocument.VcfMetaSpec buildMeta(
      VcfManager.VcfData vcfData, Path projectFile, SampleRegistry registry) {
    ProjectDocument.VcfMetaSpec meta = new ProjectDocument.VcfMetaSpec();
    File file = vcfData.getFile();
    Path abs = file.toPath().toAbsolutePath().normalize();
    meta.path = PathResolver.toAbsoluteString(abs);
    if (projectFile != null) {
      String relative = PathResolver.toRelativeString(abs, projectFile);
      if (relative != null && !relative.isBlank()) {
        meta.pathRelative = relative;
      }
    }
    meta.size = file.length();
    meta.mtime = file.lastModified();
    VariantLoader loader = vcfData.getLoader();
    if (loader != null) {
      meta.sampleNames = new ArrayList<>(loader.getVcfSampleNames());
      meta.detectedNormalSample = loader.getDetectedNormalSample();
      meta.sampleToTrack = new LinkedHashMap<>();
      for (Map.Entry<String, Integer> entry : loader.getSampleMapping().entrySet()) {
        Integer idx = entry.getValue();
        if (entry.getKey() == null || idx == null || registry == null) {
          continue;
        }
        if (idx < 0 || idx >= registry.getSampleTracks().size()) {
          continue;
        }
        SampleTrack track = registry.getSampleTracks().get(idx);
        if (track != null && track.getDisplayName() != null) {
          meta.sampleToTrack.put(entry.getKey(), track.getDisplayName());
        }
      }
    }
    meta.chromPrefix = vcfData.chromPrefix != null ? vcfData.chromPrefix : "";
    meta.hasTbiOrCsiIndex = siblingIndexExists(abs, ".tbi") || siblingIndexExists(abs, ".csi");
    meta.canTabixQuery = siblingIndexExists(abs, ".tbi");
    if (vcfData.getBreakendStyle() != null) {
      meta.breakendStyle = vcfData.getBreakendStyle().name();
    }
    meta.infoFields = toFieldSpecs(vcfData.getInfoHeaderFields());
    meta.filterFields = toFieldSpecs(vcfData.getFilterHeaderFields());
    meta.formatFields = toFieldSpecs(vcfData.getFormatHeaderFields());
    return meta;
  }

  /**
   * Register a VCF from saved meta without HTSJDK. Returns the registered data,
   * or null when the fingerprint is stale / mapping fails and caller should open.
   */
  public static VcfManager.VcfData registerFromMeta(
      ProjectDocument.VcfMetaSpec meta, Path projectFile, List<String> warnings) {
    if (meta == null) {
      return null;
    }
    Path path = PathResolver.resolve(meta.path, meta.pathRelative, projectFile);
    if (path == null || !path.toFile().exists()) {
      if (warnings != null) {
        warnings.add("Missing VCF: " + (meta.path != null ? meta.path : meta.pathRelative));
      }
      return null;
    }
    File file = path.toFile();
    if (VcfManager.getInstance().isVcfFileLoaded(file)) {
      return null;
    }
    if (!fingerprintMatches(meta, file)) {
      if (warnings != null) {
        warnings.add("VCF metadata stale for " + file.getName() + "; reopening file");
      }
      return null;
    }

    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    Map<String, Integer> sampleToTrack = new LinkedHashMap<>();
    if (meta.sampleToTrack != null && registry != null) {
      Map<String, Integer> nameToIndex = new LinkedHashMap<>();
      List<SampleTrack> tracks = registry.getSampleTracks();
      for (int i = 0; i < tracks.size(); i++) {
        SampleTrack track = tracks.get(i);
        if (track != null && track.getDisplayName() != null) {
          nameToIndex.put(track.getDisplayName(), i);
        }
      }
      for (Map.Entry<String, String> entry : meta.sampleToTrack.entrySet()) {
        Integer idx = nameToIndex.get(entry.getValue());
        if (entry.getKey() != null && idx != null) {
          sampleToTrack.put(entry.getKey(), idx);
        }
      }
    }
    // Path-listed tracks are authoritative when name map is empty/partial.
    if (sampleToTrack.isEmpty()) {
      List<Integer> allowed =
          org.baseplayer.io.SampleDataManager.findTrackIndicesListingVcf(registry, path);
      if (allowed.size() == 1 && meta.sampleNames != null) {
        int sole = allowed.get(0);
        for (String sampleName : meta.sampleNames) {
          if (sampleName == null
              || (meta.detectedNormalSample != null && meta.detectedNormalSample.equals(sampleName))) {
            continue;
          }
          sampleToTrack.put(sampleName, sole);
        }
      }
    }

    VariantLoader loader = VariantLoader.fromMetadata(
        path, meta.sampleNames, meta.detectedNormalSample, sampleToTrack);
    VcfManager.VcfData vcfData = new VcfManager.VcfData(
        loader,
        file,
        meta.chromPrefix,
        fromFieldSpecs(meta.infoFields),
        fromFieldSpecs(meta.filterFields),
        fromFieldSpecs(meta.formatFields));

    VcfReader.InterChromBreakendStyle style = parseBreakendStyle(meta.breakendStyle);
    // Mate sidecar is only meaningful for single-sided SV TRA/BNDs.
    if (style == VcfReader.InterChromBreakendStyle.SINGLE_SIDED
        && meta.mateIndexSaved
        && meta.mateIndexFile != null
        && projectFile != null) {
      Path matePath = VariantCacheStore.cacheDirectory(projectFile).resolve(meta.mateIndexFile);
      try {
        MateBreakpointIndex index = VariantCacheStore.readMateIndex(matePath, registry);
        if (index != null && !index.isEmpty()) {
          vcfData.installMateIndex(index, style);
        } else {
          // Empty/corrupt sidecar — leave null so callers may rebuild once.
          vcfData.setBreakendStyle(style);
        }
      } catch (Exception e) {
        if (warnings != null) {
          warnings.add(
              "Failed to read mate index for " + file.getName() + ": " + e.getMessage());
        }
        vcfData.setBreakendStyle(style);
      }
    } else if (style != null && style != VcfReader.InterChromBreakendStyle.SINGLE_SIDED) {
      vcfData.installMateIndex(MateBreakpointIndex.empty(), style);
    } else if (style == VcfReader.InterChromBreakendStyle.SINGLE_SIDED) {
      // Sidecar missing — leave mateIndex null for optional rebuild on visible open.
      vcfData.setBreakendStyle(style);
    } else if (style != null) {
      vcfData.setBreakendStyle(style);
    }

    VcfManager.VcfData registered = VcfManager.getInstance().registerMetadataVcf(vcfData);
    if (registered == null) {
      return null;
    }
    org.baseplayer.io.SampleDataManager.attachVcfSamplesToMappedTracks(loader, path);
    return registered;
  }

  public static String mateFileName(String absolutePath) {
    String key = absolutePath != null ? absolutePath : "";
    return "mates-" + sha256Prefix(key, 16) + ".bpm.zst";
  }

  public static boolean fingerprintMatches(ProjectDocument.VcfMetaSpec meta, File file) {
    if (meta == null || file == null || !file.exists()) {
      return false;
    }
    return meta.size == file.length() && meta.mtime == file.lastModified();
  }

  private static boolean siblingIndexExists(Path vcfPath, String suffix) {
    if (vcfPath == null) {
      return false;
    }
    return java.nio.file.Files.isRegularFile(
        vcfPath.resolveSibling(vcfPath.getFileName().toString() + suffix));
  }

  private static List<ProjectDocument.VcfHeaderFieldSpec> toFieldSpecs(
      List<VcfHeaderFieldDef> fields) {
    List<ProjectDocument.VcfHeaderFieldSpec> out = new ArrayList<>();
    if (fields == null) {
      return out;
    }
    for (VcfHeaderFieldDef field : fields) {
      if (field == null || !field.hasId()) {
        continue;
      }
      ProjectDocument.VcfHeaderFieldSpec spec = new ProjectDocument.VcfHeaderFieldSpec();
      spec.kind = field.kind() != null ? field.kind().name() : null;
      spec.id = field.id();
      spec.number = field.number();
      spec.type = field.type();
      spec.description = field.description();
      out.add(spec);
    }
    return out;
  }

  private static List<VcfHeaderFieldDef> fromFieldSpecs(
      List<ProjectDocument.VcfHeaderFieldSpec> specs) {
    List<VcfHeaderFieldDef> out = new ArrayList<>();
    if (specs == null) {
      return out;
    }
    for (ProjectDocument.VcfHeaderFieldSpec spec : specs) {
      if (spec == null || spec.id == null || spec.id.isBlank()) {
        continue;
      }
      VcfHeaderFieldDef.Kind kind = VcfHeaderFieldDef.Kind.INFO;
      if (spec.kind != null) {
        try {
          kind = VcfHeaderFieldDef.Kind.valueOf(spec.kind.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
          // keep INFO
        }
      }
      out.add(new VcfHeaderFieldDef(kind, spec.id, spec.number, spec.type, spec.description));
    }
    return out;
  }

  private static VcfReader.InterChromBreakendStyle parseBreakendStyle(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return VcfReader.InterChromBreakendStyle.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String sha256Prefix(String value, int hexChars) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      String hex = HexFormat.of().formatHex(hash);
      return hex.substring(0, Math.min(hexChars, hex.length()));
    } catch (NoSuchAlgorithmException e) {
      return Integer.toHexString(value.hashCode());
    }
  }
}
