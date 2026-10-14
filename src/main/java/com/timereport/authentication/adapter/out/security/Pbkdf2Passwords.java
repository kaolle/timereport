package com.timereport.authentication.adapter.out.security;

import com.timereport.authentication.application.port.out.Passwords;
import jakarta.enterprise.context.ApplicationScoped;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@ApplicationScoped
public class Pbkdf2Passwords implements Passwords {
    private static final int ITERATIONS = 600_000;
    private final SecureRandom random = new SecureRandom();

    @Override
    public String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    @Override
    public boolean matches(String password, String hash) {
        if (password == null || password.length() > 128 || hash == null) return false;
        try {
            var parts = hash.split("\\$");
            if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < ITERATIONS || iterations > 2_000_000) return false;
            return MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]),
                    derive(password, Base64.getDecoder().decode(parts[2]), iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        var spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Password hashing unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
