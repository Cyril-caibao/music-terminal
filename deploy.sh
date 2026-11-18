#!/usr/bin/env bash
# Kali WebTerm —— 网页版一键启动脚本（纯标准库版，零依赖，无需安装任何 Python 包）
set -e

echo "[*] Kali WebTerm (网页版) 启动"
echo "    要求：Python 3.7+（Kali 自带）即可，无需 websockets 库"

python3 -m py_compile "$(dirname "$0")/server.py"
echo "[*] 语法检查通过"

echo "[*] 启动服务："
echo "    浏览器访问 http://127.0.0.1:8765/"
echo "    局域网远程：./deploy.sh --remote            （手机用打印的 LAN URL 访问）"
echo "    自定义端口：./deploy.sh --port 9000"
echo "    组合：      ./deploy.sh --remote --port 9000"
echo "    访问令牌：  export WEBTERM_TOKEN=你的密钥"
echo "================================================"
exec python3 "$(dirname "$0")/server.py" "$@"
