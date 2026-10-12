package com.timereport.timeentry.adapter.out.persistence;

import com.timereport.timeentry.application.port.out.WriteTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.function.Supplier;

@ApplicationScoped
public class JdbcWriteTransaction implements WriteTransaction {
    private final DataSource dataSource;

    public JdbcWriteTransaction(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    @Transactional
    public <T> T execute(Supplier<T> work) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT id FROM app_write_lock WHERE id = 1 FOR UPDATE");
                var result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("Write lock row is missing");
            }
            return work.get();
        } catch (SQLException e) {
            throw JdbcErrors.translate(e);
        }
    }
}
