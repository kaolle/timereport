package com.timereport.appearance.configuration;

import com.timereport.appearance.application.port.in.AppearanceSettings;
import com.timereport.appearance.application.port.out.AppearanceRepository;
import com.timereport.appearance.application.service.AppearanceService;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

public class AppearanceConfiguration {
    @Produces
    @Singleton
    public AppearanceSettings appearanceSettings(AppearanceRepository repository) {
        return new AppearanceService(repository);
    }
}
