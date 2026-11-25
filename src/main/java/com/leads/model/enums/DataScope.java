package com.leads.model.enums;

/**
 * D14 (01 Part B item 8). LEVEL = today's behaviour (ORGANIZATION / COMPANY / BRANCH per RoleLevel).
 * OWN = additionally only the records assigned to (or created by) the user; enforced only when flag
 * own_scope.enforced is on for the organization. Existing roles are LEVEL (V54).
 */
public enum DataScope {
    LEVEL,
    OWN
}
