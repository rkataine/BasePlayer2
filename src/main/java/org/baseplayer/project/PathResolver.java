package org.baseplayer.project;

import java.nio.file.Path;

/**
 * Resolves absolute paths and paths relative to a project file directory.
 */
public final class PathResolver {

  private PathResolver() {}

  public static String toAbsoluteString(Path path) {
    if (path == null) return null;
    return path.toAbsolutePath().normalize().toString();
  }

  public static String toRelativeString(Path path, Path projectFile) {
    if (path == null || projectFile == null) return null;
    try {
      Path projectDir = projectFile.toAbsolutePath().normalize().getParent();
      if (projectDir == null) return null;
      Path absolute = path.toAbsolutePath().normalize();
      if (!absolute.startsWith(projectDir)) return null;
      return projectDir.relativize(absolute).toString();
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Prefer relative path when present and resolvable; otherwise absolute.
   */
  public static Path resolve(String absolute, String relative, Path projectFile) {
    if (projectFile != null && relative != null && !relative.isBlank()) {
      Path projectDir = projectFile.toAbsolutePath().normalize().getParent();
      if (projectDir != null) {
        Path candidate = projectDir.resolve(relative).normalize();
        if (candidate.toFile().exists()) {
          return candidate;
        }
      }
    }
    if (absolute != null && !absolute.isBlank()) {
      return Path.of(absolute).toAbsolutePath().normalize();
    }
    return null;
  }
}
