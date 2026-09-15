package com.daniyal.videograb;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.webkit.URLUtil;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DownloadQueueService extends Service {
    private static final String CHANNEL="vg_queue"; private static final int NID=8810;
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private static class Paused extends Exception{} private static class Canceled extends Exception{}

    public static void enqueue(Context c,DownloadTask t){t.updatedAt=System.currentTimeMillis();DownloadTaskStore.upsert(c,t);kick(c);}
    public static void pause(Context c,String id){DownloadTask t=DownloadTaskStore.get(c,id);if(t!=null){t.status=DownloadTask.STATUS_PAUSED;t.speedBps=0;t.updatedAt=System.currentTimeMillis();DownloadTaskStore.upsert(c,t);if(DownloadTask.KIND_DASH.equals(t.kind))FFmpegKit.cancel();}}
    public static void resume(Context c,String id){DownloadTask t=DownloadTaskStore.get(c,id);if(t!=null){t.status=DownloadTask.STATUS_QUEUED;t.error="";t.speedBps=0;t.updatedAt=System.currentTimeMillis();DownloadTaskStore.upsert(c,t);kick(c);}}
    public static void cancel(Context c,String id){DownloadTask t=DownloadTaskStore.get(c,id);if(t!=null){t.status=DownloadTask.STATUS_CANCELED;t.speedBps=0;t.updatedAt=System.currentTimeMillis();DownloadTaskStore.upsert(c,t);if(DownloadTask.KIND_DASH.equals(t.kind))FFmpegKit.cancel();if(!t.tempPath.isEmpty())new File(t.tempPath).delete();}}
    public static void kick(Context c){c.startForegroundService(new Intent(c,DownloadQueueService.class));}

    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"VideoGrab downloads",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent i,int f,int id){startForeground(NID,note("Checking queue",0,true,0));if(RUNNING.compareAndSet(false,true))new Thread(()->work(id),"vg-queue").start();return START_NOT_STICKY;}

    private void work(int startId){try{while(true){DownloadTask t=next();if(t==null)break;t.status=DownloadTask.STATUS_DOWNLOADING;t.updatedAt=System.currentTimeMillis();DownloadTaskStore.upsert(this,t);try{if(DownloadTask.KIND_HLS.equals(t.kind))hls(t);else if(DownloadTask.KIND_DASH.equals(t.kind))dash(t);else direct(t);}catch(Paused|Canceled ignored){}catch(Exception e){DownloadTask x=DownloadTaskStore.get(this,t.id);if(x!=null&&!DownloadTask.STATUS_PAUSED.equals(x.status)&&!DownloadTask.STATUS_CANCELED.equals(x.status)){x.status=DownloadTask.STATUS_FAILED;x.speedBps=0;x.updatedAt=System.currentTimeMillis();x.error=shortMsg(e.getMessage());DownloadTaskStore.upsert(this,x);}}}}finally{RUNNING.set(false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);if(next()!=null)kick(this);}}
    private DownloadTask next(){DownloadTask best=null;for(DownloadTask t:DownloadTaskStore.list(this))if(DownloadTask.STATUS_QUEUED.equals(t.status)&&(best==null||t.createdAt<best.createdAt))best=t;return best;}

    private void direct(DownloadTask t)throws Exception{
        File file=temp(t,".part");long off=file.exists()?file.length():0;HttpURLConnection c=HlsInspector.open(t.url,t.cookie,t.userAgent,t.referer);if(off>0)c.setRequestProperty("Range","bytes="+off+"-");int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);if(off>0&&code!=206){off=0;new RandomAccessFile(file,"rw").setLength(0);}long len=c.getContentLengthLong();t.total=len>0?off+len:-1;t.downloaded=off;String ct=c.getContentType();if(ct!=null)t.mime=ct.split(";")[0];DownloadTaskStore.upsert(this,t);
        long lastTime=System.currentTimeMillis(),lastBytes=t.downloaded;
        try(InputStream in=c.getInputStream();RandomAccessFile out=new RandomAccessFile(file,"rw")){out.seek(off);byte[] b=new byte[131072];int n;while((n=in.read(b))>=0){check(t.id);out.write(b,0,n);t.downloaded+=n;if(t.total>0)t.progress=(int)Math.min(94,t.downloaded*94/t.total);long now=System.currentTimeMillis();if(now-lastTime>=700){t.speedBps=Math.max(0,(t.downloaded-lastBytes)*1000L/Math.max(1,now-lastTime));t.updatedAt=now;DownloadTaskStore.upsert(this,t);notifyTask(t);lastTime=now;lastBytes=t.downloaded;}}}finally{c.disconnect();}finish(t,file,outputName(t));
    }

    private void hls(DownloadTask t)throws Exception{
        String p=HlsInspector.fetchText(t.url,t.cookie,t.userAgent,t.referer);if(p.contains("#EXT-X-STREAM-INF")){List<HlsInspector.Option> os=HlsInspector.inspect(t.url,t.cookie,t.userAgent,t.referer);t.url=os.get(0).url;p=HlsInspector.fetchText(t.url,t.cookie,t.userAgent,t.referer);}String up=p.toUpperCase(Locale.ROOT);if(up.contains("#EXT-X-KEY")&&!up.contains("METHOD=NONE"))throw new IOException("Encrypted HLS not supported");if(!up.contains("#EXT-X-ENDLIST"))throw new IOException("Live HLS not supported");if(up.contains("#EXT-X-BYTERANGE"))throw new IOException("Byte-range HLS not supported");
        List<String>s=new ArrayList<>();String map=extractMapUri(p);if(map!=null)s.add(URI.create(t.url).resolve(map).toString());for(String l:p.split("\\r?\\n")){l=l.trim();if(!l.isEmpty()&&!l.startsWith("#"))s.add(URI.create(t.url).resolve(l).toString());}if(s.isEmpty())throw new IOException("No HLS media segments");boolean mp4=map!=null||p.contains("#EXT-X-MAP");File file=temp(t,mp4?".mp4.part":".ts.part");long lastTime=System.currentTimeMillis(),lastBytes=file.exists()?file.length():0;t.downloaded=lastBytes;
        for(int i=t.segmentIndex;i<s.size();i++){check(t.id);appendWithRetry(s.get(i),file,t);t.segmentIndex=i+1;t.downloaded=file.length();t.progress=(int)((i+1)*94L/s.size());long now=System.currentTimeMillis();t.speedBps=Math.max(0,(t.downloaded-lastBytes)*1000L/Math.max(1,now-lastTime));t.updatedAt=now;lastTime=now;lastBytes=t.downloaded;DownloadTaskStore.upsert(this,t);notifyTask(t);}t.mime=mp4?"video/mp4":"video/mp2t";finish(t,file,MediaPublisher.stripExtension(t.name)+(mp4?".mp4":".ts"));
    }

    private void appendWithRetry(String url,File file,DownloadTask t)throws Exception{
        Exception last=null;for(int attempt=0;attempt<3;attempt++){HttpURLConnection c=null;try{c=HlsInspector.open(url,t.cookie,t.userAgent,t.referer);int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HLS segment HTTP "+code);try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(file,true)){byte[] b=new byte[131072];int n;while((n=in.read(b))>=0){check(t.id);out.write(b,0,n);}}return;}catch(Paused|Canceled e){throw e;}catch(Exception e){last=e;if(attempt<2)Thread.sleep(450L*(attempt+1));}finally{if(c!=null)c.disconnect();}}throw last==null?new IOException("HLS segment failed"):last;
    }

    private String extractMapUri(String playlist){for(String line:playlist.split("\\r?\\n")){line=line.trim();if(!line.startsWith("#EXT-X-MAP:"))continue;int p=line.indexOf("URI=\"");if(p<0)continue;int s=p+5;int e=line.indexOf('"',s);if(e>s)return line.substring(s,e);}return null;}

    private void dash(DownloadTask t)throws Exception{
        check(t.id);String mpd=HlsInspector.fetchText(t.url,t.cookie,t.userAgent,t.referer).toLowerCase(Locale.ROOT);if(mpd.contains("<contentprotection")||mpd.contains("urn:mpeg:cenc")||mpd.contains("widevine")||mpd.contains("playready"))throw new IOException("DRM/protected DASH is not supported");File file=temp(t,".dash.mp4");if(file.exists())file.delete();t.progress=8;t.updatedAt=System.currentTimeMillis();DownloadTaskStore.upsert(this,t);notifyTask(t);String headers=DashInspector.headers(t.cookie,t.userAgent,t.referer);StringBuilder cmd=new StringBuilder("-hide_banner -loglevel error -y ");if(!headers.isEmpty())cmd.append("-headers ").append(DashInspector.q(headers)).append(' ');cmd.append("-i ").append(DashInspector.q(t.url)).append(' ');if(DownloadTask.MODE_MP3.equals(t.mode)){if(t.dashAudioStream<0)throw new IOException("No audio track found in DASH manifest");cmd.append("-map 0:").append(t.dashAudioStream).append(" -vn -c:a copy ").append(DashInspector.q(file.getAbsolutePath()));}else{if(t.dashVideoStream<0)throw new IOException("No video track selected");cmd.append("-map 0:").append(t.dashVideoStream).append(' ');if(t.dashAudioStream>=0)cmd.append("-map 0:").append(t.dashAudioStream).append(' ');cmd.append("-c copy -movflags +faststart ").append(DashInspector.q(file.getAbsolutePath()));}t.progress=20;t.updatedAt=System.currentTimeMillis();DownloadTaskStore.upsert(this,t);notifyTask(t);FFmpegSession session=FFmpegKit.execute(cmd.toString());DownloadTask state=DownloadTaskStore.get(this,t.id);if(state==null||DownloadTask.STATUS_CANCELED.equals(state.status))throw new Canceled();if(DownloadTask.STATUS_PAUSED.equals(state.status))throw new Paused();if(!ReturnCode.isSuccess(session.getReturnCode())){String logs=session.getAllLogsAsString();if(logs!=null&&logs.length()>220)logs=logs.substring(logs.length()-220);throw new IOException("DASH merge failed"+(logs==null||logs.trim().isEmpty()?"":": "+logs.trim()));}t.progress=92;t.mime="video/mp4";t.downloaded=file.length();t.updatedAt=System.currentTimeMillis();DownloadTaskStore.upsert(this,t);notifyTask(t);finish(t,file,MediaPublisher.stripExtension(t.name)+".mp4");
    }

    private void finish(DownloadTask t,File f,String name)throws Exception{check(t.id);t.progress=96;t.speedBps=0;t.updatedAt=System.currentTimeMillis();DownloadTaskStore.upsert(this,t);Uri u;if(DownloadTask.MODE_MP3.equals(t.mode))u=MediaPublisher.convertFileToMp3(this,f,t.name,t.mp3Bitrate);else u=MediaPublisher.publish(this,f,name,t.mime);f.delete();t.outputUri=u.toString();t.progress=100;t.status=DownloadTask.STATUS_COMPLETED;t.speedBps=0;t.updatedAt=System.currentTimeMillis();t.mime=DownloadTask.MODE_MP3.equals(t.mode)?"audio/mpeg":t.mime;DownloadTaskStore.upsert(this,t);getSystemService(NotificationManager.class).notify((int)(System.currentTimeMillis()&0x7fffffff),doneNote(t.name));}
    private void check(String id)throws Paused,Canceled{DownloadTask x=DownloadTaskStore.get(this,id);if(x==null||DownloadTask.STATUS_CANCELED.equals(x.status))throw new Canceled();if(DownloadTask.STATUS_PAUSED.equals(x.status))throw new Paused();}
    private File temp(DownloadTask t,String ext){if(!t.tempPath.isEmpty())return new File(t.tempPath);File d=new File(getFilesDir(),"queue");d.mkdirs();File f=new File(d,t.id+ext);t.tempPath=f.getAbsolutePath();DownloadTaskStore.upsert(this,t);return f;}
    private String outputName(DownloadTask t){String g=URLUtil.guessFileName(t.url,null,t.mime);int p=g.lastIndexOf('.');String ext=p>=0?g.substring(p):".mp4";return MediaPublisher.stripExtension(t.name)+ext;}
    private void notifyTask(DownloadTask t){String s=t.name+" • "+t.progress+"%"+(t.speedBps>0?" • "+speed(t.speedBps):"");getSystemService(NotificationManager.class).notify(NID,note(s,t.progress,false,t.speedBps));}
    private PendingIntent managerIntent(){Intent i=new Intent(this,MainActivity.class).putExtra(MainActivity.EXTRA_SCREEN,"downloads").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);return PendingIntent.getActivity(this,40,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification note(String s,int p,boolean ind,long speed){Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_download).setContentTitle("VideoGrab").setContentText(s).setContentIntent(managerIntent()).setOngoing(true).setOnlyAlertOnce(true);b.setProgress(100,p,ind);return b.build();}
    private Notification doneNote(String s){return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_download).setContentTitle("Download complete").setContentText(s).setContentIntent(managerIntent()).setAutoCancel(true).build();}
    private String speed(long n){if(n<1048576)return String.format(Locale.US,"%.0f KB/s",n/1024.0);return String.format(Locale.US,"%.1f MB/s",n/1048576.0);}
    private String shortMsg(String s){if(s==null||s.isEmpty())return"Unknown error";return s.length()>100?s.substring(0,100):s;}
    @Override public IBinder onBind(Intent i){return null;}
}
