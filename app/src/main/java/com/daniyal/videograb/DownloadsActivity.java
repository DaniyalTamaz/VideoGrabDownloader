package com.daniyal.videograb;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;

public class DownloadsActivity extends Activity {
    private LinearLayout content; private boolean queue=true; private final Handler h=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){if(queue)renderQueue();h.postDelayed(this,1200);}};
    static class F{long id,size,date;String name,mime;Uri uri;}

    @Override protected void onCreate(Bundle b){super.onCreate(b);build();renderQueue();}
    @Override protected void onResume(){super.onResume();h.removeCallbacks(refresh);h.post(refresh);}
    @Override protected void onPause(){h.removeCallbacks(refresh);super.onPause();}

    private void build(){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(245,247,250));
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(8),dp(8),dp(10),dp(8));head.setBackgroundColor(Color.rgb(24,28,36));Button back=new Button(this);back.setText("‹");TextView title=new TextView(this);title.setText("Downloads");title.setTextColor(Color.WHITE);title.setTextSize(20);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);head.addView(back,new LinearLayout.LayoutParams(dp(52),dp(48)));head.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(head);
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(dp(8),dp(8),dp(8),dp(8));Button q=new Button(this);q.setText("Queue");q.setAllCaps(false);Button l=new Button(this);l.setText("Library");l.setAllCaps(false);tabs.addView(q,new LinearLayout.LayoutParams(0,dp(48),1));tabs.addView(l,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(tabs);
        ScrollView s=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(10),dp(8),dp(10),dp(16));s.addView(content);root.addView(s,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        back.setOnClickListener(v->finish());q.setOnClickListener(v->{queue=true;renderQueue();});l.setOnClickListener(v->{queue=false;renderLibrary();});}

    private void renderQueue(){if(!queue)return;content.removeAllViews();List<DownloadTask> list=DownloadTaskStore.list(this);if(list.isEmpty()){content.addView(empty("No downloads in the queue yet."));return;}for(DownloadTask t:list)content.addView(taskCard(t));}
    private View taskCard(DownloadTask t){LinearLayout c=card();c.addView(title(t.name));String mode=DownloadTask.MODE_MP3.equals(t.mode)?"MP3 "+t.mp3Bitrate+" kbps":t.quality;c.addView(meta(mode+" • "+t.shortStatus()));ProgressBar p=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);p.setMax(100);p.setProgress(t.progress);c.addView(p,new LinearLayout.LayoutParams(-1,dp(8)));if(DownloadTask.STATUS_FAILED.equals(t.status)&&!t.error.isEmpty()){TextView e=meta(t.error);e.setTextColor(Color.rgb(185,45,45));c.addView(e);}LinearLayout a=new LinearLayout(this);Button main=new Button(this);main.setAllCaps(false);main.setText(action(t));Button remove=new Button(this);remove.setAllCaps(false);remove.setText("Remove");a.addView(main,new LinearLayout.LayoutParams(0,dp(46),1));a.addView(remove,new LinearLayout.LayoutParams(0,dp(46),1));c.addView(a);main.setOnClickListener(v->act(t));remove.setOnClickListener(v->{if(DownloadTask.STATUS_DOWNLOADING.equals(t.status)||DownloadTask.STATUS_QUEUED.equals(t.status)||DownloadTask.STATUS_PAUSED.equals(t.status))DownloadQueueService.cancel(this,t.id);DownloadTaskStore.remove(this,t.id);renderQueue();});return c;}
    private String action(DownloadTask t){if(DownloadTask.STATUS_DOWNLOADING.equals(t.status)||DownloadTask.STATUS_QUEUED.equals(t.status))return"Pause";if(DownloadTask.STATUS_PAUSED.equals(t.status))return"Resume";if(DownloadTask.STATUS_COMPLETED.equals(t.status))return"Open";return"Retry";}
    private void act(DownloadTask t){if(DownloadTask.STATUS_DOWNLOADING.equals(t.status)||DownloadTask.STATUS_QUEUED.equals(t.status))DownloadQueueService.pause(this,t.id);else if(DownloadTask.STATUS_PAUSED.equals(t.status)||DownloadTask.STATUS_FAILED.equals(t.status)||DownloadTask.STATUS_CANCELED.equals(t.status))DownloadQueueService.resume(this,t.id);else if(DownloadTask.STATUS_COMPLETED.equals(t.status)&&!t.outputUri.isEmpty())open(Uri.parse(t.outputUri),t.mime,t.name);renderQueue();}

    private void renderLibrary(){content.removeAllViews();List<F> fs=files();if(fs.isEmpty()){content.addView(empty("Completed videos and MP3 files will appear here."));return;}for(F f:fs)content.addView(fileCard(f));}
    private View fileCard(F f){LinearLayout c=card();c.addView(title(f.name));String when=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(f.date*1000));c.addView(meta(size(f.size)+" • "+when));c.setOnClickListener(v->menu(f));return c;}
    private void menu(F f){boolean mp3=(f.mime!=null&&f.mime.startsWith("audio/"))||f.name.toLowerCase().endsWith(".mp3");List<String>a=new ArrayList<>();a.add("Play in VideoGrab");if(!mp3)a.add("Convert to MP3");a.add("Share");a.add("Delete");new AlertDialog.Builder(this).setTitle(f.name).setItems(a.toArray(new String[0]),(d,w)->{String x=a.get(w);if(x.startsWith("Play"))open(f.uri,f.mime,f.name);else if(x.startsWith("Convert"))convert(f);else if(x.equals("Share")){Intent i=new Intent(Intent.ACTION_SEND);i.setType(f.mime==null?"*/*":f.mime);i.putExtra(Intent.EXTRA_STREAM,f.uri);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"Share"));}else{getContentResolver().delete(f.uri,null,null);renderLibrary();}}).setNegativeButton("Close",null).show();}
    private void convert(F f){String[] r={"128 kbps","192 kbps","320 kbps"};int[] v={128,192,320};new AlertDialog.Builder(this).setTitle("Convert to MP3").setItems(r,(d,w)->{Intent i=new Intent(this,AudioConvertService.class);i.putExtra(AudioConvertService.EXTRA_URI,f.uri.toString());i.putExtra(AudioConvertService.EXTRA_NAME,f.name);i.putExtra(AudioConvertService.EXTRA_BITRATE,v[w]);startForegroundService(i);Toast.makeText(this,"MP3 conversion started",Toast.LENGTH_SHORT).show();}).show();}
    private void open(Uri u,String mime,String name){Intent i=new Intent(this,PlayerActivity.class);i.putExtra(PlayerActivity.EXTRA_URI,u.toString());i.putExtra(PlayerActivity.EXTRA_MIME,mime);i.putExtra(PlayerActivity.EXTRA_NAME,name);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(i);}

    private List<F> files(){List<F>o=new ArrayList<>();String[]p={MediaStore.Downloads._ID,MediaStore.Downloads.DISPLAY_NAME,MediaStore.Downloads.SIZE,MediaStore.Downloads.DATE_MODIFIED,MediaStore.Downloads.MIME_TYPE};try(Cursor c=getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,p,MediaStore.Downloads.RELATIVE_PATH+" LIKE ?",new String[]{"Download/VideoGrab%"},MediaStore.Downloads.DATE_MODIFIED+" DESC")){if(c==null)return o;while(c.moveToNext()){F f=new F();f.id=c.getLong(0);f.name=c.getString(1);f.size=c.getLong(2);f.date=c.getLong(3);f.mime=c.getString(4);f.uri=android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,f.id);o.add(f);}}catch(Exception ignored){}return o;}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(12),dp(12),dp(12),dp(12));c.setBackgroundColor(Color.WHITE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(9));c.setLayoutParams(lp);return c;}
    private TextView title(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(15);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private TextView meta(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(12);t.setTextColor(Color.rgb(100,107,118));t.setPadding(0,dp(4),0,dp(6));return t;}
    private TextView empty(String s){TextView t=meta(s);t.setTextSize(15);t.setGravity(Gravity.CENTER);t.setPadding(dp(20),dp(60),dp(20),dp(40));return t;}
    private String size(long n){if(n<1024)return n+" B";if(n<1048576)return String.format("%.1f KB",n/1024.0);if(n<1073741824)return String.format("%.1f MB",n/1048576.0);return String.format("%.2f GB",n/1073741824.0);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
