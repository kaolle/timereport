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

@ApplicationScoped
@IfBuildProfile("prod")
public class ProductionAuthenticationConfiguration {
    @ConfigProperty(name = "auth.public-url")
    String publicUrl;
    @ConfigProperty(name = "auth.cookie-secure")
    boolean secure;
    @ConfigProperty(name = "smallrye.jwt.sign.key.location")
    String privateKey;
    @ConfigProperty(name = "mp.jwt.verify.publickey.location")
    String publicKey;

    void start(@Observes StartupEvent event) {
        validateOrigin(publicUrl, secure);
        validateKeys(Path.of(privateKey), Path.of(publicKey));
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

    static void validateKeys(Path privatePath, Path publicPath) {
        try {
            var factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(readPem(privatePath, "PRIVATE KEY")));
            var publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(readPem(publicPath, "PUBLIC KEY")));
            if (privateKey.getModulus().bitLength() < 2048
                    || !privateKey.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalArgumentException("Invalid RSA key pair");
            }
        } catch (Exception e) {
            // Do not include key material or file contents in startup logs.
            throw new IllegalStateException("JWT key files must contain a matching RSA pair (at least 2048 bits): "
                    + "PKCS#8 private PEM and X.509 public PEM.");
        }
    }

    private static byte[] readPem(Path path, String label) throws java.io.IOException {
        String pem = Files.readString(path);
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        if (!pem.strip().startsWith(begin) || !pem.strip().endsWith(end)) {
            throw new IllegalArgumentException("Invalid PEM format");
        }
        return Base64.getDecoder().decode(pem.replace(begin, "").replace(end, "").replaceAll("\\s", ""));
    }
}
