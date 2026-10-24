package com.timereport.invoicing.adapter.out.persistence;

import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.invoicing.application.port.out.InvoiceTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.function.Supplier;

@ApplicationScoped
public class JdbcInvoiceTransaction implements InvoiceTransaction {
    private final DataSource dataSource;
    private final AccountIdentity identity;

    public JdbcInvoiceTransaction(DataSource dataSource, AccountIdentity identity) {
        this.dataSource = dataSource;
        this.identity = identity;
    }

    @Override
    @Transactional
    public <T> T execute(Supplier<T> work) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT id FROM accounts WHERE id=? FOR UPDATE")) {
            statement.setObject(1, identity.accountId());
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Kontot för fakturan saknas.");
            }
            return work.get();
        } catch (SQLException e) {
            throw new IllegalStateException("Fakturans transaktion misslyckades.", e);
        }
    }
}
