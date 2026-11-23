-- Baseline schema for the Leads System, generated offline from the current
-- JPA entities (backend/src/main/java/com/leads/model/entity/) via
-- Hibernate's schema-export targeting MySQLDialect — not hand-transcribed,
-- so it's exact. From this point on, MySQL schema state is owned entirely
-- by Flyway migrations (V2, V3, ...); Hibernate runs with ddl-auto=validate
-- and never issues DDL against MySQL-backed profiles.
--
-- Prerequisites (must already be applied on the target MySQL server BEFORE
-- this migration runs — see docs/DATABASE_ARCHITECTURE.md):
--   * lower_case_table_names=1, and the schema/database itself created with
--     DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_0900_ai_ci
--     (tables below have no per-table CHARACTER SET clause, so they inherit
--     the database's default — this must be utf8mb4 for emoji/non-Latin
--     WhatsApp/customer text to round-trip correctly).
--   * default-time-zone='+00:00' (paired with serverTimezone=UTC on the
--     JDBC URL) since all timestamps here are timezone-naive LocalDateTime.
--
-- Partitioning note (see plan Section 11 — not implemented here): if
-- whatsapp_messages is ever partitioned by RANGE(created_at) once it
-- approaches ~50-100M rows, MySQL requires every unique key to include the
-- partition column. That means the PK (id) and the idx_msg_wamid unique
-- constraint below would both need created_at appended to remain valid
-- unique keys under partitioning — a minor, deliberate trade-off to make at
-- that time, not now.

    create table branch_trusted_ips (
        is_active bit not null,
        branch_id bigint not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        ip_address varchar(64) not null,
        label varchar(150),
        primary key (id)
    ) engine=InnoDB;

    create table branches (
        ip_restriction_enabled bit not null,
        is_active bit not null,
        company_id bigint not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        code varchar(50) not null,
        phone varchar(50),
        city varchar(100),
        email varchar(100),
        name varchar(150) not null,
        primary key (id)
    ) engine=InnoDB;

    create table companies (
        is_active bit not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        organization_id bigint not null,
        updated_at datetime(6),
        currency varchar(10),
        code varchar(50) not null,
        tax_number varchar(50),
        name varchar(150) not null,
        logo_url text,
        primary key (id)
    ) engine=InnoDB;

    create table company_lead_settings (
        auto_assign_enabled bit not null,
        link_product_enabled bit not null,
        whatsapp_auto_reply_enabled bit not null,
        company_id bigint not null,
        created_at datetime(6),
        default_status_id bigint,
        id bigint not null auto_increment,
        updated_at datetime(6),
        auto_assign_strategy varchar(50),
        whatsapp_welcome_message varchar(500),
        primary key (id)
    ) engine=InnoDB;

    create table customer_change_logs (
        created_at datetime(6),
        customer_id bigint not null,
        id bigint not null auto_increment,
        performed_by_id bigint,
        title varchar(150) not null,
        attachment_data text,
        attachment_name varchar(255),
        description text,
        activity_type enum ('ASSIGNMENT','CALL_LOG','CLOSED','CONVERTED','CREATED','EMAIL','FIELD_UPDATE','LEAD_LINKED','MEETING','NOTE','PRODUCT_LINKED','REASSIGNMENT','STATUS_CHANGE','WHATSAPP_MSG') not null,
        primary key (id)
    ) engine=InnoDB;

    create table customers (
        converted_leads_count integer not null,
        total_leads_count integer not null,
        company_id bigint not null,
        created_at datetime(6),
        first_lead_at datetime(6),
        home_branch_id bigint,
        id bigint not null auto_increment,
        last_converted_at datetime(6),
        last_lead_at datetime(6),
        organization_id bigint not null,
        updated_at datetime(6),
        mobile_normalized varchar(9) not null,
        mobile_number varchar(50) not null,
        whatsapp_number varchar(50),
        city varchar(100),
        customer_company_name varchar(150),
        email varchar(150),
        name varchar(150) not null,
        notes text,
        status enum ('CONVERTED','EXISTING','LOST','NEW','NON_CONVERTED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table dynamic_field_definitions (
        grid_width integer not null,
        is_active bit not null,
        is_mandatory bit not null,
        sort_order integer not null,
        company_id bigint not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        module varchar(50) not null,
        field_name varchar(80) not null,
        field_label varchar(120) not null,
        default_value varchar(255),
        options_json text,
        placeholder varchar(255),
        regex_validation varchar(255),
        field_type enum ('CHECKBOX','DATE','DATETIME','DECIMAL','EMAIL','FILE','MULTI_SELECT','NUMBER','PHONE','RADIO','SELECT','SWITCH','TEXT','TEXTAREA','TIME','URL') not null,
        primary key (id)
    ) engine=InnoDB;

    create table lead_products (
        discount_amount decimal(14,2),
        quantity integer not null,
        tax_amount decimal(14,2),
        total_price decimal(14,2) not null,
        unit_price decimal(14,2) not null,
        id bigint not null auto_increment,
        lead_id bigint not null,
        product_id bigint not null,
        notes varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table lead_purposes (
        is_active bit not null,
        sort_order integer not null,
        company_id bigint,
        created_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        purpose_name varchar(120) not null,
        description varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table lead_sources (
        is_active bit not null,
        is_system_defined bit not null,
        sort_order integer not null,
        company_id bigint,
        created_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        color_code varchar(20) not null,
        icon varchar(50),
        source_code varchar(50) not null,
        source_name varchar(100) not null,
        primary key (id)
    ) engine=InnoDB;

    create table lead_statuses (
        is_active bit not null,
        is_default bit not null,
        is_system_defined bit not null,
        sort_order integer not null,
        company_id bigint,
        created_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        color_code varchar(20) not null,
        status_code varchar(50) not null,
        status_name varchar(100) not null,
        stage_type enum ('ASSIGNED','CLOSED','CONVERTED','IN_PROGRESS','LOST','NEGOTIATION','OPEN','PROPOSAL_SENT','QUALIFIED','QUOTATION_PROVIDED','QUOTE_APPROVED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table lead_timeline_activities (
        created_at datetime(6),
        id bigint not null auto_increment,
        lead_id bigint not null,
        performed_by_id bigint,
        title varchar(150) not null,
        attachment_data text,
        attachment_name varchar(255),
        description text,
        activity_type enum ('ASSIGNMENT','CALL_LOG','CLOSED','CONVERTED','CREATED','EMAIL','FIELD_UPDATE','LEAD_LINKED','MEETING','NOTE','PRODUCT_LINKED','REASSIGNMENT','STATUS_CHANGE','WHATSAPP_MSG') not null,
        meta_data json,
        primary key (id)
    ) engine=InnoDB;

    create table leads (
        closing_date date,
        estimated_deal_value decimal(14,2),
        is_converted bit not null,
        assigned_by_id bigint,
        assigned_to_id bigint,
        branch_id bigint not null,
        company_id bigint not null,
        converted_at datetime(6),
        created_at datetime(6),
        customer_id bigint,
        id bigint not null auto_increment,
        organization_id bigint not null,
        purpose_id bigint,
        source_id bigint not null,
        status_id bigint not null,
        updated_at datetime(6),
        lead_number varchar(50) not null,
        phone varchar(50) not null,
        whatsapp_number varchar(50),
        first_name varchar(100) not null,
        job_title varchar(100),
        last_name varchar(100),
        customer_company_name varchar(150),
        email varchar(150),
        notes text,
        dynamic_values json,
        primary key (id)
    ) engine=InnoDB;

    create table login_otps (
        is_used bit not null,
        created_at datetime(6),
        expires_at datetime(6) not null,
        id bigint not null auto_increment,
        user_id bigint not null,
        code varchar(10) not null,
        primary key (id)
    ) engine=InnoDB;

    create table organizations (
        is_active bit not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        code varchar(50) not null,
        subscription_plan varchar(50),
        name varchar(150) not null,
        logo_url text,
        primary key (id)
    ) engine=InnoDB;

    create table product_masters (
        is_active bit not null,
        tax_percentage decimal(5,2),
        unit_price decimal(14,2) not null,
        company_id bigint not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        currency varchar(10),
        product_code varchar(50) not null,
        sku varchar(50),
        category varchar(100),
        product_name varchar(150) not null,
        description text,
        primary key (id)
    ) engine=InnoDB;

    create table role_permissions (
        can_add bit not null,
        can_approve bit not null,
        can_assign bit not null,
        can_delete bit not null,
        can_edit bit not null,
        can_export bit not null,
        can_print bit not null,
        can_view bit not null,
        id bigint not null auto_increment,
        role_id bigint not null,
        module_name varchar(80) not null,
        primary key (id)
    ) engine=InnoDB;

    create table roles (
        is_system_role bit not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        organization_id bigint,
        updated_at datetime(6),
        role_name varchar(100) not null,
        description varchar(255),
        role_level enum ('BRANCH','COMPANY','ORGANIZATION','SUPER_ADMIN') not null,
        primary key (id)
    ) engine=InnoDB;

    create table user_branch_mappings (
        branch_id bigint not null,
        user_id bigint not null,
        primary key (branch_id, user_id)
    ) engine=InnoDB;

    create table user_company_mappings (
        company_id bigint not null,
        user_id bigint not null,
        primary key (company_id, user_id)
    ) engine=InnoDB;

    create table users (
        is_active bit not null,
        is_super_admin bit not null,
        is_trusted_user bit not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        organization_id bigint,
        role_id bigint not null,
        updated_at datetime(6),
        phone varchar(50),
        first_name varchar(80) not null,
        last_name varchar(80),
        username varchar(100) not null,
        email varchar(150) not null,
        password varchar(255) not null,
        primary key (id)
    ) engine=InnoDB;

    create table whatsapp_configurations (
        is_active bit not null,
        branch_id bigint not null,
        company_id bigint not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        display_phone_number varchar(50),
        phone_number_id varchar(100) not null,
        verify_token varchar(100) not null,
        waba_id varchar(100) not null,
        app_secret varchar(200),
        access_token varchar(500) not null,
        primary key (id)
    ) engine=InnoDB;

    create table whatsapp_conversations (
        unread_count integer not null,
        assigned_agent_id bigint,
        branch_id bigint,
        company_id bigint not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        last_message_at datetime(6),
        lead_id bigint,
        updated_at datetime(6),
        chat_status varchar(30) not null,
        customer_wa_id varchar(50) not null,
        receiving_phone_number_id varchar(100),
        customer_name varchar(150),
        last_message_text varchar(500),
        primary key (id)
    ) engine=InnoDB;

    create table whatsapp_messages (
        conversation_id bigint not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        sent_by_user_id bigint,
        message_type varchar(30) not null,
        media_mime_type varchar(100),
        media_file_name varchar(150),
        wamid varchar(150),
        media_url varchar(500),
        body_text text,
        error_message varchar(255),
        direction enum ('INBOUND','OUTBOUND') not null,
        status enum ('DELIVERED','FAILED','READ','SENT') not null,
        primary key (id)
    ) engine=InnoDB;

    alter table company_lead_settings
       add constraint UKobm75x4pxlw7ur44tf8e0eoa1 unique (company_id);

    create index idx_customer_company_branch
       on customers (company_id, home_branch_id);

    create index idx_customer_company_status
       on customers (company_id, status);

    alter table customers
       add constraint idx_customer_company_mobile unique (company_id, mobile_normalized);

    alter table leads
       add constraint UKp2byvjcuk0iv1wjdxx6o1sksc unique (lead_number);

    alter table organizations
       add constraint UKkamtsqvsul6leg7svd95mmydq unique (code);

    alter table organizations
       add constraint UKp9pbw3flq9hkay8hdx3ypsldy unique (name);

    alter table users
       add constraint UKr43af9ap4edm43mmtq01oddj6 unique (username);

    alter table users
       add constraint UK6dotkott2kjsp8vw4d0m25fb7 unique (email);

    alter table whatsapp_configurations
       add constraint UK6ywt1rt84pohkdwutrniny5xl unique (phone_number_id);

    -- Backs paginated conversation-history reads (WHERE conversation_id = ?
    -- ORDER BY created_at ... LIMIT n) — stays fast at any table size.
    create index idx_conv_company_branch_lastmsg
       on whatsapp_conversations (company_id, branch_id, last_message_at);

    -- id is a secondary sort key to keep pagination deterministic when
    -- multiple messages land on the same created_at (e.g. a webhook burst).
    create index idx_msg_conversation_created
       on whatsapp_messages (conversation_id, created_at, id);

    -- DB-level backstop for webhook-retry idempotency (Meta may redeliver
    -- the same inbound message); app layer also checks findByWamid first.
    alter table whatsapp_messages
       add constraint idx_msg_wamid unique (wamid);

    alter table branch_trusted_ips
       add constraint FKkc6f6rpf5wg20jcbidi8vnewc
       foreign key (branch_id)
       references branches (id);

    alter table branches
       add constraint FKjuiihymin83oadf71ou3oelya
       foreign key (company_id)
       references companies (id);

    alter table companies
       add constraint FKt9da2s6cqp0e3fb5ke7qrm3c5
       foreign key (organization_id)
       references organizations (id);

    alter table company_lead_settings
       add constraint FKtam4btrqgv3dw5w16gqt9l4lu
       foreign key (company_id)
       references companies (id);

    alter table company_lead_settings
       add constraint FKisfld8965tax40qmfsc68423j
       foreign key (default_status_id)
       references lead_statuses (id);

    alter table customer_change_logs
       add constraint FKpfy2sgvtt436dgfo37tbveass
       foreign key (customer_id)
       references customers (id);

    alter table customer_change_logs
       add constraint FKhwax1u5k7qfwctko0qrri2n6h
       foreign key (performed_by_id)
       references users (id);

    alter table customers
       add constraint FKl9seru5curo311xhtpbxe3074
       foreign key (company_id)
       references companies (id);

    alter table customers
       add constraint FKnhs4l6e5qrcwuk7g79feta2c0
       foreign key (home_branch_id)
       references branches (id);

    alter table customers
       add constraint FK9og6ij26kjy80lip7druo2hle
       foreign key (organization_id)
       references organizations (id);

    alter table dynamic_field_definitions
       add constraint FK9swlix16qho4yj1h8kg1dwr7w
       foreign key (company_id)
       references companies (id);

    alter table lead_products
       add constraint FKevoycx65j3v9mj8mf80eg9sq8
       foreign key (lead_id)
       references leads (id);

    alter table lead_products
       add constraint FKcbrpylmdkk1k4p113iqi7c1nv
       foreign key (product_id)
       references product_masters (id);

    alter table lead_purposes
       add constraint FK4g39i27u3rk2gv65tuwmxptv9
       foreign key (company_id)
       references companies (id);

    alter table lead_sources
       add constraint FKs3xeu1bg63lhka2kcgflivgwx
       foreign key (company_id)
       references companies (id);

    alter table lead_statuses
       add constraint FKp5hsyayw9c0snijbq00m5y8l9
       foreign key (company_id)
       references companies (id);

    alter table lead_timeline_activities
       add constraint FKcxas7h0l802k8qgpjuygexflb
       foreign key (lead_id)
       references leads (id);

    alter table lead_timeline_activities
       add constraint FKjmlgq5psj3hy0uv2e4v396g0a
       foreign key (performed_by_id)
       references users (id);

    alter table leads
       add constraint FKr3b8qovv2mogf9akd0lwhrx1w
       foreign key (assigned_by_id)
       references users (id);

    alter table leads
       add constraint FKjebse43efn0rhjmgrkdncxyqr
       foreign key (assigned_to_id)
       references users (id);

    alter table leads
       add constraint FKf7io6c142t6qdqu2ufgt5cqdq
       foreign key (branch_id)
       references branches (id);

    alter table leads
       add constraint FKtgb8clsgg6l183toravpxgfad
       foreign key (company_id)
       references companies (id);

    alter table leads
       add constraint FKtha9i86g8pjibocaxfvy8iysu
       foreign key (customer_id)
       references customers (id);

    alter table leads
       add constraint FKffyljuvcjw76le1mw3mb5uvxc
       foreign key (organization_id)
       references organizations (id);

    alter table leads
       add constraint FK609plubg1u9tkrhfgk0ftxydt
       foreign key (purpose_id)
       references lead_purposes (id);

    alter table leads
       add constraint FK5q2k3ehl603xllc15kpx1mcjy
       foreign key (source_id)
       references lead_sources (id);

    alter table leads
       add constraint FKhf0937gd5pwqpbybs3621fq1l
       foreign key (status_id)
       references lead_statuses (id);

    alter table login_otps
       add constraint FKoff9otupd3pb4gpp497x1et15
       foreign key (user_id)
       references users (id);

    alter table product_masters
       add constraint FK5fxgr98hyrmy5c8a62x5m741n
       foreign key (company_id)
       references companies (id);

    alter table role_permissions
       add constraint FKn5fotdgk8d1xvo8nav9uv3muc
       foreign key (role_id)
       references roles (id);

    alter table roles
       add constraint FKqjj9a6xa11cu9ch24cjo4a7lc
       foreign key (organization_id)
       references organizations (id);

    alter table user_branch_mappings
       add constraint FK3txhifsxapwsbm9c1o3sdti3p
       foreign key (branch_id)
       references branches (id);

    alter table user_branch_mappings
       add constraint FK2n8tjbih0piqg54ioq8uoee70
       foreign key (user_id)
       references users (id);

    alter table user_company_mappings
       add constraint FKbmcmucje1l7x3cmchr5st45vv
       foreign key (company_id)
       references companies (id);

    alter table user_company_mappings
       add constraint FKbg5n491g9orfo2hr2bo7vikk
       foreign key (user_id)
       references users (id);

    alter table users
       add constraint FKqpugllwvyv37klq7ft9m8aqxk
       foreign key (organization_id)
       references organizations (id);

    alter table users
       add constraint FKp56c1712k691lhsyewcssf40f
       foreign key (role_id)
       references roles (id);

    alter table whatsapp_configurations
       add constraint FKhg7pmtsxj024cxltpesdn8uc7
       foreign key (branch_id)
       references branches (id);

    alter table whatsapp_configurations
       add constraint FK2w3x1t8khy34aeb4tr645pbmv
       foreign key (company_id)
       references companies (id);

    alter table whatsapp_conversations
       add constraint FKp77959xkud0qqf16lf0m4x2ta
       foreign key (assigned_agent_id)
       references users (id);

    alter table whatsapp_conversations
       add constraint FK98xlsyrnm29gnkcei7i6ufq5a
       foreign key (branch_id)
       references branches (id);

    alter table whatsapp_conversations
       add constraint FKqbvfak83ejvq1sv4rr31yeda4
       foreign key (company_id)
       references companies (id);

    alter table whatsapp_conversations
       add constraint FKbe3ntnlgv123l9qlwm7mbedc
       foreign key (lead_id)
       references leads (id);

    alter table whatsapp_messages
       add constraint FKfj4fg3sm4iktqd88p5u695ax2
       foreign key (conversation_id)
       references whatsapp_conversations (id);

    alter table whatsapp_messages
       add constraint FK1ofvx01xrnpwuyumrpb4ciie7
       foreign key (sent_by_user_id)
       references users (id);
