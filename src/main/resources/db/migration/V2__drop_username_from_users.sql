-- Username eliminated as a separate identity — email is now the sole
-- login/security identity (Spring Security principal + JWT subject).
-- Drops the unique index backing it (named per V1's Hibernate-generated
-- constraint name) before dropping the column itself.
ALTER TABLE users DROP INDEX UKr43af9ap4edm43mmtq01oddj6;
ALTER TABLE users DROP COLUMN username;
