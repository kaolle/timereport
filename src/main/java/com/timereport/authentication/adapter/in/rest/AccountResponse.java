package com.timereport.authentication.adapter.in.rest;

import lombok.Builder;

import java.util.Set;
import java.util.UUID;

@Builder
public record AccountResponse(UUID accountId, String email, Set<String> roles) {
    public AccountResponse {
        roles = Set.copyOf(roles);
    }
}
