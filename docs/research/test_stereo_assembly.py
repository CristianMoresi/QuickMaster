import copy
import unittest
from assemble_stereo_study import quantiles,group_summary


class AssemblyTests(unittest.TestCase):
    def setUp(self):
        self.row={'accepted':True,'stereo':{'pooled_spectral_side_mid_db':-10.,
             'cutoffs':[{'cutoff_hz':c,'fraction_side_below':.1} for c in (120,200,250,300)]},
             'tonal':{'band_edges_hz':[0,120,24000],
                 'bands':[{'equal_time_mean_fraction':.2},{'equal_time_mean_fraction':.8}],
                 'dynamics':{'rms_400ms_p90_minus_p10_db':3.,'sample_crest_db_peak_channel_over_mean_stereo_power':8.}}}
    def test_each_recording_one_observation(self):
        a=copy.deepcopy(self.row);b=copy.deepcopy(self.row)
        b['stereo']['pooled_spectral_side_mid_db']=-2.
        a['captured_duration_s']=600.;b['captured_duration_s']=100.
        a['windows']=1000;b['windows']=3
        result=group_summary([a,b])
        self.assertEqual(result['side_mid_db']['mean'],-6.)
        self.assertEqual(result['side_mid_db']['n'],2)
    def test_partial_groups_never_complete(self):
        rows=[copy.deepcopy(self.row) for _ in range(5)];rows[0]['accepted']=False
        r=group_summary(rows)
        self.assertEqual(r['accepted'],4);self.assertFalse(r['coverage_complete'])
    def test_complete_coverage_not_perceptual_optimum(self):
        r=group_summary([copy.deepcopy(self.row) for _ in range(5)])
        self.assertTrue(r['coverage_complete'])
        self.assertFalse(r['perceptual_optimum_established']);self.assertFalse(r['population_estimate'])
        self.assertAlmostEqual(sum(x['mean'] for x in r['tonal_equal_time_normalized_fractions']),1)
    def test_missing_is_not_zero(self):
        r=quantiles([None,'-inf',float('nan'),-8.])
        self.assertEqual(r['n'],1);self.assertEqual(r['mean'],-8.)
        self.assertIsNone(quantiles([None])['mean'])
    def test_different_band_grids_rejected(self):
        a=copy.deepcopy(self.row);b=copy.deepcopy(self.row)
        b['tonal']['band_edges_hz']=[0,200,24000]
        with self.assertRaises(ValueError):group_summary([a,b])
    def test_empty_coverage_no_fake_stats(self):
        r=group_summary([{'accepted':False}])
        self.assertEqual(r['accepted'],0);self.assertNotIn('side_mid_db',r)


if __name__=='__main__':unittest.main()
