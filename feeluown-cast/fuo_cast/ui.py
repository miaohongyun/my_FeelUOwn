"""设备投放的 Qt 界面：播放控制栏按钮 + 设备列表对话框。

只在 GUI 模式被 __init__.enable() 导入，所以核心模块可以脱离 PyQt6 单测。
状态用 QTimer 轮询而不是从线程池直接回调 Qt 控件，避免跨线程崩溃。
"""
from __future__ import annotations

import logging

from PyQt6.QtCore import QTimer
from PyQt6.QtWidgets import (
    QDialog,
    QHBoxLayout,
    QLabel,
    QListWidget,
    QPushButton,
    QToolButton,
    QVBoxLayout,
)

logger = logging.getLogger(__name__)


class CastUI:
    def __init__(self, app, controller):
        self._app = app
        self._ctrl = controller
        self._button: QToolButton | None = None
        self._dialog: QDialog | None = None
        self._timer: QTimer | None = None

    def initialize(self) -> None:
        panel = self._app.ui.pc_panel
        button = QToolButton(panel)
        button.setText("投屏")
        button.setToolTip("设备投放（DLNA）— 把当前歌曲推送到电视播放")
        button.setFixedHeight(16)
        button.clicked.connect(self._open_dialog)

        inserted = False
        layout = getattr(panel, "_sub_top_layout", None)
        if layout is not None:
            try:
                layout.addSpacing(8)
                layout.addWidget(button)
                inserted = True
            except Exception:  # noqa: BLE001
                logger.exception("插入投屏按钮到标题栏失败，回退到主布局")
        if not inserted:
            panel.layout().addWidget(button)
        self._button = button

        self._timer = QTimer(button)
        self._timer.setInterval(500)
        self._timer.timeout.connect(self._sync_status)
        self._timer.start()

    def _sync_status(self) -> None:
        if self._button is None:
            return
        self._button.setText("投屏中" if self._ctrl.active else "投屏")
        self._button.setToolTip(self._ctrl.status)

    def _open_dialog(self) -> None:
        if self._dialog is None:
            self._dialog = DeviceDialog(self._app, self._ctrl, self._button)
        self._dialog.show()
        self._dialog.raise_()
        self._dialog.activateWindow()
        self._dialog.refresh()

    def shutdown(self) -> None:
        if self._timer is not None:
            self._timer.stop()
            self._timer = None
        if self._dialog is not None:
            self._dialog.close()
            self._dialog.deleteLater()
            self._dialog = None
        if self._button is not None:
            self._button.setParent(None)
            self._button = None


class DeviceDialog(QDialog):
    def __init__(self, app, controller, parent=None):
        super().__init__(parent)
        self._app = app
        self._ctrl = controller
        self._devices: list = []
        self._refreshing = False

        self.setWindowTitle("设备投放 · DLNA")
        self.resize(430, 330)

        self._list = QListWidget(self)
        self._hint = QLabel("点击「刷新设备」搜索同一局域网内的电视", self)
        self._hint.setWordWrap(True)
        self._status = QLabel("未投放", self)
        self._status.setWordWrap(True)

        self._refresh_btn = QPushButton("刷新设备", self)
        self._cast_btn = QPushButton("投放", self)
        self._stop_btn = QPushButton("停止投放", self)
        self._stop_btn.setEnabled(False)

        buttons = QHBoxLayout()
        buttons.addWidget(self._refresh_btn)
        buttons.addWidget(self._cast_btn)
        buttons.addWidget(self._stop_btn)

        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("可投放设备：", self))
        layout.addWidget(self._list, 1)
        layout.addWidget(self._hint)
        layout.addWidget(self._status)
        layout.addLayout(buttons)

        self._refresh_btn.clicked.connect(self.refresh)
        self._cast_btn.clicked.connect(self.cast_selected)
        self._stop_btn.clicked.connect(self._on_stop)
        self._list.itemDoubleClicked.connect(lambda *_: self.cast_selected())

        self._timer = QTimer(self)
        self._timer.setInterval(500)
        self._timer.timeout.connect(self._sync_status)
        self._timer.start()

    def _async(self, afn, *args):
        try:
            from feeluown.utils.aio import run_afn
        except ImportError:
            import asyncio

            asyncio.run(afn(*args))
        else:
            run_afn(afn, *args)

    @staticmethod
    async def _in_thread(fn, *args):
        try:
            from feeluown.utils.aio import run_fn
        except ImportError:
            return fn(*args)
        return await run_fn(fn, *args)

    def refresh(self) -> None:
        if self._refreshing:
            return
        self._refreshing = True
        self._refresh_btn.setEnabled(False)
        self._hint.setText("正在搜索设备…（约 3 秒）")
        self._async(self._do_refresh)

    async def _do_refresh(self) -> None:
        try:
            devices = await self._in_thread(self._ctrl.discover)
        except Exception as exc:  # noqa: BLE001
            devices = []
            text = f"搜索失败：{exc}"
        else:
            text = (
                f"找到 {len(devices)} 台设备，选中后点「投放」"
                if devices
                else "没找到设备：请确认电视与电脑在同一 Wi-Fi/局域网，"
                "并在电视上打开「多屏互动 / 媒体共享」开关"
            )
        self._devices = list(devices)
        self._list.clear()
        for device in self._devices:
            self._list.addItem(device.label)
        if self._devices:
            self._list.setCurrentRow(0)
        self._hint.setText(text)
        self._refresh_btn.setEnabled(True)
        self._refreshing = False

    def cast_selected(self) -> None:
        index = self._list.currentRow()
        if not (0 <= index < len(self._devices)):
            self._hint.setText("请先点「刷新设备」并选中一台电视")
            return
        self._hint.setText(f"正在连接 {self._devices[index].name}…")
        self._async(self._do_cast, self._devices[index])

    async def _do_cast(self, device) -> None:
        try:
            await self._in_thread(self._ctrl.cast_to, device)
        except Exception as exc:  # noqa: BLE001
            self._hint.setText(f"连接失败：{exc}")

    def _on_stop(self) -> None:
        self._ctrl.stop_cast()
        self._status.setText("未投放")

    def _sync_status(self) -> None:
        self._status.setText(self._ctrl.status)
        self._stop_btn.setEnabled(self._ctrl.active)
