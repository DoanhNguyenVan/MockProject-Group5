USE box_cinema;

CREATE TABLE IF NOT EXISTS user_activity_logs (
    log_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    email VARCHAR(255) NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'GUEST',
    action_type VARCHAR(50) NOT NULL,
    page_url VARCHAR(255) NOT NULL,
    page_title VARCHAR(150) NULL,
    ip_address VARCHAR(45) NOT NULL,
    location VARCHAR(100) NULL,
    device_info VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_activity (user_id, created_at),
    INDEX idx_created_at (created_at),
    INDEX idx_role_action (role, action_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SHOW TABLES LIKE 'user_activity_logs';
