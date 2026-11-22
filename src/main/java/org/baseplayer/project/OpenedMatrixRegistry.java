package org.baseplayer.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.io.table.MatrixTableOpen.MatrixOpenSpec;

/**
 * Session-scoped memory of matrix files opened this session so project save can
 * persist the source path + open options (per-sample synthetic {@code #} paths
 * are not enough to round-trip).
 */
public final class OpenedMatrixRegistry {

  private static final OpenedMatrixRegistry INSTANCE = new OpenedMatrixRegistry();

  private final Map<String, Entry> byAbsolutePath = new LinkedHashMap<>();

  public record Entry(Path path, MatrixOpenSpec spec) {}

  private OpenedMatrixRegistry() {}

  public static OpenedMatrixRegistry get() {
    return INSTANCE;
  }

  public synchronized void register(Path path, MatrixOpenSpec spec) {
    if (path == null || spec == null) {
      return;
    }
    Path abs = path.toAbsolutePath().normalize();
    byAbsolutePath.put(abs.toString(), new Entry(abs, spec));
  }

  public synchronized List<Entry> entries() {
    return new ArrayList<>(byAbsolutePath.values());
  }

  public synchronized void clear() {
    byAbsolutePath.clear();
  }
}
