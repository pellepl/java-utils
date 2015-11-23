package com.pelleplutt.util;
public class Meas {
  public double cmin = Double.MAX_VALUE;
  public double cmax = Double.MIN_VALUE;
  public double csum = 0;
  public long count = 0;
  public double avg = 0;
  public double min = 0;
  public double max = 0;
  
  public void add(double t) {
    cmin = Math.min(cmin, t);
    cmax = Math.max(cmax, t);
    count++;
    csum += t;
  }
  
  public void reset() {
    avg = csum/count;
    min = cmin;
    max = cmax;
    cmin = Double.MAX_VALUE;
    cmax = Double.MIN_VALUE;
    csum = 0;
    count = 0;
  }
  
  public String toString() {
    String s;
    if (count == 0)
      s = "";
    else
      s = "" + (csum/count) + " min:" + cmin + " max:" + cmax; 
    reset();
    return s;
  }
}
