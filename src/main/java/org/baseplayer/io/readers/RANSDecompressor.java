package org.baseplayer.io.readers;

import java.io.IOException;

/**
 * RANS (range Asymmetric Numeral Systems) decompression for CRAM files.
 * Supports both order-0 (memoryless) and order-1 (context-based) decoding.
 *
 * Uses TF_SHIFT=12 (modulus 4096), matching the htscodecs library used by samtools.
 * O0 uses round-robin interleaving; O1 uses striped interleaving.
 */
public class RANSDecompressor {

  /**
   * Decompress rANS-encoded data.
   * Data format: 1-byte order, 4-byte compressed size, 4-byte uncompressed size, then data.
   */
  public static byte[] decompress(byte[] data) throws IOException {
    if (data.length < 9) throw new IOException("rANS data too short");
    int order      = data[0] & 0xFF;
    int uncompSize = readInt32LE(data, 5);
    if (uncompSize <= 0) return new byte[0];

    int ptr = 9; // past the 9-byte prefix
    if (order == 0) {
      return decodeOrder0(data, ptr, uncompSize);
    } else {
      return decodeOrder1(data, ptr, uncompSize);
    }
  }

  // ── rANS order-0 (memoryless) ──────────────────────────────────

  private static byte[] decodeOrder0(byte[] data, int ptr, int outSize) {
    int[] freq    = new int[256];
    int[] cumFreq = new int[257];
    int[] ptrArr  = {ptr};

    readFreqTable0(data, ptrArr, freq);
    ptr = ptrArr[0];

    cumFreq[0] = 0;
    for (int i = 0; i < 256; i++) cumFreq[i + 1] = cumFreq[i] + freq[i];
    int M = 1 << 12; // TF_SHIFT=12 → modulus 4096, matching htscodecs

    byte[] lookup = new byte[M];
    for (int sym = 0; sym < 256; sym++) {
      for (int j = cumFreq[sym]; j < cumFreq[sym + 1] && j < M; j++) {
        lookup[j] = (byte) sym;
      }
    }

    // Init 4 rANS states
    long[] R = new long[4];
    for (int i = 0; i < 4; i++) {
      R[i] = readUInt32LE(data, ptr);
      ptr += 4;
    }

    byte[] output   = new byte[outSize];
    int    outSize4 = outSize / 4;

    // Round-robin interleaving: output[4*i+0], output[4*i+1], output[4*i+2], output[4*i+3]
    for (int i = 0; i < outSize4; i++) {
      for (int j = 0; j < 4; j++) {
        int f   = (int) (R[j] & (M - 1)); // R[j] & 0xFFF
        int sym = lookup[f] & 0xFF;
        output[4 * i + j] = (byte) sym;

        R[j] = (long) freq[sym] * (R[j] / M) + f - cumFreq[sym];

        // Renormalize
        while (R[j] < (1L << 23)) {
          R[j] = (R[j] << 8) | (ptr < data.length ? data[ptr++] & 0xFF : 0);
        }
      }
    }
    // Remainder bytes (outSize % 4) using corresponding states
    for (int i = outSize4 * 4; i < outSize; i++) {
      int si  = i & 3; // state index
      int f   = (int) (R[si] & (M - 1));
      int sym = lookup[f] & 0xFF;
      output[i] = (byte) sym;

      R[si] = (long) freq[sym] * (R[si] / M) + f - cumFreq[sym];
      while (R[si] < (1L << 23)) {
        R[si] = (R[si] << 8) | (ptr < data.length ? data[ptr++] & 0xFF : 0);
      }
    }
    return output;
  }

  // ── rANS order-1 (context-based) ───────────────────────────────

  private static byte[] decodeOrder1(byte[] data, int ptr, int outSize) {
    int[][] freq    = new int[256][256];
    int[][] cumFreq = new int[256][257];
    int[]   ctxM    = new int[256];
    byte[][] lookup = new byte[256][];
    int[] ptrArr = {ptr};

    readFreqTable1(data, ptrArr, freq, cumFreq, ctxM, lookup);
    ptr = ptrArr[0];

    long[] R = new long[4];
    int[]  L = new int[4];
    for (int i = 0; i < 4; i++) {
      R[i] = readUInt32LE(data, ptr);
      ptr += 4;
    }

    byte[] output   = new byte[outSize];
    int    outSize4 = outSize / 4;
    int    M_FIXED  = 1 << 12; // TF_SHIFT=12

    // Striped interleaving for O1: output[i + j*outSize4]
    for (int i = 0; i < outSize4; i++) {
      for (int j = 0; j < 4; j++) {
        int ctx = L[j];
        int M   = M_FIXED;
        int f   = (int) (R[j] & (M - 1));
        int sym = lookup[ctx][f] & 0xFF;
        output[i + j * outSize4] = (byte) sym;

        R[j] = (long) freq[ctx][sym] * (R[j] / M) + f - cumFreq[ctx][sym];
        while (R[j] < (1L << 23)) {
          R[j] = (R[j] << 8) | (ptr < data.length ? data[ptr++] & 0xFF : 0);
        }
        L[j] = sym;
      }
    }
    for (int i = outSize4 * 4; i < outSize; i++) {
      int ctx = L[3];
      int M   = M_FIXED;
      int f   = (int) (R[3] & (M - 1));
      int sym = lookup[ctx][f] & 0xFF;
      output[i] = (byte) sym;

      R[3] = (long) freq[ctx][sym] * (R[3] / M) + f - cumFreq[ctx][sym];
      while (R[3] < (1L << 23)) {
        R[3] = (R[3] << 8) | (ptr < data.length ? data[ptr++] & 0xFF : 0);
      }
      L[3] = sym;
    }
    return output;
  }

  // ── Frequency table reading (RLE format) ───────────────────────

  private static void readFreqTable0(byte[] data, int[] ptrArr, int[] freq) {
    int ptr     = ptrArr[0];
    for (int i = 0; i < 256; i++) freq[i] = 0;

    int sym     = data[ptr++] & 0xFF;
    int lastSym = sym;
    int rle     = 0;

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

  private static void readFreqTable1(byte[] data, int[] ptrArr,
                                     int[][] freq, int[][] cumFreq,
                                     int[] ctxM, byte[][] lookup) {
    int sym     = data[ptrArr[0]++] & 0xFF;
    int lastSym = sym;
    int rle     = 0;

    while (true) {
      int[] f0 = new int[256];
      readFreqTable0(data, ptrArr, f0);
      freq[sym] = f0;

      cumFreq[sym][0] = 0;
      for (int i = 0; i < 256; i++) cumFreq[sym][i + 1] = cumFreq[sym][i] + f0[i];
      int M = 1 << 12; // TF_SHIFT=12 → 4096
      ctxM[sym] = M;
      lookup[sym] = new byte[M];
      for (int s = 0; s < 256; s++) {
        for (int j = cumFreq[sym][s]; j < cumFreq[sym][s + 1] && j < M; j++) {
          lookup[sym][j] = (byte) s;
        }
      }

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
