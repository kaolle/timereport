package com.timereport.timeentry.adapter.out.persistence;

import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.timeentry.application.port.out.WriteTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.function.Supplier;

@ApplicationScoped
public class JdbcWriteTransaction implements WriteTransaction {
    private final DataSource dataSource;
    private final AccountIdentity identity;

    public JdbcWriteTransaction(DataSource dataSource, AccountIdentity identity) {
        this.dataSource = dataSource;
        this.identity = identity;
    }

    private java.sql.ResultSet accountRow(java.sql.PreparedStatement statement) throws SQLException {
        statement.setObject(1, identity.accountId());
        return statement.executeQuery();
    }

    @Override
    @Transactional
    public <T> T execute(Supplier<T> work) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE");
             var result = accountRow(statement)) {
            if (!result.next()) {
                throw new IllegalStateException("Write lock row is missing");
            }
            return work.get();
        } catch (SQLException e) {
            throw JdbcErrors.translate(e);
        }
    }
}
