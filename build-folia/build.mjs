/**
 * WebTerm 绘光编译：folia 项目自带的 rolldown（vite 8 的 Rust 构建内核）bundle 全部依赖为单文件 ESM。
 * 依赖来源：Mineradio-main/vendor-src/folia/node_modules（folia 项目自己的依赖，npm ci 镜像还原）。
 */
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// 优先用 build-folia/node_modules（2026-11-19 起：vendor-src/folia 的 node_modules 已被清理），
// 本地仅装了构建所需四件套：rolldown / pixi.js@8.21 / @chenglou/pretext / stub。
// 找不到时回落到 Mineradio 自带的完整 node_modules。
let NM = path.join(__dirname, 'node_modules');
if (!fs.existsSync(path.join(NM, 'rolldown', 'dist', 'index.mjs'))) {
    NM = 'C:/Users/Cuisi/Downloads/Mineradio-main/vendor-src/folia/node_modules';
}

const { build } = await import(pathToFileURL(path.join(NM, 'rolldown', 'dist', 'index.mjs')).href);

const outfile = path.join(__dirname, '..', 'web', 'folia-adapter.js');
fs.mkdirSync(path.dirname(outfile), { recursive: true });

const alias = {
    'pixi.js': path.join(NM, 'pixi.js', 'lib', 'index.mjs'),
    '@chenglou/pretext': path.join(NM, '@chenglou', 'pretext', 'dist', 'layout.js'),
    '@chenglou/pretext/rich-inline': path.join(NM, '@chenglou', 'pretext', 'dist', 'rich-inline.js'),
    'framer-motion': path.join(__dirname, 'stubs', 'framer-motion.js'),
    // 2026-11-19 起 sonnet（商籁）需要真 lucide 图标（sonnetIcons / services/lucideIconResolver），
    // nebbia（雾笺）的涂鸦与 emoji 图标同样走它。空 stub 已退役。
};

try {
    await build({
        input: path.join(__dirname, 'adapter.ts'),
        resolve: { alias },
        platform: 'browser',
        output: {
            format: 'esm',
            file: outfile,
            codeSplitting: false,
        },
        logLevel: 'info',
    });
    const bytes = fs.statSync(outfile).size;
    console.log('BUILD OK ->', outfile, `(${Math.round(bytes / 1024)} KB)`);
} catch (e) {
    console.error('BUILD FAILED');
    console.error(e && e.stack ? e.stack : e);
    process.exit(1);
}
