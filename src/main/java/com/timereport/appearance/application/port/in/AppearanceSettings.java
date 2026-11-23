package com.timereport.appearance.application.port.in;

import com.timereport.appearance.domain.Appearance;

public interface AppearanceSettings {
    Appearance current();
    Appearance change(Appearance appearance);
}
