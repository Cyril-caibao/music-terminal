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
import {
    compileSonnetProgram,
    findSonnetParagraphIndexAtTime,
} from './lumiere-src/components/visualizer/sonnet/sonnetProgram';
import { SonnetPixiRuntime } from './lumiere-src/components/visualizer/sonnet/createSonnetPixiRuntime';
import { DEFAULT_SONNET_TUNING } from './lumiere-src/types';
import { NebbiaRuntime } from './lumiere-src/components/visualizer/nebbia/createNebbiaRuntime';
import {
    DEFAULT_NEBBIA_TUNING,
    NEBBIA_BEHIND,
    NEBBIA_GLASS,
    NEBBIA_GLASS_FOR,
} from './lumiere-src/components/visualizer/nebbia/types';
import { normalizeNebbiaTuning } from './lumiere-src/components/visualizer/nebbia/tuning';
import {
    advanceLocalAnalysis,
    applyLocalAnalysisAudio,
    clearLocalAnalysis,
    getBeatEffectStrength,
    getBeatFxEvents,
    getBeatFxHeard,
    getBeatFxIntensities,
    getDrumHits,
    getVocalEnvelope,
    isBeatEffectPlaying,
    isBeatFxReady,
} from './lumiere-src/mineradio/local/localAnalysisBoundary';

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
    /* 商籁：印刷排版 PV。绝对时间驱动（镜头 + 字形 + 网点滤镜）。 */
    compileSonnetProgram,
    findSonnetParagraphIndexAtTime,
    SonnetPixiRuntime,
    DEFAULT_SONNET_TUNING,
    /* 雾笺：雾玻璃 PV。WebGL2，接节拍/人声能量（智能动感链路）。 */
    NebbiaRuntime,
    DEFAULT_NEBBIA_TUNING,
    normalizeNebbiaTuning,
    NEBBIA_BEHIND,
    NEBBIA_GLASS,
    NEBBIA_GLASS_FOR,
    /* 智能动感分析边界（节拍时钟 + 实时 FFT 打点），雾笺由桥接层喂数据。 */
    advanceLocalAnalysis,
    applyLocalAnalysisAudio,
    clearLocalAnalysis,
    getBeatEffectStrength,
    getBeatFxEvents,
    getBeatFxHeard,
    getBeatFxIntensities,
    getDrumHits,
    getVocalEnvelope,
    isBeatEffectPlaying,
    isBeatFxReady,
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
export type {
    SonnetProgram,
} from './lumiere-src/components/visualizer/sonnet/sonnetProgram';
export type {
    SonnetTuning,
} from './lumiere-src/types';

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
        SonnetFolia?: {
            MotionValue: typeof MotionValue;
            compileSonnetProgram: typeof compileSonnetProgram;
            findSonnetParagraphIndexAtTime: typeof findSonnetParagraphIndexAtTime;
            SonnetPixiRuntime: typeof SonnetPixiRuntime;
            DEFAULT_SONNET_TUNING: typeof DEFAULT_SONNET_TUNING;
            segmentTextWords: typeof segmentTextWords;
            BASE_DUAL_THEME: typeof BASE_DUAL_THEME;
            DEFAULT_THEME: typeof DEFAULT_THEME;
            DAYLIGHT_THEME: typeof DAYLIGHT_THEME;
        };
        NebbiaFolia?: {
            MotionValue: typeof MotionValue;
            NebbiaRuntime: typeof NebbiaRuntime;
            DEFAULT_NEBBIA_TUNING: typeof DEFAULT_NEBBIA_TUNING;
            normalizeNebbiaTuning: typeof normalizeNebbiaTuning;
            NEBBIA_BEHIND: typeof NEBBIA_BEHIND;
            NEBBIA_GLASS: typeof NEBBIA_GLASS;
            NEBBIA_GLASS_FOR: typeof NEBBIA_GLASS_FOR;
            advanceLocalAnalysis: typeof advanceLocalAnalysis;
            applyLocalAnalysisAudio: typeof applyLocalAnalysisAudio;
            clearLocalAnalysis: typeof clearLocalAnalysis;
            getBeatEffectStrength: typeof getBeatEffectStrength;
            getBeatFxEvents: typeof getBeatFxEvents;
            getBeatFxHeard: typeof getBeatFxHeard;
            getBeatFxIntensities: typeof getBeatFxIntensities;
            getDrumHits: typeof getDrumHits;
            getVocalEnvelope: typeof getVocalEnvelope;
            isBeatEffectPlaying: typeof isBeatEffectPlaying;
            isBeatFxReady: typeof isBeatFxReady;
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

(globalThis as { window?: Window }).window && ((globalThis as any).window.SonnetFolia = {
    MotionValue,
    compileSonnetProgram,
    findSonnetParagraphIndexAtTime,
    SonnetPixiRuntime,
    DEFAULT_SONNET_TUNING,
    segmentTextWords,
    BASE_DUAL_THEME,
    DEFAULT_THEME,
    DAYLIGHT_THEME,
});

(globalThis as { window?: Window }).window && ((globalThis as any).window.NebbiaFolia = {
    MotionValue,
    NebbiaRuntime,
    DEFAULT_NEBBIA_TUNING,
    normalizeNebbiaTuning,
    NEBBIA_BEHIND,
    NEBBIA_GLASS,
    NEBBIA_GLASS_FOR,
    advanceLocalAnalysis,
    applyLocalAnalysisAudio,
    clearLocalAnalysis,
    getBeatEffectStrength,
    getBeatFxEvents,
    getBeatFxHeard,
    getBeatFxIntensities,
    getDrumHits,
    getVocalEnvelope,
    isBeatEffectPlaying,
    isBeatFxReady,
    segmentTextWords,
    BASE_DUAL_THEME,
    DEFAULT_THEME,
    DAYLIGHT_THEME,
});
