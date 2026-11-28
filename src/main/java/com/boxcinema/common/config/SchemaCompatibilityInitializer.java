package com.boxcinema.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ensures new feature schema additions (users.avatar_url and movie_reviews)
 * exist on startup across both local MySQL and Render TiDB Cloud when ddl-auto=none.
 */
@Component
public class SchemaCompatibilityInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaCompatibilityInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public SchemaCompatibilityInitializer(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (jdbcTemplate == null) {
            return;
        }
        try {
            String usersTable = resolveExactTableName("users");
            String moviesTable = resolveExactTableName("movies");
            String bookingQrTable = resolveExactTableName("booking_qr");

            if (usersTable != null) {
                ensureColumnExists(usersTable, "users", "avatar_url", "VARCHAR(500) NULL");
            }

            if (bookingQrTable != null) {
                ensureColumnExists(bookingQrTable, "booking_qr", "used_at", "DATETIME NULL");
                ensureColumnExists(bookingQrTable, "booking_qr", "revoked_at", "DATETIME NULL");
                ensureColumnExists(bookingQrTable, "booking_qr", "ticket_url", "VARCHAR(500) NULL");
                ensureColumnExists(bookingQrTable, "booking_qr", "qr_image_path", "VARCHAR(500) NULL");
            }

            if (usersTable != null && moviesTable != null) {
                jdbcTemplate.execute(
                        "CREATE TABLE IF NOT EXISTS movie_reviews (" +
                                "review_id BIGINT NOT NULL AUTO_INCREMENT, " +
                                "movie_id BIGINT NOT NULL, " +
                                "user_id BIGINT NOT NULL, " +
                                "rating TINYINT NULL, " +
                                "comment TEXT NULL, " +
                                "is_verified_viewer TINYINT(1) NOT NULL DEFAULT 0, " +
                                "status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE', " +
                                "admin_reply TEXT NULL, " +
                                "admin_replied_at DATETIME NULL, " +
                                "admin_replied_by BIGINT NULL, " +
                                "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                                "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                                "PRIMARY KEY (review_id), " +
                                "UNIQUE KEY uq_movie_review_user (movie_id, user_id), " +
                                "KEY idx_movie_reviews_movie_status_created (movie_id, status, created_at), " +
                                "CONSTRAINT fk_movie_reviews_movie FOREIGN KEY (movie_id) REFERENCES " + moviesTable + " (movie_id) ON DELETE CASCADE, " +
                                "CONSTRAINT fk_movie_reviews_user FOREIGN KEY (user_id) REFERENCES " + usersTable + " (user_id) ON DELETE CASCADE" +
                                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
                );
                String reviewsTable = resolveExactTableName("movie_reviews");
                if (reviewsTable != null) {
                    ensureColumnExists(reviewsTable, "movie_reviews", "admin_reply", "TEXT NULL");
                    ensureColumnExists(reviewsTable, "movie_reviews", "admin_replied_at", "DATETIME NULL");
                    ensureColumnExists(reviewsTable, "movie_reviews", "admin_replied_by", "BIGINT NULL");
                    ensureRatingNullable(reviewsTable);
                }
            }
        } catch (Exception ex) {
            log.warn("Schema compatibility check skipped or encountered an error: {}", ex.getMessage());
        }
    }

    private String resolveExactTableName(String lowerName) {
        List<String> names = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND LOWER(table_name) = ?",
                String.class,
                lowerName
        );
        return names.isEmpty() ? null : names.get(0);
    }

    private void ensureColumnExists(String exactTableName, String lowerTableName, String columnName, String columnDefinition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND LOWER(table_name) = ? AND LOWER(column_name) = ?",
                Integer.class,
                lowerTableName,
                columnName.toLowerCase()
        );
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE " + exactTableName + " ADD COLUMN " + columnName + " " + columnDefinition);
            log.info("Added missing {} column to {}.", columnName, exactTableName);
        }
    }

    private void ensureRatingNullable(String exactTableName) {
        String isNullable = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(IS_NULLABLE), 'YES') FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND LOWER(table_name) = 'movie_reviews' AND LOWER(column_name) = 'rating'",
                String.class
        );
        if ("NO".equalsIgnoreCase(isNullable)) {
            jdbcTemplate.execute("ALTER TABLE " + exactTableName + " MODIFY COLUMN rating TINYINT NULL");
            log.info("Updated {}.rating column to NULLABLE for comment-only discussions.", exactTableName);
        }
    }
}
