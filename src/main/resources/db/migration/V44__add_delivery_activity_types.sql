-- lead_timeline_activities.activity_type is a native SQL ENUM with a fixed
-- member list (see V22) — adding the 5 delivery-related values to the Java
-- ActivityType enum needs the column widened to match, or every insert with
-- one of those values fails ("Data truncated for column 'activity_type'").
ALTER TABLE lead_timeline_activities
    MODIFY COLUMN activity_type ENUM(
        'ASSIGNMENT','CALL_LOG','CLOSED','CONVERTED','CREATED','EMAIL','FIELD_UPDATE',
        'LEAD_LINKED','MEETING','NOTE','PRODUCT_LINKED','QUOTATION','REASSIGNMENT',
        'STATUS_CHANGE','WHATSAPP_MSG',
        'DELIVERY_SCHEDULED','DELIVERY_STATUS_CHANGE','DELIVERY_APPROVAL_REQUESTED',
        'DELIVERY_APPROVED','DELIVERY_REJECTED'
    ) NOT NULL;
