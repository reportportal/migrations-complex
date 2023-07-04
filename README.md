# complex-migrations
ReportPortal service for different migrations

## Copyright Notice
Licensed under the [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)
license (see the LICENSE file).

## Installation steps
To add this service to ReportPortal deployment you would need to
add this to your docker-compose file:
```
    complex-migrations:
        image: reportportal/complex-migrations:latest
        environment:
            ...
```

## Migrations functionality
Currently, this service supports three migrations:
1. [**Old tokens to API keys migration**](#old-tokens-to-api-keys-migration)
2. [**Migration of attachments from old multi-bucket system to single-bucket**](#migration-from-multi-bucket-system-to-single-bucket)
3. [**Migration from MinIO single-bucket to S3 single-bucket**](#migration-from-minio-single-bucket-to-s3-single-bucket)

Additionally, you can combine steps 2 and 3 to migrate your attachments from MinIO to S3([MinIO to S3 migration](#minio-to-s3-migration)).
If you want to run all migrations at once you just would need to pass corresponding environment variables with _'true'_ values.

## Old tokens to API keys migration
If you want to migrate your access tokens to API keys you need to do the following steps:
1. Add complex-migrations to your docker-compose file(check [Installation steps](#installation-steps))
2. Add `RP_TOKEN_MIGRATION: "true"` to your environment part
3. Add database environment variables like this:
```   
   RP_DB_HOST: postgres
   RP_DB_USER: <your-db-username>
   RP_DB_PASS: <your-db-password>
   RP_DB_NAME: reportportal
```
4. Deploy ReportPortal with your changes
> **Note:** Your _oauth_access_token_ table will be dropped after the migration.
> 
> ⚠️This step is irreversible and will permanently delete all access tokens from the database.

## Migration from multi-bucket system to single-bucket
Mostly, this will be used just as a first step for [MinIO to S3 migration](#minio-to-s3-migration).
> ⚠️**Note:** This step will lead to the downtime of ReportPortal as attachments table will be blocked.

To run this migration you would need to follow next steps:
1. Add complex-migrations to your docker-compose file(check [Installation steps](#installation-steps))
2. Add `RP_SINGLEBUCKET_MIGRATION: "true"` to your environment part
3. Add database environment variables like this:
```   
   RP_DB_HOST: postgres
   RP_DB_USER: <your-db-username>
   RP_DB_PASS: <your-db-password>
   RP_DB_NAME: reportportal
```
4.1 If you want to migrate in MinIO you would need to provide these environment variables:
```   
    DATASTORE_TYPE: minio
    DATASTORE_ACCESSKEY: <your-minio-access-key>
    DATASTORE_SECRETKEY: <your-minio-secret-key>
    DATASTORE_ENDPOINT: <minio-endpoint>(e.g. http://minio:9000)
    DATASTORE_BUCKETPREFIX: <your-prefix>(default value is prj-)
    DATASTORE_DEFAULTBUCKETNAME: <your-default-bucket-name>(default value is rp-bucket)
    DATASTORE_SINGLEBUCKETNAME: <your-single-bucket-name>
```
4.2 If you want to migrate in S3:
```   
    DATASTORE_TYPE: s3
    DATASTORE_ACCESSKEY: <your-s3-access-key>
    DATASTORE_SECRETKEY: <your-s3-secret-key>
    DATASTORE_REGION: <your-aws-region>(e.g. us-west-1)
    DATASTORE_BUCKETPREFIX: <your-prefix>(default value is prj-)
    DATASTORE_DEFAULTBUCKETNAME: <your-default-bucket-name>(default value is rp-bucket)
    DATASTORE_SINGLEBUCKETNAME: <your-single-bucket-name>
```
5. If you want the data to be removed after the migration you can add `DATASTORE_REMOVE_AFTER_MIGRATION: 'true'`
6. Deploy ReportPortal

## Migration from MinIO single-bucket to S3 single-bucket
Mostly, this will be used just as a second step for [MinIO to S3 migration](#minio-to-s3-migration).
To run this migration you would need to follow next steps:
1. Create bucket in S3
2. Add complex-migrations to your docker-compose file(check [Installation steps](#installation-steps))
3. Add `RP_MINIO_S3_MIGRATION: "true"` to your environment part
4. Provide these environment variables:
```   
    MINIO_ENDPOINT: <your-minio-endpoint>(e.g. http://minio:9000)
    MINIO_ACCESS_KEY: <your-minio-accesskey>
    MINIO_SECRET_KEY: <your-minio-secretkey>
    S3_ENDPOINT: <your-s3-endpoint> (e.g. http://s3.eu-central-1.amazonaws.com)
    S3_ACCESS_KEY: <your-s3-accesskey>
    S3_SECRET_KEY: <your-s3-secretkey>
    MINIO_SINGLE_BUCKET: <name-of-minio-single-bucket>
    S3_SINGLE_BUCKET: <name-of-s3-single-bucket>
```
5. Deploy **complex-migrations** service

## MinIO to S3 migration
If you want to migrate your attachments from old MinIO multi-bucket system to S3 single-bucket you need to follow next steps:
> ⚠️**Note:** Make sure that you have created S3 bucket before running migration.

1. Go through steps 1-5 from [**Migration of attachments from old multi-bucket system to single-bucket**](#migration-from-multi-bucket-system-to-single-bucket)
2. Go through steps 3-4 from [**Migration from MinIO single-bucket to S3 single-bucket**](#migration-from-minio-single-bucket-to-s3-single-bucket)
3. Deploy ReportPortal with complex-migration service
4. After migration is completed you can update your binary storage variables in services to use s3:
```
   DATASTORE_TYPE: s3
   DATASTORE_REGION: <your-aws-region>(e.g. us-west-1)
   DATASTORE_ACCESSKEY: <your-aws-acceskey>
   DATASTORE_SECRETKEY: <your-aws-secretkey>
   DATASTORE_DEFAULTBUCKETNAME: <your-singlebucket-name>
   RP_FEATURE_FLAGS: singleBucket
   It applies for services api, authorization and jobs.
```
5. Redeploy ReportPortal with new S3 configuration
> ⚠️**Note:** If you don't follow the steps properly, your integration with external systems can break, and you would need to recreate them .

