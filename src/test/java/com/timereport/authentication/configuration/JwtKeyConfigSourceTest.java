package com.timereport.authentication.configuration;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class JwtKeyConfigSourceTest {
    @Test
    void mapsPemValuesWithoutChangingLineBreaks() {
        String privatePem = "-----BEGIN PRIVATE KEY-----\nexample\n-----END PRIVATE KEY-----\n";
        String publicPem = "-----BEGIN PUBLIC KEY-----\r\nexample\r\n-----END PUBLIC KEY-----\r\n";
        var source = new JwtKeyConfigSource(Map.of("JWT_PRIVATE_KEY", privatePem, "JWT_PUBLIC_KEY", publicPem));
        assertEquals(privatePem, source.getValue("smallrye.jwt.sign.key"));
        assertEquals(publicPem, source.getValue("mp.jwt.verify.publickey"));
        assertNull(source.getValue("smallrye.jwt.sign.key.location"));
        assertNull(source.getValue("mp.jwt.verify.publickey.location"));
    }

    @Test
    void mapsLocalPathsWithoutSettingInlineKeys() {
        var source = new JwtKeyConfigSource(Map.of("JWT_PRIVATE_KEY_PATH", "/tmp/private.pem",
                "JWT_PUBLIC_KEY_PATH", "/tmp/public.pem"));
        assertEquals("/tmp/private.pem", source.getValue("smallrye.jwt.sign.key.location"));
        assertEquals("/tmp/public.pem", source.getValue("mp.jwt.verify.publickey.location"));
        assertNull(source.getValue("smallrye.jwt.sign.key"));
        assertNull(source.getValue("mp.jwt.verify.publickey"));
    }

    @Test
    void absentOrEmptyVariablesDoNotSuppressQuarkusGeneratedKeys() {
        assertTrue(new JwtKeyConfigSource(Map.of()).getPropertyNames().isEmpty());
        var source = new JwtKeyConfigSource(Map.of("JWT_PRIVATE_KEY", "", "JWT_PUBLIC_KEY", "  ",
                "JWT_PRIVATE_KEY_PATH", "", "JWT_PUBLIC_KEY_PATH", "", "UNRELATED_SETTING", "value"));
        assertTrue(source.getProperties().isEmpty());
    }

    @Test
    void registeredWithMicroProfileConfigServiceLoader() {
        assertTrue(ServiceLoader.load(ConfigSource.class).stream()
                .anyMatch(provider -> provider.type().equals(JwtKeyConfigSource.class)));
    }
}
