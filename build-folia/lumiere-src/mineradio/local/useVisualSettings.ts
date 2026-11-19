// WebTerm 垫片：原版是 zustand 全局视觉设置仓（mineradio/local/useVisualSettings.ts，
// 200 行 + 一大串 hanabi/machi/persistence 依赖）。雾笺链路只读两个字段：
//   - rhythm：智能动感模式开关（节拍特效总闸）—— WebTerm 由桥接层驱动，默认开
//   - tunings：各可视化器的 tuning 包 —— WebTerm 没有持久化 tuning，空包即走各渲染器默认
export interface WebTermVisualSettings {
    rhythm: boolean;
    tunings: Record<string, unknown>;
}

const state: WebTermVisualSettings = {
    rhythm: true,
    tunings: {},
};

export const useVisualSettings = {
    getState: (): WebTermVisualSettings => state,
    setState: (patch: Partial<WebTermVisualSettings> | ((s: WebTermVisualSettings) => Partial<WebTermVisualSettings>)) => {
        Object.assign(state, typeof patch === 'function' ? patch(state) : patch);
    },
    subscribe: (_listener: (s: WebTermVisualSettings) => void) => () => {},
};
