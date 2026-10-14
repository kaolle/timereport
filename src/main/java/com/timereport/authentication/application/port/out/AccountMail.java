package com.timereport.authentication.application.port.out;

public interface AccountMail {
    void send(String email, String purpose, String token);
}
