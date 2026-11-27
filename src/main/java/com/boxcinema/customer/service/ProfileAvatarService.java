package com.boxcinema.customer.service;

import org.springframework.web.multipart.MultipartFile;

public interface ProfileAvatarService {
    String uploadAvatar(Long userId, MultipartFile file);
    void removeAvatar(Long userId);
}
