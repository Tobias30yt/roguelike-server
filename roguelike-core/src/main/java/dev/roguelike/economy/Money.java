package dev.roguelike.economy;

import java.math.BigInteger;

public final class Money {
  private Money() {}

  public static long multiply(long amount, long basisPoints) {
    if (amount < 0 || basisPoints < 0) throw new IllegalArgumentException("Negative money");
    return BigInteger.valueOf(amount)
        .multiply(BigInteger.valueOf(basisPoints))
        .divide(BigInteger.valueOf(10000))
        .longValueExact();
  }

  public static long positive(String value) {
    long n = Long.parseLong(value);
    if (n <= 0) throw new IllegalArgumentException("Amount must be positive");
    return n;
  }
}
