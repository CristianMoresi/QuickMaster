import copy
import json
import unittest
import numpy as np
from stereo_probe import Analyzer
from tonal_features import expand_tonal


def measurement(mid,side,rate=8000):
    analyzer=Analyzer(rate)
    analyzer.add(np.column_stack((mid+side,mid-side)))
    return analyzer.finish()


class TonalFeaturesTests(unittest.TestCase):
    def setUp(self):
        self.t=np.arange(8000*6)/8000
        self.m=.2*np.sin(2*np.pi*100*self.t)
        self.s=.1*np.sin(2*np.pi*1000*self.t)

    def test_power_distribution_and_component_separation(self):
        f=expand_tonal(measurement(self.m,self.s))
        b=f['broad_bands']
        self.assertAlmostEqual(b[0]['pooled_fraction_by_component']['stereo'],.8,places=12)
        self.assertAlmostEqual(b[2]['pooled_fraction_by_component']['stereo'],.2,places=12)
        self.assertAlmostEqual(b[0]['pooled_fraction_by_component']['mid'],1,places=12)
        self.assertAlmostEqual(b[2]['pooled_fraction_by_component']['side'],1,places=12)
        for component in ('stereo','mid','side','left','right'):
            self.assertAlmostEqual(sum(v['pooled_fraction_by_component'][component] for v in f['bands']),1,places=12)
        self.assertAlmostEqual(sum(v['equal_time_mean_fraction'] for v in f['bands']),1,places=12)
        self.assertAlmostEqual(sum(v['equal_time_mean_fraction'] for v in b),1,places=12)

    def test_normalization_and_dynamic_range_are_gain_invariant(self):
        a=expand_tonal(measurement(self.m,self.s))
        b=expand_tonal(measurement(self.m*.01,self.s*.01))
        np.testing.assert_allclose([v['equal_time_mean_fraction'] for v in a['bands']],
                                   [v['equal_time_mean_fraction'] for v in b['bands']],atol=1e-12)
        for key in ('rms_400ms_p90_minus_p10_db','sample_crest_db_peak_channel_over_mean_stereo_power'):
            self.assertAlmostEqual(a['dynamics'][key],b['dynamics'][key],places=10)
        self.assertAlmostEqual(a['dynamics']['rms_400ms_p10_p50_p90_dbfs'][1]-b['dynamics']['rms_400ms_p10_p50_p90_dbfs'][1],40,places=10)

    def test_known_twenty_db_macro_range(self):
        mid=np.concatenate((self.m*.1,self.m))
        side=np.concatenate((self.s*.1,self.s))
        f=expand_tonal(measurement(mid,side))
        self.assertAlmostEqual(f['dynamics']['rms_400ms_p90_minus_p10_db'],20.,places=10)

    def test_silence_is_not_an_eq_reference(self):
        z=np.zeros_like(self.t)
        self.assertEqual(expand_tonal(measurement(z,z))['status'],'no_full_active_windows')

    def test_null_components_not_fake_spectra(self):
        z=np.zeros_like(self.t)
        for mid,side,null in ((self.m,z,'side'),(z,self.m,'mid')):
            f=expand_tonal(measurement(mid,side))
            self.assertTrue(all(b['pooled_fraction_by_component'][null] is None for b in f['bands']))
            self.assertTrue(all(b['reliable_window_fraction_by_component'][null]==0 for b in f['bands']))
            json.dumps(f,allow_nan=False)

    def test_spectral_leakage_not_confident_reference(self):
        f=expand_tonal(measurement(self.m,self.s))
        quiet=next(b for b in f['bands'] if b['lo_hz']>=2000)
        self.assertEqual(quiet['reliable_window_fraction_by_component']['stereo'],0)
        low=next(b for b in f['bands'] if b['lo_hz']<=100<b['hi_hz'])
        self.assertEqual(low['reliable_window_fraction_by_component']['mid'],1)

    def test_short_input_has_no_full_observation(self):
        f=expand_tonal(measurement(self.m[:100],self.s[:100]))
        self.assertEqual(f['windows'],0)

    def test_bad_covariance_rejected(self):
        r=measurement(self.m,self.s)
        r['long_windows'][0]['spectral_lr_cross_powers'][0]=[1.,1.,2.]
        with self.assertRaises(ValueError):expand_tonal(r)

    def test_missing_exact_broadband_edge_not_interpolated(self):
        r=measurement(self.m,self.s)
        r['band_edges_hz'][list(r['band_edges_hz']).index(120.)]=119.
        with self.assertRaises(ValueError):expand_tonal(r)

    def test_input_not_modified_and_no_semantic_claims(self):
        r=measurement(self.m,self.s);before=copy.deepcopy(r)
        f=expand_tonal(r)
        self.assertEqual(r,before)
        self.assertIn('semantic sections',f['not_computed'])
        self.assertIn('BS.1770 integrated LUFS',f['not_computed'])
        self.assertEqual(f['macro_time_bins_15s'][0]['end_s'],6.)

    def test_equal_time_shape_does_not_favor_loud_section(self):
        # Independent synthetic FFT windows with exact legal covariance.
        r=measurement(self.m,self.s)
        w=r['long_windows'][0]
        low=next(i for i,x in enumerate(r['band_edges_hz'][1:]) if x>=120)
        high=next(i for i,x in enumerate(r['band_edges_hz'][1:]) if x>=1000)
        rows=[]
        for index,amplitude,band in ((0,1.,low),(1,.01,high)):
            row=copy.deepcopy(w);matrix=np.zeros_like(row['spectral_lr_cross_powers'])
            matrix[band]=[amplitude,amplitude,0]
            row.update(start_s=1.5*index,end_s=1.5*index+3,mid_power=amplitude/2,
                       side_power=amplitude/2,spectral_lr_cross_powers=matrix.tolist())
            rows.append(row)
        r['long_windows']=rows
        f=expand_tonal(r)
        self.assertAlmostEqual(f['bands'][high]['equal_time_mean_fraction'],.5)
        self.assertAlmostEqual(f['bands'][high]['pooled_fraction_by_component']['stereo'],.01/1.01)


if __name__=='__main__':unittest.main()
