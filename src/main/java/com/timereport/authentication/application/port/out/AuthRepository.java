package com.timereport.authentication.application.port.out;

import com.timereport.authentication.domain.Account;
import com.timereport.authentication.domain.AccountToken;
import com.timereport.authentication.domain.RefreshToken;
import com.timereport.authentication.domain.Session;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface AuthRepository {
    <T> T atomic(Supplier<T> work);

    Optional<Account> account(String email);

    Optional<Account> account(UUID id);

    void save(Account account);

    Optional<Session> session(UUID id);

    void save(Session session);

    Optional<RefreshToken> refresh(String hash);

    void save(RefreshToken token);

    Optional<AccountToken> token(String hash);

    void save(AccountToken token);

    void revokeSessions(UUID accountId);

    void consumeTokens(UUID accountId, String purpose);
}
