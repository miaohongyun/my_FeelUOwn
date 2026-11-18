import os
import tempfile
import unittest
from types import SimpleNamespace

from fuo_cast.controller import CastController, CastError
from fuo_cast.discovery import Device


SONG = SimpleNamespace(
    title_display="测试曲目",
    artists_name_display="测试歌手",
    album_name_display="测试专辑",
)


def make_media(**overrides):
    values = dict(
        url="http://cdn.example.com/a.mp3?sign=xyz",
        http_headers={"Cookie": "sid=abc"},
        http_proxy="http://127.0.0.1:7890",
        decryption_key=None,
        props=SimpleNamespace(format="mp3", duration=200),
    )
    values.update(overrides)
    return SimpleNamespace(**values)


class ControllerTest(unittest.TestCase):
    def setUp(self):
        self.app = SimpleNamespace(
            playlist=SimpleNamespace(current_song=SONG),
            player=SimpleNamespace(current_media=make_media(), volume=80, state=2),
            config=SimpleNamespace(),
        )
        config = SimpleNamespace(
            HTTP_PORT=0,
            BIND_ADDRESS="127.0.0.1",
            LAN_IP="127.0.0.1",
            SSDP_TIMEOUT=0.1,
            SSDP_BIND_IP="",
            SOAP_TIMEOUT=1.0,
            MUTE_LOCAL=True,
        )
        self.ctrl = CastController(self.app, config)
        self.ctrl.initialize()
        self.temp_files = []

    def tearDown(self):
        for path in self.temp_files:
            try:
                os.unlink(path)
            except OSError:
                pass
        self.ctrl.shutdown()

    def test_make_entry_remote(self):
        entry = self.ctrl._make_entry(make_media(), SONG)
        self.assertFalse(entry.is_local)
        self.assertEqual(entry.mime, "audio/mpeg")
        self.assertEqual(entry.headers["Cookie"], "sid=abc")
        self.assertEqual(entry.proxy, "http://127.0.0.1:7890")
        self.assertEqual(entry.title, "测试曲目")
        self.assertEqual(entry.artist, "测试歌手")
        self.assertEqual(entry.duration, 200)

    def test_make_entry_local_file(self):
        with tempfile.NamedTemporaryFile(delete=False, suffix=".flac") as handle:
            handle.write(b"fake audio")
            path = handle.name
        self.temp_files.append(path)
        entry = self.ctrl._make_entry(
            make_media(url=path, http_proxy="", props=SimpleNamespace(format="", duration=0)),
            SONG,
        )
        self.assertTrue(entry.is_local)
        self.assertEqual(entry.mime, "audio/flac")

    def test_decryption_key_is_rejected(self):
        with self.assertRaises(CastError) as ctx:
            self.ctrl._make_entry(make_media(decryption_key="k1:v1"), SONG)
        self.assertIn("解密", str(ctx.exception))

    def test_missing_url_is_rejected(self):
        media = SimpleNamespace(
            url="",
            _manifest=None,
            http_headers={},
            http_proxy="",
            decryption_key=None,
            props=None,
        )
        with self.assertRaises(CastError):
            self.ctrl._make_entry(media, SONG)

    def test_video_audio_manifest_uses_audio_url(self):
        manifest = SimpleNamespace(audio_url="http://cdn.example.com/audio.m4a")
        entry = self.ctrl._make_entry(
            make_media(url="", _manifest=manifest, props=SimpleNamespace(format="m4a", duration=0)),
            SONG,
        )
        self.assertEqual(entry.source, "http://cdn.example.com/audio.m4a")
        self.assertEqual(entry.mime, "audio/mp4")

    def test_nonexistent_local_file_is_rejected(self):
        with self.assertRaises(CastError):
            self.ctrl._make_entry(
                make_media(url="E:/definitely/not/here.mp3", props=None), SONG
            )

    def test_cast_mutes_local_and_stop_restores_volume(self):
        device = Device(
            udn="uuid:fake-tv",
            name="客厅电视",
            location="http://127.0.0.1:1/dmr.xml",
            avtransport_url="http://127.0.0.1:1/AVTransport/ctrl",
        )
        # 只验证音量与状态编排，真正的推送链路在 test_integration 里实测
        self.ctrl._push_current = lambda: None
        self.ctrl.cast_to(device)
        self.assertEqual(self.app.player.volume, 0)
        self.assertTrue(self.ctrl.active)
        self.ctrl.stop_cast()
        self.assertEqual(self.app.player.volume, 80)
        self.assertFalse(self.ctrl.active)

    def test_song_changed_without_active_cast_is_noop(self):
        media = make_media(url="http://cdn.example.com/b.mp3")
        self.ctrl._on_song_changed(SONG, media)
        self.assertIs(self.ctrl._current_media, media)
        self.assertFalse(self.ctrl.active)


if __name__ == "__main__":
    unittest.main()
