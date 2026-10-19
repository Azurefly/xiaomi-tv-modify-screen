/*
 * Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

import cn.local.livingroomcast.core.*;
import java.net.*;
import java.util.*;

public final class CoreHarness {
    public static void main(String[] args) throws Exception {
        if(args.length==2 && args[0].equals("probe")) {
            MediaProbe.Result r=MediaProbe.fetch(args[1]);
            System.out.println(r.status()+"|"+r.contentType()+"|"+r.format().kind()+"|"+r.format().mime()+"|"+r.bytes());return;
        }
        if(args.length>0 && args[0].equals("unit")) {
            if(!Xml.escape("a&<\"'").equals("a&amp;&lt;&quot;&apos;"))throw new AssertionError("escaping");
            if(Dlna.millis("01:02:03.5")!=3723500)throw new AssertionError("time");
            for(String bad:List.of("0:99:0","-1:00:00","00:00:NaN","00:00:Infinity","24","00:00:60")) {
                try { Dlna.millis(bad); throw new AssertionError("bad time accepted"); } catch(Dlna.Fault expected){}
            }
            try {Xml.parse("<!DOCTYPE r [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><r>&x;</r>");throw new AssertionError("DTD accepted");}catch(IllegalArgumentException expected){}
            if(SsdpServer.parseSearch("M-SEARCH * HTTP/1.1\r\nMAN: \"ssdp:discover\"\r\nST: ssdp:all\r\nMX: 99\r\n\r\n").mx()!=5)throw new AssertionError("MX cap");
            if(SsdpServer.parseSearch("NOTIFY * HTTP/1.1\r\nST: ssdp:all\r\n\r\n")!=null)throw new AssertionError("not search");
            String metadata="<DIDL-Lite xmlns='urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/' xmlns:dc='http://purl.org/dc/elements/1.1/'><item><dc:title>直播 &amp; 测试</dc:title><res protocolInfo='http-get:*:application/vnd.apple.mpegurl:*'>https://example.invalid/live?token=private</res></item></DIDL-Lite>";
            if(!Xml.first(metadata,"title").equals("直播 & 测试"))throw new AssertionError("metadata title");
            if(!Xml.resourceMime(metadata).equals("application/vnd.apple.mpegurl"))throw new AssertionError("metadata mime");
            if(!MediaProbe.initialMime("https://example.invalid/live.m3u8?token=private","video/mp4").equals(MediaProbe.HLS))throw new AssertionError("mislabelled HLS URI");
            if(!MediaProbe.initialMime("https://example.invalid/live.mpd?token=private","video/mp4").equals(MediaProbe.DASH))throw new AssertionError("mislabelled DASH URI");
            if(!MediaProbe.initialMime("https://example.invalid/movie.mp4","video/mp4").equals("video/mp4"))throw new AssertionError("MP4 preserved");
            if(!MediaProbe.mime("Application/X-MpegURL; charset=UTF-8").equals(MediaProbe.HLS))throw new AssertionError("canonical HLS MIME");
            if(!MediaProbe.inspect("\uFEFF \n#EXTM3U\n#EXT-X-TARGETDURATION:2".getBytes(java.nio.charset.StandardCharsets.UTF_8),"video/mp4","").kind().equals("HLS"))throw new AssertionError("HLS magic");
            if(!MediaProbe.inspect("<!DOCTYPE html><html>failed</html>".getBytes(),"video/mp4","").nonMedia())throw new AssertionError("HTML rejected");
            for(String service:Dlna.SERVICES){Xml.parse(Dlna.scpd(service)); Xml.parse(Dlna.eventXml(service,Dlna.values("TransportState","PLAYING")));}
            System.out.println("CORE_UNIT_OK");return;
        }
        Dlna.Controller controller=new Dlna.Controller(){
            String state="NO_MEDIA_PRESENT",uri="";
            @Override public synchronized Map<String,String> action(String service,String action,Map<String,String> args)throws Exception {
                switch(action){
                    case "SetAVTransportURI":uri=args.get("CurrentURI");state="STOPPED";return Map.of();
                    case "Play":state="PLAYING";return Map.of();
                    case "Pause":state="PAUSED_PLAYBACK";return Map.of();
                    case "Stop":state="STOPPED";return Map.of();
                    case "GetTransportInfo":return Dlna.values("CurrentTransportState",state,"CurrentTransportStatus","OK","CurrentSpeed","1");
                    case "GetPositionInfo":return Dlna.values("Track","1","TrackDuration","00:00:00","TrackMetaData","","TrackURI",uri,"RelTime","00:00:12","AbsTime","00:00:12","RelCount","2147483647","AbsCount","2147483647");
                    case "GetProtocolInfo":return Dlna.values("Source","","Sink",Dlna.PROTOCOLS);
                    default:return Map.of();
                }
            }
            @Override public synchronized Map<String,String> event(String service){return service.equals(Dlna.CM)?Dlna.values("SourceProtocolInfo","","SinkProtocolInfo",Dlna.PROTOCOLS,"CurrentConnectionIDs","0"):Dlna.values("TransportState",state);}
        };
        HttpServer server=new HttpServer(InetAddress.getByName("127.0.0.1"),0,"12345678-1234-1234-1234-123456789012",controller,System.err::println);
        SsdpServer ssdp=new SsdpServer(InetAddress.getByName("127.0.0.1"),"12345678-1234-1234-1234-123456789012",server.base()+"/description.xml",System.err::println);
        server.start();ssdp.start();System.out.println(server.base());System.out.flush();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{ssdp.close();server.close();}));
        Thread.sleep(180000);
        ssdp.close();server.close();
    }
}
