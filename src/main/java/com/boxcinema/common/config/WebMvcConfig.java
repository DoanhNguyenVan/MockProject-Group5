package com.boxcinema.common.config;

import com.boxcinema.common.security.ActivityLogInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ObjectProvider<ActivityLogInterceptor> activityLogInterceptorProvider;

    @Autowired
    public WebMvcConfig(ObjectProvider<ActivityLogInterceptor> activityLogInterceptorProvider) {
        this.activityLogInterceptorProvider = activityLogInterceptorProvider;
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
                            "/webjars/**",
                            "/api/**",
                            "/favicon.ico",
                            "/error"
                    );
        }
    }
}
