/**
 * WebTerm ← Folia 可视化接口层（绘光 Lumiere + 凝彩 Tempera）
 *
 * 全量照搬 Folia 源码（lumiere-src/，目录名沿用第一次移植时的叫法，实际是
 * vendor-src/folia/src 的镜像），此处只做 re-export + 全局挂载，
 * 让 WebTerm 的普通 <script> 能通过 window.LumiereFolia / window.TemperaFolia
 * 访问编译产物。两个可视化器各挂一个全局名，互不干扰。
 *
 * 注意：必须用 `import ... + export {...}` 而非 `export {...} from ...`——
 * re-export 不建立本地绑定，挂载对象里的简写属性
 * 会引用未定义标识符（浏览器实测报 compileLumiereProgram is not defined）。
 */
import { MotionValue } from 'framer-motion';
import {
    compileLumiereProgram,
    findLumiereParagraphIndexAtTime,
} from './lumiere-src/components/visualizer/lumiere/lumiereProgram';
import { LumierePixiRuntime } from './lumiere-src/components/visualizer/lumiere/createLumierePixiRuntime';
import { DEFAULT_LUMIERE_PARAMS } from './lumiere-src/components/visualizer/lumiere/program';
import { segmentTextWords } from './lumiere-src/utils/lyrics/wordSegmentation';
import { BASE_DUAL_THEME, DEFAULT_THEME, DAYLIGHT_THEME } from './lumiere-src/services/baseThemes';
import {
    compileTemperaProgram,
    findTemperaParagraphIndexAtTime,
} from './lumiere-src/components/visualizer/tempera/temperaProgram';
import { TemperaPixiRuntime } from './lumiere-src/components/visualizer/tempera/createTemperaPixiRuntime';
import { DEFAULT_TEMPERA_TUNING } from './lumiere-src/types';

export {
    MotionValue,
    compileLumiereProgram,
    findLumiereParagraphIndexAtTime,
    LumierePixiRuntime,
    DEFAULT_LUMIERE_PARAMS,
    segmentTextWords,
    /* 原版 Folia 的两套内置主题（services/baseThemes.ts 原样移植）：
       午夜墨染（深） / 日光素白（浅）。WebTerm 里由【设置】→【外观】的黑 / 白决定用哪套。 */
    BASE_DUAL_THEME,
    DEFAULT_THEME,
    DAYLIGHT_THEME,
    /* 凝彩：色块 PV。纯时间驱动，不接音频能量（与绘光不同）。 */
    compileTemperaProgram,
    findTemperaParagraphIndexAtTime,
    TemperaPixiRuntime,
    DEFAULT_TEMPERA_TUNING,
};

export type { LumiereProgram } from './lumiere-src/components/visualizer/lumiere/lumiereProgram';
export type { TemperaProgram } from './lumiere-src/components/visualizer/tempera/types';
export type {
    Line,
    Theme,
    LumiereTuning,
    AudioBands,
    TemperaTuning,
} from './lumiere-src/types';
export type {
    LumiereSongContext,
    LumiereSongMetadata,
    LumiereRuntimeOptions,
} from './lumiere-src/components/visualizer/lumiere/createLumierePixiRuntime';
export type {
    TemperaSongContext,
    TemperaSongMetadata,
    TemperaRuntimeOptions,
} from './lumiere-src/components/visualizer/tempera/createTemperaPixiRuntime';

declare global {
    interface Window {
        LumiereFolia?: {
            MotionValue: typeof MotionValue;
            compileLumiereProgram: typeof compileLumiereProgram;
            findLumiereParagraphIndexAtTime: typeof findLumiereParagraphIndexAtTime;
            LumierePixiRuntime: typeof LumierePixiRuntime;
            DEFAULT_LUMIERE_PARAMS: typeof DEFAULT_LUMIERE_PARAMS;
            segmentTextWords: typeof segmentTextWords;
            BASE_DUAL_THEME: typeof BASE_DUAL_THEME;
            DEFAULT_THEME: typeof DEFAULT_THEME;
            DAYLIGHT_THEME: typeof DAYLIGHT_THEME;
        };
        TemperaFolia?: {
            MotionValue: typeof MotionValue;
            compileTemperaProgram: typeof compileTemperaProgram;
            findTemperaParagraphIndexAtTime: typeof findTemperaParagraphIndexAtTime;
            TemperaPixiRuntime: typeof TemperaPixiRuntime;
            DEFAULT_TEMPERA_TUNING: typeof DEFAULT_TEMPERA_TUNING;
            segmentTextWords: typeof segmentTextWords;
            BASE_DUAL_THEME: typeof BASE_DUAL_THEME;
            DEFAULT_THEME: typeof DEFAULT_THEME;
            DAYLIGHT_THEME: typeof DAYLIGHT_THEME;
        };
    }
}

(globalThis as { window?: Window }).window && ((globalThis as any).window.LumiereFolia = {
    MotionValue,
    compileLumiereProgram,
    findLumiereParagraphIndexAtTime,
    LumierePixiRuntime,
    DEFAULT_LUMIERE_PARAMS,
    segmentTextWords,
    BASE_DUAL_THEME,
    DEFAULT_THEME,
    DAYLIGHT_THEME,
});

(globalThis as { window?: Window }).window && ((globalThis as any).window.TemperaFolia = {
    MotionValue,
    compileTemperaProgram,
    findTemperaParagraphIndexAtTime,
    TemperaPixiRuntime,
    DEFAULT_TEMPERA_TUNING,
    segmentTextWords,
    BASE_DUAL_THEME,
    DEFAULT_THEME,
    DAYLIGHT_THEME,
});
