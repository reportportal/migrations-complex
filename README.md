# [ReportPortal.io](http://ReportPortal.io) Migrations Complex Helm chart repository 
## Description

Our data migration service offers seamless and efficient transfer of data from multiple buckets to a single destination in either MinIO or Amazon S3, both of which provide reliable binary storage solutions. Whether you need to consolidate data from multiple sources into a central location or migrate your existing MinIO data to S3, our service ensures a smooth transition while preserving data integrity.

Key Features:

1. **Consolidation of Multiple Buckets**: If you have data spread across various buckets, our service simplifies the process of merging them into a single destination. We handle the complexities of transferring large volumes of data, maintaining file structures, and ensuring data consistency throughout the migration process.

2. **MinIO to S3 Migration**: Report Portal storage migration from multiple buckets to single bucket for S3 and MinIO. If you are looking to migrate your data from MinIO to Amazon S3, our service facilitates a seamless transition. We securely transfer your binary data while preserving metadata, permissions, and any custom configurations you have set up in your MinIO instance. This ensures that your data remains intact and accessible in the new storage environment.

3. **Database API Key Migration**: In addition to data migration, we also offer API key transfer services for databases. If you are transitioning to a new database platform or upgrading your existing one, we ensure the smooth transfer of API keys. Our service securely migrates the necessary credentials and ensures that your applications and services can continue to authenticate and access the database seamlessly.


> [Helm](https://helm.sh) must be installed to use the charts. Please refer to Helm's [documentation](https://helm.sh/docs) to get started.

> Link to README [reportportal/migrations-complex](https://github.com/reportportal/migrations-complex/blob/master/charts/README.md)

## Installing

### Install released version using Helm repository

Add the ReportPortal Helm charts repo: `helm repo add reportportal-migrations https://reportportal.io/migrations-complex/`

* Install it:
    * with Helm 3: `helm install migrations-complex reportportal-migrations/migrations-complex`
    * with Helm 2 (deprecated): `helm install --name migrations-complex reportportal-migrations/migrations-complex`

### Install custom version using Helm repository

* Install it:
    * with Helm 3: `helm install migrations-complex reportportal-migrations/migrations-complex --version=1.0.0`
    * with Helm 2 (deprecated): `helm install --name migrations-complex reportportal-migrations/migrations-complex --version=1.0.0`

### Install migrations-complex with custom values file using Helm repository

* Install it:
    * with Helm 3: `helm install migrations-complex --values=values.yaml reportportal-migrations/migrations-complex`
    * with Helm 2 (deprecated): `helm install --name migrations-complex --values=values.yaml reportportal-migrations/migrations-complex`

## Uninstalling

To remove Hlem Chart use the following command: `helm uninstall migrations-complex`
