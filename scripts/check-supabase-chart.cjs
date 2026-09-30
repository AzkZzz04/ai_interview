const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const yaml = require('../apps/web/node_modules/js-yaml');

process.chdir(path.resolve(__dirname, '..'));
const render = (extra = []) => cp.spawnSync('helm', ['template', 'interview', 'deploy/ai-interview', ...extra], { encoding: 'utf8' });
const parse = result => {
  assert.equal(result.status, 0, result.stderr);
  return yaml.loadAll(result.stdout).filter(Boolean);
};
const example = ['-f', 'deploy/ai-interview/values-supabase.example.yaml'];
const local = parse(render()), remote = parse(render(example));
const postgres = r => r.metadata.name.endsWith('-postgres');
assert(local.some(r => r.kind === 'StatefulSet' && postgres(r)));
assert(local.some(r => r.kind === 'Secret' && postgres(r)));
assert(!remote.some(r => ['StatefulSet', 'Secret', 'Service'].includes(r.kind) && postgres(r)));
assert.equal(remote.find(r => r.kind === 'PersistentVolumeClaim').metadata.annotations['helm.sh/resource-policy'], 'keep');
const config = remote.find(r => r.kind === 'ConfigMap' && r.metadata.name.endsWith('-app')).data;
assert.equal(config.SPRING_PROFILES_ACTIVE, 'supabase');
assert.equal(config.SUPABASE_SSL_ROOT_CERT, '/etc/supabase/ca.crt');
assert.equal(config.DATABASE_MAX_POOL_SIZE, '4');
assert(!('DATABASE_URL' in config) && !('DATABASE_USERNAME' in config));
assert.equal(config.REDIS_KEY_PREFIX, 'ai-interview:supabase-mjzycnjhtwyqcblwbjvy:');
assert(config.S3_BUCKET.includes('supabase-mjzycnjhtwyqcblwbjvy'));
for (const mode of ['api', 'worker']) {
  const pod = remote.find(r => r.kind === 'Deployment' && r.metadata.name.endsWith('-' + mode)).spec.template.spec;
  const app = pod.containers[0], env = Object.fromEntries(app.env.map(e => [e.name, e]));
  assert.equal('SUPABASE_SECRET_KEY' in env, mode === 'api');
  for (const key of ['DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD']) {
    assert.deepEqual(env[key].valueFrom.secretKeyRef, { name: 'ai-interview-supabase-runtime', key });
  }
  assert(!('SUPABASE_MIGRATION_PASSWORD' in env));
  assert(app.envFrom.every(e => !('secretRef' in e)));
  assert.equal(app.volumeMounts[0].readOnly, true);
  assert.equal(pod.volumes[0].secret.secretName, 'ai-interview-supabase-ca');
  const waits = pod.initContainers[0].command.at(-1);
  assert(!waits.includes('-postgres') && waits.includes('-redis') && waits.includes('-localstack'));
  assert(app.image.endsWith(':supabase-migration'));
}
for (const option of ['externalDatabase.existingSecret=', 'supabase.sdkExistingSecret=', 'supabase.caExistingSecret=', 'supabase.url=', 'postgres.enabled=true']) {
  assert.notEqual(render([...example, '--set', option]).status, 0, option);
}
console.log('Supabase Helm contracts passed: both profiles, credential isolation, CA, PVC, namespaces, and five invalid configurations.');
