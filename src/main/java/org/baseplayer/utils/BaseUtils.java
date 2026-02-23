package org.baseplayer.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.function.Function;

public class BaseUtils {
  public static Function<Long, Integer> toMegabytes = (value) -> (int)(value / 1048576);

  public static double round(double value, int places) {
	    if (places < 0) throw new IllegalArgumentException();
	    if(Double.isNaN(value) || Double.isInfinite(value)) return 0;
	    
	    BigDecimal bd = new BigDecimal(value);

	    if(bd.setScale(places, RoundingMode.HALF_UP).doubleValue() == 0.0)
	     	return bd.setScale((int)-Math.log10(bd.doubleValue())+places, RoundingMode.HALF_UP).doubleValue();
	    else return bd.setScale(places, RoundingMode.HALF_UP).doubleValue();
  }
	//public static Function<Integer, String> formatNumber = (number) -> NumberFormat.getNumberInstance(Locale.US).format(number);	
	public static String formatNumber(int number) {
		return NumberFormat.getNumberInstance(Locale.US).format(number);
	}
	
	public static String formatNumber(long number) {
		return NumberFormat.getNumberInstance(Locale.US).format(number);
	}

  /**
   * Try to parse a string as an integer, returning {@code null} on failure.
   */
  public static Integer tryParseInt(String s) {
    try {
      return Integer.valueOf(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Returns the reverse complement of a DNA sequence (A↔T, G↔C, 5′→3′). */
  public static String reverseComplement(String sequence) {
    StringBuilder sb = new StringBuilder(sequence.length());
    for (int i = sequence.length() - 1; i >= 0; i--) {
      char base = sequence.charAt(i);
      sb.append(switch (Character.toUpperCase(base)) {
        case 'A' -> 'T';
        case 'T' -> 'A';
        case 'G' -> 'C';
        case 'C' -> 'G';
        default  -> 'N';
      });
    }
    return sb.toString();
  }
}
