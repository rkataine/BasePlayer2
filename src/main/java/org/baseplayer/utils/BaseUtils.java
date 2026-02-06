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
}
