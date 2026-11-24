package com.leads.flags;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leads.config.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * D13 flag resolution: the org's override, else the global default ({@code feature_flags}).
 * <ul>
 *   <li>{@link #isOn(FeatureFlag)}: the current request's org (from {@link TenantContext}, i.e. the
 *       verified JWT). No org (anonymous, platform super admin) resolves to the global default.</li>
 *   <li>{@link #isOn(FeatureFlag, Long)}: jobs and webhooks pass the org explicitly.</li>
 * </ul>
 * Cached per org in Caffeine for 60 s and evicted on write on this instance; other instances catch
 * up within the TTL until a pub/sub eviction exists (same mechanism as S1).
 * <p>
 * Flags are operational switches, never authorization: they may hide or change a feature, but
 * every access check still goes through TenantAccess.
 */
@Slf4j
@Service
public class FeatureFlags {

    private static final Long GLOBAL = -1L;

    private final JdbcTemplate jdbc;
    private final Cache<Long, Map<FeatureFlag, Boolean>> cache;

    public FeatureFlags(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(50_000).build();
    }

    public boolean isOn(FeatureFlag flag) {
        return isOn(flag, TenantContext.get().map(TenantContext.Data::organizationId).orElse(null));
    }

    public boolean isOn(FeatureFlag flag, Long organizationId) {
        return resolved(organizationId).getOrDefault(flag, false);
    }

    /** Every flag resolved for one org (null = global defaults). */
    public Map<FeatureFlag, Boolean> resolved(Long organizationId) {
        return cache.get(organizationId == null ? GLOBAL : organizationId, this::load);
    }

    /**
     * Sets (or replaces) one org's override and evicts that org's cache entry.
     * The audited platform API arrives with module 21; until then only tests and ops scripts call this.
     */
    public void setOverride(FeatureFlag flag, long organizationId, boolean enabled, String setBy, String reason) {
        jdbc.update("INSERT INTO feature_flag_overrides (flag_key, organization_id, enabled, set_by, reason) VALUES (?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), set_by = VALUES(set_by), reason = VALUES(reason), set_at = CURRENT_TIMESTAMP(6)",
                flag.key, organizationId, enabled, setBy, reason);
        cache.invalidate(organizationId);
    }

    public void clearOverride(FeatureFlag flag, long organizationId) {
        jdbc.update("DELETE FROM feature_flag_overrides WHERE flag_key = ? AND organization_id = ?", flag.key, organizationId);
        cache.invalidate(organizationId);
    }

    /** Changing a global default must evict every org, because each cached map includes the defaults. */
    public void setDefault(FeatureFlag flag, boolean enabled) {
        jdbc.update("UPDATE feature_flags SET enabled_default = ? WHERE flag_key = ?", enabled, flag.key);
        cache.invalidateAll();
    }

    private Map<FeatureFlag, Boolean> load(Long organizationId) {
        Map<FeatureFlag, Boolean> out = new EnumMap<>(FeatureFlag.class);
        jdbc.query("SELECT flag_key, enabled_default FROM feature_flags", rs -> {
            String key = rs.getString(1);
            boolean enabled = rs.getBoolean(2);
            Optional<FeatureFlag> flag = FeatureFlag.byKey(key);
            if (flag.isPresent()) out.put(flag.get(), enabled);
            else log.warn("feature_flags has a key with no FeatureFlag enum constant: {}", key);
        });
        if (!GLOBAL.equals(organizationId)) {
            jdbc.query("SELECT flag_key, enabled FROM feature_flag_overrides WHERE organization_id = ?", rs -> {
                String key = rs.getString(1);
                boolean enabled = rs.getBoolean(2);
                FeatureFlag.byKey(key).ifPresent(f -> out.put(f, enabled));
            }, organizationId);
        }
        return Collections.unmodifiableMap(out);
    }
}
