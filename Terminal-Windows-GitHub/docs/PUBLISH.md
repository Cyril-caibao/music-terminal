# 提交到 GitHub

本目录已经整理成可直接提交的状态，**没有**执行 `git init` / `commit` / `push`，
这些步骤由你自己完成。

## 1. 公开前再检查一遍

```bash
cd "$HOME/Desktop/Terminal-Windows-GitHub"

# 确认没有本机用户名 / 工作目录 / 环境哈希混入
# Git Bash 会把参数里的反斜杠转正斜杠，所以这里用正斜杠 + 运行时展开的用户名
find . -type f | sort
grep -rInF --include="*.py" --include="*.js" --include="*.html" \
  --include="*.json" --include="*.md" --include="*.spec" \
  --exclude=PUBLISH.md \
  -e "$(whoami)" -e 'D:/Terminal-Windows' -e 'AppData/Local' . || true

# 单文件不要超过 100MB（GitHub 限制），总仓库大小建议 < 100MB
du -sh . ; find . -type f -size +10M -printf "%s %p\n"
```

确认 `Settings.json`、`users.json`、`dist/WebTerm.exe`、`*.log` 都不在列表里。
如需加入截图，放进 `docs/` 并取消 `README.md` 里图片那一行的注释。

## 2. 选择许可证

仓库目前**没有 LICENSE**。公开发布前请先选定许可证（例如 MIT / Apache-2.0 / GPL-3.0）
并新建 `LICENSE` 文件；`web/` 内的第三方前端库另有各自许可，需一并核对。

## 3. 初始化并首次提交

```bash
git init -b main
git config user.name  "你的名字"       # 全局已配置可跳过
git config user.email "你的邮箱"       # GitHub 建议用 noreply 邮箱
git add .
git status --short                   # 逐行看一眼再提交
git commit -m "Initial commit: WebTerm for Windows"
```

## 4. 关联远程仓库并推送

在 GitHub 网页上新建空仓库（**不要**勾选自动生成 README / LICENSE，避免冲突），然后：

```bash
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

也可以用 GitHub Desktop：File → Add Local Repository → 选中本目录 → Publish repository。

## 5. 推送后再确认

- 仓库网页上逐个点开 `Settings.example.json`（模板里没有真实 key）、
  `docs/README-Windows.md`，确认没有个人路径或账号信息。
- 如果之后从工作副本继续同步代码，注意不要 `git add -A` 把 `dist/`、
  `users.json`、临时探针带进来；`.gitignore` 已覆盖这些路径。
