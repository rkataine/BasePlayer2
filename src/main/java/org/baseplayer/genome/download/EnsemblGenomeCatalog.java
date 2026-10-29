package org.baseplayer.genome.download;

import java.util.List;

/**
 * Curated Ensembl reference genomes plus UCSC cytoband URLs when available.
 */
public final class EnsemblGenomeCatalog {

  public static final int ENSEMBL_RELEASE = 115;

  /**
   * @param folderId          directory name under {@code genomes/}
   * @param label             UI species / organism label
   * @param assembly          assembly short name
   * @param fastaUrl          gzipped FASTA download URL
   * @param fastaFileName     uncompressed FASTA name under {@code genomes/<folderId>/}
   * @param gff3Url           gzipped GFF3 download URL
   * @param gff3FileName      GFF3 file name under {@code annotation/}
   * @param ucscCytoBandUrl   UCSC {@code cytoBand*.txt.gz}, or {@code null} to skip bands
   * @param sizeHint          short size hint for the UI
   */
  public record Entry(
      String folderId,
      String label,
      String assembly,
      String fastaUrl,
      String fastaFileName,
      String gff3Url,
      String gff3FileName,
      String ucscCytoBandUrl,
      String sizeHint) {
  }

  private static final String ENSEMBL = "https://ftp.ensembl.org/pub";
  private static final String UCSC = "https://hgdownload.soe.ucsc.edu/goldenPath";
  private static final String T2T_BASE =
      ENSEMBL + "/rapid-release/species/Homo_sapiens/GCA_009914755.4/ensembl";

  private static final List<Entry> ENTRIES = List.of(
      entry(
          "GRCh38",
          "Human (Homo sapiens)",
          "GRCh38",
          ensemblFasta("homo_sapiens", "Homo_sapiens.GRCh38.dna.primary_assembly.fa.gz"),
          "Homo_sapiens.GRCh38.dna.primary_assembly.fa",
          ensemblGff3("homo_sapiens", "Homo_sapiens.GRCh38." + ENSEMBL_RELEASE + ".chr.gff3.gz"),
          "Homo_sapiens.GRCh38." + ENSEMBL_RELEASE + ".chr.gff3.gz",
          UCSC + "/hg38/database/cytoBand.txt.gz",
          "~841 MB compressed"),
      entry(
          "GRCh37",
          "Human (Homo sapiens)",
          "GRCh37",
          ENSEMBL + "/grch37/current/fasta/homo_sapiens/dna/Homo_sapiens.GRCh37.dna.primary_assembly.fa.gz",
          "Homo_sapiens.GRCh37.dna.primary_assembly.fa",
          ENSEMBL + "/grch37/current/gff3/homo_sapiens/Homo_sapiens.GRCh37.87.chr.gff3.gz",
          "Homo_sapiens.GRCh37.87.chr.gff3.gz",
          UCSC + "/hg19/database/cytoBand.txt.gz",
          "~830 MB compressed"),
      entry(
          "T2T",
          "Human (Homo sapiens)",
          "T2T-CHM13v2.0",
          T2T_BASE + "/genome/Homo_sapiens-GCA_009914755.4-unmasked.fa.gz",
          "Homo_sapiens-GCA_009914755.4-unmasked.fa",
          T2T_BASE + "/geneset/2022_07/Homo_sapiens-GCA_009914755.4-2022_07-genes.gff3.gz",
          "Homo_sapiens-GCA_009914755.4-2022_07-genes.gff3.gz",
          // HPRC T2T cytobands (same 5-column format as UCSC cytoBand / bands.txt)
          "https://s3-us-west-2.amazonaws.com/human-pangenomics/T2T/CHM13/assemblies/annotation/chm13v2.0_cytobands_allchrs.bed",
          "~860 MB compressed"),
      entry(
          "GRCm39",
          "Mouse (Mus musculus)",
          "GRCm39",
          ensemblFasta("mus_musculus", "Mus_musculus.GRCm39.dna.primary_assembly.fa.gz"),
          "Mus_musculus.GRCm39.dna.primary_assembly.fa",
          ensemblGff3("mus_musculus", "Mus_musculus.GRCm39." + ENSEMBL_RELEASE + ".chr.gff3.gz"),
          "Mus_musculus.GRCm39." + ENSEMBL_RELEASE + ".chr.gff3.gz",
          UCSC + "/mm39/database/cytoBand.txt.gz",
          "~760 MB compressed"),
      entry(
          "GRCz11",
          "Zebrafish (Danio rerio)",
          "GRCz11",
          ensemblFasta("danio_rerio", "Danio_rerio.GRCz11.dna.primary_assembly.fa.gz"),
          "Danio_rerio.GRCz11.dna.primary_assembly.fa",
          ensemblGff3("danio_rerio", "Danio_rerio.GRCz11." + ENSEMBL_RELEASE + ".chr.gff3.gz"),
          "Danio_rerio.GRCz11." + ENSEMBL_RELEASE + ".chr.gff3.gz",
          UCSC + "/danRer11/database/cytoBandIdeo.txt.gz",
          "~390 MB compressed"),
      entry(
          "BDGP6",
          "Fruit fly (Drosophila melanogaster)",
          "BDGP6.54",
          ensemblFasta("drosophila_melanogaster", "Drosophila_melanogaster.BDGP6.54.dna.toplevel.fa.gz"),
          "Drosophila_melanogaster.BDGP6.54.dna.toplevel.fa",
          ensemblGff3("drosophila_melanogaster", "Drosophila_melanogaster.BDGP6.54." + ENSEMBL_RELEASE + ".chr.gff3.gz"),
          "Drosophila_melanogaster.BDGP6.54." + ENSEMBL_RELEASE + ".chr.gff3.gz",
          UCSC + "/dm6/database/cytoBandIdeo.txt.gz",
          "~40 MB compressed"),
      entry(
          "WBcel235",
          "Nematode (Caenorhabditis elegans)",
          "WBcel235",
          ensemblFasta("caenorhabditis_elegans", "Caenorhabditis_elegans.WBcel235.dna.toplevel.fa.gz"),
          "Caenorhabditis_elegans.WBcel235.dna.toplevel.fa",
          ensemblGff3("caenorhabditis_elegans", "Caenorhabditis_elegans.WBcel235." + ENSEMBL_RELEASE + ".gff3.gz"),
          "Caenorhabditis_elegans.WBcel235." + ENSEMBL_RELEASE + ".gff3.gz",
          UCSC + "/ce11/database/cytoBandIdeo.txt.gz",
          "~30 MB compressed"),
      entry(
          "R64-1-1",
          "Yeast (Saccharomyces cerevisiae)",
          "R64-1-1",
          ensemblFasta("saccharomyces_cerevisiae", "Saccharomyces_cerevisiae.R64-1-1.dna.toplevel.fa.gz"),
          "Saccharomyces_cerevisiae.R64-1-1.dna.toplevel.fa",
          ensemblGff3("saccharomyces_cerevisiae", "Saccharomyces_cerevisiae.R64-1-1." + ENSEMBL_RELEASE + ".gff3.gz"),
          "Saccharomyces_cerevisiae.R64-1-1." + ENSEMBL_RELEASE + ".gff3.gz",
          null,
          "~4 MB compressed"));

  private EnsemblGenomeCatalog() {}

  public static List<Entry> entries() {
    return ENTRIES;
  }

  private static Entry entry(
      String folderId,
      String label,
      String assembly,
      String fastaUrl,
      String fastaFileName,
      String gff3Url,
      String gff3FileName,
      String ucscCytoBandUrl,
      String sizeHint) {
    return new Entry(
        folderId, label, assembly, fastaUrl, fastaFileName, gff3Url, gff3FileName, ucscCytoBandUrl, sizeHint);
  }

  private static String ensemblFasta(String speciesDir, String fileName) {
    return ENSEMBL + "/release-" + ENSEMBL_RELEASE + "/fasta/" + speciesDir + "/dna/" + fileName;
  }

  private static String ensemblGff3(String speciesDir, String fileName) {
    return ENSEMBL + "/release-" + ENSEMBL_RELEASE + "/gff3/" + speciesDir + "/" + fileName;
  }
}
