-- Agro-CTI: one-time MySQL setup. Run it once as the MySQL root user:
--     mysql -u root -p < sql/setup.sql
-- It creates the two databases (the second one is used only by the self-tests)
-- and the application user whose name and password are in db.properties.
-- The program then creates its tables itself (see sql/schema.sql).

CREATE DATABASE IF NOT EXISTS agro_cti      CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS agro_cti_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'agrocti'@'localhost' IDENTIFIED BY 'agrocti123';
GRANT ALL PRIVILEGES ON agro_cti.*      TO 'agrocti'@'localhost';
GRANT ALL PRIVILEGES ON agro_cti_test.* TO 'agrocti'@'localhost';
FLUSH PRIVILEGES;
