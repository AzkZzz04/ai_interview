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
      {{- if .Values.postgres.enabled }}
      until nc -z {{ include "ai-interview.fullname" . }}-postgres {{ .Values.postgres.service.port }}; do sleep 2; done
      {{- end }}
      until nc -z {{ include "ai-interview.fullname" . }}-redis {{ .Values.redis.service.port }}; do sleep 2; done
      until nc -z {{ include "ai-interview.fullname" . }}-localstack {{ .Values.localstack.service.port }}; do sleep 2; done
{{- end -}}

{{- define "ai-interview.validateDatabase" -}}
{{- if not .Values.postgres.enabled -}}
{{- $databaseSecret := required "externalDatabase.existingSecret is required when postgres.enabled=false" .Values.externalDatabase.existingSecret -}}
{{- end -}}
{{- if .Values.supabase.enabled -}}
{{- if .Values.postgres.enabled -}}{{ fail "supabase.enabled requires postgres.enabled=false" }}{{- end -}}
{{- $url := required "supabase.url is required" .Values.supabase.url -}}
{{- $sdk := required "supabase.sdkExistingSecret is required" .Values.supabase.sdkExistingSecret -}}
{{- $ca := required "supabase.caExistingSecret is required" .Values.supabase.caExistingSecret -}}
{{- end -}}
{{- end -}}

{{- define "ai-interview.externalDatabaseEnv" -}}
{{- if not .Values.postgres.enabled }}
{{- range $key := list "DATABASE_URL" "DATABASE_USERNAME" "DATABASE_PASSWORD" }}
- name: {{ $key }}
  valueFrom:
    secretKeyRef:
      name: {{ $.Values.externalDatabase.existingSecret }}
      key: {{ $key }}
{{- end }}
{{- end }}
{{- end -}}

{{- define "ai-interview.caMount" -}}
{{- if .Values.supabase.enabled }}
volumeMounts:
  - name: supabase-ca
    mountPath: /etc/supabase
    readOnly: true
{{- end }}
{{- end -}}

{{- define "ai-interview.caVolume" -}}
{{- if .Values.supabase.enabled }}
volumes:
  - name: supabase-ca
    secret:
      secretName: {{ .Values.supabase.caExistingSecret }}
      items:
        - key: ca.crt
          path: ca.crt
{{- end }}
{{- end -}}
