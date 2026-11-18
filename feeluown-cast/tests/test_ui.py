"""用严格的 Qt 替身验证 UI 逻辑（本机未安装 PyQt6，不需要真实 Qt 也能跑）。

替身只实现插件真正用到的 API，访问其它属性会抛 AttributeError，
这样能抓出 Qt 方法名拼写错误，而不是被宽松的 MagicMock 掩盖过去。
"""
import importlib
import sys
import types
import unittest
from contextlib import contextmanager
from types import SimpleNamespace
from unittest import mock


class FakeSignal:
    def __init__(self):
        self.callbacks = []

    def connect(self, fn):
        self.callbacks.append(fn)


class FakeTimer:
    def __init__(self, *args):
        self.timeout = FakeSignal()
        self.interval = 0
        self.started = False
        self.stopped = False

    def setInterval(self, value):
        self.interval = value

    def start(self):
        self.started = True

    def stop(self):
        self.stopped = True


class FakeLayout:
    def __init__(self, *args):
        self.widgets = []
        self.layouts = []

    def addWidget(self, widget, *args):
        self.widgets.append(widget)
        return widget

    def addLayout(self, layout):
        self.layouts.append(layout)

    def addSpacing(self, value):
        pass


class FakeWidget:
    def __init__(self, *args, **kwargs):
        self.clicked = FakeSignal()
        self._text = ""
        self._tooltip = ""
        self._enabled = True

    def __getattr__(self, name):
        raise AttributeError(f"替身未实现 Qt 属性: {name}")

    def setText(self, value):
        self._text = value

    def setToolTip(self, value):
        self._tooltip = value

    def setFixedHeight(self, value):
        pass

    def setWordWrap(self, value):
        pass

    def setEnabled(self, value):
        self._enabled = value

    def isEnabled(self):
        return self._enabled

    def setWindowTitle(self, value):
        pass

    def resize(self, *args):
        pass

    def show(self):
        pass

    def raise_(self):
        pass

    def activateWindow(self):
        pass

    def close(self):
        pass

    def deleteLater(self):
        pass

    def setParent(self, parent):
        pass


class FakeListWidget(FakeWidget):
    def __init__(self, *args):
        super().__init__(*args)
        self.itemDoubleClicked = FakeSignal()
        self._items = []
        self._row = -1

    def clear(self):
        self._items = []
        self._row = -1

    def addItem(self, text):
        self._items.append(text)

    def setCurrentRow(self, row):
        self._row = row

    def currentRow(self):
        return self._row


class FakeDialog(FakeWidget):
    pass


class FakePanel:
    def __init__(self):
        self._sub_top_layout = FakeLayout()
        self._main_layout = FakeLayout()

    def layout(self):
        return self._main_layout


@contextmanager
def loaded_ui():
    core = types.ModuleType("PyQt6.QtCore")
    core.QTimer = FakeTimer
    widgets = types.ModuleType("PyQt6.QtWidgets")
    widgets.QDialog = FakeDialog
    widgets.QHBoxLayout = FakeLayout
    widgets.QVBoxLayout = FakeLayout
    widgets.QLabel = FakeWidget
    widgets.QListWidget = FakeListWidget
    widgets.QPushButton = FakeWidget
    widgets.QToolButton = FakeWidget
    package = types.ModuleType("PyQt6")
    package.__path__ = []
    patched = {
        "PyQt6": package,
        "PyQt6.QtCore": core,
        "PyQt6.QtWidgets": widgets,
    }
    with mock.patch.dict(sys.modules, patched):
        sys.modules.pop("fuo_cast.ui", None)
        module = importlib.import_module("fuo_cast.ui")
        try:
            yield module
        finally:
            sys.modules.pop("fuo_cast.ui", None)


def fake_controller(devices=()):
    state = {"casted": [], "stopped": False}
    return SimpleNamespace(
        status="未投放",
        active=False,
        devices=list(devices),
        discover=lambda: list(devices),
        cast_to=lambda device: state["casted"].append(device),
        stop_cast=lambda: state.__setitem__("stopped", True),
        _state=state,
    )


class CastUiTest(unittest.TestCase):
    def test_button_injected_and_status_synced(self):
        with loaded_ui() as ui_mod:
            panel = FakePanel()
            app = SimpleNamespace(ui=SimpleNamespace(pc_panel=panel))
            ctrl = fake_controller()
            cast_ui = ui_mod.CastUI(app, ctrl)
            cast_ui.initialize()

            self.assertEqual(len(panel._sub_top_layout.widgets), 1)
            self.assertEqual(len(panel._main_layout.widgets), 0)
            self.assertTrue(cast_ui._timer.started)

            cast_ui._sync_status()
            self.assertEqual(cast_ui._button._text, "投屏")
            ctrl.active = True
            cast_ui._sync_status()
            self.assertEqual(cast_ui._button._text, "投屏中")

            cast_ui._open_dialog()
            self.assertIsNotNone(cast_ui._dialog)
            cast_ui.shutdown()
            self.assertIsNone(cast_ui._button)
            self.assertIsNone(cast_ui._dialog)

    def test_falls_back_to_main_layout(self):
        class BarePanel:
            def __init__(self):
                self._main_layout = FakeLayout()

            def layout(self):
                return self._main_layout

        with loaded_ui() as ui_mod:
            panel = BarePanel()
            app = SimpleNamespace(ui=SimpleNamespace(pc_panel=panel))
            cast_ui = ui_mod.CastUI(app, fake_controller())
            cast_ui.initialize()
            self.assertEqual(len(panel._main_layout.widgets), 1)
            cast_ui.shutdown()


class DeviceDialogTest(unittest.TestCase):
    def test_refresh_cast_and_stop(self):
        device = SimpleNamespace(name="客厅 TCL T7L", label="客厅 TCL T7L (TCL T7L)")
        with loaded_ui() as ui_mod:
            ctrl = fake_controller([device])
            dialog = ui_mod.DeviceDialog(None, ctrl, None)

            dialog.refresh()
            self.assertEqual(len(dialog._devices), 1)
            self.assertEqual(dialog._list.currentRow(), 0)
            self.assertEqual(dialog._list._items, [device.label])
            self.assertTrue(dialog._refresh_btn.isEnabled())

            dialog.cast_selected()
            self.assertEqual(ctrl._state["casted"], [device])

            ctrl.active = True
            dialog._sync_status()
            self.assertTrue(dialog._stop_btn.isEnabled())
            dialog._on_stop()
            self.assertTrue(ctrl._state["stopped"])

    def test_empty_device_list_shows_hint(self):
        with loaded_ui() as ui_mod:
            ctrl = fake_controller()
            dialog = ui_mod.DeviceDialog(None, ctrl, None)
            dialog.refresh()
            self.assertEqual(dialog._devices, [])
            self.assertIn("没找到设备", dialog._hint._text)
            dialog.cast_selected()
            self.assertIn("刷新设备", dialog._hint._text)


if __name__ == "__main__":
    unittest.main()
