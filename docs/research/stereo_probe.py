"""Silent, bounded-memory stereo research probe. No playback or audio-device APIs.

Local files only; FFmpeg decodes PCM to a pipe, never to a sound device. This is
measurement tooling, not application DSP and not an estimator of ideal width.
Run test_stereo_probe.py before using corpus measurements.
"""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import tempfile
import time

import numpy as np

VERSION = 4
CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
COARSE = (0, 120, 200, 250, 300, 2000, 10000, math.inf)


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def decode_issues(log):
    # FFmpeg's MP3 overread recovery is logged at INFO, not WARNING/ERROR.
    # Do not classify filenames or metadata as decoder diagnostics.
    return [line for line in log.splitlines() if line.startswith('[')
            and re.search(r'overread|underread|invalid|corrupt|error|failed|truncat|mismatch|header missing', line, re.I)]


def ratio_db(numerator, denominator):
    # Preserve singular states instead of inventing finite targets with epsilon.
    if numerator < 0 or denominator < 0:
        raise ValueError("Power cannot be negative")
    if denominator == 0:
        return None if numerator == 0 else "+inf"
    return "-inf" if numerator == 0 else float(10 * math.log10(numerator / denominator))


def descriptors(stats):
    """stats = [sum L², sum R², sum LR]; uncentered (DC-inclusive) values."""
    left, right, cross = map(float, stats)
    mid = max(0., (left + right + 2 * cross) / 4)
    side = max(0., (left + right - 2 * cross) / 4)
    denom = math.sqrt(left * right)
    state = ("silence" if left + right == 0 else "left_absent" if left == 0
             else "right_absent" if right == 0 else "dual_mono" if side == 0
             else "antiphase" if mid == 0 else "stereo")
    return {"state": state, "side_mid_db": ratio_db(side, mid),
            "lr_balance_db": ratio_db(left, right),
            "correlation_uncentered": float(np.clip(cross / denom, -1, 1)) if denom else None,
            "mid_power": mid, "side_power": side}


def edges_for(rate):
    # Exact base-2 third-octave boundaries; NOT IEC class-compliant filters.
    third = 1000 * 2. ** ((np.arange(-19, 15) - .5) / 3)
    nyquist = rate / 2
    return np.unique([0., *third[(third > 0) & (third < nyquist)],
                      *[x for x in COARSE[1:-1] if x < nyquist], nyquist])


def spectral_stats(lr, rate, edges):
    """Disjoint FFT-bin bands. Sum equals Hann-weighted time-domain power."""
    n = len(lr)
    if n < 2:
        raise ValueError("At least two frames required")
    weights = np.hanning(n + 1)[:-1]
    transform = np.fft.rfft(lr * weights[:, None], axis=0)
    scale = np.full(len(transform), 2. / (n * np.sum(weights ** 2)))
    scale[0] /= 2
    if n % 2 == 0:
        scale[-1] /= 2
    columns = np.column_stack((np.abs(transform[:, 0]) ** 2,
                               np.abs(transform[:, 1]) ** 2,
                               (transform[:, 0] * transform[:, 1].conj()).real)) * scale[:, None]
    frequencies = np.fft.rfftfreq(n, 1 / rate)
    indexes = np.minimum(np.searchsorted(edges, frequencies, side="right") - 1, len(edges) - 2)
    result = np.zeros((len(edges) - 1, 3))
    np.add.at(result, indexes, columns)
    return result


class WindowStream:
    """Chunk-independent windows; retain a final partial only if it adds new samples."""
    def __init__(self, rate, seconds, hop, spectral=False):
        self.rate = rate
        self.size = round(rate * seconds)
        self.hop = round(rate * hop)
        self.edges = edges_for(rate) if spectral else None
        self.buffer = np.empty((0, 2))
        self.offset = 0
        self.last_end = 0
        self.records = []

    def record(self, frame):
        stats = np.array([np.sum(frame[:, 0] ** 2), np.sum(frame[:, 1] ** 2),
                          np.sum(frame[:, 0] * frame[:, 1])]) / len(frame)
        result = {"start_s": self.offset / self.rate, "end_s": (self.offset + len(frame)) / self.rate,
                  "partial": len(frame) < self.size, **descriptors(stats)}
        if self.edges is not None and len(frame) > 1:
            result["spectral_lr_cross_powers"] = spectral_stats(frame, self.rate, self.edges).tolist()
        self.records.append(result)
        self.last_end = self.offset + len(frame)

    def add(self, frame):
        self.buffer = np.concatenate((self.buffer, frame))
        while len(self.buffer) >= self.size:
            self.record(self.buffer[:self.size])
            self.buffer = self.buffer[self.hop:].copy()
            self.offset += self.hop

    def finish(self):
        if len(self.buffer) and self.offset + len(self.buffer) > self.last_end:
            self.record(self.buffer)
        self.buffer = np.empty((0, 2))
        return self.records


def gate_summary(records, relative_db=None, floor_db=-80.):
    energies = np.array([r["mid_power"] + r["side_power"] for r in records])
    floor = 10 ** (floor_db / 10)
    threshold = floor if relative_db is None else max(floor, float(np.percentile(energies, 95)) * 10 ** (-relative_db / 10))
    selected = [r for r, energy in zip(records, energies) if energy >= threshold]
    ratios = [r["side_mid_db"] for r in selected if isinstance(r["side_mid_db"], (int, float))]
    return {"threshold_dbfs_power": ratio_db(threshold, 1), "selected_windows": len(selected),
            "states": dict(Counter(r["state"] for r in selected)),
            "finite_ratio_windows": len(ratios),
            "finite_ratio_p10_p50_p90_db": np.percentile(ratios, [10, 50, 90]).tolist() if ratios else None}


def band_summary(records, edges):
    # Equal full-window weighting. Partial tails remain in timeline but are not
    # given full weight in this overlapping-window summary. No chorus-only gate.
    usable = [r for r in records if not r["partial"] and r["mid_power"] + r["side_power"] >= 1e-8
              and "spectral_lr_cross_powers" in r]
    if not usable:
        return []
    arrays = np.array([r["spectral_lr_cross_powers"] for r in usable])
    coarse = [x for x in COARSE if x < edges[-1]] + [edges[-1]]
    results = []
    for lo, hi in zip(coarse[:-1], coarse[1:]):
        indexes = (edges[:-1] >= lo) & (edges[1:] <= hi)
        values = arrays[:, indexes, :].sum(axis=1)
        # Near-empty bands are not reference observations, but never erase them
        # from the timeline. -100 dBFS and 60 dB below each window are QC gates.
        total = np.array([r["mid_power"] + r["side_power"] for r in usable])
        valid = ((values[:, 0] + values[:, 1]) / 2 >= np.maximum(1e-10, total * 1e-6))
        selected = values[valid]
        desc = [descriptors(v) for v in selected]
        ratios = [d["side_mid_db"] for d in desc if isinstance(d["side_mid_db"], (float, int))]
        results.append({"lo_hz": lo, "hi_hz": hi, "valid_windows": len(selected),
                        "states": dict(Counter(d["state"] for d in desc)),
                        "pooled": descriptors(selected.mean(axis=0)) if len(selected) else None,
                        "finite_ratio_p10_p50_p90_db": np.percentile(ratios, [10, 50, 90]).tolist() if ratios else None})
    return results


class Analyzer:
    def __init__(self, rate):
        self.rate = rate
        self.count = 0
        self.stats = np.zeros(3)
        self.dc = np.zeros(2)
        self.ms_sums = np.zeros(2)
        self.peak = np.zeros(2)
        self.over = np.zeros(2, dtype=np.int64)
        self.short = WindowStream(rate, .4, .2)
        self.long = WindowStream(rate, 3., 1.5, spectral=True)

    def add(self, frame):
        if frame.ndim != 2 or frame.shape[1] != 2 or not np.isfinite(frame).all():
            raise ValueError("Expected finite, stereo PCM")
        if not len(frame):
            return
        self.count += len(frame)
        self.stats += [np.sum(frame[:, 0] ** 2), np.sum(frame[:, 1] ** 2), np.sum(frame[:, 0] * frame[:, 1])]
        self.dc += frame.sum(axis=0)
        # Direct M/S avoids subtractive cancellation in very narrow material.
        self.ms_sums += [np.sum(((frame[:, 0] + frame[:, 1]) / 2) ** 2),
                         np.sum(((frame[:, 0] - frame[:, 1]) / 2) ** 2)]
        self.peak = np.maximum(self.peak, np.abs(frame).max(axis=0))
        self.over += (np.abs(frame) > 1).sum(axis=0)
        self.short.add(frame)
        self.long.add(frame)

    def finish(self):
        if not self.count:
            raise ValueError("Empty decoded file")
        short, long = self.short.finish(), self.long.finish()
        full = descriptors(self.stats / self.count)
        full.update(mid_power=float(self.ms_sums[0] / self.count), side_power=float(self.ms_sums[1] / self.count),
                    side_mid_db=ratio_db(self.ms_sums[1], self.ms_sums[0]))
        return {"sample_rate": self.rate, "frames": self.count, "duration_s": self.count / self.rate,
                "full_file": full, "dc_lr": (self.dc / self.count).tolist(), "sample_peak_lr": self.peak.tolist(),
                "samples_over_full_scale_lr": self.over.tolist(),
                "long_window_gate_sensitivity": {"absolute_minus_80_dbfs": gate_summary(long),
                    **{f"p95_minus_{drop}_db": gate_summary(long, drop) for drop in (10, 20, 40)}},
                "band_edges_hz": self.long.edges.tolist(), "coarse_band_summary": band_summary(long, self.long.edges),
                "short_windows": short, "long_windows": long}


def probe_file(path):
    path = Path(path).resolve(strict=True)
    if not path.is_file():
        raise ValueError("Local regular file required")
    result = subprocess.run(["ffprobe", "-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", "a", "-show_streams", "-of", "json", str(path)],
                            capture_output=True, check=True, creationflags=CREATE_NO_WINDOW, timeout=60)
    streams = json.loads(result.stdout)["streams"]
    if len(streams) != 1 or streams[0].get("channels") != 2:
        raise ValueError("Exactly one two-channel audio stream required; no automatic downmix")
    info = streams[0]
    rate = int(info["sample_rate"])
    if not 8000 <= rate <= 384000:
        raise ValueError("Unsupported sample rate")
    return path, rate, {k: info.get(k) for k in ("codec_name", "sample_fmt", "sample_rate", "channels", "channel_layout", "bit_rate")}


def analyze_file(path):
    path, rate, info = probe_file(path)
    before = sha256(path)
    started = time.perf_counter()
    analyzer = Analyzer(rate)
    command = ["ffmpeg", "-v", "info", "-hide_banner", "-nostats", "-nostdin", "-protocol_whitelist", "file,pipe", "-threads", "1", "-i", str(path), "-map", "0:a:0",
               "-vn", "-sn", "-dn", "-threads", "1", "-c:a", "pcm_f64le", "-f", "f64le", "pipe:1"]
    # No -ar, -ac, gain, normalization, device output, or lossy intermediate.
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=errors, creationflags=CREATE_NO_WINDOW)
        try:
            while True:
                raw = process.stdout.read(rate * 16)  # one second; two float64 channels
                if not raw:
                    break
                if len(raw) % 16:
                    raise ValueError("Truncated PCM frame")
                analyzer.add(np.frombuffer(raw, dtype="<f8").reshape(-1, 2))
            code = process.wait(timeout=60)
            errors.seek(0)
            diagnostics = errors.read(65537).decode(errors='replace').strip()
            issues = decode_issues(diagnostics)
            if len(diagnostics) > 65536:
                issues.append('Diagnostic log exceeds bounded capture; manual review required')
            if code:
                raise RuntimeError(f"Decoder failed ({code}): {diagnostics[:4096]}")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            process.stdout.close()
    after = sha256(path)
    if before != after:
        raise RuntimeError("Source file changed during measurement")
    measured = analyzer.finish()
    return {"method_version": VERSION, "analyzer_sha256": sha256(__file__), "source_sha256": before,
            "source_unchanged": True, "source_name": path.name, "stream": info,
            "decode_quality": "quarantined" if issues else "clean",
            "decoder_issues": issues,
            "decoder_log": diagnostics,
            "ffmpeg_version": subprocess.check_output(["ffmpeg", "-version"], creationflags=CREATE_NO_WINDOW).decode().splitlines()[0],
            "numpy_version": np.__version__, "measured_utc": datetime.now(timezone.utc).isoformat(),
            "elapsed_seconds": time.perf_counter() - started, **measured}


def validate_manifest(manifest):
    ids = set()
    paths = set()
    for entry in manifest["tracks"]:
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", entry["id"]) or entry["id"] in ids:
            raise ValueError("Unsafe or duplicate track id")
        ids.add(entry["id"])
        if entry.get("role") != "technical_pilot" or entry.get("eligible_for_profile") is not False:
            raise ValueError("This pilot runner must not silently create calibration data")
        if entry.get("permission") not in ("CC-BY-4.0", "CC-BY-3.0", "user_owned_authorized"):
            raise ValueError("Permission requires review before measurement")
        if not entry.get("artist") or not entry.get("source_page"):
            raise ValueError("Missing provenance")
        local = entry.get("local_path")
        if local:
            key = str(Path(local).resolve()).casefold()
            if key in paths:
                raise ValueError("Duplicate source path")
            paths.add(key)


def run_manifest(manifest_path, output_dir):
    manifest_path = Path(manifest_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    seen = set()
    for entry in manifest["tracks"]:
        if not entry.get("local_path"):
            continue
        source = Path(entry["local_path"]).resolve(strict=True)
        out = output_dir / f"{entry['id']}.json"
        if out.resolve() == source or out.resolve() == manifest_path.resolve():
            raise ValueError("Output cannot replace an input")
        digest = sha256(source)
        provenance_hash = hashlib.sha256(json.dumps(entry, sort_keys=True).encode()).hexdigest()
        if digest in seen:
            raise ValueError("Duplicate audio hash: not an independent track")
        seen.add(digest)
        if out.exists():
            cached = json.loads(out.read_text(encoding="utf-8"))
            if (cached.get("source_sha256") == digest and cached.get("analyzer_sha256") == sha256(__file__)
                    and cached.get("provenance_sha256") == provenance_hash):
                print(f"CACHED {entry['id']}", flush=True)
                continue
            raise FileExistsError(f"Stale result: use a new output directory, preserving {out}")
        result = {"provenance": entry, "provenance_sha256": provenance_hash, **analyze_file(source)}
        temporary = out.with_suffix(".json.part")
        with temporary.open("x", encoding="utf-8") as stream:
            json.dump(result, stream, allow_nan=False, separators=(",", ":"))
        temporary.rename(out)
        print(f"{result['decode_quality'].upper()} {entry['id']}: {result['full_file']['side_mid_db']} dB S/M; {result['elapsed_seconds']:.1f}s", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    run_manifest(args.manifest, args.output_dir)
