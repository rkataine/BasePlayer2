package org.baseplayer;
import java.util.ArrayList;
import java.util.function.IntSupplier;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

public class SharedModel {
   public static ArrayList<String> sampleList = new ArrayList<String>();
   public static IntegerProperty hoverSample = new SimpleIntegerProperty(-1);
   public static int firstVisibleSample = 0;
   public static int lastVisibleSample = 0;
   public static double scrollBarPosition = 0;
   public static double sampleHeight = 0;   
   
   public static IntSupplier visibleSamples = () -> lastVisibleSample - firstVisibleSample + 1;
}