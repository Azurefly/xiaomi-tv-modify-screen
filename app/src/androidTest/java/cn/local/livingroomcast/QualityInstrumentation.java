/* Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only */
package cn.local.livingroomcast;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import cn.local.livingroomcast.core.Dlna;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/** Standalone native smoke runner; synthetic HLS at the emulator host only. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class QualityInstrumentation extends Instrumentation {
    private ReceiverService service;
    private Activity activity;
    private final List<String> checks=new ArrayList<>();
    private <T> T ui(Supplier<T> task) {
        AtomicReference<T> result=new AtomicReference<>();
        AtomicReference<Throwable> error=new AtomicReference<>();
        runOnMainSync(()->{try{result.set(task.get());}catch(Throwable e){error.set(e);}});
        if(error.get()!=null)throw new AssertionError(error.get());
        return result.get();
    }
    private void check(String message,boolean ok){if(!ok)throw new AssertionError(message);checks.add("PASS "+message);}
    private void await(String message,BooleanSupplier test)throws Exception {
        long end=System.currentTimeMillis()+45000;
        while(System.currentTimeMillis()<end){if(test.getAsBoolean()){check(message,true);return;}Thread.sleep(200);}
        throw new AssertionError(message+"; actual="+ui(()->service.ui().quality()));
    }
    private void source(String path)throws Exception {
        service.action(Dlna.AV,"SetAVTransportURI",Dlna.values("InstanceID","0","CurrentURI","http://10.0.2.2:18099/"+path,"CurrentURIMetaData",""));
        service.action(Dlna.AV,"Play",Dlna.values("InstanceID","0","Speed","1"));
    }
    private boolean tap(String text){
        AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return false;
        for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text)){
            if(!text.contentEquals(node.getText()==null?"":node.getText()) || !node.isVisibleToUser())continue;
            for(int i=0;i<4 && node!=null;i++,node=node.getParent())if(node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
        }
        return false;
    }
    private void openMenu()throws Exception {
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);Thread.sleep(200);
        await("native quality button is reachable",()->tap("清晰度"));Thread.sleep(300);
    }
    private void shot(String name)throws Exception {
        File dir=new File(getTargetContext().getFilesDir(),"quality-tests");dir.mkdirs();
        try(FileOutputStream output=new FileOutputStream(new File(dir,name))){getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,output);}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        CountDownLatch bound=new CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder binder){service=((ReceiverService.LocalBinder)binder).get();bound.countDown();}
            @Override public void onServiceDisconnected(ComponentName name){}
        };
        Bundle result=new Bundle();
        try{
            Intent launch=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=startActivitySync(launch);
            getTargetContext().bindService(new Intent(getTargetContext(),ReceiverService.class),connection,Context.BIND_AUTO_CREATE);
            check("service bound",bound.await(10,TimeUnit.SECONDS));
            source("master.m3u8");
            await("adaptive source exposes actual selectable resolutions",()->ui(()->service.qualityMenu().choices().size()>=3));
            await("decoder reports actual resolution",()->ui(()->service.player().isPlaying() && !service.ui().quality().equals("正在识别…")));
            check("new session defaults to auto",ui(()->service.qualityMenu().selectedKey().isEmpty()));
            openMenu();shot("清晰度菜单.png");
            check("native 360p menu row selects a real track",tap("360p · 640×360"));
            await("actual decoded size reaches selected 360p",()->ui(()->service.ui().quality().equals("640×360")));
            check("manual choice is recorded separately from actual size",ui(()->service.qualityMenu().selectedKey().equals("640x360")));
            openMenu();check("native 1080p menu row is available",tap("1080p · 1920×1080"));
            await("switch reaches actual 1080p frames",()->ui(()->service.ui().quality().equals("1920×1080")));
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);Thread.sleep(200);shot("实际画质-1080p.png");
            ui(()->{service.togglePause();return null;});
            check("pause preserves resolution choice",ui(()->service.qualityMenu().selectedKey().equals("1920x1080")));
            ui(()->{service.togglePause();return null;});
            var with4k=ui(()->service.qualityMenu());
            boolean supports4k=with4k.choices().stream().anyMatch(c->c.key().equals("3840x2160"));
            if(supports4k){
                check("supported source 4K can be selected",ui(()->service.selectQuality(with4k.generation(),"3840x2160")));
                await("4K selection reaches 3840x2160 decoder input",()->ui(()->service.ui().quality().equals("3840×2160")));
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);Thread.sleep(200);shot("实际画质-4K.png");
            }else check("unsupported 4K is not offered",true);
            openMenu();check("native auto row is selectable",tap("自动 · 随网络调整"));
            check("auto clears manual video overrides",ui(()->service.qualityMenu().selectedKey().isEmpty() && service.player().getTrackSelectionParameters().overrides.isEmpty()));
            var before=ui(()->service.qualityMenu());
            check("manual choice accepted for current session",ui(()->service.selectQuality(before.generation(),"640x360")));
            openMenu();source("360/index.m3u8");
            await("new fixed source exposes one real resolution",()->ui(()->service.qualityMenu().choices().size()==1));
            await("fixed source reports actual 360p",()->ui(()->service.ui().quality().equals("640×360")));
            check("new source resets to auto",ui(()->service.qualityMenu().selectedKey().isEmpty()));
            check("stale dialog session cannot override new video",!ui(()->service.selectQuality(before.generation(),"1920x1080")));
            openMenu();Thread.sleep(200);shot("固定片源提示.png");
            check("single-quality explanation is visible",getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("当前片源仅提供一个电视可播放的画质档位").size()>0);
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            ui(()->{service.stopPlayback();return null;});
            check("stop clears quality and returns waiting state",ui(()->!service.ui().video() && service.qualityMenu().selectedKey().isEmpty()));
            result.putString("stream",String.join("\n",checks)+"\nQUALITY_ANDROID_OK: "+checks.size()+" checks; 4K="+supports4k+"\n");
            result.putBoolean("quality_ok",true);finish(Activity.RESULT_OK,result);
        }catch(Throwable e){StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));result.putString("stream",String.join("\n",checks)+"\n"+trace);result.putBoolean("quality_ok",false);finish(Activity.RESULT_CANCELED,result);}
        finally{try{getTargetContext().unbindService(connection);}catch(Exception ignored){}if(activity!=null)runOnMainSync(()->activity.finish());}
    }
}
