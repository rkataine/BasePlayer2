package org.baseplayer.reads.bam;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads BGZF (Blocked Gzip Format) compressed files used by BAM/BAI.
 * Supports random access via virtual file offsets.
 * 
 * Virtual offset = (compressed_block_offset << 16) | uncompressed_offset_in_block
 */
public class BGZFInputStream implements Closeable {

  private final RandomAccessFile raf;
  
  // Current decompressed block
  private byte[] currentBlock;
  private long currentBlockCompressedOffset = -1;
  private int currentBlockSize; // uncompressed size
  private int posInBlock;       // read position within decompressed block
  
  // Next block's compressed offset (for computing virtual offsets)
  private long nextBlockCompressedOffset;
  
  // Temp buffer for reading headers
  private final byte[] headerBuf = new byte[18];

  public BGZFInputStream(Path path) throws IOException {
    this.raf = new RandomAccessFile(path.toFile(), "r");
  }

  /**
   * Seek to a virtual file offset.
   * Upper 48 bits = compressed block offset, lower 16 bits = offset within decompressed block.
   */
  public void seek(long virtualOffset) throws IOException {
    long compressedOffset = virtualOffset >>> 16;
    int uncompressedOffset = (int) (virtualOffset & 0xFFFF);
    
    if (compressedOffset != currentBlockCompressedOffset || currentBlock == null) {
      loadBlock(compressedOffset);
    }
    posInBlock = uncompressedOffset;
  }

  /**
   * Get current virtual file offset.
   */
  public long getVirtualOffset() {
    return (currentBlockCompressedOffset << 16) | (posInBlock & 0xFFFF);
  }

  /**
   * Read a single byte. Returns -1 at EOF.
   */
  public int read() throws IOException {
    if (currentBlock == null) return -1;
    if (posInBlock >= currentBlockSize) {
      if (!loadNextBlock()) return -1;
    }
    return currentBlock[posInBlock++] & 0xFF;
  }

  /**
   * Read bytes into buffer. Returns number of bytes read, or -1 at EOF.
   */
  public int read(byte[] buf, int off, int len) throws IOException {
    if (currentBlock == null) return -1;
    
    int totalRead = 0;
    while (totalRead < len) {
      if (posInBlock >= currentBlockSize) {
        if (!loadNextBlock()) {
          return totalRead > 0 ? totalRead : -1;
        }
      }
      int available = currentBlockSize - posInBlock;
      int toRead = Math.min(available, len - totalRead);
      System.arraycopy(currentBlock, posInBlock, buf, off + totalRead, toRead);
      posInBlock += toRead;
      totalRead += toRead;
    }
    return totalRead;
  }

  /**
   * Read exactly len bytes. Throws IOException if not enough data.
   */
  public void readFully(byte[] buf) throws IOException {
    readFully(buf, 0, buf.length);
  }

  public void readFully(byte[] buf, int off, int len) throws IOException {
    int read = 0;
    while (read < len) {
      int n = read(buf, off + read, len - read);
      if (n < 0) throw new IOException("Unexpected end of BGZF stream");
      read += n;
    }
  }

  /**
   * Read a little-endian int32.
   */
  public int readInt() throws IOException {
    byte[] b = new byte[4];
    readFully(b);
    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  /**
   * Read a little-endian uint32 as long.
   */
  public long readUInt() throws IOException {
    return readInt() & 0xFFFFFFFFL;
  }

  /**
   * Read a little-endian int64.
   */
  public long readLong() throws IOException {
    byte[] b = new byte[8];
    readFully(b);
    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong();
  }

  /**
   * Read a little-endian uint16.
   */
  public int readUShort() throws IOException {
    byte[] b = new byte[2];
    readFully(b);
    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
  }

  /**
   * Skip n bytes forward.
   */
  public void skip(int n) throws IOException {
    int remaining = n;
    while (remaining > 0) {
      if (posInBlock >= currentBlockSize) {
        if (!loadNextBlock()) throw new IOException("Unexpected end of BGZF stream while skipping");
      }
      int available = currentBlockSize - posInBlock;
      int toSkip = Math.min(available, remaining);
      posInBlock += toSkip;
      remaining -= toSkip;
    }
  }

  /**
   * Load the BGZF block at the given compressed file offset.
   */
  private void loadBlock(long compressedOffset) throws IOException {
    raf.seek(compressedOffset);
    
    // Read gzip header (minimum 18 bytes for BGZF)
    int bytesRead = raf.read(headerBuf, 0, 18);
    if (bytesRead < 18) {
      currentBlock = null;
      currentBlockSize = 0;
      return;
    }

    // Verify gzip magic
    int id1 = headerBuf[0] & 0xFF;
    int id2 = headerBuf[1] & 0xFF;
    if (id1 != 31 || id2 != 139) {
      throw new IOException("Not a valid BGZF block at offset " + compressedOffset 
          + " (magic: " + id1 + ", " + id2 + ")");
    }

    int flg = headerBuf[3] & 0xFF;
    if ((flg & 4) == 0) {
      throw new IOException("BGZF block missing FEXTRA flag at offset " + compressedOffset);
    }

    // Extra field length at bytes 10-11 (little-endian)
    int xlen = (headerBuf[10] & 0xFF) | ((headerBuf[11] & 0xFF) << 8);

    // Read extra fields to find BC subfield with BSIZE
    byte[] extraFields = new byte[xlen];
    // We already read 18 bytes of header; extra fields start at byte 12
    // Copy what we have from headerBuf (bytes 12-17)
    int alreadyInHeader = Math.min(xlen, 6);
    System.arraycopy(headerBuf, 12, extraFields, 0, alreadyInHeader);
    if (xlen > 6) {
      raf.read(extraFields, 6, xlen - 6);
    }

    int bsize = -1;
    int pos = 0;
    while (pos + 4 <= xlen) {
      int si1 = extraFields[pos] & 0xFF;
      int si2 = extraFields[pos + 1] & 0xFF;
      int slen = (extraFields[pos + 2] & 0xFF) | ((extraFields[pos + 3] & 0xFF) << 8);
      if (si1 == 66 && si2 == 67 && slen == 2) { // 'B', 'C'
        bsize = (extraFields[pos + 4] & 0xFF) | ((extraFields[pos + 5] & 0xFF) << 8);
        break;
      }
      pos += 4 + slen;
    }
    if (bsize < 0) {
      throw new IOException("BGZF block missing BC subfield at offset " + compressedOffset);
    }

    // Compressed data size: total block = bsize + 1, 
    // header = 12 + xlen, trailer = 8 (CRC32 + ISIZE)
    int cDataSize = (bsize + 1) - 12 - xlen - 8;
    if (cDataSize < 0) {
      throw new IOException("Invalid BGZF block size at offset " + compressedOffset);
    }

    // Seek to start of compressed data (past the header)
    raf.seek(compressedOffset + 12 + xlen);
    byte[] cData = new byte[cDataSize];
    raf.readFully(cData);

    // Read trailer: CRC32 (4 bytes) + ISIZE (4 bytes, uncompressed size)
    byte[] trailer = new byte[8];
    raf.readFully(trailer);
    int isize = (trailer[4] & 0xFF) | ((trailer[5] & 0xFF) << 8)
              | ((trailer[6] & 0xFF) << 16) | ((trailer[7] & 0xFF) << 24);

    // Decompress
    if (isize == 0) {
      // Empty block (EOF marker)
      currentBlock = new byte[0];
      currentBlockSize = 0;
    } else {
      Inflater inflater = new Inflater(true); // raw deflate (no zlib header)
      try {
        inflater.setInput(cData);
        byte[] decompressed = new byte[isize];
        int inflated = inflater.inflate(decompressed);
        if (inflated != isize) {
          throw new IOException("BGZF decompression size mismatch: expected " + isize + ", got " + inflated);
        }
        currentBlock = decompressed;
        currentBlockSize = isize;
      } catch (DataFormatException e) {
        throw new IOException("BGZF decompression error at offset " + compressedOffset, e);
      } finally {
        inflater.end();
      }
    }

    currentBlockCompressedOffset = compressedOffset;
    nextBlockCompressedOffset = compressedOffset + bsize + 1;
    posInBlock = 0;
  }

  /**
   * Load the next sequential BGZF block.
   * Returns false if at EOF.
   */
  private boolean loadNextBlock() throws IOException {
    if (nextBlockCompressedOffset >= raf.length()) return false;
    loadBlock(nextBlockCompressedOffset);
    return currentBlock != null && currentBlockSize > 0;
  }

  @Override
  public void close() throws IOException {
    raf.close();
  }
}
