package com.timereport.authentication.application.port.in;

import java.util.UUID;

/**
 * Derived only from a verified access token and a live server-side session.
 */
public interface AccountIdentity {
    UUID accountId();

    UUID sessionId();
}
