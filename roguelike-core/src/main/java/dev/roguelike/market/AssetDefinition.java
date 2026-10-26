package dev.roguelike.market;

public record AssetDefinition(
    String id,
    long initialPrice,
    long minimum,
    long maximum,
    int liquidity,
    int impact,
    long feeBasisPoints) {
  public AssetDefinition {
    if (!id.matches("[a-z0-9_]+")
        || minimum < 1
        || initialPrice < minimum
        || maximum < initialPrice
        || maximum > 1_000_000_000
        || liquidity < 1
        || liquidity > 1_000_000
        || impact < 0
        || feeBasisPoints < 0
        || feeBasisPoints > 1000) throw new IllegalArgumentException("Invalid market asset " + id);
  }

  public long marginal(long anchor, long supply) {
    return Math.clamp(
        Math.addExact(anchor, Math.multiplyExact(liquidity - supply, impact)), minimum, maximum);
  }
}
