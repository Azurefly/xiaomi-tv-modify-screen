# Copyright (C) 2026 小狗便利店投屏 contributors
# SPDX-License-Identifier: GPL-3.0-only

"""Wire-level protocol tests. These do not claim real Android or Z视介 compatibility."""
import http.client
import http.server
import os
from pathlib import Path
import queue
import socket
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ['JAVA_HOME']) / 'bin/java'
CLASSES = ROOT / 'build/core-tests'
CLASSES.mkdir(parents=True, exist_ok=True)
sources = list((ROOT / 'app/src/main/java/cn/local/livingroomcast/core').glob('*.java'))
subprocess.run([str(JAVA.with_name('javac')), '-encoding', 'UTF-8', '-d', str(CLASSES), *map(str, sources), str(ROOT/'tests/CoreHarness.java')], check=True)
subprocess.run([str(JAVA), '-cp', str(CLASSES), 'CoreHarness', 'unit'], check=True)
proc = subprocess.Popen([str(JAVA), '-cp', str(CLASSES), 'CoreHarness'], stdout=subprocess.PIPE, text=True)
base = proc.stdout.readline().strip()
parts = urlsplit(base)
checks = []
notifies = queue.Queue()

def request(method, path, body=None, headers=None):
    connection = http.client.HTTPConnection(parts.hostname, parts.port, timeout=6)
    connection.request(method, path, body=body, headers=headers or {})
    response = connection.getresponse()
    result = response.status, dict(response.getheaders()), response.read()
    connection.close()
    return result

def check(name, ok):
    assert ok, name
    checks.append(name)

def soap(service, action, values=None, header_action=None):
    namespace = f'urn:schemas-upnp-org:service:{service}:1'
    node = ET.Element(f'{{{namespace}}}{action}')
    for key, value in (values or {}).items():
        ET.SubElement(node,key).text = value
    envelope = ET.Element('{http://schemas.xmlsoap.org/soap/envelope/}Envelope')
    body = ET.SubElement(envelope,'{http://schemas.xmlsoap.org/soap/envelope/}Body')
    body.append(node)
    return request('POST',f'/control/{service}',ET.tostring(envelope),{'SOAPAction':f'"{namespace}#{header_action or action}"','Content-Type':'text/xml; charset=utf-8'})

class Callback(http.server.BaseHTTPRequestHandler):
    def do_NOTIFY(self):
        body=self.rfile.read(int(self.headers['Content-Length']))
        notifies.put((dict(self.headers),body))
        self.send_response(200); self.end_headers()
    def log_message(self,*args): pass

callback=http.server.ThreadingHTTPServer(('127.0.0.1',0),Callback)
threading.Thread(target=callback.serve_forever,daemon=True).start()
try:
    discovery=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);discovery.bind(('127.0.0.1',0));discovery.settimeout(2)
    discovery.sendto(b'M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nST: ssdp:all\r\nMX: 1\r\n\r\n',('127.0.0.1',1900))
    discovered=[]
    for _ in range(6):discovered.append(discovery.recvfrom(8192)[0].decode('ascii'))
    check('SSDP responds to all six discovery targets',len({next(line for line in response.split('\r\n') if line.startswith('ST: ')) for response in discovered})==6)
    check('SSDP advertises reachable device description',all(f'LOCATION: {base}/description.xml' in response and 'USN: uuid:12345678-1234-1234-1234-123456789012' in response for response in discovered))
    discovery.close()
    status,headers,body=request('GET','/description.xml')
    doc=ET.fromstring(body)
    ns={'d':'urn:schemas-upnp-org:device-1-0'}
    check('UTF-8 device name and MediaRenderer description', status==200 and doc.find('.//d:friendlyName',ns).text=='小米电视非官方投屏')
    services=doc.findall('.//d:service',ns)
    check('three advertised AV services',len(services)==3)
    for service in services:
        path=service.find('d:SCPDURL',ns).text
        status,_,body=request('GET',path)
        scpd=ET.fromstring(body); sn={'s':'urn:schemas-upnp-org:service-1-0'}
        variables={v.find('s:name',sn).text for v in scpd.findall('.//s:stateVariable',sn)}
        check(f'{path} valid state-variable references',status==200 and all(v.text in variables for v in scpd.findall('.//s:relatedStateVariable',sn)))
    check('HEAD sends no body',request('HEAD','/description.xml')[2]==b'')
    check('unknown path rejected',request('GET','/no-such-path')[0]==404)
    check('unknown service rejected',request('POST','/control/unknown',b'')[0]==404)
    status,_,body=soap('ConnectionManager','GetProtocolInfo')
    check('receiver advertises HLS',status==200 and b'application/vnd.apple.mpegurl' in body)
    check('unknown action is SOAP fault',soap('AVTransport','Unknown',{})[0]==500)
    check('missing arguments rejected',soap('AVTransport','SetAVTransportURI',{'InstanceID':'0'})[0]==500)
    check('SOAPAction mismatch rejected',soap('AVTransport','Stop',{'InstanceID':'0'},'Play')[0]==500)
    uri='https://example.invalid/live.m3u8?one=1&token=secret'
    check('SetAVTransportURI with escaped query accepted',soap('AVTransport','SetAVTransportURI',{'InstanceID':'0','CurrentURI':uri,'CurrentURIMetaData':''})[0]==200)
    check('play accepted',soap('AVTransport','Play',{'InstanceID':'0','Speed':'1'})[0]==200)
    status,_,body=soap('AVTransport','GetPositionInfo',{'InstanceID':'0'})
    check('URI survives XML round-trip',status==200 and ET.fromstring(body).find('.//TrackURI').text==uri)
    check('transport returns PLAYING',b'>PLAYING<' in soap('AVTransport','GetTransportInfo',{'InstanceID':'0'})[2])
    status,headers,_=request('SUBSCRIBE','/event/AVTransport',headers={'CALLBACK':f'<http://127.0.0.1:{callback.server_port}/events>','NT':'upnp:event','TIMEOUT':'Second-60'})
    sid=headers.get('SID')
    check('GENA subscription accepted',status==200 and sid and headers.get('TIMEOUT')=='Second-60')
    h,b=notifies.get(timeout=5)
    seq=next(v for k,v in h.items() if k.lower()=='seq')
    event=ET.fromstring(b);last=event.find('.//LastChange').text
    check('GENA initial sequence zero and nested LastChange XML',seq=='0' and ET.fromstring(last).find('.//{urn:schemas-upnp-org:metadata-1-0/AVT/}TransportState').get('val')=='PLAYING')
    soap('AVTransport','Pause',{'InstanceID':'0'})
    h,b=notifies.get(timeout=5)
    check('state-change notification after pause',b'PAUSED_PLAYBACK' in b)
    check('subscription renewal accepted',request('SUBSCRIBE','/event/AVTransport',headers={'SID':sid,'TIMEOUT':'Second-120'})[0]==200)
    check('wrong service cannot reuse SID',request('SUBSCRIBE','/event/RenderingControl',headers={'SID':sid})[0]==412)
    check('unrelated callback rejected',request('SUBSCRIBE','/event/AVTransport',headers={'CALLBACK':'<http://192.0.2.1/exfil>','NT':'upnp:event'})[0]==412)
    check('unsubscribe accepted',request('UNSUBSCRIBE','/event/AVTransport',headers={'SID':sid})[0]==200)
    check('expired SID cannot renew',request('SUBSCRIBE','/event/AVTransport',headers={'SID':sid})[0]==412)
    malicious=b'<!DOCTYPE x [<!ENTITY x SYSTEM "file:///etc/passwd">]><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>&x;</InstanceID></u:Stop></s:Body></s:Envelope>'
    check('DTD entity payload rejected',request('POST','/control/AVTransport',malicious,{'SOAPAction':'"urn:schemas-upnp-org:service:AVTransport:1#Stop"'})[0]==500)
    connection=socket.create_connection((parts.hostname,parts.port));connection.settimeout(5)
    connection.sendall(b'POST /control/AVTransport HTTP/1.1\r\nContent-Length: 999999\r\n\r\n')
    check('oversized request rejected',b'400 Bad Request' in connection.recv(1024));connection.close()
    sids=[request('SUBSCRIBE','/event/AVTransport',headers={'CALLBACK':f'<http://127.0.0.1:{callback.server_port}/events>','NT':'upnp:event','TIMEOUT':'Second-60'})[1].get('SID') for _ in range(5)]
    check('one peer cannot exhaust subscriptions',all(sids[:4]) and sids[4] is None)
    for extra in sids[:4]: request('UNSUBSCRIBE','/event/AVTransport',headers={'SID':extra})
    while not notifies.empty(): notifies.get_nowait()
    slow=socket.create_connection((parts.hostname,parts.port));slow.settimeout(20);started=time.monotonic();closed=False
    try:
        slow.sendall(b'GET / HTTP/1.1\r\n')
        while time.monotonic()-started<16:
            slow.sendall(b'X');time.sleep(2)
    except OSError: closed=True
    check('slow-drip request cut off by overall deadline',closed and time.monotonic()-started<16);slow.close()
    idle=[socket.create_connection((parts.hostname,parts.port)) for _ in range(3)]
    extra=socket.create_connection((parts.hostname,parts.port));extra.settimeout(3)
    try: refused=extra.recv(1)==b''
    except OSError: refused=True
    check('fourth concurrent connection from one peer refused',refused)
    for c in idle+[extra]: c.close()
    time.sleep(0.3)
    status,headers,_=request('SUBSCRIBE','/event/AVTransport',headers={'CALLBACK':f'<http://127.0.0.1:{callback.server_port}/events>','NT':'upnp:event','TIMEOUT':'Second-60'})
    burst_sid=headers.get('SID');notifies.get(timeout=5)
    for action in ('Play','Pause','Play','Pause','Play'): soap('AVTransport',action,{'InstanceID':'0','Speed':'1'})
    time.sleep(1.5);burst=[]
    while not notifies.empty(): burst.append(notifies.get_nowait())
    check('rapid state changes coalesced into one NOTIFY with final state',len(burst)==1 and b'PLAYING' in burst[0][1] and b'PAUSED_PLAYBACK' not in burst[0][1])
    request('UNSUBSCRIBE','/event/AVTransport',headers={'SID':burst_sid})
    status,_,body=soap('AVTransport','Stop',{'InstanceID':'0'})
    check('server remains usable after invalid inputs',status==200)
    print(f'PROTOCOL_OK: {len(checks)} checks')
    (ROOT/'build/protocol-test-results.txt').write_text('CORE_UNIT_OK\n'+'\n'.join('PASS '+name for name in checks)+f'\nPROTOCOL_OK: {len(checks)} checks\n',encoding='utf-8')
finally:
    proc.terminate();proc.wait(timeout=10);callback.shutdown();callback.server_close()
