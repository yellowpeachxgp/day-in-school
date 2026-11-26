// 统一的验收证据脱敏工具。只做校验，不改写证据命令；调用方必须传入已脱敏模板。
// 敏感检测只使用通用形状和运行时环境值，不写死任何真实主机、账号或路径。

function validSuffix(value) {
  return typeof value === 'string' &&
    value.length > 0 &&
    !value.split('/').includes('..') &&
    !value.includes(String.fromCharCode(0)) &&
    !value.includes(String.fromCharCode(92));
}

export function isPublicCampusLogPath(value) {
  if (typeof value !== 'string') return false;
  if (value.startsWith('<campus-root>/logs/')) {
    return validSuffix(value.slice('<campus-root>/logs/'.length));
  }
  if (value.startsWith('campus://logs/')) {
    return validSuffix(value.slice('campus://logs/'.length));
  }
  return false;
}

export function isPublicArtifactPath(value) {
  if (typeof value !== 'string') return false;
  if (value.startsWith('campus://')) {
    return validSuffix(value.slice('campus://'.length));
  }
  if (!value.startsWith('https://')) return false;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' &&
      Boolean(url.hostname) &&
      !url.username &&
      !url.password &&
      !url.search &&
      !url.hash;
  } catch {
    return false;
  }
}

export function containsHomeOrAbsolutePath(value) {
  if (typeof value !== 'string') return false;
  const text = value;
  const lower = text.toLowerCase();
  if (lower.includes('/home/') || lower.includes('/users/') || lower.includes('/srv/') || lower.includes('/volumes/')) return true;
  if (lower.includes('file:///')) return true;
  const delimiters = [' ', '\t', '\r', '\n', '"', "'", '=', '(', '[', '{', '<'];
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    const previous = index === 0 ? '' : text[index - 1];
    const atBoundary = index === 0 || delimiters.includes(previous);
    if (char === '/' && atBoundary) {
      const next = text[index + 1] || '';
      if (/[A-Za-z0-9._~]/.test(next)) return true;
    }
    if (char === '~' && atBoundary) {
      const next = text[index + 1] || '';
      if (next === '/' || /[A-Za-z0-9_]/.test(next)) return true;
    }
    if (/[A-Za-z]/.test(char) && atBoundary && text[index + 1] === ':' && (text[index + 2] === '/' || text[index + 2] === String.fromCharCode(92))) return true;
  }
  return false;
}

export function runtimeSensitiveValues(explicitRoot) {
  const result = [];
  function add(value) {
    const clean = (value || '').trim();
    if (clean.length >= 3 && !clean.startsWith('<') && !clean.includes('CIS_DEVBOX')) result.push(clean);
  }
  add(explicitRoot || process.env.CIS_CAMPUS_ROOT || '');
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
    { name: '云 AccessKey 形状', pattern: new RegExp('AKIA[0-9A-Z]{16}') }
  ];
}

export function findSensitiveHits(value, explicitRoot) {
  if (typeof value !== 'string') return [];
  const hits = [];
  if (containsHomeOrAbsolutePath(value)) hits.push('Unix/macOS home 或绝对路径形状');
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
