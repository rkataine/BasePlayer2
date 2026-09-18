package org.baseplayer.genome;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.utils.BaseUtils;
import org.baseplayer.utils.ChromosomeNames;

public class ReferenceGenome {
    private final Path fastaPath;
    private final Map<String, ChromosomeIndex> chromosomes = new LinkedHashMap<>();
    private RandomAccessFile fastaFile;
    /** {@code "chr"} if the FASTA uses chr-prefixed contigs; otherwise {@code ""}. */
    private String chromPrefix = ChromosomeNames.NONE;
    
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
        List<String> rawNames = new java.util.ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split("\t");
            if (parts.length >= 5) {
                String rawName = parts[0];
                rawNames.add(rawName);
                String name = ChromosomeNames.strip(rawName);
                long length = Long.parseLong(parts[1]);
                long offset = Long.parseLong(parts[2]);
                int lineBytes = Integer.parseInt(parts[3]);
                int lineChars = Integer.parseInt(parts[4]);
                // Internal map always keyed without chr prefix.
                chromosomes.put(name, new ChromosomeIndex(name, length, offset, lineBytes, lineChars));
            }
        }
        this.chromPrefix = ChromosomeNames.detectPrefix(rawNames);
    }

    /** Data-source prefix for this FASTA ({@code ""} or {@code "chr"}). */
    public String getChromPrefix() {
        return chromPrefix;
    }
    
    private void openFasta() throws IOException {
        fastaFile = new RandomAccessFile(fastaPath.toFile(), "r");
    }

    public List<String> getChromosomeNames() {
        return List.copyOf(chromosomes.keySet());
    }
    
    public List<String> getStandardChromosomeNames() {
        List<String> standard = chromosomes.keySet().stream()
            .filter(this::isStandardChromosome)
            .sorted(this::compareChromosomes)
            .toList();
        // Fall back to all contigs if no standard chromosome names matched
        if (standard.isEmpty()) {
            return List.copyOf(chromosomes.keySet());
        }
        return standard;
    }
    
    private int compareChromosomes(String chr1, String chr2) {
        String name1 = ChromosomeNames.strip(chr1);
        String name2 = ChromosomeNames.strip(chr2);
        
        Integer num1 = BaseUtils.tryParseInt(name1);
        Integer num2 = BaseUtils.tryParseInt(name2);
        
        if (num1 != null && num2 != null) {
            return num1.compareTo(num2);
        }
        if (num1 != null) return -1;
        if (num2 != null) return 1;
        
        int order1 = getChromosomeOrder(name1);
        int order2 = getChromosomeOrder(name2);
        return Integer.compare(order1, order2);
    }
    
    private int getChromosomeOrder(String name) {
        return switch (name) {
            case "X" -> 0;
            case "Y" -> 1;
            case "MT", "M" -> 2;
            default -> 3;
        };
    }

    private boolean isStandardChromosome(String name) {
        return ChromosomeNames.isStandardBare(ChromosomeNames.strip(name));
    }
    
    public long getChromosomeLength(String chromosome) {
        ChromosomeIndex idx = chromosomes.get(ChromosomeNames.strip(chromosome));
        return idx != null ? idx.length() : 0;
    }
    
    public String getBases(String chromosome, int start, int end) {
        ChromosomeIndex idx = chromosomes.get(ChromosomeNames.strip(chromosome));
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
            
            // Estimate raw bytes needed (bases + newlines)
            int newlines = length / idx.lineBytes() + 2;
            int rawLen = length + newlines;
            byte[] raw;
            
            // Synchronize on fastaFile to prevent concurrent seek+read races.
            // Multiple threads call getBases() concurrently (BAM/CRAM fetch threads,
            // async reference display fetch in ChromosomeCanvas, etc.). Without this lock,
            // one thread's seek() can be overwritten by another thread's seek() before
            // the first thread reads, returning wrong bases and corrupting mismatch data.
            synchronized (fastaFile) {
                fastaFile.seek(fileOffset);
                raw = new byte[rawLen];
                int totalRead = 0;
                while (totalRead < rawLen) {
                    int n = fastaFile.read(raw, totalRead, rawLen - totalRead);
                    if (n == -1) break;
                    totalRead += n;
                }
                rawLen = totalRead;
            }
            
            // Strip newlines and uppercase outside the lock
            char[] result = new char[length];
            int basesRead = 0;
            for (int i = 0; i < rawLen && basesRead < length; i++) {
                byte b = raw[i];
                if (b == '\n' || b == '\r') continue;
                result[basesRead++] = (char) Character.toUpperCase(b);
            }
            return new String(result, 0, basesRead);
        } catch (IOException e) {
            System.err.println("Error reading bases from " + chromosome + ":" + start + "-" + end + ": " + e.getMessage());
            return "";
        }
    }
    
    public String getName() {
        return fastaPath.getParent().getFileName().toString();
    }
		
		@Override
		public String toString() {
			return getName();
		}
}
