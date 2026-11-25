-- 01-foundation Part B item 7. ShedLock: each @Scheduled job runs on one instance at a time
-- (standard ShedLock JDBC schema). one_time_tasks: done-markers for start-up tasks that must
-- run exactly once per database (first: whatsapp-credential-migration).
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE one_time_tasks (
    task_key     VARCHAR(100) NOT NULL,
    completed_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (task_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
