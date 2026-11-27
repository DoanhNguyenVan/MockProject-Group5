package com.boxcinema.common.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Lightweight Cloudinary Signed Upload/Destroy client built on JDK HttpClient.
 * Converts uploaded images into optimized WebP assets on upload.
 */
@Service
public class CloudinaryMediaClient {

    private static final String AVATAR_TRANSFORMATION = "c_fill,w_256,h_256,g_face,q_auto:good";
    private static final String MOVIE_POSTER_TRANSFORMATION = "c_limit,w_800,h_1200,q_auto:good";
    private static final String MOVIE_BANNER_TRANSFORMATION = "c_limit,w_1920,h_1080,q_auto:good";

    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;
    private final String apiBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public CloudinaryMediaClient(
            @Value("${cloudinary.cloud-name:}") String cloudName,
            @Value("${cloudinary.api-key:}") String apiKey,
            @Value("${cloudinary.api-secret:}") String apiSecret,
            @Value("${app.cloudinary.api-base-url:https://api.cloudinary.com/v1_1}") String apiBaseUrl,
            ObjectMapper objectMapper
    ) {
        this.cloudName = cloudName != null ? cloudName.trim() : "";
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.apiSecret = apiSecret != null ? apiSecret.trim() : "";
        this.apiBaseUrl = apiBaseUrl != null ? apiBaseUrl.trim().replaceAll("/+$", "") : "https://api.cloudinary.com/v1_1";
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isConfigured() {
        return !cloudName.isBlank() && !apiKey.isBlank() && !apiSecret.isBlank();
    }

    /**
     * Uploads raw avatar bytes to Cloudinary with incoming transformation to 256x256 WebP.
     */
    public String uploadAvatarAsWebp(byte[] imageBytes, String originalFilename, String contentType, String publicId) {
        return uploadImageAsWebp(
                imageBytes,
                originalFilename,
                contentType,
                publicId,
                AVATAR_TRANSFORMATION,
                "avatar"
        );
    }

    /**
     * Uploads movie poster or banner bytes to Cloudinary as aspect-preserving WebP.
     */
    public String uploadMovieMediaAsWebp(
            byte[] imageBytes,
            String originalFilename,
            String contentType,
            String publicId,
            boolean isBanner
    ) {
        String transformation = isBanner ? MOVIE_BANNER_TRANSFORMATION : MOVIE_POSTER_TRANSFORMATION;
        return uploadImageAsWebp(
                imageBytes,
                originalFilename,
                contentType,
                publicId,
                transformation,
                isBanner ? "banner" : "poster"
        );
    }

    private String uploadImageAsWebp(
            byte[] imageBytes,
            String originalFilename,
            String contentType,
            String publicId,
            String transformation,
            String assetLabel
    ) {
        if (!isConfigured()) {
            throw new IllegalStateException("Cloudinary is not configured.");
        }
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        TreeMap<String, String> signedParams = new TreeMap<>();
        signedParams.put("format", "webp");
        signedParams.put("invalidate", "true");
        signedParams.put("overwrite", "true");
        signedParams.put("public_id", publicId);
        signedParams.put("timestamp", timestamp);
        signedParams.put("transformation", transformation);

        String signature = signParameters(signedParams, apiSecret);
        String boundary = "----BoxCinemaCloudinary" + UUID.randomUUID().toString().replace("-", "");

        byte[] multipartBody = buildMultipartBody(
                boundary,
                Map.of(
                        "api_key", apiKey,
                        "signature", signature,
                        "format", "webp",
                        "invalidate", "true",
                        "overwrite", "true",
                        "public_id", publicId,
                        "timestamp", timestamp,
                        "transformation", transformation
                ),
                imageBytes,
                originalFilename != null && !originalFilename.isBlank() ? originalFilename : assetLabel + ".png",
                contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream"
        );

        String endpoint = apiBaseUrl + "/" + cloudName + "/image/upload";
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errMsg = root.path("error").path("message").asText("Cloudinary upload failed.");
                throw new IllegalStateException("Unable to upload " + assetLabel + " to Cloudinary: " + errMsg);
            }
            String secureUrl = root.path("secure_url").asText("");
            if (secureUrl.isBlank()) {
                throw new IllegalStateException("Cloudinary did not return an image URL.");
            }
            return secureUrl;
        } catch (IOException ex) {
            throw new IllegalStateException("Error communicating with Cloudinary.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cloudinary upload was interrupted.", ex);
        }
    }

    /**
     * Deletes an avatar from Cloudinary by its publicId.
     */
    public void destroyAvatar(String publicId) {
        if (!isConfigured() || publicId == null || publicId.isBlank()) {
            return;
        }
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        TreeMap<String, String> signedParams = new TreeMap<>();
        signedParams.put("invalidate", "true");
        signedParams.put("public_id", publicId);
        signedParams.put("timestamp", timestamp);

        String signature = signParameters(signedParams, apiSecret);
        String form = "public_id=" + urlEncode(publicId)
                + "&invalidate=true"
                + "&timestamp=" + urlEncode(timestamp)
                + "&api_key=" + urlEncode(apiKey)
                + "&signature=" + urlEncode(signature);

        String endpoint = apiBaseUrl + "/" + cloudName + "/image/destroy";
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        try {
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Non-fatal cleanup error
        }
    }

    public static String signParameters(Map<String, String> params, String secret) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        sb.append(secret);
        return sha1Hex(sb.toString());
    }

    private static String sha1Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-1 algorithm unavailable.", ex);
        }
    }

    private static byte[] buildMultipartBody(
            String boundary,
            Map<String, String> fields,
            byte[] fileBytes,
            String filename,
            String contentType
    ) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write((entry.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            String safeFilename = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + safeFilename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(fileBytes);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to construct multipart upload payload.", ex);
        }
    }

    private static String urlEncode(String val) {
        return URLEncoder.encode(val, StandardCharsets.UTF_8);
    }
}
