CREATE TABLE ai_model_rates (
 company_id BIGINT NOT NULL,
 provider VARCHAR(20) NOT NULL,
 model_name VARCHAR(100) NOT NULL,
 input_per_million DECIMAL(18,8) NOT NULL,
 output_per_million DECIMAL(18,8) NOT NULL,
 PRIMARY KEY(company_id,provider,model_name),
 FOREIGN KEY(company_id) REFERENCES companies(id)
);
CREATE TABLE ai_call_usage (
 id VARCHAR(36) PRIMARY KEY,
 company_id BIGINT NOT NULL,
 branch_id BIGINT NOT NULL,
 provider VARCHAR(20) NOT NULL,
 model_name VARCHAR(100) NOT NULL,
 feature VARCHAR(40) NOT NULL,
 started_at DATETIME(6) NOT NULL,
 status VARCHAR(20) NOT NULL,
 input_tokens BIGINT NULL,
 output_tokens BIGINT NULL,
 input_per_million DECIMAL(18,8) NULL,
 output_per_million DECIMAL(18,8) NULL,
 estimated_usd DECIMAL(20,10) NULL,
 INDEX idx_ai_usage_company_date_branch(company_id,started_at,branch_id)
);
