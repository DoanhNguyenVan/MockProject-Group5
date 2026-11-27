package com.boxcinema.admin.controller;

import com.boxcinema.admin.service.AdminMovieManagementService;
import com.boxcinema.admin.service.MovieMediaUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminMovieControllerUploadTest {

    @Test
    void returnsOkWithUploadedUrlWhenValid() throws Exception {
        AdminMovieManagementService movieService = mock(AdminMovieManagementService.class);
        MovieMediaUploadService uploadService = mock(MovieMediaUploadService.class);
        AdminMovieController controller = new AdminMovieController(movieService);
        ReflectionTestUtils.setField(controller, "movieMediaUploadService", uploadService);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(uploadService.uploadMovieMedia(any(), eq("poster")))
                .thenReturn("https://res.cloudinary.com/demo/image/upload/v1/box-cinema/movies/posters/poster_1.webp");

        MockMultipartFile file = new MockMultipartFile("file", "poster.jpg", "image/jpeg", new byte[]{1, 2, 3});
        mvc.perform(multipart("/admin/movies/upload-media").file(file).param("mediaType", "poster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.mediaType").value("poster"))
                .andExpect(jsonPath("$.url").value("https://res.cloudinary.com/demo/image/upload/v1/box-cinema/movies/posters/poster_1.webp"));
    }

    @Test
    void returnsBadRequestJsonWhenValidationFails() throws Exception {
        AdminMovieManagementService movieService = mock(AdminMovieManagementService.class);
        MovieMediaUploadService uploadService = mock(MovieMediaUploadService.class);
        AdminMovieController controller = new AdminMovieController(movieService);
        ReflectionTestUtils.setField(controller, "movieMediaUploadService", uploadService);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(uploadService.uploadMovieMedia(any(), eq("banner")))
                .thenThrow(new IllegalArgumentException("Only JPG, PNG, or WEBP images are supported."));

        MockMultipartFile file = new MockMultipartFile("file", "bad.txt", "text/plain", new byte[]{1, 2});
        mvc.perform(multipart("/admin/movies/upload-media").file(file).param("mediaType", "banner"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Only JPG, PNG, or WEBP images are supported."));
    }
}
