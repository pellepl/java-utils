package com.pelleplutt.util;

public class LUTFunc {

  static final int SZ = 16384;
  
  static final float SIN_TAB[] = new float[SZ];
  static final float PERLIN_TAB[] = new float[SZ];
  static final float SIGMOID_TAB[] = new float[SZ];
  
  public static final float PI = SZ * 2; 
  public static final float rPI = (float)Math.PI;
  public static final float PI2LUTPI = PI / rPI;
  public static final float LUTPI2PI = rPI / PI;
  
  static {
    for (int i = 0; i < SZ; i++) {
      double x = (double)i/(double)SZ;
      SIN_TAB[i] = (float)Math.sin(x*Math.PI/2.0);
      SIGMOID_TAB[i] = (float)(1.0/(1.0+Math.pow(Math.E, -16*(x-0.5))));
      PERLIN_TAB[i] = (float)(3.0*x*x-2.0*x*x*x);
    }
  }
  
  private LUTFunc() {
  }
  
  /**
   * Lutified sinus
   * @param ang radians where PI is LUTFunc.PI
   * @return sin
   */
  public static final float sin(float ang) {
    int ix = (int)ang & (SZ*4-1);
    if (ix < SZ) return SIN_TAB[ix];
    else if (ix < 2*SZ) return SIN_TAB[2*SZ - ix - 1];
    else if (ix < 3*SZ) return -SIN_TAB[ix - 2*SZ];
    else return -SIN_TAB[4*SZ - ix - 1];
  }
  
  /**
   * Lutified cosinus
   * @param ang radians where PI is LUTFunc.PI
   * @return cos
   */
  public static final float cos(float ang) {
    int ix = (int)(ang + SZ) & (SZ*4-1);
    if (ix < SZ) return SIN_TAB[ix];
    else if (ix < 2*SZ) return SIN_TAB[2*SZ - ix - 1];
    else if (ix < 3*SZ) return -SIN_TAB[ix - 2*SZ];
    else return -SIN_TAB[4*SZ - ix - 1];
  }

  /**
   * Lutified sigmoid
   * @param x 0..1
   * @return sigmoid func
   */
  public static final float sigmoid(float x) {
    return SIGMOID_TAB[(int)(x*SZ)];
  }

  /**
   * Lutified perlin, or really a linear smoother (3*x^2-2*x^3)
   * @param x 0..1
   * @return perlin func
   */
  public static final float perlin(float x) {
    return PERLIN_TAB[(int)(x*SZ)];
  }
}
