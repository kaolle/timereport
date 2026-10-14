package com.timereport.authentication.application.port.out;

public interface Passwords {
    String hash(String password);

    boolean matches(String password, String hash);
}
