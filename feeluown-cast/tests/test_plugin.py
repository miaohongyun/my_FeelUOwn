"""验证插件契约能被 FeelUOwn 的 Plugin.create / init_config / enable 正常加载。"""
import unittest
from types import SimpleNamespace

import fuo_cast


class FakeConfig:
    def __init__(self):
        self.fields = {}

    def deffield(self, name, **kwargs):
        self.fields[name] = kwargs


class PluginContractTest(unittest.TestCase):
    def test_required_metadata(self):
        for name in ("__alias__", "__desc__", "__version__"):
            self.assertTrue(hasattr(fuo_cast, name), name)
        self.assertIsNotNone(fuo_cast.__desc__)
        for fn in ("init_config", "enable", "disable"):
            self.assertTrue(callable(getattr(fuo_cast, fn, None)), fn)

    def test_init_config_fields(self):
        config = FakeConfig()
        fuo_cast.init_config(config)
        expected = {
            "HTTP_PORT",
            "BIND_ADDRESS",
            "LAN_IP",
            "SSDP_TIMEOUT",
            "SSDP_BIND_IP",
            "SOAP_TIMEOUT",
            "MUTE_LOCAL",
        }
        self.assertTrue(expected.issubset(config.fields.keys()))
        self.assertEqual(config.fields["HTTP_PORT"]["default"], 8765)
        self.assertIs(config.fields["MUTE_LOCAL"]["default"], True)

    def test_enable_disable_in_headless_mode(self):
        plugin_config = SimpleNamespace(
            HTTP_PORT=0,
            BIND_ADDRESS="127.0.0.1",
            LAN_IP="127.0.0.1",
            SSDP_TIMEOUT=0.1,
            SSDP_BIND_IP="",
            SOAP_TIMEOUT=1.0,
            MUTE_LOCAL=False,
        )
        app = SimpleNamespace(
            config=SimpleNamespace(cast=plugin_config),
            playlist=SimpleNamespace(current_song=None),
            player=SimpleNamespace(current_media=None, volume=50, state=0),
            mode=0,
            GuiMode=1,
        )
        fuo_cast.enable(app)
        try:
            self.assertIsNotNone(fuo_cast._controller)
            self.assertIsNotNone(fuo_cast._controller._server)
            self.assertIsNone(fuo_cast._ui)
        finally:
            fuo_cast.disable(app)
        self.assertIsNone(fuo_cast._controller)
        self.assertEqual(app.player.volume, 50)


if __name__ == "__main__":
    unittest.main()
