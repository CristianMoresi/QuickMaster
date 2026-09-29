"""Independent accept/reject rules for the silent public-playback experiment."""
import math


def audit_capture(job, provenance, measurement, settings, events=()):
    errors=[]
    def need(condition,reason):
        if not condition: errors.append(reason)
    need(provenance.get('quality')=='candidate','capture_not_candidate')
    need(provenance.get('job',{}).get('url')==job['url'],'source_url_mismatch')
    need(provenance.get('job',{}).get('id')==job['id'],'source_id_mismatch')
    need(not provenance.get('routeTailTest',False),'tail_diagnostic_not_corpus')
    for key in ('sourceMuted','receiverMuted','sourceMutedAfterCaptureStop'):
        need(provenance.get(key) is True,'mute_invariant:'+key)
    need(provenance.get('pcmPersisted') is False,'unexpected_persisted_audio')
    capture=provenance.get('capture',{})
    need(capture.get('sink')=='none','not_silent_sink')
    need(capture.get('channels')==2 and capture.get('rate')==48000,'invalid_capture_format')
    for key in ('autoGainControl','echoCancellation','noiseSuppression'):
        need(capture.get('settings',{}).get(key) is False,'capture_enhancement:'+key)
    duration=provenance.get('expectedDuration',0)
    elapsed=measurement.get('duration_s',0)
    need(duration>=90,'invalid_content_duration')
    need(duration-.5<=elapsed<=duration+3,'sample_clock_duration_mismatch')
    need(abs(elapsed-measurement.get('frames',0)/48000)<1e-9,'frame_duration_mismatch')
    need(abs(elapsed-provenance.get('capturedDuration',0))<1e-9,'provenance_duration_mismatch')
    boundary=provenance.get('endBoundary') or {}
    guard=provenance.get('tailGuardSeconds',.25)
    boundary_time=boundary.get('time',0)
    need(duration-guard-.03<=boundary_time<=duration+.01,'end_boundary_unverified')
    need(duration>0 and boundary_time/max(duration,1)>=.995,'insufficient_content_coverage')
    gaps=provenance.get('inputGaps',[])
    interior=[g for g in gaps if g['startFrame']/48000>.5 and (g['startFrame']+g['frames'])/48000<duration-5]
    need(not interior,'missing_interior_audio_input')
    timeline=provenance.get('timeline',[])
    need(bool(timeline),'missing_timeline')
    need(all(not r.get('ad',True) for r in timeline),'advertising_contamination')
    need(all(r.get('rate')==1 for r in timeline),'playback_rate_changed')
    volume=[r.get('volume') for r in timeline]
    valid_volume=bool(volume) and all(isinstance(v,(int,float)) and math.isfinite(v) and v>0 for v in volume)
    need(valid_volume,'missing_player_gain')
    if valid_volume: need(max(volume)-min(volume)<1e-7,'player_gain_changed')
    need(all(abs(r.get('duration',0)-duration)<.05 for r in timeline),'content_duration_changed')
    clock_rows=[r for r in timeline if 'capturedFrames' in r and r['elapsed']>10]
    need(all(abs(r['capturedFrames']/48000-r['elapsed'])<=.75 for r in clock_rows),'sample_clock_drift')
    progress_rows=[r for r in events if r.get('kind')=='PROGRESS' and r.get('seconds',0)>10]
    need(all(abs(r['frames']/48000-r['seconds'])<=.75 for r in progress_rows),'progress_sample_clock_drift')
    need(bool(clock_rows or progress_rows),'no_clock_evidence')
    need(settings.get('stableVolume') in ('off','not_offered_in_content_menu','unavailable'),'dynamic_platform_gain_unverified')
    need(not any(r.get('checked')=='true' and ('stable volume' in r.get('text','').lower() or 'volumen estable' in r.get('text','').lower())
                 for r in settings.get('menuAfter',settings.get('menu',[]))),'stable_volume_enabled')
    need(measurement.get('sample_rate')==48000,'measurement_sample_rate')
    need(max(measurement.get('sample_peak_lr',[0]))>1e-4,'no_measurable_content')
    full=measurement.get('full_file',{})
    need(all(isinstance(full.get(k),(int,float)) and math.isfinite(full[k]) and full[k]>=0
             for k in ('mid_power','side_power')),'invalid_ms_power')
    need(bool(measurement.get('long_windows')),'missing_spectral_timeline')
    return {'accepted':not errors,'errors':errors,
        'scope':'Public streaming delivery, not studio-master certification or a perceptual optimum',
        'continuous_clock_logged':bool(clock_rows),
        'clock_evidence_mode':'per_second' if clock_rows else '30s_progress' if progress_rows else 'missing',
        'coverage_fraction_media_clock':boundary_time/duration if duration else None,
        'edge_input_gap_blocks':len(gaps)-len(interior),
        'absolute_level_comparable_between_tracks':False}
