# Migrations Complex

A ReportPortal Job that copies binary data from a **multi-bucket MinIO**
source into a **single-bucket destination** (typically AWS S3) and
rewrites the matching `attachments` rows in the ReportPortal **PostgreSQL**
database to point at the new layout.

It is intended for two situations:

1. **Consolidation**: collapse N per-project MinIO buckets (`prj-1`, `prj-2`, …)
   plus the shared `rp-bucket` into a single bucket on the same backend.
2. **Backend migration**: move that single bucket from MinIO to AWS S3
   (or any S3-compatible storage), without losing references to historical
   attachments.

The two are the same operation — only the source/destination endpoints
differ. Source must be an S3-compatible API (MinIO or S3); destination
must be S3-compatible (S3 or MinIO).

> **Looking for Kubernetes / Helm?** Use the [Helm chart](charts/README.md).
> It wires up the same image and the same environment variables, plus
> IRSA, Secrets, and a parameterized `Job`. Detailed performance guidance
> lives in [`charts/README.md` → Performance tuning](charts/README.md#performance-tuning).

---

## Table of contents

- [What it does](#what-it-does)
- [Prerequisites](#prerequisites)
- [Quick start (Docker Compose)](#quick-start-docker-compose)
- [Configuration](#configuration)
  - [Database](#database)
  - [Bucket layout](#bucket-layout)
  - [Source — MinIO (multi-bucket)](#source--minio-multi-bucket)
  - [Destination — S3 (single bucket)](#destination--s3-single-bucket)
  - [Migration tuning](#migration-tuning)
  - [DB attachment-update tuning](#db-attachment-update-tuning)
  - [Verification flags](#verification-flags)
  - [JVM](#jvm)
- [.env template](#env-template)
- [Source / destination combinations](#source--destination-combinations)
- [Operating model](#operating-model)
- [Performance tuning](#performance-tuning)
- [Troubleshooting](#troubleshooting)
- [License](#license)

---

## What it does

```text
   PostgreSQL (attachments)         ┌────────────────────────────┐
              ▲                     │   migrations-complex Job   │
              │ updates rows        │  ┌──────────────────────┐  │
              └───────────────────► │  │  copy + verify loop  │  │
                                    │  └──────────────────────┘  │
   MinIO  prj-1, prj-2, …, rp-bucket  ──►  S3   <bucket>
   (source, multi-bucket)                  (destination, single bucket)
```

- ReportPortal **does not need to be stopped**. You can switch ReportPortal
  to S3 first; the Job copies historical data in the background.
- **Idempotent** — verifies each object on the destination before recording
  it as migrated.
- Safe to **resume / re-run** — already-migrated rows are skipped.
- Optionally **deletes the source MinIO buckets** on success
  (`DATASTORE_REMOVE_AFTER_MIGRATION=true`).

---

## Prerequisites

- Docker 20.10+ and Docker Compose v2 (or any other container runtime).
- A reachable **multi-bucket MinIO** deployment (the source).
- An **existing S3 bucket** in the destination region — the Job does
  **not** create it.
- A **PostgreSQL** database backup before the run (irreversible row
  rewrites).
- Credentials for both ends (or IRSA on EKS for the destination — see the
  [Helm chart](charts/README.md#7-serviceaccount-irsa-on-eks)).

---

## Quick start (Docker Compose)

1. Copy [`.env` template](#env-template) below into a file named `.env`
   next to [`docker-compose.yaml`](docker-compose.yaml) and fill it in.
2. Make sure the destination S3 bucket exists.
3. Take a database backup.
4. Start the Job:

   ```bash
   docker compose up migrations-complex
   docker compose logs -f migrations-complex
   ```

The container exits when the migration finishes (`restart: "no"`).

---

## Configuration

All variables are consumed by the application directly. They are the same
ones the [Helm chart Job](charts/templates/job.yaml) sets, so any value
documented for the chart maps 1:1 to the same env var here.

### Database

ReportPortal's PostgreSQL — the `attachments` table is rewritten in place.

| Variable | Required | Default | Notes |
|---|---|---|---|
| `RP_DB_HOST` | yes | — | PostgreSQL host. |
| `RP_DB_USER` | yes | — | PostgreSQL user. |
| `RP_DB_PASS` | yes | — | PostgreSQL password. |
| `RP_DB_NAME` | no | `reportportal` | Database name. |
| `RP_DATASOURCE_MAXIMUMPOOLSIZE` | no | `20` | Keep ≥ `MIGRATION_PARALLELISM`. |

### Bucket layout

Describes the source bucket layout and names the destination bucket.

| Variable | Required | Default | Notes |
|---|---|---|---|
| `DATASTORE_BUCKETPREFIX` | no | `prj-` | Prefix that matches per-project source buckets. |
| `DATASTORE_DEFAULTBUCKETNAME` | no | `rp-bucket` | Source bucket holding plugins / shared data. |
| `DATASTORE_SINGLEBUCKETNAME` | yes | — | Destination single bucket name (must already exist). |
| `DATASTORE_REGION` | yes | — | AWS region of the destination bucket. |
| `DATASTORE_REMOVE_AFTER_MIGRATION` | no | `false` | Delete source MinIO buckets after success. |

### Source — MinIO (multi-bucket)

| Variable | Required | Default | Notes |
|---|---|---|---|
| `MIGRATION_STORAGE_SOURCE_ENDPOINT` | yes | — | Full URL incl. scheme + port, e.g. `http://minio:9000`. |
| `MIGRATION_STORAGE_SOURCE_ACCESSKEY` | yes | — | MinIO access key. |
| `MIGRATION_STORAGE_SOURCE_SECRETKEY` | yes | — | MinIO secret key. |
| `MIGRATION_STORAGE_SOURCE_REGION` | no | `us-east-1` | Region the source SDK reports (MinIO default). |

### Destination — S3 (single bucket)

| Variable | Required | Default | Notes |
|---|---|---|---|
| `MIGRATION_STORAGE_DESTINATION_ACCESSKEY` | yes\* | — | AWS access key. |
| `MIGRATION_STORAGE_DESTINATION_SECRETKEY` | yes\* | — | AWS secret key. |
| `MIGRATION_STORAGE_DESTINATION_REGION` | yes | — | Destination AWS region. |
| `MIGRATION_STORAGE_DESTINATION_ENDPOINT` | no | derived | Full URL. Empty → SDK derives `https://s3.<region>.amazonaws.com`. |

\* On EKS use IRSA via the [Helm chart](charts/README.md#7-serviceaccount-irsa-on-eks)
and leave both keys empty.

### Migration tuning

Controls concurrency, batching and per-object buffering. See
[Performance tuning](#performance-tuning).

| Variable | Default | Effect |
|---|---|---|
| `MIGRATION_PARALLELISM` | `8` | Concurrent project-level workers. |
| `MIGRATION_INTRA_PROJECT_PARALLELISM` | `16` | Object-level workers within one project. |
| `MIGRATION_BATCH_SIZE` | `500000` | Objects fetched per LIST call. |
| `MIGRATION_STORAGE_MAX_IN_MEMORY_COPY_MB` | `128` | Per-object in-memory buffer cap; larger objects are streamed. |
| `MIGRATION_PROGRESS_LOG_INTERVAL` | `5000` | ms between progress log lines. |

### DB attachment-update tuning

| Variable | Default | Effect |
|---|---|---|
| `MIGRATION_DB_ATTACHMENT_UPDATE_BATCH_SIZE` | `1000` | Rows per `UPDATE` batch. |
| `MIGRATION_DB_ATTACHMENT_CREATION_DATE_COLUMN` | `creation_date` | Column used for the cutoff filter. |
| `MIGRATION_DB_ATTACHMENT_CUTOFF` | _empty_ | Migrate only rows created strictly before this `YYYY-MM-DD HH:MM:SS.mmm` timestamp. |

### Verification flags

Each enabled flag adds **one HEAD request per object**. See the
[verification trade-off table](charts/README.md#verification-trade-off)
for a quick decision matrix.

| Variable | Default | Effect |
|---|---|---|
| `MIGRATION_S3_VERIFY_DESTINATION_AFTER_COPY` | `true` | HEAD destination after copy before rewriting the DB row. |
| `MIGRATION_S3_HEAD_SOURCE_BEFORE_COPY` | `true` | HEAD source before copy — skip silently-missing keys. |

### JVM

| Variable | Default | Effect |
|---|---|---|
| `JAVA_OPTS` | `-Xmx4g -XX:+UseG1GC -XX:InitiatingHeapOccupancyPercent=70` | Heap, GC, and any other JVM options. |

`-Xmx` must comfortably fit
`MIGRATION_STORAGE_MAX_IN_MEMORY_COPY_MB × concurrency overhead`. See the
[Tuning triangle](charts/README.md#tuning-triangle).

---

## .env template

Copy this file to `.env`, fill in the empty values, and Docker Compose will
pick it up automatically.

```env
# --- Database -----------------------------------------------------------------
RP_DB_HOST=postgres
RP_DB_USER=rpuser
RP_DB_PASS=
RP_DB_NAME=reportportal
RP_DATASOURCE_MAXIMUMPOOLSIZE=20

# --- Bucket layout ------------------------------------------------------------
DATASTORE_BUCKETPREFIX=prj-
DATASTORE_DEFAULTBUCKETNAME=rp-bucket
DATASTORE_SINGLEBUCKETNAME=rp-s3-storage
DATASTORE_REGION=eu-central-1
DATASTORE_REMOVE_AFTER_MIGRATION=false

# --- Source: MinIO ------------------------------------------------------------
MIGRATION_STORAGE_SOURCE_ENDPOINT=http://minio:9000
MIGRATION_STORAGE_SOURCE_ACCESSKEY=
MIGRATION_STORAGE_SOURCE_SECRETKEY=
MIGRATION_STORAGE_SOURCE_REGION=us-east-1

# --- Destination: S3 ----------------------------------------------------------
# Leave ENDPOINT empty for native AWS S3 (SDK derives it from REGION).
MIGRATION_STORAGE_DESTINATION_ENDPOINT=
MIGRATION_STORAGE_DESTINATION_ACCESSKEY=
MIGRATION_STORAGE_DESTINATION_SECRETKEY=
MIGRATION_STORAGE_DESTINATION_REGION=eu-central-1

# --- Migration tuning ---------------------------------------------------------
MIGRATION_PARALLELISM=8
MIGRATION_INTRA_PROJECT_PARALLELISM=16
MIGRATION_BATCH_SIZE=500000
MIGRATION_STORAGE_MAX_IN_MEMORY_COPY_MB=128
MIGRATION_PROGRESS_LOG_INTERVAL=5000

# --- DB attachment-update tuning ----------------------------------------------
MIGRATION_DB_ATTACHMENT_UPDATE_BATCH_SIZE=1000
MIGRATION_DB_ATTACHMENT_CREATION_DATE_COLUMN=creation_date
MIGRATION_DB_ATTACHMENT_CUTOFF=

# --- Verification flags -------------------------------------------------------
MIGRATION_S3_VERIFY_DESTINATION_AFTER_COPY=true
MIGRATION_S3_HEAD_SOURCE_BEFORE_COPY=true

# --- JVM ----------------------------------------------------------------------
JAVA_OPTS=-Xmx4g -XX:+UseG1GC -XX:InitiatingHeapOccupancyPercent=70
```

---

## Source / destination combinations

The Job is symmetric in source and destination — both must speak the S3
API. Pick the endpoints accordingly.

| Scenario | `MIGRATION_STORAGE_SOURCE_ENDPOINT` | `MIGRATION_STORAGE_DESTINATION_ENDPOINT` | Notes |
|---|---|---|---|
| **MinIO multi-bucket → AWS S3** *(primary)* | `http(s)://<minio>:9000` | _empty_ (SDK derives) | The headline use case. |
| **MinIO multi-bucket → MinIO single-bucket** | `http(s)://<old-minio>:9000` | `http(s)://<new-minio>:9000` | Same backend, consolidation only. |
| **S3 multi-bucket → S3 single-bucket** | _empty_ (or explicit S3 host) | _empty_ | Requires both `*_REGION` set. |
| **S3 → MinIO** | _empty_ | `http(s)://<minio>:9000` | Reverse migration / repatriation. |

For all of these, `DATASTORE_BUCKETPREFIX` + `DATASTORE_DEFAULTBUCKETNAME`
describe what to read on the **source**, and `DATASTORE_SINGLEBUCKETNAME`
+ `DATASTORE_REGION` describe what to write on the **destination**.

---

## Operating model

| Phase | Action |
|---|---|
| **Before** | Take a Postgres backup. Confirm the destination bucket exists. Confirm credentials / IAM. |
| **Cutover (no downtime)** | Reconfigure ReportPortal to write to the destination S3 bucket — new objects start landing there immediately. |
| **Migration** | `docker compose up migrations-complex` — historical data is copied in the background. |
| **Verify** | Tail the logs; confirm `MIGRATION_S3_VERIFY_DESTINATION_AFTER_COPY` did not flag failures. |
| **Cleanup** | `docker compose down`. Optionally re-run with `DATASTORE_REMOVE_AFTER_MIGRATION=true` to drop the old MinIO buckets. |

---

## Performance tuning

The Docker Compose run uses the same knobs as the chart, so the
**authoritative tuning guide lives in the chart README**:

- [Sizing presets](charts/README.md#sizing-presets) — concrete xs / small /
  medium / large / xl values for `parallelism`, heap, and pod resources.
- [What each knob does](charts/README.md#what-each-knob-does) — and what
  "too low" / "too high" looks like.
- [Bottleneck checklist](charts/README.md#bottleneck-checklist) —
  source / network / S3 / DB / JVM / CPU.
- [Network & cost considerations](charts/README.md#network--cost-considerations) —
  same-region runs, S3 Gateway endpoint, horizontal scale-out.
- [Tuning triangle](charts/README.md#tuning-triangle) — the rule that
  binds `MIGRATION_*`, `JAVA_OPTS -Xmx`, and the container memory limit.

Quick rules of thumb when running with Compose:

1. Bump `MIGRATION_PARALLELISM` and/or `MIGRATION_STORAGE_MAX_IN_MEMORY_COPY_MB`.
2. Raise `JAVA_OPTS -Xmx` to fit the new working set.
3. Make sure the host running the container has **≥ 1.25 × `-Xmx`** RAM
   free (Docker doesn't enforce a `mem_limit` here unless you add one).
4. Keep `RP_DATASOURCE_MAXIMUMPOOLSIZE` ≥ `MIGRATION_PARALLELISM`.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Container exits immediately, log says "missing required env" | A required variable from the [Configuration](#configuration) section is unset. |
| `Connection refused` to Postgres | Wrong `RP_DB_HOST` / `RP_DB_PORT`, or DB not yet ready (start with `depends_on`). |
| `AccessDenied` on S3 | Wrong AWS keys or IAM policy lacks `s3:GetObject` / `s3:PutObject` on `DATASTORE_SINGLEBUCKETNAME`. |
| `NoSuchBucket` on S3 | Destination bucket doesn't exist in `DATASTORE_REGION`. Create it first. |
| MinIO 403 / 404 | Wrong scheme/port in `MIGRATION_STORAGE_SOURCE_ENDPOINT` (HTTP vs HTTPS is the most common). |
| OOM (host kills the process) | `JAVA_OPTS -Xmx` too high for the host, **or** `MIGRATION_STORAGE_MAX_IN_MEMORY_COPY_MB × parallelism` doesn't fit in the heap. See [Tuning triangle](charts/README.md#tuning-triangle). |
| Job is slow | Walk through the [Bottleneck checklist](charts/README.md#bottleneck-checklist). |
| `503 SlowDown` from S3 | Lower `MIGRATION_PARALLELISM × MIGRATION_INTRA_PROJECT_PARALLELISM` for ~30 minutes; AWS auto-partitions the prefix. |

Useful commands:

```bash
docker compose logs -f migrations-complex
docker compose top  migrations-complex
docker stats        # live CPU / memory / network for the running container
```

---

## License

Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
See the [LICENSE](LICENSE) file in the repository.
