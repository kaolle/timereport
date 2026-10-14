package com.timereport.authentication.adapter.in.rest;

import lombok.Builder;

@Builder
public record AccountResponse(java.util.UUID accountId, String email) {
}
