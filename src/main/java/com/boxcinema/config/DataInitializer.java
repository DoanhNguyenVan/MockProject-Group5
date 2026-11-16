package com.boxcinema.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Khởi tạo dữ liệu mẫu (phim, rạp, thể loại...) khi bật cờ seed-enabled.
 *
 * Lưu ý:
 *   - Tài khoản hệ thống (ADMIN, MANAGER, STAFF, CUSTOMER): chạy file database/seed_accounts.sql trong MySQL.
 *   - KHÔNG insert tài khoản cứng vào đây.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Value("${app.database.seed-enabled:false}")
    private boolean seedEnabled;

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("Database auto-seeding is DISABLED (app.database.seed-enabled=false).");
            return;
        }

        log.info("Database auto-seeding flag is enabled.");
        // TODO: Thêm logic seed dữ liệu mẫu phim, rạp, thể loại... nếu cần
    }
}
