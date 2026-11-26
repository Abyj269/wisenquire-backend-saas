-- Module 12 P1 part 2 (S3-d): an outbound message is saved as PENDING before the Graph call and updated
-- right after, so no transaction or row lock is open during the HTTP call. Expand only: adds a value.
ALTER TABLE whatsapp_messages MODIFY COLUMN status ENUM ('PENDING','ACCEPTED','DELIVERED','FAILED','READ','SENT','UNKNOWN') NOT NULL;
