package org.baseplayer.io.readers;

import org.baseplayer.samples.alignment.BAMRecord;

/**
 * Common auxiliary tag processor for BAM and CRAM readers.
 * Extracts tag values into BAMRecord fields based on current settings.
 * <p>
 * Signal tags (uc, ud, ur) are only parsed when a specific signal tag is
 * selected via {@link #setActiveSignalTag(char)}.
 */
public class TagProcessor {

  /** Which signal tag to parse: 'c' for uc, 'd' for ud, 'l' for ul, '\0' for none. */
  private volatile char activeSignalTag;

  public void setActiveSignalTag(char tag) {
    this.activeSignalTag = tag;
  }

  public char getActiveSignalTag() {
    return activeSignalTag;
  }

  // ── BAM auxiliary block scanning ─────────────────────────────────

  /**
   * Scan a contiguous BAM auxiliary data block and populate record fields.
   * Used by BAMFileReader where tags are stored as a packed byte array.
   *
   * @return the MD:Z tag string, or null if absent
   */
  public String processTagBlock(BAMRecord rec, byte[] data) {
    String mdTag = null;
    StringBuilder extraSb = null;
    int pos = 0;
    while (pos + 2 < data.length) {
      char t1 = (char) data[pos++];
      char t2 = (char) data[pos++];
      char type = (char) data[pos++];

      // MD:Z — mismatch descriptor
      if (t1 == 'M' && t2 == 'D' && type == 'Z') {
        int start = pos;
        while (pos < data.length && data[pos] != 0) pos++;
        mdTag = new String(data, start, pos - start);
        pos++;
        continue;
      }

      // Methylation tags: MM:Z, Mm:Z, XM:Z
      if (((t1 == 'M' && (t2 == 'M' || t2 == 'm')) || (t1 == 'X' && t2 == 'M')) && type == 'Z') {
        rec.hasMethylTag = true;
        if (t1 == 'X' && t2 == 'M') {
          int start = pos;
          while (pos < data.length && data[pos] != 0) pos++;
          rec.methylString = new String(data, start, pos - start);
        } else {
          while (pos < data.length && data[pos] != 0) pos++;
        }
        pos++;
        continue;
      }

      // ML:B:C or Ml:B:C — methylation likelihoods
      if (t1 == 'M' && (t2 == 'L' || t2 == 'l') && type == 'B') {
        rec.hasMethylTag = true;
        pos = skipTagValue(data, pos, type);
        if (pos < 0) break;
        continue;
      }

      // HP:i — haplotype phase
      if (t1 == 'H' && t2 == 'P' && type == 'i') {
        if (pos + 3 < data.length) {
          rec.haplotype = readIntLE(data, pos);
        }
        pos += 4;
        continue;
      }

      // PS:i — phase set
      if (t1 == 'P' && t2 == 'S' && type == 'i') {
        if (pos + 3 < data.length) {
          rec.phaseSet = readIntLE(data, pos);
        }
        pos += 4;
        continue;
      }

      // RG:Z — read group
      if (t1 == 'R' && t2 == 'G' && type == 'Z') {
        int start = pos;
        while (pos < data.length && data[pos] != 0) pos++;
        rec.readGroup = new String(data, start, pos - start);
        pos++;
        continue;
      }

      // ud:B:s — Uncalled dwelled current signal values (short array)
      if (t1 == 'u' && t2 == 'd' && type == 'B') {
        if (activeSignalTag == 'd' && pos + 4 < data.length && (char) data[pos] == 's') {
          pos++; // skip element type 's'
          int count = readIntLE(data, pos);
          pos += 4;
          if (pos + count * 2 <= data.length) {
            rec.signalTag = readShortArrayLE(data, pos, count);
          }
          pos += count * 2;
        } else {
          pos = skipTagValue(data, pos, type);
          if (pos < 0) break;
        }
        continue;
      }

      // uc:B:s — Uncalled current signal values (short array)
      if (t1 == 'u' && t2 == 'c' && type == 'B') {
        if (activeSignalTag == 'c' && pos + 4 < data.length && (char) data[pos] == 's') {
          pos++; // skip element type 's'
          int count = readIntLE(data, pos);
          pos += 4;
          if (pos + count * 2 <= data.length) {
            rec.signalTag = readShortArrayLE(data, pos, count);
          }
          pos += count * 2;
        } else {
          pos = skipTagValue(data, pos, type);
          if (pos < 0) break;
        }
        continue;
      }

      // ul:B:s — Uncalled signal values (short array)
      if (t1 == 'u' && t2 == 'l' && type == 'B') {
        if (activeSignalTag == 'l' && pos + 4 < data.length && (char) data[pos] == 's') {
          pos++; // skip element type 's'
          int count = readIntLE(data, pos);
          pos += 4;
          if (pos + count * 2 <= data.length) {
            rec.signalTag = readShortArrayLE(data, pos, count);
          }
          pos += count * 2;
        } else {
          pos = skipTagValue(data, pos, type);
          if (pos < 0) break;
        }
        continue;
      }

      // ur:B:i — Reference coordinate range for uc/ud signal (int array, typically 2 elements)
      if (t1 == 'u' && t2 == 'r' && type == 'B') {
        if (activeSignalTag != '\0' && pos + 4 < data.length && (char) data[pos] == 'i') {
          pos++; // skip element type 'i'
          int count = readIntLE(data, pos);
          pos += 4;
          if (pos + count * 4 <= data.length) {
            rec.urTag = readIntArrayLE(data, pos, count);
          }
          pos += count * 4;
        } else {
          pos = skipTagValue(data, pos, type);
          if (pos < 0) break;
        }
        continue;
      }

      // SA:Z — supplementary / chimeric alignment descriptor
      if (t1 == 'S' && t2 == 'A' && type == 'Z') {
        int start = pos;
        while (pos < data.length && data[pos] != 0) pos++;
        rec.saTag = new String(data, start, pos - start);
        pos++;
        continue;
      }

      // Unknown tag — capture displayable value for the info popup (skip B arrays)
      if (type != 'B') {
        String dv = extractBamTagValue(data, pos, type);
        if (dv != null) {
          if (extraSb == null) extraSb = new StringBuilder();
          else extraSb.append(';');
          extraSb.append(t1).append(t2).append('=').append(dv);
        }
      }
      pos = skipTagValue(data, pos, type);
      if (pos < 0) break;
    }

    if (extraSb != null) rec.extraTagsLine = extraSb.toString();
    return mdTag;
  }

  // ── Individual tag processing (for CRAM) ─────────────────────────

  /**
   * Process a single decoded tag and populate record fields.
   * Used by CRAMDecoder where each tag is decoded individually.
   *
   * @param c1   first char of the tag name
   * @param c2   second char of the tag name
   * @param type BAM type code ('Z', 'i', 'B', etc.)
   * @param data decoded tag value bytes (BAM value format, without tag name/type prefix)
   */
  public void processTag(BAMRecord rec, char c1, char c2, char type, byte[] data) {
    if (data == null) return;

    // Methylation tags: MM:Z, Mm:Z, XM:Z
    if (((c1 == 'M' && (c2 == 'M' || c2 == 'm')) || (c1 == 'X' && c2 == 'M')) && type == 'Z') {
      rec.hasMethylTag = true;
      if (c1 == 'X' && c2 == 'M') {
        int len = data.length;
        if (len > 0 && data[len - 1] == 0) len--;
        rec.methylString = new String(data, 0, len);
      }
      return;
    }

    // ML:B:C or Ml:B:C — methylation likelihoods
    if (c1 == 'M' && (c2 == 'L' || c2 == 'l') && type == 'B') {
      rec.hasMethylTag = true;
      return;
    }

    // HP:i — haplotype phase
    if (c1 == 'H' && c2 == 'P' && type == 'i' && data.length >= 4) {
      rec.haplotype = readIntLE(data, 0);
      return;
    }

    // PS:i — phase set
    if (c1 == 'P' && c2 == 'S' && type == 'i' && data.length >= 4) {
      rec.phaseSet = readIntLE(data, 0);
      return;
    }

    // RG:Z — read group
    if (c1 == 'R' && c2 == 'G' && type == 'Z') {
      int len = data.length;
      if (len > 0 && data[len - 1] == 0) len--;
      rec.readGroup = new String(data, 0, len);
      return;
    }

    // SA:Z — supplementary / chimeric alignment descriptor
    if (c1 == 'S' && c2 == 'A' && type == 'Z') {
      int len = data.length;
      if (len > 0 && data[len - 1] == 0) len--;
      rec.saTag = new String(data, 0, len);
      return;
    }

    // Signal tags — only parsed when an active signal tag is selected
    if (activeSignalTag != '\0') {
      // ud:B:s — Uncalled dwelled current signal values
      if (c1 == 'u' && c2 == 'd' && type == 'B' && data.length >= 5 && (char) data[0] == 's') {
        if (activeSignalTag == 'd') {
          int count = readIntLE(data, 1);
          if (5 + count * 2 <= data.length) rec.signalTag = readShortArrayLE(data, 5, count);
        }
        return;
      }
      // uc:B:s — Uncalled current signal values
      if (c1 == 'u' && c2 == 'c' && type == 'B' && data.length >= 5 && (char) data[0] == 's') {
        if (activeSignalTag == 'c') {
          int count = readIntLE(data, 1);
          if (5 + count * 2 <= data.length) rec.signalTag = readShortArrayLE(data, 5, count);
        }
        return;
      }
      // ul:B:s — Uncalled signal values
      if (c1 == 'u' && c2 == 'l' && type == 'B' && data.length >= 5 && (char) data[0] == 's') {
        if (activeSignalTag == 'l') {
          int count = readIntLE(data, 1);
          if (5 + count * 2 <= data.length) rec.signalTag = readShortArrayLE(data, 5, count);
        }
        return;
      }
      // ur:B:i — Reference coordinate range for signal tag
      if (c1 == 'u' && c2 == 'r' && type == 'B' && data.length >= 5 && (char) data[0] == 'i') {
        int count = readIntLE(data, 1);
        if (5 + count * 4 <= data.length) rec.urTag = readIntArrayLE(data, 5, count);
        return;
      }
    }

    // Unknown tag — capture for display in info popup (skip B arrays)
    if (type != 'B') {
      String dv = extractCramTagValue(data, type);
      if (dv != null) {
        String entry = "" + c1 + c2 + "=" + dv;
        rec.extraTagsLine = rec.extraTagsLine == null ? entry : rec.extraTagsLine + ";" + entry;
      }
    }
  }

  // ── Binary reading helpers ───────────────────────────────────────

  static int readIntLE(byte[] data, int pos) {
    return (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8)
        | ((data[pos + 2] & 0xFF) << 16) | ((data[pos + 3] & 0xFF) << 24);
  }

  static short[] readShortArrayLE(byte[] data, int pos, int count) {
    short[] arr = new short[count];
    for (int i = 0; i < count; i++) {
      arr[i] = (short) ((data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8));
      pos += 2;
    }
    return arr;
  }

  static int[] readIntArrayLE(byte[] data, int pos, int count) {
    int[] arr = new int[count];
    for (int i = 0; i < count; i++) {
      arr[i] = readIntLE(data, pos);
      pos += 4;
    }
    return arr;
  }

  /**
   * Skip a BAM tag value based on its type code.
   * Returns new position, or -1 on error.
   */
  /** Extract a human-readable string for an unknown BAM aux tag value (non-B types). */
  private static String extractBamTagValue(byte[] data, int pos, char type) {
    return switch (type) {
      case 'A' -> pos < data.length ? String.valueOf((char)(data[pos] & 0xFF)) : null;
      case 'c' -> pos < data.length ? String.valueOf((int)(byte)data[pos]) : null;
      case 'C' -> pos < data.length ? String.valueOf(data[pos] & 0xFF) : null;
      case 's' -> pos + 1 < data.length
          ? String.valueOf((short)((data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8))) : null;
      case 'S' -> pos + 1 < data.length
          ? String.valueOf((data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8)) : null;
      case 'i' -> pos + 3 < data.length ? String.valueOf(readIntLE(data, pos)) : null;
      case 'I' -> pos + 3 < data.length
          ? Long.toString(Integer.toUnsignedLong(readIntLE(data, pos))) : null;
      case 'f' -> pos + 3 < data.length
          ? String.format("%.4g", Float.intBitsToFloat(readIntLE(data, pos))) : null;
      case 'Z', 'H' -> {
        int start = pos, end = pos;
        while (end < data.length && data[end] != 0) end++;
        if (end == start) yield null;
        String s = new String(data, start, end - start);
        yield s.length() > 60 ? s.substring(0, 57) + "..." : s;
      }
      default -> null;
    };
  }

  /** Extract a human-readable string for a CRAM decoded tag value. */
  private static String extractCramTagValue(byte[] data, char type) {
    int len = data.length;
    return switch (type) {
      case 'A' -> len >= 1 ? String.valueOf((char)(data[0] & 0xFF)) : null;
      case 'c' -> len >= 1 ? String.valueOf((int)(byte)data[0]) : null;
      case 'C' -> len >= 1 ? String.valueOf(data[0] & 0xFF) : null;
      case 's' -> len >= 2
          ? String.valueOf((short)((data[0] & 0xFF) | ((data[1] & 0xFF) << 8))) : null;
      case 'S' -> len >= 2
          ? String.valueOf((data[0] & 0xFF) | ((data[1] & 0xFF) << 8)) : null;
      case 'i' -> len >= 4 ? String.valueOf(readIntLE(data, 0)) : null;
      case 'I' -> len >= 4 ? Long.toString(Integer.toUnsignedLong(readIntLE(data, 0))) : null;
      case 'f' -> len >= 4
          ? String.format("%.4g", Float.intBitsToFloat(readIntLE(data, 0))) : null;
      case 'Z', 'H' -> {
        int strLen = len;
        if (strLen > 0 && data[strLen - 1] == 0) strLen--;
        if (strLen == 0) yield null;
        String s = new String(data, 0, strLen);
        yield s.length() > 60 ? s.substring(0, 57) + "..." : s;
      }
      default -> null;
    };
  }

  static int skipTagValue(byte[] data, int pos, char type) {
    switch (type) {
      case 'A', 'c', 'C' -> { return pos + 1; }
      case 's', 'S' -> { return pos + 2; }
      case 'i', 'I', 'f' -> { return pos + 4; }
      case 'd' -> { return pos + 8; }
      case 'Z', 'H' -> {
        while (pos < data.length && data[pos] != 0) pos++;
        return pos + 1;
      }
      case 'B' -> {
        if (pos + 4 >= data.length) return -1;
        char elemType = (char) data[pos++];
        int count = readIntLE(data, pos);
        pos += 4;
        int elemSize = switch (elemType) {
          case 'c', 'C' -> 1;
          case 's', 'S' -> 2;
          case 'i', 'I', 'f' -> 4;
          case 'd' -> 8;
          default -> 0;
        };
        return pos + count * elemSize;
      }
      default -> { return -1; }
    }
  }
}
