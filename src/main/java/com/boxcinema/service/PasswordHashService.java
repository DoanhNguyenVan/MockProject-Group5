package com.boxcinema.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Specification V1.2 Password Hashing Service:
 * - New hashes always use Argon2id (memory=19456 KiB, iterations=2, parallelism=1, salt=16B, hash=32B).
 * - Legacy BCrypt ($2a$, $2b$, $2y$) hashes remain verifiable without forced password reset,
 *   and upgradeEncoding(...) signals transparent upgrade to Argon2id after verified login.
 */
@Service
public class PasswordHashService implements PasswordEncoder {

    public static final int DEFAULT_SALT_LENGTH = 16;
    public static final int DEFAULT_HASH_LENGTH = 32;
    public static final int DEFAULT_PARALLELISM = 1;
    public static final int DEFAULT_MEMORY_KIB = 19456;
    public static final int DEFAULT_ITERATIONS = 2;

    private final Argon2PasswordEncoder argon2Encoder;
    private final BCryptPasswordEncoder legacyBcryptEncoder;
    private final String expectedArgon2Prefix;

    public PasswordHashService(
            @Value("${app.security.argon2.salt-length:16}") int saltLength,
            @Value("${app.security.argon2.hash-length:32}") int hashLength,
            @Value("${app.security.argon2.parallelism:1}") int parallelism,
            @Value("${app.security.argon2.memory-kib:19456}") int memoryKib,
            @Value("${app.security.argon2.iterations:2}") int iterations) {
        this.argon2Encoder = new Argon2PasswordEncoder(saltLength, hashLength, parallelism, memoryKib, iterations);
        this.legacyBcryptEncoder = new BCryptPasswordEncoder(12);
        this.expectedArgon2Prefix = "$argon2id$v=19$m=" + memoryKib + ",t=" + iterations + ",p=" + parallelism + "$";
    }

    public PasswordHashService() {
        this(DEFAULT_SALT_LENGTH, DEFAULT_HASH_LENGTH, DEFAULT_PARALLELISM, DEFAULT_MEMORY_KIB, DEFAULT_ITERATIONS);
    }

    @Override
    public String encode(CharSequence rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("Password is required.");
        }
        return argon2Encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        if (isArgon2idHash(encodedPassword)) {
            return argon2Encoder.matches(rawPassword, encodedPassword);
        }
        if (isBcryptHash(encodedPassword)) {
            return legacyBcryptEncoder.matches(rawPassword, encodedPassword);
        }
        return false;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        if (isBcryptHash(encodedPassword)) {
            return true;
        }
        if (isArgon2idHash(encodedPassword)) {
            return !encodedPassword.startsWith(expectedArgon2Prefix) && argon2Encoder.upgradeEncoding(encodedPassword);
        }
        return false;
    }

    public boolean isBcryptHash(String encodedPassword) {
        return encodedPassword != null
                && (encodedPassword.startsWith("$2a$")
                || encodedPassword.startsWith("$2b$")
                || encodedPassword.startsWith("$2y$"));
    }

    public boolean isArgon2idHash(String encodedPassword) {
        return encodedPassword != null && encodedPassword.startsWith("$argon2id$");
    }

    /**
     * Produces a fresh Argon2id hash exclusively from a previously verified raw password.
     * Throws on invalid output so the caller never overwrites an existing hash with corrupt data.
     */
    public String upgradeVerifiedPasswordToArgon2id(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("Verified password cannot be empty during hash upgrade.");
        }
        String upgraded = encode(rawPassword);
        if (!isArgon2idHash(upgraded) || !matches(rawPassword, upgraded)) {
            throw new IllegalStateException("Argon2id verification sanity check failed during upgrade.");
        }
        return upgraded;
    }
}

