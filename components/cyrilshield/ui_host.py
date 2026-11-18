# -*- coding: utf-8 -*-
"""CyrilShield 面板宿主：用插件自带的解释器跑真实界面。

为什么要有这个文件：

WebTerm 是 PyInstaller 单文件包（--onefile）。它在启动时把自己解到
``%TEMP%\\_MEI*``，再从这里 import。宿主那份归档里有一小撮标准库/第三方
条目是坏的（``__future__`` / ``dataclasses`` / ``logging`` / ``platform`` /
``traceback`` / ``xml.etree`` / ``PIL``），一 import 就抛
``Error -3 while decompressing data: incorrect header check``。

插件的处理逻辑（apk / pe / upx / vmgen / vmp）离不开 ``dataclasses`` 和
``PIL``，在宿主进程里必然起不来。所以插件自带一个完整的 CPython
（``vendor/python/``），由 ``bridge.py`` 拉起本文件，界面和任务全在这个
独立解释器里跑；宿主进程只负责开、关这个子进程，不 import 任何插件模块。

用法（只由 bridge.py 调用）：``python ui_host.py <port>``
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
VENDOR = os.path.join(HERE, "vendor")


def main() -> int:
    port = 0
    if len(sys.argv) > 1:
        try:
            port = int(sys.argv[1])
        except ValueError:
            port = 0

    # vendor 必须排在最前面：cyrilshield 包和纯 Python 依赖（PIL）都在这里
    sys.path.insert(0, VENDOR)
    os.chdir(HERE)

    from cyrilshield import server       # 延迟导入：让 sys.path 先生效

    print("CyrilShield UI host: vendor=%s port=%d" % (VENDOR, port), flush=True)
    server.serve(port=port, open_browser=False, quiet=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(0)
