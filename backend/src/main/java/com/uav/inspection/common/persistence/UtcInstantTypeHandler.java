package com.uav.inspection.common.persistence;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Maps UTC DATETIME values without depending on the JVM default time zone. */
@MappedTypes(Instant.class)
@MappedJdbcTypes(value = JdbcType.TIMESTAMP, includeNullJdbcType = true)
public final class UtcInstantTypeHandler extends BaseTypeHandler<Instant> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Instant parameter, JdbcType jdbcType)
            throws SQLException {
        statement.setTimestamp(index, Timestamp.from(parameter), utcCalendar());
    }

    @Override
    public Instant getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return instant(resultSet.getTimestamp(columnName, utcCalendar()));
    }

    @Override
    public Instant getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return instant(resultSet.getTimestamp(columnIndex, utcCalendar()));
    }

    @Override
    public Instant getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return instant(statement.getTimestamp(columnIndex, utcCalendar()));
    }

    private static Calendar utcCalendar() {
        // Calendar is mutable; each JDBC operation receives its own instance.
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
