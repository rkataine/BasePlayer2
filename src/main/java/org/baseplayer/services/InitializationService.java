package org.baseplayer.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.baseplayer.io.ReferenceGenome;

/**
 * Handles application initialization: loading genomes, annotations, initial setup.
 */
public class InitializationService {
  
  private final ReferenceGenomeService referenceGenomeService;
  
  public InitializationService() {
    this.referenceGenomeService = ServiceRegistry.getInstance().getReferenceGenomeService();
  }
  
  /**
   * Load all available reference genomes from the genomes directory.
   * 
   * @return List of available reference genomes
   */
  public List<ReferenceGenome> loadAvailableGenomes() {
    List<ReferenceGenome> genomes = new ArrayList<>();
    Path genomesDir = Path.of("genomes");
    
    if (!Files.exists(genomesDir)) {
      return genomes;
    }
    
    try (Stream<Path> dirs = Files.list(genomesDir)) {
      dirs.filter(Files::isDirectory).forEach(dir -> {
        try (Stream<Path> files = Files.list(dir)) {
          files.filter(f -> f.toString().endsWith(".fa") || f.toString().endsWith(".fasta"))
               .filter(f -> Files.exists(Path.of(f.toString() + ".fai")))
               .findFirst()
               .ifPresent(fastaPath -> {
                 try {
                   ReferenceGenome genome = new ReferenceGenome(fastaPath);
                   genomes.add(genome);
                 } catch (IOException e) {
                   System.err.println("Failed to load genome: " + fastaPath + " - " + e.getMessage());
                 }
               });
        } catch (IOException e) {
          System.err.println("Error scanning genome directory: " + dir);
        }
      });
    } catch (IOException e) {
      System.err.println("Error scanning genomes folder: " + e.getMessage());
    }
    
    return genomes;
  }
  
  /**
   * Load available annotation files for the current genome.
   * 
   * @param genomeName Name of the genome (e.g., "GRCh38")
   * @return List of annotation filenames
   */
  public List<String> loadAvailableAnnotations(String genomeName) {
    List<String> annotations = new ArrayList<>();
    Path annotationDir = Path.of("genomes/" + genomeName + "/annotation");
    
    if (!Files.exists(annotationDir)) {
      return annotations;
    }
    
    try (Stream<Path> files = Files.list(annotationDir)) {
      files.filter(f -> {
        String name = f.toString();
        return name.endsWith(".gff3.gz") || name.endsWith(".gff3") || name.endsWith(".gtf.gz");
      }).forEach(gff3Path -> {
        String filename = gff3Path.getFileName().toString();
        annotations.add(filename);
      });
    } catch (IOException e) {
      System.err.println("Error scanning annotations folder: " + e.getMessage());
    }
    
    return annotations;
  }
  
  /**
   * Select and activate a reference genome.
   * Updates the service and notifies all stacks.
   * 
   * @param genome Reference genome to activate
   */
  public void selectReferenceGenome(ReferenceGenome genome) {
    if (genome == null) return;
    
    referenceGenomeService.setCurrentGenome(genome);
    
    List<String> chromNames = genome.getStandardChromosomeNames();
    
    // Update all stacks with chromosome list
    for (var stack : ServiceRegistry.getInstance().getDrawStackManager().getStacks()) {
      stack.setChromosomeList(chromNames);
    }
  }
  
  /**
   * Find the default annotation filename from a list.
   * Prefers Ensembl annotations.
   * 
   * @param annotations List of annotation filenames
   * @return Default annotation or first one if no default found
   */
  public String findDefaultAnnotation(List<String> annotations) {
    if (annotations.isEmpty()) {
      return null;
    }
    
    return annotations.stream()
        .filter(name -> name.contains("Homo_sapiens.GRCh38"))
        .findFirst()
        .orElse(annotations.get(0));
  }
}
