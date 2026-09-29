param([string]$Destination='dist/mp3-port-20260929/fixtures')
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Path $Destination -Force|Out-Null
$cases=@(
 @{name='stereo-48000-cbr';rate=48000;channels=2;mode='cbr';value='192k';expression='0.18*sin(2*PI*997*t)+0.12*sin(2*PI*6101*t)|0.16*sin(2*PI*1703*t)+0.08*sin(2*PI*11111*t)'},
 @{name='mono-44100-vbr';rate=44100;channels=1;mode='vbr';value='2';expression='0.25*sin(2*PI*711*t)+0.12*sin(2*PI*10003*t)'},
 @{name='stereo-32000-lowrate';rate=32000;channels=2;mode='cbr';value='64k';expression='0.23*sin(2*PI*311*t)|0.19*sin(2*PI*1703*t)'},
 @{name='stereo-44100-transients';rate=44100;channels=2;mode='vbr';value='0';expression='if(lt(mod(t,0.063),0.004),0.55*sin(2*PI*13007*t),0.02*sin(2*PI*233*t))|if(lt(mod(t,0.079),0.003),0.49*sin(2*PI*7111*t),0.03*sin(2*PI*173*t))'},
 @{name='mono-48000-quiet';rate=48000;channels=1;mode='cbr';value='320k';expression='0.000004*sin(2*PI*997*t)'},
 @{name='stereo-48000-headroom';rate=48000;channels=2;mode='cbr';value='320k';expression='1.1*sin(2*PI*997*t)|1.07*sin(2*PI*1703*t)'},
 @{name='mono-24000-mpeg2';rate=24000;channels=1;mode='cbr';value='64k';expression='0.23*sin(2*PI*311*t)'},
 @{name='stereo-11025-mpeg25';rate=11025;channels=2;mode='vbr';value='2';expression='0.21*sin(2*PI*311*t)|0.19*sin(2*PI*1703*t)'}
)
foreach($case in $cases) {
 $mp3=Join-Path $Destination ($case.name+'.mp3')
 $ref=Join-Path $Destination ($case.name+'.ffmpeg.f32le')
 $option=if($case.mode -eq 'vbr'){'-q:a'}else{'-b:a'}
 $source="aevalsrc='$($case.expression)':s=$($case.rate):d=0.413"
 & ffmpeg -nostdin -hide_banner -loglevel error -y -f lavfi -i $source -ac $case.channels -c:a libmp3lame $option $case.value -write_xing 1 -id3v2_version 3 $mp3
 if($LASTEXITCODE -ne 0){throw "Fixture encoding failed $mp3"}
 & ffmpeg -nostdin -hide_banner -loglevel error -y -c:a mp3float -i $mp3 -f f32le -c:a pcm_f32le $ref
 if($LASTEXITCODE -ne 0){throw "Reference decoding failed $mp3"}
}
Get-ChildItem -LiteralPath $Destination -File|Get-FileHash -Algorithm SHA256|Select-Object Path,Hash
