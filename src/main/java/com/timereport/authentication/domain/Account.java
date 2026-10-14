package com.timereport.authentication.domain;

import lombok.Builder;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Builder(toBuilder = true)
public record Account(UUID id, String email, String passwordHash, boolean verified) {
    public Account {
        requireNonNull(id, "id is required");
        requireNonNull(email, "email is required");
        requireNonNull(passwordHash, "passwordHash is required");
    }

    @Override
    public String toString() {
        return "Account[redacted]";
    }
}
