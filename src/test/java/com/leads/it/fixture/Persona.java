package com.leads.it.fixture;

import com.leads.model.enums.RoleLevel;

/**
 * D3 personas, created in both orgs ({@code ORG_ADMIN} of A and of B, ...).
 * Scope: COMPANY_MGR -> company x1; BRANCH_MGR and both agents -> branch x1a.
 * <p>
 * {@code AGENT_OWN_1/2} are BRANCH-level users today. The OWN data scope (D14,
 * {@code roles.data_scope}) arrives in 01 Part B; until then they see the whole branch,
 * and the OWN-* matrix rows are parked (see IsolationMatrixIT).
 * {@code PLATFORM_ADMIN} arrives in Phase 2.
 */
public enum Persona {
    ORG_ADMIN(RoleLevel.ORGANIZATION),
    COMPANY_MGR(RoleLevel.COMPANY),
    BRANCH_MGR(RoleLevel.BRANCH),
    AGENT_OWN_1(RoleLevel.BRANCH),
    AGENT_OWN_2(RoleLevel.BRANCH);

    public final RoleLevel level;

    Persona(RoleLevel level) { this.level = level; }
}
