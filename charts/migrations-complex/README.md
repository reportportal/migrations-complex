# Report Portral Migrations Complex Service

Our data migration service offers seamless and efficient transfer of data from multiple buckets to a single destination in either MinIO or Amazon S3, both of which provide reliable binary storage solutions. Whether you need to consolidate data from multiple sources into a central location or migrate your existing MinIO data to S3, our service ensures a smooth transition while preserving data integrity.

Key Features:

1. **Consolidation of Multiple Buckets**: If you have data spread across various buckets, our service simplifies the process of merging them into a single destination. We handle the complexities of transferring large volumes of data, maintaining file structures, and ensuring data consistency throughout the migration process.

2. **MinIO to S3 Migration**: Report Portal storage migration from multiple buckets to single bucket for S3 and MinIO. If you are looking to migrate your data from MinIO to Amazon S3, our service facilitates a seamless transition. We securely transfer your binary data while preserving metadata, permissions, and any custom configurations you have set up in your MinIO instance. This ensures that your data remains intact and accessible in the new storage environment.

3. **Database API Key Migration**: In addition to data migration, we also offer API key transfer services for databases. If you are transitioning to a new database platform or upgrading your existing one, we ensure the smooth transfer of API keys. Our service securely migrates the necessary credentials and ensures that your applications and services can continue to authenticate and access the database seamlessly.
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