package com.timereport.authentication.application.service;

import com.timereport.authentication.application.port.in.Authentication;
import com.timereport.authentication.application.port.out.AccountMail;
import com.timereport.authentication.application.port.out.AuthRepository;
import com.timereport.authentication.application.port.out.Passwords;
import com.timereport.authentication.application.port.out.Tokens;
import com.timereport.authentication.domain.*;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

public class AuthenticationService implements Authentication {
    private final AuthRepository repository;
    private final Passwords passwords;
    private final Tokens tokens;
    private final AccountMail mail;
    private final Clock clock;
    private final String dummyHash;
    private final boolean emailVerificationRequired;

    public AuthenticationService(AuthRepository repository, Passwords passwords, Tokens tokens, AccountMail mail, Clock clock,
                                 boolean emailVerificationRequired) {
        this.repository = repository;
        this.passwords = passwords;
        this.tokens = tokens;
        this.mail = mail;
        this.clock = clock;
        this.emailVerificationRequired = emailVerificationRequired;
        dummyHash = passwords.hash(tokens.random());
    }

    private String email(String value) {
        if (value == null || value.length() > 254 || !value.strip().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Ange en giltig e-postadress.");
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private void password(String value) {
        if (value == null || value.length() < 15 || value.length() > 128) {
            throw new IllegalArgumentException("Lösenordet ska innehålla 15–128 tecken.");
        }
    }

    @Override
    public void register(String email, String password) {
        String normalized = email(email);
        String hash;
        if (emailVerificationRequired) {
            // Pending accounts have no user-selected password; the random secret is never disclosed.
            hash = passwords.hash(tokens.random());
        } else {
            password(password);
            hash = passwords.hash(password);
        }
        String passwordHash = hash;
        repository.atomic(() -> {
            var existing = repository.account(normalized);
            if (existing.isPresent()) {
                if (!existing.get().verified()) {
                    if (emailVerificationRequired) {
                        sendToken(existing.get(), "verify");
                    } else {
                        repository.save(existing.get().toBuilder().passwordHash(passwordHash).verified(true).build());
                        repository.consumeTokens(existing.get().id(), "verify");
                    }
                }
                return null;
            }
            var account = Account.builder().id(UUID.randomUUID()).email(normalized).passwordHash(passwordHash)
                    .verified(!emailVerificationRequired).build();
            repository.save(account);
            if (emailVerificationRequired) sendToken(account, "verify");
            return null;
        });
    }

    private void sendToken(Account account, String purpose) {
        String raw = tokens.random();
        repository.consumeTokens(account.id(), purpose);
        repository.save(AccountToken.builder().hash(tokens.digest(raw)).accountId(account.id()).purpose(purpose)
                .expiresAt(clock.instant().plus(Duration.ofMinutes(30))).consumed(false).build());
        mail.send(account.email(), purpose, raw);
    }

    @Override
    public void verifyEmail(String raw, String password) {
        password(password);
        String hash = passwords.hash(password);
        repository.atomic(() -> {
            var token = validToken(raw, "verify");
            var account = repository.account(token.accountId()).orElseThrow(AuthenticationFailed::new);
            if (account.verified()) throw new AuthenticationFailed();
            repository.save(account.toBuilder().passwordHash(hash).verified(true).build());
            revoke(account.id());
            return null;
        });
    }

    private AccountToken validToken(String raw, String purpose) {
        var token = repository.token(tokens.digest(raw)).orElseThrow(AuthenticationFailed::new);
        if (token.consumed() || !purpose.equals(token.purpose()) || !token.expiresAt().isAfter(clock.instant())) {
            throw new AuthenticationFailed();
        }
        return token;
    }

    @Override
    public AuthTokens login(String email, String password) {
        String normalized = email(email);
        return repository.atomic(() -> {
            var found = repository.account(normalized);
            boolean matches = passwords.matches(password, found.map(Account::passwordHash).orElse(dummyHash));
            if (!matches || found.isEmpty() || !found.get().verified()) throw new AuthenticationFailed();
            var session = Session.builder().id(UUID.randomUUID()).accountId(found.get().id())
                    .expiresAt(clock.instant().plus(Duration.ofDays(30))).revoked(false).build();
            repository.save(session);
            return issue(found.get(), session);
        });
    }

    private AuthTokens issue(Account account, Session session) {
        String raw = tokens.random();
        repository.save(RefreshToken.builder().hash(tokens.digest(raw)).sessionId(session.id()).consumed(false).build());
        return AuthTokens.builder().account(account).accessToken(tokens.access(account, session)).refreshToken(raw)
                .refreshExpiresAt(session.expiresAt()).expiresIn(tokens.accessLifetimeSeconds()).build();
    }

    @Override
    public AuthTokens refresh(String raw) {
        AuthTokens result = repository.atomic(() -> {
            var credential = repository.refresh(tokens.digest(raw)).orElse(null);
            if (credential == null) return null;
            var session = repository.session(credential.sessionId()).orElse(null);
            if (session == null || session.revoked() || !session.expiresAt().isAfter(clock.instant())) return null;
            if (credential.consumed()) {
                // Commit revocation before returning 401: throwing inside atomic would roll it back.
                repository.save(session.toBuilder().revoked(true).build());
                return null;
            }
            var account = repository.account(session.accountId()).orElseThrow(AuthenticationFailed::new);
            repository.save(credential.toBuilder().consumed(true).build());
            return issue(account, session);
        });
        if (result == null) throw new AuthenticationFailed();
        return result;
    }

    @Override
    public void logout(String raw) {
        repository.atomic(() -> {
            repository.refresh(tokens.digest(raw)).flatMap(token -> repository.session(token.sessionId()))
                    .ifPresent(session -> repository.save(session.toBuilder().revoked(true).build()));
            return null;
        });
    }

    @Override
    public void requestReset(String email) {
        String normalized = email(email);
        repository.atomic(() -> {
            repository.account(normalized).ifPresent(account -> sendToken(account, "reset"));
            return null;
        });
    }

    @Override
    public void resetPassword(String raw, String password) {
        password(password);
        String hash = passwords.hash(password);
        repository.atomic(() -> {
            var token = validToken(raw, "reset");
            var account = repository.account(token.accountId()).orElseThrow(AuthenticationFailed::new);
            repository.save(account.toBuilder().passwordHash(hash).verified(true).build());
            revoke(account.id());
            return null;
        });
    }

    @Override
    public void changePassword(UUID accountId, String currentPassword, String newPassword) {
        password(newPassword);
        repository.atomic(() -> {
            var account = repository.account(accountId).orElseThrow(AuthenticationFailed::new);
            if (!passwords.matches(currentPassword, account.passwordHash())) throw new AuthenticationFailed();
            repository.save(account.toBuilder().passwordHash(passwords.hash(newPassword)).build());
            revoke(accountId);
            return null;
        });
    }

    private void revoke(UUID accountId) {
        repository.revokeSessions(accountId);
        repository.consumeTokens(accountId, "verify");
        repository.consumeTokens(accountId, "reset");
    }
}
