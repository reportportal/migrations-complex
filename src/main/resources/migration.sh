#!/bin/sh

mc alias set minio_al $MINIO_ENDPOINT $MINIO_ACCESS_KEY $MINIO_SECRET_KEY
mc alias set s3_al $S3_ENDPOINT "$S3_ACCESS_KEY" "$S3_SECRET_KEY"
mc mirror minio_al/$MINIO_SINGLE_BUCKET/integration-secrets s3_al/$S3_SINGLE_BUCKET/integration-secrets
mc mirror minio_al/$MINIO_SINGLE_BUCKET/plugins s3_al/$S3_SINGLE_BUCKET/plugins
mc mirror minio_al/$MINIO_SINGLE_BUCKET/user-data s3_al/$S3_SINGLE_BUCKET/user-data
mc mirror minio_al/$MINIO_SINGLE_BUCKET/project-data s3_al/$S3_SINGLE_BUCKET/project-data