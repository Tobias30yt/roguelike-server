package dev.roguelike.run;

public record Run(
    String id,
    String player,
    long epoch,
    long seed,
    String profile,
    String state,
    byte[] snapshot,
    String folder,
    long created) {}
