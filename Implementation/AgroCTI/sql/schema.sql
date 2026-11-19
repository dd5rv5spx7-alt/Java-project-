-- Agro-CTI database (MySQL 8+, InnoDB). The program creates these tables itself at start-up
-- (com.agrocti.db.Schema); this file is for reference or for creating them by hand:
--     mysql -u agrocti -p agro_cti < sql/schema.sql
-- Run sql/setup.sql first (as root) to create the databases and the application user.

CREATE TABLE IF NOT EXISTS users (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(40) NOT NULL UNIQUE,
    full_name     VARCHAR(80) NOT NULL,
    role          VARCHAR(10) NOT NULL,                   -- ADMIN, ANALYST or FARMER
    password_hash CHAR(64)    NOT NULL,                   -- PBKDF2-HMAC-SHA256, hex
    salt          CHAR(32)    NOT NULL,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_user_role CHECK (role IN ('ADMIN','ANALYST','FARMER'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS farm_nodes (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    sim_index   INT NULL UNIQUE,                          -- 0..23 for the monitored network
    node_code   VARCHAR(12) NOT NULL UNIQUE,              -- e.g. GUN-F3
    region      VARCHAR(20) NOT NULL,
    crop        VARCHAR(30) NOT NULL,
    gateway_ip  VARCHAR(15) NOT NULL,
    firmware    VARCHAR(12) NOT NULL,
    status      VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
    owner_id    INT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_node_status CHECK (status IN ('ACTIVE','MAINTENANCE','RETIRED')),
    CONSTRAINT fk_node_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS threat_indicators (
    id          VARCHAR(20) PRIMARY KEY,                  -- ind-NNN from the feed, local-NNN found here
    type        VARCHAR(20) NOT NULL,                     -- ipv4 or regional-advisory
    value       VARCHAR(64) NOT NULL,
    region      INT NOT NULL DEFAULT -1,
    valid_from  INT NOT NULL DEFAULT 0,                   -- 15-minute steps since 1 July 2026
    valid_to    INT NOT NULL DEFAULT 2147483647,
    confidence  INT NOT NULL,
    source      VARCHAR(40) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_ind_type CHECK (type IN ('ipv4','regional-advisory')),
    CONSTRAINT chk_ind_conf CHECK (confidence BETWEEN 0 AND 100)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS incidents (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    node_id      INT NOT NULL,                            -- farm_nodes.sim_index
    node_name    VARCHAR(12) NOT NULL,
    region       VARCHAR(20) NOT NULL,
    start_step   INT NOT NULL,
    end_step     INT NOT NULL,
    readings     INT NOT NULL,
    severity     VARCHAR(10) NOT NULL,                    -- ALERT or CRITICAL
    max_score    DOUBLE NOT NULL,
    guess        VARCHAR(80),
    reasons      TEXT,
    truth        VARCHAR(80),                             -- ground truth (simulated data)
    status       VARCHAR(14) NOT NULL DEFAULT 'OPEN',
    analyst_note VARCHAR(500) NOT NULL DEFAULT '',
    assigned_to  INT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_inc_status CHECK (status IN ('OPEN','INVESTIGATING','RESOLVED','FALSE_ALARM')),
    CONSTRAINT fk_inc_node FOREIGN KEY (node_id) REFERENCES farm_nodes(sim_index),
    CONSTRAINT fk_inc_user FOREIGN KEY (assigned_to) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS response_actions (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    incident_id  INT NOT NULL,
    action_type  VARCHAR(20) NOT NULL,
    description  VARCHAR(300) NOT NULL,
    performed_by INT NULL,
    status       VARCHAR(12) NOT NULL DEFAULT 'PLANNED',
    due_date     DATE NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_act_status CHECK (status IN ('PLANNED','IN_PROGRESS','DONE')),
    CONSTRAINT fk_act_incident FOREIGN KEY (incident_id) REFERENCES incidents(id) ON DELETE CASCADE,
    CONSTRAINT fk_act_user FOREIGN KEY (performed_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS model_runs (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    run_at       DATETIME NOT NULL,
    seed         BIGINT,
    model        VARCHAR(60) NOT NULL,
    kind         VARCHAR(20),
    f1 DOUBLE, precision_ DOUBLE, recall DOUBLE, fpr DOUBLE,
    parameters BIGINT, memory_bytes BIGINT, latency_us DOUBLE
) ENGINE=InnoDB;
