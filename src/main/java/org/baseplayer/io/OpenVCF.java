package org.baseplayer.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.variant.VcfSnvIndel;
import org.baseplayer.variant.VcfStructuralVariant;

/**
 * Handler for opening and managing VCF files.
 */
public class OpenVCF {
  
  private VcfReader currentReader;
  
  /**
   * Open a VCF file. Must be bgzipped with tabix or csi index.
   * 
   * @param file VCF file (.vcf.gz)
   * @throws IOException if file cannot be opened or is not properly indexed
   */
  public void openVCF(File file) throws IOException {
    closeCurrentReader();
    
    Path vcfPath = file.toPath();
    currentReader = new VcfReader(vcfPath);
  }
  
  /**
   * Query SNVs and indels in a genomic region.
   * 
   * @param chromosome Chromosome name
   * @param start Start position (1-based)
   * @param end End position (1-based)
   * @return List of SNVs and indels
   * @throws IOException if query fails
   */
  public List<VcfSnvIndel> querySnvsAndIndels(String chromosome, long start, long end) 
      throws IOException {
    if (currentReader == null) {
      throw new IllegalStateException("No VCF file is currently open");
    }
    return currentReader.querySnvsAndIndels(chromosome, start, end);
  }
  
  /**
   * Query structural variants in a genomic region.
   * 
   * @param chromosome Chromosome name
   * @param start Start position (1-based)
   * @param end End position (1-based)
   * @return List of structural variants
   * @throws IOException if query fails
   */
  public List<VcfStructuralVariant> queryStructuralVariants(String chromosome, long start, long end) 
      throws IOException {
    if (currentReader == null) {
      throw new IllegalStateException("No VCF file is currently open");
    }
    return currentReader.queryStructuralVariants(chromosome, start, end);
  }
  
  /**
   * Query all variants in a region, separated into SNVs/indels and SVs.
   * 
   * @param chromosome Chromosome name
   * @param start Start position (1-based)
   * @param end End position (1-based)
   * @return Map with "snvs" and "svs" keys
   * @throws IOException if query fails
   */
  public Map<String, Object> queryAllVariants(String chromosome, long start, long end) 
      throws IOException {
    if (currentReader == null) {
      throw new IllegalStateException("No VCF file is currently open");
    }
    return currentReader.queryAllVariants(chromosome, start, end);
  }
  
  /**
   * Get the list of sample names in the VCF.
   */
  public List<String> getSampleNames() {
    if (currentReader == null) {
      throw new IllegalStateException("No VCF file is currently open");
    }
    return currentReader.getSampleNames();
  }
  
  /**
   * Close the current VCF reader.
   */
  public void closeCurrentReader() {
    if (currentReader != null) {
      try {
        currentReader.close();
      } catch (IOException e) {
        System.err.println("Error closing VCF reader: " + e.getMessage());
      }
      currentReader = null;
    }
  }
  
  /**
   * Get the currently open VCF reader (for advanced usage).
   */
  public VcfReader getCurrentReader() {
    return currentReader;
  }
}
