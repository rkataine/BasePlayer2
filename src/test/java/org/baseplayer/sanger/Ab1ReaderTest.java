package org.baseplayer.sanger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Ab1ReaderTest {

  @TempDir
  Path tempDir;

  @Test
  void parsesAnalyzedTracesAndBaseCalls() throws Exception {
    Path ab1 = tempDir.resolve("tiny.ab1");
    writeMinimalAb1(ab1, "ACGTAC", new int[] {10, 30, 50, 70, 90, 110});

    Ab1Reader reader = new Ab1Reader(ab1);
    assertEquals("ACGTAC", reader.getSequence());
    assertEquals(6, reader.getBaseCalls().size());
    assertEquals(10, reader.getBaseCalls().get(0));
    assertEquals(120, reader.getTraceLength());
    assertEquals(1000, reader.getTraceA()[10]);
    assertEquals(1000, reader.getTraceC()[30]);
    assertEquals(1000, reader.getTraceG()[50]);
    assertEquals(1000, reader.getTraceT()[70]);
  }

  @Test
  void rejectsNonAbifHeader() throws Exception {
    Path bad = tempDir.resolve("bad.ab1");
    byte[] junk = new byte[64];
    System.arraycopy("NOTA".getBytes(), 0, junk, 0, 4);
    Files.write(bad, junk);
    IOException ex = assertThrows(IOException.class, () -> new Ab1Reader(bad));
    assertTrue(ex.getMessage().contains("Not an ABIF"), ex.getMessage());
  }

  @Test
  void reverseComplementHandlesIupac() {
    assertEquals("YGTRAC", SangerAligner.reverseComplement("GTYACR"));
  }

  private static void writeMinimalAb1(Path path, String sequence, int[] peakLocs) throws IOException {
    int traceLen = 120;
    byte[] seqBytes = sequence.getBytes();
    byte[] quals = new byte[sequence.length()];
    for (int i = 0; i < quals.length; i++) {
      quals[i] = 40;
    }

    byte[] dataG = shorts(traceLen, peakLocs[2], 1000);
    byte[] dataA = shorts(traceLen, peakLocs[0], 1000);
    byte[] dataT = shorts(traceLen, peakLocs[3], 1000);
    byte[] dataC = shorts(traceLen, peakLocs[1], 1000);
    byte[] ploc = new byte[peakLocs.length * 2];
    ByteBuffer plocBuf = ByteBuffer.wrap(ploc).order(ByteOrder.BIG_ENDIAN);
    for (int p : peakLocs) {
      plocBuf.putShort((short) p);
    }

    List<DirEntry> entries = new ArrayList<>();
    entries.add(new DirEntry("DATA", 9, dataG));
    entries.add(new DirEntry("DATA", 10, dataA));
    entries.add(new DirEntry("DATA", 11, dataT));
    entries.add(new DirEntry("DATA", 12, dataC));
    entries.add(new DirEntry("PBAS", 2, seqBytes));
    entries.add(new DirEntry("PLOC", 2, ploc));
    entries.add(new DirEntry("PCON", 2, quals));

    int headerSize = 128;
    int dataStart = headerSize;
    int dataBytes = 0;
    for (DirEntry e : entries) {
      e.offset = dataStart + dataBytes;
      dataBytes += e.data.length;
    }
    int indexOffset = dataStart + dataBytes;
    int fileSize = indexOffset + entries.size() * 28;

    ByteBuffer out = ByteBuffer.allocate(fileSize).order(ByteOrder.BIG_ENDIAN);
    out.put(0, (byte) 'A');
    out.put(1, (byte) 'B');
    out.put(2, (byte) 'I');
    out.put(3, (byte) 'F');
    out.putInt(18, entries.size());
    out.putInt(26, indexOffset);

    for (DirEntry e : entries) {
      out.position(e.offset);
      out.put(e.data);
    }

    for (int i = 0; i < entries.size(); i++) {
      DirEntry e = entries.get(i);
      int entryPos = indexOffset + i * 28;
      out.position(entryPos);
      out.put(e.name.getBytes());
      out.putInt(e.number);
      out.putShort((short) 1); // element type
      out.putShort((short) 1); // element size placeholder
      out.putInt(e.numElements);
      out.putInt(e.data.length);
      out.putInt(e.offset);
      out.putInt(0);
    }

    Files.write(path, out.array());
  }

  private static byte[] shorts(int length, int peakAt, int peakValue) {
    ByteBuffer buf = ByteBuffer.allocate(length * 2).order(ByteOrder.BIG_ENDIAN);
    for (int i = 0; i < length; i++) {
      buf.putShort((short) (i == peakAt ? peakValue : 0));
    }
    return buf.array();
  }

  private static final class DirEntry {
    final String name;
    final int number;
    final byte[] data;
    final int numElements;
    int offset;

    DirEntry(String name, int number, byte[] data) {
      this.name = name;
      this.number = number;
      this.data = data;
      if ("DATA".equals(name) || "PLOC".equals(name)) {
        this.numElements = data.length / 2;
      } else {
        this.numElements = data.length;
      }
    }
  }
}
