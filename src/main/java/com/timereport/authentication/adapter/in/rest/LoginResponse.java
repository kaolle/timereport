package com.timereport.authentication.adapter.in.rest;

import lombok.Builder;

@Builder
public record LoginResponse(String accessToken, String tokenType, long expiresIn, AccountResponse account) {
    @Override
    public String toString() {
        return "LoginResponse[redacted]";
    }

}
