# Backend container: build, migrate, run, probes (plan Phase 0, Step 3.1)

Written for: whoever deploys or operates the SaaS container (owner, ops).
The live `wiseNquire_saas_live` WAR on Tomcat is **not** affected by anything here.

## 1. Build
From `wse_leads_saas_backend/`:
```
mvn -B clean package -Psaas-jar -Dmaven.compiler.release=17     # -> target/wisenquire-saas.jar
docker build -t wisenquire-backend:<tag> .
```
The default build (`mvn -B clean package -Dapp.war.name=wiseNquire_saas_live -Dmaven.compiler.release=17`) still produces the Tomcat WAR, unchanged.

## 2. Environment
The image starts with `SPRING_PROFILES_ACTIVE=prod`. `ProductionSafetyConfiguration` refuses to start unless these are set:

| Variable | Notes |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | MySQL 8. The JDBC URL uses `sslMode=REQUIRED` |
| `JWT_SECRET` | At least 64 characters, not the default |
| `SUPER_ADMIN_EMAIL`, `SUPER_ADMIN_PASSWORD` | The password needs at least 16 characters |
| `ADS_CREDENTIALS_ENCRYPTION_KEY` | Base64, 32 bytes, not the dev key |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | Real SMTP credentials |
| `ALLOWED_ORIGINS` | Comma-separated FE origins |

Optional:
- `HIBERNATE_STATISTICS=true`: staging/baseline only.
- `WHATSAPP_VERIFY_TOKEN`.

## 3. Migrate first (pre-deploy step)
App containers run with `SPRING_FLYWAY_ENABLED=false`. Run Flyway once per release **before** starting or rolling the app containers:
```
docker run --rm --env-file prod.env --entrypoint java wisenquire-backend:<tag> \
  -cp /app/app.jar -Dloader.main=com.leads.tools.MigrateMain \
  org.springframework.boot.loader.launch.PropertiesLauncher
```
- `--entrypoint java` is required. Without it, the arguments are appended to the image's `java -jar /app/app.jar`, which starts the **app** with Flyway off instead of migrating. The app then fails with `Schema-validation: missing table …`.
- It exits 0 on success and prints the schema version.
- It uses only the DB variables. `DB_URL` overrides the built URL.

## 4. Run
```
docker run -d --name wisenquire-api --env-file prod.env -p 8081:8081 wisenquire-backend:<tag>
```
- Publish **only 8081**. The API base is `http://<host>:8081/api/v1`.
- Port **9090** (management: health and Prometheus) must stay on the internal network.

## 5. Probes and metrics
| Purpose | URL |
|---|---|
| Liveness | `http://<host>:9090/actuator/health/liveness` |
| Readiness (includes the DB) | `http://<host>:9090/actuator/health/readiness` |
| Prometheus | `http://<host>:9090/actuator/prometheus` |
| Public health | WAR only: `<tomcat>/<context>/api/v1/actuator/health`. In the container, health is served only on 9090 |

## 6. Timezone check (R20)
```
docker run --rm --entrypoint date wisenquire-backend:<tag> +%Z%z             # expect +04 / +0400
docker run --rm --entrypoint java wisenquire-backend:<tag> -XshowSettings:properties -version 2>&1 | grep user.timezone   # Asia/Dubai
```
CI runs both checks on every push (`.github/workflows/backend-ci.yml`, job `container`).

## 7. Rollback
Redeploy the previous image tag. Migrations are additive (expand/contract), so the previous image runs against the migrated schema.
