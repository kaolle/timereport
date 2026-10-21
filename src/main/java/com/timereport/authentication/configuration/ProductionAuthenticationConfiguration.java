package com.timereport.authentication.configuration;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

@ApplicationScoped
@IfBuildProfile("prod")
public class ProductionAuthenticationConfiguration {
    @ConfigProperty(name = "auth.public-url")
    String publicUrl;
    @ConfigProperty(name = "auth.cookie-secure")
    boolean secure;
    @ConfigProperty(name = "smallrye.jwt.sign.key")
    Optional<String> privateKey;
    @ConfigProperty(name = "mp.jwt.verify.publickey")
    Optional<String> publicKey;
    @ConfigProperty(name = "smallrye.jwt.sign.key.location")
    Optional<String> privateKeyPath;
    @ConfigProperty(name = "mp.jwt.verify.publickey.location")
    Optional<String> publicKeyPath;

    void start(@Observes StartupEvent event) {
        validateOrigin(publicUrl, secure);
        validateKeyConfiguration(privateKey, publicKey, privateKeyPath, publicKeyPath);
    }

    static void validateOrigin(String publicUrl, boolean secure) {
        URI uri;
        try {
            uri = URI.create(publicUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PUBLIC_URL must be an HTTPS origin without a trailing slash.");
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalStateException("PUBLIC_URL must be an HTTPS origin without a trailing slash.");
        }
        if (!secure) {
            throw new IllegalStateException("auth.cookie-secure must be true in production.");
        }
    }

    static void validateKeyConfiguration(Optional<String> privateKey, Optional<String> publicKey,
                                         Optional<String> privatePath, Optional<String> publicPath) {
        boolean hasValues = privateKey.isPresent() || publicKey.isPresent();
        boolean hasPaths = privatePath.isPresent() || publicPath.isPresent();
        if (hasValues && hasPaths) {
            throw new IllegalStateException("Configure either JWT_PRIVATE_KEY/JWT_PUBLIC_KEY values or "
                    + "JWT_PRIVATE_KEY_PATH/JWT_PUBLIC_KEY_PATH files, not both.");
        }
        if (hasValues && privateKey.isPresent() && publicKey.isPresent()) {
            validateKeys(privateKey.get(), publicKey.get());
        } else if (hasPaths && privatePath.isPresent() && publicPath.isPresent()) {
            try {
                validateKeys(Path.of(privatePath.get()), Path.of(publicPath.get()));
            } catch (java.nio.file.InvalidPathException e) {
                throw unreadableKeyFiles();
            }
        } else {
            throw new IllegalStateException("Configure both JWT_PRIVATE_KEY and JWT_PUBLIC_KEY as PEM values, "
                    + "or both JWT_PRIVATE_KEY_PATH and JWT_PUBLIC_KEY_PATH as readable file paths.");
        }
    }

    static void validateKeys(Path privatePath, Path publicPath) {
        String privatePem;
        String publicPem;
        try {
            privatePem = Files.readString(privatePath);
            publicPem = Files.readString(publicPath);
        } catch (java.io.IOException | SecurityException e) {
            throw unreadableKeyFiles();
        }
        validateKeys(privatePem, publicPem);
    }

    private static IllegalStateException unreadableKeyFiles() {
        // Paths can accidentally contain secret values; never include them or the cause.
        return new IllegalStateException("JWT key files could not be read. JWT_PRIVATE_KEY_PATH and "
                + "JWT_PUBLIC_KEY_PATH must be readable file paths. For PEM values use JWT_PRIVATE_KEY/JWT_PUBLIC_KEY.");
    }

    static void validateKeys(String privatePem, String publicPem) {
        try {
            var factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(decodePem(privatePem, "PRIVATE KEY")));
            var publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(decodePem(publicPem, "PUBLIC KEY")));
            if (privateKey.getModulus().bitLength() < 2048
                    || !privateKey.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalArgumentException("Invalid RSA key pair");
            }
        } catch (Exception e) {
            // Do not include key material or nested exceptions in startup logs.
            throw new IllegalStateException("JWT keys must contain a matching RSA pair (at least 2048 bits): "
                    + "PKCS#8 private PEM and X.509 public PEM.");
        }
    }

    private static byte[] decodePem(String pem, String label) {
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        if (!pem.strip().startsWith(begin) || !pem.strip().endsWith(end)) {
            throw new IllegalArgumentException("Invalid PEM format");
        }
        return Base64.getDecoder().decode(pem.replace(begin, "").replace(end, "").replaceAll("\\s", ""));
    }
}
