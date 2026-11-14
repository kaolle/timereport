package com.timereport.authentication.adapter.out.persistence;

import com.timereport.authentication.application.port.out.AccountRoles;
import jakarta.enterprise.context.ApplicationScoped;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class JdbcAccountRoles implements AccountRoles {
    private final DataSource dataSource;

    public JdbcAccountRoles(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Set<String> roles(UUID accountId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT role FROM account_roles WHERE account_id = ?")) {
            statement.setObject(1, accountId);
            var result = new HashSet<String>();
            result.add("user");
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString("role"));
            }
            return Set.copyOf(result);
        } catch (SQLException failure) {
            throw new IllegalStateException("Kontots behörigheter kunde inte läsas.", failure);
        }
    }
}
