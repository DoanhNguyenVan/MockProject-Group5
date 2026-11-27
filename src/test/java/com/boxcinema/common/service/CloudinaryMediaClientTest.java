package com.boxcinema.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CloudinaryMediaClientTest {

    @Test
    void signsParametersAlphabeticallyWithSha1() {
        String sig = CloudinaryMediaClient.signParameters(
                Map.of(
                        "timestamp", "1700000000",
                        "public_id", "box-cinema/avatars/user_5",
                        "format", "webp",
                        "overwrite", "true",
                        "invalidate", "true",
                        "transformation", "c_fill,w_256,h_256,g_face,q_auto:good"
                ),
                "my_secret"
        );
        assertEquals(40, sig.length());
        assertTrue(sig.matches("[0-9a-f]{40}"));
    }

    @Test
    void reportsConfiguredStateAccurately() {
        ObjectMapper mapper = new ObjectMapper();
        CloudinaryMediaClient unconfigured = new CloudinaryMediaClient("", "key", "sec", "", mapper);
        assertFalse(unconfigured.isConfigured());

        CloudinaryMediaClient configured = new CloudinaryMediaClient("demo", "key", "sec", "", mapper);
        assertTrue(configured.isConfigured());
    }

    @Test
    void uploadsImageWithWebpFormatAndTransformationToEndpoint() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/demo/image/upload", exchange -> {
            byte[] reqBytes = exchange.getRequestBody().readAllBytes();
            capturedBody.set(new String(reqBytes, StandardCharsets.UTF_8));
            byte[] resp = "{\"secure_url\":\"https://res.cloudinary.com/demo/image/upload/v1/box-cinema/avatars/user_5.webp\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            String baseUrl = "http://localhost:" + server.getAddress().getPort();
            CloudinaryMediaClient client = new CloudinaryMediaClient(
                    "demo", "api_key_1", "secret_1", baseUrl, new ObjectMapper());

            String url = client.uploadAvatarAsWebp(
                    new byte[]{1, 2, 3, 4},
                    "photo.jpg",
                    "image/jpeg",
                    "box-cinema/avatars/user_5"
            );

            assertEquals("https://res.cloudinary.com/demo/image/upload/v1/box-cinema/avatars/user_5.webp", url);
            assertTrue(capturedBody.get().contains("name=\"format\"\r\n\r\nwebp"));
            assertTrue(capturedBody.get().contains("c_fill,w_256,h_256,g_face,q_auto:good"));
            assertTrue(capturedBody.get().contains("name=\"overwrite\"\r\n\r\ntrue"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void uploadsMoviePosterAndBannerWithAspectPreservingWebpTransformation() throws Exception {
        AtomicReference<String> lastBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/demo/image/upload", exchange -> {
            byte[] reqBytes = exchange.getRequestBody().readAllBytes();
            lastBody.set(new String(reqBytes, StandardCharsets.UTF_8));
            byte[] resp = "{\"secure_url\":\"https://res.cloudinary.com/demo/image/upload/v1/box-cinema/movies/media.webp\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            String baseUrl = "http://localhost:" + server.getAddress().getPort();
            CloudinaryMediaClient client = new CloudinaryMediaClient(
                    "demo", "api_key_1", "secret_1", baseUrl, new ObjectMapper());

            String posterUrl = client.uploadMovieMediaAsWebp(
                    new byte[]{1, 2, 3, 4},
                    "poster.png",
                    "image/png",
                    "box-cinema/movies/posters/poster_1",
                    false
            );
            assertTrue(posterUrl.endsWith(".webp"));
            assertTrue(lastBody.get().contains("c_limit,w_800,h_1200,q_auto:good"));
            assertTrue(lastBody.get().contains("name=\"format\"\r\n\r\nwebp"));

            String bannerUrl = client.uploadMovieMediaAsWebp(
                    new byte[]{1, 2, 3, 4},
                    "banner.jpg",
                    "image/jpeg",
                    "box-cinema/movies/banners/banner_1",
                    true
            );
            assertTrue(bannerUrl.endsWith(".webp"));
            assertTrue(lastBody.get().contains("c_limit,w_1920,h_1080,q_auto:good"));
        } finally {
            server.stop(0);
        }
    }
}
