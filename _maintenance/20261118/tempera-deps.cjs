/* 计算 tempera 运行时入口的相对 import 闭包，列出 lumiere-src 里缺的文件 */
const fs = require('fs');
const path = require('path');
const FOLIA = 'C:/Users/Cuisi/Downloads/Mineradio-main/vendor-src/folia/src';
const PORT = 'D:/Terminal-Windows/build-folia/lumiere-src';
const ENTRIES = [
  'components/visualizer/tempera/createTemperaPixiRuntime.ts',
  'components/visualizer/tempera/temperaProgram.ts',
];
const seen = new Set(), missing = new Set(), ext = ['.ts', '.tsx', '.js', '.mjs', '/index.ts', '/index.tsx'];
function resolveFile(base, spec) {
  const p = path.resolve(base, spec);
  for (const e of ext) if (fs.existsSync(p + e)) return p + e;
  if (fs.existsSync(p) && fs.statSync(p).isFile()) return p;
  return null;
}
function walk(rel) {
  if (seen.has(rel)) return;
  seen.add(rel);
  const abs = path.join(FOLIA, rel);
  if (!fs.existsSync(abs)) { missing.add(rel + '  <-- 源不存在'); return; }
  const code = fs.readFileSync(abs, 'utf8');
  const re = /(?:^|\n)\s*(?:import|export)[\s\S]*?from\s*['"]([^'"]+)['"]/g;
  let m;
  while ((m = re.exec(code))) {
    const spec = m[1];
    if (!spec.startsWith('.')) continue;
    const r = resolveFile(path.dirname(abs), spec);
    if (!r) { missing.add(spec + '  (<- ' + rel + ') 无法解析'); continue; }
    const childRel = path.relative(FOLIA, r).replace(/\\/g, '/');
    walk(childRel);
  }
}
ENTRIES.forEach(walk);
const rows = [...seen].sort();
const miss = rows.filter(r => !fs.existsSync(path.join(PORT, r)));
console.log('闭包文件数:', rows.length);
console.log('lumiere-src 已有:', rows.length - miss.length);
console.log('--- 缺失（需补）---');
miss.forEach(r => console.log('  ' + r));
console.log('--- 解析失败 ---');
[...missing].forEach(r => console.log('  ' + r));
