package com.boxcinema.common.service;

import com.boxcinema.common.util.CryptoUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.*;

/**
 * Encrypts/decrypts recipient bank account numbers with AES-256-GCM (random 12-byte IV),
 * validates VietQR/NAPAS bank BINs, and masks account numbers for UI/logs/emails.
 */
@Service
public class BankDataEncryptionService {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final Map<String, String> SUPPORTED_VIETQR_BANKS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("970422", "MB Bank (MBBank)");
        m.put("970436", "Vietcombank (VCB)");
        m.put("970415", "VietinBank (CTG)");
        m.put("970418", "BIDV");
        m.put("970407", "Techcombank (TCB)");
        m.put("970405", "Agribank");
        m.put("970416", "ACB");
        m.put("970432", "VPBank");
        m.put("970423", "TPBank");
        m.put("970403", "Sacombank");
        m.put("970437", "HDBank");
        m.put("970441", "VIB");
        m.put("970443", "SHB");
        m.put("970448", "OCB");
        m.put("970426", "MSB");
        m.put("970449", "LPBank");
        m.put("970431", "Eximbank");
        m.put("970454", "BVBank (VietCapital)");
        m.put("970429", "SCB");
        m.put("970414", "OceanBank");
        m.put("970412", "PVcomBank");
        m.put("970424", "Shinhan Bank");
        m.put("970406", "DongA Bank");
        m.put("970438", "BaoViet Bank");
        m.put("970440", "SeABank");
        m.put("970428", "Nam A Bank");
        m.put("970433", "VietBank");
        m.put("970452", "KienlongBank");
        m.put("970458", "UOB Vietnam");
        m.put("422589", "CIMB Vietnam");
        // Additional NAPAS / VietQR banks verified via official VietQR API (api.vietqr.io/v2/banks)
        m.put("546034", "Cake by VPBank");
        m.put("546035", "Ubank by VPBank");
        m.put("963388", "Timo by BVBank");
        m.put("970457", "Woori Bank Vietnam");
        m.put("668888", "KBank Vietnam");
        m.put("458761", "HSBC Vietnam");
        m.put("970410", "Standard Chartered Vietnam");
        m.put("970444", "CBBank (VCBNeo)");
        m.put("970408", "GPBank");
        m.put("970419", "NCB");
        m.put("970409", "Bac A Bank");
        m.put("970425", "ABBank");
        m.put("970400", "Saigonbank");
        m.put("970427", "VietABank");
        m.put("970430", "PGBank");
        SUPPORTED_VIETQR_BANKS = Collections.unmodifiableMap(m);
    }

    @Value("${app.security.bank-encryption-key:}")
    private String configuredEncryptionKey;

    @Value("${payos.payout.mode:MOCK}")
    private String payoutMode;

    @Value("${app.security.hmac-secret:BOX_CINEMA_DEV_FALLBACK}")
    private String devFallbackSeed;

    private byte[] aesKeyBytes;

    @PostConstruct
    public void init() {
        String raw = configuredEncryptionKey != null ? configuredEncryptionKey.trim() : "";
        if ("LIVE".equalsIgnoreCase(payoutMode)) {
            if (raw.isBlank()) {
                throw new IllegalStateException("BANK_DATA_ENC_KEY (app.security.bank-encryption-key) must be configured when payos.payout.mode=LIVE.");
            }
            boolean strongKey = raw.length() >= 32;
            if (!strongKey) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(raw);
                    strongKey = decoded.length >= 32;
                } catch (IllegalArgumentException ignored) {
                    strongKey = false;
                }
            }
            if (!strongKey) {
                throw new IllegalStateException("BANK_DATA_ENC_KEY must be at least 32 characters (or Base64-encoded 32+ bytes) when payos.payout.mode=LIVE.");
            }
        }
        try {
            String material = !raw.isBlank() ? raw : ("MOCK_DEV_BANK_KEY:" + devFallbackSeed);
            this.aesKeyBytes = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize bank encryption key", e);
        }
    }

    private byte[] getKeyBytes() {
        if (this.aesKeyBytes == null) {
            init();
        }
        return this.aesKeyBytes;
    }

    public String validateAndResolveBankName(String toBin) {
        if (toBin == null || !toBin.trim().matches("^\\d{6}$")) {
            throw new IllegalArgumentException("Bank BIN must be a valid 6-digit NAPAS/VietQR code.");
        }
        String cleanBin = toBin.trim();
        String bankName = SUPPORTED_VIETQR_BANKS.get(cleanBin);
        if (bankName == null) {
            throw new IllegalArgumentException("Unsupported bank BIN: " + cleanBin);
        }
        return bankName;
    }

    public String validateAccountNumber(String rawAccount) {
        if (rawAccount == null) {
            throw new IllegalArgumentException("Recipient bank account number is required.");
        }
        String digits = rawAccount.replaceAll("\\s+", "");
        if (!digits.matches("^\\d{6,19}$")) {
            throw new IllegalArgumentException("Bank account number must contain 6 to 19 digits.");
        }
        return digits;
    }

    public String normalizeAccountHolderName(String name) {
        if (name == null || name.isBlank()) return null;
        String decomposed = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'D');
        String ascii = decomposed.replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        if (ascii.length() > 150) ascii = ascii.substring(0, 150);
        return ascii.isEmpty() ? null : ascii;
    }

    public String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) return "";
        String clean = accountNumber.trim();
        if (clean.length() <= 4) {
            return "****";
        }
        int stars = Math.max(4, clean.length() - 4);
        return "*".repeat(stars) + clean.substring(clean.length() - 4);
    }

    public String hashAccountNumber(String accountNumber) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(getKeyBytes(), "HmacSHA256"));
            byte[] digest = mac.doFinal((accountNumber != null ? accountNumber : "").getBytes(StandardCharsets.UTF_8));
            return CryptoUtils.bytesToHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash bank account number securely.", e);
        }
    }

    public String encryptAccountNumber(String plainAccountNumber) {
        String clean = validateAccountNumber(plainAccountNumber);
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(getKeyBytes(), "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return "GCM1:" + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt bank account number securely.", e);
        }
    }

    public String decryptAccountNumber(String encryptedPayload) {
        if (encryptedPayload == null || !encryptedPayload.startsWith("GCM1:")) {
            throw new IllegalStateException("Unsupported or corrupted encrypted bank account payload.");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedPayload.substring(5));
            if (combined.length <= GCM_IV_LENGTH) {
                throw new IllegalStateException("Corrupted encrypted bank payload.");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(getKeyBytes(), "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt bank account number.", e);
        }
    }
}
