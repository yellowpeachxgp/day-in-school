// 统一的验收证据脱敏工具。只使用通用形状和运行时环境值，不写死任何真实主机、账号或路径。

export function trimTrailingSlashes(value) {
  let result = (value || '').trim();
  while (result.length > 1 && result.endsWith('/')) result = result.slice(0, -1);
  return result;
}

export function configuredCampusRoot(explicitRoot) {
  return trimTrailingSlashes(explicitRoot || process.env.CIS_CAMPUS_ROOT || '');
}

export function validSuffix(value) {
  return typeof value === 'string' &&
    value.length > 0 &&
    !value.split('/').includes('..') &&
    !value.includes(String.fromCharCode(0)) &&
    !value.includes(String.fromCharCode(92));
}

function extractCampusSuffix(value) {
  if (!value.startsWith('/')) return null;
  const parts = value.split('/');
  for (let index = 1; index < parts.length - 1; index += 1) {
    if (parts[index] === 'campus') {
      const suffix = parts.slice(index + 1).join('/');
      return validSuffix(suffix) ? suffix : null;
    }
  }
  return null;
}

export function normalizeCampusPath(input, explicitRoot) {
  const value = (input || '').trim();
  if (!value) return null;
  if (value.startsWith('<campus-root>/')) {
    const suffix = value.slice('<campus-root>/'.length);
    return validSuffix(suffix) ? value : null;
  }
  if (value.startsWith('campus://')) {
    const suffix = value.slice('campus://'.length);
    return validSuffix(suffix) ? '<campus-root>/' + suffix : null;
  }
  const root = configuredCampusRoot(explicitRoot);
  if (root && root !== '/' && value.startsWith(root + '/')) {
    const suffix = value.slice(root.length + 1);
    return validSuffix(suffix) ? '<campus-root>/' + suffix : null;
  }
  const suffix = extractCampusSuffix(value);
  return suffix ? '<campus-root>/' + suffix : null;
}

export function normalizeCommand(input, explicitRoot) {
  if (typeof input !== 'string') return input;
  let output = input;
  const root = configuredCampusRoot(explicitRoot);
  if (root && root !== '/') output = output.split(root).join('<campus-root>');
  output = output.replace(new RegExp('/(?:[^/]+/)*campus(?=/)', 'g'), '<campus-root>');
  return output;
}

export function normalizeFreeText(input, explicitRoot) {
  return normalizeCommand(input, explicitRoot);
}

function normalizeGithubUrl(value) {
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:') return null;
    if (url.hostname !== 'github.com') return null;
    if (url.username || url.password || url.search || url.hash) return null;
    return value;
  } catch {
    return null;
  }
}

export function normalizeArtifactPath(input, explicitRoot) {
  const value = (input || '').trim();
  if (!value) return null;
  const campusPath = normalizeCampusPath(value, explicitRoot);
  if (campusPath) return campusPath;
  const githubUrl = normalizeGithubUrl(value);
  if (githubUrl) return githubUrl;
  if (!value.startsWith('/') && validSuffix(value) && !value.includes(':')) return value;
  return null;
}

export function runtimeSensitiveValues(explicitRoot) {
  const result = [];
  function add(value) {
    const clean = (value || '').trim();
    if (clean.length >= 3 && !clean.startsWith('<') && !clean.includes('CIS_DEVBOX')) result.push(clean);
  }
  add(configuredCampusRoot(explicitRoot));
  const devbox = (process.env.CIS_DEVBOX || '').trim();
  if (devbox) {
    add(devbox);
    const at = devbox.lastIndexOf('@');
    if (at >= 0) {
      add(devbox.slice(0, at));
      let host = devbox.slice(at + 1).split('/')[0];
      if (host.startsWith('[')) {
        const end = host.indexOf(']');
        host = end >= 0 ? host.slice(1, end) : host;
      } else {
        host = host.split(':')[0];
      }
      add(host);
    }
  }
  return [...new Set(result)];
}

export function forbiddenPatterns() {
  return [
    { name: 'IPv4 字面量', pattern: new RegExp('(?:^|[^0-9.])(?:[0-9]{1,3}[.]){3}[0-9]{1,3}(?:[^0-9.]|$)') },
    { name: 'user@host 形式的地址或账号', pattern: new RegExp('[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+') },
    { name: '私钥头', pattern: new RegExp('PRIVATE KEY-----') },
    { name: 'GitHub Token 形状', pattern: new RegExp('(ghp_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})') },
    { name: 'OpenAI 风格 Key 形状', pattern: new RegExp('sk-[A-Za-z0-9_-]{20,}') },
    { name: '云 AccessKey 形状', pattern: new RegExp('AKIA[0-9A-Z]{16}') },
    { name: '未脱敏 srv 路径', pattern: new RegExp('/srv/') },
    { name: '未脱敏 Volumes 路径', pattern: new RegExp('/Volumes/') }
  ];
}

export function findSensitiveHits(value, explicitRoot) {
  if (typeof value !== 'string') return [];
  const hits = [];
  for (const { name, pattern } of forbiddenPatterns()) {
    if (pattern.test(value)) hits.push(name);
  }
  for (const runtimeValue of runtimeSensitiveValues(explicitRoot)) {
    if (value.includes(runtimeValue)) {
      hits.push('运行时环境敏感值');
      break;
    }
  }
  return [...new Set(hits)];
}
