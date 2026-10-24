package com.timereport.invoicing.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.invoicing.application.port.out.InvoiceRepository;
import com.timereport.invoicing.application.service.InvoicingConflict;
import com.timereport.invoicing.domain.IssuedInvoice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

@ApplicationScoped
public class JdbcInvoiceRepository implements InvoiceRepository {
    private final DataSource dataSource;
    private final AccountIdentity identity;
    private final ObjectMapper objectMapper;

    public JdbcInvoiceRepository(DataSource dataSource, AccountIdentity identity, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.identity = identity;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getNextNumber() {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT next_number FROM invoice_number_sequences WHERE account_id=?")) {
            statement.setObject(1, identity.accountId());
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 1;
            }
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    @Override
    @Transactional
    public int setNextNumber(int nextNumber) {
        String sql = "INSERT INTO invoice_number_sequences(account_id,next_number) VALUES(?,?) "
                + "ON CONFLICT(account_id) DO UPDATE SET next_number=EXCLUDED.next_number "
                + "WHERE invoice_number_sequences.next_number <= EXCLUDED.next_number";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, identity.accountId());
            statement.setInt(2, nextNumber);
            if (statement.executeUpdate() != 1)
                throw new InvoicingConflict("Nästa fakturanummer kan bara behållas eller höjas, aldrig sänkas.");
            return nextNumber;
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public int allocateNextNumber() {
        try (var connection = dataSource.getConnection()) {
            try (var insert = connection.prepareStatement("INSERT INTO invoice_number_sequences(account_id,next_number) VALUES(?,1) ON CONFLICT DO NOTHING")) {
                insert.setObject(1, identity.accountId());
                insert.executeUpdate();
            }
            int number;
            try (var select = connection.prepareStatement("SELECT next_number FROM invoice_number_sequences WHERE account_id=? FOR UPDATE")) {
                select.setObject(1, identity.accountId());
                try (var result = select.executeQuery()) {
                    if (!result.next()) throw new IllegalStateException("Fakturanummerserie saknas.");
                    number = result.getInt(1);
                }
            }
            if (number > 999_999_999) throw new InvoicingConflict("Fakturanummerserien är slut.");
            try (var update = connection.prepareStatement("UPDATE invoice_number_sequences SET next_number=? WHERE account_id=?")) {
                update.setInt(1, number + 1);
                update.setObject(2, identity.accountId());
                update.executeUpdate();
            }
            return number;
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void save(IssuedInvoice invoice, byte[] pdf) {
        String sql = "INSERT INTO issued_invoices(id,account_id,invoice_number,period_month,customer_id,invoice_date,due_date,issued_at,snapshot,pdf) "
                + "VALUES(?,?,?,?,?,?,?,?,?::jsonb,?)";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, invoice.id());
            statement.setObject(2, identity.accountId());
            statement.setInt(3, invoice.invoiceNumber());
            statement.setString(4, invoice.periodMonth());
            statement.setObject(5, invoice.customer().id());
            statement.setObject(6, invoice.invoiceDate());
            statement.setObject(7, invoice.dueDate());
            statement.setTimestamp(8, Timestamp.from(invoice.issuedAt()));
            statement.setString(9, json(invoice));
            statement.setBytes(10, pdf);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    @Override
    public Optional<byte[]> findPdf(int invoiceNumber) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT pdf FROM issued_invoices WHERE account_id=? AND invoice_number=?")) {
            statement.setObject(1, identity.accountId());
            statement.setInt(2, invoiceNumber);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getBytes(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    private String json(IssuedInvoice invoice) {
        try {
            return objectMapper.writeValueAsString(invoice);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Fakturaögonblicksbilden kunde inte sparas.", e);
        }
    }

    private RuntimeException databaseError(SQLException error) {
        if ("23505".equals(error.getSQLState()))
            return new InvoicingConflict("En faktura finns redan för kunden och månaden, eller så används fakturanumret.");
        return new IllegalStateException("Databasåtgärden för fakturan misslyckades.", error);
    }
}
