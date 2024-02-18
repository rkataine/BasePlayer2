package org.baseplayer.io;
import java.io.File;
import java.util.HashMap;
import java.util.List;

import org.baseplayer.MainApp;

import java.util.Collections;

import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;


public class FileDialog {
  private final String dialogType;
  private final String header;
  private final String filtertype;
  private final boolean multiSelect;
  private static HashMap<String, String> filefilters = new HashMap<String, String>();
  public static HashMap<String, File> defaultPaths = new HashMap<String, File>();
  public static HashMap<String, String> savePaths = new HashMap<String, String>();

  public FileDialog(String header, String filtertype, String dialogType /*FileDialog.LOAD / SAVE*/, boolean multiSelect) {
    this.dialogType = dialogType;
    this.header = header;
    this.filtertype = filtertype;
    this.multiSelect = multiSelect;
  }

  public List<File> chooseFiles() {
      final FileChooser fileChooser = new FileChooser(); // FileDialog.LOAD FileDialog.SAVE
      fileChooser.setTitle(header);
      fileChooser.setInitialDirectory(defaultPaths.get(filtertype));
      ExtensionFilter extFilter = new ExtensionFilter(filtertype, filefilters.get(filtertype));
      fileChooser.getExtensionFilters().add(extFilter);
      List<File> files = null;
      
      if (multiSelect) {
        files = fileChooser.showOpenMultipleDialog(MainApp.stage);
      } else if (dialogType.equals("SAVE")) {
        files = Collections.singletonList(fileChooser.showSaveDialog(MainApp.stage));
      } else {
        files = Collections.singletonList(fileChooser.showOpenDialog(MainApp.stage));
      }
      return files;
  }
  /* private void savePath(File[] files) {
    if (files.length == 0) return;
    defaultPaths.put(filtertype, files[0].getParent());
    //MainPane.writeToConfig(savePaths.get(filtertype) +"=" + defaultPaths.get(filtertype));
  }
   */
  //private FilenameFilter fileNameFilter = (dir, name) -> Arrays.asList(this.filterString.replace("*", "").split(";")).stream().anyMatch(name.toLowerCase()::endsWith);
  
  static {
    savePaths.put("VCF", "DefaultDir");
    savePaths.put("BAM", "DefaultTrackDir");
    savePaths.put("CTRL", "DefaultControlDir");
    savePaths.put("BED", "TrackDir");
    savePaths.put("JSON", "DefaultProjectDir");
    File file = new File("C:\\");
    defaultPaths.put("VCF", file);
    defaultPaths.put("BAM", file);
    defaultPaths.put("CTRL", file);
    defaultPaths.put("BED", file);
    defaultPaths.put("JSON", file);

    filefilters.put("VCF", "*.vcf.gz");
    filefilters.put("BAM", "*.bam, *.cram");
    filefilters.put("CTRL", "*.vcf.gz");
    filefilters.put("BED", "*.bed, *.bed.gz, *.bedgraph.gz, *.gff.gz, *.gff3.gz, *.bigwig, *.bw, *.bigbed, *.bb");
    filefilters.put("JSON", "*.json");
 
  }
}
