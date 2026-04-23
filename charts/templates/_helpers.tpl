{{/*
Expand the name of the chart.
*/}}
{{- define "migrations-complex.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "migrations-complex.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "migrations-complex.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "migrations-complex.labels" -}}
helm.sh/chart: {{ include "migrations-complex.chart" . }}
{{ include "migrations-complex.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "migrations-complex.selectorLabels" -}}
app.kubernetes.io/name: {{ include "migrations-complex.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "migrations-complex.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "migrations-complex.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
MinIO source endpoint URL: <scheme>://<host>[:<port>]
*/}}
{{- define "migrations-complex.sourceMinioUrl" -}}
{{- $scheme := ternary "https" "http" .Values.source.minio.ssl -}}
{{- $host := .Values.source.minio.endpoint -}}
{{- $port := .Values.source.minio.port -}}
{{- if $port -}}
{{- printf "%s://%s:%v" $scheme $host $port -}}
{{- else -}}
{{- printf "%s://%s" $scheme $host -}}
{{- end -}}
{{- end }}

{{/*
S3 destination endpoint URL.
Resolution order:
  1. `destination.s3.endpointOverride` — used as-is (must include scheme).
  2. `destination.s3.endpoint` — host only; combined with `ssl` and optional `port`.
  3. Derived default — `s3.<destination.s3.region>.amazonaws.com`.
*/}}
{{- define "migrations-complex.destinationS3Url" -}}
{{- if .Values.destination.s3.endpointOverride -}}
{{- .Values.destination.s3.endpointOverride -}}
{{- else -}}
{{- $scheme := ternary "https" "http" .Values.destination.s3.ssl -}}
{{- $host := .Values.destination.s3.endpoint -}}
{{- if not $host -}}
{{- $host = printf "s3.%s.amazonaws.com" .Values.destination.s3.region -}}
{{- end -}}
{{- $port := .Values.destination.s3.port -}}
{{- if $port -}}
{{- printf "%s://%s:%v" $scheme $host $port -}}
{{- else -}}
{{- printf "%s://%s" $scheme $host -}}
{{- end -}}
{{- end -}}
{{- end }}
