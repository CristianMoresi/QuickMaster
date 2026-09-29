"""Research-only M/S measurement; not application DSP or a genre-profile builder.

Python + NumPy + SciPy. WAV input stays untouched. JSON contains no audio.
Usage: python stereo_measurements.py --self-test
       python stereo_measurements.py --output result.json file1.wav file2.wav
"""

import argparse
import hashlib
import json
import math
from pathlib import Path

import numpy as np
import scipy
from scipy.io import wavfile


EDGES = (0, 120, 200, 250, 300, 2000, 10000, math.inf)
LABELS = ("0-120", "120-200", "200-250", "250-300", "300-2000", "2000-10000", "10000-Nyquist")


def db_ratio(side, mid):
    if mid <= 0:
        return None if side <= 0 else "+inf"
    if side <= 0:
        return "-inf"
    return float(10 * np.log10(side / mid))


def file_hash(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def spectral_power(frame, rate):
    """Hann-weighted power, exact one-sided Parseval scaling, disjoint bands."""
    size = len(frame)
    weights = np.hanning(size + 1)[:-1]
    spectrum = np.fft.rfft(frame * weights[:, None], axis=0)
    power = np.abs(spectrum) ** 2 / (size * np.sum(weights * weights))
    power[1:-1 if size % 2 == 0 else None] *= 2
    frequencies = np.fft.rfftfreq(size, 1 / rate)
    return np.array([
        power[(frequencies >= lo) & (frequencies < hi)].sum(axis=0)
        for lo, hi in zip(EDGES[:-1], EDGES[1:])
    ])


def self_test():
    rate = 48000
    t = np.arange(rate * 3) / rate
    mid = np.sin(2 * np.pi * 1000 * t)
    side = 0.5 * np.sin(2 * np.pi * 1100 * t)
    lr = np.column_stack((mid + side, mid - side))
    ms = np.column_stack(((lr[:, 0] + lr[:, 1]) / 2, (lr[:, 0] - lr[:, 1]) / 2))
    power = spectral_power(ms, rate)
    np.testing.assert_allclose(power.sum(axis=0), [0.5, 0.125], atol=1e-12)
    assert abs(db_ratio(power[:, 1].sum(), power[:, 0].sum()) + 6.02059991328) < 1e-9
    np.testing.assert_allclose(power[4], [0.5, 0.125], atol=1e-12)
    for n in (1000, 1001):
        rng = np.random.default_rng(n)
        probe = rng.normal(size=(n, 2))
        w = np.hanning(n + 1)[:-1]
        np.testing.assert_allclose(spectral_power(probe, rate).sum(axis=0),
                                   np.sum((probe * w[:, None]) ** 2, axis=0) / np.sum(w * w),
                                   rtol=1e-12)
    # Exact algebraic edge cases: no epsilon fabricates a finite stereo target.
    assert db_ratio(0, 1) == "-inf"
    assert db_ratio(1, 0) == "+inf"
    assert db_ratio(0, 0) is None
    left_only = np.column_stack((mid / 2, mid / 2))
    p = spectral_power(left_only, rate).sum(axis=0)
    assert db_ratio(p[1], p[0]) == 0
    # Global gain invariance, channel-swap invariance and energy reconstruction.
    np.testing.assert_allclose(spectral_power(ms * 0.2, rate), power * 0.04, atol=1e-12)
    np.testing.assert_allclose(spectral_power(ms * [1, -1], rate), power, atol=1e-12)
    np.testing.assert_allclose(np.mean(lr * lr), np.mean(mid * mid) + np.mean(side * side))
    # S/M alone cannot distinguish hard panning from diffuse independent channels.
    for freq in (80, 160, 225, 275, 1000, 5000, 15000):
        probe = np.column_stack((np.sin(2 * np.pi * freq * t), np.zeros_like(t)))
        p = spectral_power(probe, rate)
        expected = next(i for i, (lo, hi) in enumerate(zip(EDGES[:-1], EDGES[1:])) if lo <= freq < hi)
        assert int(np.argmax(p[:, 0])) == expected
        assert abs(p[expected, 0] - 0.5) < 1e-12
    return "PASS: Parseval even/odd, known -6.0206 dB, bands, nulls, one-channel, gain and swap invariance"


def analyze(path):
    before = file_hash(path)
    rate, source = wavfile.read(path)
    if source.ndim != 2 or source.shape[1] != 2:
        raise ValueError(f"Expected exactly two channels: {path.name}")
    scale = float(2 ** (source.dtype.itemsize * 8 - 1)) if np.issubdtype(source.dtype, np.signedinteger) else 1.0
    if source.dtype == np.uint8:
        raise ValueError("Unsigned PCM is not supported by this research probe")
    size = int(rate * 3)
    hop = size // 2
    powers = []
    total = np.zeros(2)
    lr_stats = np.zeros(3)
    count = len(source)
    # Full-file powers: all samples, rectangular weighting, no silence gate.
    for start in range(0, count, size):
        lr = source[start:start + size].astype(np.float64) / scale
        if not np.isfinite(lr).all():
            raise ValueError("Nonfinite audio")
        ms = np.column_stack(((lr[:, 0] + lr[:, 1]) / 2, (lr[:, 0] - lr[:, 1]) / 2))
        total += np.sum(ms * ms, axis=0)
        lr_stats += [np.sum(lr[:, 0] ** 2), np.sum(lr[:, 1] ** 2), np.sum(lr[:, 0] * lr[:, 1])]
    for start in range(0, count - size + 1, hop):
        lr = source[start:start + size].astype(np.float64) / scale
        ms = np.column_stack(((lr[:, 0] + lr[:, 1]) / 2, (lr[:, 0] - lr[:, 1]) / 2))
        powers.append(spectral_power(ms, rate))
    if not powers:
        raise ValueError("At least 3 seconds required by this research probe")
    powers = np.asarray(powers)
    summed = powers.sum(axis=(1, 2))
    # Research activity gate only, NOT a semantic chorus/verse detector.
    gate = max(1e-6, float(np.percentile(summed, 95)) / 10)
    active = powers[summed >= gate]
    if not len(active):
        raise ValueError("No active windows")
    band_sums = active.sum(axis=0)
    bands = {}
    for i, label in enumerate(LABELS):
        # Do not interpret nearly empty bands as a stable ratio.
        selected = active[(active[:, i, :].sum(axis=1) >= 1e-8)
                          & (active[:, i, 0] >= 1e-12)
                          & (active[:, i, 1] >= 1e-12), i, :]
        ratios = 10 * np.log10(selected[:, 1] / selected[:, 0]) if len(selected) else np.array([])
        bands[label] = {
            "summed_power_ratio_db": db_ratio(band_sums[i, 1], band_sums[i, 0]),
            "active_energy_percent": float(100 * band_sums[i].sum() / band_sums.sum()),
            "valid_windows": len(selected),
            "window_ratio_p10_p50_p90_db": np.percentile(ratios, [10, 50, 90]).tolist() if len(ratios) else None,
        }
    cutoffs = {}
    for cutoff in (200, 250, 300):
        index = EDGES.index(cutoff)
        below, above = band_sums[:index].sum(axis=0), band_sums[index:].sum(axis=0)
        cutoffs[str(cutoff)] = {
            "below_energy_percent": float(100 * below.sum() / band_sums.sum()),
            "below_side_mid_db": db_ratio(below[1], below[0]),
            "above_side_mid_db": db_ratio(above[1], above[0]),
        }
    denominator = np.sqrt(lr_stats[0] * lr_stats[1])
    after = file_hash(path)
    if before != after:
        raise RuntimeError("Source changed during analysis")
    return {
        "file": path.name, "sha256": before, "source_unchanged": before == after,
        "sample_rate": rate, "frames": count, "seconds": count / rate,
        "decoded_dtype": str(source.dtype),
        "full_file_side_mid_db": db_ratio(total[1], total[0]),
        "full_file_correlation_uncentered": float(lr_stats[2] / denominator) if denominator > 0 else None,
        "full_file_lr_balance_db": db_ratio(lr_stats[0], lr_stats[1]),
        "full_file_side_energy_percent": float(100 * total[1] / total.sum()) if total.sum() > 0 else None,
        "windows_total": len(powers), "windows_active": len(active),
        "activity_gate_dbfs_power": float(10 * np.log10(gate)),
        "active_window_energy_side_mid_db": db_ratio(band_sums[:, 1].sum(), band_sums[:, 0].sum()),
        "bands": bands, "cutoffs": cutoffs,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("files", nargs="*", type=Path)
    args = parser.parse_args()
    if args.output and args.output.resolve() in {p.resolve() for p in args.files}:
        raise ValueError("Output must not overwrite source audio")
    result = {"method_version": 1, "numpy": np.__version__, "scipy": scipy.__version__,
              "self_test": self_test(),
              "method": "M=(L+R)/2; S=(L-R)/2; 10log10(Ps/Pm); full-file exact powers; bands via 3s Hann FFT, 1.5s hop; active >= max(-60dBFS, P95-10dB); no resampling or DSP applied",
              "tracks": []}
    for path in args.files:
        measured = analyze(path)
        result["tracks"].append(measured)
        print(f"{path.name}: {measured['full_file_side_mid_db']:.3f} dB S/M", flush=True)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("w", encoding="utf-8") as stream:
            json.dump(result, stream, indent=2, allow_nan=False)
    else:
        print(json.dumps(result, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
