package com.boxcinema.config;

import com.boxcinema.entity.User;
import com.boxcinema.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Value("${app.database.seed-enabled:false}")
    private boolean seedEnabled;

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public DataInitializer(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) {
        ensureDefaultUsers();

        if (!seedEnabled) {
            log.info("Database auto-seeding is DISABLED (app.database.seed-enabled=false). Application serves 100% genuine database data.");
            return;
        }
        log.info("Database auto-seeding flag is enabled.");
    }

    private void ensureDefaultUsers() {
        try {
            if (!userRepository.existsByEmail("alex@boxcinema.com")
                    && !userRepository.existsByPhone("0901234567")) {
                User customer = new User(
                        "Alex Nguyen",
                        "alex@boxcinema.com",
                        "0901234567",
                        passwordEncoder.encode("Password123!"),
                        "CUSTOMER"
                );
                userRepository.save(customer);
                log.info("Seeded default CUSTOMER account: alex@boxcinema.com");
            }

            if (!userRepository.existsByEmail("staff@boxcinema.com")
                    && !userRepository.existsByPhone("0901234568")) {
                User staff = new User(
                        "Minh Tran (Staff)",
                        "staff@boxcinema.com",
                        "0901234568",
                        passwordEncoder.encode("Password123!"),
                        "STAFF"
                );
                userRepository.save(staff);
                log.info("Seeded default STAFF account: staff@boxcinema.com");
            }

            if (!userRepository.existsByEmail("manager@boxcinema.com")
                    && !userRepository.existsByPhone("0901234569")) {
                User manager = new User(
                        "Linh Pham (Manager)",
                        "manager@boxcinema.com",
                        "0901234569",
                        passwordEncoder.encode("Password123!"),
                        "MANAGER"
                );
                userRepository.save(manager);
                log.info("Seeded default MANAGER account: manager@boxcinema.com");
            }
        } catch (Exception ex) {
            log.warn("Could not verify/seed default user accounts: {}", ex.getMessage());
        }
    }
}
