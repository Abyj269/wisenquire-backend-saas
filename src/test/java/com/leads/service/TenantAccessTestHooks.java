package com.leads.service;

import java.lang.reflect.Field;

/** Test-only peek at TenantAccess's system scope (a private thread-local). */
public final class TenantAccessTestHooks {
    private TenantAccessTestHooks() { }

    @SuppressWarnings("unchecked")
    public static Long systemCompany() {
        try {
            Field f = TenantAccess.class.getDeclaredField("SYSTEM_COMPANY");
            f.setAccessible(true);
            return ((ThreadLocal<Long>) f.get(null)).get();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
