package com.timereport.authentication.configuration;

import com.timereport.authentication.application.port.in.Authentication;
import com.timereport.authentication.application.port.out.AccountMail;
import com.timereport.authentication.application.port.out.AuthRepository;
import com.timereport.authentication.application.port.out.Passwords;
import com.timereport.authentication.application.port.out.Tokens;
import com.timereport.authentication.application.service.AuthenticationService;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;

public class AuthenticationConfiguration {
    @Produces
    @Singleton
    public Authentication authentication(AuthRepository repository, Passwords passwords, Tokens tokens, AccountMail mail,
                                         @ConfigProperty(name = "auth.email-verification-required", defaultValue = "true")
                                         boolean emailVerificationRequired) {
        return new AuthenticationService(repository, passwords, tokens, mail, Clock.systemUTC(), emailVerificationRequired);
    }
}
