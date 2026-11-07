package com.timereport.authentication.application.port.out;

public interface AccountMail {
    void send(java.util.UUID accountId, String email, String purpose, String token, java.time.Instant expiresAt);

    void cancel(java.util.UUID accountId, String purpose);
}
