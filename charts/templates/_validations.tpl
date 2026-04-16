{{/*
Validate database configuration (required for storage migration)
*/}}
{{- define "migrations-complex.validateDatabase" -}}
{{- if not .Values.database.endpoint }}
{{- fail "database.endpoint is required" }}
{{- end }}
{{- if not .Values.database.user }}
{{- fail "database.user is required" }}
{{- end }}
{{- if not .Values.database.dbName }}
{{- fail "database.dbName is required" }}
{{- end }}
{{- if not .Values.database.secretName }}
  {{- if not .Values.database.password }}
  {{- fail "Either database.secretName or database.password must be provided" }}
  {{- end }}
{{- end }}
{{- end }}

{{/*
Validate MinIO source + S3 destination for storage migration
*/}}
{{- define "migrations-complex.validateStorageMigration" -}}
{{- if not .Values.storage.minio.endpoint }}
{{- fail "storage.minio.endpoint is required (MinIO multi-bucket source)" }}
{{- end }}
{{- if not .Values.storage.minio.secretName }}
  {{- if not .Values.storage.minio.accessKey }}
  {{- fail "Either storage.minio.secretName or storage.minio.accessKey must be provided" }}
  {{- end }}
  {{- if not .Values.storage.minio.secretKey }}
  {{- fail "Either storage.minio.secretName or storage.minio.secretKey must be provided" }}
  {{- end }}
{{- end }}
{{- if not .Values.storage.s3.endpoint }}
{{- fail "storage.s3.endpoint is required (AWS S3 API endpoint host)" }}
{{- end }}
{{- if not .Values.storage.s3.region }}
{{- fail "storage.s3.region is required (must match the destination bucket region)" }}
{{- end }}
{{- if not .Values.storage.s3.secretName }}
  {{- if not .Values.storage.s3.accessKey }}
  {{- fail "Either storage.s3.secretName or storage.s3.accessKey must be provided" }}
  {{- end }}
  {{- if not .Values.storage.s3.secretKey }}
  {{- fail "Either storage.s3.secretName or storage.s3.secretKey must be provided" }}
  {{- end }}
{{- end }}
{{- if not .Values.migration.destinationBucket }}
{{- fail "migration.destinationBucket is required (S3 single bucket name)" }}
{{- end }}
{{- end }}
