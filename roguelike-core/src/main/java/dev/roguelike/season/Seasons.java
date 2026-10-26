package dev.roguelike.season;

import java.time.*;

public final class Seasons {
  private Seasons() {}

  public static String at(ZonedDateTime date) {
    int month = date.getMonthValue();
    return month >= 3 && month <= 5
        ? "SPRING"
        : month >= 6 && month <= 8 ? "SUMMER" : month >= 9 && month <= 11 ? "AUTUMN" : "WINTER";
  }

  public static String scope(ZonedDateTime date) {
    return at(date) + ":" + (date.getMonthValue() <= 2 ? date.getYear() - 1 : date.getYear());
  }

  public static int cycle(ZonedDateTime date, int month, int day) {
    LocalDate boundary = LocalDate.of(date.getYear(), month, day);
    return date.toLocalDate().isBefore(boundary) ? date.getYear() - 1 : date.getYear();
  }
}
