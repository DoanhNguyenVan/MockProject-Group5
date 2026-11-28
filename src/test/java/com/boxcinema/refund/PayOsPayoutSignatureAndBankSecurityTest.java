package com.boxcinema.refund;

import com.boxcinema.common.service.BankDataEncryptionService;
import com.boxcinema.common.util.PayOsPayoutSignatureUtil;
import com.boxcinema.customer.service.impl.PayOsServiceImpl;
import com.boxcinema.refund.service.PayOsPayoutService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PayOsPayoutSignatureAndBankSecurityTest {

    private static final String TEST_CHECKSUM_KEY = "test_checksum_key";

    @Test
    void payOsOfficialVector1_SinglePayoutWithCategory() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("referenceId", "referenceId");
        payload.put("amount", 2000);
        payload.put("description", "payout");
        payload.put("toBin", "970422");
        payload.put("toAccountNumber", "0123456789");
        payload.put("category", List.of("salary", "hoa"));

        String sig = PayOsPayoutSignatureUtil.signPayload(payload, TEST_CHECKSUM_KEY);
        assertEquals("1bb7686b766bb5d164227a7d4d2e4d4ad241e4a7a91f61c75674c0af9cf90fca", sig);
    }

    @Test
    void payOsOfficialVector2_SinglePayoutWithEmptyCategory() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("referenceId", "referenceId");
        payload.put("amount", 2000);
        payload.put("description", "payout");
        payload.put("toBin", "970422");
        payload.put("toAccountNumber", "0123456789");
        payload.put("category", List.of());

        String sig = PayOsPayoutSignatureUtil.signPayload(payload, TEST_CHECKSUM_KEY);
        assertEquals("40af04e4b404ef9ad67d034c2d8b4c158c9aad965afe87ab37d456526a949abb", sig);
    }

    @Test
    void payOsOfficialVector3_PayoutResponseWithNestedTransactionsAndNullCategory() {
        Map<String, Object> txn = new LinkedHashMap<>();
        txn.put("id", "batchTxnId");
        txn.put("referenceId", "referenceId");
        txn.put("amount", 2000);
        txn.put("description", "batch payout");
        txn.put("toBin", "970422");
        txn.put("toAccountNumber", "0123456789");
        txn.put("toAccountName", "NGUYEN VAN A");
        txn.put("reference", "FT-REFERENCE");
        txn.put("transactionDatetime", "2025-12-12T09:00:00+07:00");
        txn.put("errorMessage", null);
        txn.put("errorCode", null);
        txn.put("state", "SUCCEEDED");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", "batchId");
        data.put("referenceId", "referenceId");
        data.put("transactions", List.of(txn));
        data.put("category", null);
        data.put("approvalState", "COMPLETED");
        data.put("createdAt", "2025-12-12T09:00:00+07:00");

        String sig = PayOsPayoutSignatureUtil.signPayload(data, TEST_CHECKSUM_KEY);
        assertEquals("2d7196c3521883f9aeb858393e11fff0542c0fbed14c1d8b8710e2652a83e2e1", sig);
        assertTrue(PayOsPayoutSignatureUtil.verifySignature(data, sig, TEST_CHECKSUM_KEY));
        assertFalse(PayOsPayoutSignatureUtil.verifySignature(data, "0".repeat(64), TEST_CHECKSUM_KEY));
    }

    @Test
    void bankEncryptionService_AesGcmRandomIvAndMaskingAndLiveFailClosed() {
        BankDataEncryptionService service = new BankDataEncryptionService();
        ReflectionTestUtils.setField(service, "payoutMode", "MOCK");
        ReflectionTestUtils.setField(service, "configuredEncryptionKey", "TestEncryptionKey2026!");
        ReflectionTestUtils.setField(service, "devFallbackSeed", "seed");
        service.init();

        assertEquals("MB Bank (MBBank)", service.validateAndResolveBankName("970422"));
        assertThrows(IllegalArgumentException.class, () -> service.validateAndResolveBankName("999999"));
        assertThrows(IllegalArgumentException.class, () -> service.validateAccountNumber("123"));

        String enc1 = service.encryptAccountNumber("0123456789");
        String enc2 = service.encryptAccountNumber("0123456789");
        assertTrue(enc1.startsWith("GCM1:"));
        assertNotEquals(enc1, enc2); // Random 12-byte IV
        assertEquals("0123456789", service.decryptAccountNumber(enc1));
        assertEquals("******6789", service.maskAccountNumber("0123456789"));

        // Fail-closed in LIVE mode without BANK_DATA_ENC_KEY
        BankDataEncryptionService liveSvc = new BankDataEncryptionService();
        ReflectionTestUtils.setField(liveSvc, "payoutMode", "LIVE");
        ReflectionTestUtils.setField(liveSvc, "configuredEncryptionKey", "");
        assertThrows(IllegalStateException.class, liveSvc::init);

        // Fail-closed in LIVE mode when BANK_DATA_ENC_KEY is weak (< 32 chars and < 32 Base64 bytes)
        BankDataEncryptionService weakLiveSvc = new BankDataEncryptionService();
        ReflectionTestUtils.setField(weakLiveSvc, "payoutMode", "LIVE");
        ReflectionTestUtils.setField(weakLiveSvc, "configuredEncryptionKey", "too-short-key-12345");
        assertThrows(IllegalStateException.class, weakLiveSvc::init);

        BankDataEncryptionService weakB64LiveSvc = new BankDataEncryptionService();
        ReflectionTestUtils.setField(weakB64LiveSvc, "payoutMode", "LIVE");
        ReflectionTestUtils.setField(weakB64LiveSvc, "configuredEncryptionKey", Base64.getEncoder().encodeToString("16-bytes-only!!".getBytes()));
        assertThrows(IllegalStateException.class, weakB64LiveSvc::init);

        // Strong 32+ char or 32-byte Base64 key succeeds in LIVE mode
        BankDataEncryptionService strongLiveSvc = new BankDataEncryptionService();
        ReflectionTestUtils.setField(strongLiveSvc, "payoutMode", "LIVE");
        ReflectionTestUtils.setField(strongLiveSvc, "configuredEncryptionKey", Base64.getEncoder().encodeToString(new byte[32]));
        assertDoesNotThrow(strongLiveSvc::init);
    }

    @Test
    void payOsPayoutService_LiveModeFailsFastWhenKeysMissingOrSharedWithPayment() {
        PayOsPayoutService svc = new PayOsPayoutService(null, null, null, null, null, new org.springframework.jdbc.support.JdbcTransactionManager());
        ReflectionTestUtils.setField(svc, "mode", "LIVE");
        ReflectionTestUtils.setField(svc, "clientId", "");
        ReflectionTestUtils.setField(svc, "apiKey", "");
        ReflectionTestUtils.setField(svc, "checksumKey", "");
        assertThrows(IllegalStateException.class, svc::validateConfiguration);

        // Trimmed / case-insensitive clientId collision rejected
        ReflectionTestUtils.setField(svc, "clientId", "  PAY-CID  ");
        ReflectionTestUtils.setField(svc, "apiKey", "payout-api");
        ReflectionTestUtils.setField(svc, "checksumKey", "payout-chk");
        ReflectionTestUtils.setField(svc, "paymentClientId", "pay-cid");
        ReflectionTestUtils.setField(svc, "paymentApiKey", "other-api");
        ReflectionTestUtils.setField(svc, "paymentChecksumKey", "other-chk");
        assertThrows(IllegalStateException.class, svc::validateConfiguration);

        // Trimmed apiKey collision rejected
        ReflectionTestUtils.setField(svc, "clientId", "payout-cid");
        ReflectionTestUtils.setField(svc, "apiKey", "  pay-api  ");
        ReflectionTestUtils.setField(svc, "checksumKey", "payout-chk");
        ReflectionTestUtils.setField(svc, "paymentClientId", "pay-cid");
        ReflectionTestUtils.setField(svc, "paymentApiKey", "pay-api");
        ReflectionTestUtils.setField(svc, "paymentChecksumKey", "other-chk");
        assertThrows(IllegalStateException.class, svc::validateConfiguration);

        // Trimmed checksumKey collision rejected
        ReflectionTestUtils.setField(svc, "clientId", "payout-cid");
        ReflectionTestUtils.setField(svc, "apiKey", "payout-api");
        ReflectionTestUtils.setField(svc, "checksumKey", "  pay-chk ");
        ReflectionTestUtils.setField(svc, "paymentClientId", "pay-cid");
        ReflectionTestUtils.setField(svc, "paymentApiKey", "other-api");
        ReflectionTestUtils.setField(svc, "paymentChecksumKey", "pay-chk");
        assertThrows(IllegalStateException.class, svc::validateConfiguration);

        // Distinct trimmed creds succeed
        ReflectionTestUtils.setField(svc, "checksumKey", "payout-chk-distinct");
        assertDoesNotThrow(svc::validateConfiguration);
    }

    @Test
    void payOsCollectionService_LiveModeFailsFastWhenMissingDefaultOrSharedWithPayout() {
        PayOsServiceImpl colSvc = new PayOsServiceImpl(null, null, null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(colSvc, "collectionMode", "LIVE");

        // Missing credentials
        ReflectionTestUtils.setField(colSvc, "clientId", " ");
        ReflectionTestUtils.setField(colSvc, "apiKey", "col-api");
        ReflectionTestUtils.setField(colSvc, "checksumKey", "col-chk");
        assertThrows(IllegalStateException.class, colSvc::validateCollectionConfiguration);

        // Default placeholder credentials rejected in LIVE mode
        ReflectionTestUtils.setField(colSvc, "clientId", "DUMMY-CLIENT-ID");
        ReflectionTestUtils.setField(colSvc, "apiKey", "col-api");
        ReflectionTestUtils.setField(colSvc, "checksumKey", "col-chk");
        assertThrows(IllegalStateException.class, colSvc::validateCollectionConfiguration);

        ReflectionTestUtils.setField(colSvc, "clientId", "col-cid");
        ReflectionTestUtils.setField(colSvc, "apiKey", "dummy-api-key");
        assertThrows(IllegalStateException.class, colSvc::validateCollectionConfiguration);

        ReflectionTestUtils.setField(colSvc, "apiKey", "col-api");
        ReflectionTestUtils.setField(colSvc, "checksumKey", "BOX_CINEMA_DEFAULT_SECURE_PAYOS_KEY_2026_@!#");
        assertThrows(IllegalStateException.class, colSvc::validateCollectionConfiguration);

        // Shared with Payout (case-insensitive clientId, trimmed apiKey/checksumKey) rejected
        ReflectionTestUtils.setField(colSvc, "clientId", "  COL-CID ");
        ReflectionTestUtils.setField(colSvc, "apiKey", "col-api");
        ReflectionTestUtils.setField(colSvc, "checksumKey", "col-chk");
        ReflectionTestUtils.setField(colSvc, "payoutClientId", "col-cid");
        ReflectionTestUtils.setField(colSvc, "payoutApiKey", "po-api");
        ReflectionTestUtils.setField(colSvc, "payoutChecksumKey", "po-chk");
        assertThrows(IllegalStateException.class, colSvc::validateCollectionConfiguration);

        ReflectionTestUtils.setField(colSvc, "payoutClientId", "po-cid");
        ReflectionTestUtils.setField(colSvc, "payoutApiKey", " col-api ");
        assertThrows(IllegalStateException.class, colSvc::validateCollectionConfiguration);

        ReflectionTestUtils.setField(colSvc, "payoutApiKey", "po-api");
        ReflectionTestUtils.setField(colSvc, "payoutChecksumKey", " col-chk ");
        assertThrows(IllegalStateException.class, colSvc::validateCollectionConfiguration);

        // Non-HTTPS returnUrl/cancelUrl rejected in LIVE mode, HTTPS succeeds
        ReflectionTestUtils.setField(colSvc, "payoutChecksumKey", "po-chk");
        assertThrows(IllegalStateException.class, colSvc::validateCollectionConfiguration);

        ReflectionTestUtils.setField(colSvc, "returnUrl", "https://cinema.example.com/booking/buy-tickets");
        ReflectionTestUtils.setField(colSvc, "cancelUrl", "https://cinema.example.com/booking/buy-tickets");
        assertDoesNotThrow(colSvc::validateCollectionConfiguration);
    }
}
