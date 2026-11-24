# WiseNquire backend: container image for the SaaS deployment (plan Phase 0, D10).
# The live Tomcat WAR (wiseNquire_saas_live) is NOT built from this file.
#
# Build (from wse_leads_saas_backend/):
#   mvn -B clean package -Psaas-jar -Dmaven.compiler.release=17   -> target/wisenquire-saas.jar
#   docker build -t wisenquire-backend:dev .
# Run, migrate and probes: see docker/README.md.

FROM eclipse-temurin:17-jre-jammy

# R20 / D15: the code stores and compares Asia/Dubai wall-clock times. The container
# default (UTC) would silently shift every LocalDateTime.now() by -4 h.
ENV TZ=Asia/Dubai

# Non-root user. /app is writable for the local WhatsApp media cache
# (relative dir "whatsapp-media-cache") until S13 moves media to object storage.
RUN groupadd --system app \
 && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app \
 && mkdir -p /app/whatsapp-media-cache \
 && chown -R app:app /app

WORKDIR /app
COPY --chown=app:app target/wisenquire-saas.jar /app/app.jar
USER app

# - user.timezone: belt and braces with TZ above (R20).
# - Flyway is a separate pre-deploy step (com.leads.tools.MigrateMain), not run by app pods.
# - Management port 9090: health + Prometheus for the internal network only. Never publish it.
# - Log lines carry the tenant MDC (orgId, userId, traceId).
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Dubai -XX:MaxRAMPercentage=75" \
    SPRING_PROFILES_ACTIVE=prod \
    SPRING_FLYWAY_ENABLED=false \
    MANAGEMENT_SERVER_PORT=9090 \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,prometheus \
    LOGGING_PATTERN_LEVEL="%5p [org=%X{orgId:-} user=%X{userId:-} trace=%X{traceId:-}]"

# 8081 = API (context path /api/v1); 9090 = management (internal only).
EXPOSE 8081 9090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
