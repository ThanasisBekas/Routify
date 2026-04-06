# environments/

Branch-scoped `.env` files for the Routify platform.

## Naming convention

Each file is named `.env.<branch>` where `<branch>` is the source branch name with `/` replaced by `-`:

| File | Source branch |
|---|---|
| `.env.develop` | `develop` |
| `.env.master` | `master` |
| `.env.release-1` | `release/1` |
| `.env.feature-ai-integration` | `feature/ai-integration` |

## How files are generated

Run the **"Generate .env"** GitHub Actions workflow (`Actions → Generate .env → Run workflow`).

- **Input:** the source branch name (e.g. `release/1`)
- **Output:** `environments/.env.<branch>` committed to `develop`
- Credentials are randomly generated on every run (RSA-2048 keypair, AES-256 key, random passwords)
- `OPENAI_API_KEY` is sourced from the `OPENAI_API_KEY` repository secret and is never rotated

## Usage

### Docker Compose

Copy the appropriate file to the project root before starting services:

```bash
cp environments/.env.develop .env
docker compose --env-file .env up -d
```

### IntelliJ Run Configurations

All `.run/*.run.xml` configurations use IntelliJ's `<envFilePaths>` element to load
`$PROJECT_DIR$/environments/.env.develop` at launch — **no manual copying is required**.

Non-secret, static variables (localhost addresses, ports, DB name/user) are still
declared inline in the run configs so they are visible in version control.
Secret variables (`DB_PASS`, `RABBITMQ_PASS`, `REDIS_PASS`, `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY`,
`CERT_VAULT_ENCRYPTION_KEY`, `ADMIN_INITIAL_PASSWORD`, `OPENAI_API_KEY`) come
exclusively from the env file.

If the file is missing, generate it via the **"Generate .env"** workflow or copy your
root `.env`:

```bash
cp .env environments/.env.develop
```

## Variables

| Variable | Description |
|---|---|
| `DB_PASS` | PostgreSQL password for the `routify` user |
| `RABBITMQ_PASS` | RabbitMQ password for the `routify` user |
| `REDIS_PASS` | Redis password (`--requirepass`) — used by all services that connect to Redis |
| `JWT_PRIVATE_KEY` | RSA-2048 private key (PKCS8, DER, base64) — used by identity-service to sign JWTs |
| `JWT_PUBLIC_KEY` | RSA-2048 public key (SPKI, DER, base64) — used by all services to verify JWTs |
| `CERT_VAULT_ENCRYPTION_KEY` | AES-256 key (32 bytes, base64) — used by cert-vault to encrypt certificate material |
| `ADMIN_INITIAL_PASSWORD` | Initial password for the seeded admin user (identity-service `DataSeeder`) |
| `GRAFANA_PASSWORD` | Grafana admin password |
| `OPENAI_API_KEY` | OpenAI API key — shared across all branches; sourced from the repository secret |

