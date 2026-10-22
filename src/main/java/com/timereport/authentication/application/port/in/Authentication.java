package com.timereport.authentication.application.port.in;

import com.timereport.authentication.domain.AuthTokens;

import java.util.UUID;

public interface Authentication {
    void register(String email, String password);

    void verifyEmail(String token, String password);

    AuthTokens login(String email, String password);

    AuthTokens refresh(String token);

    void logout(String token);

    void requestReset(String email);

    void resetPassword(String token, String password);

    void changePassword(UUID accountId, String currentPassword, String newPassword);
}
