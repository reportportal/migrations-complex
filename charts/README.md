# ReportPortal Migrations Complex Service

A Helm chart for migrating ReportPortal data between storage systems and upgrading database schemas. This service handles three types of migrations that can run independently or together in a single execution.

## Table of Contents

- [Quick Start](#quick-start)
- [What This Service Does](#what-this-service-does)
- [Migration Types](#migration-types)
- [Prerequisites](#prerequisites)
- [Configuration Guide](#configuration-guide)
  - [Quick Reference: All Parameters](#quick-reference-all-parameters)
  - [Database Configuration](#database-configuration)
  - [Storage Configuration](#storage-configuration)
- [Migration Scenarios](#migration-scenarios)
  - [Scenario 1: API Keys Migration Only](#scenario-1-api-keys-migration-only)
  - [Scenario 2: Multi-Bucket to Single-Bucket](#scenario-2-multi-bucket-to-single-bucket)
  - [Scenario 3: MinIO to S3 Migration](#scenario-3-minio-to-s3-migration)
  - [Scenario 4: Complete Migration (All Steps)](#scenario-4-complete-migration-all-steps)
- [Installation Steps](#installation-steps)
- [Troubleshooting](#troubleshooting)

---

## Quick Start

1. **Add the Helm repository:**
   ```bash
   helm repo add reportportal-migrations https://reportportal.io/migrations-complex/
   helm repo update
   ```

2. **Download default values:**
   ```bash
   helm show values reportportal-migrations/migrations-complex > my-migration-values.yaml
   ```

3. **Edit the values file** to enable your desired migration(s) and configure credentials

4. **Deploy the migration:**
   ```bash
   helm install my-migration reportportal-migrations/migrations-complex -f my-migration-values.yaml
   ```

5. **Monitor the migration:**
   ```bash
   kubectl get pods -l app.kubernetes.io/name=migrations-complex
   kubectl logs -l app.kubernetes.io/name=migrations-complex --follow
   ```

6. **Clean up after completion:**
   ```bash
   helm uninstall my-migration
   ```

---

## What This Service Does

This migration service helps you:

- **Upgrade to ReportPortal 23.3+**: Migrate OAuth access tokens to API keys (required for versions 23.3 and higher)
- **Consolidate Storage**: Merge multiple storage buckets into a single bucket (supports MinIO and S3)
- **Switch Storage Providers**: Migrate data from MinIO to Amazon S3

**Key Benefits:**
- ✅ All migrations can run in a single job execution
- ✅ Migrations execute sequentially and automatically
- ✅ No service restart required between migrations
- ✅ Preserves data integrity throughout the process

**Downtime Summary:**
| Migration Type | Downtime Required | Notes |
|---------------|-------------------|-------|
| API Keys Migration | ⚠️ **Yes** | ReportPortal must be stopped |
| Multi-Bucket to Single-Bucket | ⚠️ **Yes** | ReportPortal must be stopped (attachments table blocked) |
| MinIO to S3 | ✅ **No** | ReportPortal can run normally - switch to S3 and continue working |

---

## Migration Types

### 1. Database API Keys Migration
**When to use:** Upgrading from ReportPortal versions older than 23.3 to version 23.3 or higher

**What it does:** Converts OAuth access tokens in the database to API keys format

**Downtime Required:** ⚠️ **Yes** - ReportPortal must be stopped during this migration

**Requirements:** Database connection credentials

### 2. Multi-Bucket to Single-Bucket Migration
**When to use:** Consolidating data from multiple buckets (e.g., `prj-1`, `prj-2`, `rp-bucket`) into one bucket

**What it does:** 
- Finds all buckets matching your project prefix (e.g., `prj-*`)
- Consolidates all data into a single destination bucket
- Updates database records to point to the new bucket structure

**Downtime Required:** ⚠️ **Yes** - ReportPortal must be stopped during this migration (attachments table will be blocked)

**Requirements:** 
- Database connection credentials
- Storage credentials (MinIO or S3, depending on destination)
- Source buckets must be accessible

### 3. MinIO to S3 Migration
**When to use:** Moving from MinIO storage to Amazon S3

**What it does:** Transfers all data from a single MinIO bucket to a single S3 bucket

**Downtime Required:** ✅ **No** - ReportPortal can continue running! You can switch ReportPortal to use S3 storage and work normally while the migration runs in the background. The migration will copy data from MinIO to S3 without blocking ReportPortal operations.

**Requirements:**
- MinIO credentials (source)
- S3 credentials (destination)
- Both buckets must exist and be accessible

---

## Prerequisites

Before starting a migration, ensure you have:

- ✅ Kubernetes cluster with Helm 3.x installed
- ✅ Access to your ReportPortal database
- ✅ Storage credentials (MinIO and/or S3) with read/write permissions
- ✅ Sufficient cluster resources (see [Resource Configuration](#resource-configuration))
- ✅ Backup of your database (recommended)

**Important Notes:**
- ⚠️ **API Keys Migration** requires **downtime** - ReportPortal must be stopped during this migration
- ⚠️ **Multi-Bucket to Single-Bucket Migration** requires **downtime** - ReportPortal must be stopped during this migration
- ⚠️ **MinIO to S3 Migration** - ReportPortal can continue running! You can switch ReportPortal to use S3 storage and work normally while the migration runs in the background
- ⚠️ API Keys migration is **irreversible** - OAuth tokens will be permanently deleted
- ⚠️ Ensure you have sufficient storage space in the destination bucket

---

## Configuration Guide

### Quick Reference: All Parameters

#### Migration Enablement
```yaml
migrations:
  database:
    apiKeys:
      enabled: false  # Set to true for API keys migration
  storage:
    multiBucketToSingleBucket:
      enabled: false  # Set to true to consolidate buckets
      destinationType: minio  # or "s3"
      removeSourceBuckets: false  # Set to true to delete source buckets after migration
      buckets:
        projectPrefix: "prj-"  # Prefix for project buckets (e.g., "prj-" matches prj-1, prj-2)
        pluginsBucket: "rp-bucket"  # Name of plugins bucket
        singleBucketName: "rp-storage"  # Destination bucket name
    minioToS3:
      enabled: false  # Set to true to migrate from MinIO to S3
      buckets:
        sourceBucket: "rp-storage"  # MinIO source bucket
        destinationBucket: "rp-s3-storage"  # S3 destination bucket
```

#### Database Configuration
```yaml
database:
  secretName: ""  # Optional: K8s secret name containing password
  passwordKeyName: "postgresql-password"  # Key name in secret
  endpoint: <postgresql-release-name>-postgresql.default.svc.cluster.local
  port: 5432
  user: rpuser
  dbName: reportportal
  password: ""  # Required if secretName is not set
```

#### MinIO Configuration
```yaml
storage:
  minio:
    secretName: ""  # Optional: K8s secret name
    accessKey: <minio-accesskey>  # Required if secretName not set
    secretKey: <minio-secretkey>  # Required if secretName not set
    accessKeyName: "access-key"  # Key name in secret
    secretKeyName: "secret-key"  # Key name in secret
    endpoint: <minio-release-name>-minio.default.svc.cluster.local  # Hostname only
    ssl: false  # true for https, false for http
    port: 9000
```

#### S3 Configuration
```yaml
storage:
  s3:
    region: "eu-central-1"  # AWS region
    secretName: ""  # Optional: K8s secret name
    accessKey: <s3-accesskey>  # Required if secretName not set
    secretKey: <s3-secretkey>  # Required if secretName not set
    accessKeyName: "access-key"  # Key name in secret
    secretKeyName: "secret-key"  # Key name in secret
    endpoint: https://s3.eu-central-1.amazonaws.com  # Full S3 endpoint URL
```

#### Resource Configuration
```yaml
resources:
  limits:
    cpu: 500m  # Increase for faster migrations (e.g., 2000m for large datasets)
    memory: 512Mi  # Increase for faster migrations (e.g., 2Gi for large datasets)
  requests:
    cpu: 250m
    memory: 248Mi
```

---

## Migration Scenarios

### Scenario 1: API Keys Migration Only

**Use case:** Upgrading from ReportPortal < 23.3 to 23.3+

**⚠️ Downtime Required:** Yes - ReportPortal must be stopped during this migration

**Configuration:**
```yaml
migrations:
  database:
    apiKeys:
      enabled: true

database:
  endpoint: my-postgresql.default.svc.cluster.local
  port: 5432
  user: rpuser
  dbName: reportportal
  password: "your-password"
  # OR use a secret:
  # secretName: "postgresql-secret"
  # passwordKeyName: "postgresql-password"
```

**Steps:**
1. ⚠️ **Stop ReportPortal** before starting the migration
2. Configure database credentials
3. Deploy: `helm install api-keys-migration reportportal-migrations/migrations-complex -f values.yaml`
4. Wait for completion: `kubectl wait --for=condition=complete job/api-keys-migration-migrations-complex --timeout=3600s`
5. Clean up: `helm uninstall api-keys-migration`
6. **Restart ReportPortal** after migration completes

---

### Scenario 2: Multi-Bucket to Single-Bucket

**Use case:** Consolidating multiple buckets into one bucket

**⚠️ Downtime Required:** Yes - ReportPortal must be stopped during this migration (attachments table will be blocked)

**Configuration:**
```yaml
migrations:
  storage:
    multiBucketToSingleBucket:
      enabled: true
      destinationType: minio  # or "s3"
      removeSourceBuckets: false  # Set to true to delete source buckets after migration
      buckets:
        projectPrefix: "prj-"
        pluginsBucket: "rp-bucket"
        singleBucketName: "rp-storage"

storage:
  minio:  # Use this if destinationType is "minio"
    endpoint: my-minio.default.svc.cluster.local
    ssl: false
    port: 9000
    accessKey: "minioadmin"
    secretKey: "minioadmin"
  # OR
  s3:  # Use this if destinationType is "s3"
    region: "eu-central-1"
    endpoint: https://s3.eu-central-1.amazonaws.com
    accessKey: "your-s3-access-key"
    secretKey: "your-s3-secret-key"

database:
  endpoint: my-postgresql.default.svc.cluster.local
  port: 5432
  user: rpuser
  dbName: reportportal
  password: "your-password"
```

**Steps:**
1. ⚠️ **Stop ReportPortal** to avoid data conflicts
2. Configure storage and database credentials
3. Ensure destination bucket exists (or will be created automatically)
4. Deploy: `helm install multi-to-single reportportal-migrations/migrations-complex -f values.yaml`
5. Monitor: `kubectl logs -f job/multi-to-single-migrations-complex`
6. After completion, update ReportPortal configuration to use single bucket
7. Clean up: `helm uninstall multi-to-single`

---

### Scenario 3: MinIO to S3 Migration

**Use case:** Moving from MinIO to Amazon S3

**✅ No Downtime Required:** ReportPortal can continue running! You can switch ReportPortal to use S3 storage and work normally while the migration runs in the background.

**Configuration:**
```yaml
migrations:
  storage:
    minioToS3:
      enabled: true
      buckets:
        sourceBucket: "rp-storage"  # MinIO bucket name
        destinationBucket: "rp-s3-storage"  # S3 bucket name (must exist)

storage:
  minio:
    endpoint: my-minio.default.svc.cluster.local
    ssl: false
    port: 9000
    accessKey: "minioadmin"
    secretKey: "minioadmin"
  s3:
    region: "eu-central-1"
    endpoint: https://s3.eu-central-1.amazonaws.com
    accessKey: "your-s3-access-key"
    secretKey: "your-s3-secret-key"
```

**Steps:**
1. Create the destination S3 bucket in AWS
2. Configure MinIO and S3 credentials
3. **Switch ReportPortal to use S3 storage** (update ReportPortal configuration to point to S3)
4. Deploy: `helm install minio-to-s3 reportportal-migrations/migrations-complex -f values.yaml`
5. **ReportPortal can continue working normally** - users can access ReportPortal while migration runs
6. Monitor progress: `kubectl logs -f job/minio-to-s3-migrations-complex`
7. Migration will copy all data from MinIO to S3 in the background
8. Once migration completes, clean up: `helm uninstall minio-to-s3`

---

### Scenario 4: Complete Migration (All Steps)

**Use case:** Full migration: API keys + consolidate buckets + move to S3

**Configuration:**
```yaml
migrations:
  database:
    apiKeys:
      enabled: true
  storage:
    multiBucketToSingleBucket:
      enabled: true
      destinationType: minio
      buckets:
        projectPrefix: "prj-"
        pluginsBucket: "rp-bucket"
        singleBucketName: "rp-storage"
    minioToS3:
      enabled: true
      buckets:
        sourceBucket: "rp-storage"  # Must match singleBucketName above
        destinationBucket: "rp-s3-storage"

storage:
  minio:
    endpoint: my-minio.default.svc.cluster.local
    ssl: false
    port: 9000
    accessKey: "minioadmin"
    secretKey: "minioadmin"
  s3:
    region: "eu-central-1"
    endpoint: https://s3.eu-central-1.amazonaws.com
    accessKey: "your-s3-access-key"
    secretKey: "your-s3-secret-key"

database:
  endpoint: my-postgresql.default.svc.cluster.local
  port: 5432
  user: rpuser
  dbName: reportportal
  password: "your-password"
```

**Execution Order:**
1. API Keys Migration runs first (requires downtime)
2. Multi-Bucket to Single-Bucket runs second (requires downtime)
3. MinIO to S3 runs third (no downtime - ReportPortal can run)

**Steps:**
1. ⚠️ **Stop ReportPortal** before starting migrations
2. Create destination S3 bucket
3. Configure all credentials
4. Deploy: `helm install complete-migration reportportal-migrations/migrations-complex -f values.yaml`
5. Monitor: `kubectl logs -f job/complete-migration-migrations-complex`
6. **After API Keys and Multi-to-Single migrations complete**, switch ReportPortal to use S3 storage and **restart ReportPortal**
7. ReportPortal can now run normally while MinIO-to-S3 migration continues in the background
8. After all migrations complete, clean up: `helm uninstall complete-migration`

---

## Installation Steps

### Step-by-Step Guide

1. **Add Helm Repository**
   ```bash
   helm repo add reportportal-migrations https://reportportal.io/migrations-complex/
   helm repo update
   ```

2. **Download Default Values**
   ```bash
   helm show values reportportal-migrations/migrations-complex > migration-values.yaml
   ```

3. **Edit Configuration**
   - Open `migration-values.yaml` in your editor
   - Enable the migrations you need
   - Fill in all required credentials
   - Adjust resource limits if needed for large datasets

4. **Verify Configuration**
   ```bash
   # Validate your values file
   helm template test-migration reportportal-migrations/migrations-complex -f migration-values.yaml --debug
   ```

5. **Deploy Migration**
   ```bash
   helm install my-migration reportportal-migrations/migrations-complex -f migration-values.yaml
   ```

6. **Monitor Progress**
   ```bash
   # Watch pod status
   kubectl get pods -l app.kubernetes.io/name=migrations-complex -w
   
   # View logs
   kubectl logs -f -l app.kubernetes.io/name=migrations-complex
   
   # Check job status
   kubectl get job -l app.kubernetes.io/name=migrations-complex
   ```

7. **Verify Completion**
   ```bash
   # Job should show "1/1 Completed"
   kubectl get job -l app.kubernetes.io/name=migrations-complex
   
   # Check pod logs for success messages
   kubectl logs -l app.kubernetes.io/name=migrations-complex | tail -20
   ```

8. **Clean Up**
   ```bash
   helm uninstall my-migration
   ```

---

## Troubleshooting

### Common Issues

#### Migration Job Fails to Start
**Symptoms:** Pod stays in `Pending` state

**Solutions:**
- Check resource availability: `kubectl describe pod <pod-name>`
- Verify image pull secrets if using private registry
- Check node selectors and tolerations match your cluster

#### Database Connection Errors
**Symptoms:** Logs show database connection failures

**Solutions:**
- Verify database endpoint is correct and accessible from the pod
- Check database credentials (password or secret)
- Ensure database allows connections from the pod's network
- Test connection: `kubectl run -it --rm debug --image=postgres:13 -- psql -h <db-endpoint> -U <user> -d <dbname>`

#### Storage Access Errors
**Symptoms:** Logs show "access denied" or "bucket not found"

**Solutions:**
- Verify storage credentials are correct
- Check bucket exists and is accessible
- For S3: Verify IAM permissions allow read/write operations
- For MinIO: Verify endpoint, SSL, and port settings
- Test access manually using AWS CLI or MinIO client

#### Migration Takes Too Long
**Symptoms:** Migration runs for hours without progress

**Solutions:**
- Increase resource limits (CPU and memory)
- Check network bandwidth between cluster and storage
- Verify storage system performance
- Monitor pod resource usage: `kubectl top pod <pod-name>`

#### "Bucket Already Exists" Error
**Symptoms:** Error when creating destination bucket

**Solutions:**
- Bucket may already exist - this is usually fine
- Ensure bucket is empty if reusing
- Check bucket naming conflicts

#### Migration Partially Completes
**Symptoms:** Some data migrated, but not all

**Solutions:**
- Check logs for specific errors
- Verify source buckets are accessible
- Ensure sufficient storage space in destination
- Check for network interruptions during migration

### Getting Help

1. **Check Logs:**
   ```bash
   kubectl logs -l app.kubernetes.io/name=migrations-complex --tail=100
   ```

2. **Describe Job/Pod:**
   ```bash
   kubectl describe job <job-name>
   kubectl describe pod <pod-name>
   ```

3. **Verify Configuration:**
   ```bash
   helm get values my-migration
   ```

4. **Test Connectivity:**
   ```bash
   # Test database
   kubectl run -it --rm test-db --image=postgres:13 -- psql -h <db-endpoint> -U <user>
   
   # Test MinIO
   kubectl run -it --rm test-minio --image=minio/mc -- mc alias set myminio http://<endpoint>:<port> <accesskey> <secretkey>
   
   # Test S3
   kubectl run -it --rm test-s3 --image=amazon/aws-cli -- aws s3 ls --endpoint-url <s3-endpoint>
   ```

### Best Practices

- ✅ **Always backup your database** before running migrations
- ✅ **Test migrations in a staging environment** first
- ✅ **Stop ReportPortal** during storage migrations to avoid conflicts
- ✅ **Monitor resource usage** and adjust limits for large datasets
- ✅ **Verify bucket names** match between sequential migrations
- ✅ **Keep migration job logs** for troubleshooting
- ✅ **Use Kubernetes secrets** for sensitive credentials instead of plain text

---

## Additional Resources

- [ReportPortal Documentation](https://reportportal.io)
- [Helm Documentation](https://helm.sh/docs/)
- [Kubernetes Jobs](https://kubernetes.io/docs/concepts/workloads/controllers/job/)
