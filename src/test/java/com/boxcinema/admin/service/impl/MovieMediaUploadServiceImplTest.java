package com.boxcinema.admin.service.impl;

import com.boxcinema.common.service.CloudinaryMediaClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MovieMediaUploadServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadsPosterToCloudinaryWhenConfigured() throws Exception {
        CloudinaryMediaClient cloudinaryClient = mock(CloudinaryMediaClient.class);
        when(cloudinaryClient.isConfigured()).thenReturn(true);
        when(cloudinaryClient.uploadMovieMediaAsWebp(any(), eq("poster.png"), eq("image/png"), contains("box-cinema/movies/posters/poster_"), eq(false)))
                .thenReturn("https://res.cloudinary.com/demo/image/upload/v1/box-cinema/movies/posters/poster_abc.webp");

        MovieMediaUploadServiceImpl service = new MovieMediaUploadServiceImpl(
                cloudinaryClient, tempDir.toString(), "box-cinema/movies");

        MockMultipartFile file = new MockMultipartFile("file", "poster.png", "image/png", validPngBytes(600, 900));
        String url = service.uploadMovieMedia(file, "poster");

        assertEquals("https://res.cloudinary.com/demo/image/upload/v1/box-cinema/movies/posters/poster_abc.webp", url);
        verify(cloudinaryClient).uploadMovieMediaAsWebp(any(), eq("poster.png"), eq("image/png"), contains("posters/poster_"), eq(false));
    }

    @Test
    void fallsBackToLocalBoundedImageWhenCloudinaryNotConfigured() throws Exception {
        CloudinaryMediaClient cloudinaryClient = mock(CloudinaryMediaClient.class);
        when(cloudinaryClient.isConfigured()).thenReturn(false);

        MovieMediaUploadServiceImpl service = new MovieMediaUploadServiceImpl(
                cloudinaryClient, tempDir.toString(), "box-cinema/movies");

        MockMultipartFile file = new MockMultipartFile("file", "banner.png", "image/png", validPngBytes(2400, 1200));
        String url = service.uploadMovieMedia(file, "banner");

        assertTrue(url.startsWith("/uploads/movies/banner_"));
        assertTrue(url.endsWith(".png"));

        Path savedFile = tempDir.resolve(url.substring("/uploads/movies/".length()));
        assertTrue(Files.exists(savedFile));
        BufferedImage savedImg = ImageIO.read(savedFile.toFile());
        assertNotNull(savedImg);
        assertEquals(1920, savedImg.getWidth());
        assertEquals(960, savedImg.getHeight());
    }

    @Test
    void rejectsSpoofedImageAndUnsupportedMediaType() {
        CloudinaryMediaClient cloudinaryClient = mock(CloudinaryMediaClient.class);
        MovieMediaUploadServiceImpl service = new MovieMediaUploadServiceImpl(
                cloudinaryClient, tempDir.toString(), "box-cinema/movies");

        MockMultipartFile spoofed = new MockMultipartFile(
                "file", "fake.png", "image/png", "not-actually-a-png-header".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> service.uploadMovieMedia(spoofed, "poster"));

        assertThrows(IllegalArgumentException.class, () -> service.uploadMovieMedia(spoofed, "trailer"));
    }

    @Test
    void rejectsOversizedFileAbove5Mb() {
        CloudinaryMediaClient cloudinaryClient = mock(CloudinaryMediaClient.class);
        MovieMediaUploadServiceImpl service = new MovieMediaUploadServiceImpl(
                cloudinaryClient, tempDir.toString(), "box-cinema/movies");

        byte[] oversized = new byte[5 * 1024 * 1024 + 64];
        MockMultipartFile file = new MockMultipartFile("file", "huge.png", "image/png", oversized);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.uploadMovieMedia(file, "banner"));
        assertTrue(ex.getMessage().contains("5 MB"));
    }

    private static byte[] validPngBytes(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
