package com.timereport.appearance.application.service;

import com.timereport.appearance.application.port.in.AppearanceSettings;
import com.timereport.appearance.application.port.out.AppearanceRepository;
import com.timereport.appearance.domain.Appearance;

public class AppearanceService implements AppearanceSettings {
    private final AppearanceRepository repository;

    public AppearanceService(AppearanceRepository repository) { this.repository = repository; }

    public Appearance current() { return repository.load(); }

    public Appearance change(Appearance appearance) {
        repository.save(appearance);
        return appearance;
    }
}
