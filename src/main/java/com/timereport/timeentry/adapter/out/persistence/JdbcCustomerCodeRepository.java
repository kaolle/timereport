package com.timereport.timeentry.adapter.out.persistence;

import com.timereport.timeentry.application.port.out.CustomerCodeRepository;
import com.timereport.timeentry.application.service.ConflictException;
import com.timereport.timeentry.domain.CustomerCode;
import com.timereport.timeentry.domain.RegisteredCustomerCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class JdbcCustomerCodeRepository implements CustomerCodeRepository {
    private final DataSource dataSource;

    public JdbcCustomerCodeRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<RegisteredCustomerCode> find(CustomerCode code) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT code, active, version FROM customer_codes WHERE code = ?")) {
            statement.setString(1, code.value());
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw JdbcErrors.translate(e);
        }
    }

    @Override
    public List<RegisteredCustomerCode> list() {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT code, active, version FROM customer_codes ORDER BY code");
                var result = statement.executeQuery()) {
            var codes = new ArrayList<RegisteredCustomerCode>();
            while (result.next()) {
                codes.add(map(result));
            }
            return List.copyOf(codes);
        } catch (SQLException e) {
            throw JdbcErrors.translate(e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void save(RegisteredCustomerCode code, long expectedVersion) {
        String sql = expectedVersion == 0
                ? "INSERT INTO customer_codes (active, version, code) VALUES (?, ?, ?)"
                : "UPDATE customer_codes SET active = ?, version = ? WHERE code = ? AND version = ?";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, code.active());
            statement.setLong(2, code.version());
            statement.setString(3, code.code().value());
            if (expectedVersion > 0) {
                statement.setLong(4, expectedVersion);
            }
            if (statement.executeUpdate() != 1) {
                throw new ConflictException("Customer code changed; reload before saving");
            }
        } catch (SQLException e) {
            throw JdbcErrors.translate(e);
        }
    }

    private RegisteredCustomerCode map(ResultSet result) throws SQLException {
        return RegisteredCustomerCode.builder().code(new CustomerCode(result.getString("code")))
                .active(result.getBoolean("active")).version(result.getLong("version")).build();
    }
}
