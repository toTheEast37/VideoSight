package com.vibeteaching.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** 数据库 DATETIME 字段约定保存 UTC，不使用 JVM 的默认时区。 */
public class UtcInstantTypeHandler extends BaseTypeHandler<Instant> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Instant value, JdbcType jdbcType)
            throws SQLException {
        statement.setTimestamp(index, Timestamp.from(value), utc());
    }

    @Override
    public Instant getNullableResult(ResultSet result, String column) throws SQLException {
        return instant(result.getTimestamp(column, utc()));
    }

    @Override
    public Instant getNullableResult(ResultSet result, int column) throws SQLException {
        return instant(result.getTimestamp(column, utc()));
    }

    @Override
    public Instant getNullableResult(CallableStatement statement, int column) throws SQLException {
        return instant(statement.getTimestamp(column, utc()));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }
}
