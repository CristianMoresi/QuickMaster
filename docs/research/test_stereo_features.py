import unittest
import numpy as np
from stereo_probe import Analyzer
from stereo_features import expand

def features(mid,side,rate=8000):
    analyzer=Analyzer(rate)
    analyzer.add(np.column_stack((mid+side,mid-side)))
    return expand(analyzer.finish())

class FeatureTests(unittest.TestCase):
    def setUp(self):
        self.t=np.arange(8000*6)/8000
    def test_side_only_at_high_frequency(self):
        f=features(.2*np.sin(2*np.pi*100*self.t),.1*np.sin(2*np.pi*1000*self.t))
        self.assertLess(f['cutoffs'][3]['fraction_side_below'],1e-12)
        self.assertAlmostEqual(f['cutoffs'][3]['fraction_mid_below'],1,places=12)
        self.assertAlmostEqual(sum(x['fraction_of_total_side'] for x in f['bands']),1,places=12)
    def test_low_side_cutoff_comparison(self):
        f=features(.2*np.sin(2*np.pi*1000*self.t),.1*np.sin(2*np.pi*225*self.t))
        self.assertLess(f['cutoffs'][1]['fraction_side_below'],1e-12)
        self.assertAlmostEqual(f['cutoffs'][2]['fraction_side_below'],1,places=12)
    def test_dual_mono_keeps_missing_side_distinct(self):
        f=features(.2*np.sin(2*np.pi*100*self.t),np.zeros_like(self.t))
        self.assertTrue(all(x['fraction_of_total_side'] is None for x in f['bands']))
        self.assertEqual(f['pooled_spectral_side_mid_db'],'-inf')
    def test_silence_no_reference_observations(self):
        f=features(np.zeros_like(self.t),np.zeros_like(self.t))
        self.assertEqual(f['status'],'no_full_active_windows')
    def test_antiphase_explicit(self):
        f=features(np.zeros_like(self.t),.2*np.sin(2*np.pi*100*self.t))
        self.assertEqual(f['pooled_spectral_side_mid_db'],'+inf')
        self.assertEqual(f['negative_correlation_fraction'],1)
    def test_gain_invariance(self):
        m=.2*np.sin(2*np.pi*100*self.t);s=.1*np.sin(2*np.pi*1000*self.t)
        a,b=features(m,s),features(m*.01,s*.01)
        self.assertAlmostEqual(a['pooled_spectral_side_mid_db'],b['pooled_spectral_side_mid_db'],places=10)
        np.testing.assert_allclose([x['fraction_of_total_side'] for x in a['bands']],
                                   [x['fraction_of_total_side'] for x in b['bands']],atol=1e-12)
    def test_time_bins_not_musical_labels(self):
        f=features(.2*np.sin(2*np.pi*100*self.t),.1*np.sin(2*np.pi*1000*self.t))
        self.assertEqual(f['macro_time_bins_15s'][0]['end_s'],6)
        self.assertIn('semantic musical section labels',f['not_computed'])
    def test_short_track_no_full_window(self):
        f=features(np.ones(100),np.zeros(100))
        self.assertEqual(f['spectral_windows'],0)

if __name__=='__main__':unittest.main()
