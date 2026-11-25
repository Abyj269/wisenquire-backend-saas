package com.leads.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 01 Part B item 9: every POST/PUT/PATCH/DELETE handled by an annotated controller is written to
 * {@code audit_log} (who, which org, what, which record, outcome, IP, trace id), with secrets in the
 * request body masked. Reads are not audited. See {@link AuditAspect}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Audited {
    /** {@code audit_log.entity_type}, e.g. "tenant", "lead-settings". */
    String entity();
}
