# Copyright (C) 2026 小狗便利店投屏 contributors
# SPDX-License-Identifier: GPL-3.0-only

"""Format regression tests: a misleading MP4 label must not override actual playlist bytes."""
import http.server
import os
from pathlib import Path
import subprocess
import threading

ROOT=Path(__file__).resolve().parents[1]
JAVA=Path(os.environ['JAVA_HOME'])/'bin/java'
CLASSES=ROOT/'build/core-tests'
CLASSES.mkdir(parents=True,exist_ok=True)
subprocess.run([str(JAVA.with_name('javac')),'-encoding','UTF-8','-d',str(CLASSES),*map(str,(ROOT/'app/src/main/java/cn/local/livingroomcast/core').glob('*.java')),str(ROOT/'tests/CoreHarness.java')],check=True)
subprocess.run([str(JAVA),'-cp',str(CLASSES),'CoreHarness','unit'],check=True)
playlist=b'#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nsegment.ts\n'
class Fixture(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        route=self.path.split('?',1)[0]
        if route=='/redirect':
            self.send_response(302);self.send_header('Location','/hidden?token=DO_NOT_LOG_ME');self.end_headers();return
        if route=='/reject-range' and self.headers.get('Range'):
            self.send_response(416);self.end_headers();return
        cases={
            '/hidden':(200,'video/mp4',playlist),
            '/html':(200,'video/mp4',b'<!doctype html><html>Not a video</html>'),
            '/json':(200,'video/mp4',b'{"error":"session expired"}'),
            '/dash':(200,'application/octet-stream',b'<?xml version="1.0"?><!--'+b'x'*2048+b'--><MPD xmlns="urn:mpeg:dash:schema:mpd:2011"></MPD>'),
            '/reject-range':(200,'video/mp4',playlist),
            '/large':(200,'application/octet-stream',b'x'*100000),
            '/expired':(401,'text/html',b'<html>Denied</html>'),
            '/mp4':(200,'video/mp4',b'\x00\x00\x00\x18ftypisom'+b'\x00'*20),
            '/flv':(200,'application/octet-stream',b'FLV\x01'+b'\x00'*100),
        }
        code,mime,data=cases.get(route,(404,'text/plain',b''))
        self.send_response(code);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(data)));self.end_headers()
        try:self.wfile.write(data)
        except (BrokenPipeError,ConnectionResetError):pass
    def log_message(self,*args):pass
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Fixture)
threading.Thread(target=server.serve_forever,daemon=True).start()
checks=[]
try:
    for path,expected in [('/hidden','HLS'),('/redirect','HLS'),('/html','HTML'),('/json','JSON'),('/dash','DASH'),('/reject-range','HLS'),('/large','UNKNOWN'),('/expired','HTTP-ERROR'),('/mp4','MP4'),('/flv','FLV')]:
        output=subprocess.check_output([str(JAVA),'-cp',str(CLASSES),'CoreHarness','probe',f'http://127.0.0.1:{server.server_port}{path}'],text=True).strip()
        status,mime,kind,playback,read=output.split('|')
        assert kind==expected,(path,output)
        assert int(read)<=8192,(path,'oversized read')
        assert 'DO_NOT_LOG_ME' not in output
        checks.append(f'PASS {path}: {kind}, bounded read, no URL/token output')
    (ROOT/'build/format-probe-results.txt').write_text('\n'.join(checks)+f'\nFORMAT_PROBE_OK: {len(checks)} checks\n',encoding='utf-8')
    print('\n'.join(checks));print('FORMAT_PROBE_OK:',len(checks),'checks')
finally:server.shutdown();server.server_close()
