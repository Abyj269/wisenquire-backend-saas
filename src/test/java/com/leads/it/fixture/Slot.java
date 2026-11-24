package com.leads.it.fixture;

/**
 * A branch in the D3 fixture: 2 orgs (A, B) x 2 companies (1, 2) x 2 branches (a, b).
 * {@code A1a} = org A, company A1, branch A1a. Personas live in A1 / A1a (see {@link Persona}).
 * Orgs S and P exist only for the STATE rows.
 */
public enum Slot {
    A1a, A1b, A2a, A2b, B1a, B1b, B2a, B2b,
    /** STATE rows (01 Part B item 2): org S is SUSPENDED, org P is PAST_DUE past its grace period. One company, one branch each. */
    S1a, P1a;

    public char org() { return name().charAt(0); }
    /** "A1", "B2", ... */
    public String company() { return name().substring(0, 2); }
}
