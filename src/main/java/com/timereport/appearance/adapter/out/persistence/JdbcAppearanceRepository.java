package com.timereport.appearance.adapter.out.persistence;

import com.timereport.appearance.application.port.out.AppearanceRepository;
import com.timereport.appearance.domain.Appearance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import javax.sql.DataSource;
import java.sql.SQLException;

@ApplicationScoped
public class JdbcAppearanceRepository implements AppearanceRepository {
    private final DataSource dataSource;

    public JdbcAppearanceRepository(DataSource dataSource) { this.dataSource = dataSource; }

    public Appearance load() {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT theme FROM application_appearance WHERE id=1");
             var rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("Appens temainställning saknas.");
            return new Appearance(Appearance.Theme.valueOf(rows.getString("theme")));
        } catch (SQLException error) {
            throw new IllegalStateException("Temat kunde inte hämtas.", error);
        }
    }

    @Transactional
    public void save(Appearance appearance) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("UPDATE application_appearance SET theme=? WHERE id=1")) {
            statement.setString(1, appearance.theme().name());
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Appens temainställning saknas.");
        } catch (SQLException error) {
            throw new IllegalStateException("Temat kunde inte sparas.", error);
        }
    }
}
