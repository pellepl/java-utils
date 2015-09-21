package com.pelleplutt.util;

public class FastMath {

  private FastMath() {}
  
  public static final float floor(float a) {
    if (a >= 0) {
      return (int) a;
    } else {
      if (a == (int) a) {
        return (int) a;
      }
      return (int) a - 1;
    }
  }
  
  /**
   * Returns 1/(sqrt(x))
   * @param x
   * @return
   */
  public static final float invSqrt(float x) {
    float xhalf = 0.5f*x;
    int i = Float.floatToIntBits(x);
    i = 0x5f3759df - (i>>1);
    x = Float.intBitsToFloat(i);
    x = x*(1.5f - xhalf*x*x);
    return x;
  }
  
  /**
   * Returns approximate of sqrt given an expected value.
   * E.g. you have x = y + e, and know what sqrt(y) is, and e is small
   * @param x     y + epsilon
   * @param expX  sqrt(y)
   * @return sqrt(x)
   */
  public static final float sqrtNear(float x, float expX) {
    return (expX * expX + x)/(2f*expX);
  }

}
