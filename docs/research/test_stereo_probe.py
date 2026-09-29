"""Mechanical oracles for research measurements; all fixtures are synthesized."""
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
from scipy.io import wavfile

import stereo_probe as probe


def signal(rate=8000, seconds=6.25):
    t = np.arange(round(rate * seconds)) / rate
    mid = .3 * np.sin(2 * np.pi * 1000 * t)
    side = .15 * np.sin(2 * np.pi * 1200 * t)
    return np.column_stack((mid + side, mid - side))


def measure(audio, chunk=8000, rate=8000):
    analyzer = probe.Analyzer(rate)
    for offset in range(0, len(audio), chunk):
        analyzer.add(audio[offset:offset + chunk])
    return analyzer.finish()


class AlgebraTests(unittest.TestCase):
    def test_known_ratio(self):
        result = measure(signal())
        self.assertAlmostEqual(result['full_file']['side_mid_db'], -6.020599913279624, places=10)
        self.assertAlmostEqual(result['full_file']['correlation_uncentered'], .6, places=10)

    def test_chunk_independence(self):
        a, b = measure(signal(), 137), measure(signal(), 11003)
        np.testing.assert_allclose(list(a['full_file'].values())[1:], list(b['full_file'].values())[1:], atol=1e-12)
        self.assertEqual(a['long_windows'], b['long_windows'])
        self.assertEqual(a['short_windows'], b['short_windows'])

    def test_gain_invariance(self):
        self.assertAlmostEqual(measure(signal())['full_file']['side_mid_db'],
                               measure(signal() * .001)['full_file']['side_mid_db'], places=10)

    def test_channel_swap(self):
        x = signal() * [1, .4]
        a, b = measure(x)['full_file'], measure(x[:, ::-1])['full_file']
        self.assertAlmostEqual(a['side_mid_db'], b['side_mid_db'], places=10)
        self.assertAlmostEqual(a['lr_balance_db'], -b['lr_balance_db'], places=10)

    def test_silence(self):
        result = measure(np.zeros((40000, 2)))
        self.assertEqual(result['full_file']['state'], 'silence')
        self.assertIsNone(result['full_file']['side_mid_db'])
        self.assertEqual(result['coarse_band_summary'], [])
        json.dumps(result, allow_nan=False)

    def test_dual_mono(self):
        x = np.tile(signal()[:, :1], (1, 2))
        result = measure(x)['full_file']
        self.assertEqual(result['state'], 'dual_mono')
        self.assertEqual(result['side_mid_db'], '-inf')
        self.assertEqual(result['correlation_uncentered'], 1)

    def test_antiphase(self):
        x = np.tile(signal()[:, :1], (1, 2)) * [1, -1]
        result = measure(x)['full_file']
        self.assertEqual(result['state'], 'antiphase')
        self.assertEqual(result['side_mid_db'], '+inf')
        self.assertEqual(result['correlation_uncentered'], -1)

    def test_missing_channel_is_not_diffuse_width(self):
        result = measure(signal() * [1, 0])['full_file']
        self.assertEqual(result['state'], 'right_absent')
        self.assertEqual(result['side_mid_db'], 0)
        self.assertIsNone(result['correlation_uncentered'])

    def test_nonfinite_rejected(self):
        for bad in (np.nan, np.inf, -np.inf):
            with self.assertRaises(ValueError):
                measure(np.array([[bad, 0.]]))

    def test_empty_rejected(self):
        with self.assertRaises(ValueError):
            measure(np.empty((0, 2)))

    def test_sub_three_second_input_and_tail(self):
        for n in (1, 3199, 3200, 24000, 24001, 50003):
            result = measure(np.ones((n, 2)) * .1)
            self.assertEqual(result['frames'], n)
            self.assertEqual(result['short_windows'][-1]['end_s'], n / 8000)
            self.assertEqual(result['long_windows'][-1]['end_s'], n / 8000)
            json.dumps(result, allow_nan=False)

    def test_quiet_section_is_retained(self):
        x = np.concatenate((signal(seconds=9) * .01, signal(seconds=9)))
        result = measure(x)
        gates = result['long_window_gate_sensitivity']
        self.assertGreater(gates['absolute_minus_80_dbfs']['selected_windows'], gates['p95_minus_10_db']['selected_windows'])
        self.assertAlmostEqual(result['long_windows'][0]['side_mid_db'], -6.020599913279624, places=10)

    def test_overs_are_not_clamped(self):
        x = signal() * 10
        result = measure(x)
        self.assertGreater(result['sample_peak_lr'][0], 1)
        self.assertGreater(result['samples_over_full_scale_lr'][0], 0)
        self.assertAlmostEqual(result['full_file']['side_mid_db'], -6.020599913279624, places=10)

    def test_dc_recorded(self):
        x = signal() + [.1, -.1]
        np.testing.assert_allclose(measure(x)['dc_lr'], [.1, -.1], atol=1e-12)


class SpectralTests(unittest.TestCase):
    def test_parseval_even_odd_and_nyquist(self):
        for n in (1000, 1001, 4096):
            x = np.random.default_rng(n).normal(size=(n, 2))
            w = np.hanning(n + 1)[:-1]
            expected = [np.sum((x[:, 0] * w) ** 2), np.sum((x[:, 1] * w) ** 2), np.sum(x[:, 0] * x[:, 1] * w ** 2)]
            result = probe.spectral_stats(x, 48000, probe.edges_for(48000)).sum(axis=0)
            np.testing.assert_allclose(result, np.array(expected) / np.sum(w ** 2), atol=1e-12)
        x = np.tile((-1.) ** np.arange(1000), (2, 1)).T
        self.assertAlmostEqual(probe.spectral_stats(x, 48000, probe.edges_for(48000)).sum(axis=0)[0], 1.)

    def test_low_cutoff_tones(self):
        rate = 48000
        edges = probe.edges_for(rate)
        t = np.arange(rate * 3) / rate
        for frequency, lo, hi in ((80, 0, 120), (160, 120, 200), (225, 200, 250), (275, 250, 300), (1000, 300, 2000)):
            x = np.column_stack((np.sin(2 * np.pi * frequency * t), np.zeros_like(t)))
            result = probe.spectral_stats(x, rate, edges)
            mask = (edges[:-1] >= lo) & (edges[1:] <= hi)
            self.assertAlmostEqual(result[mask, 0].sum(), .5, places=10)

    def test_band_summary_ratio(self):
        result = measure(signal())
        midband = next(b for b in result['coarse_band_summary'] if b['lo_hz'] == 300)
        self.assertAlmostEqual(midband['pooled']['side_mid_db'], -6.020599913279624, places=10)

    def test_edges_disjoint_and_end_at_nyquist(self):
        for rate in (8000, 44100, 48000, 96000, 192000):
            edges = probe.edges_for(rate)
            self.assertTrue(np.all(np.diff(edges) > 0))
            self.assertEqual(edges[0], 0)
            self.assertEqual(edges[-1], rate / 2)


class DecoderAndManifestTests(unittest.TestCase):
    def test_float_pcm_exact_no_playback_and_source_intact(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / 'fixture.wav'
            audio = signal() * 4
            wavfile.write(source, 8000, audio)
            before = probe.sha256(source)
            result = probe.analyze_file(source)
            self.assertEqual(probe.sha256(source), before)
            self.assertEqual(result['frames'], len(audio))
            self.assertEqual(result['sample_rate'], 8000)
            self.assertAlmostEqual(result['full_file']['side_mid_db'], measure(audio)['full_file']['side_mid_db'], places=12)
            self.assertGreater(result['sample_peak_lr'][0], 1)
            self.assertEqual(result['decode_quality'], 'clean')

    def test_zero_exit_code_with_decoder_error_is_quarantined(self):
        class ReportedError:
            def __init__(self, command, stdout, stderr, **kwargs):
                stderr.write(b'[mp3float] overread, invalid frame\n')
                self.stdout = io.BytesIO(signal(seconds=.5).astype('<f8').tobytes())

            def wait(self, **kwargs):
                return 0

            def poll(self):
                return 0

        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / 'fixture.wav'
            wavfile.write(source, 8000, signal(seconds=.5))
            # Probe/version calls are separate processes; mock only PCM decode.
            original = probe.subprocess.Popen

            def dispatch(command, **kwargs):
                return ReportedError(command, **kwargs) if 'pipe:1' in command else original(command, **kwargs)

            with patch.object(probe.subprocess, 'Popen', side_effect=dispatch):
                result = probe.analyze_file(source)
            self.assertEqual(result['decode_quality'], 'quarantined')
            self.assertIn('overread', result['decoder_issues'][0])

    def test_info_level_recovery_is_not_hidden_or_confused_with_metadata(self):
        log = ('Input #0, mp3, from /music/error.mp3:\n'
               '  title: invalid love\n'
               '[in#0/mp3 @ addr] Estimating duration from bitrate, this may be inaccurate\n'
               '[mp3float @ addr] overread, skip -5 enddists: -2 -2\n')
        self.assertEqual(probe.decode_issues(log), ['[mp3float @ addr] overread, skip -5 enddists: -2 -2'])

    def test_mono_and_multichannel_are_rejected_not_downmixed(self):
        with tempfile.TemporaryDirectory() as temp:
            for shape in ((8000,), (8000, 4)):
                source = Path(temp) / 'fixture.wav'
                wavfile.write(source, 8000, np.zeros(shape, dtype=np.float32))
                with self.assertRaises(ValueError):
                    probe.analyze_file(source)

    def test_manifest_rejects_duplicate_and_unreviewed_permission(self):
        entry = {'id': 'test', 'role': 'technical_pilot', 'eligible_for_profile': False,
                 'permission': 'CC-BY-4.0', 'artist': 'Test', 'source_page': 'fixture'}
        probe.validate_manifest({'tracks': [entry]})
        for key, value in (('id', '../escape'), ('role', 'professional_master'),
                           ('permission', 'unknown'), ('eligible_for_profile', True), ('artist', '')):
            bad = copy.deepcopy(entry)
            bad[key] = value
            with self.assertRaises(ValueError):
                probe.validate_manifest({'tracks': [bad]})
        with self.assertRaises(ValueError):
            probe.validate_manifest({'tracks': [entry, entry]})

    def test_result_cache_and_stale_result_protection(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, manifest = root / 'fixture.wav', root / 'manifest.json'
            wavfile.write(source, 8000, signal(seconds=.5))
            entry = {'id': 'test', 'role': 'technical_pilot', 'eligible_for_profile': False,
                     'permission': 'CC-BY-4.0', 'artist': 'Test', 'source_page': 'fixture', 'local_path': str(source)}
            manifest.write_text(json.dumps({'tracks': [entry]}), encoding='utf-8')
            probe.run_manifest(manifest, root / 'results')
            before = probe.sha256(root / 'results/test.json')
            probe.run_manifest(manifest, root / 'results')
            self.assertEqual(probe.sha256(root / 'results/test.json'), before)
            wavfile.write(source, 8000, signal(seconds=.5) * .5)
            with self.assertRaises(FileExistsError):
                probe.run_manifest(manifest, root / 'results')


if __name__ == '__main__':
    unittest.main(verbosity=2)
