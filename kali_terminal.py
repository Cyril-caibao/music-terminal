#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Kali Terminal (VTE) —— 基于 Kali 原版开源终端改造

Kali 默认终端 xfce4-terminal 的底层就是开源终端组件 VTE（GTK3）。
本程序直接用 GTK3 + VTE 构建 Kali 风格桌面终端：
  - 真正的终端模拟器：完美支持 vi / top / sudo 等交互程序
  - 输入/输出零问题：VTE 原生处理键盘与 PTY，不存在"无法输入"
  - 双主题：黑色（Kali 默认深色）/ 白色（浅色）两套 16 色配色
  - 背景图：可自定义终端背景图片（GTK CSS background-image，同 xfce4-terminal）
  - 右键菜单：复制 / 粘贴 / 清屏 / 主题 / 背景图

依赖（Kali 上一次性安装）：
  sudo apt install -y python3-gi gir1.2-gtk-3.0 gir1.2-vte-2.91

运行（不要用 sudo 运行程序本身）：
  python3 kali_terminal.py
"""

import json
import os
import sys

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Vte", "2.91")
from gi.repository import Gtk, Gdk, GdkPixbuf, GLib, Pango, Vte

try:
    import cairo   # 离屏渲染用（Kali 一般自带 python3-cairo；缺则壁纸功能禁用）
except ImportError:
    cairo = None

SHELL = os.environ.get("SHELL", "/bin/bash")

# 设置持久化：主题 / 壁纸 / 白罩滑块，下次启动自动恢复
SETTINGS_PATH = os.path.join(
    os.path.expanduser("~"), ".config", "cyril-terminal", "settings.json")

# ---------------- 双主题配色（黑色 / 白色） ----------------
THEMES = {
    "black": {
        "label": "黑色",
        "panel": "#1e1e1e",          # 顶部装饰条
        "btn": "#2c2c2c",            # 工具按钮背景（比导航栏亮一层）
        "btn_fg": "#d3d7cf",         # 工具按钮文字
        "border": "#3a3a3a",
        "bg": "#141414",             # 终端背景
        "fg": "#d3d7cf",             # 前景
        "title": "#73d216",          # 标题绿色
        "palette": [                 # 16 色（Kali 默认）
            "#000000", "#ef2929", "#73d216", "#fce94f",
            "#3465a4", "#75507b", "#06989a", "#eeeeec",
            "#555753", "#ef2929", "#8ae234", "#fce94f",
            "#729fcf", "#ad7fa8", "#34e2e2", "#ffffff",
        ],
    },
    "white": {
        "label": "白色",
        "panel": "#ffffff",
        "btn": "#cccccc",            # 工具按钮：浅灰底
        "btn_fg": "#000000",         # 工具按钮：纯黑字
        "border": "#b0b0af",
        "bg": "#f5f5f4",
        "fg": "#2e3436",
        "title": "#3465a4",
        "palette": [                 # 16 色（Tango 浅色，Kali 白色界面风格）
            "#000000", "#cc0000", "#4e9a06", "#c4a000",
            "#3465a4", "#75507b", "#06989a", "#d3d7cf",
            "#555753", "#ef2929", "#8ae234", "#fce94f",
            "#729fcf", "#ad7fa8", "#34e2e2", "#ffffff",
        ],
    },
}

# Kali 风格彩色提示符（VTE 真终端会正确渲染 ANSI 颜色）
PS1 = (r"\[\e[1;31m\]\u\[\e[0m\]@\[\e[1;32m\]\h\[\e[0m\]"
       r":\[\e[1;34m\]\w\[\e[0m\]$ ")


class KaliTerminal(Gtk.Window):
    def __init__(self):
        super().__init__(title="Kali Terminal")
        self.set_default_size(920, 560)
        self.set_border_width(0)

        self.current_theme = "black"
        self._bg_path = None
        self._bg_pixbuf = None
        self._bg_path = None
        self._draw_id = None
        self._white_alpha = 0.0   # 白罩透明度（0 = 不遮挡，保持壁纸原貌）

        # CSS Provider：界面配色（主题）。
        # 背景透明不再走 CSS（这台 VTE 不认 background-color/background-image），
        # 改走 set_colors 背景色的 alpha（GdkRGBA 参与渲染）。
        self._ui_provider = Gtk.CssProvider()
        Gtk.StyleContext.add_provider_for_screen(
            Gdk.Screen.get_default(), self._ui_provider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION,
        )

        vbox = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.add(vbox)

        # 顶部类窗口装饰条（红黄绿圆点 + 标题）
        header = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        header.set_name("header")
        for cls in ("dotr", "doty", "dotg"):
            dot = Gtk.Label(label="●")
            dot.get_style_context().add_class(cls)
            header.pack_start(dot, False, False, 6)
        self.title_label = Gtk.Label(label="  Kali Terminal  (VTE)")
        self.title_label.get_style_context().add_class("ctitle")
        header.pack_start(self.title_label, False, False, 0)

        # 工具按钮（与网页版一致）：外观（主题/白罩/背景）/ 壁纸
        btnbox = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=4)

        # 「外观」面板：Gtk.Popover
        # （不用 Gtk.Menu：菜单 grab 会拦截鼠标事件，内嵌 Gtk.Scale 滑块拖不动）
        self.appearance_btn = Gtk.MenuButton(label="外观")
        self.appearance_pop = Gtk.Popover()
        self.appearance_pop.set_name("appearance-pop")
        self.appearance_pop.set_position(Gtk.PositionType.BOTTOM)
        apop = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        apop.set_margin_start(12)
        apop.set_margin_end(12)
        apop.set_margin_top(10)
        apop.set_margin_bottom(10)
        self._appearance_radio = {}
        first = None
        for key, label in (("black", "黑色"), ("white", "白色")):
            # 用关键字构造（Gtk.RadioButton(label=...)）+ join_group：
            # 部分 pygobject 版本要求 new_with_label 传 (group, label) 两个参数，
            # 单参调用会报 TypeError（实测 Kali 上抛 "takes exactly 2 arguments"）
            rb = Gtk.RadioButton(label=label)
            if first is not None:
                rb.join_group(first)
            else:
                first = rb
            rb.connect("toggled", self._on_theme_btn_toggled, key)
            apop.pack_start(rb, False, False, 0)
            self._appearance_radio[key] = rb
        # 白罩：滑块（只作用于背景层；无背景时禁用，与网页版一致）
        wbox = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        wlbl = Gtk.Label(label="白罩")
        wbox.pack_start(wlbl, False, False, 0)
        self._white_adj = Gtk.Adjustment(
            value=0, lower=0, upper=100, step_increment=1, page_increment=10
        )
        self._white_scale = Gtk.Scale(
            orientation=Gtk.Orientation.HORIZONTAL, adjustment=self._white_adj
        )
        self._white_scale.set_size_request(150, -1)
        self._white_scale.set_draw_value(False)
        self._white_scale.connect("value-changed", self._on_white_alpha_changed)
        wbox.pack_start(self._white_scale, True, True, 0)
        apop.pack_start(wbox, False, False, 0)
        for label, fn in (
            ("背景…", self._choose_background),
            ("清除", self._clear_background),
        ):
            b = Gtk.Button(label=label)
            b.connect("clicked", lambda *_, f=fn: f())
            apop.pack_start(b, False, False, 0)
        apop.show_all()
        self.appearance_pop.add(apop)
        self.appearance_btn.set_popover(self.appearance_pop)
        btnbox.pack_start(self.appearance_btn, False, False, 0)
        # 初始无背景：白罩滑块禁用
        self._white_scale.set_sensitive(False)

        for label, fn in (
            ("壁纸", self._use_default_wallpaper),
        ):
            b = Gtk.Button(label=label)
            b.connect("clicked", lambda *_, f=fn: f())
            btnbox.pack_start(b, False, False, 0)

        header.pack_end(btnbox, False, False, 6)

        vbox.pack_start(header, False, False, 0)

        # ---------------- VTE 真终端 ----------------
        self.term = Vte.Terminal()
        self.term.set_name("termwrap")
        self.term.set_font(Pango.FontDescription("DejaVu Sans Mono 13"))
        self.term.set_scrollback_lines(5000)
        self.term.set_mouse_autohide(False)
        vbox.pack_start(self.term, True, True, 0)

        # 右键菜单：复制 / 粘贴 / 清屏 / 主题 / 背景图
        self.term.connect("button-press-event", self._on_button_press)
        self._build_menu()

        # 应用默认主题（需在菜单构建之后：主题会同步菜单勾选）
        self._apply_theme("black")
        # 恢复上次保存的设置（主题 / 壁纸 / 白罩）
        self._load_settings()

        # 启动用户的 shell
        ok, _pid = self._spawn()
        if not ok:
            self._show_error("无法启动 shell：%s" % SHELL)

        # 窗口几何自动记忆：移动/缩放后防抖保存，关闭窗口时兜底保存
        self.connect("configure-event", self._on_configure)
        self.connect("destroy", self._on_destroy_save)

        self.term.grab_focus()

    # ---------------- 主题 ----------------
    def _apply_theme(self, name):
        t = THEMES.get(name, THEMES["black"])
        self.current_theme = name

        palette = [Gdk.RGBA() for _ in range(16)]
        for i, c in enumerate(t["palette"]):
            palette[i].parse(c)
        fg = Gdk.RGBA()
        fg.parse(t["fg"])
        bg = Gdk.RGBA()
        bg.parse(t["bg"])
        if getattr(self, "_bg_path", None):
            # 有壁纸：背景完全透明（alpha=0），壁纸 100% 原貌显示。
            # 不要用主题色做遮罩：白色主题下 0.35 的白罩会盖住壁纸，
            # 且 VTE 半透明背景会导致文字渲染变糊看不清。
            # 遮罩完全交给「白罩」滑块（_on_vte_draw 里画的白层）。
            bg.alpha = 0.0
        self.term.set_colors(fg, bg, palette)
        # 关键：独立 API 设置渲染用背景色（保留 alpha）。
        # set_colors 的背景参数在 VTE 内部不保留 alpha（实测会画纯色盖住壁纸），
        # vte_terminal_set_color_background 是 xfce4-terminal 实际使用的通道。
        self.term.set_color_background(bg)

        self._ui_provider.load_from_data(
            ("#header { background-color: %s; }"
             ".dotr { color: #ff5f57; }"
             ".doty { color: #febc2e; }"
             ".dotg { color: #28c840; }"
             "#header .ctitle { color: %s; font-weight: bold; }"
             "#header button { background-color: %s; color: %s;"
             " border: 2px solid %s; border-radius: 4px; padding: 2px 10px;"
             " font-size: 13px; font-weight: bold; }"
             "#header button label { color: %s; }"
             "#header button:hover, #header button:hover label { color: %s; }"
             "#header button:checked { background-color: %s; color: #ffffff; border-color: %s; }"
             "#header button:checked label { color: #ffffff; }"
             "#header label { color: %s; }"
             "#header scale { color: %s; }"
             "#header scale trough { background-color: %s; min-height: 4px; border-radius: 2px; }"
             "#header scale highlight { background-color: %s; min-height: 4px; border-radius: 2px; }"
             "#header scale slider { background-color: %s; border: 1px solid %s;"
             " border-radius: 6px; min-width: 12px; min-height: 12px; }"
             "#appearance-pop { background-color: %s; border: 1px solid %s; border-radius: 10px; }"
             "#appearance-pop arrow { background-color: %s; border-color: %s; }"
             "#appearance-pop label { color: %s; }"
             "#appearance-pop button { background-color: %s; color: %s;"
             " border: 2px solid %s; border-radius: 4px; padding: 3px 10px;"
             " font-size: 13px; font-weight: bold; }"
             "#appearance-pop button label { color: %s; }"
             "#appearance-pop button:hover, #appearance-pop button:hover label { color: %s; border-color: %s; }"
             "#appearance-pop radio { color: %s; }"
             "#appearance-pop scale { color: %s; }"
             "#appearance-pop scale trough { background-color: %s; min-height: 4px; border-radius: 2px; }"
             "#appearance-pop scale highlight { background-color: %s; min-height: 4px; border-radius: 2px; }"
             "#appearance-pop scale slider { background-color: %s; border: 1px solid %s;"
             " border-radius: 6px; min-width: 12px; min-height: 12px; }"
             % (t["panel"], t["title"], t["btn"], t["btn_fg"], t["border"],
                t["btn_fg"], t["title"], t["title"], t["title"],
                t["fg"], t["fg"], t["border"], t["title"], t["title"], t["border"],
                t["panel"], t["border"], t["panel"], t["border"],
                t["fg"], t["btn"], t["btn_fg"], t["border"], t["btn_fg"],
                t["title"], t["title"], t["fg"], t["fg"],
                t["border"], t["title"], t["title"], t["border"])
             ).encode())

        # 同步右键菜单 / 外观菜单勾选（set_active 触发回调，靠防重入判断）
        for key, item in getattr(self, "_theme_radio", {}).items():
            want = key == name
            if item.get_active() != want:
                item.set_active(want)
        for key, item in getattr(self, "_appearance_radio", {}).items():
            want = key == name
            if item.get_active() != want:
                item.set_active(want)

    def _on_theme_btn_toggled(self, btn, key):
        if not btn.get_active():
            return
        if key == self.current_theme:
            return
        self._apply_theme(key)
        self._save_settings()

    # ---------------- 设置持久化 ----------------
    def _settings_file(self):
        try:
            os.makedirs(os.path.dirname(SETTINGS_PATH), exist_ok=True)
        except OSError:
            pass
        return SETTINGS_PATH

    def _save_settings(self):
        """把当前 主题/壁纸/白罩/窗口几何 写入配置文件"""
        data = {
            "theme": self.current_theme,
            "bg_path": self._bg_path,
            "white_alpha": round(self._white_alpha * 100),
        }
        if self.get_realized():
            # 窗口几何只在已实现后记录；最大化时不覆盖普通尺寸与位置
            if self.is_maximized():
                data["maximized"] = True
            else:
                data["maximized"] = False
                x, y = self.get_position()
                w, h = self.get_size()
                if w > 0 and h > 0:
                    data["win_x"], data["win_y"] = x, y
                    data["win_w"], data["win_h"] = w, h
        try:
            with open(self._settings_file(), "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False)
        except OSError:
            pass

    def _load_settings(self):
        """启动时读取配置文件并应用；无文件/损坏则保持默认"""
        try:
            with open(SETTINGS_PATH, encoding="utf-8") as f:
                d = json.load(f)
        except Exception:
            return
        theme = d.get("theme")
        if theme in THEMES:
            self._apply_theme(theme)
        bg = d.get("bg_path")
        if bg and os.path.exists(bg):
            self._set_background_image(bg)
        wa = d.get("white_alpha", 0)
        try:
            wa = int(wa)
        except (TypeError, ValueError):
            wa = 0
        wa = max(0, min(100, wa))
        self._white_alpha = wa / 100.0
        if getattr(self, "_white_adj", None):
            # 触发 value-changed（同步滑块位置并重绘）
            self._white_adj.set_value(wa)
        self._restore_geometry(d)

    # ---------------- 窗口几何自动记忆 ----------------
    def _on_configure(self, widget, event):
        """移动 / 缩放：0.5s 防抖后保存窗口几何"""
        if not self.get_realized():
            return False
        if getattr(self, "_geom_save_id", None):
            GLib.source_remove(self._geom_save_id)
        self._geom_save_id = GLib.timeout_add(500, self._flush_geometry_save)
        return False

    def _flush_geometry_save(self):
        self._geom_save_id = None
        self._save_settings()
        return False

    def _on_destroy_save(self, widget):
        """关闭窗口时兜底保存（防抖可能还没到 0.5s）"""
        if getattr(self, "_geom_save_id", None):
            GLib.source_remove(self._geom_save_id)
            self._geom_save_id = None
        self._save_settings()

    def _restore_geometry(self, d):
        try:
            w = int(d.get("win_w", 0))
            h = int(d.get("win_h", 0))
            x = int(d.get("win_x", 0))
            y = int(d.get("win_y", 0))
            maximized = bool(d.get("maximized"))
        except (TypeError, ValueError):
            return
        if w < 200 or h < 120:      # 过滤非法 / 初始默认值
            return
        try:
            # 钳制到主显示器工作区，保证窗口至少露出一角
            wa = Gdk.Screen.get_default().get_monitor_workarea(0)
            x = max(wa.x - w + 100, min(x, wa.x + wa.width - 100))
            y = max(wa.y - 40, min(y, wa.y + wa.height - 60))
            self.resize(w, h)
            self.move(x, y)
        except Exception:
            pass
        if maximized:
            self.maximize()

    # ---------------- 终端背景图 ----------------
    def _on_white_alpha_changed(self, scale):
        """白罩滑块：0-100 -> 0.0-1.0，重绘终端；停拖 1 秒后保存设置"""
        self._white_alpha = scale.get_value() / 100.0
        if self._bg_pixbuf is not None:
            self.term.queue_draw()
        if getattr(self, "_white_save_id", None):
            GLib.source_remove(self._white_save_id)
        self._white_save_id = GLib.timeout_add_seconds(1, self._flush_white_save)

    def _flush_white_save(self):
        self._white_save_id = None
        self._save_settings()
        return False

    def _set_background_image(self, path):
        # 照抄 xfce4-terminal 真实源码（terminal-screen.c）：
        #  1) 背景色用 vte_terminal_set_color_background 设置带 alpha 的遮罩色
        #  2) 壁纸在 VTE 的 draw 回调里先画（SOURCE 铺底），再把 VTE 离屏渲染 OVER 叠加
        # 踩坑记录（全部实测排除）：
        #  - set_opacity(0)：壁纸透出但文字一起消失（不能用）
        #  - CSS background-color/background-image：这台 VTE 不认（不能用）
        #  - set_colors 背景参数带 alpha：VTE 内部不保留 alpha，画纯色盖壁纸（不能用）
        if cairo is None:
            self._show_error("缺少 python3-cairo：sudo apt install python3-cairo")
            return
        try:
            self._bg_pixbuf = GdkPixbuf.Pixbuf.new_from_file(path)
        except Exception as e:
            self._show_error("无法加载图片：%s" % e)
            return
        self._bg_path = path
        if self._draw_id is None:
            self._draw_id = self.term.connect("draw", self._on_vte_draw)
        self.term.queue_draw()
        if getattr(self, "_white_scale", None):
            self._white_scale.set_sensitive(True)   # 有背景：白罩可调
        self._apply_theme(self.current_theme)   # 重设背景色（带 alpha 遮罩）
        self._save_settings()                   # 记住壁纸

    # ---------------- 视频壁纸（GStreamer appsink 取帧） ----------------
    def _clear_background(self):
        self._bg_pixbuf = None
        self._bg_path = None
        if self._draw_id is not None:
            self.term.disconnect(self._draw_id)
            self._draw_id = None
        self.term.queue_draw()
        if getattr(self, "_white_scale", None):
            self._white_scale.set_sensitive(False)  # 无背景：白罩不可调
        self._apply_theme(self.current_theme)   # 恢复不透明背景色
        self._save_settings()                   # 记住已清除

    def _on_vte_draw(self, widget, cr):
        """xfce4-terminal 同款：先画壁纸，再把 VTE 离屏渲染 OVER 叠加"""
        if self._bg_pixbuf is None:
            return False
        alloc = widget.get_allocation()
        w, h = alloc.width, alloc.height
        if w <= 0 or h <= 0:
            return False
        widget.handler_block(self._draw_id)
        try:
            # 1) 壁纸铺底（cover 等比、居中）
            pw = self._bg_pixbuf.get_width()
            ph = self._bg_pixbuf.get_height()
            s = max(w / pw, h / ph)
            cr.save()
            cr.rectangle(0, 0, w, h)
            cr.clip()
            cr.translate((w - pw * s) / 2, (h - ph * s) / 2)
            cr.scale(s, s)
            Gdk.cairo_set_source_pixbuf(cr, self._bg_pixbuf, 0, 0)
            cr.set_operator(cairo.OPERATOR_SOURCE)
            cr.paint()
            cr.restore()

            # 2) 白罩（可调透明度的白色遮罩，盖在壁纸上、终端之下）
            if self._white_alpha > 0:
                cr.save()
                cr.rectangle(0, 0, w, h)
                cr.set_source_rgba(1.0, 1.0, 1.0, self._white_alpha)
                cr.set_operator(cairo.OPERATOR_OVER)
                cr.fill()
                cr.restore()

            # 3) VTE 离屏渲染（背景 alpha=0，透明不遮壁纸；文字正常清晰）
            surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, w, h)
            ctx = cairo.Context(surface)
            widget.draw(ctx)

            # 4) OVER 叠加到壁纸/白罩上（文字在最上）
            cr.set_source_surface(surface, 0, 0)
            cr.set_operator(cairo.OPERATOR_OVER)
            cr.paint()
        finally:
            widget.handler_unblock(self._draw_id)
        return True   # 消费 draw 事件

    def _use_default_wallpaper(self):
        # 白色主题 -> 原来的 wallpaper.jpg；黑色主题 -> wallpaper_dark.jpg
        name = "wallpaper.jpg" if self.current_theme == "white" else "wallpaper_dark.jpg"
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), name)
        if os.path.exists(path):
            self._set_background_image(path)
        else:
            self._show_error("未找到默认壁纸 %s（应与程序同目录）" % name)

    def _choose_background(self):
        dlg = Gtk.FileChooserDialog(
            title="选择终端背景图片", parent=self,
            action=Gtk.FileChooserAction.OPEN)
        dlg.add_buttons(Gtk.STOCK_CANCEL, Gtk.ResponseType.CANCEL,
                        Gtk.STOCK_OPEN, Gtk.ResponseType.OK)
        filt = Gtk.FileFilter()
        filt.set_name("图片 (PNG/JPG/JPEG/BMP)")
        for pat in ("*.png", "*.jpg", "*.jpeg", "*.bmp"):
            filt.add_pattern(pat)
        dlg.add_filter(filt)
        if dlg.run() == Gtk.ResponseType.OK:
            self._set_background_image(dlg.get_filename())
        dlg.destroy()

    # ---------------- 右键菜单 ----------------
    def _build_menu(self):
        self.menu = Gtk.Menu()
        for label, fn in (
            ("复制", lambda: self.term.copy_clipboard()),
            ("粘贴", lambda: self.term.paste_clipboard()),
            ("清屏", lambda: self.term.reset(True, True)),
        ):
            item = Gtk.MenuItem(label=label)
            item.connect("activate", lambda *_: fn())
            self.menu.append(item)

        self.menu.append(Gtk.SeparatorMenuItem())

        # 主题子菜单（黑 / 白，radio 单选）
        theme_item = Gtk.MenuItem(label="主题")
        theme_sub = Gtk.Menu()
        self._theme_radio = {}
        first = None
        for key in ("black", "white"):
            r = Gtk.RadioMenuItem(group=first, label=THEMES[key]["label"])
            if first is None:
                first = r
            r.connect("toggled", self._on_theme_btn_toggled, key)
            self._theme_radio[key] = r
            theme_sub.append(r)
        theme_item.set_submenu(theme_sub)
        self.menu.append(theme_item)

        # 背景图
        bg_item = Gtk.MenuItem(label="选择背景图片…")
        bg_item.connect("activate", lambda *_: self._choose_background())
        self.menu.append(bg_item)
        clear_item = Gtk.MenuItem(label="清除背景")
        clear_item.connect("activate", lambda *_: self._clear_background())
        self.menu.append(clear_item)

        self.menu.show_all()

    def _on_button_press(self, widget, event):
        if event.type == Gdk.EventType.BUTTON_PRESS and event.button == 3:
            self.menu.popup_at_pointer(event)
            return True
        return False

    # ---------------- 启动 shell（兼容不同 VTE 版本签名） ----------------
    def _spawn(self):
        cwd = os.path.expanduser("~")
        argv = [SHELL]
        envv = [f"{k}={v}" for k, v in os.environ.items()]
        envv.append("TERM=xterm-256color")
        envv.append("PS1=" + PS1)
        flags = GLib.SpawnFlags.DO_NOT_REAP_CHILD
        attempts = [
            # 经典签名（VTE 0.60-0.70）：flags, cwd, argv, envv, spawn_flags, child_setup, child_setup_data, cancellable
            lambda: self.term.spawn_sync(Vte.PtyFlags.DEFAULT, cwd, argv, envv,
                                         flags, None, None, None),
            # 简化签名（部分发行版打包）
            lambda: self.term.spawn_sync(Vte.PtyFlags.DEFAULT, cwd, argv, envv,
                                         flags, None, None),
            lambda: self.term.spawn_sync(Vte.PtyFlags.DEFAULT, cwd, argv, envv,
                                         flags, None),
        ]
        for fn in attempts:
            try:
                return fn()
            except TypeError:
                continue
        return (False, -1)

    def _show_error(self, msg):
        dlg = Gtk.MessageDialog(
            transient_for=self, message_type=Gtk.MessageType.ERROR,
            buttons=Gtk.ButtonsType.OK, text=msg,
        )
        dlg.run()
        dlg.destroy()


def main():
    win = KaliTerminal()
    win.connect("destroy", Gtk.main_quit)
    win.show_all()
    win.present()
    Gtk.main()


if __name__ == "__main__":
    main()
