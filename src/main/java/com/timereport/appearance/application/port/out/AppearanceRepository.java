package com.timereport.appearance.application.port.out;

import com.timereport.appearance.domain.Appearance;

public interface AppearanceRepository {
    Appearance load();
    void save(Appearance appearance);
}
