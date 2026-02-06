package org.baseplayer.io;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReferenceGenome {
    private final Path fastaPath;
    private final Map<String, ChromosomeIndex> chromosomes = new LinkedHashMap<>();
    private RandomAccessFile fastaFile;
    
    public record ChromosomeIndex(String name, long length, long offset, int lineBytes, int lineChars) {}
    
    public ReferenceGenome(Path fastaPath) throws IOException {
        this.fastaPath = fastaPath;
        loadIndex();
        openFasta();
    }
   
    private void loadIndex() throws IOException {
        Path indexPath = Path.of(fastaPath.toString() + ".fai");
        if (!Files.exists(indexPath)) throw new IOException("Fasta index file not found: " + indexPath);
        
        List<String> lines = Files.readAllLines(indexPath);
        for (String line : lines) {
            String[] parts = line.split("\t");
            if (parts.length >= 5) {
                String name = parts[0];
                long length = Long.parseLong(parts[1]);
                long offset = Long.parseLong(parts[2]);
                int lineBytes = Integer.parseInt(parts[3]);
                int lineChars = Integer.parseInt(parts[4]);
                chromosomes.put(name, new ChromosomeIndex(name, length, offset, lineBytes, lineChars));
            }
        }
    }
    
    private void openFasta() throws IOException {
        fastaFile = new RandomAccessFile(fastaPath.toFile(), "r");
    }

    public List<String> getChromosomeNames() {
        return List.copyOf(chromosomes.keySet());
    }
    
    public List<String> getStandardChromosomeNames() {
        return chromosomes.keySet().stream()
            .filter(this::isStandardChromosome)
            .sorted(this::compareChromosomes)
            .toList();
    }
    
    private int compareChromosomes(String chr1, String chr2) {
        // Remove "chr" prefix if present for comparison
        String name1 = chr1.startsWith("chr") ? chr1.substring(3) : chr1;
        String name2 = chr2.startsWith("chr") ? chr2.substring(3) : chr2;
        
        // Parse numeric chromosomes
        Integer num1 = tryParseInt(name1);
        Integer num2 = tryParseInt(name2);
        
        // Both numeric: compare numerically
        if (num1 != null && num2 != null) {
            return num1.compareTo(num2);
        }
        
        // One numeric, one not: numeric comes first
        if (num1 != null) return -1;
        if (num2 != null) return 1;
        
        // Neither numeric: explicit order X, Y, MT, M
        int order1 = getChromosomeOrder(name1);
        int order2 = getChromosomeOrder(name2);
        return Integer.compare(order1, order2);
    }
    
    private int getChromosomeOrder(String name) {
        return switch (name) {
            case "X" -> 0;
            case "Y" -> 1;
            case "MT", "M" -> 2;
            default -> 3; // Other chromosomes come last
        };
    }
    
    private Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    private boolean isStandardChromosome(String name) {
        // Match chromosomes with or without "chr" prefix: 1-22, X, Y, MT/M
        return name.matches("^(chr)?(\\d{1,2}|X|Y|MT?)$");
    }
    
    public long getChromosomeLength(String chromosome) {
        ChromosomeIndex idx = chromosomes.get(chromosome);
        return idx != null ? idx.length() : 0;
    }
    
    public String getBases(String chromosome, int start, int end) {
        ChromosomeIndex idx = chromosomes.get(chromosome);
        if (idx == null) return "";
        
        start = Math.max(1, start);
        end = Math.min((int) idx.length(), end);
        if (start > end) return "";
        
        int length = end - start + 1;
        
        try {
            int zeroBasedStart = start - 1;
            int linesBeforeStart = zeroBasedStart / idx.lineBytes();
            int posInLine = zeroBasedStart % idx.lineBytes();
            
            long fileOffset = idx.offset() + (linesBeforeStart * (long) idx.lineChars()) + posInLine;
            
            fastaFile.seek(fileOffset);
            
            StringBuilder result = new StringBuilder(length);
            int basesRead = 0;
            
            while (basesRead < length) {
                int c = fastaFile.read();
                if (c == -1) break;
                if (c == '\n' || c == '\r') continue;
                
                result.append((char) Character.toUpperCase(c));
                basesRead++;
            }
            
            return result.toString();
            
        } catch (IOException e) {
            System.err.println("Error reading bases from " + chromosome + ":" + start + "-" + end + ": " + e.getMessage());
            return "";
        }
    }
    
    void close() throws IOException {
        if (fastaFile != null) fastaFile.close();
    }
    
    public String getName() {
        return fastaPath.getParent().getFileName().toString();
    }
		
		@Override
		public String toString() {
			return getName();
		}
}
