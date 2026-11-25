-- Module 03 P1 (F-6). Organizations get a unique, URL-safe slug; the display name stops being
-- unique across the platform, so two tenants may both be called "ABC Motors".
-- Expand/contract: slug is added and filled first; only then is UNIQUE(name) dropped.

ALTER TABLE organizations ADD COLUMN slug VARCHAR(80) NULL;

-- From the (unique) code: lower case, runs of other characters -> '-', trimmed.
UPDATE organizations
SET slug = TRIM(BOTH '-' FROM LOWER(REGEXP_REPLACE(code, '[^A-Za-z0-9]+', '-')));
UPDATE organizations SET slug = CONCAT('org-', id) WHERE slug IS NULL OR slug = '';

-- Lower-casing / punctuation can make two codes produce the same slug: suffix those with the id.
UPDATE organizations o
JOIN (SELECT slug FROM (SELECT slug FROM organizations GROUP BY slug HAVING COUNT(*) > 1) dup_inner) dup ON o.slug = dup.slug
SET o.slug = CONCAT(LEFT(o.slug, 60), '-', o.id);

ALTER TABLE organizations MODIFY slug VARCHAR(80) NOT NULL;
ALTER TABLE organizations ADD CONSTRAINT uk_organizations_slug UNIQUE (slug);

-- Drop UNIQUE(name) by looking up its real name (V1 named it UKp9pbw3flq9hkay8hdx3ypsldy, but a
-- database created by Hibernate could differ). No-op if no such index exists.
SET @name_idx = (SELECT index_name FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'organizations'
                   AND column_name = 'name' AND non_unique = 0 LIMIT 1);
SET @drop_sql = IF(@name_idx IS NULL, 'SELECT 1', CONCAT('ALTER TABLE organizations DROP INDEX `', @name_idx, '`'));
PREPARE drop_name_unique FROM @drop_sql;
EXECUTE drop_name_unique;
DEALLOCATE PREPARE drop_name_unique;
