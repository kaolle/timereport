package com.timereport.authentication.application.service;

import com.timereport.authentication.application.port.in.AccountAuthorization;
import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.authentication.application.port.out.AccountRoles;

public class AccountAuthorizationService implements AccountAuthorization {
    private final AccountIdentity identity;
    private final AccountRoles roles;

    public AccountAuthorizationService(AccountIdentity identity, AccountRoles roles) {
        this.identity = identity;
        this.roles = roles;
    }

    @Override
    public void requireAdmin() {
        // Administrative actions recheck the database so a revoked grant stops
        // working immediately, even while an older access token remains valid.
        if (!roles.roles(identity.accountId()).contains("admin")) throw new AdministrationForbidden();
    }
}
