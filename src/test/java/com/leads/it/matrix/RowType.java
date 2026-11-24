package com.leads.it.matrix;

/** Plan D3 row types. The ones marked "parked" need 01 Part B and are not generated yet. */
public enum RowType {
    /** Control row: the persona on its own branch must succeed, so a denial is never just a broken request. */
    ALLOWED,
    /** No token. */
    ANON,
    /** Any org-A persona -> org-B record / companyId / branchId. */
    X_ORG,
    /** COMPANY_MGR (A1) -> company A2. */
    X_COMPANY,
    /** BRANCH_MGR (A1a) -> branch A1b. */
    X_BRANCH,
    /** BRANCH_MGR (A1a) -> a company-wide record or report of its own company (F-1). */
    X_COMPANY_WIDE,
    /** Parked until D14 (roles.data_scope, Part B item 8). */
    OWN_RECORD, OWN_LIST, OWN_AGG, OWN_WRITE,
    /** Parked until V51 org status (Part B item 2). */
    STATE
}
