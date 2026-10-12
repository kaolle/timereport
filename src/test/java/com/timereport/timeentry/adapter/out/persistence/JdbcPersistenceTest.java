package com.timereport.timeentry.adapter.out.persistence;

import com.timereport.timeentry.application.port.out.CustomerCodeRepository;
import com.timereport.timeentry.application.port.out.TimeEntryRepository;
import com.timereport.timeentry.application.port.out.WriteTransaction;
import com.timereport.timeentry.application.service.ConflictException;
import com.timereport.timeentry.domain.CustomerCode;
import com.timereport.timeentry.domain.RegisteredCustomerCode;
import com.timereport.timeentry.domain.TimeEntry;
import com.timereport.timeentry.domain.VersionedTimeEntry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class JdbcPersistenceTest {
    @Inject WriteTransaction transaction;
    @Inject CustomerCodeRepository codes;
    @Inject TimeEntryRepository entries;
    @Inject DataSource dataSource;

    @Test
    void failedTransactionRollsBackPreviouslyExecutedWrites() {
        var code = new CustomerCode("ROLLBACK-" + UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> transaction.execute(() -> {
            codes.save(new RegisteredCustomerCode(code, true, 1), 0);
            throw new IllegalStateException("Simulate failure after insert");
        }));
        assertTrue(codes.find(code).isEmpty());
    }

    @Test
    void committedDataIsVisibleToANewConnection() throws Exception {
        var code = new CustomerCode("COMMIT-" + UUID.randomUUID());
        transaction.execute(() -> {
            codes.save(new RegisteredCustomerCode(code, true, 1), 0);
            return null;
        });
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT version FROM customer_codes WHERE code = ?")) {
            statement.setString(1, code.value());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getLong(1));
            }
        }
    }

    @Test
    void databaseExclusionConstraintProtectsAgainstBypassingTheService() {
        var code = new CustomerCode("CONSTRAINT-" + UUID.randomUUID());
        var entry = TimeEntry.builder().id(UUID.randomUUID()).date(LocalDate.of(2050, 1, 1))
                .start(LocalTime.of(8, 0)).end(LocalTime.of(17, 0)).customerCode(code).build();
        transaction.execute(() -> {
            codes.save(new RegisteredCustomerCode(code, true, 1), 0);
            entries.save(new VersionedTimeEntry(entry, 1, false), 0);
            return null;
        });
        var overlapping = entry.toBuilder().id(UUID.randomUUID()).build();
        assertThrows(ConflictException.class, () -> transaction.execute(() -> {
            entries.save(new VersionedTimeEntry(overlapping, 1, false), 0);
            return null;
        }));
        assertTrue(entries.find(overlapping.id()).isEmpty());
    }
}
