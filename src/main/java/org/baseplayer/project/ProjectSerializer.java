package org.baseplayer.project;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Gson read/write / deep-copy for {@link ProjectDocument}.
 */
public final class ProjectSerializer {

  private static final Gson GSON = new GsonBuilder()
      .setPrettyPrinting()
      .create();

  private ProjectSerializer() {}

  public static void write(ProjectDocument document, Path file) throws IOException {
    try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      GSON.toJson(document, writer);
    }
  }

  public static ProjectDocument read(Path file) throws IOException {
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      ProjectDocument doc = GSON.fromJson(reader, ProjectDocument.class);
      if (doc == null) {
        throw new IOException("Empty or invalid project file: " + file);
      }
      if (doc.schemaVersion <= 0) {
        doc.schemaVersion = 1;
      }
      return doc;
    }
  }

  /** Deep copy via Gson (safe snapshot for async save). */
  public static ProjectDocument deepCopy(ProjectDocument document) {
    if (document == null) {
      return new ProjectDocument();
    }
    ProjectDocument copy = GSON.fromJson(GSON.toJson(document), ProjectDocument.class);
    return copy != null ? copy : new ProjectDocument();
  }
}
