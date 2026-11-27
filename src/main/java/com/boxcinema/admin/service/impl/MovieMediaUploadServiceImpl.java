package com.boxcinema.admin.service.impl;

import com.boxcinema.admin.service.MovieMediaUploadService;
import com.boxcinema.common.service.CloudinaryMediaClient;
import com.boxcinema.common.util.ImageUploadSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores uploaded movie posters and banners on Cloudinary as WebP when configured,
 * falling back to aspect-preserving local files when running without Cloudinary credentials.
 */
@Service
public class MovieMediaUploadServiceImpl implements MovieMediaUploadService {

    private static final long MAX_MEDIA_BYTES = 5L * 1024L * 1024L;
    private static final String LOCAL_URL_PREFIX = "/uploads/movies/";
    private static final int POSTER_MAX_WIDTH = 800;
    private static final int POSTER_MAX_HEIGHT = 1200;
    private static final int BANNER_MAX_WIDTH = 1920;
    private static final int BANNER_MAX_HEIGHT = 1080;

    private final CloudinaryMediaClient cloudinaryMediaClient;
    private final String mediaStorageDir;
    private final String cloudinaryFolder;

    public MovieMediaUploadServiceImpl(
            CloudinaryMediaClient cloudinaryMediaClient,
            @Value("${app.movie-media.storage-dir:./uploads/movies}") String mediaStorageDir,
            @Value("${app.movie-media.cloudinary-folder:box-cinema/movies}") String cloudinaryFolder
    ) {
        this.cloudinaryMediaClient = cloudinaryMediaClient;
        this.mediaStorageDir = mediaStorageDir;
        this.cloudinaryFolder = cloudinaryFolder != null
                ? cloudinaryFolder.replaceAll("^/+|/+$", "")
                : "box-cinema/movies";
    }

    @Override
    public String uploadMovieMedia(MultipartFile file, String mediaType) {
        String normalizedType = normalizeMediaType(mediaType);
        boolean isBanner = "banner".equals(normalizedType);

        String detectedFormat = ImageUploadSupport.validateAndDetectFormat(
                file,
                MAX_MEDIA_BYTES,
                "Movie image must be 5 MB or smaller."
        );

        byte[] rawBytes;
        try {
            rawBytes = file.getBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read uploaded file.", ex);
        }

        if (cloudinaryMediaClient.isConfigured()) {
            String subFolder = isBanner ? "banners" : "posters";
            String publicId = cloudinaryFolder + "/" + subFolder + "/" + normalizedType + "_" + UUID.randomUUID();
            return cloudinaryMediaClient.uploadMovieMediaAsWebp(
                    rawBytes,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    publicId,
                    isBanner
            );
        }

        return saveLocalBoundedImage(normalizedType, rawBytes, detectedFormat, isBanner);
    }

    private String normalizeMediaType(String mediaType) {
        if (mediaType == null) {
            throw new IllegalArgumentException("Media type must be poster or banner.");
        }
        String normalized = mediaType.trim().toLowerCase(Locale.ROOT);
        if (!"poster".equals(normalized) && !"banner".equals(normalized)) {
            throw new IllegalArgumentException("Media type must be poster or banner.");
        }
        return normalized;
    }

    private String saveLocalBoundedImage(String mediaType, byte[] rawBytes, String format, boolean isBanner) {
        try {
            Path dir = Paths.get(mediaStorageDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);

            if ("webp".equals(format)) {
                String filename = mediaType + "_" + UUID.randomUUID() + ".webp";
                Files.write(dir.resolve(filename), rawBytes);
                return LOCAL_URL_PREFIX + filename;
            }

            BufferedImage original = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (original == null) {
                throw new IllegalArgumentException("Unable to decode image.");
            }
            int maxW = isBanner ? BANNER_MAX_WIDTH : POSTER_MAX_WIDTH;
            int maxH = isBanner ? BANNER_MAX_HEIGHT : POSTER_MAX_HEIGHT;
            BufferedImage fitted = fitWithinBounds(original, maxW, maxH, "png".equals(format));

            String outFormat = "png".equals(format) ? "png" : "jpg";
            String filename = mediaType + "_" + UUID.randomUUID() + "." + outFormat;
            ImageIO.write(fitted, "jpg".equals(outFormat) ? "jpeg" : "png", dir.resolve(filename).toFile());
            return LOCAL_URL_PREFIX + filename;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to save movie image.", ex);
        }
    }

    private BufferedImage fitWithinBounds(BufferedImage source, int maxWidth, int maxHeight, boolean preserveAlpha) {
        int srcW = source.getWidth();
        int srcH = source.getHeight();
        double scale = Math.min(1.0, Math.min((double) maxWidth / srcW, (double) maxHeight / srcH));
        int targetW = Math.max(1, (int) Math.round(srcW * scale));
        int targetH = Math.max(1, (int) Math.round(srcH * scale));

        int imageType = preserveAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage dest = new BufferedImage(targetW, targetH, imageType);
        Graphics2D g = dest.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (!preserveAlpha) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, targetW, targetH);
        }
        g.drawImage(source, 0, 0, targetW, targetH, null);
        g.dispose();
        return dest;
    }
}
