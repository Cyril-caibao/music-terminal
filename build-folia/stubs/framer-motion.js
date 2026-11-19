/**
 * framer-motion stub — 仅提供 Lumiere 运行时需要的 MotionValue（get/set）。
 * 源码只以类型方式使用 MotionValue 的其余 API。
 */
export class MotionValue {
    constructor(value) {
        this._value = value;
        this._listeners = [];
    }
    get() { return this._value; }
    set(value) {
        if (value !== this._value) {
            const prev = this._value;
            this._value = value;
            for (const cb of this._listeners) cb(value, prev);
        }
        return this;
    }
    onChange(cb) { this._listeners.push(cb); return () => { this._listeners = this._listeners.filter(f => f !== cb); }; }
    getPrevious() { return this._value; }
    stop() {}
    jump(value) { this._value = value; }
    isAnimating() { return false; }
}

export function useMotionValue(value) {
    return new MotionValue(value);
}

export function motionValue(value) {
    return new MotionValue(value);
}
