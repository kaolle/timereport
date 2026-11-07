package com.timereport.mail.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timereport.mail.domain.MailMessage;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@ApplicationScoped
public class EncryptedMailPayload {
    private static final int IV_LENGTH = 12;
    private final SecretKeySpec key;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public EncryptedMailPayload(@ConfigProperty(name = "mail.outbox-key") String encodedKey, ObjectMapper mapper) {
        byte[] bytes = Base64.getDecoder().decode(encodedKey);
        if (bytes.length != 32) throw new IllegalArgumentException("MAIL_OUTBOX_KEY must be a 32-byte base64 key");
        key = new SecretKeySpec(bytes, "AES");
        this.mapper = mapper;
    }

    public byte[] encrypt(MailMessage message) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(mapper.writeValueAsBytes(message));
            byte[] output = Arrays.copyOf(iv, iv.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, output, iv.length, ciphertext.length);
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("Mail could not be queued", e);
        }
    }

    public MailMessage decrypt(byte[] payload) {
        try {
            if (payload.length <= IV_LENGTH + 16) throw new IllegalArgumentException("Mail payload is invalid");
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(payload, IV_LENGTH)));
            return mapper.readValue(cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH), MailMessage.class);
        } catch (Exception e) {
            // Jackson's parsing exception may quote the decrypted text. Do not log it.
            throw new IllegalStateException("Queued mail could not be read");
        }
    }
}
