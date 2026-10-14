package com.timereport.timeentry.adapter.out.persistence;

import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.timeentry.application.port.out.VacationRepository;
import com.timereport.timeentry.domain.VacationDay;
import jakarta.enterprise.context.ApplicationScoped;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class JdbcVacationRepository implements VacationRepository {
    private final DataSource dataSource;
    private final AccountIdentity identity;

    public JdbcVacationRepository(DataSource dataSource, AccountIdentity identity) {
        this.dataSource = dataSource;
        this.identity = identity;
    }

    @Override
    public List<VacationDay> list(LocalDate from, LocalDate to) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT vacation_date FROM vacation_days WHERE vacation_date BETWEEN ? AND ? AND account_id = ? ORDER BY vacation_date")) {
            statement.setObject(1, from);
            statement.setObject(2, to);
            statement.setObject(3, identity.accountId());
            try (var result = statement.executeQuery()) {
                var days = new ArrayList<VacationDay>();
                while (result.next()) days.add(VacationDay.builder().date(result.getObject(1, LocalDate.class)).build());
                return List.copyOf(days);
            }
        } catch (SQLException e) {
            throw JdbcErrors.translate(e);
        }
    }

    @Override
    public void set(VacationDay day, boolean vacation) {
        String sql = vacation
                ? "INSERT INTO vacation_days (vacation_date, account_id) VALUES (?, ?) ON CONFLICT (account_id, vacation_date) DO NOTHING"
                : "DELETE FROM vacation_days WHERE vacation_date = ? AND account_id = ?";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, day.date());
            statement.setObject(2, identity.accountId());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw JdbcErrors.translate(e);
        }
    }
}
