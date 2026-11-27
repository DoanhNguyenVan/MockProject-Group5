package com.boxcinema.customer.service.impl;

import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.UserRepository;
import com.boxcinema.common.service.CloudinaryMediaClient;
import com.boxcinema.common.util.ImageUploadSupport;
import com.boxcinema.customer.service.ProfileAvatarService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
@Transactional
public class ProfileAvatarServiceImpl implements ProfileAvatarService {

    private static final long MAX_AVATAR_BYTES = 2L * 1024L * 1024L;
    private static final int LOCAL_TARGET_SIZE = 256;
    private static final String LOCAL_URL_PREFIX = "/uploads/avatars/";

    private final UserRepository userRepository;
    private final CloudinaryMediaClient cloudinaryMediaClient;
    private final String avatarStorageDir;
    private final String cloudinaryFolder;

    public ProfileAvatarServiceImpl(
            UserRepository userRepository,
            CloudinaryMediaClient cloudinaryMediaClient,
            @Value("${app.avatar.storage-dir:./uploads/avatars}") String avatarStorageDir,
            @Value("${app.avatar.cloudinary-folder:box-cinema/avatars}") String cloudinaryFolder
    ) {
        this.userRepository = userRepository;
        this.cloudinaryMediaClient = cloudinaryMediaClient;
        this.avatarStorageDir = avatarStorageDir;
        this.cloudinaryFolder = cloudinaryFolder != null ? cloudinaryFolder.replaceAll("^/+|/+$", "") : "box-cinema/avatars";
    }

    @Override
    public String uploadAvatar(Long userId, MultipartFile file) {
        if (userId == null) {
            throw new IllegalArgumentException("Authentication is required.");
        }
        String detectedFormat = ImageUploadSupport.validateAndDetectFormat(
                file,
                MAX_AVATAR_BYTES,
                "Profile photo must be 2 MB or smaller."
        );

        String contentType = file.getContentType() != null ? file.getContentType().trim().toLowerCase() : "";
        byte[] rawBytes;
        try {
            rawBytes = file.getBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read uploaded file.", ex);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User account not found."));

        String previousUrl = user.getAvatarUrl();
        String nextAvatarUrl;

        if (cloudinaryMediaClient.isConfigured()) {
            nextAvatarUrl = cloudinaryMediaClient.uploadAvatarAsWebp(
                    rawBytes,
                    file.getOriginalFilename(),
                    contentType,
                    buildPublicId(userId)
            );
            deleteLocalAvatarIfOwned(previousUrl);
        } else {
            nextAvatarUrl = saveLocalResizedAvatar(userId, rawBytes, detectedFormat);
            deleteLocalAvatarIfOwned(previousUrl);
        }

        user.setAvatarUrl(nextAvatarUrl);
        userRepository.saveAndFlush(user);
        return nextAvatarUrl;
    }

    @Override
    public void removeAvatar(Long userId) {
        if (userId == null) {
            return;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User account not found."));

        String currentUrl = user.getAvatarUrl();
        if (currentUrl != null && currentUrl.contains("res.cloudinary.com") && cloudinaryMediaClient.isConfigured()) {
            cloudinaryMediaClient.destroyAvatar(buildPublicId(userId));
        }
        deleteLocalAvatarIfOwned(currentUrl);

        user.setAvatarUrl(null);
        userRepository.saveAndFlush(user);
    }

    private String buildPublicId(Long userId) {
        return cloudinaryFolder + "/user_" + userId;
    }

    private String saveLocalResizedAvatar(Long userId, byte[] rawBytes, String format) {
        try {
            Path dir = Paths.get(avatarStorageDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);

            if ("webp".equals(format)) {
                String filename = "avatar_" + userId + "_" + UUID.randomUUID() + ".webp";
                Files.write(dir.resolve(filename), rawBytes);
                return LOCAL_URL_PREFIX + filename;
            }

            BufferedImage original = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (original == null) {
                throw new IllegalArgumentException("Unable to decode image.");
            }
            BufferedImage cropped = centerSquareResize(original, LOCAL_TARGET_SIZE, "png".equals(format));
            String outFormat = "png".equals(format) ? "png" : "jpg";
            String filename = "avatar_" + userId + "_" + UUID.randomUUID() + "." + outFormat;
            ImageIO.write(cropped, outFormat.equals("jpg") ? "jpeg" : "png", dir.resolve(filename).toFile());
            return LOCAL_URL_PREFIX + filename;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to save profile photo.", ex);
        }
    }

    private BufferedImage centerSquareResize(BufferedImage source, int size, boolean preserveAlpha) {
        int w = source.getWidth();
        int h = source.getHeight();
        int side = Math.min(w, h);
        int x = (w - side) / 2;
        int y = (h - side) / 2;

        int imageType = preserveAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage dest = new BufferedImage(size, size, imageType);
        Graphics2D g = dest.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (!preserveAlpha) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, size, size);
        }
        g.drawImage(source, 0, 0, size, size, x, y, x + side, y + side, null);
        g.dispose();
        return dest;
    }

    private void deleteLocalAvatarIfOwned(String avatarUrl) {
        if (avatarUrl == null || !avatarUrl.startsWith(LOCAL_URL_PREFIX)) {
            return;
        }
        String filename = avatarUrl.substring(LOCAL_URL_PREFIX.length());
        if (filename.isBlank() || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return;
        }
        try {
            Path dir = Paths.get(avatarStorageDir).toAbsolutePath().normalize();
            Path target = dir.resolve(filename).normalize();
            if (target.startsWith(dir)) {
                Files.deleteIfExists(target);
            }
        } catch (IOException ignored) {
            // Non-fatal cleanup
        }
    }
}
