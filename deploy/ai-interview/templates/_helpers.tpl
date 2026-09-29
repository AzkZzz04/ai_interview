{{- define "ai-interview.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "ai-interview.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else if contains .Chart.Name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name (include "ai-interview.name" .) | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "ai-interview.labels" -}}
app.kubernetes.io/name: {{ include "ai-interview.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end }}

{{- define "ai-interview.selectorLabels" -}}
app.kubernetes.io/name: {{ include "ai-interview.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "ai-interview.waitForDependencies" -}}
- name: wait-for-dependencies
  image: busybox:1.37
  command:
    - sh
    - -ec
    - |
      until nc -z {{ include "ai-interview.fullname" . }}-postgres {{ .Values.postgres.service.port }}; do sleep 2; done
      until nc -z {{ include "ai-interview.fullname" . }}-redis {{ .Values.redis.service.port }}; do sleep 2; done
      until nc -z {{ include "ai-interview.fullname" . }}-localstack {{ .Values.localstack.service.port }}; do sleep 2; done
{{- end -}}
