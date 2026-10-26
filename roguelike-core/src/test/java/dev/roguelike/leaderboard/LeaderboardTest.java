package dev.roguelike.leaderboard;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LeaderboardTest {
  @Test
  void periodsUseMondayAndCalendarMonths() {
    LocalDate sunday = LocalDate.of(2026, 10, 25);
    assertEquals(LocalDate.of(2026, 10, 19), LeaderboardService.start(sunday, "weekly"));
    assertEquals(LocalDate.of(2026, 10, 1), LeaderboardService.start(sunday, "monthly"));
    assertEquals(sunday, LeaderboardService.start(sunday, "daily"));
    assertThrows(IllegalArgumentException.class, () -> LeaderboardService.start(sunday, "hourly"));
  }
}
