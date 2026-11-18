#!/usr/bin/env bash
# Kali Terminal —— 版本与依赖自检脚本
# 用法：在 Kali 的 Terminal 目录下执行  bash check.sh
echo "=============================================="
echo "  Kali Terminal 自检"
echo "=============================================="

ok=1

echo ""
echo "[1] kali_terminal.py 是否为 VTE 新版（应含 Vte）"
if grep -q "Vte" kali_terminal.py 2>/dev/null; then
    echo "    ✅ 新版 OK（基于 Kali 原版开源终端 VTE 改造）"
else
    echo "    ❌ 旧版！请用 D:\\Terminal 最新 kali_terminal.py 覆盖"; ok=0
fi

echo ""
echo "[2] server.py 是否为纯标准库新版（应含 FrameParser，且不依赖 websockets）"
if grep -q "class FrameParser" server.py 2>/dev/null && ! grep -q "import websockets" server.py 2>/dev/null; then
    echo "    ✅ 新版 OK（零依赖，无需 websockets）"
else
    echo "    ❌ 旧版！请用 D:\\Terminal 最新 server.py 覆盖"; ok=0
fi

echo ""
echo "[3] 桌面终端依赖（Python GTK3 + VTE）"
python3 -c "import gi; gi.require_version('Gtk','3.0'); print('    ✅ GTK3 可用')" 2>/dev/null \
    || { echo "    ❌ 缺 python3-gi / GTK3：sudo apt install -y python3-gi gir1.2-gtk-3.0"; ok=0; }
python3 -c "import gi; gi.require_version('Vte','2.91'); print('    ✅ VTE 可用')" 2>/dev/null \
    || { echo "    ❌ 缺 VTE：sudo apt install -y gir1.2-vte-2.91"; ok=0; }

echo ""
echo "[4] 语法检查"
python3 -m py_compile kali_terminal.py && echo "    ✅ kali_terminal.py 语法 OK" || { echo "    ❌ 语法错误"; ok=0; }
python3 -m py_compile server.py && echo "    ✅ server.py 语法 OK" || { echo "    ❌ 语法错误"; ok=0; }

echo ""
echo "=============================================="
if [ "$ok" = "1" ]; then
    echo "  自检通过 ✅"
    echo "  桌面终端 : python3 ./kali_terminal.py（勿加 sudo）"
    echo "  网页版   : python3 ./server.py → 访问 http://127.0.0.1:8765/"
else
    echo "  存在缺失，请按上面提示更新文件/安装依赖后重试"
fi
echo "=============================================="
