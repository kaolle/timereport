package com.timereport.timeentry.adapter.out.persistence;

import com.timereport.timeentry.application.service.ConflictException;
import java.sql.SQLException;

final class JdbcErrors {
    private JdbcErrors() {
    }

    static RuntimeException translate(SQLException error) {
        if ("23505".equals(error.getSQLState()) || "23P01".equals(error.getSQLState())) {
            return new ConflictException("A conflicting time interval or identifier already exists");
        }
        return new IllegalStateException("Database operation failed", error);
    }
}
