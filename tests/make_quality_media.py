# Copyright (C) 2026 小狗便利店投屏 contributors
# SPDX-License-Identifier: GPL-3.0-only
"""Generate synthetic multi-resolution HLS; requires an external ffmpeg with libx264."""
import argparse
from pathlib import Path
import subprocess
parser=argparse.ArgumentParser();parser.add_argument('directory',type=Path);args=parser.parse_args()
root=args.directory;root.mkdir(parents=True,exist_ok=True)
rows=['#EXTM3U','#EXT-X-VERSION:3']
for name,width,height,color,bandwidth,level in [('360',640,360,'blue',100000,'4.0'),('720',1280,720,'green',300000,'4.0'),('1080',1920,1080,'red',700000,'4.0'),('2160',3840,2160,'purple',2000000,'5.1')]:
    directory=root/name;directory.mkdir(exist_ok=True)
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i',f'color=c={color}:s={width}x{height}:r=2','-t','180','-c:v','libx264','-threads','2','-preset','ultrafast','-tune','zerolatency','-profile:v','baseline','-level:v',level,'-pix_fmt','yuv420p','-g','4','-sc_threshold','0','-an','-hls_time','2','-hls_playlist_type','vod','-hls_list_size','0','-hls_segment_filename',str(directory/'segment%03d.ts'),str(directory/'index.m3u8')],check=True)
    codec='avc1.42c033' if name=='2160' else 'avc1.42c028'
    rows.extend([f'#EXT-X-STREAM-INF:BANDWIDTH={bandwidth},RESOLUTION={width}x{height},FRAME-RATE=2.000,CODECS="{codec}"',name+'/index.m3u8'])
(root/'master.m3u8').write_text('\n'.join(rows)+'\n')
print('Synthetic test media generated at',root)
