package com.timereport.timeentry.adapter.out.persistence;

import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.timeentry.application.port.out.TimeEntryRepository;
import com.timereport.timeentry.application.service.ConflictException;
import com.timereport.timeentry.domain.CustomerCode;
import com.timereport.timeentry.domain.TimeEntry;
import com.timereport.timeentry.domain.VersionedTimeEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JdbcTimeEntryRepository implements TimeEntryRepository {
    private final DataSource dataSource;
    private final AccountIdentity identity;

    public JdbcTimeEntryRepository(DataSource dataSource, AccountIdentity identity) {
        this.dataSource = dataSource;
        this.identity = identity;
    }

    @Override
    public Optional<VersionedTimeEntry> find(UUID id) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT * FROM time_entries WHERE id = ? AND account_id = ?")) {
            statement.setObject(1, id);
            statement.setObject(2, identity.accountId());
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw JdbcErrors.translate(e);
        }
    }

    @Override
    public List<VersionedTimeEntry> list(LocalDate from, LocalDate to) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT * FROM time_entries WHERE work_date BETWEEN ? AND ? AND NOT deleted AND account_id = ?
                ORDER BY work_date, start_time, id
                """)) {
            statement.setObject(1, from);
            statement.setObject(2, to);
            statement.setObject(3, identity.accountId());
            try (var result = statement.executeQuery()) {
                var entries = new ArrayList<VersionedTimeEntry>();
                while (result.next()) {
                    entries.add(map(result));
                }
                return List.copyOf(entries);
            }
        } catch (SQLException e) {
            throw JdbcErrors.translate(e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void save(VersionedTimeEntry saved, long expectedVersion) {
        String sql = expectedVersion == 0 ? """
                INSERT INTO time_entries
                (work_date, start_time, end_time, deducted_minutes, customer_code, comment, version, deleted, id, account_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """ : """
                UPDATE time_entries SET work_date = ?, start_time = ?, end_time = ?, deducted_minutes = ?,
                customer_code = ?, comment = ?, version = ?, deleted = ? WHERE id = ? AND account_id = ? AND version = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            TimeEntry entry = saved.entry();
            statement.setObject(1, entry.date());
            statement.setObject(2, entry.start());
            statement.setObject(3, entry.end());
            statement.setInt(4, entry.deductedMinutes());
            statement.setString(5, entry.customerCode() == null ? null : entry.customerCode().value());
            statement.setString(6, entry.comment());
            statement.setLong(7, saved.version());
            statement.setBoolean(8, saved.deleted());
            statement.setObject(9, entry.id());
            statement.setObject(10, identity.accountId());
            if (expectedVersion > 0) {
                statement.setLong(11, expectedVersion);
            }
            if (statement.executeUpdate() != 1) {
                throw new ConflictException("Time entry changed; reload before saving");
            }
        } catch (SQLException e) {
            throw JdbcErrors.translate(e);
        }
    }

    private VersionedTimeEntry map(ResultSet result) throws SQLException {
        var entry = TimeEntry.builder().id(result.getObject("id", UUID.class))
                .date(result.getObject("work_date", LocalDate.class))
                .start(result.getObject("start_time", LocalTime.class))
                .end(result.getObject("end_time", LocalTime.class))
                .deductedMinutes(result.getInt("deducted_minutes"))
                .customerCode(result.getString("customer_code") == null ? null : new CustomerCode(result.getString("customer_code")))
                .comment(result.getString("comment")).build();
        return VersionedTimeEntry.builder().entry(entry).version(result.getLong("version"))
                .deleted(result.getBoolean("deleted")).build();
    }
}
