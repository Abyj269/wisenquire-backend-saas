# Runbook: AI features (module 18)

## Short transactions for AI calls (P1 part 1, S3-b)
- Flag `ai.short_transactions` (V82, default OFF), per org.
- **OFF (today):** summary, translation, voice-note transcription and the conversion-score refresh each run in one transaction. That transaction holds the branch's AI usage-counter row lock (`ai_usage_counters`, `PESSIMISTIC_WRITE`) during the provider call (30 s timeout; 60 s for transcription). A branch's AI calls therefore run one after another.
- **ON:**
  1. A short read checks scope and builds the context.
  2. The admission check commits on its own (lock released).
  3. The provider call, and for voice notes the Meta media download, run with **no transaction open**.
  4. A short write saves the result.
  - A failed provider call gives its reservation back, so it does not count against the branch's caps. This also applies to reply suggestion and draft improvement.
- **What to watch after turning it on:**
  - The AI endpoints' latency under load should drop (calls in one branch overlap).
  - 429 `AI_USAGE_LIMIT` should still appear at the caps.
  - `ai_usage_counters.request_count` should match the successful calls.
- **Rollback:** turn the flag off for the org (instant; no data change).

```sql
INSERT INTO feature_flag_overrides (flag_key, organization_id, enabled, set_by, reason)
VALUES ('ai.short_transactions', <org_id>, 1, '<you>', 'module 18 S3-b');
DELETE FROM feature_flag_overrides WHERE flag_key = 'ai.short_transactions' AND organization_id = <org_id>;
```

## Load check (L7)
- No load-test tooling or L7 baseline exists in the repos yet.
- The in-repo stand-in is `AiShortTransactionsIT`: 10 parallel calls in one branch with a 1 s provider finish in under 5 s with the flag ON (serialised OFF: about 1 s per call).
- Run the real L7 mix on staging once load tooling is in place, and record the baseline there.

## AI model rates are platform-owned (P1 part 2, D9)
- Table `platform_ai_model_rates (provider, model_name)` (V81), filled at migration with the **highest** rate each model had in any company.
- The usage ledger prices new calls from it; a model with no platform rate still uses the old per-company rate (`ai_model_rates`, kept for one release).
- Only the platform super admin can change rates (`PUT /ai/settings/usage-report/rates`); tenant admins get 403 and see the rates read-only.

```sql
SELECT * FROM platform_ai_model_rates ORDER BY provider, model_name;
INSERT INTO platform_ai_model_rates (provider, model_name, input_per_million, output_per_million) VALUES ('OPENAI','<model>',<in>,<out>)
  ON DUPLICATE KEY UPDATE input_per_million = VALUES(input_per_million), output_per_million = VALUES(output_per_million);
```

## Usage report days (T-2)
- `ai_call_usage.started_at` is stored in UTC. The report converts the company's local days to UTC bounds and groups rows by the company day; the response says which timezone (`timezone`).
- Totals for a day now match the company's calendar; older reports viewed before this change were 4 h (Dubai) or 5 h 30 (India) off.

