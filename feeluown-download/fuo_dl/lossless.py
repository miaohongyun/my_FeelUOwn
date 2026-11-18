# -*- coding: utf-8 -*-
"""真无损校验：判断一个音频文件是「无损源转来的无损」，还是「有损转码的假无损」。

市面上大量所谓无损，是把 320kbps MP3 / AAC 直接用 ``ffmpeg -c:a flac`` 转成 FLAC，
容器头写着 lossless，听感却是有损。本模块用三层证据交叉判定：

1. **容器层**：codec 本身就是有损编码（mp3/aac/opus/vorbis…）→ 直接判 ``lossy_source``。
2. **频谱层**：有损转码在截止频率处会留下几十 dB 的垂直断崖，而真实录音是连续滚降。
   这是识别假无损最可靠的证据。
3. **位深层**：声称 24bit 但低 8 位恒为 0，说明本质是 16bit 左移填充的「假 Hi-Res」。

依赖系统的 ``ffmpeg`` / ``ffprobe``。装了 numpy 走 STFT 快速路径，
否则回落到多趟 ``highpass + astats`` 测能量。两者都缺时只做容器层判定，
不会抛异常，也不阻断下载流程。

用法::

    from fuo_dl.lossless import verify, format_report

    result = verify("/path/to/song.flac")
    print(format_report(result))
"""
from __future__ import annotations

import array
import json
import logging
import math
import os
import re
import shutil
import subprocess
from dataclasses import dataclass, field
from typing import List, Optional, Sequence, Tuple

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# 判定阈值：集中放这里，方便按实际样本调参
# ---------------------------------------------------------------------------

#: 相对 1k-5k 参考能量，低于此值视为「该频点没有实质内容」
CUTOFF_DB = -60.0
#: 在 300Hz 窗口内出现这么大的落差，说明是转码造成的人工断崖
CLIFF_DB = 35.0
#: 有陡崖时，截止频率占奈奎斯特低于此比例才算假无损（留一点容差）
CLIFF_CUTOFF_RATIO = 0.94
#: 没有陡崖但截止频率占奈奎斯特低于此值，判可疑（多半是老录音自然滚降）
SUSPICIOUS_CUTOFF_RATIO = 0.80
#: 最高 1kHz 频段低于此值，基本可断定是数字静音（真实录音不会这样）
DIGITAL_SILENCE_DB = -110.0
#: 频谱分析片段长度（秒）
ANALYZE_SECONDS = 20.0
#: 位深检测片段长度（秒）
BIT_DEPTH_SECONDS = 3.0
#: 单帧 FFT 采样点数（44.1k 下 10.8Hz 分辨率）
FRAME_SIZE_LOW = 4096
FRAME_SIZE_HIGH = 8192
#: 最多分析多少帧，长文件均匀抽帧，避免耗时随文件长度线性增长
MAX_FRAMES = 96
#: 采样率上限，DSD/384k 之类降到这里分析
MAX_SAMPLE_RATE = 192000
#: ffprobe / ffmpeg 超时（秒）
PROBE_TIMEOUT = 30
DECODE_TIMEOUT = 180

#: 明确的无损编码
LOSSLESS_CODECS = {
    "flac",
    "alac",
    "ape",
    "wavpack",
    "tta",
    "shorten",
    "mlp",
    "truehd",
    "wmalossless",
}

#: 判定结果
VERDICT_LOSSLESS = "lossless"
VERDICT_SUSPICIOUS = "suspicious"
VERDICT_FAKE_LOSSLESS = "fake_lossless"
VERDICT_LOSSY_SOURCE = "lossy_source"
VERDICT_UNREADABLE = "unreadable"

VERDICT_LABELS = {
    VERDICT_LOSSLESS: "真无损",
    VERDICT_SUSPICIOUS: "可疑",
    VERDICT_FAKE_LOSSLESS: "假无损",
    VERDICT_LOSSY_SOURCE: "有损源",
    VERDICT_UNREADABLE: "无法解析",
}


@dataclass
class AudioProfile:
    """ffprobe 读出来的容器参数。"""

    path: str
    codec: str = ""
    sample_rate: int = 0
    bits_per_sample: int = 0
    bit_rate: int = 0
    duration: float = 0.0
    channels: int = 0
    error: str = ""

    @property
    def is_lossless_codec(self) -> bool:
        return is_lossless_codec(self.codec)

    @property
    def nyquist_hz(self) -> float:
        return self.sample_rate / 2.0 if self.sample_rate else 0.0


@dataclass
class SpectrumProfile:
    """频谱分析的量化结果。"""

    nyquist_hz: float = 0.0
    #: 能量跌到 -60dB 的最高频点
    cutoff_hz: float = 0.0
    #: 最大能量落差（dB）与发生位置
    cliff_drop_db: float = 0.0
    cliff_hz: float = 0.0
    #: 最高 1kHz 频段是否是数字静音
    digital_silence_top: bool = False
    #: 频点数，用于判断结果可信度
    bins: int = 0

    @property
    def cutoff_ratio(self) -> float:
        return self.cutoff_hz / self.nyquist_hz if self.nyquist_hz else 0.0


@dataclass
class VerifyResult:
    """一次完整校验的结果。"""

    path: str
    verdict: str = VERDICT_UNREADABLE
    profile: Optional[AudioProfile] = None
    spectrum: Optional[SpectrumProfile] = None
    #: 实际有效位深（None 表示没测出来）
    effective_bits: Optional[int] = None
    #: 声称位深远高于实测有效位深（假 Hi-Res）
    fake_bit_depth: bool = False
    reasons: List[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return self.verdict == VERDICT_LOSSLESS

    @property
    def label(self) -> str:
        text = VERDICT_LABELS.get(self.verdict, self.verdict)
        # 频谱没造假、只是位深标高了，属于「真无损但假 Hi-Res」，需要区分开
        if self.verdict == VERDICT_LOSSLESS and self.fake_bit_depth:
            return text + "（假 Hi-Res）"
        return text

    def text(self) -> str:
        """一行摘要，适合直接塞进 UI 状态栏。"""
        return format_report(self)


class ToolMissing(RuntimeError):
    """系统里找不到 ffmpeg / ffprobe。"""


# ---------------------------------------------------------------------------
# 外部命令封装
# ---------------------------------------------------------------------------

_FFMPEG: Optional[str] = None
_FFPROBE: Optional[str] = None
_TOOLS_READY = False


def _resolve_tools() -> Tuple[Optional[str], Optional[str]]:
    global _FFMPEG, _FFPROBE, _TOOLS_READY
    if not _TOOLS_READY:
        _FFMPEG = shutil.which("ffmpeg")
        _FFPROBE = shutil.which("ffprobe")
        _TOOLS_READY = True
    return _FFMPEG, _FFPROBE


def _reset_tools_cache() -> None:
    """测试用：清掉命令查找缓存。"""
    global _TOOLS_READY
    _TOOLS_READY = False


def has_tools() -> bool:
    """系统里是否同时具备 ffmpeg 与 ffprobe。"""
    ffmpeg, ffprobe = _resolve_tools()
    return bool(ffmpeg) and bool(ffprobe)


def _run(cmd: Sequence[str], timeout: int) -> bytes:
    """执行命令并返回 stdout，失败时抛 RuntimeError（带 stderr 片段）。"""
    try:
        proc = subprocess.run(
            list(cmd),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired:
        raise RuntimeError("命令执行超时: %s" % cmd[0])
    if proc.returncode != 0:
        err = proc.stderr.decode("utf-8", "replace").strip()
        raise RuntimeError("%s 退出码 %d: %s" % (cmd[0], proc.returncode, err[:300]))
    return proc.stdout


# ---------------------------------------------------------------------------
# 1. 容器层
# ---------------------------------------------------------------------------


def is_lossless_codec(codec: str) -> bool:
    """codec 名称是否属于无损编码。PCM / DSD 全部按无损处理。"""
    if not codec:
        return False
    name = codec.lower()
    return (
        name in LOSSLESS_CODECS
        or name.startswith("pcm_")
        or name.startswith("dsd_")
    )


def probe(path: str) -> AudioProfile:
    """用 ffprobe 读取文件参数。任何失败都收敛成 ``profile.error``，不抛异常。"""
    profile = AudioProfile(path=path)
    _, ffprobe = _resolve_tools()
    if ffprobe is None:
        profile.error = "系统未安装 ffprobe"
        return profile

    cmd = [
        ffprobe,
        "-v",
        "error",
        "-select_streams",
        "a:0",
        "-show_entries",
        "stream=codec_name,sample_rate,channels,bits_per_sample,"
        "bits_per_raw_sample,bit_rate",
        "-show_entries",
        "format=duration,bit_rate",
        "-print_format",
        "json",
        path,
    ]
    try:
        raw = _run(cmd, PROBE_TIMEOUT)
        data = json.loads(raw.decode("utf-8", "replace") or "{}")
    except Exception as exc:  # noqa: BLE001 - 探测失败不能阻断下载
        profile.error = str(exc)
        return profile

    streams = data.get("streams") or []
    if not streams:
        profile.error = "没有音频流"
        return profile

    stream = streams[0]
    fmt = data.get("format") or {}
    profile.codec = (stream.get("codec_name") or "").lower()
    profile.sample_rate = _to_int(stream.get("sample_rate"))
    profile.channels = _to_int(stream.get("channels"))
    # FLAC/ALAC 的位深在 bits_per_raw_sample，WAV 在 bits_per_sample
    profile.bits_per_sample = _to_int(
        stream.get("bits_per_raw_sample")
    ) or _to_int(stream.get("bits_per_sample"))
    profile.bit_rate = _to_int(stream.get("bit_rate")) or _to_int(
        fmt.get("bit_rate")
    )
    profile.duration = _to_float(fmt.get("duration"))
    return profile


def _to_int(value) -> int:
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return 0


def _to_float(value) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


# ---------------------------------------------------------------------------
# 2. 解码与频谱
# ---------------------------------------------------------------------------


def _decode_pcm(
    path: str, sample_rate: int, seconds: float, start: float, fmt: str
) -> bytes:
    """把指定位置的音频解码成裸 PCM 字节流。"""
    ffmpeg, _ = _resolve_tools()
    if ffmpeg is None:
        raise ToolMissing("系统未安装 ffmpeg")

    cmd = [ffmpeg, "-nostdin", "-v", "error"]
    if start > 0:
        # -ss 放在 -i 前面走快速定位，不用从头解码
        cmd += ["-ss", "%.3f" % start]
    cmd += ["-t", "%.3f" % seconds, "-i", path]
    cmd += ["-map", "0:a:0", "-ac", "1", "-ar", str(sample_rate)]
    cmd += ["-f", fmt, "-"]
    return _run(cmd, DECODE_TIMEOUT)


def _pick_start(duration: float, seconds: float) -> List[float]:
    """挑几个分析位置：中段优先，静音时回退到前段和后段。"""
    if duration <= seconds * 1.5:
        return [0.0]
    middle = duration / 2.0 - seconds / 2.0
    return [middle, duration * 0.15, duration * 0.8]


def _rms_from_float(samples) -> float:
    """numpy 数组的 RMS（浮点 PCM，范围 -1~1）。"""
    try:
        return float((samples * samples).mean() ** 0.5)
    except Exception:  # noqa: BLE001
        return 0.0


_NUMPY_STATE: Optional[bool] = None


def _numpy_available() -> bool:
    """缓存 numpy 是否可用，避免每首歌重复试导入。"""
    global _NUMPY_STATE
    if _NUMPY_STATE is None:
        try:
            import numpy  # noqa: F401
        except ImportError:
            _NUMPY_STATE = False
        else:
            _NUMPY_STATE = True
    return _NUMPY_STATE


def _spectrum_numpy(
    path: str, sample_rate: int, start: float, seconds: float
) -> Optional[Tuple[List[float], List[float]]]:
    """numpy 路径：STFT 平均功率谱。返回 (频点列表, 功率列表)。"""
    try:
        import numpy as np
    except ImportError:
        return None

    try:
        raw = _decode_pcm(path, sample_rate, seconds, start, "f32le")
    except Exception as exc:  # noqa: BLE001 - 解码失败由上层降级
        logger.warning("解码失败，跳过频谱分析: %s", exc)
        return None
    if not raw:
        return None

    samples = np.frombuffer(raw, dtype="<f4")
    frame = FRAME_SIZE_LOW if sample_rate <= 48000 else FRAME_SIZE_HIGH
    if samples.size < frame * 4:
        return None
    if _rms_from_float(samples) < 1e-4:
        return None  # 片段是静音，换位置重试

    total_frames = samples.size // frame
    if total_frames > MAX_FRAMES:
        # 均匀抽帧，长文件不增加耗时
        indexes = np.linspace(0, total_frames - 1, MAX_FRAMES).astype(int)
    else:
        indexes = np.arange(total_frames)

    window = np.hanning(frame)
    accumulated = np.zeros(frame // 2 + 1, dtype=np.float64)
    for index in indexes:
        chunk = samples[index * frame : (index + 1) * frame].astype(np.float64)
        magnitude = np.abs(np.fft.rfft(chunk * window))
        accumulated += magnitude * magnitude
    power = accumulated / len(indexes)
    freqs = np.fft.rfftfreq(frame, 1.0 / sample_rate)
    return freqs.tolist(), power.tolist()


def _rms_from_float(samples) -> float:
    """numpy 数组的 RMS（浮点 PCM，范围 -1~1）。"""
    try:
        return float((samples * samples).mean() ** 0.5)
    except Exception:  # noqa: BLE001
        return 0.0


def _spectrum_filterbank(
    path: str, sample_rate: int, start: float, seconds: float
) -> Optional[Tuple[List[float], List[float]]]:
    """无 numpy 的回落路径：多趟 highpass + astats 测各频段能量。

    精度比 STFT 粗，但足以区分「转码断崖」和「自然滚降」。
    """
    ffmpeg, _ = _resolve_tools()
    if ffmpeg is None:
        return None

    nyquist = sample_rate / 2.0
    low = 8000.0
    if nyquist <= low + 1000:
        return None

    step = max(250.0, (nyquist - low) / 40.0)
    points = []
    freq = low
    while freq < nyquist:
        points.append(freq)
        freq += step

    # 先测参考能量（1k-5k 的 RMS）
    reference = _measure_rms(ffmpeg, path, start, seconds, 1000.0, 5000.0)
    if reference is None:
        return None

    freqs: List[float] = []
    powers: List[float] = []
    for point in points:
        value = _measure_rms(ffmpeg, path, start, seconds, point, None)
        if value is None:
            continue
        # dB 差转回线性功率，与 STFT 路径保持同一量纲
        ratio_db = value - reference
        freqs.append(point)
        powers.append(10.0 ** (ratio_db / 10.0))
    if len(freqs) < 8:
        return None
    return freqs, powers


_RMS_RE = re.compile(r"RMS level dB:\s*(-?inf|-?[\d.]+)")


def _measure_rms(
    ffmpeg: str,
    path: str,
    start: float,
    seconds: float,
    highpass: float,
    lowpass: Optional[float],
) -> Optional[float]:
    """跑一趟 ffmpeg，返回滤波后音频的 RMS(dB)。"""
    # 两级 biquad 级联 ≈ 24dB/oct，比单级更接近理想带通
    filters = "highpass=f=%g,highpass=f=%g" % (highpass, highpass)
    if lowpass is not None:
        filters += ",lowpass=f=%g" % lowpass

    cmd = [ffmpeg, "-nostdin", "-hide_banner", "-v", "info"]
    if start > 0:
        cmd += ["-ss", "%.3f" % start]
    cmd += ["-t", "%.3f" % seconds, "-i", path]
    cmd += ["-map", "0:a:0", "-ac", "1"]
    cmd += ["-af", filters + ",astats=metadata=0:reset=0", "-f", "null", "-"]

    try:
        proc = subprocess.run(
            cmd,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            timeout=DECODE_TIMEOUT,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return None
    text = proc.stderr.decode("utf-8", "replace")
    matches = _RMS_RE.findall(text)
    if not matches:
        return None
    try:
        return float(matches[-1])
    except ValueError:  # "-inf"
        return None


def analyze_spectrum(path: str, profile: AudioProfile) -> Optional[SpectrumProfile]:
    """频谱分析主入口。优先 numpy STFT，缺 numpy 时回落 ffmpeg 滤波法。"""
    sample_rate = min(profile.sample_rate or 44100, MAX_SAMPLE_RATE)
    if sample_rate <= 0:
        return None

    seconds = ANALYZE_SECONDS
    if profile.duration and profile.duration < seconds:
        seconds = max(profile.duration, 3.0)

    starts = _pick_start(profile.duration, seconds)
    use_numpy = _numpy_available()

    for start in starts:
        if use_numpy:
            data = _spectrum_numpy(path, sample_rate, start, seconds)
        else:
            data = _spectrum_filterbank(path, sample_rate, start, seconds)
        if data is not None:
            return _summarize(data[0], data[1], sample_rate / 2.0)

    # numpy 路径全部失败（例如解码异常），再退回滤波法试一次
    if use_numpy and starts:
        data = _spectrum_filterbank(path, sample_rate, starts[0], seconds)
        if data is not None:
            return _summarize(data[0], data[1], sample_rate / 2.0)
    return None


def _summarize(
    freqs: List[float], power: List[float], nyquist: float
) -> SpectrumProfile:
    """把功率谱压缩成截止频率、最大落差、顶部是否静音三个指标。"""
    if not freqs or not power or len(freqs) != len(power):
        return SpectrumProfile(nyquist_hz=nyquist)

    db = [10.0 * math.log10(value + 1e-30) for value in power]
    reference = _band_mean_db(freqs, db, 1000.0, 5000.0)
    if reference is None:
        reference = max(db)
    relative = [value - reference for value in db]

    # 截止频率：能量还在 -60dB 以上的最高频点
    cutoff = 0.0
    for index in range(len(freqs) - 1, -1, -1):
        if relative[index] >= CUTOFF_DB:
            cutoff = freqs[index]
            break

    # 最大落差：300Hz 窗口内跌得最狠的位置。转码断崖会远超 35dB。
    cliff_drop = 0.0
    cliff_hz = 0.0
    window = 300.0
    tail = 0
    for index in range(len(freqs)):
        while tail < len(freqs) and freqs[tail] - freqs[index] < window:
            tail += 1
        if tail >= len(freqs):
            break
        drop = relative[index] - relative[tail]
        if drop > cliff_drop:
            cliff_drop = drop
            cliff_hz = freqs[tail]

    # 顶部 1kHz：真实录音总有量化噪声，精确的 -inf 就是人工静音
    top_relative = [
        value
        for freq, value in zip(freqs, relative)
        if freq >= max(nyquist - 1000.0, nyquist * 0.95)
    ]
    digital_silence = bool(top_relative) and max(top_relative) < DIGITAL_SILENCE_DB

    return SpectrumProfile(
        nyquist_hz=nyquist,
        cutoff_hz=cutoff,
        cliff_drop_db=cliff_drop,
        cliff_hz=cliff_hz,
        digital_silence_top=digital_silence,
        bins=len(freqs),
    )


def _band_mean_db(
    freqs: List[float], db: List[float], low: float, high: float
) -> Optional[float]:
    """指定频段内的平均 dB 值（功率域平均后再取对数更稳）。"""
    values = [
        value for freq, value in zip(freqs, db) if low <= freq <= high
    ]
    if not values:
        return None
    linear = sum(10.0 ** (value / 10.0) for value in values) / len(values)
    return 10.0 * math.log10(linear + 1e-30)


# ---------------------------------------------------------------------------
# 3. 位深层
# ---------------------------------------------------------------------------


def measure_effective_bits(path: str, profile: AudioProfile) -> Optional[int]:
    """反推有效位深，用来识别「16bit 左移填充成 24bit」的假 Hi-Res。

    ffmpeg 输出 s32le 时会把 16bit 左移 16 位、24bit 左移 8 位，
    所以把所有样本按位或起来、数末尾有几个零，就能还原真实位深。
    """
    if not profile.is_lossless_codec or profile.bits_per_sample <= 16:
        return None

    sample_rate = min(profile.sample_rate or 44100, MAX_SAMPLE_RATE)
    start = 0.0
    if profile.duration > BIT_DEPTH_SECONDS * 3:
        start = profile.duration / 2.0

    try:
        raw = _decode_pcm(
            path, sample_rate, BIT_DEPTH_SECONDS, start, "s32le"
        )
    except Exception:  # noqa: BLE001 - 位深检测失败不影响主判定
        logger.debug("位深解码失败", exc_info=True)
        return None
    return _effective_bits_from_s32(raw)


def _effective_bits_from_s32(raw: bytes, max_samples: int = 200000) -> Optional[int]:
    """从 s32le 字节流判断实际用的位深档位（16 / 24 / 32）。

    ffmpeg 输出 s32le 时会按源位深左移：16bit 左移 16 位、24bit 左移 8 位、
    32bit 不移动。所以直接看「低位是否全为零」就能反推，比数末尾零位可靠：
    样本本身的低位零不会干扰掩码判断。
    """
    if len(raw) < 4:
        return None
    usable = len(raw) // 4 * 4
    has_signal = 0
    low16 = 0
    low8 = 0
    for value in array.array("i", raw[:usable])[:max_samples]:
        magnitude = abs(value)
        has_signal |= magnitude
        low16 |= magnitude & 0xFFFF
        low8 |= magnitude & 0xFF
    if has_signal == 0:
        return None  # 整段是静音，判断不了位深
    if low16 == 0:
        return 16
    if low8 == 0:
        return 24
    return 32


# ---------------------------------------------------------------------------
# 4. 综合判定
# ---------------------------------------------------------------------------


def decide_verdict(
    profile: AudioProfile,
    spectrum: Optional[SpectrumProfile],
    effective_bits: Optional[int] = None,
) -> VerifyResult:
    """纯判定函数：不碰磁盘、不跑命令，方便单测覆盖各种组合。"""
    result = VerifyResult(path=profile.path, profile=profile, spectrum=spectrum)
    result.effective_bits = effective_bits

    if profile.error:
        result.verdict = VERDICT_UNREADABLE
        result.reasons.append(profile.error)
        return result

    if not profile.is_lossless_codec:
        result.verdict = VERDICT_LOSSY_SOURCE
        result.reasons.append(
            "编码是 %s，本身属于有损格式" % (profile.codec or "未知")
        )
        return result

    # 假 Hi-Res：位深声明与实际不符
    if (
        effective_bits is not None
        and profile.bits_per_sample >= 24
        and effective_bits <= profile.bits_per_sample - 8
    ):
        result.fake_bit_depth = True
        result.reasons.append(
            "声称 %dbit，实测只有 %dbit 有效精度" % (
                profile.bits_per_sample, effective_bits
            )
        )

    if spectrum is None:
        # 频谱没测出来（缺 ffmpeg/numpy），只能信容器层
        result.verdict = VERDICT_LOSSLESS
        result.reasons.append("频谱未分析，仅按编码格式判定")
        return result

    ratio = spectrum.cutoff_ratio
    has_cliff = spectrum.cliff_drop_db >= CLIFF_DB

    if spectrum.digital_silence_top:
        result.verdict = VERDICT_FAKE_LOSSLESS
        result.reasons.append("奈奎斯特附近是数字静音，真实录音不会这样")
        return result

    if has_cliff and ratio < CLIFF_CUTOFF_RATIO:
        result.verdict = VERDICT_FAKE_LOSSLESS
        result.reasons.append(
            "%.1fkHz 处断崖式下降 %.0fdB（截止频率仅 %.0f%% 奈奎斯特）"
            % (spectrum.cliff_hz / 1000.0, spectrum.cliff_drop_db, ratio * 100)
        )
        return result

    if ratio < SUSPICIOUS_CUTOFF_RATIO:
        result.verdict = VERDICT_SUSPICIOUS
        result.reasons.append(
            "高频截止在 %.1fkHz，低于无损常见的水平（可能是老录音或低码率升频）"
            % (spectrum.cutoff_hz / 1000.0)
        )
        return result

    result.verdict = VERDICT_LOSSLESS
    if has_cliff:
        result.reasons.append(
            "截止 %.1fkHz，虽有滚降但仍在奈奎斯特 %.0f%% 以上"
            % (spectrum.cutoff_hz / 1000.0, ratio * 100)
        )
    else:
        result.reasons.append(
            "高频连续延伸至 %.1fkHz，无转码断崖" % (spectrum.cutoff_hz / 1000.0)
        )
    return result


def verify(
    path: str,
    with_spectrum: bool = True,
    with_bit_depth: bool = True,
) -> VerifyResult:
    """校验一个音频文件。永远不会抛异常，失败收敛成 ``unreadable``。"""
    if not os.path.isfile(path):
        profile = AudioProfile(path=path, error="文件不存在：%s" % path)
        return decide_verdict(profile, None)

    profile = probe(path)
    if profile.error:
        return decide_verdict(profile, None)

    spectrum = None
    if with_spectrum:
        try:
            spectrum = analyze_spectrum(path, profile)
        except Exception:  # noqa: BLE001 - 频谱分析失败不能影响下载结果
            logger.warning("频谱分析失败: %s", path, exc_info=True)

    effective_bits = None
    if with_bit_depth:
        try:
            effective_bits = measure_effective_bits(path, profile)
        except Exception:  # noqa: BLE001
            logger.debug("位深检测失败: %s", path, exc_info=True)

    return decide_verdict(profile, spectrum, effective_bits)


# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------


def _fmt_bits(profile: AudioProfile) -> str:
    """位深/采样率摘要，例如 44.1kHz/16bit。"""
    rate = profile.sample_rate
    if not rate:
        return ""
    if rate % 1000 == 0:
        rate_text = "%dkHz" % (rate // 1000)
    else:
        rate_text = "%.1fkHz" % (rate / 1000.0)
    if profile.bits_per_sample:
        return "%s/%dbit" % (rate_text, profile.bits_per_sample)
    return rate_text


def format_report(result: VerifyResult) -> str:
    """生成单行中文报告，可直接展示给用户。"""
    profile = result.profile
    if profile is None:
        return "[%s] %s" % (result.label, result.path)

    parts = [result.label]
    detail = " ".join(
        item for item in (profile.codec.upper(), _fmt_bits(profile)) if item
    )
    if detail:
        parts.append(detail)
    if result.spectrum is not None and result.spectrum.cutoff_hz:
        parts.append("截止 %.1fkHz" % (result.spectrum.cutoff_hz / 1000.0))
    if result.reasons:
        parts.append(result.reasons[0])
    return " | ".join(parts)


__all__ = [
    "AudioProfile",
    "SpectrumProfile",
    "VerifyResult",
    "VERDICT_FAKE_LOSSLESS",
    "VERDICT_LOSSLESS",
    "VERDICT_LOSSY_SOURCE",
    "VERDICT_SUSPICIOUS",
    "VERDICT_UNREADABLE",
    "analyze_spectrum",
    "decide_verdict",
    "format_report",
    "has_tools",
    "is_lossless_codec",
    "measure_effective_bits",
    "probe",
    "verify",
]
