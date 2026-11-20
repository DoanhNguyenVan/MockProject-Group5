package com.boxcinema.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Initialize sample data (movies, cinemas, genres...) when seed-enabled flag is on.
 *
 * Notes:
 *   - System accounts (ADMIN, MANAGER, STAFF, CUSTOMER): run database/seed_accounts.sql in MySQL.
 *   - DO NOT hardcode accounts here.
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
        // TODO: Add sample seed data logic for movies, cinemas, genres if needed
    }
}
