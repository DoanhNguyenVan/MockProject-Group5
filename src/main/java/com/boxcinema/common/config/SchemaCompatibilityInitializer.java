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

            if (usersTable != null) {
                Integer avatarColCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns " +
                                "WHERE table_schema = DATABASE() AND LOWER(table_name) = 'users' AND LOWER(column_name) = 'avatar_url'",
                        Integer.class
                );
                if (avatarColCount != null && avatarColCount == 0) {
                    jdbcTemplate.execute("ALTER TABLE " + usersTable + " ADD COLUMN avatar_url VARCHAR(500) NULL");
                    log.info("Added missing avatar_url column to {}.", usersTable);
                }
            }

            if (usersTable != null && moviesTable != null) {
                jdbcTemplate.execute(
                        "CREATE TABLE IF NOT EXISTS movie_reviews (" +
                                "review_id BIGINT NOT NULL AUTO_INCREMENT, " +
                                "movie_id BIGINT NOT NULL, " +
                                "user_id BIGINT NOT NULL, " +
                                "rating TINYINT NOT NULL, " +
                                "comment TEXT NULL, " +
                                "is_verified_viewer TINYINT(1) NOT NULL DEFAULT 0, " +
                                "status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE', " +
                                "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                                "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                                "PRIMARY KEY (review_id), " +
                                "UNIQUE KEY uq_movie_review_user (movie_id, user_id), " +
                                "KEY idx_movie_reviews_movie_status_created (movie_id, status, created_at), " +
                                "CONSTRAINT fk_movie_reviews_movie FOREIGN KEY (movie_id) REFERENCES " + moviesTable + " (movie_id) ON DELETE CASCADE, " +
                                "CONSTRAINT fk_movie_reviews_user FOREIGN KEY (user_id) REFERENCES " + usersTable + " (user_id) ON DELETE CASCADE" +
                                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
                );
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
}
