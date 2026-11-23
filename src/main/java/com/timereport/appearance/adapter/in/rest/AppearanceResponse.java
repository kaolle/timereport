package com.timereport.appearance.adapter.in.rest;

import com.timereport.appearance.domain.Appearance;
import lombok.Builder;

@Builder
public record AppearanceResponse(Appearance.Theme theme) {
    public static AppearanceResponse from(Appearance appearance) { return new AppearanceResponse(appearance.theme()); }
}
