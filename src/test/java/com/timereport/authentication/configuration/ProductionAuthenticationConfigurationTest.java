package com.timereport.authentication.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionAuthenticationConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void acceptsHttpsOriginWithSecureCookies() {
        assertDoesNotThrow(() -> ProductionAuthenticationConfiguration.validateOrigin("https://time.example.com", true));
        assertDoesNotThrow(() -> ProductionAuthenticationConfiguration.validateOrigin("https://time.example.com:8443", true));
    }

    @Test
    void rejectsOriginsThatCannotMatchBrowserOriginOrExposeLinksOverHttp() {
        for (String origin : new String[]{"http://localhost:8080", "https://time.example.com/",
                "https://time.example.com/app", "https://user@time.example.com", "https://time.example.com?x=1",
                "https://time.example.com#reset", "not a URL", "https://time.example.com:99999"}) {
            assertThrows(IllegalStateException.class,
                    () -> ProductionAuthenticationConfiguration.validateOrigin(origin, true), origin);
        }
    }

    @Test
    void rejectsInsecureProductionCookies() {
        assertThrows(IllegalStateException.class,
                () -> ProductionAuthenticationConfiguration.validateOrigin("https://time.example.com", false));
    }

    @Test
    void acceptsMatchingKeysAndRejectsMismatchedOrMissingKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        Path privateKey = writePem("private.pem", "PRIVATE KEY", pair.getPrivate().getEncoded());
        Path publicKey = writePem("public.pem", "PUBLIC KEY", pair.getPublic().getEncoded());
        assertDoesNotThrow(() -> ProductionAuthenticationConfiguration.validateKeys(privateKey, publicKey));
        Path otherPublicKey = writePem("other.pem", "PUBLIC KEY", generator.generateKeyPair().getPublic().getEncoded());
        assertThrows(IllegalStateException.class,
                () -> ProductionAuthenticationConfiguration.validateKeys(privateKey, otherPublicKey));
        assertThrows(IllegalStateException.class,
                () -> ProductionAuthenticationConfiguration.validateKeys(directory.resolve("missing"), publicKey));
    }

    @Test
    void rejectsWeakKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        var pair = generator.generateKeyPair();
        Path privateKey = writePem("private.pem", "PRIVATE KEY", pair.getPrivate().getEncoded());
        Path publicKey = writePem("public.pem", "PUBLIC KEY", pair.getPublic().getEncoded());
        assertThrows(IllegalStateException.class,
                () -> ProductionAuthenticationConfiguration.validateKeys(privateKey, publicKey));
    }

    private Path writePem(String file, String label, byte[] encoded) throws Exception {
        return Files.writeString(directory.resolve(file), "-----BEGIN " + label + "-----\n"
                + Base64.getEncoder().encodeToString(encoded) + "\n-----END " + label + "-----\n");
    }
}
