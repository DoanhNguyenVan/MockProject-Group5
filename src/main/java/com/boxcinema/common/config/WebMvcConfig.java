package com.boxcinema.common.config;

import com.boxcinema.common.security.ActivityLogInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ObjectProvider<ActivityLogInterceptor> activityLogInterceptorProvider;

    @Value("${app.avatar.storage-dir:./uploads/avatars}")
    private String avatarStorageDir;

    @Value("${app.movie-media.storage-dir:./uploads/movies}")
    private String movieMediaStorageDir;

    @Autowired
    public WebMvcConfig(ObjectProvider<ActivityLogInterceptor> activityLogInterceptorProvider) {
        this.activityLogInterceptorProvider = activityLogInterceptorProvider;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registerUploadHandler(registry, "/uploads/avatars/**", avatarStorageDir);
        registerUploadHandler(registry, "/uploads/movies/**", movieMediaStorageDir);
    }

    private void registerUploadHandler(ResourceHandlerRegistry registry, String pattern, String storageDir) {
        Path uploadPath = Paths.get(storageDir).toAbsolutePath().normalize();
        String locationUri = uploadPath.toUri().toString();
        if (!locationUri.endsWith("/")) {
            locationUri += "/";
        }
        registry.addResourceHandler(pattern)
                .addResourceLocations(locationUri);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        ActivityLogInterceptor interceptor = activityLogInterceptorProvider.getIfAvailable();
        if (interceptor != null) {
            registry.addInterceptor(interceptor)
                    .addPathPatterns("/**")
                    .excludePathPatterns(
                            "/assets/**",
                            "/css/**",
                            "/js/**",
                            "/images/**",
                            "/uploads/**",
                            "/webjars/**",
                            "/api/**",
                            "/favicon.ico",
                            "/error"
                    );
        }
    }
}
