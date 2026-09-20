package sefirah.worker;

import android.app.ActivityManagerHidden;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.File;

import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.adapter.UidObserverAdapter;

/**
 * Shell clipboard worker — {@code app_process} entry and {@link IWorkerService} implementation.
 *
 * <pre>
 * /data/app/…/lib/arm64/libsefirah_worker.so --apk=/data/app/…/base.apk
 * </pre>
 */
public final class WorkerService extends IWorkerService.Stub {
    static String HOST_PACKAGE = System.getProperty("sefirah.host.package", "com.castle.sefirah.ai");
    private static final String METHOD_SEND_BINDER = "sendBinder";
    private static final String EXTRA_BINDER = "sefirah.intent.extra.BINDER";
    private static final long HOST_GONE_RECHECK_MS = 10_000;

    private final Context context;
    private final Handler mainHandler;
    private final ClipboardListener clipboard;

    private volatile IHostBridge hostBridge;
    private boolean registering;
    @SuppressWarnings("FieldCanBeLocal")
    private HostObserver hostObserver;

    public static void main(String[] args) {
        Ln.i("boot version=" + BuildConfig.VERSION_CODE);

        Looper.prepareMainLooper();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        Context shellContext;
        try {
            shellContext = FakeContext.get();
        } catch (Throwable t) {
            Ln.e("Failed to create system context", t);
            System.exit(2);
            return;
        }

        WorkerService service = new WorkerService(shellContext, mainHandler);
        mainHandler.post(service::start);
        Looper.loop();
        throw new RuntimeException("Main looper unexpectedly quit");
    }

    private WorkerService(Context context, Handler mainHandler) {
        this.context = context;
        this.mainHandler = mainHandler;
        this.clipboard = new ClipboardListener();
    }

    private void start() {
        ApplicationInfo host = GetHostAppInfo();
        hostObserver = new HostObserver(host.uid);
        hostObserver.start();
        bind();
    }

    private void onAppUidActive() {
        if (isConnected()) {
            return;
        }
        bind();
    }

    private void onHostUidGone() {
        if (GetHostAppInfo() != null) {
            mainHandler.postDelayed(this::GetHostAppInfo, HOST_GONE_RECHECK_MS);
        }
    }

    /** Host {@link ApplicationInfo}, or {@link System#exit} if the package is gone. */
    private ApplicationInfo GetHostAppInfo() {
        try {
            return context.getPackageManager().getApplicationInfo(HOST_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Ln.i("Host uninstalled — exiting worker");
            System.exit(0);
            return null;
        }
    }

    private void bind() {
        if (isConnected() || registering) {
            return;
        }
        registering = true;
        try {
            Bundle extras = new Bundle();
            extras.putBinder(EXTRA_BINDER, this);

            Bundle result = ContentProviders.call(METHOD_SEND_BINDER, null, extras);
            if (result == null) {
                Ln.w("Bridge provider returned null");
                return;
            }

            IBinder bridgeBinder = result.getBinder(EXTRA_BINDER);
            if (bridgeBinder == null) {
                Ln.w("sendBinder reply missing binder");
                return;
            }

            connect(IHostBridge.Stub.asInterface(bridgeBinder));
            try {
                bridgeBinder.linkToDeath(() -> mainHandler.post(this::disconnect), 0);
            } catch (RemoteException e) {
                Ln.w("linkToDeath failed", e);
            }
        } catch (SecurityException e) {
            Ln.w("Registration auth failed", e);
        } catch (Exception e) {
            Ln.w("register via ContentProvider failed", e);
        } finally {
            registering = false;
        }
    }

    private boolean isConnected() {
        return hostBridge != null;
    }

    private void connect(IHostBridge hostBridge) {
        this.hostBridge = hostBridge;
    }

    private void disconnect() {
        hostBridge = null;
        clipboard.stopWatching();
    }

    private void reportClipboardText(String text) {
        IHostBridge bridge = hostBridge;
        if (bridge == null) {
            return;
        }
        try {
            bridge.onClipboardText(text);
        } catch (RemoteException e) {
            Ln.w("Host bridge died while reporting clip", e);
            disconnect();
        }
    }

    private void reportClipboardImage(String mimeType, ParcelFileDescriptor fd) {
        IHostBridge bridge = hostBridge;
        if (bridge == null) {
            return;
        }
        try {
            bridge.onClipboardImage(mimeType, fd);
        } catch (RemoteException e) {
            Ln.w("Host bridge died while reporting clip", e);
            disconnect();
        }
    }

    @Override
    public int getVersion() {
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public void destroy() {
        disconnect();
        System.exit(0);
    }

    @Override
    public void startWatching() {
        clipboard.startWatching();
    }

    @Override
    public void stopWatching() {
        clipboard.stopWatching();
    }

    @Override
    public void suppressNextOutbound() {
        clipboard.suppressNextOutbound();
    }

    private final class ClipboardListener {
        private final ClipboardManager clipboardManager;

        /** Ignore this many upcoming clipboard changes (host app wrote the clip). */
        private int suppressCount;
        private long lastHandledTimestamp;

        private final ClipboardManager.OnPrimaryClipChangedListener clipChangedListener =
                this::sendPrimaryClipboard;

        ClipboardListener() {
            this.clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        }

        void startWatching() {
            if (clipboardManager == null) {
                Ln.e("ClipboardManager unavailable");
                return;
            }
            mainHandler.post(() -> {
                try {
                    clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
                } catch (Exception ignored) {
                }
                try {
                    clipboardManager.addPrimaryClipChangedListener(clipChangedListener);
                } catch (Exception e) {
                    Ln.e("addPrimaryClipChangedListener failed", e);
                }
            });
        }

        void stopWatching() {
            if (clipboardManager == null) {
                return;
            }
            mainHandler.post(() -> {
                try {
                    clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
                } catch (Exception ignored) {
                }
            });
        }

        synchronized void suppressNextOutbound() {
            suppressCount++;
        }

        private void sendPrimaryClipboard() {
            if (clipboardManager == null) {
                return;
            }
            try {
                // Description only — getPrimaryClip() shows "Shell pasted from clipboard".
                ClipDescription desc = clipboardManager.getPrimaryClipDescription();
                if (shouldSkip(desc)) {
                    return;
                }

                ClipData clip = clipboardManager.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) {
                    return;
                }
                if (desc == null) {
                    desc = clip.getDescription();
                }
                ClipData.Item item = clip.getItemAt(0);

                CharSequence textCs = item.getText();
                if (textCs != null) {
                    String text = textCs.toString();
                    if (!text.isEmpty()) {
                        WorkerService.this.reportClipboardText(text);
                        return;
                    }
                }

                Uri uri = item.getUri();
                String[] imageMimes = desc != null ? desc.filterMimeTypes("image/*") : null;
                if (uri != null && imageMimes != null && imageMimes.length > 0) {
                    String mime = imageMimes[0];
                    ParcelFileDescriptor pfd = openUriParcelFileDescriptor(uri);
                    if (pfd == null) {
                        Ln.w("Failed to open clipboard image: " + uri);
                        return;
                    }
                    try (pfd) {
                        WorkerService.this.reportClipboardImage(mime, pfd);
                    }
                }
            } catch (Exception e) {
                Ln.e("sendPrimaryClipboard failed", e);
            }
        }

        /**
         * True if this clipboard change should be skipped.
         * Android 12+ fires twice for the same clip (before and after text classification).
         * We ignore the second callback with the same timestamp.
         * <p>
         * <a href="https://developer.android.com/reference/android/content/ClipboardManager.OnPrimaryClipChangedListener">OnPrimaryClipChangedListener</a>
         * <p>
         * Also skips if suppressCount > 0 (host app wrote this clipboard change).
         */
        private synchronized boolean shouldSkip(ClipDescription desc) {
            long timestamp = desc == null ? 0L : desc.getTimestamp();
            if (timestamp != 0L && timestamp == lastHandledTimestamp) {
                return true;
            }
            if (suppressCount > 0) {
                suppressCount--;
                lastHandledTimestamp = timestamp;
                return true;
            }
            lastHandledTimestamp = timestamp;
            return false;
        }

        private ParcelFileDescriptor openUriParcelFileDescriptor(Uri uri) {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                if (path == null) {
                    return null;
                }
                try {
                    return ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
                } catch (Exception e) {
                    Ln.e("open file PFD failed: " + uri, e);
                    return null;
                }
            }
            if (!"content".equalsIgnoreCase(uri.getScheme())) {
                Ln.w("Unsupported uri scheme: " + uri);
                return null;
            }
            try {
                return context.getContentResolver().openFileDescriptor(uri, "r");
            } catch (Exception e) {
                Ln.e("openFileDescriptor failed: " + uri, e);
                return null;
            }
        }
    }

    private final class HostObserver extends UidObserverAdapter {
        private final int appUid;

        HostObserver(int appUid) {
            this.appUid = appUid;
        }

        void start() {
            try {
                int flags = ActivityManagerHidden.UID_OBSERVER_ACTIVE
                        | ActivityManagerHidden.UID_OBSERVER_CACHED
                        | ActivityManagerHidden.UID_OBSERVER_GONE;
                ActivityManagerApis.registerUidObserver(
                        this,
                        flags,
                        ActivityManagerHidden.PROCESS_STATE_UNKNOWN,
                        null);
            } catch (Throwable t) {
                Ln.e("Failed to register UidObserver", t);
            }
        }

        @Override
        public void onUidActive(int uid) {
            if (uid == appUid) {
                mainHandler.post(WorkerService.this::onAppUidActive);
            }
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) {
            if (uid == appUid && !cached) {
                mainHandler.post(WorkerService.this::onAppUidActive);
            }
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            if (uid == appUid) {
                mainHandler.post(WorkerService.this::onHostUidGone);
            }
        }
    }
}
