package com.boxcinema.common.util;

import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;

/**
 * Shared validation and magic-byte verification for user-uploaded JPG, PNG, and WEBP images.
 */
public final class ImageUploadSupport {

    public static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp"
    );

    private ImageUploadSupport() {
    }

    /**
     * Validates size, MIME type, file header signature, and decodable dimensions.
     *
     * @return verified lowercase format token: "png", "jpg", or "webp"
     */
    public static String validateAndDetectFormat(MultipartFile file, long maxBytes, String sizeErrorMessage) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please choose an image file to upload.");
        }
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException(sizeErrorMessage);
        }

        String contentType = file.getContentType() != null ? file.getContentType().trim().toLowerCase() : "";
        if (!ALLOWED_MIME_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Only JPG, PNG, or WEBP images are supported.");
        }

        byte[] rawBytes;
        try {
            rawBytes = file.getBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read uploaded file.", ex);
        }

        return detectAndVerifyImageFormat(rawBytes);
    }

    /**
     * Verifies image magic bytes and checks readable image dimensions for PNG and JPEG payloads.
     */
    public static String detectAndVerifyImageFormat(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            throw new IllegalArgumentException("Invalid or corrupted image file.");
        }
        boolean isPng = bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47;
        boolean isJpeg = bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF;
        boolean isWebp = "RIFF".equals(new String(bytes, 0, 4, StandardCharsets.US_ASCII))
                && "WEBP".equals(new String(bytes, 8, 4, StandardCharsets.US_ASCII));

        if (!isPng && !isJpeg && !isWebp) {
            throw new IllegalArgumentException("Only valid JPG, PNG, or WEBP image files are allowed.");
        }

        if (isPng || isJpeg) {
            String formatName = isPng ? "png" : "jpeg";
            try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                Iterator<ImageReader> readers = iis != null ? ImageIO.getImageReadersByFormatName(formatName) : null;
                if (readers == null || !readers.hasNext()) {
                    throw new IllegalArgumentException("The uploaded file is not a readable image.");
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(iis, true, true);
                    if (reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) {
                        throw new IllegalArgumentException("The uploaded file has invalid image dimensions.");
                    }
                } finally {
                    reader.dispose();
                }
            } catch (IOException ex) {
                throw new IllegalArgumentException("The uploaded image could not be decoded.", ex);
            }
            return isPng ? "png" : "jpg";
        }
        return "webp";
    }
}
