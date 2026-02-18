package org.baseplayer.io.readers;

import java.io.IOException;

/**
 * RANS (range Asymmetric Numeral Systems) decompression for CRAM files.
 * Supports both order-0 (memoryless) and order-1 (context-based) decoding.
 * 
 * Extracted from CRAMFileReader to separate compression algorithm concerns.
 */
public class RANSDecompressor {

  /**
   * Decompress rANS-encoded data.
   * Data format: 1-byte order, 4-byte compressed size, 4-byte uncompressed size, then compressed data.
   */
  public static byte[] decompress(byte[] data) throws IOException {
    if (data.length < 9) throw new IOException("rANS data too short");
    int order = data[0] & 0xFF;
    int compSize = readInt32LE(data, 1);
    int uncompSize = readInt32LE(data, 5);
    System.err.println("rANS: order=" + order + " compSize=" + compSize
                     + " uncompSize=" + uncompSize + " data.length=" + data.length);
    if (uncompSize <= 0) return new byte[0];

    int ptr = 9; // past the 9-byte prefix
    if (order == 0) {
      return decodeOrder0(data, ptr, uncompSize);
    } else {
      return decodeOrder1(data, ptr, uncompSize);
    }
  }

  // ── rANS order-0 (memoryless) ──────────────────────────────────

  private static byte[] decodeOrder0(byte[] data, int ptr, int outSize) throws IOException {
    int[] freq = new int[256];
    int[] cumFreq = new int[257];
    int[] ptrArr = {ptr};

    readFreqTable0(data, ptrArr, freq);
    ptr = ptrArr[0];

    // Build cumulative frequencies
    cumFreq[0] = 0;
    for (int i = 0; i < 256; i++) cumFreq[i + 1] = cumFreq[i] + freq[i];
    int totalFreq = cumFreq[256];

    // Normalize to 4096 if needed
    if (totalFreq != 4096) {
      normalizeFreqs(freq, cumFreq, 4096);
    }

    // Build reverse lookup
    byte[] lookup = new byte[4096];
    for (int sym = 0; sym < 256; sym++) {
      for (int j = cumFreq[sym]; j < cumFreq[sym + 1]; j++) {
        lookup[j] = (byte) sym;
      }
    }

    // Init 4 rANS states
    long[] R = new long[4];
    for (int i = 0; i < 4; i++) {
      R[i] = readUInt32LE(data, ptr);
      ptr += 4;
    }

    System.err.println("rANS O0: ptr after states=" + ptr + " data.length=" + data.length + " outSize=" + outSize);
    byte[] output = new byte[outSize];
    for (int i = 0; i < outSize; i++) {
      int idx = i & 3; // i % 4
      int f = (int) (R[idx] & 0xFFF); // cumulative freq
      int sym = lookup[f] & 0xFF;
      output[i] = (byte) sym;

      // Advance step
      R[idx] = freq[sym] * (R[idx] >> 12) + (R[idx] & 0xFFF) - cumFreq[sym];

      // Renormalize
      while (R[idx] < (1L << 23)) {
        if (ptr >= data.length) {
          System.err.println("rANS O0: out of data at i=" + i + "/" + outSize + " ptr=" + ptr
                           + " state=" + R[idx] + " sym=" + sym);
          return output;
        }
        R[idx] = (R[idx] << 8) | (data[ptr++] & 0xFF);
      }
    }
    return output;
  }

  // ── rANS order-1 (context-based) ───────────────────────────────

  private static byte[] decodeOrder1(byte[] data, int ptr, int outSize) throws IOException {
    int[][] freq = new int[256][256];
    int[][] cumFreq = new int[256][257];
    byte[][] lookup = new byte[256][4096];
    int[] ptrArr = {ptr};

    // Read frequency tables: same structure as order-0 RLE but nested per context
    readFreqTable1(data, ptrArr, freq, cumFreq, lookup);
    ptr = ptrArr[0];

    // Init 4 rANS states + last-symbol trackers
    long[] R = new long[4];
    int[] L = new int[4]; // last symbol for each state
    for (int i = 0; i < 4; i++) {
      R[i] = readUInt32LE(data, ptr);
      ptr += 4;
    }

    System.err.println("rANS O1: ptr after states=" + ptr + " data.length=" + data.length + " outSize=" + outSize);
    byte[] output = new byte[outSize];
    int outSize4 = outSize / 4;

    for (int i = 0; i < outSize4; i++) {
      for (int j = 0; j < 4; j++) {
        int ctx = L[j];
        int f = (int) (R[j] & 0xFFF);
        int sym = lookup[ctx][f] & 0xFF;
        output[i + j * outSize4] = (byte) sym;

        R[j] = freq[ctx][sym] * (R[j] >> 12) + (R[j] & 0xFFF) - cumFreq[ctx][sym];
        while (R[j] < (1L << 23)) {
          if (ptr >= data.length) {
            System.err.println("rANS O1: out of data at i=" + i + " j=" + j + " outSize4=" + outSize4
                             + " ptr=" + ptr + " state=" + R[j] + " sym=" + sym + " ctx=" + ctx);
            return output;
          }
          R[j] = (R[j] << 8) | (data[ptr++] & 0xFF);
        }
        L[j] = sym;
      }
    }
    // Remainder using state 3
    for (int i = outSize4 * 4; i < outSize; i++) {
      int ctx = L[3];
      int f = (int) (R[3] & 0xFFF);
      int sym = lookup[ctx][f] & 0xFF;
      output[i] = (byte) sym;

      R[3] = freq[ctx][sym] * (R[3] >> 12) + (R[3] & 0xFFF) - cumFreq[ctx][sym];
      while (R[3] < (1L << 23)) {
        if (ptr >= data.length) {
          System.err.println("rANS O1 remainder: out of data at i=" + i + " ptr=" + ptr);
          return output;
        }
        R[3] = (R[3] << 8) | (data[ptr++] & 0xFF);
      }
      L[3] = sym;
    }
    return output;
  }

  // ── Frequency table reading (RLE format) ───────────────────────

  private static void readFreqTable0(byte[] data, int[] ptrArr, int[] freq) {
    int ptr = ptrArr[0];
    for (int i = 0; i < 256; i++) freq[i] = 0;

    int sym = data[ptr++] & 0xFF;
    int lastSym = sym;
    int rle = 0;

    while (true) {
      int f = data[ptr++] & 0xFF;
      if (f >= 128) f = ((f & 0x7F) << 8) | (data[ptr++] & 0xFF);
      freq[sym] = f;

      if (rle > 0) {
        rle--;
        sym++;
      } else {
        sym = data[ptr++] & 0xFF;
        if (sym == 0) break;
        if (sym == ((lastSym + 1) & 0xFF)) {
          rle = data[ptr++] & 0xFF;
        }
      }
      lastSym = sym;
    }
    ptrArr[0] = ptr;
  }

  private static void readFreqTable1(byte[] data, int[] ptrArr, int[][] freq,
                                     int[][] cumFreq, byte[][] lookup) {
    int sym = data[ptrArr[0]++] & 0xFF;
    int lastSym = sym;
    int rle = 0;

    while (true) {
      int[] f0 = new int[256];
      readFreqTable0(data, ptrArr, f0);
      freq[sym] = f0;

      // Build cumulative + lookup for this context
      int total = 0;
      for (int i = 0; i < 256; i++) total += f0[i];
      if (total != 4096) normalizeFreqs(f0, null, 4096);
      cumFreq[sym][0] = 0;
      for (int i = 0; i < 256; i++) cumFreq[sym][i + 1] = cumFreq[sym][i] + f0[i];
      for (int s = 0; s < 256; s++) {
        for (int j = cumFreq[sym][s]; j < cumFreq[sym][s + 1]; j++) {
          lookup[sym][j] = (byte) s;
        }
      }
      freq[sym] = f0;

      if (rle > 0) {
        rle--;
        sym++;
      } else {
        sym = data[ptrArr[0]++] & 0xFF;
        if (sym == 0) break;
        if (sym == ((lastSym + 1) & 0xFF)) {
          rle = data[ptrArr[0]++] & 0xFF;
        }
      }
      lastSym = sym;
    }
  }

  private static void normalizeFreqs(int[] freq, int[] cumFreq, int target) {
    int total = 0;
    for (int f : freq) total += f;
    if (total == 0 || total == target) return;

    int maxIdx = 0;
    for (int i = 1; i < 256; i++) if (freq[i] > freq[maxIdx]) maxIdx = i;

    int newTotal = 0;
    for (int i = 0; i < 256; i++) {
      if (freq[i] == 0) continue;
      freq[i] = Math.max(1, (int) ((long) freq[i] * target / total));
      newTotal += freq[i];
    }
    freq[maxIdx] += target - newTotal;

    if (cumFreq != null) {
      cumFreq[0] = 0;
      for (int i = 0; i < 256; i++) cumFreq[i + 1] = cumFreq[i] + freq[i];
    }
  }

  // ── Byte-order utilities ───────────────────────────────────────

  private static int readInt32LE(byte[] data, int offset) {
    return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
         | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
  }

  private static long readUInt32LE(byte[] data, int offset) {
    return ((data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8)
          | ((data[offset + 2] & 0xFFL) << 16) | ((data[offset + 3] & 0xFFL) << 24));
  }
}
