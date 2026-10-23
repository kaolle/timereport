package com.timereport.invoicing.adapter.in.rest;

import lombok.Builder;

@Builder
public record InvoicingConflictResponse(String code, String message) {
}
