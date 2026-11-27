package com.boxcinema.admin.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * Handles validation, optimization, and storage for admin-uploaded movie posters and banners.
 */
public interface MovieMediaUploadService {

    /**
     * Uploads a movie poster or banner image from device and returns its public URL.
     *
     * @param file      uploaded image file
     * @param mediaType "poster" or "banner"
     * @return hosted Cloudinary WebP URL or local fallback URL
     */
    String uploadMovieMedia(MultipartFile file, String mediaType);
}
