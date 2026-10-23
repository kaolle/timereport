package com.timereport.authentication.adapter.in.rest;

import lombok.Builder;

@Builder
public record AuthConfigResponse(boolean emailVerificationRequired) {
}
