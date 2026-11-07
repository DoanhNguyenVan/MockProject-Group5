package com.boxcinema.config;

import com.boxcinema.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Value("${app.database.seed-enabled:false}")
    private boolean seedEnabled;

    public DataInitializer() {
    }

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("Database auto-seeding is DISABLED (app.database.seed-enabled=false). Application serves 100% genuine database data.");
            return;
        }
        log.info("Database auto-seeding flag is enabled.");
    }
}
