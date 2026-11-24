package com.leads.service;

import com.leads.model.entity.User;

/**
 * 01 Part B item 1: the authenticated user, loaded once per request (or per STOMP frame) by the
 * JWT filter / STOMP interceptor, so {@link TenantAccess#actor()} does not repeat findByEmail
 * (P-1: about 3+ per request before this).
 * <ul>
 *   <li>Thread-bound; cleared by RequestTraceFilter at request end and after every STOMP frame.</li>
 *   <li>The user is loaded with its role, permissions, organization and allowed companies/branches
 *       in one query, so it is safe to use detached (the filter runs before the request's
 *       persistence context opens).</li>
 *   <li>{@link #clear()} must be called when the user's session version changes within the request
 *       (an admin editing their own access), so the next actor() re-reads it.</li>
 *   <li>Kill switch: {@code app.tenant.actor-memo.enabled=false} (then nothing is stored).</li>
 * </ul>
 */
public final class ActorMemo {

    private record Entry(String email, User user) { }

    private static final ThreadLocal<Entry> CURRENT = new ThreadLocal<>();

    private ActorMemo() { }

    public static void put(String email, User user) {
        if (email != null && user != null) CURRENT.set(new Entry(email, user));
    }

    /** The memoised user if it was loaded for this email on this thread, else null. */
    public static User get(String email) {
        Entry e = CURRENT.get();
        return e != null && e.email().equals(email) ? e.user() : null;
    }

    public static void clear() { CURRENT.remove(); }
}
