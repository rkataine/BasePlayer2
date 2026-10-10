package org.baseplayer.sanger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses Applied Biosystems ABIF ({@code .ab1}) chromatogram files.
 * Reads analyzed traces (DATA 9–12), base calls (PBAS/PLOC), and qualities (PCON).
 */
public final class Ab1Reader {

  private final int[] traceA;
  private final int[] traceC;
  private final int[] traceG;
  private final int[] traceT;
  private final List<Integer> baseCalls;
  private final byte[] qualityScores;
  private final String sequence;
  private final int traceLength;

  public Ab1Reader(Path path) throws IOException {
    if (path == null) {
      throw new IllegalArgumentException("path is null");
    }
    Parsed parsed = parse(Files.readAllBytes(path));
    this.traceA = parsed.traceA;
    this.traceC = parsed.traceC;
    this.traceG = parsed.traceG;
    this.traceT = parsed.traceT;
    this.baseCalls = Collections.unmodifiableList(parsed.baseCalls);
    this.qualityScores = parsed.qualityScores;
    this.sequence = parsed.sequence;
    this.traceLength = parsed.traceLength;
  }

  public String getSequence() {
    return sequence;
  }

  public int[] getTraceA() {
    return traceA;
  }

  public int[] getTraceC() {
    return traceC;
  }

  public int[] getTraceG() {
    return traceG;
  }

  public int[] getTraceT() {
    return traceT;
  }

  public List<Integer> getBaseCalls() {
    return baseCalls;
  }

  public byte[] getQualityScores() {
    return qualityScores;
  }

  public int getTraceLength() {
    return traceLength;
  }

  private static Parsed parse(byte[] fileContent) throws IOException {
    if (fileContent.length < 30) {
      throw new IOException("AB1 file too short (" + fileContent.length + " bytes)");
    }
    String magic = new String(fileContent, 0, 4, StandardCharsets.US_ASCII);
    if (!"ABIF".equals(magic) && !"ABI\0".equals(magic)) {
      throw new IOException("Not an ABIF file (header=" + magic + ")");
    }

    ByteBuffer buf = ByteBuffer.wrap(fileContent).order(ByteOrder.BIG_ENDIAN);
    int numTags = buf.getInt(18);
    int indexOffset = buf.getInt(26);
    if (numTags < 0 || indexOffset < 0 || indexOffset + (long) numTags * 28 > fileContent.length) {
      throw new IOException("Invalid ABIF directory (tags=" + numTags + ", offset=" + indexOffset + ")");
    }

    int[] traceA = null;
    int[] traceC = null;
    int[] traceG = null;
    int[] traceT = null;
    List<Integer> baseCalls = new ArrayList<>();
    byte[] qualityScores = null;
    String sequence = null;
    int traceLength = 0;

    for (int i = 0; i < numTags; i++) {
      int entryPos = indexOffset + (i * 28);
      byte[] tagNameB = new byte[4];
      buf.position(entryPos);
      buf.get(tagNameB);
      String tagName = new String(tagNameB, StandardCharsets.US_ASCII);

      int tagNumber = buf.getInt(entryPos + 4);
      int numElements = buf.getInt(entryPos + 12);
      int dataSize = buf.getInt(entryPos + 16);
      int dataOffset = buf.getInt(entryPos + 20);
      if (dataSize <= 4) {
        dataOffset = entryPos + 20;
      }
      if (numElements < 0 || dataOffset < 0 || dataOffset > fileContent.length) {
        continue;
      }

      if ("DATA".equals(tagName) && tagNumber >= 9 && tagNumber <= 12) {
        int[] trace = readShortTrace(buf, dataOffset, numElements, fileContent.length);
        if (trace == null) {
          continue;
        }
        switch (tagNumber) {
          case 9 -> traceG = trace;
          case 10 -> traceA = trace;
          case 11 -> traceT = trace;
          case 12 -> traceC = trace;
          default -> { }
        }
        traceLength = numElements;
      } else if ("PBAS".equals(tagName) && tagNumber == 2) {
        sequence = readAscii(buf, dataOffset, numElements, fileContent.length);
      } else if ("PBAS".equals(tagName) && tagNumber == 1 && sequence == null) {
        sequence = readAscii(buf, dataOffset, numElements, fileContent.length);
      } else if ("PLOC".equals(tagName) && tagNumber == 2) {
        baseCalls = readShortList(buf, dataOffset, numElements, fileContent.length);
      } else if ("PLOC".equals(tagName) && tagNumber == 1 && baseCalls.isEmpty()) {
        baseCalls = readShortList(buf, dataOffset, numElements, fileContent.length);
      } else if ("PCON".equals(tagName) && (tagNumber == 1 || tagNumber == 2)) {
        byte[] quals = readBytes(buf, dataOffset, numElements, fileContent.length);
        if (quals != null && (qualityScores == null || tagNumber == 2)) {
          qualityScores = quals;
        }
      }
    }

    if (traceA == null || traceC == null || traceG == null || traceT == null) {
      throw new IOException("AB1 missing analyzed DATA 9–12 traces");
    }
    if (sequence == null || sequence.isEmpty()) {
      throw new IOException("AB1 missing PBAS base calls");
    }
    if (baseCalls.isEmpty()) {
      throw new IOException("AB1 missing PLOC peak locations");
    }

    Parsed parsed = new Parsed();
    parsed.traceA = traceA;
    parsed.traceC = traceC;
    parsed.traceG = traceG;
    parsed.traceT = traceT;
    parsed.baseCalls = baseCalls;
    parsed.qualityScores = qualityScores != null ? qualityScores : new byte[0];
    parsed.sequence = sequence;
    parsed.traceLength = traceLength;
    return parsed;
  }

  private static int[] readShortTrace(ByteBuffer buf, int offset, int numElements, int fileLen) {
    if (numElements <= 0 || offset + (long) numElements * 2 > fileLen) {
      return null;
    }
    int[] trace = new int[numElements];
    buf.position(offset);
    for (int k = 0; k < numElements; k++) {
      trace[k] = buf.getShort() & 0xFFFF;
    }
    return trace;
  }

  private static List<Integer> readShortList(ByteBuffer buf, int offset, int numElements, int fileLen) {
    if (numElements <= 0 || offset + (long) numElements * 2 > fileLen) {
      return new ArrayList<>();
    }
    List<Integer> list = new ArrayList<>(numElements);
    buf.position(offset);
    for (int k = 0; k < numElements; k++) {
      list.add((int) (buf.getShort() & 0xFFFF));
    }
    return list;
  }

  private static String readAscii(ByteBuffer buf, int offset, int numElements, int fileLen) {
    byte[] bytes = readBytes(buf, offset, numElements, fileLen);
    return bytes == null ? null : new String(bytes, StandardCharsets.US_ASCII);
  }

  private static byte[] readBytes(ByteBuffer buf, int offset, int numElements, int fileLen) {
    if (numElements <= 0 || offset + (long) numElements > fileLen) {
      return null;
    }
    byte[] bytes = new byte[numElements];
    buf.position(offset);
    buf.get(bytes);
    return bytes;
  }

  private static final class Parsed {
    int[] traceA;
    int[] traceC;
    int[] traceG;
    int[] traceT;
    List<Integer> baseCalls;
    byte[] qualityScores;
    String sequence;
    int traceLength;
  }
}
