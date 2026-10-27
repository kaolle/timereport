package com.timereport.invoicing.adapter.out.persistence;

import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.invoicing.application.port.out.InvoiceDeliveryRepository;
import com.timereport.invoicing.application.port.out.InvoiceStatusRepository;
import com.timereport.invoicing.application.service.InvoiceNotFound;
import com.timereport.invoicing.application.service.InvoicingConflict;
import com.timereport.invoicing.domain.InvoiceDelivery;
import com.timereport.invoicing.domain.InvoiceStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JdbcInvoiceDeliveryRepository implements InvoiceDeliveryRepository, InvoiceStatusRepository {
    private final DataSource dataSource;
    private final AccountIdentity identity;

    public JdbcInvoiceDeliveryRepository(DataSource dataSource, AccountIdentity identity) {
        this.dataSource = dataSource;
        this.identity = identity;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean start(InvoiceDelivery delivery) {
        try (var connection = dataSource.getConnection()) {
            lockInvoice(connection, delivery.invoiceNumber());
            var existing = find(delivery.id());
            if (existing.isPresent()) {
                verifyRequest(existing.get(), delivery);
                return false;
            }
            try (var statement = connection.prepareStatement("SELECT count(*), count(*) FILTER (WHERE state='SENDING' AND requested_at > now()-interval '5 minutes') FROM invoice_deliveries WHERE account_id=? AND invoice_number=? AND requested_at > now()-interval '1 hour'")) {
                statement.setObject(1, identity.accountId());
                statement.setInt(2, delivery.invoiceNumber());
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (result.getInt(2) > 0)
                        throw new InvoicingConflict("Ett utskick pågår eller saknar bekräftelse. Vänta fem minuter innan du försöker skicka igen.");
                    if (result.getInt(1) >= 5)
                        throw new InvoicingConflict("För många utskicksförsök för fakturan. Försök igen om en timme.");
                }
            }
            try (var statement = connection.prepareStatement("INSERT INTO invoice_deliveries(account_id,id,invoice_number,recipient,state,requested_at) VALUES(?,?,?,?,'SENDING',?) ON CONFLICT DO NOTHING")) {
                statement.setObject(1, identity.accountId());
                statement.setObject(2, delivery.id());
                statement.setInt(3, delivery.invoiceNumber());
                statement.setString(4, delivery.recipient());
                statement.setTimestamp(5, Timestamp.from(delivery.requestedAt()));
                if (statement.executeUpdate() == 1) return true;
                verifyRequest(find(delivery.id()).orElseThrow(), delivery);
                return false;
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    private void verifyRequest(InvoiceDelivery existing, InvoiceDelivery requested) {
        if (existing.invoiceNumber() != requested.invoiceNumber() || !existing.recipient().equals(requested.recipient()))
            throw new InvoicingConflict("Utskicks-id används redan för ett annat utskick.");
    }

    @Override
    public Optional<InvoiceDelivery> find(UUID requestId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT id,invoice_number,recipient,state,requested_at,completed_at FROM invoice_deliveries WHERE account_id=? AND id=?")) {
            statement.setObject(1, identity.accountId());
            statement.setObject(2, requestId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void complete(InvoiceDelivery delivery) {
        try (var connection = dataSource.getConnection()) {
            lockInvoice(connection, delivery.invoiceNumber());
            try (var statement = connection.prepareStatement("UPDATE invoice_deliveries SET state=?,completed_at=? WHERE account_id=? AND id=? AND state='SENDING'")) {
                statement.setString(1, delivery.state().name());
                statement.setTimestamp(2, Timestamp.from(delivery.completedAt()));
                statement.setObject(3, identity.accountId());
                statement.setObject(4, delivery.id());
                if (statement.executeUpdate() != 1) throw new InvoicingConflict("Utskicket har redan avslutats.");
            }
            if (delivery.state() == InvoiceDelivery.State.SENT) {
                try (var statement = connection.prepareStatement("INSERT INTO invoice_status(account_id,invoice_number,sent_at,version) VALUES(?,?,?,1) ON CONFLICT(account_id,invoice_number) DO UPDATE SET sent_at=EXCLUDED.sent_at,version=invoice_status.version+1")) {
                    statement.setObject(1, identity.accountId());
                    statement.setInt(2, delivery.invoiceNumber());
                    statement.setTimestamp(3, Timestamp.from(delivery.completedAt()));
                    statement.executeUpdate();
                }
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public InvoiceStatus setStatus(int invoiceNumber, boolean sent, boolean paid, long expectedVersion) {
        try (var connection = dataSource.getConnection()) {
            lockInvoice(connection, invoiceNumber);
            var current = InvoiceStatus.builder().version(0).build();
            try (var statement = connection.prepareStatement("SELECT sent_at,paid_at,version FROM invoice_status WHERE account_id=? AND invoice_number=?")) {
                statement.setObject(1, identity.accountId());
                statement.setInt(2, invoiceNumber);
                try (var result = statement.executeQuery()) {
                    if (result.next())
                        current = new InvoiceStatus(instant(result, "sent_at"), instant(result, "paid_at"), result.getLong("version"));
                }
            }
            if (current.version() != expectedVersion)
                throw new InvoicingConflict("Fakturans status har ändrats. Listan uppdateras; försök igen.");
            Instant now = Instant.now();
            var updated = new InvoiceStatus(sent ? (current.sent() ? current.sentAt() : now) : null,
                    paid ? (current.paid() ? current.paidAt() : now) : null, current.version() + 1);
            try (var statement = connection.prepareStatement("INSERT INTO invoice_status(account_id,invoice_number,sent_at,paid_at,version) VALUES(?,?,?,?,?) ON CONFLICT(account_id,invoice_number) DO UPDATE SET sent_at=EXCLUDED.sent_at,paid_at=EXCLUDED.paid_at,version=EXCLUDED.version")) {
                statement.setObject(1, identity.accountId());
                statement.setInt(2, invoiceNumber);
                statement.setTimestamp(3, updated.sent() ? Timestamp.from(updated.sentAt()) : null);
                statement.setTimestamp(4, updated.paid() ? Timestamp.from(updated.paidAt()) : null);
                statement.setLong(5, updated.version());
                statement.executeUpdate();
            }
            return updated;
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    private void lockInvoice(Connection connection, int invoiceNumber) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id FROM issued_invoices WHERE account_id=? AND invoice_number=? FOR UPDATE")) {
            statement.setObject(1, identity.accountId());
            statement.setInt(2, invoiceNumber);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new InvoiceNotFound();
            }
        }
    }

    static InvoiceDelivery read(ResultSet result) throws SQLException {
        return InvoiceDelivery.builder().id(result.getObject("id", UUID.class)).invoiceNumber(result.getInt("invoice_number"))
                .recipient(result.getString("recipient")).state(InvoiceDelivery.State.valueOf(result.getString("state")))
                .requestedAt(instant(result, "requested_at")).completedAt(instant(result, "completed_at")).build();
    }

    static Instant instant(ResultSet result, String name) throws SQLException {
        Timestamp value = result.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private RuntimeException databaseError(SQLException error) {
        return new IllegalStateException("Fakturans utskicksstatus kunde inte sparas.", error);
    }
}
