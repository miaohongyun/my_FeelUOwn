import logging

from PyQt6.QtWidgets import QPushButton

from feeluown.utils import aio
from feeluown.library import ModelType, ModelState, MediaNotFound
from feeluown.gui.helpers import async_run
from feeluown.media import Media
from feeluown.gui.widgets.statusline import StatusLineItem

from .manager import DownloadManager
from .helpers import cook_filename, guess_media_url_ext
from .statusline import DownloadLabel
from .quality import choose_quality, prepare_media
from .fallback import choose_open_source, download_open_track, is_lossless_media
from .custom_media import add_menu_action as add_custom_media_action

from .tag_manager import TagManager


logger = logging.getLogger(__name__)


class DownloadUi:
    def __init__(self, mgr: DownloadManager, tag_mgr: TagManager, app, ui):
        """

        :type app: feeluown.app.App
        :type ui: feeluown.ui.Ui
        """
        self._mgr = mgr
        self._tag_mgr = tag_mgr
        self._app = app
        self._ui = ui
        self.cur_song_dl_btn = getattr(ui.pc_panel, "download_btn", None)
        if self.cur_song_dl_btn is None:
            self.cur_song_dl_btn = QPushButton(ui.pc_panel)
            self.cur_song_dl_btn.setObjectName("download_btn")
            self.cur_song_dl_btn.setToolTip("下载当前歌曲")
            self.cur_song_dl_btn.setCheckable(True)
            self.cur_song_dl_btn.setFixedSize(15, 15)
            ui.pc_panel.download_btn = self.cur_song_dl_btn
        status_line = getattr(self._ui.bottom_panel, "status_line", None)
        if status_line is not None:
            status_line.add_item(
                StatusLineItem("download", DownloadLabel(self._app, mgr))
            )

    def initialize(self):
        logger.info(f"fuo-dl init")
        self._app.player.media_changed.connect(self._on_media_changed, aioqueue=True)

        # bind download btn clicked signal
        try:
            self.cur_song_dl_btn.disconnect()
        except TypeError:  # signal has no slots
            pass
        self.cur_song_dl_btn.clicked.connect(self._download_cur_media)
        self._ui.pc_panel._sub_top_layout.addSpacing(8)
        self._ui.pc_panel._sub_top_layout.addWidget(self.cur_song_dl_btn)
        self._ui.songs_table.about_to_show_menu.connect(self._add_download_action)
        self._ui.songs_table.about_to_show_menu.connect(self._add_custom_media_action)
        self.cur_song_dl_btn.show()

        self._mgr.download_finished.connect(self._tag_mgr.write_tag)

    def _add_custom_media_action(self, ctx):
        """右键菜单里的「播放自定义音频地址」入口。"""
        add_custom_media_action(self._app, ctx)

    def _on_media_changed(self, media):
        async def func(media):
            # FIXME: current media may be unrelated to current song
            song = self._app.playlist.current_song
            # 自定义播放地址：能否下载只看地址本身，与当前歌曲无关
            entry = getattr(media, "custom_entry", None)
            if entry is not None:
                self.cur_song_dl_btn.setEnabled(media.url.startswith("http"))
                self.cur_song_dl_btn.setChecked(False)
                return
            if song is None or media is None:
                self.cur_song_dl_btn.setEnabled(False)
                return
            media_url = media.url
            if media_url and not media_url.startswith("http"):
                self.cur_song_dl_btn.setEnabled(False)
                self.cur_song_dl_btn.setChecked(True)
                return

            ext = guess_media_url_ext(media_url)
            if self._tag_mgr._name_fmts:
                if hasattr(song, "state") and song.state is ModelState.cant_upgrade:
                    # BriefSongModdel
                    tag_obj, cover_url = await aio.run_fn(
                        self._tag_mgr.prepare_tag, song
                    )
                else:
                    song = await aio.run_fn(self._app.library.song_upgrade, song)
                    album = await aio.run_fn(self._app.library.album_upgrade, song.album)
                    artists = [
                        await aio.run_fn(self._app.library.artist_upgrade, artist)
                        for artist in song.artists
                    ]
                    tag_obj, cover_url = await aio.run_fn(
                        self._tag_mgr.prepare_tag, song, album, artists
                    )
                filename = self._tag_mgr.prepare_filename(tag_obj, ext)
            else:
                title = await async_run(lambda: song.title)
                artists_name = await async_run(lambda: song.artists_name)
                filename = cook_filename(title, artists_name, ext)
            is_downloaded = self._mgr.is_file_downloaded(filename)
            if is_downloaded:
                self.cur_song_dl_btn.setEnabled(False)
                self.cur_song_dl_btn.setChecked(True)
            else:
                self.cur_song_dl_btn.setEnabled(True)
                self.cur_song_dl_btn.setChecked(False)

        aio.create_task(func(media))

    def _download_cur_media(self, _checked=False):
        # QPushButton.clicked 会带上 checked 状态，这里显式接收以兼容不同 Qt 绑定。
        media = getattr(self._app.player, "current_media", None)
        entry = getattr(media, "custom_entry", None)
        if entry is not None:
            aio.create_task(self._download_custom(media, entry))
            return
        song = self._app.playlist.current_song
        if song is None:
            return
        aio.create_task(self._download_songs([song]))

    async def _download_custom(self, media, entry):
        """下载自定义播放地址；文件名用条目标题，不写歌曲 tag。"""
        url = media.url or ""
        if not url.startswith("http"):
            logger.info("自定义音源不是网络地址，跳过下载：%s", url)
            return
        filename = cook_filename(
            entry.title, "自定义音源", guess_media_url_ext(url)
        )
        if self._mgr.is_file_downloaded(filename):
            logger.info(f"download {filename} has exists")
            return
        logger.info(f"download {url} into {filename}")
        await self._mgr.get(media, filename)

    async def _download_songs(self, songs):
        """批量下载前的统一入口：只弹一次音质选择框，然后逐首提交任务。"""
        cancelled, quality = await choose_quality(self._app, songs)
        if cancelled:
            logger.info("用户取消了下载音质选择")
            return
        for song in songs:
            aio.create_task(self.download_song(song, quality=quality))

    async def download_song(self, song, quality=None):
        """下载单首歌。quality 为 None 时沿用原来的播放策略。"""
        try:
            media = await aio.run_fn(
                prepare_media,
                self._app,
                song,
                quality,
            )
        except MediaNotFound:
            media = None

        # 原音源不是无损（或者根本没有流）时，尝试从开放曲库补一首真无损
        if media is None or not is_lossless_media(media):
            track = await choose_open_source(self._app, song)
            if track is not None:
                await download_open_track(
                    self._app, track, self._mgr.download_dir
                )
                return

        if media is None:
            logger.warning("没有找到可下载的音频：%s", song)
            return
        media_url = media.url
        if media_url:
            if not media_url.startswith("http"):
                # media_url in local provider is filelname
                logger.info(f"download {media_url} has exists")
                return

            ext = guess_media_url_ext(media_url)
            if self._tag_mgr._name_fmts:
                if hasattr(song, "state") and song.state is ModelState.cant_upgrade:
                    # BriefSongModdel
                    tag_obj, cover_url = await aio.run_fn(
                        self._tag_mgr.prepare_tag, song
                    )
                else:
                    song = await aio.run_fn(self._app.library.song_upgrade, song)
                    album = await aio.run_fn(self._app.library.album_upgrade, song.album)
                    artists = [
                        await aio.run_fn(self._app.library.artist_upgrade, artist)
                        for artist in song.artists
                    ]
                    tag_obj, cover_url = await aio.run_fn(
                        self._tag_mgr.prepare_tag, song, album, artists
                    )
                tag_obj, cover_url = await aio.run_fn(
                    self._tag_mgr.prepare_tag, song, album, artists
                )
                filename = self._tag_mgr.prepare_filename(tag_obj, ext)
            else:
                title = await async_run(lambda: song.title)
                artists_name = await async_run(lambda: song.artists_name)
                filename = cook_filename(title, artists_name, ext)
            if quality is not None:
                # V1 的文件名不含码率，不同音质会互相判定为「已下载」而跳过。
                stem, dot, suffix = filename.rpartition(".")
                filename = (
                    f"{stem}_{quality.value}.{suffix}" if dot
                    else f"{filename}_{quality.value}"
                )
            is_downloaded = self._mgr.is_file_downloaded(filename)
            if is_downloaded:
                logger.info(f"download {filename} has exists")
                return

            logger.info(f"download {media_url} into {filename}")
            file_path = await self._mgr.get(media, filename)

            if self._tag_mgr._name_fmts and file_path and cover_url:
                self._tag_mgr.put_f(filename, file_path, tag_obj, cover_url)
        else:
            # this should not happen, so we log a error msg
            logger.error("url of current song is empty, will not download")

    def _add_download_action(self, ctx):
        def download(models):
            # Note that these models may be the same.
            songs = [
                model for model in set(models)
                if model.meta.model_type == ModelType.song
            ]
            if not songs:
                return
            for model in songs:
                logger.info(f"add download task: {model}")
            # 多选时只弹一次音质选择框，用第一首歌的可用档位代表整批。
            aio.create_task(self._download_songs(songs))

        add_action = ctx["add_action"]
        add_action("下载歌曲", download)
