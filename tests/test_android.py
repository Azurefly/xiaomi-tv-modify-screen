# Copyright (C) 2026 小狗便利店投屏 contributors
# SPDX-License-Identifier: GPL-3.0-only

"""Smoke test against the dedicated emulator, with synthetic local HLS media only."""
import http.client
import http.server
import json
import os
from pathlib import Path
import re
import subprocess
import time
import threading
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT.parent
ADB=WORK/'toolchain/android-sdk/platform-tools/adb'
ENV=dict(os.environ,ANDROID_USER_HOME=str(WORK/'toolchain/android-user'))
SERIAL='emulator-5558'
checks=[]

def adb(*args, input=None):
    return subprocess.run([str(ADB),'-s',SERIAL,*args],input=input,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15,check=True,env=ENV).stdout

interfaces=adb('shell','ip','-4','addr').decode()
ip=re.search(r'inet (10\.0\.2\.\d+)/',interfaces).group(1)

def request(method,path,body=b'',headers=None):
    headers=dict(headers or {})
    headers.update({'Host':f'{ip}:8192','Content-Length':str(len(body)),'Connection':'close'})
    wire=f'{method} {path} HTTP/1.1\r\n'+''.join(f'{k}: {v}\r\n' for k,v in headers.items())+'\r\n'
    # adb's exec-out stdin EOF can truncate a remote nc reply; a remote pipe drains it fully.
    import base64
    encoded=base64.b64encode(wire.encode()+body).decode('ascii')
    raw=adb('shell',f"printf '%s' '{encoded}' | toybox base64 -d | toybox nc -w 8 {ip} 8192")
    status=int(raw.split(b' ',2)[1]);payload=raw.split(b'\r\n\r\n',1)[1]
    return status,payload

def soap(action,values=None,service='AVTransport'):
    ns=f'urn:schemas-upnp-org:service:{service}:1'
    env=ET.Element('{http://schemas.xmlsoap.org/soap/envelope/}Envelope')
    node=ET.SubElement(ET.SubElement(env,'{http://schemas.xmlsoap.org/soap/envelope/}Body'),f'{{{ns}}}{action}')
    for k,v in (values or {}).items():ET.SubElement(node,k).text=v
    return request('POST',f'/control/{service}',ET.tostring(env),{'SOAPAction':f'"{ns}#{action}"','Content-Type':'text/xml; charset=utf-8'})

def check(name,ok):
    assert ok,name
    checks.append(name);print('PASS',name,flush=True)

def state():
    status,body=soap('GetTransportInfo',{'InstanceID':'0'})
    assert status==200,body
    return ET.fromstring(body).find('.//CurrentTransportState').text

def wait_playing():
    end=time.monotonic()+35
    while time.monotonic()<end:
        if state()=='PLAYING':return True
        time.sleep(1)
    return False

status,body=request('GET','/description.xml')
check('Android receiver HTTP service starts',status==200 and '小米电视非官方投屏' in body.decode())
status,body=soap('GetProtocolInfo',service='ConnectionManager')
check('Android XML parser and protocol query work',status==200 and b'application/vnd.apple.mpegurl' in body)
check('nonzero InstanceID rejected',soap('GetTransportInfo',{'InstanceID':'7'})[0]==500)
check('unsupported URI scheme rejected',soap('SetAVTransportURI',{'InstanceID':'0','CurrentURI':'file:///etc/passwd','CurrentURIMetaData':''})[0]==500)
uri='http://10.0.2.2:18099/live.m3u8'
check('live URI received',soap('SetAVTransportURI',{'InstanceID':'0','CurrentURI':uri,'CurrentURIMetaData':''})[0]==200)
check('play command accepted',soap('Play',{'InstanceID':'0','Speed':'1'})[0]==200)
check('synthetic HLS live reaches PLAYING',wait_playing())
time.sleep(3)
_,body=soap('GetPositionInfo',{'InstanceID':'0'})
check('playback position advances',ET.fromstring(body).find('.//RelTime').text!='00:00:00')
(WORK/'emulator-playing.png').write_bytes(adb('exec-out','screencap','-p'))
check('live seek correctly rejected',soap('Seek',{'InstanceID':'0','Unit':'REL_TIME','Target':'00:00:10'})[0]==500)
check('pause command accepted',soap('Pause',{'InstanceID':'0'})[0]==200 and state()=='PAUSED_PLAYBACK')
check('resume accepted',soap('Play',{'InstanceID':'0','Speed':'1'})[0]==200 and wait_playing())
check('second live URI replaces current stream',soap('SetAVTransportURI',{'InstanceID':'0','CurrentURI':uri+'?switch=1','CurrentURIMetaData':''})[0]==200)
check('new stream starts after switching',soap('Play',{'InstanceID':'0','Speed':'1'})[0]==200 and wait_playing())
check('stop returns NO_MEDIA_PRESENT',soap('Stop',{'InstanceID':'0'})[0]==200 and state()=='NO_MEDIA_PRESENT')
_,body=soap('GetVolume',{'InstanceID':'0','Channel':'Master'},service='RenderingControl')
original=ET.fromstring(body).find('.//CurrentVolume').text
check('volume query and bounded update',soap('SetVolume',{'InstanceID':'0','Channel':'Master','DesiredVolume':original},service='RenderingControl')[0]==200)
check('invalid volume rejected',soap('SetVolume',{'InstanceID':'0','Channel':'Master','DesiredVolume':'101'},service='RenderingControl')[0]==500)
check('malformed seek target rejected',soap('Seek',{'InstanceID':'0','Unit':'REL_TIME','Target':'00:00:NaN'})[0]==500)
class FaultMedia(http.server.SimpleHTTPRequestHandler):
    first_failure=None
    def __init__(self,*args,**kwargs):super().__init__(*args,directory=str(WORK/'test-media'),**kwargs)
    def do_GET(self):
        if self.path=='/hidden-live':
            data=(WORK/'test-media/live.m3u8').read_bytes()
            self.send_response(200);self.send_header('Content-Type','video/mp4');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
        if self.path=='/html-video':
            data=b'<!DOCTYPE html><html><body>Not a playable stream</body></html>'
            self.send_response(200);self.send_header('Content-Type','video/mp4');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
        if self.path=='/expired.m3u8':self.send_error(401);return
        if self.path=='/recover.m3u8':
            if type(self).first_failure is None:type(self).first_failure=time.monotonic()
            if time.monotonic()-type(self).first_failure<7:self.send_error(503);return
            self.path='/live.m3u8'
        super().do_GET()
    def log_message(self,*args):pass
fault_server=http.server.ThreadingHTTPServer(('127.0.0.1',18100),FaultMedia)
threading.Thread(target=fault_server.serve_forever,daemon=True).start()
try:
    misleading='<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item><res protocolInfo="http-get:*:video/mp4:*">http://example.invalid/</res></item></DIDL-Lite>'
    soap('SetAVTransportURI',{'InstanceID':'0','CurrentURI':'http://10.0.2.2:18100/hidden-live','CurrentURIMetaData':misleading})
    soap('Play',{'InstanceID':'0','Speed':'1'})
    check('HLS with no extension and wrong MP4 metadata/header recovers to PLAYING',wait_playing())
    soap('SetAVTransportURI',{'InstanceID':'0','CurrentURI':uri,'CurrentURIMetaData':misleading})
    soap('Play',{'InstanceID':'0','Speed':'1'})
    check('m3u8 URI takes precedence over wrong MP4 metadata',wait_playing())
    soap('SetAVTransportURI',{'InstanceID':'0','CurrentURI':'http://10.0.2.2:18100/html-video','CurrentURIMetaData':misleading})
    soap('Play',{'InstanceID':'0','Speed':'1'})
    rejected=False
    for _ in range(12):
        _,payload=soap('GetTransportInfo',{'InstanceID':'0'})
        if ET.fromstring(payload).find('.//CurrentTransportStatus').text=='ERROR_OCCURRED':rejected=True;break
        time.sleep(1)
    check('HTTP 200 HTML labelled MP4 is rejected as non-media',rejected)
    soap('SetAVTransportURI',{'InstanceID':'0','CurrentURI':'http://10.0.2.2:18100/recover.m3u8','CurrentURIMetaData':''})
    soap('Play',{'InstanceID':'0','Speed':'1'})
    check('temporary HTTP 503 recovers to PLAYING',wait_playing())
    soap('SetAVTransportURI',{'InstanceID':'0','CurrentURI':'http://10.0.2.2:18100/expired.m3u8','CurrentURIMetaData':''})
    soap('Play',{'InstanceID':'0','Speed':'1'})
    failure=False
    for _ in range(12):
        _,payload=soap('GetTransportInfo',{'InstanceID':'0'})
        if ET.fromstring(payload).find('.//CurrentTransportStatus').text=='ERROR_OCCURRED':failure=True;break
        time.sleep(1)
    check('HTTP 401 stops with error rather than infinite retry',failure)
    soap('Stop',{'InstanceID':'0'})
finally:
    fault_server.shutdown();fault_server.server_close()
adb('shell','input','keyevent','19')
(WORK/'emulator-waiting.png').write_bytes(adb('exec-out','screencap','-p'))
print('ANDROID_SMOKE_OK:',len(checks),'checks',flush=True)
(ROOT/'build/android-smoke-results.txt').write_text('\n'.join('PASS '+c for c in checks)+f'\nANDROID_SMOKE_OK: {len(checks)} checks\n',encoding='utf-8')
