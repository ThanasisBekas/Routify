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

Copy the appropriate file to the project root before starting services:

```bash
cp environments/.env.develop .env
docker compose --env-file .env up -d
```

## Variables

| Variable | Description |
|---|---|
| `DB_PASS` | PostgreSQL password for the `routify` user |
| `RABBITMQ_PASS` | RabbitMQ password for the `routify` user |
| `JWT_PRIVATE_KEY` | RSA-2048 private key (PKCS8, DER, base64) — used by identity-service to sign JWTs |
| `JWT_PUBLIC_KEY` | RSA-2048 public key (SPKI, DER, base64) — used by all services to verify JWTs |
| `CERT_VAULT_ENCRYPTION_KEY` | AES-256 key (32 bytes, base64) — used by cert-vault to encrypt certificate material |
| `ADMIN_INITIAL_PASSWORD` | Initial password for the seeded admin user (identity-service `DataSeeder`) |
| `GRAFANA_PASSWORD` | Grafana admin password |
| `OPENAI_API_KEY` | OpenAI API key — shared across all branches; sourced from the repository secret |

