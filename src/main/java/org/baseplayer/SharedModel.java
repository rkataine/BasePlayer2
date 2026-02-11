package org.baseplayer;
import java.util.ArrayList;
import java.util.function.IntSupplier;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.io.ReferenceGenome;
import org.baseplayer.reads.bam.SampleFile;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

public class SharedModel {
   public static ArrayList<String> sampleList = new ArrayList<>();
   public static ArrayList<SampleFile> bamFiles = new ArrayList<>();
   public static IntegerProperty hoverSample = new SimpleIntegerProperty(-1);
   public static int firstVisibleSample = 0;
   public static int lastVisibleSample = 0;
   public static double scrollBarPosition = 0;
   public static double sampleHeight = 0;
   public static ReferenceGenome referenceGenome;
   public static String currentChromosome = "1";
   
   public static final double MASTER_TRACK_HEIGHT = 28;
   
   public static IntSupplier visibleSamples() {
      return () -> lastVisibleSample - firstVisibleSample + 1;
   }
   
   /**
    * Repack cached BAM reads for the given stack to optimize row usage.
    * Call after zoom operations complete.
    */
   public static void repackBamReadsForStack(DrawStack stack) {
      for (SampleFile sf : bamFiles) {
         sf.repackReads(stack);
         for (SampleFile overlay : sf.getOverlays()) {
            overlay.repackReads(stack);
         }
      }
   }
}