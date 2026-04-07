{{/*
Expand the name of the chart.
*/}}
{{- define "routify.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
*/}}
{{- define "routify.fullname" -}}
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
{{- define "routify.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "routify.labels" -}}
helm.sh/chart: {{ include "routify.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: routify
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
{{- end }}

{{/* ─── Gateway ─────────────────────────────────────────────────────────────── */}}

{{- define "routify.gateway.fullname" -}}
{{- printf "%s-gateway" (include "routify.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "routify.gateway.labels" -}}
{{ include "routify.labels" . }}
{{ include "routify.gateway.selectorLabels" . }}
{{- end }}

{{- define "routify.gateway.selectorLabels" -}}
app.kubernetes.io/name: routify-gateway
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: gateway
{{- end }}

{{/* ─── Admin API ───────────────────────────────────────────────────────────── */}}

{{- define "routify.adminApi.fullname" -}}
{{- printf "%s-admin-api" (include "routify.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "routify.adminApi.labels" -}}
{{ include "routify.labels" . }}
{{ include "routify.adminApi.selectorLabels" . }}
{{- end }}

{{- define "routify.adminApi.selectorLabels" -}}
app.kubernetes.io/name: routify-admin-api
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: admin-api
{{- end }}

{{/* ─── Identity Service ────────────────────────────────────────────────────── */}}

{{- define "routify.identityService.fullname" -}}
{{- printf "%s-identity-service" (include "routify.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "routify.identityService.labels" -}}
{{ include "routify.labels" . }}
{{ include "routify.identityService.selectorLabels" . }}
{{- end }}

{{- define "routify.identityService.selectorLabels" -}}
app.kubernetes.io/name: routify-identity-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: identity-service
{{- end }}

{{/* ─── Route Service ───────────────────────────────────────────────────────── */}}

{{- define "routify.routeService.fullname" -}}
{{- printf "%s-route-service" (include "routify.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "routify.routeService.labels" -}}
{{ include "routify.labels" . }}
{{ include "routify.routeService.selectorLabels" . }}
{{- end }}

{{- define "routify.routeService.selectorLabels" -}}
app.kubernetes.io/name: routify-route-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: route-service
{{- end }}

{{/* ─── Audit Service ───────────────────────────────────────────────────────── */}}

{{- define "routify.auditService.fullname" -}}
{{- printf "%s-audit-service" (include "routify.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "routify.auditService.labels" -}}
{{ include "routify.labels" . }}
{{ include "routify.auditService.selectorLabels" . }}
{{- end }}

{{- define "routify.auditService.selectorLabels" -}}
app.kubernetes.io/name: routify-audit-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: audit-service
{{- end }}

{{/* ─── Cert Vault ──────────────────────────────────────────────────────────── */}}

{{- define "routify.certVault.fullname" -}}
{{- printf "%s-cert-vault" (include "routify.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "routify.certVault.labels" -}}
{{ include "routify.labels" . }}
{{ include "routify.certVault.selectorLabels" . }}
{{- end }}

{{- define "routify.certVault.selectorLabels" -}}
app.kubernetes.io/name: routify-cert-vault
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: cert-vault
{{- end }}

{{/* ─── AI Service ──────────────────────────────────────────────────────────── */}}

{{- define "routify.aiService.fullname" -}}
{{- printf "%s-ai-service" (include "routify.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "routify.aiService.labels" -}}
{{ include "routify.labels" . }}
{{ include "routify.aiService.selectorLabels" . }}
{{- end }}

{{- define "routify.aiService.selectorLabels" -}}
app.kubernetes.io/name: routify-ai-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: ai-service
{{- end }}

{{/* ─── Dashboard ───────────────────────────────────────────────────────────── */}}

{{- define "routify.dashboard.fullname" -}}
{{- printf "%s-dashboard" (include "routify.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "routify.dashboard.labels" -}}
{{ include "routify.labels" . }}
{{ include "routify.dashboard.selectorLabels" . }}
{{- end }}

{{- define "routify.dashboard.selectorLabels" -}}
app.kubernetes.io/name: routify-dashboard
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: dashboard
{{- end }}

{{/* ─── GitOps Agent ────────────────────────────────────────────────────────── */}}

{{- define "routify.gitopsAgent.fullname" -}}
{{- printf "%s-gitops-agent" (include "routify.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "routify.gitopsAgent.labels" -}}
{{ include "routify.labels" . }}
{{ include "routify.gitopsAgent.selectorLabels" . }}
{{- end }}

{{- define "routify.gitopsAgent.selectorLabels" -}}
app.kubernetes.io/name: routify-gitops-agent
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: gitops-agent
{{- end }}

{{/* ─── Secrets Helper ──────────────────────────────────────────────────────── */}}

{{/*
Return the name of the secret containing application credentials.
If existingSecret is set, use that; otherwise use the chart-generated secret.
*/}}
{{- define "routify.secretName" -}}
{{- if .Values.secrets.existingSecret }}
{{- .Values.secrets.existingSecret }}
{{- else }}
{{- printf "%s-secrets" (include "routify.fullname" .) }}
{{- end }}
{{- end }}

{{/* ─── Image Helper ────────────────────────────────────────────────────────── */}}

{{/*
Return a fully qualified image reference: registry/repo:tag
*/}}
{{- define "routify.image" -}}
{{- $registry := .global.imageRegistry | default "" -}}
{{- if $registry }}
{{- printf "%s/%s:%s" $registry .image.repository .image.tag }}
{{- else }}
{{- printf "%s:%s" .image.repository .image.tag }}
{{- end }}
{{- end }}

{{/* ─── Infrastructure Hosts ────────────────────────────────────────────────── */}}

{{- define "routify.postgresHost" -}}
{{- if .Values.postgresql.enabled }}
{{- printf "%s-postgresql" .Release.Name }}
{{- else }}
{{- .Values.externalPostgresql.host }}
{{- end }}
{{- end }}

{{- define "routify.redisHost" -}}
{{- if .Values.redis.enabled }}
{{- printf "%s-redis-master" .Release.Name }}
{{- else }}
{{- .Values.externalRedis.host }}
{{- end }}
{{- end }}

{{- define "routify.kafkaHost" -}}
{{- if .Values.kafka.enabled }}
{{- printf "%s-kafka:9092" .Release.Name }}
{{- else }}
{{- .Values.externalKafka.bootstrapServers }}
{{- end }}
{{- end }}

{{- define "routify.rabbitmqHost" -}}
{{- if .Values.rabbitmq.enabled }}
{{- printf "%s-rabbitmq" .Release.Name }}
{{- else }}
{{- .Values.externalRabbitmq.host }}
{{- end }}
{{- end }}

{{/* ─── Common Environment Variables ────────────────────────────────────────── */}}

{{/*
Environment variables common to all Java services (Kafka, RabbitMQ, tracing).
Usage: {{ include "routify.commonEnv" . | nindent 12 }}
*/}}
{{- define "routify.commonEnv" -}}
- name: KAFKA_BOOTSTRAP
  value: {{ include "routify.kafkaHost" . | quote }}
- name: RABBITMQ_HOST
  value: {{ include "routify.rabbitmqHost" . | quote }}
- name: RABBITMQ_PASS
  valueFrom:
    secretKeyRef:
      name: {{ include "routify.secretName" . }}
      key: rabbitmq-password
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.global.otelEndpoint | default "" | quote }}
{{- end }}

{{/*
Environment variables for services with database access.
Usage: {{ include "routify.dbEnv" . | nindent 12 }}
*/}}
{{- define "routify.dbEnv" -}}
- name: DB_HOST
  value: {{ include "routify.postgresHost" . | quote }}
- name: DB_USER
  value: {{ .Values.global.dbUser | default "routify" | quote }}
- name: DB_PASS
  valueFrom:
    secretKeyRef:
      name: {{ include "routify.secretName" . }}
      key: db-password
{{- end }}

{{/*
Environment variables for services needing Redis.
Usage: {{ include "routify.redisEnv" . | nindent 12 }}
*/}}
{{- define "routify.redisEnv" -}}
- name: REDIS_HOST
  value: {{ include "routify.redisHost" . | quote }}
- name: REDIS_PASS
  valueFrom:
    secretKeyRef:
      name: {{ include "routify.secretName" . }}
      key: redis-password
{{- end }}

{{/*
JWT public key environment variable (needed by most services).
*/}}
{{- define "routify.jwtPublicKeyEnv" -}}
- name: JWT_PUBLIC_KEY
  valueFrom:
    secretKeyRef:
      name: {{ include "routify.secretName" . }}
      key: jwt-public-key
{{- end }}

{{/*
JWT private + public key environment variables (identity-service only).
*/}}
{{- define "routify.jwtKeysEnv" -}}
- name: JWT_PRIVATE_KEY
  valueFrom:
    secretKeyRef:
      name: {{ include "routify.secretName" . }}
      key: jwt-private-key
- name: JWT_PUBLIC_KEY
  valueFrom:
    secretKeyRef:
      name: {{ include "routify.secretName" . }}
      key: jwt-public-key
{{- end }}

