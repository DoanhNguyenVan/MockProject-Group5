package com.boxcinema.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared validation and normalization rules for emails, phone numbers (E.164),
 * full names, and passwords.
 */
@Service
public class PasswordPolicyService {

    public static final int EMAIL_MAX_LENGTH = 255;
    public static final int FULL_NAME_MIN_LENGTH = 2;
    public static final int FULL_NAME_MAX_LENGTH = 100;
    public static final int PASSWORD_MIN_CODE_POINTS = 6;
    public static final int PASSWORD_MAX_CODE_POINTS = 128;

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    private static final Pattern E164_PHONE_PATTERN = Pattern.compile(
            "^\\+[1-9][0-9]{7,14}$"
    );

    private static final Pattern LOCAL_VN_PHONE_PATTERN = Pattern.compile(
            "^0[1-9][0-9]{8,9}$"
    );

    public String validateAndNormalizeEmail(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new IllegalArgumentException("Email is required.");
        }
        // Preserve Gmail dots and +alias tags; canonicalize only trim + lowercase.
        String cleanEmail = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (cleanEmail.length() > EMAIL_MAX_LENGTH || !EMAIL_PATTERN.matcher(cleanEmail).matches()) {
            throw new IllegalArgumentException("Please enter a valid email address.");
        }
        return cleanEmail;
    }

    public boolean isValidEmailFormat(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return false;
        }
        String cleanEmail = rawEmail.trim().toLowerCase(Locale.ROOT);
        return cleanEmail.length() <= EMAIL_MAX_LENGTH && EMAIL_PATTERN.matcher(cleanEmail).matches();
    }

    /**
     * Normalizes input phone number strictly into E.164 format (e.g. +84901234567).
     * Accepts +<country><subscriber> or local leading-zero numbers (mapped to +84).
     */
    public String validateAndNormalizePhone(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            throw new IllegalArgumentException("Phone number is required.");
        }
        String compact = rawPhone.trim().replaceAll("[\\s().-]+", "");
        String e164;
        if (LOCAL_VN_PHONE_PATTERN.matcher(compact).matches()) {
            e164 = "+84" + compact.substring(1);
        } else if (E164_PHONE_PATTERN.matcher(compact).matches()) {
            e164 = compact;
        } else {
            throw new IllegalArgumentException("Please enter a valid phone number in E.164 format (e.g. +84901234567 or 0901234567).");
        }
        if (e164.length() > 20) {
            throw new IllegalArgumentException("Phone number is too long.");
        }
        return e164;
    }

    /**
     * Returns equivalent storage variants so uniqueness checks match both E.164 (+84...) and legacy local (0...) rows.
     */
    public List<String> getEquivalentPhoneVariants(String normalizedE164Phone) {
        List<String> variants = new ArrayList<>(2);
        if (normalizedE164Phone == null || normalizedE164Phone.isBlank()) {
            return variants;
        }
        variants.add(normalizedE164Phone);
        if (normalizedE164Phone.startsWith("+84") && normalizedE164Phone.length() >= 11) {
            variants.add("0" + normalizedE164Phone.substring(3));
        }
        return variants;
    }

    public String validateFullName(String rawName) {
        if (rawName == null || rawName.trim().length() < FULL_NAME_MIN_LENGTH) {
            throw new IllegalArgumentException("Full name must be at least 2 characters.");
        }
        String trimmed = rawName.trim();
        if (trimmed.length() > FULL_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Full name must not exceed 100 characters.");
        }
        return trimmed;
    }

    /**
     * Validates new passwords:
     * - 6 to 128 Unicode code points, not blank whitespace
     * - Must include at least 1 uppercase letter, 1 number, and 1 special character
     */
    public void validatePasswordStrength(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password is required.");
        }
        int codePoints = password.codePointCount(0, password.length());
        if (codePoints < PASSWORD_MIN_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Password must be at least " + PASSWORD_MIN_CODE_POINTS + " characters long."
            );
        }
        if (codePoints > PASSWORD_MAX_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Password must not exceed " + PASSWORD_MAX_CODE_POINTS + " characters."
            );
        }
        boolean hasUppercase = password.codePoints().anyMatch(Character::isUpperCase);
        boolean hasDigit = password.codePoints().anyMatch(cp -> cp >= '0' && cp <= '9');
        boolean hasSpecial = password.codePoints().anyMatch(cp -> !Character.isLetterOrDigit(cp) && !Character.isWhitespace(cp));
        if (!hasUppercase || !hasDigit || !hasSpecial) {
            throw new IllegalArgumentException(
                    "Password must include at least 1 uppercase letter, 1 number, and 1 special character."
            );
        }
    }

    public void validatePasswordPair(String password, String confirmPassword) {
        validatePasswordStrength(password);
        if (confirmPassword == null || !password.equals(confirmPassword)) {
            throw new IllegalArgumentException("Passwords do not match.");
        }
    }
}
