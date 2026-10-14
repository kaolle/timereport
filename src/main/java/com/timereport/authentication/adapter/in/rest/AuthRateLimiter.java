package com.timereport.authentication.adapter.in.rest;

import com.timereport.authentication.application.port.out.Tokens;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.SQLException;

@ApplicationScoped
public class AuthRateLimiter {
    private final DataSource dataSource;
    private final Tokens tokens;

    public AuthRateLimiter(DataSource dataSource, Tokens tokens) {
        this.dataSource = dataSource;
        this.tokens = tokens;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean allow(String key, int limit) {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("""
                INSERT INTO auth_rate_limits(bucket,window_start,hits) VALUES (?,now(),1)
                ON CONFLICT(bucket) DO UPDATE SET
                hits=CASE WHEN auth_rate_limits.window_start < now()-interval '15 minutes' THEN 1 ELSE auth_rate_limits.hits+1 END,
                window_start=CASE WHEN auth_rate_limits.window_start < now()-interval '15 minutes' THEN now() ELSE auth_rate_limits.window_start END
                RETURNING hits
                """)) {
            s.setString(1, tokens.digest(key));
            try (var r = s.executeQuery()) {
                r.next();
                return r.getInt(1) <= limit;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Rate limit storage failed", e);
        }
    }
}
