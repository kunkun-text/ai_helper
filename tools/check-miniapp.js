// 小程序静态自检：JS 语法 / app.json 页面四件套 / require 路径 / wxml 标签闭合与 bind 事件存在性 / wx:for 缺 wx:key
// 用法：node tools/check-miniapp.js [小程序根目录]
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const root = path.resolve(process.argv[2] || path.join(__dirname, '../src/main/resources/static/Ai'));
let errors = 0, warns = 0;
const err = (m) => { errors++; console.log('  ✗ ' + m); };
const warn = (m) => { warns++; console.log('  ! ' + m); };

function walk(dir, ext, out = []) {
  for (const f of fs.readdirSync(dir)) {
    if (f.startsWith('.') || f === 'node_modules' || f === 'miniprogram_npm') continue;
    const p = path.join(dir, f);
    if (fs.statSync(p).isDirectory()) walk(p, ext, out); else if (p.endsWith(ext)) out.push(p);
  }
  return out;
}

console.log('[1] JS 语法（node --check）');
const jsFiles = walk(root, '.js');
for (const f of jsFiles) {
  try { execFileSync(process.execPath, ['--check', f], { stdio: 'pipe' }); }
  catch (e) { err(path.relative(root, f) + '\n' + e.stderr.toString()); }
}
console.log('  ' + jsFiles.length + ' 个 JS 文件已检查');

console.log('[2] JSON 解析 + app.json 页面四件套');
for (const f of walk(root, '.json')) {
  try { JSON.parse(fs.readFileSync(f, 'utf8')); } catch (e) { err('JSON 解析失败 ' + path.relative(root, f) + ': ' + e.message); }
}
const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
for (const p of app.pages) {
  for (const ext of ['.js', '.wxml', '.wxss', '.json']) {
    if (!fs.existsSync(path.join(root, p + ext))) err('缺少 ' + p + ext);
  }
  const pj = JSON.parse(fs.readFileSync(path.join(root, p + '.json'), 'utf8'));
  for (const [name, comp] of Object.entries(pj.usingComponents || {})) {
    const base = comp.startsWith('/') ? path.join(root, comp) : path.join(root, path.dirname(p), comp);
    if (!fs.existsSync(base + '.js')) err(p + ' 组件 ' + name + ' 无法解析 ' + comp);
  }
}
console.log('  ' + app.pages.length + ' 个页面已检查');

console.log('[3] require 路径可解析');
for (const f of jsFiles) {
  const src = fs.readFileSync(f, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  const re = /require\(\s*['"]([^'"]+)['"]\s*\)/g; let m;
  while ((m = re.exec(src))) {
    const t = m[1];
    if (!t.startsWith('.') && !t.startsWith('/')) continue;
    const abs = t.startsWith('/') ? path.join(root, t) : path.resolve(path.dirname(f), t);
    if (!fs.existsSync(abs) && !fs.existsSync(abs + '.js')) err(path.relative(root, f) + ' require 不存在: ' + t);
  }
}

console.log('[4] WXML 标签闭合 / 事件处理函数存在 / wx:for 带 wx:key / navigateTo 目标已注册');
// 小程序里允许不写闭合标签的只有这几类（其余必须成对或自闭合）
const VOID = new Set(['input', 'image', 'icon', 'import', 'include', 'br']);
for (const p of app.pages) {
  const wxml = fs.readFileSync(path.join(root, p + '.wxml'), 'utf8').replace(/<!--[\s\S]*?-->/g, '');
  const js = fs.readFileSync(path.join(root, p + '.js'), 'utf8');
  const stack = [];
  const tagRe = /<(\/?)([a-zA-Z][\w-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(\/?)>/g; let m;
  while ((m = tagRe.exec(wxml))) {
    const [, close, name, attrs, self] = m;
    if (close) {
      const top = stack.pop();
      if (top !== name) { err(p + '.wxml 标签不匹配: 期望 </' + top + '> 实得 </' + name + '>'); break; }
    } else if (!self && !VOID.has(name)) stack.push(name);
    if (!close && /wx:for=/.test(attrs) && !/wx:key=/.test(attrs)) warn(p + '.wxml wx:for 缺少 wx:key: <' + name + attrs.slice(0, 60) + '…>');
    const evRe = /(?:bind|catch|capture-bind|capture-catch):?([a-z]+)="([^"{}]+)"/g; let e;
    while ((e = evRe.exec(attrs))) {
      const h = e[2].trim();
      if (!new RegExp('(^|[\\s,{])' + h.replace(/[$]/g, '\\$') + '\\s*(\\(|:\\s*(function|\\())').test(js)) err(p + '.wxml 事件 ' + e[1] + '="' + h + '" 在 js 中找不到处理函数');
    }
  }
  if (stack.length) err(p + '.wxml 未闭合标签: ' + stack.join(','));
  const navRe = /url\s*:\s*['"`]\/?(pages\/[\w-]+\/[\w-]+)/g;
  while ((m = navRe.exec(js))) if (!app.pages.includes(m[1])) err(p + '.js 跳转目标未注册: ' + m[1]);
}

console.log('[5] 前端裸用地址（应统一走 config.getBaseUrl()）');
for (const f of jsFiles) {
  if (f.endsWith('config.js')) continue;
  const src = fs.readFileSync(f, 'utf8');
  if (/https?:\/\/(localhost|127\.0\.0\.1|192\.168\.)/.test(src)) warn(path.relative(root, f) + ' 含硬编码地址');
}

console.log('\n结果：' + errors + ' 个错误，' + warns + ' 个警告');
process.exit(errors ? 1 : 0);
