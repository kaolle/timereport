package com.timereport.subscription.adapter.out.account;

import com.timereport.authentication.application.port.out.AuthRepository;
import com.timereport.subscription.application.port.out.SubscriptionAccountEmail;
import com.timereport.subscription.application.service.SubscriptionPurchaseConflict;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class AuthenticationAccountEmail implements SubscriptionAccountEmail {
    private final AuthRepository accounts;

    public AuthenticationAccountEmail(AuthRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public String email(UUID accountId) {
        return accounts.account(accountId).orElseThrow(() ->
                new SubscriptionPurchaseConflict("Kontot finns inte längre.")).email();
    }
}
