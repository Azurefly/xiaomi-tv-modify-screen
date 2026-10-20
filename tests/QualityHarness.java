/* Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only */
import cn.local.livingroomcast.core.QualityCatalog;
import java.util.*;

public class QualityHarness {
    static QualityCatalog.Track t(int group,int index,int w,int h,boolean supported,boolean active) {
        return new QualityCatalog.Track(group,index,w,h,supported,active);
    }
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message);System.out.println("PASS "+message); }
    public static void main(String[] args) {
        var choices=QualityCatalog.from(List.of(t(0,0,1280,720,true,true),t(0,1,1920,1080,true,true),t(0,2,3840,2160,true,true),t(0,3,7680,4320,false,true)));
        check(choices.size()==3 && choices.get(0).key().equals("3840x2160"),"only advertised and supported resolutions; highest first");
        check(choices.get(0).label().equals("4K · 3840×2160"),"real 2160p track is labelled 4K");
        var single=QualityCatalog.from(List.of(t(0,0,1920,1080,true,true)));
        check(single.size()==1 && single.get(0).label().startsWith("1080p"),"fixed 1080p stream never invents a 4K or 720p option");
        var duplicate=QualityCatalog.from(List.of(t(0,0,1920,1080,true,false),t(0,1,1920,1080,true,false),t(1,0,1920,1080,true,true)));
        check(duplicate.size()==1,"duplicate bitrate and codec variants produce one resolution choice");
        check(duplicate.get(0).preferredGroup()==1 && duplicate.get(0).indices(1).equals(List.of(0)),"preserve active codec group without mixing track indices");
        check(duplicate.get(0).indices(0).equals(List.of(0,1)),"same-resolution bitrate variants remain available within their group");
        check(QualityCatalog.from(List.of(t(0,0,-1,-1,true,false),t(0,1,3840,2160,false,false))).isEmpty(),"unknown dimensions and unsupported 4K are omitted");
        var portrait=QualityCatalog.from(List.of(t(0,0,1080,1920,true,true)));
        check(portrait.get(0).label().equals("1080p · 1080×1920"),"portrait video keeps its actual encoded dimensions");
        System.out.println("QUALITY_UNIT_OK: 8 checks");
    }
}
