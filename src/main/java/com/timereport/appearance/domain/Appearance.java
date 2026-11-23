package com.timereport.appearance.domain;

import lombok.Builder;
import java.util.Objects;

@Builder
public record Appearance(Theme theme) {
    public Appearance { Objects.requireNonNull(theme, "Tema måste anges."); }
    public enum Theme { STANDARD, CHRISTMAS }
}
