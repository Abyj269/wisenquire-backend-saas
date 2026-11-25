-- Module 06 P1: a random, unique code per company for document numbers (LD-<code>-<year>-<seq>),
-- so a number no longer shows the internal company id. Used only with flag
-- documents.company_code_prefix ON (V71, default OFF). Expand only: a new column; nothing else changes.
-- New companies get a code from the application (@PrePersist, same alphabet).

ALTER TABLE companies ADD COLUMN document_code VARCHAR(12) NULL;

-- 6 random base-36 characters (0-9, A-Z), not derived from the id.
UPDATE companies
SET document_code = LPAD(UPPER(CONV(FLOOR(RAND() * 2176782336), 10, 36)), 6, '0')
WHERE document_code IS NULL;

-- Re-draw the (very rare) duplicates, twice; the unique key below fails loudly if any remain.
UPDATE companies c
JOIN (SELECT document_code FROM (SELECT document_code FROM companies GROUP BY document_code HAVING COUNT(*) > 1) d1) d
  ON c.document_code = d.document_code
SET c.document_code = LPAD(UPPER(CONV(FLOOR(RAND() * 2176782336), 10, 36)), 6, '0');

UPDATE companies c
JOIN (SELECT document_code FROM (SELECT document_code FROM companies GROUP BY document_code HAVING COUNT(*) > 1) d1) d
  ON c.document_code = d.document_code
SET c.document_code = LPAD(UPPER(CONV(FLOOR(RAND() * 2176782336), 10, 36)), 6, '0');

ALTER TABLE companies ADD CONSTRAINT uk_companies_document_code UNIQUE (document_code);
