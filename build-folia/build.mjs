/**
 * WebTerm 绘光编译：folia 项目自带的 rolldown（vite 8 的 Rust 构建内核）bundle 全部依赖为单文件 ESM。
 * 依赖来源：Mineradio-main/vendor-src/folia/node_modules（folia 项目自己的依赖，npm ci 镜像还原）。
 */
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FOLIA_NM = 'C:/Users/Cuisi/Downloads/Mineradio-main/vendor-src/folia/node_modules';

const { build } = await import(pathToFileURL(path.join(FOLIA_NM, 'rolldown', 'dist', 'index.mjs')).href);

const outfile = path.join(__dirname, '..', 'web', 'folia-adapter.js');
fs.mkdirSync(path.dirname(outfile), { recursive: true });

const alias = {
    'pixi.js': path.join(FOLIA_NM, 'pixi.js', 'lib', 'index.mjs'),
    '@chenglou/pretext': path.join(FOLIA_NM, '@chenglou', 'pretext', 'dist', 'layout.js'),
    '@chenglou/pretext/rich-inline': path.join(FOLIA_NM, '@chenglou', 'pretext', 'dist', 'rich-inline.js'),
    'framer-motion': path.join(__dirname, 'stubs', 'framer-motion.js'),
    'lucide-react': path.join(__dirname, 'stubs', 'lucide-react.js'),
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
