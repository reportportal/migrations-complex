# Report Portral Migrations Complex Service

## Table of Contents
- [Report Portral Migrations Complex Service](#report-portral-migrations-complex-service)
  - [Table of Contents](#table-of-contents)
  - [Description](#description)
- [Parameters](#parameters)
  - [Image Parameters](#image-parameters)
  - [Common parameters](#common-parameters)
  - [Job.batch parameters](#jobbatch-parameters)
  - [Container environments](#container-environments)
  - [Dependencies](#dependencies)
    - [API keys migration](#api-keys-migration)
    - [Migration from multi-bucket system to single-bucket](#migration-from-multi-bucket-system-to-single-bucket)
    - [MinIO to S3 migration](#minio-to-s3-migration)
  - [Installation steps](#installation-steps)
    - [API keys migration](#api-keys-migration-1)
    - [Migration from multi-bucket system to single-bucket](#migration-from-multi-bucket-system-to-single-bucket-1)
    - [Migration from multi-bucket system to single-bucket](#migration-from-multi-bucket-system-to-single-bucket-2)
    - [Migration from MinIO single-bucket to S3 single-bucket](#migration-from-minio-single-bucket-to-s3-single-bucket)
    - [All steps at once in one run](#all-steps-at-once-in-one-run)


## Description

Our data migration service offers seamless and efficient transfer of data from multiple buckets to a single destination in either MinIO or Amazon S3, both of which provide reliable binary storage solutions. Whether you need to consolidate data from multiple sources into a central location or migrate your existing MinIO data to S3, our service ensures a smooth transition while preserving data integrity.

Key Features:

1. **Consolidation of Multiple Buckets**: If you have data spread across various buckets, our service simplifies the process of merging them into a single destination. We handle the complexities of transferring large volumes of data, maintaining file structures, and ensuring data consistency throughout the migration process.

2. **MinIO to S3 Migration**: Report Portal storage migration from multiple buckets to single bucket for S3 and MinIO. If you are looking to migrate your data from MinIO to Amazon S3, our service facilitates a seamless transition. We securely transfer your binary data while preserving metadata, permissions, and any custom configurations you have set up in your MinIO instance. This ensures that your data remains intact and accessible in the new storage environment.

3. **Database API Key Migration**: In addition to data migration, we also offer API key migrations services for databases. If you are transitioning to a new database platform or upgrading your existing one, we ensure the smooth transfer of API keys.
# Parameters
## Image Parameters
|Name|Description|Value|
|-|-|-|
|`image.repository`|Image repository|`reportportal/migrations-complex`|
|`image.tag`|Image tag|`1.0.0`|
|`image.pullPolicy`|Image pull policy|`IfNotPresent`|
|`imagePullSecrets`|Specify docker-registry secret names as an array|`[]`|

## Common parameters
|Name|Description|Value|
|-|-|-|
|`nameOverride`|String to partially override rabbitmq.fullname template (will maintain the release name)|`""`|
|`fullnameOverride`|String to fully override rabbitmq.fullname template|`""`|

## Job.batch parameters
|Name|Description|Value|
|-|-|-|
|`podAnnotations`|Pod annotations. Evaluated as a template|`{}`|
|`podSecurityContext`|Security Context|`{}`|
|`securityContext`|Container Security Context|`{}`|
|`resources.limits`|The limits limits for container|`{}`|
|`resources.requests`|The resources limits for container|`{}`|
|`nodeSelector`|Node labels for pod assignment. Evaluated as a template|`{}`|
|`tolerations`|Tolerations for pod assignment. Evaluated as a template|`[]`|
|`affinity`|Pod affinity|`[]`|

## Container environments
|Name|Description|Value|
|-|-|-|
|`apiKey.enabled`|Enable API key migrations|`false`|
|`multiToSingle.enabled`|Enable storage migration from multiple buckets to single bucket for S3 and MinIO|`false`|
|`multiToSingle.storageType`|Switching between MinIO and S3 storages (parametes: `minio`, `s3`)|`minio`|
|`multiToSingle.removeAfterMigration`|Allow files to be deleted after migration|`false`|
|`multiToSingle.bucket.bucketPrefix`|Bucket prefix on FS|`prj-`|
|`multiToSingle.bucket.bucketForPlugins`|Bucket name for storing Plugins|`rp-bucket`|
|`multiToSingle.bucket.bucketSingleName`|A new single bucket to which the data will be migrated|`rp-storage`|
|`singleMinioToSingleS3.enable`|Enable storage migration from single bucket MinIO to single bucket S3|`false`|
|`singleMinioToSingleS3.bucket.fromMinioBucket`|MinIO bucket from which data will migrate. Must be equal to `multiToSingle.bucket.bucketSingleName`|`rp-storage`|
|`singleMinioToSingleS3.bucket.toS3Bucket`|S3 bucket where data will be migrated to|`rp-s3-storage`|
|`minio.secretName`|Secret name with access and secret key for MinIO|`""`|
|`minio.accesskey`|MinIO access key. Required if `SecretName` is not specified|`""`|
|`minio.secretkey`|Minio Secret key. Required if `SecretName` is not specified|`""`|
|`minio.accesskeyName`|Key form K8s Secret for Access key (`.data.access-key`)|`access-key`|
|`minio.secretkeyName`|Key form K8s Secret for Secret key (`.data.secret-key`)|`secret-key`|
|`minio.endpoint`|MinIO endpoint|`http://<minio-release-name>-minio.default.svc.cluster.local:9000`|
|`s3.region`|Amazon S3 Region|`us-west-3`|
|`s3.secretName`|Secret name with access and secret key for S3|`""`|
|`s3.accesskey`|S3 access key. Required if `SecretName` is not specified|`""`|
|`s3.secretkey`|S3 Secret key. Required if `SecretName` is not specified|`""`|
|`s3.accesskeyName`|Key form K8s Secret for Access key (`.data.access-key`)|`s3-access-key`|
|`s3.secretkeyName`|Key form K8s Secret for Secret key (`.data.secret-key`)|`s3-secret-key`|
|`s3.endpoint`|S3 endpoint. S3 enpoints ref: [https://docs.aws.amazon.com/general/latest/gr/s3.html](https://docs.aws.amazon.com/general/latest/gr/s3.html) |`s3.eu-west-3.amazonaws.com`|
|`database.secretName`|Secret name for database|`""`|
|`database.passwordkKeyName`|Key form K8s Secret|`"postgresql-password"`|
|`database.endpoint`|Database URL|`<postgresql-release-name>-postgresql.default.svc.cluster.local`|
|`database.port`|Database port|`5432`|
|`database.user`|Database user name|`rpuser`|
|`database.dbName`|Database name|`reportportal`|
|`database.password`|Database pasword. Required if `secretName` is not specified|`""`|

## Dependencies

### API keys migration

To migrate API keys, you must specify:
1. Enable migration by `apiKey.enabled=true`.
2. Database values

```yaml
apiKey:
  enabled: false

database:
  secretName: ""
  passwordkKeyName: "postgresql-password"
  endpoint: <postgresql-release-name>-postgresql.default.svc.cluster.local
  port: 5432
  user: rpuser
  dbName: reportportal
  password:
```

### Migration from multi-bucket system to single-bucket

To migrate from multi-bucket system to single-bucket you must specify:
1. Enable migration by `multiToSingle.enabled=true`.
2. Storage values `minio` if `multiToSingle.storageType=minio`, or `s3` if `multiToSingle.storageType=s3`
3. Database values.

```yaml
multiToSingle:
  enable: false
  ## Where will migration processing take place?
  storageType: minio
  # type: minio / s3
  removeAfterMigration: false
  bucket: 
    bucketPrefix: "prj-"
    ## bucket name for storing Plugins
    bucketForPlugins: "rp-bucket"
    ## A new single bucket to which the data will be migrated
    bucketSingleName: "rp-storage"

minio:
  secretName: ""
  accesskey: <minio-accesskey>
  secretkey: <minio-secretkey>
  accesskeyName: "access-key"
  secretkeyName: "secret-key"
  endpoint: http://<minio-release-name>-minio.default.svc.cluster.local:9000
s3: 
  region: "us-west-3"
  secretName: ""
  accesskey: <s3-accesskey>
  secretkey: <s3-secretkey>
  accesskeyName: "s3-access-key"
  secretkeyName: "s3-secret-key"
  ## S3 enpoints ref: https://docs.aws.amazon.com/general/latest/gr/s3.html
  endpoint: http://s3.eu-west-3.amazonaws.com

database:
  secretName: ""
  passwordkKeyName: "postgresql-password"
  endpoint: <postgresql-release-name>-postgresql.default.svc.cluster.local
  port: 5432
  user: rpuser
  dbName: reportportal
  password:
```

### MinIO to S3 migration

To migrate from MinIO single-bucket to S3 single-bucket you must specify:
1. Enable migration by `singleMinioToSingleS3.enabled=true`.
2. Buckets names.
3. MinIO values.
4. S3 values.

```yaml
singleMinioToSingleS3:
  enable: fasle
  bucket:
    fromMinioBucket: "rp-storage"
    toS3Bucket: "rp-s3-storage"

minio:
  secretName: ""
  accesskey: <minio-accesskey>
  secretkey: <minio-secretkey>
  accesskeyName: "access-key"
  secretkeyName: "secret-key"
  endpoint: http://<minio-release-name>-minio.default.svc.cluster.local:9000
s3: 
  region: "us-west-3"
  secretName: ""
  accesskey: <s3-accesskey>
  secretkey: <s3-secretkey>
  accesskeyName: "s3-access-key"
  secretkeyName: "s3-secret-key"
  ## S3 enpoints ref: https://docs.aws.amazon.com/general/latest/gr/s3.html
  endpoint: http://s3.eu-west-3.amazonaws.com

```

## Installation steps

Add the ReportPortal Helm charts repo: `helm repo add reportportal-migrations https://reportportal.io/migrations-complex/`

> You can migrate all 3 steps at once in one run


### API keys migration

If you want to migrate your access tokens to API keys you need to do the following steps:

1. Download Complex Migration values: 
```bash
helm show values reportportal-migrations/migrations-complex > complex-migration-values.yaml
```
2. Enable API keys migration by `apiKey.enabled=true` [here](https://github.com/reportportal/migrations-complex/blob/master/charts/values.yaml#L4).
3. Fill in the [database values](https://github.com/reportportal/migrations-complex/blob/master/charts/values.yaml#L48)
4. Deploy the chart: 
```bash
helm install api-key-migrations \
  -f complex-migration-values.yaml \
  reportportal-migrations/migrations-complex
```
5. When the Job gets status `0/1 Completed` you can delete the service:
```bash
helm uninstall api-key-migrations
```

> Note: Your oauth_access_token table will be dropped after the migration.

> ⚠️ This step is irreversible and will permanently delete all access tokens from the database.


### Migration from multi-bucket system to single-bucket

> ⚠️ Note: This step will lead to the downtime of ReportPortal as attachments table will be blocked.

To switch from multiple buckets to single, follow these steps:
1. Download Complex Migration values: 
```bash
helm show values reportportal-migrations/migrations-complex > complex-migration-values.yaml
```
2. Enable API keys migration by `multiToSingle.enabled=true` [here](https://github.com/reportportal/migrations-complex/blob/master/charts/values.yaml#L24).
3. Fill in the [database values](https://github.com/reportportal/migrations-complex/blob/master/charts/values.yaml#L48)
4. If you are migrating to MinIO, use [minio values](https://github.com/reportportal/migrations-complex/blob/master/charts/values.yaml#L32), or if you are migrating to S3, use the following [s3 values](https://github.com/reportportal/migrations-complex/blob/master/charts/values.yaml#L39).
4. Deploy the chart: 
```bash
helm install multi-single-migrations \
  -f complex-migration-values.yaml \
  reportportal-migrations/migrations-complex
```
5. When the Job gets status `0/1 Completed` you can delete the service:
```bash
helm uninstall multi-single-migrations
```

### Migration from multi-bucket system to single-bucket

> ⚠️ Note: This step will lead to the downtime of ReportPortal as attachments table will be blocked.

To switch from multiple buckets to single, follow these steps:
1. Uninstall ReportPortal `helm uninstall reportportal`
2. Download Complex Migration values: 
```bash
helm show values reportportal-migrations/migrations-complex > complex-migration-values.yaml
```
3. Enable migration by `multiToSingle.enabled=true` [here](https://github.com/reportportal/migrations-complex/blob/master/charts/values.yaml#L24).
4. Choose [storage type](https://github.com/reportportal/migrations-complex/blob/master/charts/values.yaml#L11)
5. Change your bucket [preferences](https://github.com/reportportal/migrations-complex/blob/master/charts/values.yaml#L14) if you're not using the default reporting portal settings.
6. Specify the [name of the single bucket](https://github.com/reportportal/migrations-complex/blob/master/charts/values.yaml#L19) where the data will be transferred.
7. If you are migrating to MinIO, use [minio values](https://github.com/reportportal/migrations-complex/blob/master/charts/values.yaml#L32), or if you are migrating to S3, use the following [s3 values](https://github.com/reportportal/migrations-complex/blob/master/charts/values.yaml#L39).
8. Fill in the [database values](https://github.com/reportportal/migrations-complex/blob/master/charts/values.yaml#L48)
9. Deploy the chart: 
```bash
helm install multi-single-migrations \
  -f complex-migration-values.yaml \
  reportportal-migrations/migrations-complex
```
10. When the Job gets status `0/1 Completed` you can delete the service:
```bash
helm uninstall multi-single-migrations
```
11. Change ReportPortal values to switch from multi bucket to singe.
    - [Switch from multi to single](https://github.com/reportportal/kubernetes/blob/release/23.2/reportportal/values.yaml#L460)
    - [Specify bucket name](https://github.com/reportportal/kubernetes/blob/release/23.2/reportportal/values.yaml#L465)
12. Deploy Report Portal.

### Migration from MinIO single-bucket to S3 single-bucket

To switch from single bucket MinIO to single bucket S3, follow the following steps:

1. Create Amazon S3 bucket `reportportal-datastore`
2. Download Complex Migration values: 
```bash
helm show values reportportal-migrations/migrations-complex > complex-migration-values.yaml
```
3. Enable migration by `singleMinioToSingleS3.enabled=true` [here](https://github.com/reportportal/migrations-complex/blob/master/charts/values.yaml#L24).
4. Fill in the MinIO and S3 values.
5. Deploy the chart: 
```bash
helm install multi-single-migrations \
  -f complex-migration-values.yaml \
  reportportal-migrations/migrations-complex
```
6. Upgrade ReportPortal with [new values](https://github.com/reportportal/kubernetes/blob/release/23.2/reportportal/values.yaml#L441):
    - `storage.type=s3` - switch to Amazon S3
    - `storage.secretName=S3-access-keys` - access and secret key to S3
    - `storage.region=us-west-3` - bucket region
    - `storage.bucket.type=single` - switch from multi to single bucket
    - `storage.bucket.bucketDefaultName=reportportal-datastore` - Amazon S3 bucket name.
7. When the Job gets status `0/1 Completed` you can delete the service:
```bash
helm uninstall multi-single-migrations
```

### All steps at once in one run

1. Uninstall ReportPortal
2. Download Complex Migration values: 
```bash
helm show values reportportal-migrations/migrations-complex > complex-migration-values.yaml
```
3. Enable all migrations with `true` flag.
4. Fill in all values.
5. Deploy the chart: 
```bash
helm install multi-single-migrations \
  -f complex-migration-values.yaml \
  reportportal-migrations/migrations-complex
```
6. Once the migration from MinIO single to S3 single bucket is running (you can catch this from the logs), deploy the ReportPortal back `helm install reportportal`
7. When the Job gets status `0/1 Completed` you can delete the service:
```bash
helm uninstall multi-single-migrations
```