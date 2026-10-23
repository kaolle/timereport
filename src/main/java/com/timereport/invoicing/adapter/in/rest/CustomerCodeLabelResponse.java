package com.timereport.invoicing.adapter.in.rest;

import lombok.Builder;

@Builder
public record CustomerCodeLabelResponse(String code, String customerShortName) {}
