/**
 * 编译 WebTerm 绘光 adapter：esbuild（folia 项目自带）bundle 全部依赖为单文件 ESM。
 * 依赖来源：Mineradio-main/vendor-src/folia/node_modules（folia 项目自己的依赖，npm ci 还原）。
 */
const path = require('node:path');

const FOLIA_NM = 'C:/Users/Cuisi/Downloads/Mineradio-main/vendor-src/folia/node_modules';
const WORK = __dirname;

const esbuild = require(path.join(FOLIA_NM, 'esbuild'));

async function main() {
    const result = await esbuild.build({
        entryPoints: [path.join(WORK, 'adapter.ts')],
        bundle: true,
        format: 'esm',
        target: ['es2020'],
        outfile: path.join(WORK, '..', 'web', 'folia', 'adapter.js'),
        alias: {
            'pixi.js': path.join(FOLIA_NM, 'pixi.js'),
            '@chenglou/pretext': path.join(FOLIA_NM, '@chenglou', 'pretext'),
            '@chenglou/pretext/rich-inline': path.join(FOLIA_NM, '@chenglou', 'pretext'),
            'framer-motion': path.join(WORK, 'stubs', 'framer-motion.js'),
            'lucide-react': path.join(WORK, 'stubs', 'lucide-react.js'),
        },
        logLevel: 'info',
        metafile: true,
        outfile: path.join(WORK, '..', 'web', 'folia', 'adapter.js'),
    });
    const meta = result.metafile;
    const inputs = Object.keys(meta.inputs);
    console.log('--- bundle inputs:', inputs.length, 'files');
    const byDir = {};
    for (const p of inputs) {
        const key = p.replace(/\\/g, '/').split('/').slice(-2).join('/');
        byDir[key] = (byDir[key] || 0) + 1;
    }
    const list = Object.entries(byDir).sort((a, b) => b[1] - a[1]).slice(0, 30);
    for (const [k, v] of list) console.log(`  ${v.toString().padStart(3)}  ${k}`);
    const outSize = result.metafile.outputs[Object.keys(result.metafile.outputs)[0]].bytes;
    console.log('--- output bytes:', outSize);
    console.log('BUILD OK');
}

main().catch((e) => {
    console.error('BUILD FAILED');
    console.error(e && e.stack ? e.stack : e);
    process.exit(1);
});
