/* 歌词解析单元测试：从 web/index.html 里抽出 parseLrc 源码直接跑，
 * 验证「原文块 + 翻译块（同时间戳）」被并成 text + trans，而不是两条独立行。
 * 用法：node _lrc_parse_test.cjs [lrc 路径...]
 */
const fs = require('fs');
const path = require('path');

const HTML = path.join(__dirname, 'web', 'index.html');
const src = fs.readFileSync(HTML, 'utf8');

function extract(name) {
  const i = src.indexOf('function ' + name + '(');
  if (i < 0) throw new Error('找不到函数 ' + name);
  // 从 function 关键字起花括号配对
  let j = src.indexOf('{', i), depth = 0, k = j;
  for (; k < src.length; k++) {
    if (src[k] === '{') depth++;
    else if (src[k] === '}') { depth--; if (!depth) { k++; break; } }
  }
  return src.slice(i, k);
}

const parseLrc = new Function(extract('parseLrc') + '\nreturn parseLrc;')();
const latGroupLrc = new Function(extract('latGroupLrc') + '\nreturn latGroupLrc;')();

let pass = 0, fail = 0;
function ck(cond, msg, extra) {
  if (cond) { pass++; console.log('PASS ' + msg); }
  else { fail++; console.log('FAIL ' + msg + (extra ? '  ' + JSON.stringify(extra) : '')); }
}

/* ---------- 1. 合成用例：原文块 + 空行 + 翻译块 ---------- */
const synthetic = [
  '[ti:Test]',
  '[00:01.000]I caught a sickness',
  '[00:05.000]I flew to Paris',
  '',
  '[00:01.000]我染上了一种病',
  '[00:05.000]我飞去巴黎',
].join('\n');
const s = parseLrc(synthetic);
ck(s.length === 2, '同时间戳原文 + 译文合成 2 条', s);
ck(s[0].text === 'I caught a sickness', '第 1 条正文是原文', s[0]);
ck(s[0].trans === '我染上了一种病', '第 1 条译文进 trans', s[0]);
ck(s[1].text === 'I flew to Paris' && s[1].trans === '我飞去巴黎', '第 2 条同样', s[1]);

/* ---------- 2. 一行多时间戳 ---------- */
const multi = parseLrc('[00:10.00][01:20.00]副歌\n[00:20.00]主歌');
ck(multi.length === 3, '一行多时间戳拆成 3 条', multi.length);
ck(multi[0].t === 10 && multi[2].t === 80, '时间戳解析正确', multi.map(l => l.t));

/* ---------- 3. 纯标签 / 空行不产生条目 ---------- */
const tags = parseLrc('[ar:X]\n[offset:0]\n[00:03.00]  \n[00:04.00]hi');
ck(tags.length === 1 && tags[0].text === 'hi', '标签行与空白行被丢弃', tags);

/* ---------- 4. 第三条同时间戳不丢内容 ---------- */
const three = parseLrc('[00:01.00]A\n[00:01.00]B\n[00:01.00]C');
ck(three.length === 1 && three[0].text === 'A / C' && three[0].trans === 'B',
  '第三条并回正文（宁多勿丢）', three[0]);

/* ---------- 5. 真实英文歌 ---------- */
const files = process.argv.slice(2);
const defaults = [
  'C:/Users/Cuisi/Music/Amazing - Ed Sheeran.lrc',
  'C:/Users/Cuisi/Music/Free Loop - Daniel Powter.lrc'.replace('Free Loop', 'Else/Free Loop'),
];
const targets = (files.length ? files : defaults).filter(f => fs.existsSync(f));
targets.forEach(f => {
  const raw = fs.readFileSync(f, 'utf8');
  const lines = parseLrc(raw);
  const withTrans = lines.filter(l => l.trans).length;
  const latinFirst = lines.filter(l => l.trans && /^[^\u4e00-\u9fa5]*$/.test(l.text) && /[\u4e00-\u9fa5]/.test(l.trans)).length;
  console.log('\n文件 ' + path.basename(f) + '：' + lines.length + ' 条（带译文 ' + withTrans + ' 条，原文拉丁+译文中文 ' + latinFirst + ' 条）');
  lines.slice(0, 4).forEach(l => console.log('  [' + l.t.toFixed(2) + 's] ' + l.text + (l.trans ? '  ／ ' + l.trans : '')));
  const groups = latGroupLrc(lines);
  ck(groups.length > 0 && groups.length <= lines.length, '拼贴分组数量合理（' + groups.length + '）');
  ck(groups[0].items[0] === lines[0].text, '拼贴首组 main 是原文', groups[0]);
  if (withTrans) {
    ck(groups.some(g => g.items[1]), '拼贴组里存在译文副行');
    /* 复刻 updateLrcHighlight 的取行规则：idx = 最后一个 t <= now 的行。
       修复前这条规则会落到同时间戳的译文上，现在必须落到原文。 */
    const at = (lines, now) => {
      let idx = -1;
      for (let i = 0; i < lines.length; i++) { if (lines[i].t <= now) idx = i; else break; }
      return idx >= 0 ? lines[idx] : null;
    };
    [20.5, 30.5, 60.5, 120.5].forEach(now => {
      const l = at(lines, now);
      const isOrig = l && (!l.trans || !/[\u4e00-\u9fa5]/.test(l.text));
      ck(!!isOrig, '播放到 ' + now + 's 时选中的是原文行', l && { text: l.text, trans: l.trans });
    });
  }
});

console.log('\n---- 结果 ----\nPASS ' + pass + ' / FAIL ' + fail);
process.exit(fail ? 1 : 0);
