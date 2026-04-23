# ReportPortal `migrations-complex` Helm chart

A single-purpose Kubernetes Job that copies ReportPortal binary data from a
**multi-bucket MinIO** source into a **single AWS S3 bucket**, and updates the
matching `attachments` rows in the ReportPortal **PostgreSQL** database.

```text
   PostgreSQL (attachments)         ┌────────────────────────────┐
              ▲                     │   migrations-complex Job   │
              │ updates rows        │  ┌──────────────────────┐  │
              └───────────────────► │  │  copy + verify loop  │  │
                                    │  └──────────────────────┘  │
   MinIO  prj-1, prj-2, …, rp-bucket  ──►  AWS S3   <bucket>
   (source, multi-bucket)                  (destination, single bucket)
```

- ReportPortal **does not need to be stopped**. You can switch ReportPortal to
  S3 first; the Job copies historical data in the background.
- Idempotent — verifies each object on the destination before recording it as migrated.
- Safe to resume / re-run — already-migrated rows are skipped.

> This is the **only** migration scenario the image supports today: a
> multi-bucket MinIO source consolidated into a single AWS S3 bucket, with
> the matching `attachments` rows rewritten in PostgreSQL. The chart and
> the [`docker-compose.yaml`](../docker-compose.yaml) at the repo root use
> the same image and the same env-var schema — they just package it for
> Kubernetes vs. local Docker.

---

## Table of contents

- [TL;DR](#tldr)
- [Prerequisites](#prerequisites)
- [How it works](#how-it-works)
- [Configuration](#configuration)
  - [1. Image](#1-image)
  - [2. Migration tuning](#2-migration-tuning)
  - [3. Source — PostgreSQL + MinIO](#3-source--postgresql--minio)
  - [4. Destination — AWS S3](#4-destination--aws-s3)
  - [5. Job, JVM, resources](#5-job-jvm-resources)
  - [Tuning triangle](#tuning-triangle)
  - [6. Pod scheduling / security](#6-pod-scheduling--security)
  - [7. ServiceAccount (IRSA on EKS)](#7-serviceaccount-irsa-on-eks)
- [Performance tuning](#performance-tuning)
  - [Sizing presets](#sizing-presets)
  - [What each knob does](#what-each-knob-does)
  - [Bottleneck checklist](#bottleneck-checklist)
  - [Network & cost considerations](#network--cost-considerations)
  - [Verification trade-off](#verification-trade-off)
- [Operating model](#operating-model)
- [Parameters reference](#parameters-reference)
- [Troubleshooting](#troubleshooting)

---

## TL;DR

```bash
helm install migrate ./charts -f my-values.yaml
kubectl logs -f -l app.kubernetes.io/name=migrations-complex
```

Minimum `my-values.yaml`:

```yaml
source:
  database:
    endpoint: my-postgresql.default.svc.cluster.local
    user: rpuser
    dbName: reportportal
    secretName: postgresql-credentials   # holds key `postgresql-password`
  minio:
    endpoint: my-minio.default.svc.cluster.local
    port: 9000
    ssl: false
    secretName: minio-credentials        # holds keys `access-key` and `secret-key`

destination:
  s3:
    region: eu-central-1
    bucket: rp-s3-storage                # must already exist
    secretName: aws-credentials          # OR rely on IRSA via serviceAccount.annotations
```

---

## Prerequisites

- Kubernetes 1.24+ and Helm 3.x+
- A PostgreSQL database reachable from the cluster (managed RDS or in-cluster)
- A reachable **multi-bucket MinIO** deployment (the source)
- An **existing AWS S3 bucket** in the target region (the destination)
- Credentials supplied as one of:
  - Kubernetes `Secret`s (recommended)
  - inline plain values (only for local / dev)
  - **IRSA** for the destination on EKS (preferred for AWS) — leave all
    `destination.s3.{accessKey,secretKey,secretName}` empty and supply the role
    in `serviceAccount.annotations`

---

## How it works

The chart renders a single `batch/v1` `Job` that runs the
`reportportal/migrations-complex` image with `RP_STORAGE_MIGRATION=true`.
At runtime the Job:

1. Connects to PostgreSQL using `source.database.*`.
2. Lists buckets on MinIO matching `source.minio.buckets.projectPrefix` plus
   the dedicated `source.minio.buckets.pluginsBucket`.
3. Copies every object to `destination.s3.bucket` in `destination.s3.region`.
4. Updates the `attachments` rows so they reference the new bucket layout.
5. Optionally HEADs the source first (`migration.headSourceBeforeCopy`) and
   verifies each object on the destination after copy
   (`migration.verifyDestinationAfterCopy`).
6. Optionally deletes the source MinIO buckets after success
   (`migration.removeSourceBucketsAfterMigration`).

The `Job` is created with `restartPolicy: Never` and `backoffLimit: 0` by
default — set `backoffLimit` if you want automatic retries.

The destination S3 endpoint is resolved in this order:

1. `destination.s3.endpointOverride` (full URL incl. scheme), if set.
2. `destination.s3.endpoint` (host only) combined with `ssl` and optional `port`.
3. Auto-derived: `s3.<destination.s3.region>.amazonaws.com`.

This means you only need to set `destination.s3.region` for the common AWS case.

---

## Configuration

The values file follows Bitnami conventions and is split into the following
sections — see the [annotated `values.yaml`](values.yaml) for full inline docs.

### 1. Image

```yaml
image:
  repository: reportportal/migrations-complex
  tag: "1.0.0"
  pullPolicy: IfNotPresent
imagePullSecrets: []
```

### 2. Migration tuning

App-level knobs. They are translated to the `MIGRATION_*` env vars consumed by
the Java application.

```yaml
migration:
  parallelism: 8                # concurrent project workers
  intraProjectParallelism: 16   # workers within one project
  batchSize: 500000             # objects copied per batch
  maxInMemoryCopyMb: 128        # max payload buffered in-memory per copy
  progressLogInterval: 5000     # ms between progress log lines
  attachmentUpdateBatchSize: 1000
  attachmentCreationDateColumn: "creation_date"
  attachmentCutoff: ""          # only migrate rows created strictly before this timestamp
  verifyDestinationAfterCopy: true
  headSourceBeforeCopy: true
  removeSourceBucketsAfterMigration: false
```

### 3. Source — PostgreSQL + MinIO

```yaml
source:
  database:
    endpoint: postgresql.postgresql.svc.cluster.local
    port: 5432
    user: rpuser
    dbName: reportportal
    maximumPoolSize: 20
    # Either an existing Secret OR a plain password:
    secretName: ""
    passwordKeyName: "postgresql-password"
    password: ""

  minio:
    endpoint: minio.minio.svc.cluster.local
    port: 9000
    ssl: false
    region: "us-east-1"           # MinIO default
    # Either an existing Secret OR plain credentials:
    secretName: ""
    accessKeyName: "access-key"
    secretKeyName: "secret-key"
    accessKey: ""
    secretKey: ""
    buckets:
      projectPrefix: "prj-"        # matches prj-1, prj-2, …
      pluginsBucket: "rp-bucket"
```

### 4. Destination — AWS S3

```yaml
destination:
  s3:
    region: "eu-central-1"
    bucket: ""                    # required — must already exist
    endpoint: ""                  # host-only override; empty → s3.<region>.amazonaws.com
    endpointOverride: ""          # full URL override (wins over endpoint/ssl/port)
    port: ""
    ssl: true
    # Either an existing Secret, plain credentials, or IRSA on the ServiceAccount:
    secretName: ""
    accessKeyName: "access-key"
    secretKeyName: "secret-key"
    accessKey: ""
    secretKey: ""
```

### 5. Job, JVM, resources

```yaml
backoffLimit: 0
jvmArgs: "-Xmx4g -XX:+UseG1GC -XX:InitiatingHeapOccupancyPercent=70"

resources:
  limits:
    cpu: 500m
    memory: 512Mi
  requests:
    cpu: 250m
    memory: 248Mi
```

#### Tuning triangle

`migration.*` (app), `jvmArgs` (JVM), and `resources` (Kubernetes) are
operationally coupled:

```text
migration.maxInMemoryCopyMb  +  migration.parallelism * overhead
             ≤  JVM heap (-Xmx in jvmArgs)
             ≤  resources.limits.memory
```

Rule of thumb when scaling up:

1. Increase `migration.parallelism` and/or `migration.maxInMemoryCopyMb`.
2. Bump `jvmArgs` `-Xmx` (and `-Xms`) accordingly.
3. Raise `resources.limits.memory` to **≥ 1.25× the new -Xmx** (leave headroom
   for off-heap, native S3 SDK buffers, GC overhead).
4. Raise `resources.limits.cpu` if `parallelism × intraProjectParallelism`
   grows materially.

### 6. Pod scheduling / security

```yaml
extraInitContainers: []
podAnnotations: {}
podSecurityContext: {}
securityContext: {}
nodeSelector: {}
tolerations: []
affinity: {}
```

### 7. ServiceAccount (IRSA on EKS)

```yaml
serviceAccount:
  create: true
  name: ""
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::<account-id>:role/<role-name>
```

When IRSA is configured, leave all `destination.s3.{accessKey,secretKey,secretName}`
empty — the AWS SDK picks up credentials from the projected token automatically.

---

## Performance tuning

The Job is bottlenecked by **one of three layers** at any time: the source
(MinIO read throughput), the network (cross-AZ / cross-region bandwidth and
TLS handshakes), or the destination (S3 PUT throughput + DB attachment
updates). Tuning is the process of finding the layer that's actually
limiting you and matching the other two to it — never tune blindly.

Always change knobs in the order described in the
[Tuning triangle](#tuning-triangle): app → JVM → resources. Otherwise you
will OOM-kill the pod before observing any speed-up.

### Sizing presets

Starting points for the `migrations-complex` Job. Numbers are conservative —
measure first, then push up.

| Profile | Use case | `parallelism` | `intraProjectParallelism` | `maxInMemoryCopyMb` | `batchSize` | `jvmArgs` (`-Xmx`) | `resources.limits` (cpu / mem) | DB pool (`source.database.maximumPoolSize`) |
|---------|----------|---------------|---------------------------|---------------------|-------------|--------------------|--------------------------------|---------------------------------------------|
| **xs / smoke test** | < 10 GB total, single project, dev cluster | 2 | 4 | 32 | 50 000 | `-Xmx1g` | 500m / 1Gi | 8 |
| **small** | up to ~100 GB, ≤ 10 projects, mostly small files | 4 | 8 | 64 | 100 000 | `-Xmx2g` | 1 / 2Gi | 12 |
| **medium** *(default)* | up to ~1 TB, ≤ 100 projects, mixed object sizes | 8 | 16 | 128 | 500 000 | `-Xmx4g` | 2 / 6Gi | 20 |
| **large** | several TB, hundreds of projects, mostly large attachments | 16 | 24 | 256 | 1 000 000 | `-Xmx10g` | 4 / 14Gi | 32 |
| **xl / dedicated node** | 10 TB+, fast S3 link, dedicated worker node | 32 | 32 | 512 | 2 000 000 | `-Xmx20g` | 8 / 28Gi | 48 |

Rules that hold for **every** profile:

- `resources.limits.memory` ≥ **1.25 × `-Xmx`** (off-heap, native S3 SDK
  buffers, GC overhead).
- `resources.requests.cpu` ≥ **0.5 × `resources.limits.cpu`** so the Pod
  isn't throttled while still leaving room for bursts.
- `source.database.maximumPoolSize` ≥ `migration.parallelism` (one JDBC
  connection per top-level worker, plus a few for the attachment writer).
- Keep `migration.attachmentUpdateBatchSize` at `1000` unless you see DB
  CPU saturation — larger batches stress shared_buffers and increase WAL.

### What each knob does

| Knob | Effect | Symptom you're too low | Symptom you're too high |
|---|---|---|---|
| `migration.parallelism` | Number of buckets / projects copied in parallel. | Wall time scales linearly with #projects, source link idle. | DB pool saturated, MinIO LIST throttled, S3 5xx slowdown. |
| `migration.intraProjectParallelism` | Object-level workers within one project. | One huge project dominates wall time. | Goroutine-style thrash, GC time grows, network maxed out. |
| `migration.maxInMemoryCopyMb` | Per-object buffer ceiling. Objects larger than this are streamed. | Lots of slow streamed copies for medium-sized files. | OOMKilled pod, GC pauses > 1 s. |
| `migration.batchSize` | Objects fetched per LIST call. | LIST round-trips dominate logs. | First batch takes minutes, memory spike on startup. |
| `migration.progressLogInterval` | ms between progress lines. | Logs too quiet — hard to spot stalls. | Log noise, log-shipping cost. |
| `migration.attachmentUpdateBatchSize` | Rows per `UPDATE ... WHERE id IN (…)`. | DB updates lag behind copies. | Postgres lock contention, WAL bloat. |
| `migration.attachmentCutoff` | Skip attachments newer than this timestamp. | — (purely optional). | You miss data created before cutover. Always pick the cutover instant. |
| `jvmArgs` `-Xmx` | Java heap ceiling. | Frequent full GC, copy throughput drops. | Wastes node memory, smaller `resources` headroom. |

### Bottleneck checklist

Run `kubectl top pod -l app.kubernetes.io/name=migrations-complex` and the
Job logs side-by-side. Then:

1. **Source-bound (MinIO)** — Pod CPU < 30 %, network-rx flat, MinIO logs
   show high LIST/GET concurrency. → Scale MinIO out, or add a MinIO read
   replica. Do **not** raise `parallelism` further.
2. **Network-bound** — Pod CPU < 50 %, network-rx ≈ network-tx and both at
   the node NIC ceiling. → Move the Job to a node closer to the
   destination region (cross-region MinIO→S3 is the worst case), or use a
   VPC S3 Gateway endpoint to avoid NAT.
3. **Destination-bound (S3)** — S3 returns `503 SlowDown` or copy latencies
   spike. → Lower `parallelism × intraProjectParallelism` until the 503s
   disappear; AWS will auto-partition the prefix within ~30 minutes and
   you can raise it back.
4. **DB-bound** — Postgres CPU > 80 %, attachment updates lagging behind
   copies. → Raise `source.database.maximumPoolSize`, lower
   `migration.attachmentUpdateBatchSize`, or move the DB to faster storage.
5. **JVM-bound** — Long GC pauses in logs (`G1 Pause`), throughput drops
   between batches. → Bump `-Xmx` and `resources.limits.memory` together
   per the [Tuning triangle](#tuning-triangle).
6. **Pod-bound (CPU throttling)** — `kubectl top pod` reports CPU at the
   limit and `container_cpu_cfs_throttled_seconds_total` is non-zero. →
   Raise `resources.limits.cpu` (and `requests.cpu`).

### Network & cost considerations

- **Same-region MinIO → S3** is by far the cheapest path. If you can
  collocate the cluster running the Job with the destination S3 bucket,
  you avoid both NAT data-processing fees and inter-AZ bandwidth.
- On AWS, attach an **S3 Gateway endpoint** to the VPC routing table used
  by the worker node. It costs nothing and removes per-GB egress for
  destination PUTs.
- For **cross-region** runs, expect the network to be the hard cap. The
  practical limit is usually 1–2 Gbit/s per Pod even on a 10 Gbit NIC.
  Scale **horizontally** by splitting the migration on
  `source.minio.buckets.projectPrefix` and running multiple Helm releases
  with disjoint prefixes — the Job is idempotent and resumable.
- S3 PUT requests are **billed per request**, not per byte. Lots of small
  files (typical for ReportPortal logs/screenshots) costs more than a few
  large ones — this is normal, not a tuning bug.

### Verification trade-off

`migration.headSourceBeforeCopy` and `migration.verifyDestinationAfterCopy`
each cost **one extra HEAD request per object**. With both enabled you do
3 round-trips per file (HEAD source + COPY/PUT + HEAD destination).

| `headSourceBeforeCopy` | `verifyDestinationAfterCopy` | When to use |
|---|---|---|
| `true`  | `true`  *(default)* | First production run, untrusted source, "must not lose data". |
| `false` | `true`  | Re-run after a partial copy: skip the source HEAD because we already know objects exist. |
| `true`  | `false` | Throughput-bound dry-run / benchmark. Don't use in production. |
| `false` | `false` | **Never** in production — DB rows would be rewritten with no proof the object landed on S3. |

For multi-TB runs, leaving both **on** typically costs ~10–20 % wall-time
versus the unsafe variant — well worth it.

---

## Operating model

| Phase | Action |
|---|---|
| Before | Take a database backup. Confirm the destination S3 bucket exists. Confirm IAM / MinIO access. |
| Cutover (no downtime) | Switch ReportPortal to use S3 (`destination.s3.bucket`) — new objects start landing on S3 immediately. |
| Migration | `helm install migrate ./charts -f my-values.yaml` — historical data is copied in the background. |
| Verify | Check Job logs and confirm `verifyDestinationAfterCopy` did not flag failures. |
| Cleanup | `helm uninstall migrate`. Optionally re-run with `migration.removeSourceBucketsAfterMigration: true` to drop the old MinIO buckets. |

---

## Parameters reference

### Common

| Name                | Description                                       | Default |
|---------------------|---------------------------------------------------|---------|
| `nameOverride`      | Partial chart-name override                       | `""`    |
| `fullnameOverride`  | Full chart-fullname override                      | `""`    |

### Image

| Name                  | Description                       | Default                                  |
|-----------------------|-----------------------------------|------------------------------------------|
| `image.repository`    | Migration image repository        | `reportportal/migrations-complex`        |
| `image.tag`           | Migration image tag               | `"1.0.0"`                                |
| `image.pullPolicy`    | Pull policy                       | `IfNotPresent`                           |
| `imagePullSecrets`    | Image pull secret references      | `[]`                                     |

### Migration tuning

| Name                                          | Description                                        | Default          |
|-----------------------------------------------|----------------------------------------------------|------------------|
| `migration.parallelism`                       | Concurrent top-level copy workers                  | `8`              |
| `migration.intraProjectParallelism`           | Concurrent workers within one project              | `16`             |
| `migration.batchSize`                         | Objects copied per batch                           | `500000`         |
| `migration.maxInMemoryCopyMb`                 | Max payload (MB) buffered in-memory per copy       | `128`            |
| `migration.progressLogInterval`               | Progress log interval, ms                          | `5000`           |
| `migration.attachmentUpdateBatchSize`         | DB batch size for attachment updates               | `1000`           |
| `migration.attachmentCreationDateColumn`      | Attachment creation-date column name               | `creation_date`  |
| `migration.attachmentCutoff`                  | Migrate only rows created before this timestamp    | `""`             |
| `migration.verifyDestinationAfterCopy`        | HEAD each object on the destination after copy     | `true`           |
| `migration.headSourceBeforeCopy`              | HEAD each source object before copy                | `true`           |
| `migration.removeSourceBucketsAfterMigration` | Delete source MinIO buckets after success          | `false`          |

### Source — PostgreSQL

| Name                                  | Description                                   | Default                                       |
|---------------------------------------|-----------------------------------------------|-----------------------------------------------|
| `source.database.endpoint`            | PostgreSQL host                               | `postgresql.default.svc.cluster.local`     |
| `source.database.port`                | PostgreSQL port                               | `5432`                                        |
| `source.database.user`                | PostgreSQL user                               | `rpuser`                                      |
| `source.database.dbName`              | PostgreSQL DB name                            | `reportportal`                                |
| `source.database.maximumPoolSize`     | JDBC connection pool size                     | `20`                                          |
| `source.database.password`            | Plain password (only if `secretName` empty)   | `""`                                          |
| `source.database.secretName`          | Existing Secret with the password             | `""`                                          |
| `source.database.passwordKeyName`     | Key in the Secret                             | `postgresql-password`                         |

### Source — MinIO

| Name                                              | Description                                | Default                                |
|---------------------------------------------------|--------------------------------------------|----------------------------------------|
| `source.minio.endpoint`                           | MinIO host (no scheme / port)              | `minio.default.svc.cluster.local`        |
| `source.minio.port`                               | MinIO port                                 | `9000`                                 |
| `source.minio.ssl`                                | Use HTTPS                                  | `false`                                |
| `source.minio.region`                             | AWS region MinIO reports                   | `us-east-1`                            |
| `source.minio.accessKey` / `.secretKey`           | Plain credentials                          | `""`                                   |
| `source.minio.secretName`                         | Existing Secret with credentials           | `""`                                   |
| `source.minio.accessKeyName` / `.secretKeyName`   | Keys inside the Secret                     | `access-key` / `secret-key`            |
| `source.minio.buckets.projectPrefix`              | Prefix matching per-project buckets        | `prj-`                                 |
| `source.minio.buckets.pluginsBucket`              | Bucket holding plugins / shared data       | `rp-bucket`                            |

### Destination — AWS S3

| Name                                              | Description                                                         | Default          |
|---------------------------------------------------|---------------------------------------------------------------------|------------------|
| `destination.s3.region`                           | AWS region of the destination bucket                                | `eu-central-1`   |
| `destination.s3.bucket`                           | Destination bucket name (must exist) — **required**                 | `""`             |
| `destination.s3.endpoint`                         | S3 host override (no scheme); empty → `s3.<region>.amazonaws.com`   | `""`             |
| `destination.s3.endpointOverride`                 | Full URL override (incl. scheme)                                    | `""`             |
| `destination.s3.port`                             | Optional explicit port                                              | `""`             |
| `destination.s3.ssl`                              | Use HTTPS                                                           | `true`           |
| `destination.s3.accessKey` / `.secretKey`         | Plain credentials                                                   | `""`             |
| `destination.s3.secretName`                       | Existing Secret with credentials                                    | `""`             |
| `destination.s3.accessKeyName` / `.secretKeyName` | Keys inside the Secret                                              | `access-key` / `secret-key` |

### Job, JVM, resources, scheduling, ServiceAccount

| Name                         | Description                                              | Default                                                           |
|------------------------------|----------------------------------------------------------|-------------------------------------------------------------------|
| `backoffLimit`               | Job retries before failure                               | `0`                                                               |
| `jvmArgs`                    | JVM options forwarded as `JAVA_OPTS`                     | `-Xmx4g -XX:+UseG1GC -XX:InitiatingHeapOccupancyPercent=70`       |
| `resources`                  | Container requests / limits                              | see values                                                        |
| `extraInitContainers`        | Extra init containers                                    | `[]`                                                              |
| `podAnnotations`             | Pod annotations                                          | `{}`                                                              |
| `podSecurityContext`         | Pod-level security context                               | `{}`                                                              |
| `securityContext`            | Container-level security context                         | `{}`                                                              |
| `nodeSelector`               | Node selector                                            | `{}`                                                              |
| `tolerations`                | Tolerations                                              | `[]`                                                              |
| `affinity`                   | Affinity                                                 | `{}`                                                              |
| `serviceAccount.create`      | Whether to create a ServiceAccount                       | `true`                                                            |
| `serviceAccount.name`        | ServiceAccount name (auto-generated if empty)            | `""`                                                              |
| `serviceAccount.annotations` | ServiceAccount annotations (use IRSA here on EKS)        | `{}`                                                              |

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Job fails to start, Pod stays `Pending` | Insufficient resources or scheduling constraints. `kubectl describe pod ...` |
| `chart values failed validation: ... is required` | A required value is missing; commonly `destination.s3.bucket` or any of the credential fields. |
| DB connection refused | Wrong `source.database.endpoint` / `port`, missing NetworkPolicy, or wrong password key in the Secret. |
| `AccessDenied` on S3 | Credentials missing or IAM role lacks `s3:GetObject` / `s3:PutObject` on `destination.s3.bucket`. |
| `NoSuchBucket` on S3 | Destination bucket does not exist in `destination.s3.region`. |
| MinIO 403 / 404 | Wrong `source.minio.endpoint`, `ssl`, `port` combination — SSL flag mismatch is the most common one. |
| Migration too slow | Tune `migration.parallelism`, `intraProjectParallelism`, `batchSize`, **and** bump `jvmArgs -Xmx` and `resources.limits` together (see [Tuning triangle](#tuning-triangle)). |
| `OOMKilled` | `resources.limits.memory` < `-Xmx` headroom. Raise both, or lower `migration.maxInMemoryCopyMb` / `parallelism`. |

Useful commands:

```bash
helm get values migrate
kubectl logs -f -l app.kubernetes.io/name=migrations-complex
kubectl describe job -l app.kubernetes.io/name=migrations-complex
kubectl top pod  -l app.kubernetes.io/name=migrations-complex
```

---

## License

Apache License 2.0 — see [LICENSE](../LICENSE).
