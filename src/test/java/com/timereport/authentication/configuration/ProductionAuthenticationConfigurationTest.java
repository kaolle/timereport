package com.timereport.authentication.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void acceptsSecretManagerPemValuesWithMultilineContent() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded()).replace("\n", "\r\n");
        String publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());
        assertDoesNotThrow(() -> ProductionAuthenticationConfiguration.validateKeyConfiguration(
                Optional.of(privatePem), Optional.of(publicPem), Optional.empty(), Optional.empty()));
    }

    @Test
    void acceptsFileConfigurationWithoutInlineValues() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        Path privatePath = writePem("private.pem", "PRIVATE KEY", pair.getPrivate().getEncoded());
        Path publicPath = writePem("public.pem", "PUBLIC KEY", pair.getPublic().getEncoded());
        assertDoesNotThrow(() -> ProductionAuthenticationConfiguration.validateKeyConfiguration(
                Optional.empty(), Optional.empty(), Optional.of(privatePath.toString()), Optional.of(publicPath.toString())));
    }

    @Test
    void rejectsMissingPartialAndMixedKeySources() {
        Optional<String> none = Optional.empty();
        Optional<String> supplied = Optional.of("value-must-not-be-logged");
        for (var sources : java.util.List.of(
                java.util.List.of(none, none, none, none),
                java.util.List.of(supplied, none, none, none),
                java.util.List.of(none, supplied, none, none),
                java.util.List.of(none, none, supplied, none),
                java.util.List.of(none, none, none, supplied),
                java.util.List.of(supplied, supplied, supplied, supplied),
                java.util.List.of(supplied, none, none, supplied))) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> ProductionAuthenticationConfiguration.validateKeyConfiguration(
                            sources.get(0), sources.get(1), sources.get(2), sources.get(3)));
            assertFalse(failure.getMessage().contains(supplied.get()));
            assertNull(failure.getCause());
        }
    }

    @Test
    void rejectsMismatchedAndWeakInlineKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var first = generator.generateKeyPair();
        var second = generator.generateKeyPair();
        assertThrows(IllegalStateException.class, () -> ProductionAuthenticationConfiguration.validateKeys(
                pem("PRIVATE KEY", first.getPrivate().getEncoded()), pem("PUBLIC KEY", second.getPublic().getEncoded())));
        generator.initialize(1024);
        var weak = generator.generateKeyPair();
        assertThrows(IllegalStateException.class, () -> ProductionAuthenticationConfiguration.validateKeys(
                pem("PRIVATE KEY", weak.getPrivate().getEncoded()), pem("PUBLIC KEY", weak.getPublic().getEncoded())));
    }

    @Test
    void rejectsMalformedPemWithoutIncludingKeyContentInErrors() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        String publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());
        for (String invalid : new String[]{"", "secret-content-must-not-be-logged",
                privatePem.replace("PRIVATE KEY", "RSA PRIVATE KEY"), privatePem.replace("\n", "\\n"), publicPem}) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> ProductionAuthenticationConfiguration.validateKeys(invalid, publicPem));
            assertFalse(failure.getMessage().contains("secret-content-must-not-be-logged"));
            assertFalse(failure.getMessage().contains("-----BEGIN"));
            assertNull(failure.getCause());
        }
        assertThrows(IllegalStateException.class,
                () -> ProductionAuthenticationConfiguration.validateKeys(privatePem, "invalid-public-key"));
    }

    @Test
    void invalidPathDoesNotLeakAccidentallyInjectedKeyContent() {
        var failure = assertThrows(IllegalStateException.class,
                () -> ProductionAuthenticationConfiguration.validateKeyConfiguration(
                        Optional.empty(), Optional.empty(), Optional.of("secret-content\u0000"), Optional.of("public.pem")));
        assertFalse(failure.getMessage().contains("secret-content"));
        assertNull(failure.getCause());
    }

    private Path writePem(String file, String label, byte[] encoded) throws Exception {
        return Files.writeString(directory.resolve(file), pem(label, encoded));
    }

    private static String pem(String label, byte[] encoded) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded) + "\n-----END " + label + "-----\n";
    }
}
