package org.mozilla.vrbrowser.browser.engine.gecko;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.ContentBlockingController;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtensionController;
import org.mozilla.vrbrowser.browser.engine.Session;
import org.mozilla.vrbrowser.browser.engine.SessionStore;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import mozilla.components.concept.engine.Engine;
import mozilla.components.concept.engine.EngineSession;
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy;
import mozilla.components.concept.engine.EngineSessionState;
import mozilla.components.concept.engine.EngineView;
import mozilla.components.concept.engine.Settings;
import mozilla.components.concept.engine.content.blocking.TrackerLog;
import mozilla.components.concept.engine.content.blocking.TrackingProtectionExceptionStorage;
import mozilla.components.concept.engine.utils.EngineVersion;
import mozilla.components.concept.engine.webextension.WebExtension;
import mozilla.components.concept.engine.webextension.WebExtensionDelegate;
import mozilla.components.concept.engine.webnotifications.WebNotificationDelegate;
import mozilla.components.concept.engine.webpush.WebPushDelegate;
import mozilla.components.concept.engine.webpush.WebPushHandler;

import static org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION;

public class GeckoEngine implements Engine {

    private Context mContext;
    private SessionStore mSessionStore;
    private WebExtensionDelegate mWebExtensionDelegate;
    private Settings mSettings;

    GeckoEngine(@NonNull Context context, @NonNull SessionStore sessionstore) {
        mContext = context;
        mSessionStore = sessionstore;
        mSettings = new FxRSettings(mSessionStore.getRuntime().getSettings());
    }

    @NotNull
    @Override
    public Settings getSettings() {
        return mSettings;
    }

    @NotNull
    @Override
    public TrackingProtectionExceptionStorage getTrackingProtectionExceptionStore() {
        throw new UnsupportedOperationException("getTrackingProtectionExceptionStore not supported yet");
    }

    @NotNull
    @Override
    public EngineVersion getVersion() {
        EngineVersion version = EngineVersion.Companion.parse(MOZILLA_VERSION);
        if (version != null) {
            return version;
        }

        throw new IllegalStateException("Could not determine engine version");
    }

    @Override
    public void clearData(@NotNull BrowsingData browsingData, @Nullable String host, @NotNull Function0<Unit> onSuccess, @NotNull Function1<? super Throwable, Unit> onError) {
        long types = (long) browsingData.getTypes();
        if (host != null) {
            mSessionStore.getRuntime().getStorageController().clearDataFromHost(host, types).then(aVoid -> {
                onSuccess.invoke();
                return null;
            }, throwable -> {
                onError.invoke(throwable);
                return null;
            });

        } else {
            mSessionStore.getRuntime().getStorageController().clearData(types).then(aVoid -> {
                onSuccess.invoke();
                return null;
            }, throwable -> {
                onError.invoke(throwable);
                return null;
            });
        }

    }

    @NotNull
    @Override
    public EngineSession createSession(boolean isPrivate) {
        return new GeckoEngineSession(mContext, mSessionStore.createSession(isPrivate));
    }

    @NotNull
    @Override
    public EngineSessionState createSessionState(@NotNull JSONObject jsonObject) {
        return GeckoEngineSessionState.fromJSON(jsonObject);
    }

    @NotNull
    @Override
    public EngineView createView(@NotNull Context context, @Nullable AttributeSet attributeSet) {
        // We don't support Engine views creation
        throw new UnsupportedOperationException("createView not supported yet");
    }

    @Override
    public void getTrackersLog(@NotNull EngineSession engineSession, @NotNull Function1<? super List<TrackerLog>, Unit> onSuccess, @NotNull Function1<? super Throwable, Unit> onError) {
        GeckoSession session = ((GeckoEngineSession)engineSession).getGeckoSession();
        if (session != null) {
            mSessionStore.getRuntime().getContentBlockingController().getLog(session).then(logEntries -> {
                List<ContentBlockingController.LogEntry> list = new ArrayList<>();
                if (logEntries!= null) {
                    list = logEntries;
                }
                List<TrackerLog> logs = list.stream().map(this::toTrackerLog).filter(it -> !(it.getCookiesHasBeenBlocked() && it.getBlockedCategories().isEmpty() && it.getLoadedCategories().isEmpty())).collect(Collectors.toCollection(ArrayList::new));

                onSuccess.invoke(logs);
                return GeckoResult.fromValue(Void.TYPE);

            }, throwable -> {
                onError.invoke(throwable);
                return GeckoResult.fromException(throwable);
            });

        } else {
            onError.invoke(new NullPointerException("The GeckoSession instance is null"));
        }
    }

    @Override
    public void installWebExtension(@NotNull String id, @NotNull String url, boolean allowContentMessaging, @NotNull Function1<? super WebExtension, Unit> onSuccess, @NotNull Function2<? super String, ? super Throwable, Unit> onError) {
        final GeckoWebExtension extension = new GeckoWebExtension(id, url, allowContentMessaging);
        mSessionStore.getRuntime().registerWebExtension(extension.getNativeExtension()).then(aVoid -> {
            if (mWebExtensionDelegate != null) {
                mWebExtensionDelegate.onInstalled(extension);
            }

            onSuccess.invoke(extension);
            return GeckoResult.fromValue(Void.TYPE);

        }, throwable -> {
            onError.invoke(id, throwable);
            return GeckoResult.fromException(throwable);
        });
    }

    @NotNull
    @Override
    public String name() {
        return "Gecko";
    }

    @Override
    public void registerWebExtensionDelegate(@NotNull WebExtensionDelegate webExtensionDelegate) {
        mWebExtensionDelegate = webExtensionDelegate;

        mSessionStore.getRuntime().getWebExtensionController().setTabDelegate(new WebExtensionController.TabDelegate() {
            // We use this map to find the engine session of a given gecko
            // session, as we currently have no other way of accessing the
            // list of engine sessions. This will change once the engine
            // gets access to the browser store:
            // https://github.com/mozilla-mobile/android-components/issues/4965
            private WeakHashMap<GeckoEngineSession, String> tabs = new WeakHashMap<>();

            @NotNull
            @Override
            public GeckoResult<GeckoSession> onNewTab(@Nullable org.mozilla.geckoview.WebExtension webExtension, @Nullable String url) {
                Session session = mSessionStore.createSession(mSessionStore.getActiveSession().isPrivateMode());
                GeckoEngineSession geckoEngineSession = new GeckoEngineSession(mContext, session);
                GeckoWebExtension extension = null;
                if (webExtension != null) {
                    extension = new GeckoWebExtension(webExtension.id, webExtension.location, true);
                }

                mWebExtensionDelegate.onNewTab(extension, url != null ? url : "", geckoEngineSession);
                if (webExtension != null) {
                    tabs.put(geckoEngineSession, webExtension.id);
                }

                return GeckoResult.fromValue(geckoEngineSession.getGeckoSession());
            }

            @NonNull
            @Override
            public GeckoResult<AllowOrDeny> onCloseTab(@Nullable org.mozilla.geckoview.WebExtension webExtension, @NonNull GeckoSession geckoSession) {
                GeckoEngineSession geckoEngineSession = null;
                for (GeckoEngineSession engineSession : tabs.keySet()) {
                    if (engineSession.getGeckoSession() == geckoSession) {
                        geckoEngineSession = engineSession;
                    }
                }

                if (geckoEngineSession == null) {
                    return GeckoResult.DENY;
                }

                if (webExtension != null && tabs.get(geckoEngineSession).equals(webExtension.id)) {
                    GeckoWebExtension geckoWebExtension = new GeckoWebExtension(webExtension.id, webExtension.location, true);
                    if (webExtensionDelegate.onCloseTab(geckoWebExtension, geckoEngineSession)) {
                        return GeckoResult.ALLOW;

                    } else {
                        return GeckoResult.DENY;
                    }

                } else {
                    return GeckoResult.DENY;
                }
            }
        });
    }

    @Override
    public void registerWebNotificationDelegate(@NotNull WebNotificationDelegate webNotificationDelegate) {
        // Not yet implemented
        throw new UnsupportedOperationException("registerWebNotificationDelegate not yet supported");
    }

    @NotNull
    @Override
    public WebPushHandler registerWebPushDelegate(@NotNull WebPushDelegate webPushDelegate) {
        // Not yet implemented
        throw new UnsupportedOperationException("registerWebPushDelegate not yet supported");
    }

    @Override
    public void speculativeConnect(@NotNull String s) {
        // Not yet implemented
        throw new UnsupportedOperationException("speculativeConnect not yet supported");
    }

    @Override
    public void warmUp() {

    }

    private TrackerLog toTrackerLog(ContentBlockingController.LogEntry entry) {
        boolean cookiesHasBeenBlocked = entry.blockingData.stream().anyMatch(blockingData -> blockingData.blocked);
        List<TrackingProtectionPolicy.TrackingCategory> loadedCategories = entry.blockingData.stream().map(this::getLoadedCategory).filter(it -> it != TrackingProtectionPolicy.TrackingCategory.NONE).distinct().collect(Collectors.toCollection(ArrayList::new));
        List<TrackingProtectionPolicy.TrackingCategory> blockedCategories = entry.blockingData.stream().map(this::getBlockedCategory).filter(it -> it != TrackingProtectionPolicy.TrackingCategory.NONE).distinct().collect(Collectors.toCollection(ArrayList::new));
        entry.blockingData.stream().map(blockingData -> blockingData.category).filter(it -> it != TrackingProtectionPolicy.TrackingCategory.NONE.getId()).distinct().collect(Collectors.toCollection(ArrayList::new));
        return new TrackerLog(
                entry.origin,
                loadedCategories,
                blockedCategories,
                cookiesHasBeenBlocked
        );
    }

    private TrackingProtectionPolicy.TrackingCategory getLoadedCategory(ContentBlockingController.LogEntry.BlockingData blockingData) {
        switch (blockingData.category) {
            case ContentBlockingController.Event.LOADED_FINGERPRINTING_CONTENT: return TrackingProtectionPolicy.TrackingCategory.FINGERPRINTING;
            case ContentBlockingController.Event.LOADED_CRYPTOMINING_CONTENT: return TrackingProtectionPolicy.TrackingCategory.CRYPTOMINING;
            case ContentBlockingController.Event.LOADED_SOCIALTRACKING_CONTENT: return TrackingProtectionPolicy.TrackingCategory.MOZILLA_SOCIAL;
            case ContentBlockingController.Event.LOADED_LEVEL_1_TRACKING_CONTENT: return TrackingProtectionPolicy.TrackingCategory.SCRIPTS_AND_SUB_RESOURCES;
            case ContentBlockingController.Event.LOADED_LEVEL_2_TRACKING_CONTENT: {
                // We are making sure that we are only showing trackers that our settings are
                // taking into consideration.
                boolean isContentListActive = false;
                if (mSettings.getTrackingProtectionPolicy() != null) {
                    isContentListActive = mSettings.getTrackingProtectionPolicy().contains(TrackingProtectionPolicy.TrackingCategory.CONTENT);
                }
                boolean isStrictLevelActive = mSessionStore.getRuntime().getSettings().getContentBlocking().getEnhancedTrackingProtectionLevel() == ContentBlocking.EtpLevel.STRICT;

                if (isStrictLevelActive && isContentListActive) {
                    return TrackingProtectionPolicy.TrackingCategory.SCRIPTS_AND_SUB_RESOURCES;
                } else {
                    return TrackingProtectionPolicy.TrackingCategory.NONE;
                }
            }
            default: return TrackingProtectionPolicy.TrackingCategory.NONE;
        }
    }

    private TrackingProtectionPolicy.TrackingCategory getBlockedCategory(ContentBlockingController.LogEntry.BlockingData blockingData) {
        switch (blockingData.category) {
            case ContentBlockingController.Event.BLOCKED_FINGERPRINTING_CONTENT: return TrackingProtectionPolicy.TrackingCategory.FINGERPRINTING;
            case ContentBlockingController.Event.BLOCKED_CRYPTOMINING_CONTENT: return TrackingProtectionPolicy.TrackingCategory.CRYPTOMINING;
            case ContentBlockingController.Event.BLOCKED_SOCIALTRACKING_CONTENT: return TrackingProtectionPolicy.TrackingCategory.MOZILLA_SOCIAL;
            case ContentBlockingController.Event.BLOCKED_TRACKING_CONTENT: return TrackingProtectionPolicy.TrackingCategory.SCRIPTS_AND_SUB_RESOURCES;
            default: return TrackingProtectionPolicy.TrackingCategory.NONE;
        }
    }
}