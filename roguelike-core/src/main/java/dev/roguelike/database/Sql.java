package dev.roguelike.database;

import java.sql.*;
import java.util.*;

public final class Sql {
  private Sql() {}

  public static PreparedStatement prepare(Connection c, String sql, Object... values)
      throws SQLException {
    PreparedStatement s = c.prepareStatement(sql);
    for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
    return s;
  }

  public static int update(Connection c, String sql, Object... values) throws SQLException {
    try (var s = prepare(c, sql, values)) {
      return s.executeUpdate();
    }
  }

  public static long number(Connection c, String sql, Object... values) throws SQLException {
    try (var s = prepare(c, sql, values);
        var r = s.executeQuery()) {
      return r.next() ? r.getLong(1) : 0;
    }
  }

  public static String string(Connection c, String sql, Object... values) throws SQLException {
    try (var s = prepare(c, sql, values);
        var r = s.executeQuery()) {
      return r.next() ? r.getString(1) : null;
    }
  }

  public static List<Map<String, Object>> rows(Connection c, String sql, Object... values)
      throws SQLException {
    try (var s = prepare(c, sql, values);
        var r = s.executeQuery()) {
      var rows = new ArrayList<Map<String, Object>>();
      while (r.next()) {
        var row = new LinkedHashMap<String, Object>();
        for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
          row.put(r.getMetaData().getColumnLabel(i), r.getObject(i));
        rows.add(row);
      }
      return rows;
    }
  }
}
