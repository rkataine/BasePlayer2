package org.baseplayer.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SampleGroupMappingRoundTripTest {

  @TempDir
  Path temp;

  @Test
  void parseAcceptsMultipleGroupsAndDuplicateSamples() throws IOException {
    Path file = temp.resolve("groups.tsv");
    Files.writeString(file, """
        sample_name\tgroup_name
        SampleA\tTumor
        SampleB\tTumor
        SampleC\tNormal
        SampleA\tControl
        """);
    List<String[]> pairs = SampleGroupMappingImporter.parseMappingFile(file);
    assertEquals(4, pairs.size());
    assertEquals("SampleA", pairs.get(0)[0]);
    assertEquals("Tumor", pairs.get(0)[1]);
    assertEquals("SampleA", pairs.get(3)[0]);
    assertEquals("Control", pairs.get(3)[1]);
  }

  @Test
  void exportWritesHeaderAndRows() throws IOException {
    Path file = temp.resolve("out.tsv");
    List<String[]> rows = List.of(
        new String[] {"SampleA", "Tumor"},
        new String[] {"SampleB", "Normal"},
        new String[] {"SampleA", "Control"});
    SampleGroupMappingExporter.writeMappingFile(file.toFile(), rows);
    String text = Files.readString(file);
    assertTrue(text.startsWith("sample_name\tgroup_name"));
    assertTrue(text.contains("SampleA\tTumor"));
    assertTrue(text.contains("SampleA\tControl"));
    List<String[]> parsed = SampleGroupMappingImporter.parseMappingFile(file);
    assertEquals(3, parsed.size());
  }
}
