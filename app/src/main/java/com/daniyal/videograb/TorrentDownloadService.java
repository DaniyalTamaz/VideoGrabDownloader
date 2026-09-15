package com.daniyal.videograb;

import android.app.*;
import android.content.*;
import android.os.*;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.ErrorCode;
import org.libtorrent4j.SessionHandle;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.swig.error_code;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class TorrentDownloadService extends Service {
    private static final String CHANNEL = "vg_torrent";
    private static final int NID = 8840;
    private static final String ACTION_PAUSE = "vg.torrent.PAUSE";
    private static final String ACTION_RESUME = "vg.torrent.RESUME";
    private static final String ACTION_CANCEL = "vg.torrent.CANCEL";
    private static final String EXTRA_ID = "id";
    private static final SessionManager MANAGER = new SessionManager(false);
    private static final Map<String, TorrentHandle> HANDLES = new ConcurrentHashMap<>();
    private static final AtomicBoolean LOOP = new AtomicBoolean(false);

    public static void enqueue(Context c, DownloadTask t) {
        t.kind = DownloadTask.KIND_TORRENT;
        t.mime = "application/x-videograb-torrent";
        t.updatedAt = System.currentTimeMillis();
        DownloadTaskStore.upsert(c, t);
        kick(c, null, t.id);
    }

    public static void pause(Context c, String id) { kick(c, ACTION_PAUSE, id); }
    public static void resume(Context c, String id) { kick(c, ACTION_RESUME, id); }
    public static void cancel(Context c, String id) { kick(c, ACTION_CANCEL, id); }

    private static void kick(Context c, String action, String id) {
        Intent i = new Intent(c, TorrentDownloadService.class);
        if (action != null) i.setAction(action);
        if (id != null) i.putExtra(EXTRA_ID, id);
        c.startForegroundService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "VideoGrab torrents", NotificationManager.IMPORTANCE_LOW));
        synchronized (MANAGER) { if (!MANAGER.isRunning()) MANAGER.start(); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NID, note("Torrent engine ready", 0, true));
        if (intent != null && intent.getAction() != null) applyAction(intent.getAction(), intent.getStringExtra(EXTRA_ID));
        if (LOOP.compareAndSet(false, true)) new Thread(() -> loop(startId), "vg-torrent-loop").start();
        return START_STICKY;
    }

    private void applyAction(String action, String id) {
        if (id == null) return;
        DownloadTask t = DownloadTaskStore.get(this, id);
        if (t == null || !DownloadTask.KIND_TORRENT.equals(t.kind)) return;
        TorrentHandle h = HANDLES.get(id);
        if (ACTION_PAUSE.equals(action)) {
            t.status = DownloadTask.STATUS_PAUSED; t.speedBps = 0; t.updatedAt = System.currentTimeMillis();
            DownloadTaskStore.upsert(this, t); if (valid(h)) h.pause();
        } else if (ACTION_RESUME.equals(action)) {
            t.status = DownloadTask.STATUS_QUEUED; t.error = ""; t.updatedAt = System.currentTimeMillis();
            DownloadTaskStore.upsert(this, t); if (valid(h)) h.resume();
        } else if (ACTION_CANCEL.equals(action)) {
            t.status = DownloadTask.STATUS_CANCELED; t.speedBps = 0; t.updatedAt = System.currentTimeMillis();
            DownloadTaskStore.upsert(this, t);
            if (valid(h)) MANAGER.remove(h);
            HANDLES.remove(id);
            TorrentStorage.deleteTaskData(this, id);
        }
    }

    private void loop(int startId) {
        try {
            int idleRounds = 0;
            while (true) {
                boolean any = false;
                List<DownloadTask> tasks = DownloadTaskStore.list(this);
                for (DownloadTask t : tasks) {
                    if (!DownloadTask.KIND_TORRENT.equals(t.kind)) continue;
                    if (DownloadTask.STATUS_COMPLETED.equals(t.status) || DownloadTask.STATUS_FAILED.equals(t.status) || DownloadTask.STATUS_CANCELED.equals(t.status)) continue;
                    any = true;
                    try { updateTorrent(t); }
                    catch (Throwable e) { fail(t, e); }
                }
                if (!any) {
                    idleRounds++;
                    if (idleRounds >= 3) break;
                } else idleRounds = 0;
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
            }
        } finally {
            LOOP.set(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            if (hasRunnableTorrent()) kick(this, null, null);
        }
    }

    private boolean hasRunnableTorrent() {
        for (DownloadTask t : DownloadTaskStore.list(this)) {
            if (!DownloadTask.KIND_TORRENT.equals(t.kind)) continue;
            if (DownloadTask.STATUS_QUEUED.equals(t.status) || DownloadTask.STATUS_DOWNLOADING.equals(t.status)) return true;
        }
        return false;
    }

    private void updateTorrent(DownloadTask t) throws Exception {
        TorrentHandle h = HANDLES.get(t.id);
        if (!valid(h) && !DownloadTask.STATUS_PAUSED.equals(t.status)) {
            h = add(t);
            HANDLES.put(t.id, h);
        }
        if (!valid(h)) return;

        if (DownloadTask.STATUS_PAUSED.equals(t.status)) { h.pause(); return; }
        if (DownloadTask.STATUS_QUEUED.equals(t.status)) { h.resume(); t.status = DownloadTask.STATUS_DOWNLOADING; }

        TorrentStatus s = h.status(true);
        if (s.errorCode() != null && s.errorCode().isError()) throw new IllegalStateException(s.errorCode().getMessage());
        TorrentInfo info = h.torrentFile();
        if (info != null && info.isValid()) {
            if (info.name() != null && !info.name().trim().isEmpty()) t.name = MediaPublisher.sanitize(info.name());
            t.torrentFiles = info.numFiles();
        } else if (s.name() != null && !s.name().trim().isEmpty() && !s.name().toLowerCase(Locale.ROOT).startsWith("magnet")) {
            t.name = MediaPublisher.sanitize(s.name());
        }

        t.downloaded = Math.max(0, s.totalWantedDone());
        t.total = s.totalWanted() > 0 ? s.totalWanted() : s.total();
        t.speedBps = Math.max(0, s.downloadPayloadRate());
        t.torrentPeers = Math.max(0, s.numPeers());
        t.torrentSeeds = Math.max(0, s.numSeeds());
        t.progress = t.total > 0 ? (int)Math.min(100, t.downloaded * 100L / t.total) : Math.max(0, Math.min(99, Math.round(s.progress() * 100f)));
        t.updatedAt = System.currentTimeMillis();

        if (s.isFinished()) {
            t.progress = 100;
            t.status = DownloadTask.STATUS_COMPLETED;
            t.speedBps = 0;
            t.outputUri = "videograb-torrent://" + t.id;
            t.mime = "application/x-videograb-torrent";
            DownloadTaskStore.upsert(this, t);
            h.pause();
            MANAGER.remove(h);
            HANDLES.remove(t.id);
            getSystemService(NotificationManager.class).notify((int)(System.currentTimeMillis() & 0x7fffffff), doneNote(t));
            return;
        }

        DownloadTaskStore.upsert(this, t);
        getSystemService(NotificationManager.class).notify(NID, note(t.name + " • " + t.progress + "% • " + t.torrentPeers + " peers" + (t.speedBps > 0 ? " • " + speed(t.speedBps) : ""), t.progress, t.total <= 0));
    }

    private TorrentHandle add(DownloadTask t) throws Exception {
        File saveDir = TorrentStorage.taskDir(this, t.id);
        AddTorrentParams p;
        if (t.url != null && t.url.startsWith("magnet:?")) {
            p = AddTorrentParams.parseMagnetUri(t.url);
            if ((t.name == null || t.name.equals("video") || t.name.startsWith("Magnet")) && p.getName() != null && !p.getName().trim().isEmpty()) t.name = MediaPublisher.sanitize(p.getName());
        } else {
            if (t.torrentMetaPath == null || t.torrentMetaPath.isEmpty()) throw new IllegalArgumentException("Torrent metadata is missing");
            TorrentInfo info = new TorrentInfo(new File(t.torrentMetaPath));
            if (!info.isValid()) throw new IllegalArgumentException("Invalid .torrent metadata");
            p = new AddTorrentParams();
            p.setTorrentInfo(info);
            t.name = MediaPublisher.sanitize(info.name());
            t.torrentFiles = info.numFiles();
        }
        p.setSavePath(saveDir.getAbsolutePath());
        t.torrentHash = p.getInfoHashes().getBest().toHex();
        t.quality = "P2P • Torrent";
        t.mime = "application/x-videograb-torrent";
        t.updatedAt = System.currentTimeMillis();
        DownloadTaskStore.upsert(this, t);

        TorrentHandle existing = MANAGER.find(p.getInfoHashes().getBest());
        if (valid(existing)) return existing;
        SessionHandle sh = new SessionHandle(MANAGER.swig());
        ErrorCode ec = new ErrorCode(new error_code());
        TorrentHandle h = sh.addTorrent(p, ec);
        if (ec.isError() || !valid(h)) throw new IllegalArgumentException(ec.getMessage() == null ? "Could not add torrent" : ec.getMessage());
        return h;
    }

    private void fail(DownloadTask t, Throwable e) {
        DownloadTask latest = DownloadTaskStore.get(this, t.id);
        if (latest == null || DownloadTask.STATUS_PAUSED.equals(latest.status) || DownloadTask.STATUS_CANCELED.equals(latest.status)) return;
        latest.status = DownloadTask.STATUS_FAILED;
        latest.speedBps = 0;
        latest.error = shortMsg(e == null ? null : e.getMessage());
        latest.updatedAt = System.currentTimeMillis();
        DownloadTaskStore.upsert(this, latest);
        TorrentHandle h = HANDLES.remove(latest.id);
        if (valid(h)) MANAGER.remove(h);
    }

    private boolean valid(TorrentHandle h) { try { return h != null && h.isValid(); } catch (Throwable e) { return false; } }

    private PendingIntent managerIntent() {
        Intent i = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_SCREEN, "downloads").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 48, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification note(String text, int progress, boolean indeterminate) {
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_download).setContentTitle("VideoGrab Torrent").setContentText(text).setContentIntent(managerIntent()).setOngoing(true).setOnlyAlertOnce(true).setProgress(100, progress, indeterminate).build();
    }

    private Notification doneNote(DownloadTask t) {
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_download).setContentTitle("Torrent download complete").setContentText(t.name).setContentIntent(managerIntent()).setAutoCancel(true).build();
    }

    private String speed(long n) { if (n < 1048576) return String.format(Locale.US, "%.0f KB/s", n / 1024.0); return String.format(Locale.US, "%.1f MB/s", n / 1048576.0); }
    private String shortMsg(String s) { if (s == null || s.trim().isEmpty()) return "Torrent download failed"; return s.length() > 140 ? s.substring(0, 140) : s; }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        super.onDestroy();
    }
}
