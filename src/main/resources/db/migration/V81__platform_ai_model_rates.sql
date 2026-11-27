-- Module 18 P1 part 2 (D9): AI model rates are platform-owned. Expand only: ai_model_rates (per company) stays
-- for one release as the fallback for a model with no platform rate. Owner decision: where companies disagree,
-- the platform rate is the highest (it never under-estimates cost).
CREATE TABLE platform_ai_model_rates (
  provider VARCHAR(20) NOT NULL,
  model_name VARCHAR(100) NOT NULL,
  input_per_million DECIMAL(18,8) NOT NULL,
  output_per_million DECIMAL(18,8) NOT NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (provider, model_name)
);
INSERT INTO platform_ai_model_rates (provider, model_name, input_per_million, output_per_million)
SELECT provider, model_name, MAX(input_per_million), MAX(output_per_million)
FROM ai_model_rates GROUP BY provider, model_name;
