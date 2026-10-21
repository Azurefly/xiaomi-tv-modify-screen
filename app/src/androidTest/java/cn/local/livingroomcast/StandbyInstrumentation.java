/* Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only */
package cn.local.livingroomcast;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.provider.Settings;
import cn.local.livingroomcast.core.Dlna;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/** Exercises actual service lifetime, SSDP and SOAP while the TV UI is stopped. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class StandbyInstrumentation extends Instrumentation {
    private ReceiverService service;
    private Activity activity;
    private final List<String> checks=new ArrayList<>();
    private <T> T ui(Supplier<T> task) {
        AtomicReference<T> result=new AtomicReference<>(); AtomicReference<Throwable> error=new AtomicReference<>();
        runOnMainSync(()->{try{result.set(task.get());}catch(Throwable e){error.set(e);}});
        if(error.get()!=null)throw new AssertionError(error.get());return result.get();
    }
    private void check(String text,boolean ok){if(!ok)throw new AssertionError(text);checks.add("PASS "+text);}
    private void await(String text,BooleanSupplier test)throws Exception {
        long end=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<end){if(test.getAsBoolean()){check(text,true);return;}Thread.sleep(150);}
        throw new AssertionError(text+"; "+ui(()->service.ui().status()));
    }
    private byte[] read(InputStream in)throws IOException { ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray(); }
    private void shell(String command)throws Exception {
        try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){read(in);}
    }
    private String endpoint;
    private String http(String action,String args)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint+"/control/AVTransport").openConnection();
        c.setConnectTimeout(3000);c.setReadTimeout(6000);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Content-Type","text/xml; charset=utf-8");c.setRequestProperty("SOAPAction","\""+Dlna.type(Dlna.AV)+"#"+action+"\"");
        String body="<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:"+action+" xmlns:u=\""+Dlna.type(Dlna.AV)+"\"><InstanceID>0</InstanceID>"+args+"</u:"+action+"></s:Body></s:Envelope>";
        try {c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));int code=c.getResponseCode();String text=new String(read(code==200?c.getInputStream():c.getErrorStream()),StandardCharsets.UTF_8);if(code!=200)throw new AssertionError(action+": "+code+" "+text);return text;}finally{c.disconnect();}
    }
    private void source()throws Exception {
        http("SetAVTransportURI","<CurrentURI>http://10.0.2.2:18099/360/index.m3u8</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>");
        http("Play","<Speed>1</Speed>");
    }
    private boolean discover()throws Exception {
        String address=ui(()->service.ui().address());
        try(MulticastSocket socket=new MulticastSocket()){
            socket.setNetworkInterface(NetworkInterface.getByInetAddress(InetAddress.getByName(address)));socket.setSoTimeout(4000);
            byte[] data=("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: "+Dlna.DEVICE+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(data,data.length,InetAddress.getByName("239.255.255.250"),1900));
            byte[] reply=new byte[4096];long end=SystemClock.elapsedRealtime()+4000;
            while(SystemClock.elapsedRealtime()<end) {
                DatagramPacket packet=new DatagramPacket(reply,reply.length);socket.receive(packet);
                if(new String(reply,0,packet.getLength(),StandardCharsets.US_ASCII).contains("LOCATION: "+endpoint+"/description.xml"))return true;
            }
            return false;
        }
    }
    private boolean description() {
        try{HttpURLConnection c=(HttpURLConnection)new URL(endpoint+"/description.xml").openConnection();c.setConnectTimeout(1000);c.setReadTimeout(1000);try{return c.getResponseCode()==200 && new String(read(c.getInputStream()),StandardCharsets.UTF_8).contains("小米电视非官方投屏");}finally{c.disconnect();}}catch(IOException e){return false;}
    }
    private void home()throws Exception {shell("input keyevent 3");await("activity releases its playback surface on Home",()->ui(()->!service.hasPresentation()));}
    private void open()throws Exception {
        getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("opening app attaches the playback surface",()->ui(()->service.hasPresentation()));
    }
    private void shot(String name)throws Exception {
        File dir=new File(getTargetContext().getFilesDir(),"standby-tests");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name))){getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,out);}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();CountDownLatch bound=new CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName n,IBinder b){service=((ReceiverService.LocalBinder)b).get();bound.countDown();}
            @Override public void onServiceDisconnected(ComponentName n){}
        };
        boolean binding=false;
        try{
            shell("appops set cn.local.livingroomcast SYSTEM_ALERT_WINDOW deny");
            activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            binding=getTargetContext().bindService(new Intent(getTargetContext(),ReceiverService.class),connection,Context.BIND_AUTO_CREATE);
            check("service can be inspected",bound.await(10,TimeUnit.SECONDS));
            await("LAN receiver is ready",()->ui(()->!service.ui().endpoint().isEmpty()));endpoint=ui(()->service.ui().endpoint());
            getTargetContext().unbindService(connection);binding=false;
            check("receiver has its foreground notification",getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications().length==1);
            shot("等待页-后台待机.png");
            home();Thread.sleep(3500);
            check("HTTP receiver survives Home with no bound clients",description());
            check("real multicast discovery works in background",discover());
            source();Thread.sleep(800);
            check("background SOAP Play waits for a visible TV surface",http("GetTransportInfo","").contains("TRANSITIONING") && ui(()->!service.player().isPlaying() && service.player().getMediaItemCount()==0));
            check("no overlay permission is silently granted",!Settings.canDrawOverlays(getTargetContext()));
            http("Pause","");open();Thread.sleep(500);
            check("Pause cancels pending background playback",ui(()->!service.player().isPlaying()));
            ui(()->{service.stopPlayback();return null;});home();source();http("Stop","");open();
            check("Stop cancels a pending cast before opening",ui(()->!service.ui().video()));
            home();source();open();
            await("opening app plays the cast received in background",()->ui(()->service.player().isPlaying() && service.ui().quality().equals("640×360")));
            home();
            check("Home during playback stops hidden video and sound",ui(()->!service.player().isPlaying() && !service.ui().video()));
            check("ending playback leaves background discovery alive",discover());
            shell("appops set cn.local.livingroomcast SYSTEM_ALERT_WINDOW allow");
            check("auto-open permission is available in this test",Settings.canDrawOverlays(getTargetContext()));
            source();
            await("authorized background cast automatically opens playback",()->ui(()->service.hasPresentation() && service.player().isPlaying()));
            shot("后台收到投屏-自动播放.png");http("Stop","");
            check("sender Stop keeps the receiver available",description());
            ui(()->{activity.finish();return null;});
            await("finished activity detaches from the service",()->ui(()->!service.hasPresentation()));
            check("finished activity does not stop standby discovery",discover());
            Notification notice=getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications()[0].getNotification();
            check("foreground notification provides an exit action",notice.actions.length==1 && notice.actions[0].title.toString().equals("退出接收"));
            notice.actions[0].actionIntent.send();
            await("explicit exit closes the HTTP receiver",()->!description());Thread.sleep(600);
            check("explicit exit removes the foreground notification",getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications().length==0);
            Thread.sleep(2000);check("explicit exit does not immediately restart the service",!description());
            result.putString("stream",String.join("\n",checks)+"\nSTANDBY_ANDROID_OK: "+checks.size()+" checks\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));if(service!=null)trace.append("\nReceiver state: "+ui(()->service.ui().toString()));result.putString("stream",String.join("\n",checks)+"\n"+trace);finish(Activity.RESULT_CANCELED,result);}
        finally{try{shell("appops set cn.local.livingroomcast SYSTEM_ALERT_WINDOW default");}catch(Exception ignored){}if(binding)try{getTargetContext().unbindService(connection);}catch(Exception ignored){}if(activity!=null)runOnMainSync(()->activity.finish());}
    }
}
