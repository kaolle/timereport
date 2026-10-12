package com.timereport.timeentry.adapter.in.rest;

import lombok.Builder;

@Builder
public record ErrorResponse(String code, String message) {
}
