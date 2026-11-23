-- lead_timeline_activities.activity_type is a native SQL ENUM with a fixed
-- member list — adding QUOTATION to the Java ActivityType enum (for
-- quotation create/update/send activity logging) needs the column widened
-- to match, or every insert with that value fails ("Data truncated").
ALTER TABLE lead_timeline_activities
    MODIFY COLUMN activity_type ENUM(
        'ASSIGNMENT','CALL_LOG','CLOSED','CONVERTED','CREATED','EMAIL','FIELD_UPDATE',
        'LEAD_LINKED','MEETING','NOTE','PRODUCT_LINKED','QUOTATION','REASSIGNMENT',
        'STATUS_CHANGE','WHATSAPP_MSG'
    ) NOT NULL;
