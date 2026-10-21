package com.timereport.authentication.configuration;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Maps optional environment variables without overriding dev/test defaults with empty values.
 */
public final class JwtKeyConfigSource implements ConfigSource {
    private static final Map<String, String> VARIABLES = Map.of(
            "smallrye.jwt.sign.key", "JWT_PRIVATE_KEY",
            "mp.jwt.verify.publickey", "JWT_PUBLIC_KEY",
            "smallrye.jwt.sign.key.location", "JWT_PRIVATE_KEY_PATH",
            "mp.jwt.verify.publickey.location", "JWT_PUBLIC_KEY_PATH");

    private final Map<String, String> properties;

    public JwtKeyConfigSource() {
        this(System.getenv());
    }

    JwtKeyConfigSource(Map<String, String> environment) {
        var values = new HashMap<String, String>();
        VARIABLES.forEach((property, variable) -> {
            String value = environment.get(variable);
            if (value != null && !value.isBlank()) {
                values.put(property, value);
            }
        });
        properties = Map.copyOf(values);
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public Set<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
    }

    @Override
    public String getName() {
        return "JWT environment variables";
    }

    @Override
    public int getOrdinal() {
        // Override application.properties; standard environment variables/system properties still win.
        return 275;
    }
}
