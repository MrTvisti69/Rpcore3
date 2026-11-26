package ru.rpcore.db;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Превращает текущую строку ResultSet в объект. Не вызывайте rs.next() внутри. */
@FunctionalInterface
public interface SqlMapper<T> {
    T map(ResultSet rs) throws SQLException;
}
