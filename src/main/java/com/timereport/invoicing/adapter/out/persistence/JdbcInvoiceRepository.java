package com.timereport.invoicing.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.invoicing.application.port.out.InvoiceRepository;
import com.timereport.invoicing.application.service.InvoicingConflict;
import com.timereport.invoicing.domain.InvoiceStatus;
import com.timereport.invoicing.domain.InvoiceSummary;
import com.timereport.invoicing.domain.IssuedInvoice;
import com.timereport.invoicing.domain.IssuedInvoiceDocument;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

    @Override
    public Optional<IssuedInvoiceDocument> findDocument(int invoiceNumber) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT snapshot,pdf FROM issued_invoices WHERE account_id=? AND invoice_number=?")) {
            statement.setObject(1, identity.accountId());
            statement.setInt(2, invoiceNumber);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(new IssuedInvoiceDocument(
                        objectMapper.readValue(result.getString("snapshot"), IssuedInvoice.class), result.getBytes("pdf"))) : Optional.empty();
            }
        } catch (SQLException error) {
            throw databaseError(error);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Fakturaögonblicksbilden kunde inte läsas.", error);
        }
    }

    @Override
    public List<InvoiceSummary> list() {
        String sql = "SELECT i.invoice_number,i.period_month,i.issued_at,i.due_date,"
                + "i.snapshot->'customer'->>'name' AS customer_name,i.snapshot->'customer'->>'invoiceEmail' AS invoice_email,"
                + "(i.snapshot->>'totalAmount')::numeric AS total_amount,(i.snapshot->>'vatAmount')::numeric AS vat_amount,"
                + "i.snapshot->'seller'->>'currencyCode' AS currency_code,s.sent_at,s.paid_at,COALESCE(s.version,0) AS status_version,"
                + "d.id,d.recipient,d.cc,d.bcc,d.state,d.requested_at,d.completed_at "
                + "FROM issued_invoices i LEFT JOIN invoice_status s ON s.account_id=i.account_id AND s.invoice_number=i.invoice_number "
                + "LEFT JOIN LATERAL (SELECT * FROM invoice_deliveries d WHERE d.account_id=i.account_id AND d.invoice_number=i.invoice_number ORDER BY requested_at DESC,id DESC LIMIT 1) d ON true "
                + "WHERE i.account_id=? ORDER BY i.invoice_number DESC";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, identity.accountId());
            try (var result = statement.executeQuery()) {
                var summaries = new ArrayList<InvoiceSummary>();
                while (result.next())
                    summaries.add(InvoiceSummary.builder().invoiceNumber(result.getInt("invoice_number"))
                            .month(result.getString("period_month")).customerName(result.getString("customer_name"))
                            .invoiceEmail(result.getString("invoice_email")).issuedAt(result.getTimestamp("issued_at").toInstant())
                            .dueDate(result.getObject("due_date", LocalDate.class)).totalAmount(result.getBigDecimal("total_amount"))
                            .vatAmount(result.getBigDecimal("vat_amount")).currencyCode(result.getString("currency_code"))
                            .status(new InvoiceStatus(JdbcInvoiceDeliveryRepository.instant(result, "sent_at"), JdbcInvoiceDeliveryRepository.instant(result, "paid_at"), result.getLong("status_version")))
                            .lastDelivery(result.getObject("id") == null ? null : JdbcInvoiceDeliveryRepository.read(result)).build());
                return List.copyOf(summaries);
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    private RuntimeException databaseError(SQLException error) {
        if ("23505".equals(error.getSQLState()))
            return new InvoicingConflict("En faktura finns redan för kunden och månaden, eller så används fakturanumret.");
        return new IllegalStateException("Databasåtgärden för fakturan misslyckades.", error);
    }
}
