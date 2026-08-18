package org.baseplayer.io.readers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.baseplayer.variant.VcfSnvIndel;
import org.baseplayer.variant.VcfStructuralVariant;

/**
 * Example usage of the VCF reader.
 */
public class VcfReaderExample {
    
    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Usage: VcfReaderExample <vcf.gz> <chr> <start> <end>");
            System.exit(1);
        }
        
        String vcfFile = args[0];
        String chromosome = args[1];
        long start = Long.parseLong(args[2]);
        long end = Long.parseLong(args[3]);
        
        try (VcfReader reader = new VcfReader(Path.of(vcfFile))) {
            System.out.println("=== VCF File Info ===");
            System.out.println("File: " + vcfFile);
            System.out.println("Samples: " + reader.getSampleNames());
            System.out.println();
            
            // Example 1: Query only SNVs and indels
            System.out.println("=== SNVs and Indels ===");
            List<VcfSnvIndel> snvs = reader.querySnvsAndIndels(chromosome, start, end);
            System.out.println("Found " + snvs.size() + " SNVs/indels");
            
            for (VcfSnvIndel variant : snvs.stream().limit(5).toList()) {
                System.out.printf("  %s:%d %s>%s [%s] Q=%.1f PASS=%s%n",
                    variant.getChromosome(),
                    variant.getPosition(),
                    variant.getRef(),
                    variant.getAlt(),
                    variant.getType(),
                    variant.getQuality(),
                    variant.isPassed());
                
                // Show some INFO fields if available
                if (variant.getInfo().containsKey("AF")) {
                    System.out.println("    AF=" + variant.getInfoField("AF"));
                }
            }
            System.out.println();
            
            // Example 2: Query only structural variants
            System.out.println("=== Structural Variants ===");
            List<VcfStructuralVariant> svs = reader.queryStructuralVariants(chromosome, start, end);
            System.out.println("Found " + svs.size() + " structural variants");
            
            for (VcfStructuralVariant sv : svs.stream().limit(5).toList()) {
                System.out.printf("  %s:%d-%s %s [%s] len=%d PASS=%s%n",
                    sv.getChromosome(),
                    sv.getPosition(),
                    sv.getEnd(),
                    sv.getSvType(),
                    sv.getType(),
                    sv.getAbsLength(),
                    sv.isPassed());
                    
                if (sv.getChr2() != null) {
                    System.out.println("    Translocation to: " + sv.getChr2() + ":" + sv.getEnd2());
                }
            }
            System.out.println();
            
            // Example 3: Query all variants at once (separated)
            System.out.println("=== All Variants (separated) ===");
            Map<String, Object> allVariants = reader.queryAllVariants(chromosome, start, end);
            
            @SuppressWarnings("unchecked")
            List<VcfSnvIndel> allSnvs = (List<VcfSnvIndel>) allVariants.get("snvs");
            
            @SuppressWarnings("unchecked")
            List<VcfStructuralVariant> allSvs = (List<VcfStructuralVariant>) allVariants.get("svs");
            
            System.out.println("Total SNVs/indels: " + allSnvs.size());
            System.out.println("Total SVs: " + allSvs.size());
            
            // Example 4: Access genotype information
            if (!snvs.isEmpty() && !reader.getSampleNames().isEmpty()) {
                System.out.println();
                System.out.println("=== Genotype Example ===");
                VcfSnvIndel firstVariant = snvs.get(0);
                System.out.println("Variant: " + firstVariant);
                
                for (String sample : reader.getSampleNames()) {
                    Map<String, Object> gt = firstVariant.getGenotype(sample);
                    if (gt != null) {
                        System.out.printf("  Sample %s: GT=%s DP=%s GQ=%s%n",
                            sample,
                            gt.get("GT"),
                            gt.get("DP"),
                            gt.get("GQ"));
                    }
                }
            }
            
        } catch (IOException e) {
            System.err.println("Error reading VCF: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
