package com.boxcinema.customer.service.impl;

import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.UserRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileAvatarServiceImplTest {

    @TempDir
    Path tempDir;

    private byte[] createSamplePngBytes() throws Exception {
        BufferedImage img = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void uploadsToCloudinaryAsWebpWhenConfigured() throws Exception {
        UserRepository userRepo = mock(UserRepository.class);
        CloudinaryMediaClient cld = mock(CloudinaryMediaClient.class);

        User user = new User();
        user.setUserId(10L);
        when(userRepo.findById(10L)).thenReturn(Optional.of(user));
        when(cld.isConfigured()).thenReturn(true);
        when(cld.uploadAvatarAsWebp(any(), eq("me.png"), eq("image/png"), eq("box-cinema/avatars/user_10")))
                .thenReturn("https://res.cloudinary.com/demo/image/upload/v1/box-cinema/avatars/user_10.webp");

        ProfileAvatarServiceImpl service = new ProfileAvatarServiceImpl(
                userRepo, cld, tempDir.toString(), "box-cinema/avatars");
        MockMultipartFile file = new MockMultipartFile("avatarFile", "me.png", "image/png", createSamplePngBytes());

        String url = service.uploadAvatar(10L, file);

        assertEquals("https://res.cloudinary.com/demo/image/upload/v1/box-cinema/avatars/user_10.webp", url);
        assertEquals(url, user.getAvatarUrl());
        verify(userRepo).saveAndFlush(user);
    }

    @Test
    void fallsBackToLocalResizedStorageAndCleansOldFileWhenCloudinaryNotConfigured() throws Exception {
        UserRepository userRepo = mock(UserRepository.class);
        CloudinaryMediaClient cld = mock(CloudinaryMediaClient.class);
        when(cld.isConfigured()).thenReturn(false);

        User user = new User();
        user.setUserId(11L);
        when(userRepo.findById(11L)).thenReturn(Optional.of(user));

        ProfileAvatarServiceImpl service = new ProfileAvatarServiceImpl(
                userRepo, cld, tempDir.toString(), "box-cinema/avatars");

        MockMultipartFile file1 = new MockMultipartFile("avatarFile", "a.png", "image/png", createSamplePngBytes());
        String firstUrl = service.uploadAvatar(11L, file1);
        assertTrue(firstUrl.startsWith("/uploads/avatars/avatar_11_"));
        Path firstFile = tempDir.resolve(firstUrl.replace("/uploads/avatars/", ""));
        assertTrue(Files.exists(firstFile));

        MockMultipartFile file2 = new MockMultipartFile("avatarFile", "b.png", "image/png", createSamplePngBytes());
        String secondUrl = service.uploadAvatar(11L, file2);
        assertNotEquals(firstUrl, secondUrl);
        assertFalse(Files.exists(firstFile));

        service.removeAvatar(11L);
        assertNull(user.getAvatarUrl());
        assertFalse(Files.exists(tempDir.resolve(secondUrl.replace("/uploads/avatars/", ""))));
    }

    @Test
    void rejectsSvgAndSpoofedNonImageFiles() {
        UserRepository userRepo = mock(UserRepository.class);
        CloudinaryMediaClient cld = mock(CloudinaryMediaClient.class);
        ProfileAvatarServiceImpl service = new ProfileAvatarServiceImpl(
                userRepo, cld, tempDir.toString(), "box-cinema/avatars");

        MockMultipartFile svg = new MockMultipartFile(
                "avatarFile", "bad.svg", "image/svg+xml", "<svg></svg>".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> service.uploadAvatar(1L, svg));

        MockMultipartFile fakePng = new MockMultipartFile(
                "avatarFile", "fake.png", "image/png", "not-actually-an-image-bytes".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> service.uploadAvatar(1L, fakePng));
    }
}
