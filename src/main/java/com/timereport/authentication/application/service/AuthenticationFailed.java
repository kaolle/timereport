package com.timereport.authentication.application.service;

public class AuthenticationFailed extends RuntimeException {
    public AuthenticationFailed() {
        super("Inloggningen eller länken är ogiltig eller har gått ut.");
    }
}
