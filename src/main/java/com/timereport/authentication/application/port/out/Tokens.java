package com.timereport.authentication.application.port.out;

import com.timereport.authentication.domain.Account;
import com.timereport.authentication.domain.Session;

public interface Tokens {
    String random();

    String digest(String token);

    String access(Account account, Session session);

    long accessLifetimeSeconds();
}
