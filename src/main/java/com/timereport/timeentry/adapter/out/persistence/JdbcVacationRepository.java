package com.timereport.timeentry.adapter.out.persistence;

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

    public JdbcVacationRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<VacationDay> list(LocalDate from, LocalDate to) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT vacation_date FROM vacation_days WHERE vacation_date BETWEEN ? AND ? ORDER BY vacation_date")) {
            statement.setObject(1, from);
            statement.setObject(2, to);
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
                ? "INSERT INTO vacation_days (vacation_date) VALUES (?) ON CONFLICT (vacation_date) DO NOTHING"
                : "DELETE FROM vacation_days WHERE vacation_date = ?";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, day.date());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw JdbcErrors.translate(e);
        }
    }
}
