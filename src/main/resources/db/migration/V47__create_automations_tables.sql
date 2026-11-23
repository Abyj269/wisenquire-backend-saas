CREATE TABLE automations (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  version           BIGINT DEFAULT 0,
  company_id        BIGINT NOT NULL,
  branch_id         BIGINT,
  name              VARCHAR(200) NOT NULL,
  description       TEXT,
  status            VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
  trigger_type      VARCHAR(50) NOT NULL,
  trigger_json      LONGTEXT,
  conditions_json   LONGTEXT,
  actions_json      LONGTEXT,
  trigger_count     INT DEFAULT 0,
  success_count     INT DEFAULT 0,
  failure_count     INT DEFAULT 0,
  last_triggered_at DATETIME(6),
  created_by_user_id BIGINT,
  created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at        DATETIME(6),
  deleted_at        DATETIME(6),
  INDEX idx_automation_company   (company_id),
  INDEX idx_automation_status    (status),
  INDEX idx_automation_trigger   (trigger_type),
  CONSTRAINT fk_automation_company FOREIGN KEY (company_id) REFERENCES companies(id),
  CONSTRAINT fk_automation_branch FOREIGN KEY (branch_id) REFERENCES branches(id),
  CONSTRAINT fk_automation_creator FOREIGN KEY (created_by_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE automation_executions (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  automation_id   BIGINT NOT NULL,
  customer_id     BIGINT,
  customer_phone  VARCHAR(30) NOT NULL,
  customer_name   VARCHAR(200),
  status          VARCHAR(30) NOT NULL DEFAULT 'RUNNING',
  started_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  completed_at    DATETIME(6),
  failure_reason  TEXT,
  INDEX idx_execution_automation (automation_id),
  INDEX idx_execution_status     (status),
  CONSTRAINT fk_execution_automation FOREIGN KEY (automation_id) REFERENCES automations(id) ON DELETE CASCADE,
  CONSTRAINT fk_execution_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE automation_execution_steps (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  execution_id  BIGINT NOT NULL,
  node_type     VARCHAR(50) NOT NULL,
  node_label    VARCHAR(200),
  status        VARCHAR(30) NOT NULL,
  executed_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  detail        TEXT,
  INDEX idx_step_execution (execution_id),
  CONSTRAINT fk_step_execution FOREIGN KEY (execution_id) REFERENCES automation_executions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
