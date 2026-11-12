package com.boxcinema.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class GoogleOidcServiceTest {

    private static final String CLIENT_ID = "box-cinema-test-client.apps.googleusercontent.com";
    private static final String KEY_ID = "test-rsa-key-1";

    private static RSAPublicKey publicKey;
    private static RSAPrivateKey privateKey;
    private static ObjectMapper objectMapper;
    private static GoogleOidcService googleOidcService;

    @BeforeAll
    static void setUpKeys() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        publicKey = (RSAPublicKey) kp.getPublic();
        privateKey = (RSAPrivateKey) kp.getPrivate();
        objectMapper = new ObjectMapper();
        googleOidcService = new GoogleOidcService(objectMapper, CLIENT_ID, uri -> Map.of(KEY_ID, publicKey));
    }

    @Test
    void testValidGoogleIdTokenVerifiesAllClaimsAndSignature() throws Exception {
        String nonce = "nonce-secret-123456";
        String jwt = signJwt(
                Map.of("alg", "RS256", "kid", KEY_ID, "typ", "JWT"),
                baseClaims(nonce),
                privateKey
        );

        GoogleOidcService.VerifiedGoogleIdentity identity = googleOidcService.verifyIdToken(jwt, nonce);
        assertEquals("google_sub_999", identity.googleSub());
        assertEquals("user@gmail.com", identity.email());
        assertEquals("Box Member", identity.fullName());
        assertTrue(identity.emailVerified());
    }

    @Test
    void testRejectsInvalidSignature() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        RSAPrivateKey wrongKey = (RSAPrivateKey) kpg.generateKeyPair().getPrivate();

        String jwt = signJwt(
                Map.of("alg", "RS256", "kid", KEY_ID),
                baseClaims("nonce-1"),
                wrongKey
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> googleOidcService.verifyIdToken(jwt, "nonce-1")
        );
        assertTrue(ex.getMessage().contains("signature"));
    }

    @Test
    void testRejectsAlgNoneAttack() throws Exception {
        String header = b64Url(objectMapper.writeValueAsBytes(Map.of("alg", "none", "kid", KEY_ID)));
        String payload = b64Url(objectMapper.writeValueAsBytes(baseClaims("nonce-1")));
        String forged = header + "." + payload + ".fakesig";

        assertThrows(IllegalArgumentException.class, () -> googleOidcService.verifyIdToken(forged, "nonce-1"));
    }

    @Test
    void testRejectsWrongAudience() throws Exception {
        Map<String, Object> claims = baseClaims("nonce-1");
        claims.put("aud", "other-app.apps.googleusercontent.com");
        String jwt = signJwt(Map.of("alg", "RS256", "kid", KEY_ID), claims, privateKey);

        assertThrows(IllegalArgumentException.class, () -> googleOidcService.verifyIdToken(jwt, "nonce-1"));
    }

    @Test
    void testRejectsUntrustedIssuer() throws Exception {
        Map<String, Object> claims = baseClaims("nonce-1");
        claims.put("iss", "https://evil.example.com");
        String jwt = signJwt(Map.of("alg", "RS256", "kid", KEY_ID), claims, privateKey);

        assertThrows(IllegalArgumentException.class, () -> googleOidcService.verifyIdToken(jwt, "nonce-1"));
    }

    @Test
    void testRejectsExpiredToken() throws Exception {
        Map<String, Object> claims = baseClaims("nonce-1");
        claims.put("exp", Instant.now().getEpochSecond() - 120);
        String jwt = signJwt(Map.of("alg", "RS256", "kid", KEY_ID), claims, privateKey);

        assertThrows(IllegalArgumentException.class, () -> googleOidcService.verifyIdToken(jwt, "nonce-1"));
    }

    @Test
    void testRejectsMismatchedNonce() throws Exception {
        String jwt = signJwt(Map.of("alg", "RS256", "kid", KEY_ID), baseClaims("nonce-expected"), privateKey);
        com.boxcinema.exception.AuthException ex = assertThrows(
                com.boxcinema.exception.AuthException.class,
                () -> googleOidcService.verifyIdToken(jwt, "nonce-attacker")
        );
        assertEquals("OAUTH_NONCE_INVALID", ex.getErrorCode());
    }

    @Test
    void testRejectsUnverifiedGoogleEmail() throws Exception {
        Map<String, Object> claims = baseClaims("nonce-1");
        claims.put("email_verified", false);
        String jwt = signJwt(Map.of("alg", "RS256", "kid", KEY_ID), claims, privateKey);

        assertThrows(IllegalArgumentException.class, () -> googleOidcService.verifyIdToken(jwt, "nonce-1"));
    }

    @Test
    void testRejectsMissingOrFutureIssuedAtClaim() throws Exception {
        Map<String, Object> missingIat = baseClaims("nonce-1");
        missingIat.remove("iat");
        String missingJwt = signJwt(Map.of("alg", "RS256", "kid", KEY_ID), missingIat, privateKey);
        assertThrows(IllegalArgumentException.class, () -> googleOidcService.verifyIdToken(missingJwt, "nonce-1"));

        Map<String, Object> futureIat = baseClaims("nonce-1");
        futureIat.put("iat", Instant.now().getEpochSecond() + 600);
        String futureJwt = signJwt(Map.of("alg", "RS256", "kid", KEY_ID), futureIat, privateKey);
        assertThrows(IllegalArgumentException.class, () -> googleOidcService.verifyIdToken(futureJwt, "nonce-1"));
    }

    @Test
    void testPkceS256ChallengeAndIsolatedRedirectUris() {
        String verifier = googleOidcService.generatePkceCodeVerifier();
        assertNotNull(verifier);
        assertTrue(verifier.length() >= 43 && verifier.length() <= 128);

        String challenge = googleOidcService.computeS256CodeChallenge(verifier);
        assertNotNull(challenge);
        assertFalse(challenge.contains("="));
        assertNotEquals(verifier, challenge);

        ReflectionTestUtils.setField(googleOidcService, "googleClientId", CLIENT_ID);
        ReflectionTestUtils.setField(googleOidcService, "googleClientSecret", "test-client-secret");
        String loginUrl = googleOidcService.buildAuthorizationUrl(
                "st-login", "nn-login", challenge, "http://localhost:8080/login/oauth2/code/google"
        );
        assertTrue(loginUrl.contains("code_challenge=" + challenge));
        assertTrue(loginUrl.contains("code_challenge_method=S256"));
        assertFalse(loginUrl.contains("prompt="), "Normal Google login must not force prompt=consent, login, or select_account");

        String linkUrl = googleOidcService.buildAuthorizationUrl(
                "st-1", "nn-1", challenge, "http://localhost:8080/oauth2/link/google/callback", "select_account"
        );
        assertTrue(linkUrl.contains("code_challenge=" + challenge));
        assertTrue(linkUrl.contains("code_challenge_method=S256"));
        assertTrue(linkUrl.contains("prompt=select_account"));
        assertFalse(linkUrl.contains("prompt=consent"));
        assertFalse(linkUrl.contains("prompt=login"));
        assertEquals("http://localhost:8080/oauth2/link/google/callback",
                googleOidcService.resolveLinkRedirectUri("http://localhost:8080/oauth2/link/google/callback"));
        assertEquals("http://localhost:8080/oauth2/set-password/google/callback",
                googleOidcService.resolveSetPasswordRedirectUri("http://localhost:8080/oauth2/set-password/google/callback"));
    }

    @Test
    void testHostedDomainExtractionAndTrustedGoogleEmailRules() throws Exception {
        Map<String, Object> gmailClaims = baseClaims("nonce-hd");
        String gmailJwt = signJwt(Map.of("alg", "RS256", "kid", KEY_ID), gmailClaims, privateKey);
        GoogleOidcService.VerifiedGoogleIdentity gmailId = googleOidcService.verifyIdToken(gmailJwt, "nonce-hd");
        assertNull(gmailId.hostedDomain());
        assertTrue(googleOidcService.isTrustedGoogleManagedEmail(gmailId.email(), gmailId.hostedDomain()));
        assertTrue(googleOidcService.isTrustedGoogleManagedEmail("user@googlemail.com", null));

        Map<String, Object> workspaceClaims = baseClaims("nonce-ws");
        workspaceClaims.put("email", "employee@boxcinema.edu.vn");
        workspaceClaims.put("hd", "boxcinema.edu.vn");
        String wsJwt = signJwt(Map.of("alg", "RS256", "kid", KEY_ID), workspaceClaims, privateKey);
        GoogleOidcService.VerifiedGoogleIdentity wsId = googleOidcService.verifyIdToken(wsJwt, "nonce-ws");
        assertEquals("boxcinema.edu.vn", wsId.hostedDomain());
        assertTrue(googleOidcService.isTrustedGoogleManagedEmail(wsId.email(), wsId.hostedDomain()));

        // Third-party domain without matching Workspace hd claim MUST NOT be treated as Google-managed
        assertFalse(googleOidcService.isTrustedGoogleManagedEmail("external@outlook.com", null));
        assertFalse(googleOidcService.isTrustedGoogleManagedEmail("external@company.com", "wrongdomain.com"));
    }

    @Test
    void testRejectsTamperedSignatureAndInvalidAudience() throws Exception {
        String validJwt = signJwt(Map.of("alg", "RS256", "kid", KEY_ID), baseClaims("nonce-sig"), privateKey);
        String[] parts = validJwt.split("\\.");
        String tamperedJwt = parts[0] + "." + parts[1] + ".tamperedSignature";
        assertThrows(IllegalArgumentException.class, () -> googleOidcService.verifyIdToken(tamperedJwt, "nonce-sig"));

        Map<String, Object> wrongAudClaims = baseClaims("nonce-aud");
        wrongAudClaims.put("aud", "wrong-client-id.apps.googleusercontent.com");
        String wrongAudJwt = signJwt(Map.of("alg", "RS256", "kid", KEY_ID), wrongAudClaims, privateKey);
        assertThrows(IllegalArgumentException.class, () -> googleOidcService.verifyIdToken(wrongAudJwt, "nonce-aud"));
    }

    private Map<String, Object> baseClaims(String nonce) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("aud", CLIENT_ID);
        claims.put("sub", "google_sub_999");
        claims.put("email", "User@Gmail.com");
        claims.put("email_verified", true);
        claims.put("name", "Box Member");
        claims.put("nonce", nonce);
        claims.put("iat", now - 5);
        claims.put("exp", now + 300);
        return claims;
    }

    private String signJwt(Map<String, Object> header, Map<String, Object> claims, RSAPrivateKey key) throws Exception {
        String headerPart = b64Url(objectMapper.writeValueAsBytes(header));
        String payloadPart = b64Url(objectMapper.writeValueAsBytes(claims));
        String signingInput = headerPart + "." + payloadPart;

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        String sigPart = b64Url(sig.sign());
        return signingInput + "." + sigPart;
    }

    private String b64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}

