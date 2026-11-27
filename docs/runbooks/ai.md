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
