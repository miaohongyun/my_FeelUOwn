"""
fuo_dl
------
fuo_dl 是 FeelUOwn 的一个音乐下载插件。

fuo_dl 支持多首歌并行下载，也支持一首歌分多段并行下载（也就是常说的多线程下载）。
另外，用户可以在 ~/.fuorc 中自定义下载路径::

   config.fuo_dl.DOWNLOAD_DIR = '~/Music'
"""

import logging

from feeluown.consts import SONG_DIR as DEFAULT_DOWNLOAD_DIR
from .manager import DownloadManager
from .playback import install as install_playback
from .tag_manager import TagManager

__alias__ = "音乐下载"
__desc__ = __doc__
__version__ = "0.1"


logger = logging.getLogger(__name__)


dm_mgr = None
tg_mgr = None
dm_ui = None
#: 播放桥实例，disable 时需要还原 library.song_prepare_media
playback_bridge = None

# keep a reference to the UI instance, so that the install will not be deleted.
dm_ui_v2 = None


def init_config(config):
    config.deffield("DOWNLOAD_DIR", type_=str, default=DEFAULT_DOWNLOAD_DIR, desc="")
    # CORE_LANGUAGE: 写入tag的语言类型, auto/ch/tc: 不进行转换/强制转简体中文/强制转繁体中文
    config.deffield("CORE_LANGUAGE", type_=str, default="auto", desc="")
    # NAME_FORMATS: 以关键字段为标识的命名方式(根据优先级排序的列表), 目前仅支持 albumartist/album/artist/title/track五种字段
    # eg. ['{albumartist}/{album}/{track} {title}', '{albumartist}/{album}/{title}', '{artist}/{title}']
    config.deffield("NAME_FORMATS", type_=list, default=[], desc="")

    # V2 的功能预期和之前（V1）一样，V1 的功能比较丰富，但代码层面比较复杂。
    # V2 只会兼容最新的 Model 设计，这样代码会简单很多。
    config.deffield("ENABLE_V2", type_=bool, default=False)

    # 下载前弹出音质选择框，选项按品质由高到低排列（shq > hq > sq > lq）。
    # 设为 False 则恢复旧行为：直接按播放策略 AUDIO_SELECT_POLICY 取流。
    config.deffield(
        "ASK_QUALITY", type_=bool, default=True,
        desc="下载前弹出音质选择框（品质由高到低）",
    )

    # 下载完成后自动做真无损校验：识别「有损转码成 FLAC」和「假 Hi-Res」。
    # 需要系统安装 ffmpeg 与 ffprobe；没装时只按编码格式判定，不会报错。
    config.deffield(
        "VERIFY_LOSSLESS", type_=bool, default=True,
        desc="下载完成后校验是否为真无损",
    )

    # 原音源不是无损时，检索开放曲库（Internet Archive / Jamendo）并弹框让用户挑。
    # 只覆盖公共领域和 CC 授权作品，主流流行专辑搜不到。
    config.deffield(
        "OPEN_SOURCE_FALLBACK", type_=bool, default=True,
        desc="拿不到无损时从开放曲库补充",
    )

    # Jamendo 的免费 client_id，到 https://devportal.jamendo.com 申请。
    # 留空时只检索 Internet Archive。
    config.deffield(
        "JAMENDO_CLIENT_ID", type_=str, default="",
        desc="Jamendo client_id，留空则只用 Internet Archive",
    )

    # 播放时如果原音源不是无损，去开放曲库找同名无损版本并缓存。
    config.deffield(
        "PLAY_OPEN_SOURCE_FALLBACK", type_=bool, default=True,
        desc="播放时自动补充开放曲库的无损音源",
    )

    # 第一次播放最多等多久（秒）让无损音源就绪；0 表示不等，后台准备、
    # 下次播放生效。
    config.deffield(
        "PLAY_WAIT_TIMEOUT", type_=int, default=10,
        desc="首次播放等待无损音源的秒数（0 表示不等待）",
    )

    # 播放当前歌曲时，后台顺手为下一首准备无损，切歌时通常不用再等。
    config.deffield(
        "PLAY_PREFETCH_NEXT", type_=bool, default=True,
        desc="播放时预取下一首的无损音源",
    )


def enable_v2(app):
    global dm_ui_v2

    if app.mode & app.GuiMode:
        from fuo_dl.v2.ui import UI

        ui = UI(app)
        ui.download_mgr.initialize()
        dm_ui_v2 = ui


def enable(app):
    global dm_mgr, dm_ui, tg_mgr, playback_bridge

    # 播放链路的无损补充与下载 UI 相互独立，V1/V2 都要挂载。
    playback_bridge = playback_bridge or install_playback(app)

    if app.config.dl.ENABLE_V2:
        return enable_v2(app)

    # initialize download manager
    dm_mgr = dm_mgr or DownloadManager(app, app.config.fuo_dl)
    dm_mgr.initialize()

    # initialize tag manager
    tg_mgr = tg_mgr or TagManager(app, app.config.fuo_dl)
    app.tag_mgr = tg_mgr

    # initialize ui for download manager
    if app.mode & app.GuiMode:
        from .ui import DownloadUi  # noqa

        dm_ui = dm_ui or DownloadUi(dm_mgr, tg_mgr, app, app.ui)
        dm_ui.initialize()


def disable(app):
    global playback_bridge
    if playback_bridge is not None:
        playback_bridge.uninstall()
        playback_bridge = None
