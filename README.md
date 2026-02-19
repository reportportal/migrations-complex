# Migrations Complex

A ReportPortal service that runs database and storage migrations: API keys migration, multi-bucket to single-bucket consolidation, and MinIO-to-S3 transfer. Use it when upgrading to ReportPortal 23.3+ or changing your binary storage layout.

---

## Table of contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Migration types at a glance](#migration-types-at-a-glance)
- [Installation](#installation)
- [Migration 1: Access tokens → API keys](#migration-1-access-tokens--api-keys)
- [Migration 2: Multi-bucket → single bucket](#migration-2-multi-bucket--single-bucket)
- [Migration 3: MinIO single-bucket → S3 single-bucket](#migration-3-minio-single-bucket--s3-single-bucket)
- [End-to-end: MinIO to S3](#end-to-end-minio-to-s3)
- [After migration: switching ReportPortal to S3](#after-migration-switching-reportportal-to-s3)
- [Running Migrations with Docker Compose](#running-migrations-with-docker-compose)
- [License](#license)

---

## Overview

**Migrations Complex** runs one or more migrations in a single execution. You enable the migrations you need via environment variables and deploy the service next to (or instead of) ReportPortal.

| What you get | Use case |
|--------------|----------|
| **API keys migration** | Upgrade from pre-23.3 to 23.3+ (OAuth tokens → API keys). |
| **Multi-bucket → single bucket** | Merge project buckets (e.g. `prj-1`, `prj-2`, `rp-bucket`) into one bucket (MinIO or S3). |
| **MinIO → S3** | Copy data from one MinIO bucket to one S3 bucket. |

You can run migrations separately or together. For a full move from MinIO to S3, run multi-bucket → single bucket first, then MinIO → S3.

---

## Prerequisites

- ReportPortal deployment (or its database and storage available).
- **Database**: PostgreSQL host, user, password, and database name.
- **Storage** (for storage migrations): MinIO and/or S3 credentials and bucket names.
- **Backup**: Take a database backup before any migration.

For **Kubernetes/Helm** deployments, use the [Helm chart](charts/README.md) instead; it uses the same environment variables under the hood (see [charts/values.yaml](charts/values.yaml) for the mapping).

---

## Migration types at a glance

| Migration | Downtime | Main env flag |
|-----------|----------|----------------|
| Access tokens → API keys | Yes (stop ReportPortal) | `RP_TOKEN_MIGRATION=true` |
| Multi-bucket → single bucket | Yes (stop ReportPortal) | `RP_SINGLEBUCKET_MIGRATION=true` |
| MinIO → S3 (single bucket) | No | `RP_MINIO_S3_MIGRATION=true` |

---

## Installation

Add the service to your Docker Compose stack and configure it with environment variables. The variable names below match what the application (and the [Helm chart](charts/values.yaml)) expect. Replace placeholders with your real values.

**Minimal example** (database only, e.g. for API keys migration):

```yaml
services:
  migrations-complex:
    image: reportportal/migrations-complex:latest
    environment:
      RP_DB_HOST: postgres
      RP_DB_USER: rpuser
      RP_DB_PASS: your-db-password
      RP_DB_NAME: reportportal
      # Enable the migrations you need (see sections below):
      # RP_TOKEN_MIGRATION: "true"
      # RP_SINGLEBUCKET_MIGRATION: "true"
      # RP_MINIO_S3_MIGRATION: "true"
```

Default bucket names used in examples (same as [charts/values.yaml](charts/values.yaml)): project prefix `prj-`, plugins bucket `rp-bucket`, single bucket `rp-storage`, S3 destination `rp-s3-storage`.

Then start the stack:

```bash
docker compose up -d migrations-complex
```

Check logs to confirm the migration finished:

```bash
docker compose logs -f migrations-complex
```

---

## Migration 1: Access tokens → API keys

**When to use:** Upgrading from ReportPortal older than 23.3 to 23.3 or newer. The new version uses API keys instead of OAuth access tokens; this migration converts existing tokens in the database.

> **Warning:** This migration is **irreversible**. It drops the `oauth_access_token` table and removes all access tokens. Users will need to generate new API keys. Back up the database first.

**Steps:**

1. **Stop ReportPortal** (or at least ensure no one is using existing tokens during the migration).

2. Add **migrations-complex** to your stack and set:

```yaml
environment:
  RP_TOKEN_MIGRATION: "true"
  RP_DB_HOST: postgres
  RP_DB_USER: rpuser
  RP_DB_PASS: your-db-password
  RP_DB_NAME: reportportal
```

3. Start the migration service:

```bash
docker compose up -d migrations-complex
docker compose logs -f migrations-complex
```

4. When the job completes, restart ReportPortal. Users can create new API keys from the UI.

**Example (fragment):**

```yaml
migrations-complex:
  image: reportportal/migrations-complex:latest
  environment:
    RP_TOKEN_MIGRATION: "true"
    RP_DB_HOST: postgres
    RP_DB_USER: rpuser
    RP_DB_PASS: "${POSTGRES_PASSWORD}"
    RP_DB_NAME: reportportal
```

---

## Migration 2: Multi-bucket → single bucket

**When to use:** You have multiple buckets (e.g. `prj-1`, `prj-2`, `rp-bucket`) and want one consolidated bucket. This is often the first step before [MinIO → S3](#migration-3-minio-single-bucket--s3-single-bucket).

> **Warning:** ReportPortal must be **stopped** during this migration; the attachments table is blocked. Plan for downtime.

**Steps:**

1. **Stop ReportPortal.**

2. Add **migrations-complex** with database and storage variables.

3. Choose **MinIO** or **S3** as the destination and set the variables for that backend.

**Option A — Destination: MinIO** (matches `migrations.storage.multiBucketToSingleBucket.destinationType: minio` and `storage.minio` in the chart):

```yaml
environment:
  RP_SINGLEBUCKET_MIGRATION: "true"
  RP_DB_HOST: postgres
  RP_DB_USER: rpuser
  RP_DB_PASS: your-db-password
  RP_DB_NAME: reportportal
  DATASTORE_TYPE: minio
  DATASTORE_ACCESSKEY: minioadmin
  DATASTORE_SECRETKEY: minioadmin
  DATASTORE_ENDPOINT: http://minio:9000
  DATASTORE_BUCKETPREFIX: prj-
  DATASTORE_DEFAULTBUCKETNAME: rp-bucket
  DATASTORE_SINGLEBUCKETNAME: rp-storage
  # Optional: remove source buckets after migration (chart: removeSourceBuckets)
  # DATASTORE_REMOVE_AFTER_MIGRATION: "true"
```

**Option B — Destination: S3** (matches `destinationType: s3` and `storage.s3` in the chart):

```yaml
environment:
  RP_SINGLEBUCKET_MIGRATION: "true"
  RP_DB_HOST: postgres
  RP_DB_USER: rpuser
  RP_DB_PASS: your-db-password
  RP_DB_NAME: reportportal
  DATASTORE_TYPE: s3
  DATASTORE_ACCESSKEY: your-aws-access-key
  DATASTORE_SECRETKEY: your-aws-secret-key
  DATASTORE_REGION: eu-central-1
  DATASTORE_BUCKETPREFIX: prj-
  DATASTORE_DEFAULTBUCKETNAME: rp-bucket
  DATASTORE_SINGLEBUCKETNAME: rp-storage
```

4. Deploy and monitor:

```bash
docker compose up -d migrations-complex
docker compose logs -f migrations-complex
```

5. After completion, reconfigure ReportPortal to use the new single bucket, then start ReportPortal again.

---

## Migration 3: MinIO single-bucket → S3 single-bucket

**When to use:** You already have one MinIO bucket (e.g. after [Migration 2](#migration-2-multi-bucket--single-bucket)) and want to copy its contents to an S3 bucket. ReportPortal can keep running; you can switch it to S3 and let the migration run in the background.

**Steps:**

1. **Create the target S3 bucket** in AWS (or your S3-compatible storage).

2. Add **migrations-complex** with MinIO (source) and S3 (destination) settings. Bucket names match chart defaults (`migrations.storage.minioToS3.buckets`):

```yaml
environment:
  RP_MINIO_S3_MIGRATION: "true"
  MINIO_ENDPOINT: http://minio:9000
  MINIO_ACCESS_KEY: minioadmin
  MINIO_SECRET_KEY: minioadmin
  S3_ENDPOINT: https://s3.eu-central-1.amazonaws.com
  S3_ACCESS_KEY: your-aws-access-key
  S3_SECRET_KEY: your-aws-secret-key
  MINIO_SINGLE_BUCKET: rp-storage
  S3_SINGLE_BUCKET: rp-s3-storage
```

3. Deploy and monitor:

```bash
docker compose up -d migrations-complex
docker compose logs -f migrations-complex
```

4. When the copy is done, [switch ReportPortal to S3](#after-migration-switching-reportportal-to-s3) and remove or reconfigure the migration service.

---

## End-to-end: MinIO to S3

To move from an existing MinIO multi-bucket setup to a single S3 bucket:

1. **Create the S3 bucket** you will use as the final destination.

2. **Run [Migration 2](#migration-2-multi-bucket--single-bucket)** (multi-bucket → single bucket) with **MinIO** as the destination.  
   - Stop ReportPortal, run the migration, then reconfigure ReportPortal to use the new single MinIO bucket and start it again.

3. **Run [Migration 3](#migration-3-minio-single-bucket--s3-single-bucket)** (MinIO → S3).  
   - You can switch ReportPortal to S3 and start it; the migration can run in parallel.

4. **After Migration 3 completes**, ensure all services use the S3 bucket and single-bucket settings, then remove or disable migrations-complex.

> **Note:** Incorrect order or configuration can break integrations. Test in a non-production environment first and keep a database backup.

---

## After migration: switching ReportPortal to S3

When storage has been migrated to S3, point ReportPortal services (e.g. **api**, **authorization**, **jobs**) to S3 and enable the single-bucket flag. Use the same S3 bucket name you set as `S3_SINGLE_BUCKET` (e.g. `rp-s3-storage`):

```yaml
# Example for api, authorization, jobs
environment:
  DATASTORE_TYPE: s3
  DATASTORE_REGION: eu-central-1
  DATASTORE_ACCESSKEY: your-aws-access-key
  DATASTORE_SECRETKEY: your-aws-secret-key
  DATASTORE_DEFAULTBUCKETNAME: rp-s3-storage
  RP_FEATURE_FLAGS: singleBucket
```

Then redeploy those services with the new configuration.

---

# Running Migrations with Docker Compose

Below is the recommended Docker Compose setup aligned with Helm chart parameters.

## 1. docker-compose.yaml

```yaml
version: "3.9"

services:
  migrations-complex:
    image: reportportal/migrations-complex:1.0.0
    environment:
      RP_TOKEN_MIGRATION: ${RP_TOKEN_MIGRATION}
      RP_SINGLEBUCKET_MIGRATION: ${RP_SINGLEBUCKET_MIGRATION}
      RP_MINIO_S3_MIGRATION: ${RP_MINIO_S3_MIGRATION}
      RP_DB_HOST: ${RP_DB_HOST}
      RP_DB_USER: ${RP_DB_USER}
      RP_DB_PASS: ${RP_DB_PASS}
      RP_DB_NAME: ${RP_DB_NAME}
      DATASTORE_TYPE: ${DATASTORE_TYPE}
      DATASTORE_REMOVE_AFTER_MIGRATION: ${DATASTORE_REMOVE_AFTER_MIGRATION}
      DATASTORE_BUCKETPREFIX: ${DATASTORE_BUCKETPREFIX}
      DATASTORE_DEFAULTBUCKETNAME: ${DATASTORE_DEFAULTBUCKETNAME}
      DATASTORE_SINGLEBUCKETNAME: ${DATASTORE_SINGLEBUCKETNAME}
      MINIO_ENDPOINT: ${MINIO_ENDPOINT}
      MINIO_ACCESS_KEY: ${MINIO_ACCESS_KEY}
      MINIO_SECRET_KEY: ${MINIO_SECRET_KEY}
      MINIO_SINGLE_BUCKET: ${MINIO_SINGLE_BUCKET}
      MINIO_USE_SSL: ${MINIO_USE_SSL}
      DATASTORE_REGION: ${DATASTORE_REGION}
      S3_ENDPOINT: ${S3_ENDPOINT}
      S3_ACCESS_KEY: ${S3_ACCESS_KEY}
      S3_SECRET_KEY: ${S3_SECRET_KEY}
      S3_SINGLE_BUCKET: ${S3_SINGLE_BUCKET}
      S3_USE_SSL: ${S3_USE_SSL}
    restart: "no"
```

---

## 2. `.env` Template

```env
RP_TOKEN_MIGRATION=false
RP_SINGLEBUCKET_MIGRATION=false
RP_MINIO_S3_MIGRATION=false
RP_DB_HOST=postgres
RP_DB_USER=rpuser
RP_DB_PASS=
RP_DB_NAME=reportportal
DATASTORE_TYPE=minio
DATASTORE_REMOVE_AFTER_MIGRATION=false
DATASTORE_BUCKETPREFIX=prj-
DATASTORE_DEFAULTBUCKETNAME=rp-bucket
DATASTORE_SINGLEBUCKETNAME=rp-storage
MINIO_ENDPOINT=http://minio:9000
MINIO_ACCESS_KEY=
MINIO_SECRET_KEY=
MINIO_USE_SSL=false
MINIO_SINGLE_BUCKET=rp-storage
DATASTORE_REGION=eu-central-1
S3_ENDPOINT=https://s3.eu-central-1.amazonaws.com
S3_ACCESS_KEY=
S3_SECRET_KEY=
S3_SINGLE_BUCKET=rp-s3-storage
S3_USE_SSL=true
```

---

## 3. Running

```bash
docker compose up migrations-complex
docker compose logs -f migrations-complex
```

The container stops automatically when the migration finishes.

---

## License

Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). See the [LICENSE](LICENSE) file in the repository.
