package org.baseplayer.io.bbreader;

/*
 * Copyright (c) 2007-2011 by The Broad Institute of MIT and Harvard.  All Rights Reserved.
 *
 * This software is licensed under the terms of the GNU Lesser General Public License (LGPL),
 * Version 2.1 which is available at http://www.opensource.org/licenses/lgpl-2.1.php.
 *
 * Adapted for BasePlayer2: removed legacy BedTrack / MainPane / Getter coupling.
 */

import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.samtools.seekablestream.SeekableStreamFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;

/**
 * Broad Institute Interactive Genome Viewer Big Binary File (BBFile) Reader
 * for UCSC BigWig and BigBed file types.
 */
public class BBFileReader {

  public static final long BBFILE_HEADER_OFFSET = 0;

  private final SeekableStream fis;
  private final BBFileHeader fileHeader;
  private final boolean isLowToHigh;
  private final int uncompressBufSize;

  private final int zoomLevelCount;
  private final BBZoomLevels zoomLevels;
  private final BBTotalSummaryBlock totalSummaryBlock;
  private final BPTree chromosomeIDTree;
  private final RPTree chromosomeDataTree;
  private String autoSql;

  public BBFileReader(String path) throws IOException {
    this(path, SeekableStreamFactory.getInstance().getStreamFor(path));
  }

  public BBFileReader(URL path) throws IOException {
    this(path.getPath(), SeekableStreamFactory.getInstance().getStreamFor(path));
  }

  public BBFileReader(String path, SeekableStream stream) throws IOException {
    fis = stream;

    long fileOffset = BBFILE_HEADER_OFFSET;
    fileHeader = new BBFileHeader(path, fis, fileOffset);
    if (!fileHeader.isHeaderOK()) {
      throw new RuntimeException("Error reading BBFile header for: " + path);
    }

    isLowToHigh = fileHeader.isLowToHigh();
    uncompressBufSize = fileHeader.getUncompressBuffSize();
    fileOffset += BBFileHeader.BBFILE_HEADER_SIZE;

    zoomLevelCount = fileHeader.getZoomLevels();
    if (zoomLevelCount > 0) {
      long zoomLevelOffset = fileOffset;
      zoomLevels = new BBZoomLevels(fis, zoomLevelOffset, zoomLevelCount,
          isLowToHigh, uncompressBufSize);
      fileOffset += (long) zoomLevelCount * BBZoomLevelHeader.ZOOM_LEVEL_HEADER_SIZE;
    } else {
      zoomLevels = null;
    }

    long autoSqlOffset = fileHeader.getAutoSqlOffset();
    if (autoSqlOffset != 0) {
      fis.seek(autoSqlOffset);
      autoSql = readNullTerminatedString(fis);
    }

    fileOffset = fileHeader.getTotalSummaryOffset();
    if (fileHeader.getVersion() >= 2 && fileOffset > 0) {
      totalSummaryBlock = new BBTotalSummaryBlock(fis, fileOffset, isLowToHigh);
    } else {
      totalSummaryBlock = null;
    }

    long chromIDTreeOffset = fileHeader.getChromosomeTreeOffset();
    if (chromIDTreeOffset != 0) {
      chromosomeIDTree = new BPTree(fis, chromIDTreeOffset, isLowToHigh);
    } else {
      chromosomeIDTree = null;
    }

    long chromDataTreeOffset = fileHeader.getFullIndexOffset();
    if (chromDataTreeOffset != 0) {
      boolean forceDescend = false;
      chromosomeDataTree = new RPTree(fis, chromDataTreeOffset, isLowToHigh,
          uncompressBufSize, forceDescend);
    } else {
      chromosomeDataTree = null;
    }
  }

  public BBFileHeader getBBFileHeader() {
    return fileHeader;
  }

  public boolean isBigBedFile() {
    return fileHeader.isBigBed();
  }

  public boolean isBigWigFile() {
    return fileHeader.isBigWig();
  }

  public boolean isLowToHigh() {
    return isLowToHigh;
  }

  public BBTotalSummaryBlock getTotalSummaryBlock() {
    return totalSummaryBlock;
  }

  public BBZoomLevels getZoomLevels() {
    return zoomLevels;
  }

  public int getZoomLevelCount() {
    return zoomLevelCount;
  }

  public ArrayList<String> getChromosomeNames() {
    if (chromosomeIDTree == null) {
      return new ArrayList<>();
    }
    return chromosomeIDTree.getChromosomeNames();
  }

  public String getAutoSql() {
    return autoSql;
  }

  public void close() {
    try {
      if (fis != null) {
        fis.close();
      }
    } catch (IOException _) {
      // ignore
    }
  }

  synchronized public BigBedIterator getBigBedIterator(
      String startChromosome, int startBase,
      String endChromosome, int endBase, boolean contained) {
    if (!isBigBedFile()) {
      return null;
    }
    RPChromosomeRegion selectionRegion = getChromosomeBounds(
        startChromosome, startBase, endChromosome, endBase);
    if (selectionRegion == null) {
      return new BigBedIterator();
    }
    return new BigBedIterator(fis, chromosomeIDTree, chromosomeDataTree,
        selectionRegion, contained);
  }

  synchronized public BigWigIterator getBigWigIterator(
      String startChromosome, int startBase,
      String endChromosome, int endBase, boolean contained) {
    if (!isBigWigFile()) {
      return null;
    }
    RPChromosomeRegion selectionRegion = getChromosomeBounds(
        startChromosome, startBase, endChromosome, endBase);
    if (selectionRegion == null) {
      return new BigWigIterator();
    }
    return new BigWigIterator(fis, chromosomeIDTree, chromosomeDataTree,
        selectionRegion, contained);
  }

  synchronized public ZoomLevelIterator getZoomLevelIterator(
      int zoomLevel, String startChromosome, int startBase,
      String endChromosome, int endBase, boolean contained) {
    if (zoomLevel < 1 || zoomLevel > zoomLevelCount || zoomLevels == null) {
      throw new RuntimeException("Error: ZoomLevelIterator zoom level is out of range\n");
    }
    RPTree zoomDataTree = zoomLevels.getZoomLevelRPTree(zoomLevel);
    RPChromosomeRegion selectionRegion = getChromosomeBounds(
        startChromosome, startBase, endChromosome, endBase);
    return new ZoomLevelIterator(fis, chromosomeIDTree, zoomDataTree, zoomLevel,
        selectionRegion, contained);
  }

  synchronized public ZoomLevelIterator getZoomLevelIterator(int zoomLevel) {
    if (zoomLevel < 1 || zoomLevel > zoomLevelCount || zoomLevels == null) {
      throw new RuntimeException("Error: ZoomLevelIterator zoom level is out of range\n");
    }
    RPTree zoomDataTree = zoomLevels.getZoomLevelRPTree(zoomLevel);
    RPChromosomeRegion selectionRegion = zoomDataTree.getChromosomeBounds();
    return new ZoomLevelIterator(fis, chromosomeIDTree, zoomDataTree, zoomLevel,
        selectionRegion, true);
  }

  private RPChromosomeRegion getChromosomeBounds(
      String startChromosome, int startBase,
      String endChromosome, int endBase) {
    if (chromosomeIDTree == null) {
      return null;
    }
    if (startChromosome.length() > chromosomeIDTree.getKeySize()) {
      return null;
    }
    String startChromKey = chromosomeIDTree.getChromosomeKey(startChromosome);
    int startChromID = chromosomeIDTree.getChromosomeID(startChromKey);
    if (startChromID < 0) {
      return null;
    }
    String endChromKey = chromosomeIDTree.getChromosomeKey(endChromosome);
    int endChromID = chromosomeIDTree.getChromosomeID(endChromKey);
    if (endChromID < 0) {
      return null;
    }
    return new RPChromosomeRegion(startChromID, startBase, endChromID, endBase);
  }

  private static String readNullTerminatedString(InputStream fis) throws IOException {
    BufferedInputStream bis = new BufferedInputStream(fis, 1000);
    ByteArrayOutputStream bos = new ByteArrayOutputStream(100);
    byte b;
    while ((b = (byte) bis.read()) != 0) {
      bos.write(b);
    }
    return new String(bos.toByteArray());
  }
}
