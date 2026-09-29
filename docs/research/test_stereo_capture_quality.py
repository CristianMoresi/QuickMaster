import copy
import unittest
from capture_quality import audit_capture


class CaptureQualityTests(unittest.TestCase):
    def setUp(self):
        self.job={'id':'test','url':'https://www.youtube.com/watch?v=abcdefghijk'}
        self.prov={'quality':'candidate','job':self.job,'sourceMuted':True,'receiverMuted':True,
            'sourceMutedAfterCaptureStop':True,'pcmPersisted':False,'expectedDuration':200.,
            'capturedDuration':201.,'tailGuardSeconds':.25,'endBoundary':{'time':199.76},
            'capture':{'sink':'none','channels':2,'rate':48000,'settings':{
                'autoGainControl':False,'echoCancellation':False,'noiseSuppression':False}},
            'timeline':[{'elapsed':20.,'capturedFrames':960000,'ad':False,'rate':1,'volume':.7,'duration':200.}],
            'inputGaps':[]}
        self.meas={'duration_s':201.,'frames':201*48000,'sample_rate':48000,
            'sample_peak_lr':[.5,.5],'full_file':{'mid_power':.1,'side_power':.01},'long_windows':[{}]}
        self.settings={'stableVolume':'not_offered_in_content_menu','menu':[]}
    def check(self):return audit_capture(self.job,self.prov,self.meas,self.settings)
    def test_good_delivery_accepted_with_scope(self):
        self.assertTrue(self.check()['accepted'])
        self.assertFalse(self.check()['absolute_level_comparable_between_tracks'])
    def test_short_audio_clock_not_accepted(self):
        self.meas['duration_s']=130.;self.meas['frames']=130*48000
        self.assertIn('sample_clock_duration_mismatch',self.check()['errors'])
    def test_temporary_clock_drift_rejected_even_if_duration_recovers(self):
        self.prov['timeline'][0]['capturedFrames']=600000
        self.assertIn('sample_clock_drift',self.check()['errors'])
    def test_diagnostic_tail_excluded(self):
        self.prov['routeTailTest']=True
        self.assertIn('tail_diagnostic_not_corpus',self.check()['errors'])
    def test_ad_and_variable_volume_rejected(self):
        row=copy.deepcopy(self.prov['timeline'][0]);row.update(ad=True,volume=.9)
        self.prov['timeline'].append(row)
        self.assertIn('advertising_contamination',self.check()['errors'])
        self.assertIn('player_gain_changed',self.check()['errors'])
    def test_input_gaps_and_unverified_end_rejected(self):
        self.prov['inputGaps']=[{'startFrame':48000,'frames':4096}]
        self.prov['endBoundary']=None
        self.assertIn('missing_interior_audio_input',self.check()['errors'])
        self.assertIn('end_boundary_unverified',self.check()['errors'])
    def test_mute_loss_and_dynamic_platform_gain_rejected(self):
        self.prov['sourceMutedAfterCaptureStop']=False
        self.settings={'stableVolume':'off','menuAfter':[{'text':'Volumen estable','checked':'true'}]}
        self.assertIn('mute_invariant:sourceMutedAfterCaptureStop',self.check()['errors'])
        self.assertIn('stable_volume_enabled',self.check()['errors'])
    def test_source_mismatch_not_reused(self):
        wrong={**self.job,'url':'https://www.youtube.com/watch?v=other123456'}
        self.prov['job']=wrong
        self.assertIn('source_url_mismatch',self.check()['errors'])

    def test_legacy_progress_clock_is_checked_not_invented(self):
        self.prov['timeline'][0].pop('capturedFrames')
        self.assertIn('no_clock_evidence',self.check()['errors'])
        good=[{'kind':'PROGRESS','seconds':30,'frames':1440000}]
        a=audit_capture(self.job,self.prov,self.meas,self.settings,good)
        self.assertTrue(a['accepted']);self.assertEqual(a['clock_evidence_mode'],'30s_progress')
        bad=[{'kind':'PROGRESS','seconds':30,'frames':960000}]
        a=audit_capture(self.job,self.prov,self.meas,self.settings,bad)
        self.assertIn('progress_sample_clock_drift',a['errors'])


if __name__=='__main__':unittest.main()
