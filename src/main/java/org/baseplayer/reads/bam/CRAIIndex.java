package org.baseplayer.reads.bam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Reads and queries CRAI (CRAM Index) files.
 * CRAI files are gzip-compressed TSV with columns:
 * seqId, alignmentStart, alignmentSpan, containerOffset, sliceOffset, sliceSize
 */
public class CRAIIndex {

  public static class Entry {
    public final int seqId;
    public final int alignmentStart;
    public final int alignmentSpan;
    public final long containerOffset;
    public final int sliceOffset;
    public final int sliceSize;

    public Entry(int seqId, int alignmentStart, int alignmentSpan,
                 long containerOffset, int sliceOffset, int sliceSize) {
      this.seqId = seqId;
      this.alignmentStart = alignmentStart;
      this.alignmentSpan = alignmentSpan;
      this.containerOffset = containerOffset;
      this.sliceOffset = sliceOffset;
      this.sliceSize = sliceSize;
    }
  }

  private final List<Entry> entries;

  public CRAIIndex(Path craiPath) throws IOException {
    entries = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(
           new InputStreamReader(new GZIPInputStream(Files.newInputStream(craiPath))))) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) continue;
        String[] fields = line.split("\t");
        if (fields.length < 6) continue;
        entries.add(new Entry(
          Integer.parseInt(fields[0]),
          Integer.parseInt(fields[1]),
          Integer.parseInt(fields[2]),
          Long.parseLong(fields[3]),
          Integer.parseInt(fields[4]),
          Integer.parseInt(fields[5])
        ));
      }
    }
  }

  /**
   * Get CRAI entries overlapping [start, end) for the given reference sequence.
   * CRAI positions are 1-based.
   */
  public List<Entry> getEntries(int seqId, int start, int end) {
    List<Entry> result = new ArrayList<>();
    for (Entry e : entries) {
      if (e.seqId != seqId) continue;
      // Check overlap: entry covers [alignmentStart, alignmentStart+alignmentSpan)
      int entryEnd = e.alignmentStart + e.alignmentSpan;
      if (entryEnd > start && e.alignmentStart < end) {
        result.add(e);
      }
    }
    if (result.isEmpty()) return Collections.emptyList();
    // Sort by container offset for sequential I/O
    result.sort((a, b) -> Long.compare(a.containerOffset, b.containerOffset));
    return result;
  }
}
