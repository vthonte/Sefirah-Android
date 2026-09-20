package sefirah.worker;

import android.content.IContentProvider;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;

/**
 * {@link IContentProvider#call} after {@code getContentProviderExternal},
 */
final class ContentProviders {
    private static String getAuthority() {
        return WorkerService.HOST_PACKAGE + ".worker.bridge";
    }

    private ContentProviders() {
    }

    static Bundle call(String method, String arg, Bundle extras) {
        String authority = getAuthority();
        IContentProvider provider = ActivityManagers.getContentProviderExternal(authority);
        if (provider == null) {
            return null;
        }
        try {
            return callCompat(provider, method, arg, extras);
        } catch (RemoteException e) {
            Ln.e("IContentProvider.call failed for " + authority + "/" + method, e);
            return null;
        } finally {
            ActivityManagers.removeContentProviderExternal(authority);
        }
    }

    private static Bundle callCompat(
            IContentProvider provider,
            String method,
            String arg,
            Bundle extras
    ) throws RemoteException {
        String authority = getAuthority();
        String pkg = FakeContext.PACKAGE_NAME;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return provider.call(
                    FakeContext.get().getAttributionSource(),
                    authority,
                    method,
                    arg,
                    extras
            );
        }
        if (Build.VERSION.SDK_INT == 30) {
            return provider.call(pkg, null, authority, method, arg, extras);
        }
        if (Build.VERSION.SDK_INT == 29) {
            return provider.call(pkg, authority, method, arg, extras);
        }
        return provider.call(pkg, method, arg, extras);
    }
}
