package com.boxcinema.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Cryptographic Google OAuth2 / OpenID Connect ID Token verification service.
 * Validates RS256 signatures against Google JWKS plus iss, aud, exp, nonce, sub, and email_verified=true.
 */
@Service
public class GoogleOidcService {

    private static final String GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String DEFAULT_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final long JWKS_CACHE_TTL_SECONDS = 3600L;
    private static final long CLOCK_SKEW_SECONDS = 30L;

    public record VerifiedGoogleIdentity(
            String googleSub,
            String email,
            String fullName,
            boolean emailVerified,
            String hostedDomain
    ) {
        public VerifiedGoogleIdentity(
                String googleSub,
                String email,
                String fullName,
                boolean emailVerified) {
            this(googleSub, email, fullName, emailVerified, null);
        }
    }

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, RSAPublicKey> jwkCache = new ConcurrentHashMap<>();
    private final Function<String, Map<String, RSAPublicKey>> jwksLoaderOverride;
    private volatile long jwksCacheExpiresAtEpochSec = 0L;

    @Value("${app.oauth2.google.client-id:}")
    private String googleClientId;

    @Value("${app.oauth2.google.client-secret:}")
    private String googleClientSecret;

    @Value("${app.oauth2.google.redirect-uri:}")
    private String googleRedirectUri;

    @Value("${app.oauth2.google.link-redirect-uri:}")
    private String googleLinkRedirectUri;

    @Value("${app.oauth2.google.set-password-redirect-uri:}")
    private String googleSetPasswordRedirectUri;

    @Value("${app.oauth2.google.token-uri:" + DEFAULT_TOKEN_URI + "}")
    private String tokenUri = DEFAULT_TOKEN_URI;

    @Value("${app.oauth2.google.jwks-uri:" + DEFAULT_JWKS_URI + "}")
    private String jwksUri = DEFAULT_JWKS_URI;

    @Autowired
    public GoogleOidcService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.jwksLoaderOverride = null;
    }

    /**
     * Package-accessible constructor for unit testing OIDC verification with deterministic keys.
     */
    public GoogleOidcService(
            ObjectMapper objectMapper,
            String googleClientId,
            Function<String, Map<String, RSAPublicKey>> jwksLoaderOverride) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.googleClientId = googleClientId;
        this.jwksLoaderOverride = jwksLoaderOverride;
    }

    public boolean isConfigured() {
        return isConfiguredValue(googleClientId) && isConfiguredValue(googleClientSecret);
    }

    public String getGoogleClientId() {
        return googleClientId != null ? googleClientId.trim() : "";
    }

    public String resolveRedirectUri(String fallbackRedirectUri) {
        return isConfiguredValue(googleRedirectUri) ? googleRedirectUri.trim() : fallbackRedirectUri;
    }

    public String resolveLinkRedirectUri(String fallbackRedirectUri) {
        return isConfiguredValue(googleLinkRedirectUri) ? googleLinkRedirectUri.trim() : fallbackRedirectUri;
    }

    public String resolveSetPasswordRedirectUri(String fallbackRedirectUri) {
        return isConfiguredValue(googleSetPasswordRedirectUri) ? googleSetPasswordRedirectUri.trim() : fallbackRedirectUri;
    }

    public String generateCryptographicToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String generatePkceCodeVerifier() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String computeS256CodeChallenge(String codeVerifier) {
        if (codeVerifier == null || codeVerifier.isBlank()) {
            throw new IllegalArgumentException("PKCE code verifier is required.");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute PKCE S256 code challenge.", ex);
        }
    }

    public String buildAuthorizationUrl(String state, String nonce, String resolvedRedirectUri) {
        return buildAuthorizationUrl(state, nonce, null, resolvedRedirectUri, null);
    }

    public String buildAuthorizationUrl(String state, String nonce, String codeChallenge, String resolvedRedirectUri) {
        return buildAuthorizationUrl(state, nonce, codeChallenge, resolvedRedirectUri, null);
    }

    public String buildAuthorizationUrl(
            String state,
            String nonce,
            String codeChallenge,
            String resolvedRedirectUri,
            String prompt) {
        if (!isConfigured()) {
            throw new IllegalStateException("Google OAuth2 client credentials are not configured.");
        }
        StringBuilder url = new StringBuilder(GOOGLE_AUTH_ENDPOINT)
                .append("?client_id=").append(urlEncode(googleClientId.trim()))
                .append("&redirect_uri=").append(urlEncode(resolvedRedirectUri))
                .append("&response_type=code")
                .append("&scope=").append(urlEncode("openid email profile"))
                .append("&state=").append(urlEncode(state))
                .append("&nonce=").append(urlEncode(nonce));
        if (prompt != null && !prompt.isBlank()) {
            url.append("&prompt=").append(urlEncode(prompt.trim()));
        }
        if (codeChallenge != null && !codeChallenge.isBlank()) {
            url.append("&code_challenge=").append(urlEncode(codeChallenge.trim()))
               .append("&code_challenge_method=S256");
        }
        return url.toString();
    }

    public VerifiedGoogleIdentity exchangeCodeAndVerifyIdToken(String code, String resolvedRedirectUri, String expectedNonce) {
        return exchangeCodeAndVerifyIdToken(code, resolvedRedirectUri, expectedNonce, null);
    }

    public VerifiedGoogleIdentity exchangeCodeAndVerifyIdToken(
            String code,
            String resolvedRedirectUri,
            String expectedNonce,
            String codeVerifier
    ) {
        if (!isConfigured()) {
            throw new IllegalStateException("Google OAuth2 is not configured.");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Google authorization code is missing.");
        }
        try {
            StringBuilder formBuilder = new StringBuilder()
                    .append("code=").append(urlEncode(code.trim()))
                    .append("&client_id=").append(urlEncode(googleClientId.trim()))
                    .append("&client_secret=").append(urlEncode(googleClientSecret.trim()))
                    .append("&redirect_uri=").append(urlEncode(resolvedRedirectUri))
                    .append("&grant_type=authorization_code");
            if (codeVerifier != null && !codeVerifier.isBlank()) {
                formBuilder.append("&code_verifier=").append(urlEncode(codeVerifier.trim()));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUri))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(12))
                    .POST(HttpRequest.BodyPublishers.ofString(formBuilder.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("Failed to exchange authorization code with Google.");
            }

            JsonNode tokenJson = objectMapper.readTree(response.body());
            String idToken = tokenJson.path("id_token").asText("");
            if (idToken.isBlank()) {
                throw new IllegalArgumentException("Google token response did not include an OpenID id_token.");
            }
            return verifyIdToken(idToken, expectedNonce);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Google authentication could not be completed. Please try again.");
        }
    }

    public VerifiedGoogleIdentity verifyIdToken(String idToken, String expectedNonce) {
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("Google ID token is missing.");
        }
        if (expectedNonce == null || expectedNonce.isBlank()) {
            throw new IllegalArgumentException("OAuth nonce verification failed.");
        }
        if (!isConfiguredValue(googleClientId)) {
            throw new IllegalStateException("Google OAuth client ID is not configured.");
        }

        String[] parts = idToken.split("\\.", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException("Malformed Google ID token.");
        }

        try {
            JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            String alg = header.path("alg").asText("");
            String kid = header.path("kid").asText("");
            if (!"RS256".equals(alg) || kid.isBlank()) {
                throw new IllegalArgumentException("Unsupported Google ID token algorithm or missing key ID.");
            }

            RSAPublicKey publicKey = resolvePublicKey(kid);
            if (publicKey == null) {
                throw new IllegalArgumentException("Unable to resolve Google public signing key.");
            }

            byte[] signedContent = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            byte[] signatureBytes = Base64.getUrlDecoder().decode(parts[2]);

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(signedContent);
            if (!sig.verify(signatureBytes)) {
                throw new IllegalArgumentException("Invalid Google ID token cryptographic signature.");
            }

            JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            validateClaims(claims, expectedNonce);

            String sub = claims.path("sub").asText("").trim();
            String email = claims.path("email").asText("").trim().toLowerCase(Locale.ROOT);
            boolean emailVerified = claims.path("email_verified").isBoolean() && claims.path("email_verified").asBoolean();
            String rawName = claims.path("name").asText("").trim();
            String fullName = rawName.isBlank() ? email.substring(0, email.indexOf('@')) : rawName;
            String rawHd = claims.path("hd").asText("").trim().toLowerCase(Locale.ROOT);
            String hostedDomain = rawHd.isBlank() ? null : rawHd;

            return new VerifiedGoogleIdentity(sub, email, fullName, emailVerified, hostedDomain);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Google ID token validation failed.");
        }
    }

    /**
     * Checks whether a Google email identity is eligible for trusted email-based auto-linking:
     * standard Google-managed consumer domains (gmail.com / googlemail.com) or Google Workspace
     * accounts whose verified {@code hd} claim strictly matches the email domain.
     */
    public boolean isTrustedGoogleManagedEmail(String email, String hostedDomain) {
        return isTrustedGoogleEmailIdentity(email, hostedDomain);
    }

    public static boolean isTrustedGoogleEmailIdentity(String email, String hostedDomain) {
        if (email == null || email.isBlank()) {
            return false;
        }
        int atIdx = email.lastIndexOf('@');
        if (atIdx < 0 || atIdx == email.length() - 1) {
            return false;
        }
        String domain = email.substring(atIdx + 1).trim().toLowerCase(Locale.ROOT);
        if ("gmail.com".equals(domain) || "googlemail.com".equals(domain)) {
            return true;
        }
        return hostedDomain != null && !hostedDomain.isBlank()
                && domain.equals(hostedDomain.trim().toLowerCase(Locale.ROOT));
    }

    private void validateClaims(JsonNode claims, String expectedNonce) {
        String iss = claims.path("iss").asText("");
        if (!"https://accounts.google.com".equals(iss) && !"accounts.google.com".equals(iss)) {
            throw new IllegalArgumentException("Untrusted Google token issuer.");
        }

        String configuredAud = googleClientId.trim();
        JsonNode audNode = claims.path("aud");
        boolean audMatches = false;
        if (audNode.isTextual()) {
            audMatches = MessageDigest.isEqual(
                    configuredAud.getBytes(StandardCharsets.UTF_8),
                    audNode.asText("").getBytes(StandardCharsets.UTF_8)
            );
        } else if (audNode.isArray()) {
            for (JsonNode entry : audNode) {
                if (configuredAud.equals(entry.asText(""))) {
                    audMatches = true;
                    break;
                }
            }
            if (audMatches && claims.hasNonNull("azp") && !configuredAud.equals(claims.path("azp").asText(""))) {
                audMatches = false;
            }
        }
        if (!audMatches) {
            throw new IllegalArgumentException("Google token audience does not match BOX Cinema.");
        }

        long nowSec = Instant.now().getEpochSecond();
        if (!claims.path("exp").isNumber()) {
            throw new IllegalArgumentException("Google token missing expiration claim.");
        }
        long expSec = claims.path("exp").asLong();
        if (expSec <= nowSec - CLOCK_SKEW_SECONDS) {
            throw new IllegalArgumentException("Google authentication token has expired.");
        }

        if (!claims.path("iat").isNumber()) {
            throw new IllegalArgumentException("Google token missing issued-at claim.");
        }
        long iatSec = claims.path("iat").asLong();
        if (iatSec > nowSec + 300L || iatSec > expSec) {
            throw new IllegalArgumentException("Google token issued-at time is invalid.");
        }

        String tokenNonce = claims.path("nonce").asText("");
        if (tokenNonce.isBlank() || !MessageDigest.isEqual(
                expectedNonce.getBytes(StandardCharsets.UTF_8),
                tokenNonce.getBytes(StandardCharsets.UTF_8))) {
            throw com.boxcinema.exception.AuthException.badRequest(
                    "OAUTH_NONCE_INVALID",
                    "Google token nonce validation failed."
            );
        }

        String sub = claims.path("sub").asText("");
        String email = claims.path("email").asText("");
        if (sub.isBlank() || email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("Google token did not provide valid user identity claims.");
        }

        JsonNode emailVerifiedNode = claims.path("email_verified");
        if (!emailVerifiedNode.isBoolean() || !emailVerifiedNode.asBoolean()) {
            throw new IllegalArgumentException("Your Google account email must be verified with Google.");
        }
    }

    private RSAPublicKey resolvePublicKey(String kid) throws Exception {
        long now = Instant.now().getEpochSecond();
        RSAPublicKey cached = jwkCache.get(kid);
        if (cached != null && now < jwksCacheExpiresAtEpochSec) {
            return cached;
        }
        refreshJwksCache();
        return jwkCache.get(kid);
    }

    private synchronized void refreshJwksCache() throws Exception {
        long now = Instant.now().getEpochSecond();
        if (now < jwksCacheExpiresAtEpochSec && !jwkCache.isEmpty()) {
            // Re-check inside lock, but allow refresh if force called by unknown kid handled below
        }

        Map<String, RSAPublicKey> loaded = new ConcurrentHashMap<>();
        if (jwksLoaderOverride != null) {
            loaded.putAll(jwksLoaderOverride.apply(jwksUri));
        } else {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(jwksUri))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Failed to fetch Google JWKS keys.");
            }
            JsonNode jwksNode = objectMapper.readTree(response.body());
            JsonNode keys = jwksNode.path("keys");
            if (keys.isArray()) {
                KeyFactory rsaFactory = KeyFactory.getInstance("RSA");
                for (JsonNode jwk : keys) {
                    if (!"RSA".equals(jwk.path("kty").asText(""))) {
                        continue;
                    }
                    String kid = jwk.path("kid").asText("");
                    String n = jwk.path("n").asText("");
                    String e = jwk.path("e").asText("");
                    if (kid.isBlank() || n.isBlank() || e.isBlank()) {
                        continue;
                    }
                    BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(n));
                    BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(e));
                    RSAPublicKey publicKey = (RSAPublicKey) rsaFactory.generatePublic(new RSAPublicKeySpec(modulus, exponent));
                    loaded.put(kid, publicKey);
                }
            }
        }

        if (!loaded.isEmpty()) {
            jwkCache.clear();
            jwkCache.putAll(loaded);
            jwksCacheExpiresAtEpochSec = Instant.now().getEpochSecond() + JWKS_CACHE_TTL_SECONDS;
        }
    }

    private boolean isConfiguredValue(String value) {
        return value != null && !value.isBlank() && !value.trim().startsWith("YOUR_");
    }

    private String urlEncode(String val) {
        return URLEncoder.encode(val, StandardCharsets.UTF_8);
    }
}

