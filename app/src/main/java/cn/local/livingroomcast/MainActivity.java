/*
 * Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package cn.local.livingroomcast;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.ui.PlayerView;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(16,27,32), MINT = Color.rgb(140,232,195), WHITE = Color.rgb(239,246,243), MUTED = Color.rgb(173,193,193);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ReceiverService service;
    private boolean bound, video;
    private PlayerView playerView;
    private LinearLayout waiting, hud;
    private TextView status, network, progress, playbackTitle, playbackStatus, playbackTime;
    private Button pause;
    private String lastStatus = "";
    private final Runnable hideHud = () -> { if (service != null && service.player().isPlaying()) hud.setVisibility(View.GONE); };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ReceiverService.LocalBinder)binder).get(); playerView.setPlayer(service.player()); service.observe(MainActivity.this::render);
        }
        @Override public void onServiceDisconnected(ComponentName name) { service=null; status.setText("接收服务已停止，请退出后重新打开"); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(INK); setContentView(root);
        playerView = new PlayerView(this); playerView.setUseController(false); playerView.setKeepContentOnPlayerReset(false); playerView.setFocusable(false);
        root.addView(playerView, new FrameLayout.LayoutParams(-1,-1));

        waiting = column(); waiting.setPadding(dp(48),dp(25),dp(48),dp(22));
        root.addView(waiting,new FrameLayout.LayoutParams(-1,-1));
        TextView eyebrow = text("LIVING ROOM  /  直播接收验证 0.1.3",12,MINT); eyebrow.setLetterSpacing(.10f); waiting.addView(eyebrow);
        TextView title = text("小狗便利店投屏",36,WHITE); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); add(waiting,title,12);
        TextView instruction = text("在平板的软件中点TV/投屏，选择“小狗便利店投屏”",19,WHITE); add(waiting,instruction,9);
        network = text("正在检查电视网络…",14,MUTED); add(waiting,network,13);

        LinearLayout card=column(); card.setPadding(dp(18),dp(12),dp(18),dp(12)); card.setBackground(background(0xff1d3038,12));
        status=text("正在启动接收服务",21,MINT); card.addView(status);
        progress=text("首次使用：平板和电视连接同一家庭网络。",14,MUTED); progress.setMaxLines(4); add(card,progress,7);
        add(waiting,card,18);
        Space space = new Space(this); waiting.addView(space,new LinearLayout.LayoutParams(1,0,1));
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button details=button("连接详情",v->details()); buttons.addView(details);
        Button exit=button("退出接收",v->confirmExit()); LinearLayout.LayoutParams exitParams=new LinearLayout.LayoutParams(-2,dp(48)); exitParams.leftMargin=dp(12); buttons.addView(exit,exitParams);
        waiting.addView(buttons);
        TextView note=text("测试时请保持本应用打开。按返回键结束播放，再按返回可退出接收。",12,MUTED); add(waiting,note,8);

        hud=column(); hud.setPadding(dp(36),dp(24),dp(36),dp(24)); hud.setBackgroundColor(0x99081016);
        root.addView(hud,new FrameLayout.LayoutParams(-1,-1)); hud.setVisibility(View.GONE);
        playbackTitle=text("小狗便利店投屏",25,WHITE); playbackTitle.setMaxLines(2); hud.addView(playbackTitle);
        playbackStatus=text("正在连接直播",17,MINT); add(hud,playbackStatus,8);
        playbackTime=text("",13,MUTED); add(hud,playbackTime,6);
        hud.addView(new Space(this),new LinearLayout.LayoutParams(1,0,1));
        LinearLayout controls=new LinearLayout(this); controls.setOrientation(LinearLayout.HORIZONTAL);
        pause=button("暂停 / 继续",v->{if(service!=null)service.togglePause();showHud();}); controls.addView(pause);
        Button diagnostics=button("连接详情",v->details()); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,dp(48)); cp.leftMargin=dp(12); controls.addView(diagnostics,cp);
        Button disconnect=button("结束投屏",v->{if(service!=null)service.stopPlayback();}); LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-2,dp(48));dp.leftMargin=dp(12);controls.addView(disconnect,dp);
        hud.addView(controls); add(hud,text("方向键唤出操作栏 · 返回键结束本次播放",12,MUTED),10);
        details.requestFocus();
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},21);
    }
    @Override protected void onStart() {
        super.onStart();
        Intent intent=new Intent(this,ReceiverService.class);
        try {
            if(Build.VERSION.SDK_INT>=26)startForegroundService(intent); else startService(intent);
            bound=bindService(intent,connection,BIND_AUTO_CREATE);
        } catch(RuntimeException e) { status.setText("启动失败，请退出重开"); progress.setText(e.getClass().getSimpleName()); }
    }
    private void render() {
        if(service==null)return;
        ReceiverService.Ui ui=service.ui();
        status.setText(ui.status());
        network.setText(ui.address().isEmpty()?"请连接家庭 Wi-Fi，或将电视接入同一路由器的网线。":"电视地址  " + ui.address() + "    ·    DLNA 接收");
        String summary = "已收到 " + ui.received() + " 次播放地址";
        progress.setText(ui.error().isEmpty()?summary + "\n若设备列表没有本机，请先结束当贝接收，再重新搜索。":ui.error());
        playbackTitle.setText(ui.title());
        playbackStatus.setText(ui.status() + (ui.error().isEmpty()?"":"\n" + ui.error()));
        playbackTime.setText("本次连接  " + ui.elapsed());
        boolean changed=video!=ui.video(); video=ui.video();
        waiting.setVisibility(video?View.GONE:View.VISIBLE);
        if(video) {
            if(changed || !lastStatus.equals(ui.status())) { showHud(); pause.requestFocus(); }
            if(!service.player().isPlaying()) { handler.removeCallbacks(hideHud); hud.setVisibility(View.VISIBLE); }
        } else { hud.setVisibility(View.GONE); if(changed)waiting.getChildAt(waiting.getChildCount()-2).requestFocus(); }
        lastStatus=ui.status();
    }
    private void showHud() { if(!video)return; hud.setVisibility(View.VISIBLE); handler.removeCallbacks(hideHud); handler.postDelayed(hideHud,6000); }
    private void details() {
        handler.removeCallbacks(hideHud);
        String message="等待接收服务启动。";
        if(service!=null) {
            ReceiverService.Ui u=service.ui();
            message="小狗便利店投屏 0.1.3-test\n电视：" + Build.MANUFACTURER + " " + Build.MODEL + "\nAndroid " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                    + "\n接收器：" + (u.endpoint().isEmpty()?"尚未就绪":u.endpoint())
                    + "\n收到地址：" + u.received() + " 次\n本次连接：" + u.elapsed()
                    + "\n\n排查顺序：\n1. 没收到搜索：检查同一网络、访客网络隔离。\n2. 能被搜索但没收到地址：可能存在发送端兼容限制。\n3. 已收到地址但不能播放：看下面错误代码。\n\n" + u.logs()
                    + "\n\n记录只保存在本次运行的内存中；不显示完整视频地址或登录信息。\n本地网页可打开只表示网络可达，实际投屏仍需测试。";
        }
        ScrollView scroll=new ScrollView(this); TextView content=text(message,14,WHITE); content.setPadding(dp(24),dp(12),dp(24),dp(20)); scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("连接详情").setView(scroll).setPositiveButton("关闭",(d,w)->showHud()).create();
        dialog.setOnDismissListener(d->showHud()); dialog.show();
        dialog.getWindow().setLayout(dp(660),dp(390));
    }
    private void confirmExit() { new AlertDialog.Builder(this).setTitle("退出接收？").setMessage("退出后平板将无法发现本接收器。再次打开即可恢复。").setPositiveButton("退出",(d,w)->finish()).setNegativeButton("继续接收",null).show(); }
    @Override public boolean onKeyDown(int code,KeyEvent event) {
        if(code==KeyEvent.KEYCODE_BACK){if(service!=null && service.ui().video())service.stopPlayback();else confirmExit();return true;}
        if(video && (code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code==KeyEvent.KEYCODE_MEDIA_PAUSE || code==KeyEvent.KEYCODE_MEDIA_PLAY)) {
            if(service!=null)service.togglePause();showHud();return true;
        }
        if(video && (code==KeyEvent.KEYCODE_DPAD_CENTER || code==KeyEvent.KEYCODE_ENTER || code==KeyEvent.KEYCODE_DPAD_UP || code==KeyEvent.KEYCODE_DPAD_DOWN || code==KeyEvent.KEYCODE_DPAD_LEFT || code==KeyEvent.KEYCODE_DPAD_RIGHT)) {
            boolean hidden=hud.getVisibility()!=View.VISIBLE;showHud();if(hidden){pause.requestFocus();return true;}
        }
        return super.onKeyDown(code,event);
    }
    @Override protected void onStop() {
        handler.removeCallbacksAndMessages(null);
        playerView.setPlayer(null); if(service!=null)service.observe(null);
        if(bound){unbindService(connection);bound=false;}
        service=null;
        if(!isChangingConfigurations())stopService(new Intent(this,ReceiverService.class));
        super.onStop();
    }
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(2),1);return v;}
    private Button button(String label,View.OnClickListener click){Button b=new Button(this);b.setText(label);b.setTextSize(15);b.setTextColor(WHITE);b.setAllCaps(false);b.setPadding(dp(20),0,dp(20),0);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setOnClickListener(click);b.setOnFocusChangeListener((v,focus)->{b.setTextColor(focus?INK:WHITE);b.setBackground(background(focus?MINT:0xff2b414b,8));});b.setBackground(background(0xff2b414b,8));return b;}
    private GradientDrawable background(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private void add(LinearLayout parent,View child,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(child,p);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
