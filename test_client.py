#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Kali WebTerm 冒烟测试：连接 WebSocket，执行真实命令并回显结果。（在 Kali/Linux 上运行）"""
import asyncio
import json

import websockets

HOST = "127.0.0.1"
PORT = 8765

COMMANDS = [
    "whoami && hostname && pwd\n",
    "ls -la\n",
    "echo hello-kali-webterm\n",
    "python3 -c 'print(6*7, \"answer\" )'\n",
    "echo 'PS1 test:' && echo $PS1 | head -c 60\n",
    "exit\n",
]


async def main():
    uri = f"ws://{HOST}:{PORT}"
    async with websockets.connect(uri, max_size=8 * 1024 * 1024) as ws:
        print("=== connected ===")
        # 发送命令
        for cmd in COMMANDS:
            await ws.send(json.dumps({"type": "input", "data": cmd}))
            await asyncio.sleep(1.2)
        # 收集剩余输出
        buf = ""
        try:
            while True:
                data = await asyncio.wait_for(ws.recv(), timeout=1.5)
                try:
                    j = json.loads(data)
                    if j.get("type") == "pong":
                        continue
                except Exception:
                    buf += data
        except asyncio.TimeoutError:
            pass
        print("=== captured output (ANSI 保留) ===")
        print(buf)
        print("=== end ===")
        # 简单断言
        for key in ["kali", "hello-kali-webterm", "42", "answer", "webterm"]:
            assert key in buf.lower(), f"missing: {key}"
        print("SMOKE TEST PASSED")


if __name__ == "__main__":
    asyncio.run(main())
