{{/*
Validate that at least one migration is enabled
*/}}
{{- define "migrations-complex.validateMigrations" -}}
{{- $apiKeysEnabled := .Values.migrations.database.apiKeys.enabled }}
{{- $multiBucketEnabled := .Values.migrations.storage.multiBucketToSingleBucket.enabled }}
{{- $minioToS3Enabled := .Values.migrations.storage.minioToS3.enabled }}
{{- if not (or $apiKeysEnabled $multiBucketEnabled $minioToS3Enabled) }}
{{- fail "At least one migration must be enabled. Please enable at least one of: migrations.database.apiKeys.enabled, migrations.storage.multiBucketToSingleBucket.enabled, or migrations.storage.minioToS3.enabled" }}
{{- end }}
{{- end }}

{{/*
Validate database configuration when required
*/}}
{{- define "migrations-complex.validateDatabase" -}}
{{- $apiKeysEnabled := .Values.migrations.database.apiKeys.enabled }}
{{- $multiBucketEnabled := .Values.migrations.storage.multiBucketToSingleBucket.enabled }}
{{- if or $apiKeysEnabled $multiBucketEnabled }}
  {{- if not .Values.database.endpoint }}
  {{- fail "database.endpoint is required when database or storage migrations are enabled" }}
  {{- end }}
  {{- if not .Values.database.user }}
  {{- fail "database.user is required when database or storage migrations are enabled" }}
  {{- end }}
  {{- if not .Values.database.dbName }}
  {{- fail "database.dbName is required when database or storage migrations are enabled" }}
  {{- end }}
  {{- if not .Values.database.secretName }}
    {{- if not .Values.database.password }}
    {{- fail "Either database.secretName or database.password must be provided when database or storage migrations are enabled" }}
    {{- end }}
  {{- end }}
{{- end }}
{{- end }}

{{/*
Validate storage configuration for multiBucketToSingleBucket migration
*/}}
{{- define "migrations-complex.validateMultiBucketStorage" -}}
{{- if .Values.migrations.storage.multiBucketToSingleBucket.enabled }}
  {{- $destType := .Values.migrations.storage.multiBucketToSingleBucket.destinationType }}
  {{- if ne $destType "minio" }}
    {{- if ne $destType "s3" }}
    {{- fail "migrations.storage.multiBucketToSingleBucket.destinationType must be either 'minio' or 's3'" }}
    {{- end }}
  {{- end }}
  {{- if eq $destType "minio" }}
    {{- if not .Values.storage.minio.endpoint }}
    {{- fail "storage.minio.endpoint is required when destinationType is 'minio'" }}
    {{- end }}
    {{- if not .Values.storage.minio.secretName }}
      {{- if not .Values.storage.minio.accessKey }}
      {{- fail "Either storage.minio.secretName or storage.minio.accessKey must be provided when destinationType is 'minio'" }}
      {{- end }}
      {{- if not .Values.storage.minio.secretKey }}
      {{- fail "Either storage.minio.secretName or storage.minio.secretKey must be provided when destinationType is 'minio'" }}
      {{- end }}
    {{- end }}
  {{- else if eq $destType "s3" }}
    {{- if not .Values.storage.s3.endpoint }}
    {{- fail "storage.s3.endpoint is required when destinationType is 's3'" }}
    {{- end }}
    {{- if not .Values.storage.s3.region }}
    {{- fail "storage.s3.region is required when destinationType is 's3'" }}
    {{- end }}
    {{- if not .Values.storage.s3.secretName }}
      {{- if not .Values.storage.s3.accessKey }}
      {{- fail "Either storage.s3.secretName or storage.s3.accessKey must be provided when destinationType is 's3'" }}
      {{- end }}
      {{- if not .Values.storage.s3.secretKey }}
      {{- fail "Either storage.s3.secretName or storage.s3.secretKey must be provided when destinationType is 's3'" }}
      {{- end }}
    {{- end }}
  {{- end }}
{{- end }}
{{- end }}

{{/*
Validate storage configuration for minioToS3 migration
*/}}
{{- define "migrations-complex.validateMinioToS3" -}}
{{- if .Values.migrations.storage.minioToS3.enabled }}
  {{- if not .Values.storage.minio.endpoint }}
  {{- fail "storage.minio.endpoint is required when minioToS3 migration is enabled" }}
  {{- end }}
  {{- if not .Values.storage.minio.secretName }}
    {{- if not .Values.storage.minio.accessKey }}
    {{- fail "Either storage.minio.secretName or storage.minio.accessKey must be provided when minioToS3 migration is enabled" }}
    {{- end }}
    {{- if not .Values.storage.minio.secretKey }}
    {{- fail "Either storage.minio.secretName or storage.minio.secretKey must be provided when minioToS3 migration is enabled" }}
    {{- end }}
  {{- end }}
  {{- if not .Values.storage.s3.endpoint }}
  {{- fail "storage.s3.endpoint is required when minioToS3 migration is enabled" }}
  {{- end }}
  {{- if not .Values.storage.s3.region }}
  {{- fail "storage.s3.region is required when minioToS3 migration is enabled" }}
  {{- end }}
  {{- if not .Values.storage.s3.secretName }}
    {{- if not .Values.storage.s3.accessKey }}
    {{- fail "Either storage.s3.secretName or storage.s3.accessKey must be provided when minioToS3 migration is enabled" }}
    {{- end }}
    {{- if not .Values.storage.s3.secretKey }}
    {{- fail "Either storage.s3.secretName or storage.s3.secretKey must be provided when minioToS3 migration is enabled" }}
    {{- end }}
  {{- end }}
{{- end }}
{{- end }}
