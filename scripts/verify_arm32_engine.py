import io,struct,sys,zipfile
from pathlib import Path
p=Path(sys.argv[1]); aar=p.suffix=='.aar'; prefix='jni/' if aar else 'lib/'
with zipfile.ZipFile(p) as z:
    assert z.testzip() is None, 'ZIP integrity check failed'
    entries=[n for n in z.namelist() if n.startswith(prefix) and n.endswith('.so')]
    arm=[n for n in entries if n.startswith(prefix+'armeabi-v7a/')]
    names={n.rsplit('/',1)[1] for n in arm}
    assert arm, 'No ARM32 libraries'
    if not aar: assert len(entries)==len(arm), 'APK is not ARM32-only'
    for stem in ('avutil','swscale','swresample','avcodec','avformat','avfilter','avdevice'):
        assert {'lib'+stem+'.so','lib'+stem+'_neon.so'} & names, 'Missing '+stem
    assert 'libffmpegkit_abidetect.so' in names, 'Missing ABI detection library'
    assert {'libffmpegkit.so','libffmpegkit_armv7a_neon.so'} & names, 'Missing FFmpegKit JNI library'
    for n in arm:
        with z.open(n) as f: h=f.read(20)
        assert h[:6]==b'\x7fELF\x01\x01' and struct.unpack_from('<H',h,18)[0]==40, 'Not little-endian ELF32 ARM: '+n
        print('PASS ELF32 ARM:',n)
    if aar:
        with zipfile.ZipFile(io.BytesIO(z.read('classes.jar'))) as j:
            for c in ('FFmpegKit','FFmpegKitConfig','FFprobeKit','ReturnCode','AbiDetect'):
                assert 'com/arthenica/ffmpegkit/'+c+'.class' in j.namelist(), 'Missing Java class '+c
    else:
        assert 'AndroidManifest.xml' in z.namelist() and 'classes.dex' in z.namelist(), 'Not a complete APK'
print('PASS:',p.name,'contains',len(arm),'verified ARM32 native libraries')
