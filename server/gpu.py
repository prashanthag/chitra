"""Hardware-acceleration detection for ffmpeg video work and torch inference.

Probes the local ffmpeg once and picks the best available H.264 backend:

    NVIDIA NVENC  >  Intel Quick Sync (QSV)  >  Apple VideoToolbox
                  >  AMD AMF  >  VAAPI (Linux)  >  CPU (libx264)

Override detection with the GPU_BACKEND env var
(auto | nvidia | qsv | videotoolbox | amf | vaapi | cpu).

For torch (CLIP / face models) it picks CUDA, then Apple MPS, then CPU.
"""

from __future__ import annotations

import functools
import os
import subprocess

NVIDIA = "nvidia"
QSV = "qsv"
VIDEOTOOLBOX = "videotoolbox"
AMF = "amf"
VAAPI = "vaapi"
CPU = "cpu"

# H.264 encoder name + the ffmpeg -hwaccel flag each backend needs for decode.
_BACKENDS = {
    NVIDIA: {"encoder": "h264_nvenc", "hwaccel": "cuda"},
    QSV: {"encoder": "h264_qsv", "hwaccel": "qsv"},
    VIDEOTOOLBOX: {"encoder": "h264_videotoolbox", "hwaccel": "videotoolbox"},
    AMF: {"encoder": "h264_amf", "hwaccel": None},
    VAAPI: {"encoder": "h264_vaapi", "hwaccel": "vaapi"},
    CPU: {"encoder": "libx264", "hwaccel": None},
}

# Detection order: fastest / least fiddly first. VAAPI is last HW option since it
# needs a render node and explicit frame upload.
_PRIORITY = [NVIDIA, QSV, VIDEOTOOLBOX, AMF, VAAPI]


def _run(cmd: list[str]) -> str:
    try:
        return subprocess.run(
            cmd, capture_output=True, text=True, timeout=10
        ).stdout
    except Exception:
        return ""


@functools.lru_cache(maxsize=1)
def _probe() -> tuple[str, str]:
    """Return (encoders listing, hwaccels listing) from the local ffmpeg."""
    return (
        _run(["ffmpeg", "-hide_banner", "-encoders"]),
        _run(["ffmpeg", "-hide_banner", "-hwaccels"]),
    )


@functools.lru_cache(maxsize=1)
def detect_backend() -> str:
    """Pick the video backend, honoring GPU_BACKEND (and legacy USE_CUDA=0)."""
    forced = os.environ.get("GPU_BACKEND", "auto").strip().lower()
    if forced and forced != "auto":
        return forced if forced in _BACKENDS else CPU
    # Legacy escape hatch: USE_CUDA=0 means "no GPU".
    if os.environ.get("USE_CUDA", "1") == "0":
        return CPU
    encoders, hwaccels = _probe()
    for backend in _PRIORITY:
        spec = _BACKENDS[backend]
        if spec["encoder"] in encoders and (
            spec["hwaccel"] is None or spec["hwaccel"] in hwaccels
        ) and _works(backend):
            return backend
    return CPU


def _works(backend: str) -> bool:
    """Encode a few synthetic frames to prove the backend really works.

    A listed encoder can still fail at runtime — e.g. NVENC reports
    "unsupported device" after a driver update until the machine reboots —
    which would otherwise make every transcode silently emit zero bytes.
    """
    _, out_args = transcode_args(backend, "1M", "2M", "2M")
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error"]
    if backend == VAAPI:
        cmd += ["-vaapi_device",
                os.environ.get("VAAPI_DEVICE", "/dev/dri/renderD128")]
    cmd += ["-f", "lavfi", "-i", "color=black:size=256x144:rate=30:duration=0.2",
            *out_args, "-an", "-f", "null", "-"]
    try:
        return subprocess.run(cmd, capture_output=True, timeout=15).returncode == 0
    except Exception:
        return False


def describe(backend: str) -> str:
    return {
        NVIDIA: "NVIDIA NVENC (CUDA)",
        QSV: "Intel Quick Sync (QSV)",
        VIDEOTOOLBOX: "Apple VideoToolbox",
        AMF: "AMD AMF",
        VAAPI: "VAAPI",
        CPU: "CPU (libx264)",
    }.get(backend, backend)


# ---------- ffmpeg command fragments ----------


def thumb_decode_args(backend: str) -> list[str]:
    """Input-side ffmpeg args to hardware-decode a frame for a thumbnail."""
    if backend == NVIDIA:
        return ["-hwaccel", "cuda", "-hwaccel_output_format", "cuda"]
    hwaccel = _BACKENDS[backend]["hwaccel"]
    # For QSV/VAAPI/VideoToolbox, plain -hwaccel decodes then auto-downloads to
    # system memory, so a normal CPU scale filter works on the result.
    if hwaccel and backend != VAAPI:
        return ["-hwaccel", hwaccel]
    return []


def thumb_scale_vf(backend: str, size: int) -> str:
    """Scale filter for thumbnail extraction."""
    if backend == NVIDIA:
        return f"scale_cuda='min({size},iw)':-2,hwdownload,format=nv12"
    return f"scale='min({size},iw)':-2"


def transcode_args(
    backend: str,
    bitrate: str = "4M",
    maxrate: str = "6M",
    bufsize: str = "8M",
) -> tuple[list[str], list[str]]:
    """Return (input_args, output_args) for an H.264 transcode on `backend`."""
    rate = ["-b:v", bitrate, "-maxrate", maxrate, "-bufsize", bufsize]
    if backend == NVIDIA:
        return (
            ["-hwaccel", "cuda"],
            ["-c:v", "h264_nvenc", "-preset", "p3", "-tune", "hq", *rate,
             "-pix_fmt", "yuv420p"],
        )
    if backend == QSV:
        return (
            ["-hwaccel", "qsv"],
            ["-c:v", "h264_qsv", "-preset", "medium", *rate, "-pix_fmt", "nv12"],
        )
    if backend == VIDEOTOOLBOX:
        return (
            ["-hwaccel", "videotoolbox"],
            ["-c:v", "h264_videotoolbox", *rate, "-pix_fmt", "yuv420p"],
        )
    if backend == AMF:
        return (
            [],
            ["-c:v", "h264_amf", "-quality", "balanced", *rate,
             "-pix_fmt", "yuv420p"],
        )
    if backend == VAAPI:
        dev = os.environ.get("VAAPI_DEVICE", "/dev/dri/renderD128")
        return (
            ["-hwaccel", "vaapi", "-hwaccel_device", dev,
             "-hwaccel_output_format", "vaapi"],
            ["-vf", "format=nv12|vaapi,hwupload", "-c:v", "h264_vaapi", *rate],
        )
    return (
        [],
        ["-c:v", "libx264", "-preset", "veryfast", *rate, "-pix_fmt", "yuv420p"],
    )


def torch_device(torch) -> str:
    """Best torch device: CUDA, then Apple MPS, then CPU."""
    try:
        if torch.cuda.is_available():
            return "cuda"
        mps = getattr(torch.backends, "mps", None)
        if mps is not None and mps.is_available():
            return "mps"
    except Exception:
        pass
    return "cpu"
