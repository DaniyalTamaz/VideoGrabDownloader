package com.daniyal.videograb;

import android.app.*;
import android.content.*;
import android.os.*;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.swig.torrent_flags_t;

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

    public static void recover(Context c) {
        boolean runnable = false;
        for (DownloadTask t : DownloadTaskStore.list(c)) {
            if (!DownloadTask.KIND_TORRENT.equals(t.kind)) continue;
            if (DownloadTask.STATUS_QUEUED.equals(t.status) || DownloadTask.STATUS_DOWNLOADING.equals(t.status)) {
                runnable = true;
                break;
            }
        }
        if (runnable) kick(c, null, null);
    }

    public static void pause(Context c, String id) { kick(c, ACTION_PAUSE, id); }
    public static void resume(Context c, String id) { kick(c, ACTION_RESUME, id); }
    public static void cancel(Context c, String id) { kick(c, ACTION_CANCEL, id); }

    private static void kick(Context c, String action, String id) {
        Intent i = new Intent(c, TorrentDownloadService.class);
        if (action != null) i.setAction(action);
        if (id != null) i.putExtra(EXTRA_ID, id);
        try { c.startForegroundService(i); }
        catch (Throwable ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "VideoGrab torrents", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NID, note("Torrent engine starting", 0, true));
        if (intent != null && intent.getAction() != null) applyAction(intent.getAction(), intent.getStringExtra(EXTRA_ID));
        if (LOOP.compareAndSet(false, true)) new Thread(() -> loop(startId), "vg-torrent-loop").start();
        return START_STICKY;
    }

    private void ensureManager() {
        synchronized (MANAGER) {
            if (!MANAGER.isRunning()) MANAGER.start();
        }
    }

    private void applyAction(String action, String id) {
        if (id == null) return;
        DownloadTask t = DownloadTaskStore.get(this, id);
        if (t == null || !DownloadTask.KIND_TORRENT.equals(t.kind)) return;
        TorrentHandle h = HANDLES.get(id);

        if (ACTION_PAUSE.equals(action)) {
            t.status = DownloadTask.STATUS_PAUSED;
            t.speedBps = 0;
            t.updatedAt = System.currentTimeMillis();
            DownloadTaskStore.upsert(this, t);
            if (valid(h)) try { h.pause(); } catch (Throwable ignored) { }
        } else if (ACTION_RESUME.equals(action)) {
            t.status = DownloadTask.STATUS_QUEUED;
            t.error = "";
            t.speedBps = 0;
            t.updatedAt = System.currentTimeMillis();
            DownloadTaskStore.upsert(this, t);
            if (valid(h)) try { h.resume(); } catch (Throwable ignored) { }
        } else if (ACTION_CANCEL.equals(action)) {
            t.status = DownloadTask.STATUS_CANCELED;
            t.speedBps = 0;
            t.updatedAt = System.currentTimeMillis();
            DownloadTaskStore.upsert(this, t);
            if (valid(h)) {
                try { h.pause(); } catch (Throwable ignored) { }
                try { MANAGER.remove(h); } catch (Throwable ignored) { }
            }
            HANDLES.remove(id);
            TorrentStorage.deleteTaskData(this, id);
        }
    }

    private void loop(int startId) {
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoGrab:Torrent");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }

            ensureManager();
            int idleRounds = 0;
            while (true) {
                boolean anyRunnable = false;
                List<DownloadTask> tasks = DownloadTaskStore.list(this);

                for (DownloadTask t : tasks) {
                    if (!DownloadTask.KIND_TORRENT.equals(t.kind)) continue;
                    if (!(DownloadTask.STATUS_QUEUED.equals(t.status) || DownloadTask.STATUS_DOWNLOADING.equals(t.status))) continue;
                    anyRunnable = true;
                    try { updateTorrent(t); }
                    catch (Throwable e) { fail(t, e); }
                }

                if (!anyRunnable) {
                    idleRounds++;
                    if (idleRounds >= 2) break;
                } else idleRounds = 0;

                try { Thread.sleep(1200); }
                catch (InterruptedException ignored) { break; }
            }
        } catch (Throwable fatal) {
            markActiveFailed(fatal);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                try { wakeLock.release(); } catch (Throwable ignored) { }
            }
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
        if (!valid(h)) {
            h = findOrAdd(t);
            HANDLES.put(t.id, h);
        }
        if (!valid(h)) throw new IllegalStateException("Torrent engine did not return a valid handle");

        if (DownloadTask.STATUS_QUEUED.equals(t.status)) {
            try { h.resume(); } catch (Throwable ignored) { }
            t.status = DownloadTask.STATUS_DOWNLOADING;
        }

        // Use SessionManager/TorrentHandle's cached status instead of forcing a native status request every second.
        TorrentStatus s = h.status();
        if (s == null) return;

        if (s.errorCode() != null && s.errorCode().isError()) {
            String message = s.errorCode().getMessage();
            if (message != null && !message.trim().isEmpty()) throw new IllegalStateException(message);
        }

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
        t.progress = t.total > 0
                ? (int)Math.min(100, t.downloaded * 100L / t.total)
                : Math.max(0, Math.min(99, Math.round(s.progress() * 100f)));
        t.updatedAt = System.currentTimeMillis();

        if (s.isFinished()) {
            t.progress = 100;
            t.status = DownloadTask.STATUS_COMPLETED;
            t.speedBps = 0;
            t.outputUri = "videograb-torrent://" + t.id;
            t.mime = "application/x-videograb-torrent";
            DownloadTaskStore.upsert(this, t);

            // Pause after completion so VideoGrab never silently seeds. Do not remove the
            // native torrent here; keeping the session stable avoids alert-loop teardown races.
            try { h.pause(); } catch (Throwable ignored) { }
            HANDLES.remove(t.id);
            getSystemService(NotificationManager.class).notify((int)(System.currentTimeMillis() & 0x7fffffff), doneNote(t));
            return;
        }

        DownloadTaskStore.upsert(this, t);
        getSystemService(NotificationManager.class).notify(
                NID,
                note(t.name + " • " + t.progress + "% • " + t.torrentPeers + " peers" +
                        (t.torrentSeeds > 0 ? " • " + t.torrentSeeds + " seeds" : "") +
                        (t.speedBps > 0 ? " • " + speed(t.speedBps) : ""),
                        t.progress,
                        t.total <= 0));
    }

    private TorrentHandle findOrAdd(DownloadTask t) throws Exception {
        File saveDir = TorrentStorage.taskDir(this, t.id);
        Sha1Hash hash;

        if (t.url != null && t.url.startsWith("magnet:?")) {
            AddTorrentParams p = AddTorrentParams.parseMagnetUri(t.url);
            hash = p.getInfoHashes().getBest();
            if ((t.name == null || t.name.equals("video") || t.name.startsWith("Magnet")) &&
                    p.getName() != null && !p.getName().trim().isEmpty()) {
                t.name = MediaPublisher.sanitize(p.getName());
            }
            t.torrentHash = hash.toHex();

            TorrentHandle existing = MANAGER.find(hash);
            if (valid(existing)) return existing;

            // Use the high-level SessionManager API instead of wrapping the native session directly.
            MANAGER.download(t.url, saveDir, new torrent_flags_t());
        } else {
            if (t.torrentMetaPath == null || t.torrentMetaPath.isEmpty()) throw new IllegalArgumentException("Torrent metadata is missing");
            TorrentInfo info = new TorrentInfo(new File(t.torrentMetaPath));
            if (!info.isValid()) throw new IllegalArgumentException("Invalid .torrent metadata");
            hash = info.infoHash();
            t.torrentHash = hash.toHex();
            t.name = MediaPublisher.sanitize(info.name());
            t.torrentFiles = info.numFiles();

            TorrentHandle existing = MANAGER.find(hash);
            if (valid(existing)) return existing;

            MANAGER.download(info, saveDir);
        }

        t.quality = "P2P • Torrent";
        t.mime = "application/x-videograb-torrent";
        t.updatedAt = System.currentTimeMillis();
        DownloadTaskStore.upsert(this, t);

        for (int i = 0; i < 40; i++) {
            TorrentHandle h = MANAGER.find(hash);
            if (valid(h)) return h;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        throw new IllegalStateException("Torrent engine could not start this download");
    }

    private void fail(DownloadTask t, Throwable e) {
        DownloadTask latest = DownloadTaskStore.get(this, t.id);
        if (latest == null || DownloadTask.STATUS_PAUSED.equals(latest.status) || DownloadTask.STATUS_CANCELED.equals(latest.status)) return;
        latest.status = DownloadTask.STATUS_FAILED;
        latest.speedBps = 0;
        latest.error = shortMsg(e == null ? null : e.getMessage());
        latest.updatedAt = System.currentTimeMillis();
        DownloadTaskStore.upsert(this, latest);

        // Do not tear down/remove the native torrent on an ordinary error. Pause it so Retry can
        // reuse the same handle and avoid native alert-loop lifecycle races.
        TorrentHandle h = HANDLES.get(latest.id);
        if (valid(h)) try { h.pause(); } catch (Throwable ignored) { }
    }

    private void markActiveFailed(Throwable e) {
        for (DownloadTask t : DownloadTaskStore.list(this)) {
            if (!DownloadTask.KIND_TORRENT.equals(t.kind)) continue;
            if (DownloadTask.STATUS_QUEUED.equals(t.status) || DownloadTask.STATUS_DOWNLOADING.equals(t.status)) fail(t, e);
        }
    }

    private boolean valid(TorrentHandle h) {
        try { return h != null && h.isValid(); }
        catch (Throwable e) { return false; }
    }

    private PendingIntent managerIntent() {
        Intent i = new Intent(this, TorrentMainActivity.class)
                .putExtra(MainActivity.EXTRA_SCREEN, "downloads")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 48, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification note(String text, int progress, boolean indeterminate) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("VideoGrab Torrent")
                .setContentText(text)
                .setContentIntent(managerIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, indeterminate)
                .build();
    }

    private Notification doneNote(DownloadTask t) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("Torrent download complete")
                .setContentText(t.name)
                .setContentIntent(managerIntent())
                .setAutoCancel(true)
                .build();
    }

    private String speed(long n) {
        if (n < 1048576) return String.format(Locale.US, "%.0f KB/s", n / 1024.0);
        return String.format(Locale.US, "%.1f MB/s", n / 1048576.0);
    }

    private String shortMsg(String s) {
        if (s == null || s.trim().isEmpty()) return "Torrent engine stopped unexpectedly";
        return s.length() > 140 ? s.substring(0, 140) : s;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
