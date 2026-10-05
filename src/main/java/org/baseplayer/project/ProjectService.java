package org.baseplayer.project;

import java.io.IOException;
import java.nio.file.Path;

import org.baseplayer.io.UserPreferences;
import org.baseplayer.services.ThreadRunner;

/**
 * Save/load orchestration for the live {@link ProjectDocument} SSOT.
 * Durable state is mutated on the live document; save deep-copies it.
 * Load replaces the document and rebuilds runtime via {@link SessionRuntime}.
 */
public final class ProjectService {

  private ProjectService() {}

  /**
   * Sync volatile runtime into the live document, then return a deep copy for IO.
   * Prefer mutating the live document directly; this sync is the save-time safety net.
   */
  public static ProjectDocument snapshotForSave(Path projectFile) {
    SessionDocumentSync.syncAll(projectFile);
    ProjectDocument snap = ProjectSerializer.deepCopy(ProjectSessionState.get().getDocument());
    finalizeDocumentName(snap, projectFile);
    return snap;
  }

  /** @deprecated use {@link #snapshotForSave(Path)} — kept for any external callers. */
  @Deprecated
  public static ProjectDocument capture(Path projectFile) {
    return snapshotForSave(projectFile);
  }

  public static void save(Path projectFile) throws IOException {
    if (projectFile == null) {
      throw new IllegalArgumentException("projectFile is required");
    }
    ProjectDocument doc = snapshotForSave(projectFile);
    writeDocumentAndCache(doc, projectFile);
    ProjectSessionState.get().replaceDocument(ProjectSerializer.deepCopy(doc));
    ProjectSessionState.get().setOpened(projectFile, doc.name);
    UserPreferences.addRecentProject(projectFile.toFile());
  }

  /**
   * Snapshot on the FX thread, then write JSON + variant cache in the background.
   */
  public static void saveAsync(Path projectFile, Runnable onSuccess, java.util.function.Consumer<Exception> onError) {
    if (projectFile == null) {
      if (onError != null) {
        onError.accept(new IllegalArgumentException("projectFile is required"));
      }
      return;
    }

    ProjectDocument doc;
    try {
      doc = snapshotForSave(projectFile);
    } catch (Exception e) {
      if (onError != null) {
        onError.accept(e);
      }
      return;
    }

    final ProjectDocument snapshot = doc;
    final Exception[] writeError = new Exception[1];
    ThreadRunner.get().submit("Saving project…",
        () -> {
          try {
            writeDocumentAndCache(snapshot, projectFile);
            return Boolean.TRUE;
          } catch (Exception e) {
            writeError[0] = e;
            return null;
          }
        },
        ok -> {
          if (ok == null || writeError[0] != null) {
            Exception err = writeError[0] != null
                ? writeError[0]
                : new IOException("Session save failed");
            if (onError != null) {
              onError.accept(err);
            }
            return;
          }
          ProjectSessionState.get().replaceDocument(ProjectSerializer.deepCopy(snapshot));
          ProjectSessionState.get().setOpened(projectFile, snapshot.name);
          UserPreferences.addRecentProject(projectFile.toFile());
          if (onSuccess != null) {
            onSuccess.run();
          }
        });
  }

  /**
   * Install {@code document} as the live SSOT and rebuild runtime from it.
   */
  public static void loadAsync(Path projectFile, ProjectDocument document, Runnable onDone) {
    if (document != null) {
      ProjectSessionState.get().replaceDocument(document);
    }
    SessionRuntime.applyAsync(projectFile, document, onDone);
  }

  private static void finalizeDocumentName(ProjectDocument doc, Path projectFile) {
    if (doc == null || projectFile == null) {
      return;
    }
    if (doc.name == null || doc.name.isBlank()) {
      String fn = projectFile.getFileName().toString();
      int dot = fn.lastIndexOf('.');
      doc.name = dot > 0 ? fn.substring(0, dot) : fn;
    }
  }

  private static void writeDocumentAndCache(ProjectDocument doc, Path projectFile) throws IOException {
    ProjectSerializer.write(doc, projectFile);
    try {
      VariantCacheStore.writeSessionCache(projectFile);
    } catch (Exception e) {
      System.err.println("Failed to write variant session cache: " + e.getMessage());
      e.printStackTrace();
      if (e instanceof IOException io) {
        throw io;
      }
      throw new IOException("Failed to write variant session cache", e);
    }
  }
}
