{{/*
Validate the ReportPortal database (source) configuration.
*/}}
{{- define "migrations-complex.validateDatabase" -}}
{{- if not .Values.source.database.endpoint }}
{{- fail "source.database.endpoint is required" }}
{{- end }}
{{- if not .Values.source.database.user }}
{{- fail "source.database.user is required" }}
{{- end }}
{{- if not .Values.source.database.dbName }}
{{- fail "source.database.dbName is required" }}
{{- end }}
{{- if not .Values.source.database.secretName }}
  {{- if not .Values.source.database.password }}
  {{- fail "Either source.database.secretName or source.database.password must be provided" }}
  {{- end }}
{{- end }}
{{- end }}

{{/*
Validate MinIO source + S3 destination for the storage migration.
*/}}
{{- define "migrations-complex.validateStorageMigration" -}}
{{/* MinIO source */}}
{{- if not .Values.source.minio.endpoint }}
{{- fail "source.minio.endpoint is required (MinIO multi-bucket source)" }}
{{- end }}
{{- if not .Values.source.minio.secretName }}
  {{- if not .Values.source.minio.accessKey }}
  {{- fail "Either source.minio.secretName or source.minio.accessKey must be provided" }}
  {{- end }}
  {{- if not .Values.source.minio.secretKey }}
  {{- fail "Either source.minio.secretName or source.minio.secretKey must be provided" }}
  {{- end }}
{{- end }}
{{/* S3 destination */}}
{{- if not .Values.destination.s3.region }}
{{- fail "destination.s3.region is required (must match the destination bucket region)" }}
{{- end }}
{{- if not .Values.destination.s3.bucket }}
{{- fail "destination.s3.bucket is required (S3 single bucket name)" }}
{{- end }}
{{- if not .Values.destination.s3.secretName }}
  {{- if not .Values.destination.s3.accessKey }}
  {{- fail "Either destination.s3.secretName or destination.s3.accessKey must be provided" }}
  {{- end }}
  {{- if not .Values.destination.s3.secretKey }}
  {{- fail "Either destination.s3.secretName or destination.s3.secretKey must be provided" }}
  {{- end }}
{{- end }}
{{- end }}
