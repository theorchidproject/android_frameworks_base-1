/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.StatusBarManager.WindowVisibleState;
import static android.app.StatusBarManager.windowStateToString;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.InsetsState.containsType;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS;
import static android.view.WindowInsetsController.APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS;

import static androidx.core.view.ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
import static androidx.core.view.ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
import static androidx.lifecycle.Lifecycle.State.RESUMED;

import static com.android.systemui.Dependency.TIME_TICK_HANDLER_NAME;
import static com.android.systemui.charging.WirelessChargingLayout.UNKNOWN_BATTERY_LEVEL;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_ASLEEP;
import static com.android.systemui.statusbar.NotificationLockscreenUserManager.PERMISSION_SELF;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.TransitionMode;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.IWallpaperManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.TaskInfo;
import android.app.TaskStackBuilder;
import android.app.UiModeManager;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.PointF;
import android.hardware.display.DisplayManager;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.IRemoteAnimationRunner;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.RemoteAnimationAdapter;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.widget.DateTimeView;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.ArcaneIdleManager;
import com.android.systemui.AutoReinflateContainer;
import com.android.systemui.DejankUtils;
import com.android.systemui.EventLogTags;
import com.android.systemui.InitController;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.accessibility.floatingmenu.AccessibilityFloatingMenuController;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.DelegateLaunchAnimatorController;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.biometrics.AuthRippleController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.camera.CameraIntents;
import com.android.systemui.charging.WirelessChargingAnimation;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.emergency.EmergencyGesture;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.fragments.ExtensionFragmentListener;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.keyguard.KeyguardService;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationBarView;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.OverlayPlugin;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper.SnoozeOption;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.qs.QSPanelController;
import com.android.systemui.recents.ScreenPinningRequest;
import com.android.systemui.scrim.ScrimView;
import com.android.systemui.settings.brightness.BrightnessSliderController;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.AutoHideUiElement;
import com.android.systemui.statusbar.BackDropView;
import com.android.systemui.statusbar.CircleReveal;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.KeyboardShortcuts;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.LiftReveal;
import com.android.systemui.statusbar.LightRevealScrim;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.OperatorNameViewController;
import com.android.systemui.statusbar.PowerButtonReveal;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VisualizerView;
import com.android.systemui.statusbar.charging.WiredChargingRippleController;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationLaunchAnimatorControllerProvider;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.collection.legacy.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.render.NotifShadeEventSource;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.interruption.BypassHeadsUpNotifier;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;
import com.android.systemui.statusbar.phone.dagger.StatusBarPhoneModule;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragmentLogger;
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.window.StatusBarWindowController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.DumpUtilsKt;
import com.android.systemui.util.WallpaperController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.MessageRouter;
import com.android.systemui.volume.VolumeComponent;
import com.android.systemui.wmshell.BubblesManager;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.startingsurface.SplashscreenContentDrawer;
import com.android.wm.shell.startingsurface.StartingSurface;

import lineageos.providers.LineageSettings;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Named;

import dagger.Lazy;

/** */
public class StatusBar extends SystemUI implements
        ActivityStarter,
        LifecycleOwner,
        TunerService.Tunable {
    public static final boolean MULTIUSER_DEBUG = false;

    protected static final int MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU = 1027;

    // Should match the values in PhoneWindowManager
    public static final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";
    static public final String SYSTEM_DIALOG_REASON_SCREENSHOT = "screenshot";

    private static final String FORCE_SHOW_NAVBAR =
            "lineagesystem:" + LineageSettings.System.FORCE_SHOW_NAVBAR;
    public static final String SCREEN_BRIGHTNESS_MODE =
            "system:" + Settings.System.SCREEN_BRIGHTNESS_MODE;
    private static final String STATUS_BAR_BRIGHTNESS_CONTROL =
            "lineagesystem:" + LineageSettings.System.STATUS_BAR_BRIGHTNESS_CONTROL;

    private static final String BANNER_ACTION_CANCEL =
            "com.android.systemui.statusbar.banner_action_cancel";
    private static final String BANNER_ACTION_SETUP =
            "com.android.systemui.statusbar.banner_action_setup";
    public static final String TAG = "StatusBar";
    public static final boolean DEBUG = false;
    public static final boolean SPEW = false;
    public static final boolean DUMPTRUCK = true; // extra dumpsys info
    public static final boolean DEBUG_GESTURES = false;
    public static final boolean DEBUG_MEDIA_FAKE_ARTWORK = false;
    public static final boolean DEBUG_CAMERA_LIFT = false;

    public static final boolean DEBUG_WINDOW_STATE = false;

    // additional instrumentation for testing purposes; intended to be left on during development
    public static final boolean CHATTY = DEBUG;

    public static final boolean SHOW_LOCKSCREEN_MEDIA_ARTWORK = true;

    public static final String ACTION_FAKE_ARTWORK = "fake_artwork";

    private static final int MSG_OPEN_SETTINGS_PANEL = 1002;
    private static final int MSG_LAUNCH_TRANSITION_TIMEOUT = 1003;
    private static final int MSG_LONG_PRESS_BRIGHTNESS_CHANGE = 1004;
    // 1020-1040 reserved for BaseStatusBar

    // Time after we abort the launch transition.
    static final long LAUNCH_TRANSITION_TIMEOUT_MS = 5000;

    protected static final boolean CLOSE_PANEL_WHEN_EMPTIED = true;

    /**
     * The delay to reset the hint text when the hint animation is finished running.
     */
    private static final int HINT_RESET_DELAY_MS = 1200;

    private static final float BRIGHTNESS_CONTROL_PADDING = 0.15f;
    private static final int BRIGHTNESS_CONTROL_LONG_PRESS_TIMEOUT = 750; // ms
    private static final int BRIGHTNESS_CONTROL_LINGER_THRESHOLD = 20;

    public static final int FADE_KEYGUARD_START_DELAY = 100;
    public static final int FADE_KEYGUARD_DURATION = 300;
    public static final int FADE_KEYGUARD_DURATION_PULSING = 96;

    public static final long[] CAMERA_LAUNCH_GESTURE_VIBRATION_TIMINGS =
            new long[]{20, 20, 20, 20, 100, 20};
    public static final int[] CAMERA_LAUNCH_GESTURE_VIBRATION_AMPLITUDES =
            new int[]{39, 82, 139, 213, 0, 127};

    /**
     * If true, the system is in the half-boot-to-decryption-screen state.
     * Prudently disable QS and notifications.
     */
    public static final boolean ONLY_CORE_APPS;

    /** If true, the lockscreen will show a distinct wallpaper */
    public static final boolean ENABLE_LOCKSCREEN_WALLPAPER = true;

    private static final UiEventLogger sUiEventLogger = new UiEventLoggerImpl();

    static {
        boolean onlyCoreApps;
        try {
            IPackageManager packageManager =
                    IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
            onlyCoreApps = packageManager != null && packageManager.isOnlyCoreApps();
        } catch (RemoteException e) {
            onlyCoreApps = false;
        }
        ONLY_CORE_APPS = onlyCoreApps;
    }

    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    private StatusBarCommandQueueCallbacks mCommandQueueCallbacks;

    void setWindowState(int state) {
        mStatusBarWindowState =  state;
        mStatusBarWindowHidden = state == WINDOW_STATE_HIDDEN;
        mStatusBarHideIconsForBouncerManager.setStatusBarWindowHidden(mStatusBarWindowHidden);
        if (getStatusBarView() != null) {
            // Should #updateHideIconsForBouncer always be called, regardless of whether we have a
            //   status bar view? If so, we can make #updateHideIconsForBouncer private.
            mStatusBarHideIconsForBouncerManager.updateHideIconsForBouncer(/* animate= */ false);
        }
    }

    void acquireGestureWakeLock(long time) {
        mGestureWakeLock.acquire(time);
    }

    boolean setAppearance(int appearance) {
        if (mAppearance != appearance) {
            mAppearance = appearance;
            return updateBarMode(barMode(isTransientShown(), appearance));
        }

        return false;
    }

    int getBarMode() {
        return mStatusBarMode;
    }

    void resendMessage(int msg) {
        mMessageRouter.cancelMessages(msg);
        mMessageRouter.sendMessage(msg);
    }

    void resendMessage(Object msg) {
        mMessageRouter.cancelMessages(msg.getClass());
        mMessageRouter.sendMessage(msg);
    }

    int getDisabled1() {
        return mDisabled1;
    }

    void setDisabled1(int disabled) {
        mDisabled1 = disabled;
    }

    int getDisabled2() {
        return mDisabled2;
    }

    void setDisabled2(int disabled) {
        mDisabled2 = disabled;
    }

    void setLastCameraLaunchSource(int source) {
        mLastCameraLaunchSource = source;
    }

    void setLaunchCameraOnFinishedGoingToSleep(boolean launch) {
        mLaunchCameraOnFinishedGoingToSleep = launch;
    }

    void setLaunchCameraOnFinishedWaking(boolean launch) {
        mLaunchCameraWhenFinishedWaking = launch;
    }

    void setLaunchEmergencyActionOnFinishedGoingToSleep(boolean launch) {
        mLaunchEmergencyActionOnFinishedGoingToSleep = launch;
    }

    void setLaunchEmergencyActionOnFinishedWaking(boolean launch) {
        mLaunchEmergencyActionWhenFinishedWaking = launch;
    }

    void setTopHidesStatusBar(boolean hides) {
        mTopHidesStatusBar = hides;
    }

    QSPanelController getQSPanelController() {
        return mQSPanelController;
    }

    /** */
    public void animateExpandNotificationsPanel() {
        mCommandQueueCallbacks.animateExpandNotificationsPanel();
    }

    /** */
    public void animateExpandSettingsPanel(@Nullable String subpanel) {
        mCommandQueueCallbacks.animateExpandSettingsPanel(subpanel);
    }

    /** */
    public void animateCollapsePanels(int flags, boolean force) {
        mCommandQueueCallbacks.animateCollapsePanels(flags, force);
    }

    /**
     * The {@link StatusBarState} of the status bar.
     */
    protected int mState; // TODO: remove this. Just use StatusBarStateController
    protected boolean mBouncerShowing;

    private final PhoneStatusBarPolicy mIconPolicy;

    private final VolumeComponent mVolumeComponent;
    private BrightnessMirrorController mBrightnessMirrorController;
    private boolean mBrightnessMirrorVisible;
    private BiometricUnlockController mBiometricUnlockController;
    private final LightBarController mLightBarController;
    private final Lazy<LockscreenWallpaper> mLockscreenWallpaperLazy;
    private final LockscreenGestureLogger mLockscreenGestureLogger;
    @Nullable
    protected LockscreenWallpaper mLockscreenWallpaper;
    private final AutoHideController mAutoHideController;
    private final CollapsedStatusBarFragmentLogger mCollapsedStatusBarFragmentLogger;

    private final Point mCurrentDisplaySize = new Point();

    protected NotificationShadeWindowView mNotificationShadeWindowView;
    protected PhoneStatusBarView mStatusBarView;
    private PhoneStatusBarViewController mPhoneStatusBarViewController;
    private AuthRippleController mAuthRippleController;
    private VisualizerView mVisualizerView;
    private int mStatusBarWindowState = WINDOW_STATE_SHOWING;
    protected NotificationShadeWindowController mNotificationShadeWindowController;
    private final StatusBarWindowController mStatusBarWindowController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @VisibleForTesting
    DozeServiceHost mDozeServiceHost;
    private boolean mWakeUpComingFromTouch;
    private PointF mWakeUpTouchLocation;
    private LightRevealScrim mLightRevealScrim;
    private WiredChargingRippleController mChargingRippleAnimationController;
    private PowerButtonReveal mPowerButtonReveal;

    private final Object mQueueLock = new Object();

    private final PulseExpansionHandler mPulseExpansionHandler;
    private final NotificationWakeUpCoordinator mWakeUpCoordinator;
    private final KeyguardBypassController mKeyguardBypassController;
    private final KeyguardStateController mKeyguardStateController;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    private final DynamicPrivacyController mDynamicPrivacyController;
    private final BypassHeadsUpNotifier mBypassHeadsUpNotifier;
    private final FalsingCollector mFalsingCollector;
    private final FalsingManager mFalsingManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final ConfigurationController mConfigurationController;
    protected NotificationShadeWindowViewController mNotificationShadeWindowViewController;
    private final DozeParameters mDozeParameters;
    private final Lazy<BiometricUnlockController> mBiometricUnlockControllerLazy;
    private final StatusBarComponent.Factory mStatusBarComponentFactory;
    private final PluginManager mPluginManager;
    private final Optional<LegacySplitScreen> mSplitScreenOptional;
    private final StatusBarNotificationActivityStarter.Builder
            mStatusBarNotificationActivityStarterBuilder;
    private final ShadeController mShadeController;
    private final LightsOutNotifController mLightsOutNotifController;
    private final InitController mInitController;

    private final PluginDependencyProvider mPluginDependencyProvider;
    private final KeyguardDismissUtil mKeyguardDismissUtil;
    private final ExtensionController mExtensionController;
    private final UserInfoControllerImpl mUserInfoControllerImpl;
    private final DemoModeController mDemoModeController;
    private final NotificationsController mNotificationsController;
    private final OngoingCallController mOngoingCallController;
    private final SystemStatusAnimationScheduler mAnimationScheduler;
    private final StatusBarSignalPolicy mStatusBarSignalPolicy;
    private final StatusBarLocationPublisher mStatusBarLocationPublisher;
    private final StatusBarIconController mStatusBarIconController;
    private final StatusBarHideIconsForBouncerManager mStatusBarHideIconsForBouncerManager;

    // expanded notifications
    // the sliding/resizing panel within the notification window
    protected NotificationPanelViewController mNotificationPanelViewController;

   // Arcane Idle
    private boolean isIdleManagerIstantiated = false;

    // settings
    private QSPanelController mQSPanelController;

    private final OperatorNameViewController.Factory mOperatorNameViewControllerFactory;
    KeyguardIndicationController mKeyguardIndicationController;

    private View mReportRejectedTouch;

    private boolean mExpandedVisible;

    private final int[] mAbsPos = new int[2];

    private final NotifShadeEventSource mNotifShadeEventSource;
    protected final NotificationEntryManager mEntryManager;
    private final NotificationGutsManager mGutsManager;
    private final NotificationLogger mNotificationLogger;
    private final NotificationViewHierarchyManager mViewHierarchyManager;
    private final PanelExpansionStateManager mPanelExpansionStateManager;
    private final KeyguardViewMediator mKeyguardViewMediator;
    protected final NotificationInterruptStateProvider mNotificationInterruptStateProvider;
    private final BrightnessSliderController.Factory mBrightnessSliderFactory;
    private final FeatureFlags mFeatureFlags;
    private final FragmentService mFragmentService;
    private final WallpaperController mWallpaperController;
    private final KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    private final MessageRouter mMessageRouter;
    private final WallpaperManager mWallpaperManager;
    private final UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    private final TunerService mTunerService;

    private StatusBarComponent mStatusBarComponent;
    ActivityManager mAm;
    private ArrayList<String> mStoplist = new ArrayList<String>();

    // the tracker view
    int mTrackingPosition; // the position of the top of the tracking view.

    private DisplayManager mDisplayManager;

    private int mMinBrightness;
    private int mInitialTouchX;
    private int mInitialTouchY;
    private int mLinger;
    private int mQuickQsOffsetHeight;
    private boolean mAutomaticBrightness;
    private boolean mBrightnessControl;
    private boolean mBrightnessChanged;
    private boolean mJustPeeked;

    // Flags for disabling the status bar
    // Two variables becaseu the first one evidently ran out of room for new flags.
    private int mDisabled1 = 0;
    private int mDisabled2 = 0;

    /** @see android.view.WindowInsetsController#setSystemBarsAppearance(int, int) */
    private @Appearance int mAppearance;

    private boolean mTransientShown;

    private final DisplayMetrics mDisplayMetrics;

    // XXX: gesture research
    private final GestureRecorder mGestureRec = DEBUG_GESTURES
        ? new GestureRecorder("/sdcard/statusbar_gestures.dat")
        : null;

    private final ScreenPinningRequest mScreenPinningRequest;

    private final MetricsLogger mMetricsLogger;

    // ensure quick settings is disabled until the current user makes it through the setup wizard
    @VisibleForTesting
    protected boolean mUserSetup = false;

    @VisibleForTesting
    public enum StatusBarUiEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Secured lockscreen is opened.")
        LOCKSCREEN_OPEN_SECURE(405),

        @UiEvent(doc = "Lockscreen without security is opened.")
        LOCKSCREEN_OPEN_INSECURE(406),

        @UiEvent(doc = "Secured lockscreen is closed.")
        LOCKSCREEN_CLOSE_SECURE(407),

        @UiEvent(doc = "Lockscreen without security is closed.")
        LOCKSCREEN_CLOSE_INSECURE(408),

        @UiEvent(doc = "Secured bouncer is opened.")
        BOUNCER_OPEN_SECURE(409),

        @UiEvent(doc = "Bouncer without security is opened.")
        BOUNCER_OPEN_INSECURE(410),

        @UiEvent(doc = "Secured bouncer is closed.")
        BOUNCER_CLOSE_SECURE(411),

        @UiEvent(doc = "Bouncer without security is closed.")
        BOUNCER_CLOSE_INSECURE(412);

        private final int mId;

        StatusBarUiEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    private Handler mMainHandler;
    private final DelayableExecutor mMainExecutor;

    private int mInteractingWindows;
    private @TransitionMode int mStatusBarMode;

    private final ViewMediatorCallback mKeyguardViewMediatorCallback;
    private final ScrimController mScrimController;
    protected DozeScrimController mDozeScrimController;
    private final Executor mUiBgExecutor;

    protected boolean mDozing;
    private boolean mIsFullscreen;

    private final NotificationMediaManager mMediaManager;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private boolean mWallpaperSupported;

    private Runnable mLaunchTransitionEndRunnable;
    private boolean mLaunchCameraWhenFinishedWaking;
    private boolean mLaunchCameraOnFinishedGoingToSleep;
    private boolean mLaunchEmergencyActionWhenFinishedWaking;
    private boolean mLaunchEmergencyActionOnFinishedGoingToSleep;
    private int mLastCameraLaunchSource;
    protected PowerManager.WakeLock mGestureWakeLock;

    private final int[] mTmpInt2 = new int[2];

    // Fingerprint (as computed by getLoggingFingerprint() of the last logged state.
    private int mLastLoggedStateFingerprint;
    private boolean mTopHidesStatusBar;
    private boolean mStatusBarWindowHidden;
    private boolean mIsOccluded;
    private boolean mIsLaunchingActivityOverLockscreen;

    private final UserSwitcherController mUserSwitcherController;
    private final NetworkController mNetworkController;
    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);
    protected final BatteryController mBatteryController;
    protected boolean mPanelExpanded;
    private UiModeManager mUiModeManager;
    protected boolean mIsKeyguard;
    private LogMaker mStatusBarStateLog;
    protected final NotificationIconAreaController mNotificationIconAreaController;
    @Nullable private View mAmbientIndicationContainer;
    private final SysuiColorExtractor mColorExtractor;
    private final ScreenLifecycle mScreenLifecycle;
    private final WakefulnessLifecycle mWakefulnessLifecycle;

    private boolean mNoAnimationOnNextBarModeChange;
    private final SysuiStatusBarStateController mStatusBarStateController;

    private final ActivityLaunchAnimator mActivityLaunchAnimator;
    private NotificationLaunchAnimatorControllerProvider mNotificationAnimationProvider;
    protected StatusBarNotificationPresenter mPresenter;
    private NotificationActivityStarter mNotificationActivityStarter;
    private final Lazy<NotificationShadeDepthController> mNotificationShadeDepthControllerLazy;
    private final Optional<BubblesManager> mBubblesManagerOptional;
    private final Optional<Bubbles> mBubblesOptional;
    private final Bubbles.BubbleExpandListener mBubbleExpandListener;
    private final Optional<StartingSurface> mStartingSurfaceOptional;

    private final ActivityIntentHelper mActivityIntentHelper;
    private NotificationStackScrollLayoutController mStackScrollerController;

    private final ColorExtractor.OnColorsChangedListener mOnColorsChangedListener =
            (extractor, which) -> updateTheme();


    /**
     * Public constructor for StatusBar.
     *
     * StatusBar is considered optional, and therefore can not be marked as @Inject directly.
     * Instead, an @Provide method is included. See {@link StatusBarPhoneModule}.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public StatusBar(
            Context context,
            NotificationsController notificationsController,
            FragmentService fragmentService,
            LightBarController lightBarController,
            AutoHideController autoHideController,
            StatusBarWindowController statusBarWindowController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            StatusBarSignalPolicy statusBarSignalPolicy,
            PulseExpansionHandler pulseExpansionHandler,
            NotificationWakeUpCoordinator notificationWakeUpCoordinator,
            KeyguardBypassController keyguardBypassController,
            KeyguardStateController keyguardStateController,
            HeadsUpManagerPhone headsUpManagerPhone,
            DynamicPrivacyController dynamicPrivacyController,
            BypassHeadsUpNotifier bypassHeadsUpNotifier,
            FalsingManager falsingManager,
            FalsingCollector falsingCollector,
            BroadcastDispatcher broadcastDispatcher,
            NotifShadeEventSource notifShadeEventSource,
            NotificationEntryManager notificationEntryManager,
            NotificationGutsManager notificationGutsManager,
            NotificationLogger notificationLogger,
            NotificationInterruptStateProvider notificationInterruptStateProvider,
            NotificationViewHierarchyManager notificationViewHierarchyManager,
            PanelExpansionStateManager panelExpansionStateManager,
            KeyguardViewMediator keyguardViewMediator,
            DisplayMetrics displayMetrics,
            MetricsLogger metricsLogger,
            @UiBackground Executor uiBgExecutor,
            NotificationMediaManager notificationMediaManager,
            NotificationLockscreenUserManager lockScreenUserManager,
            NotificationRemoteInputManager remoteInputManager,
            UserSwitcherController userSwitcherController,
            NetworkController networkController,
            BatteryController batteryController,
            SysuiColorExtractor colorExtractor,
            ScreenLifecycle screenLifecycle,
            WakefulnessLifecycle wakefulnessLifecycle,
            SysuiStatusBarStateController statusBarStateController,
            Optional<BubblesManager> bubblesManagerOptional,
            Optional<Bubbles> bubblesOptional,
            VisualStabilityManager visualStabilityManager,
            DeviceProvisionedController deviceProvisionedController,
            NavigationBarController navigationBarController,
            AccessibilityFloatingMenuController accessibilityFloatingMenuController,
            Lazy<AssistManager> assistManagerLazy,
            ConfigurationController configurationController,
            NotificationShadeWindowController notificationShadeWindowController,
            DozeParameters dozeParameters,
            ScrimController scrimController,
            Lazy<LockscreenWallpaper> lockscreenWallpaperLazy,
            LockscreenGestureLogger lockscreenGestureLogger,
            Lazy<BiometricUnlockController> biometricUnlockControllerLazy,
            DozeServiceHost dozeServiceHost,
            PowerManager powerManager,
            ScreenPinningRequest screenPinningRequest,
            DozeScrimController dozeScrimController,
            VolumeComponent volumeComponent,
            CommandQueue commandQueue,
            CollapsedStatusBarFragmentLogger collapsedStatusBarFragmentLogger,
            StatusBarComponent.Factory statusBarComponentFactory,
            PluginManager pluginManager,
            Optional<LegacySplitScreen> splitScreenOptional,
            LightsOutNotifController lightsOutNotifController,
            StatusBarNotificationActivityStarter.Builder
                    statusBarNotificationActivityStarterBuilder,
            ShadeController shadeController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            ViewMediatorCallback viewMediatorCallback,
            InitController initController,
            @Named(TIME_TICK_HANDLER_NAME) Handler timeTickHandler,
            PluginDependencyProvider pluginDependencyProvider,
            KeyguardDismissUtil keyguardDismissUtil,
            ExtensionController extensionController,
            UserInfoControllerImpl userInfoControllerImpl,
            OperatorNameViewController.Factory operatorNameViewControllerFactory,
            PhoneStatusBarPolicy phoneStatusBarPolicy,
            KeyguardIndicationController keyguardIndicationController,
            DemoModeController demoModeController,
            Lazy<NotificationShadeDepthController> notificationShadeDepthControllerLazy,
            StatusBarTouchableRegionManager statusBarTouchableRegionManager,
            NotificationIconAreaController notificationIconAreaController,
            BrightnessSliderController.Factory brightnessSliderFactory,
            WiredChargingRippleController chargingRippleAnimationController,
            WallpaperController wallpaperController,
            OngoingCallController ongoingCallController,
            SystemStatusAnimationScheduler animationScheduler,
            StatusBarLocationPublisher locationPublisher,
            StatusBarIconController statusBarIconController,
            StatusBarHideIconsForBouncerManager statusBarHideIconsForBouncerManager,
            LockscreenShadeTransitionController lockscreenShadeTransitionController,
            FeatureFlags featureFlags,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            @Main Handler mainHandler,
            @Main DelayableExecutor delayableExecutor,
            @Main MessageRouter messageRouter,
            WallpaperManager wallpaperManager,
            UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            Optional<StartingSurface> startingSurfaceOptional,
            TunerService tunerService,
            DumpManager dumpManager,
            ActivityLaunchAnimator activityLaunchAnimator) {
        super(context);
        mNotificationsController = notificationsController;
        mFragmentService = fragmentService;
        mLightBarController = lightBarController;
        mAutoHideController = autoHideController;
        mStatusBarWindowController = statusBarWindowController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mPulseExpansionHandler = pulseExpansionHandler;
        mWakeUpCoordinator = notificationWakeUpCoordinator;
        mKeyguardBypassController = keyguardBypassController;
        mKeyguardStateController = keyguardStateController;
        mHeadsUpManager = headsUpManagerPhone;
        mOperatorNameViewControllerFactory = operatorNameViewControllerFactory;
        mKeyguardIndicationController = keyguardIndicationController;
        mStatusBarTouchableRegionManager = statusBarTouchableRegionManager;
        mDynamicPrivacyController = dynamicPrivacyController;
        mBypassHeadsUpNotifier = bypassHeadsUpNotifier;
        mFalsingCollector = falsingCollector;
        mFalsingManager = falsingManager;
        mBroadcastDispatcher = broadcastDispatcher;
        mNotifShadeEventSource = notifShadeEventSource;
        mEntryManager = notificationEntryManager;
        mGutsManager = notificationGutsManager;
        mNotificationLogger = notificationLogger;
        mNotificationInterruptStateProvider = notificationInterruptStateProvider;
        mViewHierarchyManager = notificationViewHierarchyManager;
        mPanelExpansionStateManager = panelExpansionStateManager;
        mKeyguardViewMediator = keyguardViewMediator;
        mDisplayMetrics = displayMetrics;
        mMetricsLogger = metricsLogger;
        mUiBgExecutor = uiBgExecutor;
        mMediaManager = notificationMediaManager;
        mLockscreenUserManager = lockScreenUserManager;
        mRemoteInputManager = remoteInputManager;
        mUserSwitcherController = userSwitcherController;
        mNetworkController = networkController;
        mBatteryController = batteryController;
        mColorExtractor = colorExtractor;
        mScreenLifecycle = screenLifecycle;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mStatusBarStateController = statusBarStateController;
        mBubblesManagerOptional = bubblesManagerOptional;
        mBubblesOptional = bubblesOptional;
        mVisualStabilityManager = visualStabilityManager;
        mDeviceProvisionedController = deviceProvisionedController;
        mNavigationBarController = navigationBarController;
        mAccessibilityFloatingMenuController = accessibilityFloatingMenuController;
        mAssistManagerLazy = assistManagerLazy;
        mConfigurationController = configurationController;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mDozeServiceHost = dozeServiceHost;
        mPowerManager = powerManager;
        mDozeParameters = dozeParameters;
        mScrimController = scrimController;
        mLockscreenWallpaperLazy = lockscreenWallpaperLazy;
        mLockscreenGestureLogger = lockscreenGestureLogger;
        mScreenPinningRequest = screenPinningRequest;
        mDozeScrimController = dozeScrimController;
        mBiometricUnlockControllerLazy = biometricUnlockControllerLazy;
        mNotificationShadeDepthControllerLazy = notificationShadeDepthControllerLazy;
        mVolumeComponent = volumeComponent;
        mCommandQueue = commandQueue;
        mCollapsedStatusBarFragmentLogger = collapsedStatusBarFragmentLogger;
        mStatusBarComponentFactory = statusBarComponentFactory;
        mPluginManager = pluginManager;
        mSplitScreenOptional = splitScreenOptional;
        mStatusBarNotificationActivityStarterBuilder = statusBarNotificationActivityStarterBuilder;
        mShadeController = shadeController;
        mLightsOutNotifController =  lightsOutNotifController;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mKeyguardViewMediatorCallback = viewMediatorCallback;
        mInitController = initController;
        mPluginDependencyProvider = pluginDependencyProvider;
        mKeyguardDismissUtil = keyguardDismissUtil;
        mExtensionController = extensionController;
        mUserInfoControllerImpl = userInfoControllerImpl;
        mIconPolicy = phoneStatusBarPolicy;
        mDemoModeController = demoModeController;
        mNotificationIconAreaController = notificationIconAreaController;
        mBrightnessSliderFactory = brightnessSliderFactory;
        mChargingRippleAnimationController = chargingRippleAnimationController;
        mWallpaperController = wallpaperController;
        mOngoingCallController = ongoingCallController;
        mAnimationScheduler = animationScheduler;
        mStatusBarSignalPolicy = statusBarSignalPolicy;
        mStatusBarLocationPublisher = locationPublisher;
        mStatusBarIconController = statusBarIconController;
        mStatusBarHideIconsForBouncerManager = statusBarHideIconsForBouncerManager;
        mFeatureFlags = featureFlags;
        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        mMainHandler = mainHandler;
        mMainExecutor = delayableExecutor;
        mMessageRouter = messageRouter;
        mWallpaperManager = wallpaperManager;
        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;
        mTunerService = tunerService;

        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        mStartingSurfaceOptional = startingSurfaceOptional;
        lockscreenShadeTransitionController.setStatusbar(this);

        mPanelExpansionStateManager.addExpansionListener(this::onPanelExpansionChanged);

        mBubbleExpandListener =
                (isExpanding, key) -> mContext.getMainExecutor().execute(() -> {
                    mNotificationsController.requestNotificationUpdate("onBubbleExpandChanged");
                    updateScrimController();
                });

        mActivityIntentHelper = new ActivityIntentHelper(mContext);
        mActivityLaunchAnimator = activityLaunchAnimator;

        // The status bar background may need updating when the ongoing call status changes.
        mOngoingCallController.addCallback((animate) -> maybeUpdateBarMode());

        // TODO(b/190746471): Find a better home for this.
        DateTimeView.setReceiverHandler(timeTickHandler);

        mMessageRouter.subscribeTo(KeyboardShortcutsMessage.class,
                data -> toggleKeyboardShortcuts(data.mDeviceId));
        mMessageRouter.subscribeTo(MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU,
                id -> dismissKeyboardShortcuts());
        mMessageRouter.subscribeTo(AnimateExpandSettingsPanelMessage.class,
                data -> mCommandQueueCallbacks.animateExpandSettingsPanel(data.mSubpanel));
        mMessageRouter.subscribeTo(MSG_LAUNCH_TRANSITION_TIMEOUT,
                id -> onLaunchTransitionTimeout());
        mMessageRouter.subscribeTo(MSG_LONG_PRESS_BRIGHTNESS_CHANGE,
                id -> onLongPressBrightnessChange());
    }

    @Override
    public void start() {
        mScreenLifecycle.addObserver(mScreenObserver);
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
        mUiModeManager = mContext.getSystemService(UiModeManager.class);
        mBypassHeadsUpNotifier.setUp();
        if (mBubblesOptional.isPresent()) {
            mBubblesOptional.get().setExpandListener(mBubbleExpandListener);
        }

        mStatusBarSignalPolicy.init();
        mKeyguardIndicationController.init();

        mColorExtractor.addOnColorsChangedListener(mOnColorsChangedListener);
        mStatusBarStateController.addCallback(mStateListener,
                SysuiStatusBarStateController.RANK_STATUS_BAR);

        mNeedsNavigationBar = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_showNavigationBar);
        // Allow a system property to override this. Used by the emulator.
        // See also hasNavigationBar().
        String navBarOverride = SystemProperties.get("qemu.hw.mainkeys");
        if ("1".equals(navBarOverride)) {
            mNeedsNavigationBar = false;
        } else if ("0".equals(navBarOverride)) {
            mNeedsNavigationBar = true;
        }

        mTunerService.addTunable(this, FORCE_SHOW_NAVBAR);
        mTunerService.addTunable(this, SCREEN_BRIGHTNESS_MODE);
        mTunerService.addTunable(this, STATUS_BAR_BRIGHTNESS_CONTROL);

        mDisplayManager = mContext.getSystemService(DisplayManager.class);

        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));

        mDisplay = mContext.getDisplay();
        mDisplayId = mDisplay.getDisplayId();
        updateDisplaySize();
        mStatusBarHideIconsForBouncerManager.setDisplayId(mDisplayId);

        // start old BaseStatusBar.start().
        mWindowManagerService = WindowManagerGlobal.getWindowManagerService();
        mDevicePolicyManager = (DevicePolicyManager) mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);

        mAccessibilityManager = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);

        mKeyguardUpdateMonitor.setKeyguardBypassController(mKeyguardBypassController);
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        mAm = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);

        mDeviceProvisionedController = Dependency.get(DeviceProvisionedController.class);
        mDeviceProvisionedController.addCallback(mDeviceProvisionedListener);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ZEN_MODE), false,
                mSettingsObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS), false,
                mLockscreenSettingsObserver,
                UserHandle.USER_ALL);
        if (ENABLE_LOCK_SCREEN_ALLOW_REMOTE_INPUT) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_ALLOW_REMOTE_INPUT),
                    false,
                    mSettingsObserver,
                    UserHandle.USER_ALL);
        }

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS),
                true,
                mLockscreenSettingsObserver,
                UserHandle.USER_ALL);

        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        mKeyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mWallpaperSupported = mWallpaperManager.isWallpaperSupported();

        RegisterStatusBarResult result = null;
        try {
            result = mBarService.registerStatusBar(mCommandQueue);
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }

        createAndAddWindows(result);

        if (mWallpaperSupported) {
            // Make sure we always have the most current wallpaper info.
            IntentFilter wallpaperChangedFilter = new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED);
            mBroadcastDispatcher.registerReceiver(mWallpaperChangedReceiver, wallpaperChangedFilter,
                    null /* handler */, UserHandle.ALL);
            mWallpaperChangedReceiver.onReceive(mContext, null);
        } else if (DEBUG) {
            Log.v(TAG, "start(): no wallpaper service ");
        }

        // Set up the initial notification state. This needs to happen before CommandQueue.disable()
        setUpPresenter();

        if (containsType(result.mTransientBarTypes, ITYPE_STATUS_BAR)) {
            showTransientUnchecked();
        }
        mCommandQueueCallbacks.onSystemBarAttributesChanged(mDisplayId, result.mAppearance,
                result.mAppearanceRegions, result.mNavbarColorManagedByIme, result.mBehavior,
                result.mRequestedVisibilities, result.mPackageName);

        // StatusBarManagerService has a back up of IME token and it's restored here.
        mCommandQueueCallbacks.setImeWindowStatus(mDisplayId, result.mImeToken,
                result.mImeWindowVis, result.mImeBackDisposition, result.mShowImeSwitcher);

        // Set up the initial icon state
        int numIcons = result.mIcons.size();
        for (int i = 0; i < numIcons; i++) {
            mCommandQueue.setIcon(result.mIcons.keyAt(i), result.mIcons.valueAt(i));
        }


        if (DEBUG) {
            Log.d(TAG, String.format(
                    "init: icons=%d disabled=0x%08x lights=0x%08x imeButton=0x%08x",
                    numIcons,
                    result.mDisabledFlags1,
                    result.mAppearance,
                    result.mImeWindowVis));
        }

        IntentFilter internalFilter = new IntentFilter();
        internalFilter.addAction(BANNER_ACTION_CANCEL);
        internalFilter.addAction(BANNER_ACTION_SETUP);
        mContext.registerReceiver(mBannerActionBroadcastReceiver, internalFilter, PERMISSION_SELF,
                null);

        if (mWallpaperSupported) {
            IWallpaperManager wallpaperManager = IWallpaperManager.Stub.asInterface(
                    ServiceManager.getService(Context.WALLPAPER_SERVICE));
            try {
                wallpaperManager.setInAmbientMode(false /* ambientMode */, 0L /* duration */);
            } catch (RemoteException e) {
                // Just pass, nothing critical.
            }
        }

        // end old BaseStatusBar.start().

        // Lastly, call to the icon policy to install/update all the icons.
        mIconPolicy.init();

        mKeyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onUnlockedChanged() {
                updateKeyguardState();
                logStateToEventlog();
            }
        });
        startKeyguard();

        mKeyguardUpdateMonitor.registerCallback(mUpdateCallback);
        mDozeServiceHost.initialize(
                this,
                mStatusBarKeyguardViewManager,
                mNotificationShadeWindowViewController,
                mNotificationPanelViewController,
                mAmbientIndicationContainer);
        updateLightRevealScrimVisibility();

        mConfigurationController.addCallback(mConfigurationListener);

        mBatteryController.observe(mLifecycle, mBatteryStateChangeCallback);
        mLifecycle.setCurrentState(RESUMED);

        mAccessibilityFloatingMenuController.init();

        // set the initial view visibility
        int disabledFlags1 = result.mDisabledFlags1;
        int disabledFlags2 = result.mDisabledFlags2;
        mInitController.addPostInitTask(
                () -> setUpDisableFlags(disabledFlags1, disabledFlags2));

        mFalsingManager.addFalsingBeliefListener(mFalsingBeliefListener);

        mPluginManager.addPluginListener(
                new PluginListener<OverlayPlugin>() {
                    private final ArraySet<OverlayPlugin> mOverlays = new ArraySet<>();

                    @Override
                    public void onPluginConnected(OverlayPlugin plugin, Context pluginContext) {
                        mMainExecutor.execute(
                                () -> plugin.setup(getNotificationShadeWindowView(),
                                        getNavigationBarView(),
                                        new Callback(plugin), mDozeParameters));
                    }

                    @Override
                    public void onPluginDisconnected(OverlayPlugin plugin) {
                        mMainExecutor.execute(() -> {
                            mOverlays.remove(plugin);
                            mNotificationShadeWindowController
                                    .setForcePluginOpen(mOverlays.size() != 0, this);
                        });
                    }

                    class Callback implements OverlayPlugin.Callback {
                        private final OverlayPlugin mPlugin;

                        Callback(OverlayPlugin plugin) {
                            mPlugin = plugin;
                        }

                        @Override
                        public void onHoldStatusBarOpenChange() {
                            if (mPlugin.holdStatusBarOpen()) {
                                mOverlays.add(mPlugin);
                            } else {
                                mOverlays.remove(mPlugin);
                            }
                            mMainExecutor.execute(() -> {
                                mNotificationShadeWindowController
                                        .setStateListener(b -> mOverlays.forEach(
                                                o -> o.setCollapseDesired(b)));
                                mNotificationShadeWindowController
                                        .setForcePluginOpen(mOverlays.size() != 0, this);
                            });
                        }
                    }
                }, OverlayPlugin.class, true /* Allow multiple plugins */);

        mStartingSurfaceOptional.ifPresent(startingSurface -> startingSurface.setSysuiProxy(
                (requestTopUi, componentTag) -> mMainExecutor.execute(() ->
                        mNotificationShadeWindowController.setRequestTopUi(
                                requestTopUi, componentTag))));
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    protected void makeStatusBarView(@Nullable RegisterStatusBarResult result) {
        updateDisplaySize(); // populates mDisplayMetrics
        updateResources();
        updateTheme();

        inflateStatusBarWindow();
        mNotificationShadeWindowViewController.setService(this, mNotificationShadeWindowController);
        mNotificationShadeWindowView.setOnTouchListener(getStatusBarWindowTouchListener());
        mWallpaperController.setRootView(mNotificationShadeWindowView);

        mMinBrightness = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessDim);

        // TODO: Deal with the ugliness that comes from having some of the statusbar broken out
        // into fragments, but the rest here, it leaves some awkward lifecycle and whatnot.
        NotificationListContainer notifListContainer =
                mStackScrollerController.getNotificationListContainer();
        mNotificationLogger.setUpWithContainer(notifListContainer);

        mNotificationIconAreaController.setupShelf(mNotificationShelfController);
        mPanelExpansionStateManager.addExpansionListener(mWakeUpCoordinator);

        mUserSwitcherController.init(mNotificationShadeWindowView);

        // Allow plugins to reference DarkIconDispatcher and StatusBarStateController
        mPluginDependencyProvider.allowPluginDependency(DarkIconDispatcher.class);
        mPluginDependencyProvider.allowPluginDependency(StatusBarStateController.class);
        mStatusBarWindowController.getFragmentHostManager()
                .addTagListener(CollapsedStatusBarFragment.TAG, (tag, fragment) -> {
                    StatusBarFragmentComponent statusBarFragmentComponent =
                            ((CollapsedStatusBarFragment) fragment).getStatusBarFragmentComponent();
                    if (statusBarFragmentComponent == null) {
                        throw new IllegalStateException(
                                "CollapsedStatusBarFragment should have a valid component");
                    }

                    mStatusBarView = statusBarFragmentComponent.getPhoneStatusBarView();
                    mPhoneStatusBarViewController =
                            statusBarFragmentComponent.getPhoneStatusBarViewController();

                    // Ensure we re-propagate panel expansion values to the panel controller and
                    // any listeners it may have, such as PanelBar. This will also ensure we
                    // re-display the notification panel if necessary (for example, if
                    // a heads-up notification was being displayed and should continue being
                    // displayed).
                    mNotificationPanelViewController.updatePanelExpansionAndVisibility();
                    setBouncerShowingForStatusBarComponents(mBouncerShowing);

                    mLightsOutNotifController.setLightsOutNotifView(
                            mStatusBarView.findViewById(R.id.notification_lights_out));
                    mNotificationShadeWindowViewController.setStatusBarView(mStatusBarView);
                    checkBarModes();
                }).getFragmentManager()
                .beginTransaction()
                .replace(R.id.status_bar_container,
                        mStatusBarComponent.createCollapsedStatusBarFragment(),
                        CollapsedStatusBarFragment.TAG)
                .commit();

        mHeadsUpManager.setup(mVisualStabilityManager);
        mStatusBarTouchableRegionManager.setup(this, mNotificationShadeWindowView);
        mHeadsUpManager.addListener(mNotificationPanelViewController.getOnHeadsUpChangedListener());
        mHeadsUpManager.addListener(mVisualStabilityManager);
        mNotificationPanelViewController.setHeadsUpManager(mHeadsUpManager);

        createNavigationBar(result);

        if (ENABLE_LOCKSCREEN_WALLPAPER && mWallpaperSupported) {
            mLockscreenWallpaper = mLockscreenWallpaperLazy.get();
        }

        mNotificationPanelViewController.setKeyguardIndicationController(
                mKeyguardIndicationController);

        mAmbientIndicationContainer = mNotificationShadeWindowView.findViewById(
                R.id.ambient_indication_container);

        mAutoHideController.setStatusBar(new AutoHideUiElement() {
            @Override
            public void synchronizeState() {
                checkBarModes();
            }

            @Override
            public boolean shouldHideOnTouch() {
                return !mRemoteInputManager.isRemoteInputActive();
            }

            @Override
            public boolean isVisible() {
                return isTransientShown();
            }

            @Override
            public void hide() {
                clearTransient();
            }
        });

        ScrimView scrimBehind = mNotificationShadeWindowView.findViewById(R.id.scrim_behind);
        ScrimView notificationsScrim = mNotificationShadeWindowView
                .findViewById(R.id.scrim_notifications);
        ScrimView scrimInFront = mNotificationShadeWindowView.findViewById(R.id.scrim_in_front);

        mScrimController.setScrimVisibleListener(scrimsVisible -> {
            mNotificationShadeWindowController.setScrimsVisibility(scrimsVisible);
        });
        mScrimController.attachViews(scrimBehind, notificationsScrim, scrimInFront);

        mLightRevealScrim = mNotificationShadeWindowView.findViewById(R.id.light_reveal_scrim);
        mLightRevealScrim.setScrimOpaqueChangedListener((opaque) -> {
            Runnable updateOpaqueness = () -> {
                mNotificationShadeWindowController.setLightRevealScrimOpaque(
                        mLightRevealScrim.isScrimOpaque());
            };
            if (opaque) {
                // Delay making the view opaque for a frame, because it needs some time to render
                // otherwise this can lead to a flicker where the scrim doesn't cover the screen
                mLightRevealScrim.post(updateOpaqueness);
            } else {
                updateOpaqueness.run();
            }
        });
        mUnlockedScreenOffAnimationController.initialize(this, mLightRevealScrim);
        updateLightRevealScrimVisibility();

        mNotificationPanelViewController.initDependencies(
                this,
                this::makeExpandedInvisible,
                mNotificationShelfController);

        BackDropView backdrop = mNotificationShadeWindowView.findViewById(R.id.backdrop);
        mMediaManager.setup(backdrop, backdrop.findViewById(R.id.backdrop_front),
                backdrop.findViewById(R.id.backdrop_back), mScrimController, mLockscreenWallpaper);
        float maxWallpaperZoom = mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_wallpaperMaxScale);
        mNotificationShadeDepthControllerLazy.get().addListener(depth -> {
            float scale = MathUtils.lerp(maxWallpaperZoom, 1f, depth);
            backdrop.setPivotX(backdrop.getWidth() / 2f);
            backdrop.setPivotY(backdrop.getHeight() / 2f);
            backdrop.setScaleX(scale);
            backdrop.setScaleY(scale);
        });

        mNotificationPanelViewController.setUserSetupComplete(mUserSetup);

        // Set up the quick settings tile panel
        final View container = mNotificationShadeWindowView.findViewById(R.id.qs_frame);
        if (container != null) {
            FragmentHostManager fragmentHostManager = FragmentHostManager.get(container);
            ExtensionFragmentListener.attachExtensonToFragment(container, QS.TAG, R.id.qs_frame,
                    mExtensionController
                            .newExtension(QS.class)
                            .withPlugin(QS.class)
                            .withDefault(this::createDefaultQSFragment)
                            .build());
            mBrightnessMirrorController = new BrightnessMirrorController(
                    mNotificationShadeWindowView,
                    mNotificationPanelViewController,
                    mNotificationShadeDepthControllerLazy.get(),
                    mBrightnessSliderFactory,
                    (visible) -> {
                        mBrightnessMirrorVisible = visible;
                        updateScrimController();
                    });
            fragmentHostManager.addTagListener(QS.TAG, (tag, f) -> {
                QS qs = (QS) f;
                if (qs instanceof QSFragment) {
                    mQSPanelController = ((QSFragment) qs).getQSPanelController();
                    ((QSFragment) qs).setBrightnessMirrorController(mBrightnessMirrorController);
                }
            });
        }

        mReportRejectedTouch = mNotificationShadeWindowView
                .findViewById(R.id.report_rejected_touch);
        if (mReportRejectedTouch != null) {
            updateReportRejectedTouchVisibility();
            mReportRejectedTouch.setOnClickListener(v -> {
                Uri session = mFalsingManager.reportRejectedTouch();
                if (session == null) { return; }

                StringWriter message = new StringWriter();
                message.write("Build info: ");
                message.write(SystemProperties.get("ro.build.description"));
                message.write("\nSerial number: ");
                message.write(SystemProperties.get("ro.serialno"));
                message.write("\n");

                startActivityDismissingKeyguard(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                                .setType("*/*")
                                .putExtra(Intent.EXTRA_SUBJECT, "Rejected touch report")
                                .putExtra(Intent.EXTRA_STREAM, session)
                                .putExtra(Intent.EXTRA_TEXT, message.toString()),
                        "Share rejected touch report")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        true /* onlyProvisioned */, true /* dismissShade */);
            });
        }

        if (!mPowerManager.isInteractive()) {
            mBroadcastReceiver.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));
        }
        mGestureWakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "sysui:GestureWakeLock");

        // receive broadcasts
        registerBroadcastReceiver();

        IntentFilter demoFilter = new IntentFilter();
        if (DEBUG_MEDIA_FAKE_ARTWORK) {
            demoFilter.addAction(ACTION_FAKE_ARTWORK);
        }
        mContext.registerReceiverAsUser(mDemoReceiver, UserHandle.ALL, demoFilter,
                android.Manifest.permission.DUMP, null);

        // listen for USER_SETUP_COMPLETE setting (per-user)
        mDeviceProvisionedController.addCallback(mUserSetupObserver);
        mUserSetupObserver.onUserSetupChanged();

        // disable profiling bars, since they overlap and clutter the output on app windows
        ThreadedRenderer.overrideProperty("disableProfileBars", "true");

        // Private API call to make the shadows look better for Recents
        ThreadedRenderer.overrideProperty("ambientRatio", String.valueOf(1.5f));
    }


    /**
     * When swiping up to dismiss the lock screen, the panel expansion fraction goes from 1f to 0f.
     * This results in the clock/notifications/other content disappearing off the top of the screen.
     *
     * We also use the expansion fraction to animate in the app/launcher surface from the bottom of
     * the screen, 'pushing' off the notifications and other content. To do this, we dispatch the
     * expansion fraction to the KeyguardViewMediator if we're in the process of dismissing the
     * keyguard.
     */
    private void dispatchPanelExpansionForKeyguardDismiss(float fraction, boolean trackingTouch) {
        // Things that mean we're not swiping to dismiss the keyguard, and should ignore this
        // expansion:
        // - Keyguard isn't even visible.
        // - Keyguard is occluded. Expansion changes here are the shade being expanded over the
        //   occluding activity.
        // - Keyguard is visible, but can't be dismissed (swiping up will show PIN/password prompt).
        // - The SIM is locked, you can't swipe to unlock. If the SIM is locked but there is no
        //   device lock set, canDismissLockScreen returns true even though you should not be able
        //   to dismiss the lock screen until entering the SIM PIN.
        // - QS is expanded and we're swiping - swiping up now will hide QS, not dismiss the
        //   keyguard.
        if (!isKeyguardShowing()
                || mIsOccluded
                || !mKeyguardStateController.canDismissLockScreen()
                || mKeyguardViewMediator.isAnySimPinSecure()
                || (mNotificationPanelViewController.isQsExpanded() && trackingTouch)) {
            return;
        }

        // Otherwise, we should let the keyguard know about this if we're tracking touch, or if we
        // are already animating the keyguard dismiss (since we will need to either finish or cancel
        // the animation).
        if (trackingTouch
                || mKeyguardViewMediator.isAnimatingBetweenKeyguardAndSurfaceBehindOrWillBe()
                || mKeyguardUnlockAnimationController.isUnlockingWithSmartSpaceTransition()) {
            mKeyguardStateController.notifyKeyguardDismissAmountChanged(
                    1f - fraction, trackingTouch);
        }
    }

    private void onPanelExpansionChanged(float fraction, boolean expanded, boolean tracking) {
        dispatchPanelExpansionForKeyguardDismiss(fraction, tracking);

        if (fraction == 0 || fraction == 1) {
            if (getNavigationBarView() != null) {
                getNavigationBarView().onStatusBarPanelStateChanged();
            }
            if (getNotificationPanelViewController() != null) {
                getNotificationPanelViewController().updateSystemUiStateFlags();
            }
        }
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @VisibleForTesting
    protected void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(DevicePolicyManager.ACTION_SHOW_DEVICE_MONITORING_DIALOG);
        filter.addAction(lineageos.content.Intent.ACTION_SCREEN_CAMERA_GESTURE);
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, filter, null, UserHandle.ALL);
    }

    protected QS createDefaultQSFragment() {
        return FragmentHostManager.get(mNotificationShadeWindowView).create(QSFragment.class);
    }

    private void setUpPresenter() {
        // Set up the initial notification state.
        mActivityLaunchAnimator.setCallback(mKeyguardHandler);
        mNotificationAnimationProvider = new NotificationLaunchAnimatorControllerProvider(
                mNotificationShadeWindowViewController,
                mStackScrollerController.getNotificationListContainer(),
                mHeadsUpManager
        );

        // TODO: inject this.
        mPresenter = new StatusBarNotificationPresenter(
                mContext,
                mNotificationPanelViewController,
                mHeadsUpManager,
                mNotificationShadeWindowView,
                mStackScrollerController,
                mDozeScrimController,
                mScrimController,
                mNotificationShadeWindowController,
                mDynamicPrivacyController,
                mKeyguardStateController,
                mKeyguardIndicationController,
                mFeatureFlags,
                this /* statusBar */,
                mShadeController,
                mLockscreenShadeTransitionController,
                mCommandQueue,
                mViewHierarchyManager,
                mLockscreenUserManager,
                mStatusBarStateController,
                mNotifShadeEventSource,
                mEntryManager,
                mMediaManager,
                mGutsManager,
                mKeyguardUpdateMonitor,
                mLockscreenGestureLogger,
                mInitController,
                mNotificationInterruptStateProvider,
                mRemoteInputManager,
                mConfigurationController);

        mNotificationShelfController.setOnActivatedListener(mPresenter);
        mRemoteInputManager.addControllerCallback(mNotificationShadeWindowController);

        mNotificationActivityStarter =
                mStatusBarNotificationActivityStarterBuilder
                        .setStatusBar(this)
                        .setActivityLaunchAnimator(mActivityLaunchAnimator)
                        .setNotificationAnimatorControllerProvider(mNotificationAnimationProvider)
                        .setNotificationPresenter(mPresenter)
                        .setNotificationPanelViewController(mNotificationPanelViewController)
                        .build();
        mStackScrollerController.setNotificationActivityStarter(mNotificationActivityStarter);
        mGutsManager.setNotificationActivityStarter(mNotificationActivityStarter);

        mNotificationsController.initialize(
                this,
                mBubblesOptional,
                mPresenter,
                mStackScrollerController.getNotificationListContainer(),
                mNotificationActivityStarter,
                mPresenter);
    }

    /**
     * Post-init task of {@link #start()}
     * @param state1 disable1 flags
     * @param state2 disable2 flags
     */
    protected void setUpDisableFlags(int state1, int state2) {
        mCommandQueue.disable(mDisplayId, state1, state2, false /* animate */);
    }

    /**
     * Ask the display to wake up if currently dozing, else do nothing
     *
     * @param time when to wake up
     * @param where the view requesting the wakeup
     * @param why the reason for the wake up
     */
    public void wakeUpIfDozing(long time, View where, String why) {
        if (mDozing && !mUnlockedScreenOffAnimationController.isScreenOffAnimationPlaying()) {
            mPowerManager.wakeUp(
                    time, PowerManager.WAKE_REASON_GESTURE, "com.android.systemui:" + why);
            mWakeUpComingFromTouch = true;
            where.getLocationInWindow(mTmpInt2);

            // NOTE, the incoming view can sometimes be the entire container... unsure if
            // this location is valuable enough
            mWakeUpTouchLocation = new PointF(mTmpInt2[0] + where.getWidth() / 2,
                    mTmpInt2[1] + where.getHeight() / 2);
            mFalsingCollector.onScreenOnFromTouch();
        }
    }

    // TODO(b/117478341): This was left such that CarStatusBar can override this method.
    // Try to remove this.
    protected void createNavigationBar(@Nullable RegisterStatusBarResult result) {
        mNavigationBarController.createNavigationBars(true /* includeDefaultDisplay */, result);
    }

    /**
     * Returns the {@link android.view.View.OnTouchListener} that will be invoked when the
     * background window of the status bar is clicked.
     */
    protected View.OnTouchListener getStatusBarWindowTouchListener() {
        return (v, event) -> {
            mAutoHideController.checkUserAutoHide(event);
            mRemoteInputManager.checkRemoteInputOutside(event);
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (mExpandedVisible) {
                    mShadeController.animateCollapsePanels();
                }
            }
            return mNotificationShadeWindowView.onTouchEvent(event);
        };
    }

    private void inflateStatusBarWindow() {
        mStatusBarComponent = mStatusBarComponentFactory.create();
        mFragmentService.addFragmentInstantiationProvider(mStatusBarComponent);

        mNotificationShadeWindowView = mStatusBarComponent.getNotificationShadeWindowView();
        mVisualizerView = mStatusBarComponent.getVisualizerView();
        mNotificationShadeWindowViewController = mStatusBarComponent
                .getNotificationShadeWindowViewController();
        mNotificationShadeWindowController.setNotificationShadeView(mNotificationShadeWindowView);
        mNotificationShadeWindowViewController.setupExpandedStatusBar();
        mNotificationPanelViewController = mStatusBarComponent.getNotificationPanelViewController();
        mStatusBarComponent.getLockIconViewController().init();
        mStackScrollerController = mStatusBarComponent.getNotificationStackScrollLayoutController();
        mStackScroller = mStackScrollerController.getView();

        mNotificationShelfController = mStatusBarComponent.getNotificationShelfController();
        mAuthRippleController = mStatusBarComponent.getAuthRippleController();
        mAuthRippleController.init();

        mHeadsUpManager.addListener(mStatusBarComponent.getStatusBarHeadsUpChangeListener());

        mHeadsUpManager.addListener(mStatusBarComponent.getStatusBarHeadsUpChangeListener());

        // Listen for demo mode changes
        mDemoModeController.addCallback(mStatusBarComponent.getStatusBarDemoMode());

        if (mCommandQueueCallbacks != null) {
            mCommandQueue.removeCallback(mCommandQueueCallbacks);
        }
        mCommandQueueCallbacks = mStatusBarComponent.getStatusBarCommandQueueCallbacks();
        // Connect in to the status bar manager service
        mCommandQueue.addCallback(mCommandQueueCallbacks);
    }

    protected void startKeyguard() {
        Trace.beginSection("StatusBar#startKeyguard");
        mBiometricUnlockController = mBiometricUnlockControllerLazy.get();
        mBiometricUnlockController.setBiometricModeListener(
                new BiometricUnlockController.BiometricModeListener() {
                    @Override
                    public void onResetMode() {
                        setWakeAndUnlocking(false);
                    }

                    @Override
                    public void onModeChanged(int mode) {
                        switch (mode) {
                            case BiometricUnlockController.MODE_WAKE_AND_UNLOCK_FROM_DREAM:
                            case BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING:
                            case BiometricUnlockController.MODE_WAKE_AND_UNLOCK:
                                setWakeAndUnlocking(true);
                        }
                    }

                    @Override
                    public void notifyBiometricAuthModeChanged() {
                        StatusBar.this.notifyBiometricAuthModeChanged();
                    }

                    private void setWakeAndUnlocking(boolean wakeAndUnlocking) {
                        if (getNavigationBarView() != null) {
                            getNavigationBarView().setWakeAndUnlocking(wakeAndUnlocking);
                        }
                    }
                });
        mStatusBarKeyguardViewManager.registerStatusBar(
                /* statusBar= */ this,
                mNotificationPanelViewController,
                mPanelExpansionStateManager,
                mBiometricUnlockController,
                mStackScroller,
                mKeyguardBypassController);
        mKeyguardIndicationController
                .setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);
        mRemoteInputManager.addControllerCallback(mStatusBarKeyguardViewManager);
        mDynamicPrivacyController.setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);

        mLightBarController.setBiometricUnlockController(mBiometricUnlockController);
        mMediaManager.setBiometricUnlockController(mBiometricUnlockController);
        mKeyguardDismissUtil.setDismissHandler(this::executeWhenUnlocked);
        Trace.endSection();
    }

    protected PhoneStatusBarView getStatusBarView() {
        return mStatusBarView;
    }

    public NotificationShadeWindowView getNotificationShadeWindowView() {
        return mNotificationShadeWindowView;
    }

    public NotificationShadeWindowViewController getNotificationShadeWindowViewController() {
        return mNotificationShadeWindowViewController;
    }

    public NotificationPanelViewController getNotificationPanelViewController() {
        return mNotificationPanelViewController;
    }

    public ViewGroup getBouncerContainer() {
        return mNotificationShadeWindowViewController.getBouncerContainer();
    }

    public VisualizerView getVisualizerView() {
        return mVisualizerView;
    }

    public int getStatusBarHeight() {
        return mStatusBarWindowController.getStatusBarHeight();
    }

    public boolean toggleSplitScreenMode(int metricsDockAction, int metricsUndockAction) {
        if (!mSplitScreenOptional.isPresent()) {
            return false;
        }

        final LegacySplitScreen legacySplitScreen = mSplitScreenOptional.get();
        if (legacySplitScreen.isDividerVisible()) {
            if (legacySplitScreen.isMinimized() && !legacySplitScreen.isHomeStackResizable()) {
                // Undocking from the minimized state is not supported
                return false;
            }

            legacySplitScreen.onUndockingTask();
            if (metricsUndockAction != -1) {
                mMetricsLogger.action(metricsUndockAction);
            }
            return true;
        }

        if (legacySplitScreen.splitPrimaryTask()) {
            if (metricsDockAction != -1) {
                mMetricsLogger.action(metricsDockAction);
            }
            return true;
        }

        return false;
    }

    /**
     * Disable QS if device not provisioned.
     * If the user switcher is simple then disable QS during setup because
     * the user intends to use the lock screen user switcher, QS in not needed.
     */
    void updateQsExpansionEnabled() {
        final boolean expandEnabled = mDeviceProvisionedController.isDeviceProvisioned()
                && (mUserSetup || mUserSwitcherController == null
                        || !mUserSwitcherController.isSimpleUserSwitcher())
                && !isShadeDisabled()
                && ((mDisabled2 & StatusBarManager.DISABLE2_QUICK_SETTINGS) == 0)
                && !mDozing
                && !ONLY_CORE_APPS;
        mNotificationPanelViewController.setQsExpansionEnabledPolicy(expandEnabled);
        Log.d(TAG, "updateQsExpansionEnabled - QS Expand enabled: " + expandEnabled);
    }

    public boolean isShadeDisabled() {
        return (mDisabled2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0;
    }

    /**
     * Request a notification update
     * @param reason why we're requesting a notification update
     */
    public void requestNotificationUpdate(String reason) {
        mNotificationsController.requestNotificationUpdate(reason);
    }

    /**
     * Asks {@link KeyguardUpdateMonitor} to run face auth.
     */
    public void requestFaceAuth(boolean userInitiatedRequest) {
        if (!mKeyguardStateController.canDismissLockScreen()) {
            mKeyguardUpdateMonitor.requestFaceAuth(userInitiatedRequest);
        }
    }

    private void updateReportRejectedTouchVisibility() {
        if (mReportRejectedTouch == null) {
            return;
        }
        mReportRejectedTouch.setVisibility(mState == StatusBarState.KEYGUARD && !mDozing
                && mFalsingCollector.isReportingEnabled() ? View.VISIBLE : View.INVISIBLE);
    }

    boolean areNotificationAlertsDisabled() {
        return (mDisabled1 & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0;
    }

    @Override
    public void startActivity(Intent intent, boolean onlyProvisioned, boolean dismissShade,
            int flags) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade, flags);
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade) {
        startActivityDismissingKeyguard(intent, false /* onlyProvisioned */, dismissShade);
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade,
            @Nullable ActivityLaunchAnimator.Controller animationController,
            boolean showOverLockscreenWhenLocked) {
        // Make sure that we dismiss the keyguard if it is directly dismissable or when we don't
        // want to show the activity above it.
        if (mKeyguardStateController.isUnlocked() || !showOverLockscreenWhenLocked) {
            startActivityDismissingKeyguard(intent, false, dismissShade,
                false /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */,
                0 /* flags */, animationController);
            return;
        }

        boolean animate =
                animationController != null && shouldAnimateLaunch(true /* isActivityIntent */,
                        showOverLockscreenWhenLocked);

        ActivityLaunchAnimator.Controller controller = null;
        if (animate) {
            // Wrap the animation controller to dismiss the shade and set
            // mIsLaunchingActivityOverLockscreen during the animation.
            ActivityLaunchAnimator.Controller delegate = wrapAnimationController(
                    animationController, dismissShade);
            controller = new DelegateLaunchAnimatorController(delegate) {
                @Override
                public void onIntentStarted(boolean willAnimate) {
                    getDelegate().onIntentStarted(willAnimate);

                    if (willAnimate) {
                        StatusBar.this.mIsLaunchingActivityOverLockscreen = true;
                    }
                }

                @Override
                public void onLaunchAnimationEnd(boolean isExpandingFullyAbove) {
                    // Set mIsLaunchingActivityOverLockscreen to false before actually finishing the
                    // animation so that we can assume that mIsLaunchingActivityOverLockscreen
                    // being true means that we will collapse the shade (or at least run the
                    // post collapse runnables) later on.
                    StatusBar.this.mIsLaunchingActivityOverLockscreen = false;
                    getDelegate().onLaunchAnimationEnd(isExpandingFullyAbove);
                }

                @Override
                public void onLaunchAnimationCancelled() {
                    // Set mIsLaunchingActivityOverLockscreen to false before actually finishing the
                    // animation so that we can assume that mIsLaunchingActivityOverLockscreen
                    // being true means that we will collapse the shade (or at least run the
                    // post collapse runnables) later on.
                    StatusBar.this.mIsLaunchingActivityOverLockscreen = false;
                    getDelegate().onLaunchAnimationCancelled();
                }
            };
        } else if (dismissShade) {
            // The animation will take care of dismissing the shade at the end of the animation. If
            // we don't animate, collapse it directly.
            collapseShade();
        }

        mActivityLaunchAnimator.startIntentWithAnimation(controller, animate,
                intent.getPackage(), showOverLockscreenWhenLocked, (adapter) -> TaskStackBuilder
                        .create(mContext)
                        .addNextIntent(intent)
                        .startActivities(getActivityOptions(getDisplayId(), adapter),
                                UserHandle.CURRENT));
    }

    /**
     * Whether we are currently animating an activity launch above the lockscreen (occluding
     * activity).
     */
    public boolean isLaunchingActivityOverLockscreen() {
        return mIsLaunchingActivityOverLockscreen;
    }

    @Override
    public void startActivity(Intent intent, boolean onlyProvisioned, boolean dismissShade) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade);
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade, Callback callback) {
        startActivityDismissingKeyguard(intent, false, dismissShade,
                false /* disallowEnterPictureInPictureWhileLaunching */, callback, 0,
                null /* animationController */);
    }

    public void setQsExpanded(boolean expanded) {
        mNotificationShadeWindowController.setQsExpanded(expanded);
        mNotificationPanelViewController.setStatusAccessibilityImportance(expanded
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        mNotificationPanelViewController.updateSystemUiStateFlags();
        if (getNavigationBarView() != null) {
            getNavigationBarView().onStatusBarPanelStateChanged();
        }
    }

    public boolean isWakeUpComingFromTouch() {
        return mWakeUpComingFromTouch;
    }

    public boolean isFalsingThresholdNeeded() {
        return true;
    }

    /**
     * To be called when there's a state change in StatusBarKeyguardViewManager.
     */
    public void onKeyguardViewManagerStatesUpdated() {
        logStateToEventlog();
    }

    public void setPanelExpanded(boolean isExpanded) {
        if (mPanelExpanded != isExpanded) {
            mNotificationLogger.onPanelExpandedChanged(isExpanded);
        }
        mPanelExpanded = isExpanded;
        mStatusBarHideIconsForBouncerManager.setPanelExpandedAndTriggerUpdate(isExpanded);
        mNotificationShadeWindowController.setPanelExpanded(isExpanded);
        mStatusBarStateController.setPanelExpanded(isExpanded);
        if (isExpanded && mStatusBarStateController.getState() != StatusBarState.KEYGUARD) {
            if (DEBUG) {
                Log.v(TAG, "clearing notification effects from Height");
            }
            clearNotificationEffects();
        }

        if (!isExpanded) {
            mRemoteInputManager.onPanelCollapsed();
        }
    }

    public ViewGroup getNotificationScrollLayout() {
        return mStackScroller;
    }

    public boolean isPulsing() {
        return mDozeServiceHost.isPulsing();
    }

    @Nullable
    public View getAmbientIndicationContainer() {
        return mAmbientIndicationContainer;
    }

    /**
     * When the keyguard is showing and covered by a "showWhenLocked" activity it
     * is occluded. This is controlled by {@link com.android.server.policy.PhoneWindowManager}
     *
     * @return whether the keyguard is currently occluded
     */
    public boolean isOccluded() {
        return mIsOccluded;
    }

    public void setOccluded(boolean occluded) {
        mIsOccluded = occluded;
        mStatusBarHideIconsForBouncerManager.setIsOccludedAndTriggerUpdate(occluded);
        mScrimController.setKeyguardOccluded(occluded);
    }

    /** A launch animation was cancelled. */
    //TODO: These can / should probably be moved to NotificationPresenter or ShadeController
    public void onLaunchAnimationCancelled(boolean isLaunchForActivity) {
        if (mPresenter.isPresenterFullyCollapsed() && !mPresenter.isCollapsing()
                && isLaunchForActivity) {
            onClosingFinished();
        } else {
            mShadeController.collapsePanel(true /* animate */);
        }
    }

    /** A launch animation ended. */
    public void onLaunchAnimationEnd(boolean launchIsFullScreen) {
        if (!mPresenter.isCollapsing()) {
            onClosingFinished();
        }
        if (launchIsFullScreen) {
            instantCollapseNotificationPanel();
        }
    }

    /**
     * Whether we should animate an activity launch.
     *
     * Note: This method must be called *before* dismissing the keyguard.
     */
    public boolean shouldAnimateLaunch(boolean isActivityIntent, boolean showOverLockscreen) {
        // TODO(b/184121838): Support launch animations when occluded.
        if (isOccluded()) {
            return false;
        }

        // Always animate if we are not showing the keyguard or if we animate over the lockscreen
        // (without unlocking it).
        if (showOverLockscreen || !mKeyguardStateController.isShowing()) {
            return true;
        }

        // If we are locked and have to dismiss the keyguard, only animate if remote unlock
        // animations are enabled. We also don't animate non-activity launches as they can break the
        // animation.
        // TODO(b/184121838): Support non activity launches on the lockscreen.
        return isActivityIntent && KeyguardService.sEnableRemoteKeyguardGoingAwayAnimation;
    }

    /** Whether we should animate an activity launch. */
    public boolean shouldAnimateLaunch(boolean isActivityIntent) {
        return shouldAnimateLaunch(isActivityIntent, false /* showOverLockscreen */);
    }

    public boolean isDeviceInVrMode() {
        return mPresenter.isDeviceInVrMode();
    }

    public NotificationPresenter getPresenter() {
        return mPresenter;
    }

    @VisibleForTesting
    void setBarStateForTest(int state) {
        mState = state;
    }

    static class KeyboardShortcutsMessage {
        final int mDeviceId;

        KeyboardShortcutsMessage(int deviceId) {
            mDeviceId = deviceId;
        }
    }

    static class AnimateExpandSettingsPanelMessage {
        final String mSubpanel;

        AnimateExpandSettingsPanelMessage(String subpanel) {
            mSubpanel = subpanel;
        }
    }

    private void maybeEscalateHeadsUp() {
        mHeadsUpManager.getAllEntries().forEach(entry -> {
            final StatusBarNotification sbn = entry.getSbn();
            final Notification notification = sbn.getNotification();
            if (notification.fullScreenIntent != null) {
                if (DEBUG) {
                    Log.d(TAG, "converting a heads up to fullScreen");
                }
                try {
                    EventLog.writeEvent(EventLogTags.SYSUI_HEADS_UP_ESCALATION,
                            sbn.getKey());
                    wakeUpForFullScreenIntent();
                    notification.fullScreenIntent.send();
                    entry.notifyFullScreenIntentLaunched();
                } catch (PendingIntent.CanceledException e) {
                }
            }
        });
        mHeadsUpManager.releaseAllImmediately();
    }

    void wakeUpForFullScreenIntent() {
        if (isGoingToSleep() || mDozing) {
            mPowerManager.wakeUp(
                    SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_APPLICATION,
                    "com.android.systemui:full_screen_intent");
            mWakeUpComingFromTouch = false;
            mWakeUpTouchLocation = null;
        }
    }

    void makeExpandedVisible(boolean force) {
        if (SPEW) Log.d(TAG, "Make expanded visible: expanded visible=" + mExpandedVisible);
        if (!force && (mExpandedVisible || !mCommandQueue.panelsEnabled())) {
            return;
        }

        mExpandedVisible = true;

        // Expand the window to encompass the full screen in anticipation of the drag.
        // This is only possible to do atomically because the status bar is at the top of the screen!
        mNotificationShadeWindowController.setPanelVisible(true);

        visibilityChanged(true);
        mCommandQueue.recomputeDisableFlags(mDisplayId, !force /* animate */);
        setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true);
    }

    public void postAnimateCollapsePanels() {
        mMainExecutor.execute(mShadeController::animateCollapsePanels);
    }

    public void postAnimateForceCollapsePanels() {
        mMainExecutor.execute(
                () -> mShadeController.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE,
                true /* force */));
    }

    public void postAnimateOpenPanels() {
        mMessageRouter.sendMessage(MSG_OPEN_SETTINGS_PANEL);
    }

    public boolean isExpandedVisible() {
        return mExpandedVisible;
    }

    public boolean isPanelExpanded() {
        return mPanelExpanded;
    }

    /**
     * Called when another window is about to transfer it's input focus.
     */
    public void onInputFocusTransfer(boolean start, boolean cancel, float velocity) {
        if (!mCommandQueue.panelsEnabled()) {
            return;
        }

        if (start) {
            mNotificationPanelViewController.startWaitingForOpenPanelGesture();
        } else {
            mNotificationPanelViewController.stopWaitingForOpenPanelGesture(cancel, velocity);
        }
    }

    public void animateCollapseQuickSettings() {
        if (mState == StatusBarState.SHADE) {
            mNotificationPanelViewController.collapsePanel(
                    true, false /* delayed */, 1.0f /* speedUpFactor */);
        }
    }

    void makeExpandedInvisible() {
        if (SPEW) Log.d(TAG, "makeExpandedInvisible: mExpandedVisible=" + mExpandedVisible
                + " mExpandedVisible=" + mExpandedVisible);

        if (!mExpandedVisible || mNotificationShadeWindowView == null) {
            return;
        }

        // Ensure the panel is fully collapsed (just in case; bug 6765842, 7260868)
        mNotificationPanelViewController.collapsePanel(/*animate=*/ false, false /* delayed*/,
                1.0f /* speedUpFactor */);

        mNotificationPanelViewController.closeQs();

        mExpandedVisible = false;
        visibilityChanged(false);

        // Update the visibility of notification shade and status bar window.
        mNotificationShadeWindowController.setPanelVisible(false);
        mStatusBarWindowController.setForceStatusBarVisible(false);

        // Close any guts that might be visible
        mGutsManager.closeAndSaveGuts(true /* removeLeavebehind */, true /* force */,
                true /* removeControls */, -1 /* x */, -1 /* y */, true /* resetMenu */);

        mShadeController.runPostCollapseRunnables();
        setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);
        if (!mNotificationActivityStarter.isCollapsingToShowActivityOverLockscreen()) {
            showBouncerOrLockScreenIfKeyguard();
        } else if (DEBUG) {
            Log.d(TAG, "Not showing bouncer due to activity showing over lockscreen");
        }
        mCommandQueue.recomputeDisableFlags(
                mDisplayId,
                mNotificationPanelViewController.hideStatusBarIconsWhenExpanded() /* animate */);

        // Trimming will happen later if Keyguard is showing - doing it here might cause a jank in
        // the bouncer appear animation.
        if (!mStatusBarKeyguardViewManager.isShowing()) {
            WindowManagerGlobal.getInstance().trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        }
    }

    private void adjustBrightness(int x) {
        mBrightnessChanged = true;
        float raw = ((float) x) / getDisplayWidth();

        // Add a padding to the brightness control on both sides to
        // make it easier to reach min/max brightness
        float padded = Math.min(1.0f - BRIGHTNESS_CONTROL_PADDING,
                Math.max(BRIGHTNESS_CONTROL_PADDING, raw));
        float value = (padded - BRIGHTNESS_CONTROL_PADDING) /
                (1 - (2.0f * BRIGHTNESS_CONTROL_PADDING));
        if (mAutomaticBrightness) {
            float adj = (2 * value) - 1;
            adj = Math.max(adj, -1);
            adj = Math.min(adj, 1);
            final float val = adj;
            mDisplayManager.setTemporaryAutoBrightnessAdjustment(val);
            AsyncTask.execute(new Runnable() {
                public void run() {
                    Settings.System.putFloatForUser(mContext.getContentResolver(),
                            Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, val,
                            UserHandle.USER_CURRENT);
                }
            });
        } else {
            int newBrightness = mMinBrightness + (int) Math.round(value *
                    (PowerManager.BRIGHTNESS_ON - mMinBrightness));
            newBrightness = Math.min(newBrightness, PowerManager.BRIGHTNESS_ON);
            newBrightness = Math.max(newBrightness, mMinBrightness);
            final int val = newBrightness;
            mDisplayManager.setTemporaryBrightness(mDisplayId, val);
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    Settings.System.putIntForUser(mContext.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS, val,
                            UserHandle.USER_CURRENT);
                }
            });
        }
    }

    private void brightnessControl(MotionEvent event) {
        final int action = event.getAction();
        final int x = (int) event.getRawX();
        final int y = (int) event.getRawY();
        mQuickQsOffsetHeight = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height);
        if (action == MotionEvent.ACTION_DOWN) {
            if (y < mQuickQsOffsetHeight) {
                mLinger = 0;
                mInitialTouchX = x;
                mInitialTouchY = y;
                mJustPeeked = true;
                mMessageRouter.cancelMessages(MSG_LONG_PRESS_BRIGHTNESS_CHANGE);
                mMessageRouter.sendMessageDelayed(MSG_LONG_PRESS_BRIGHTNESS_CHANGE,
                        BRIGHTNESS_CONTROL_LONG_PRESS_TIMEOUT);
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (y < mQuickQsOffsetHeight && mJustPeeked) {
                if (mLinger > BRIGHTNESS_CONTROL_LINGER_THRESHOLD) {
                    adjustBrightness(x);
                } else {
                    final int xDiff = Math.abs(x - mInitialTouchX);
                    final int yDiff = Math.abs(y - mInitialTouchY);
                    final int touchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
                    if (xDiff > yDiff) {
                        mLinger++;
                    }
                    if (xDiff > touchSlop || yDiff > touchSlop) {
                        mMessageRouter.cancelMessages(MSG_LONG_PRESS_BRIGHTNESS_CHANGE);
                    }
                }
            } else {
                if (y > mQuickQsOffsetHeight) {
                    mJustPeeked = false;
                }
                mMessageRouter.cancelMessages(MSG_LONG_PRESS_BRIGHTNESS_CHANGE);
            }
        } else if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            mMessageRouter.cancelMessages(MSG_LONG_PRESS_BRIGHTNESS_CHANGE);
        }
    }

    void onLongPressBrightnessChange() {
        mStatusBarView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        adjustBrightness(mInitialTouchX);
        mLinger = BRIGHTNESS_CONTROL_LINGER_THRESHOLD + 1;
    }

    /** Called when a touch event occurred on {@link PhoneStatusBarView}. */
    public void onTouchEvent(MotionEvent event) {
        // TODO(b/202981994): Move this touch debugging to a central location. (Right now, it's
        //   split between NotificationPanelViewController and here.)
        if (DEBUG_GESTURES) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                EventLog.writeEvent(EventLogTags.SYSUI_STATUSBAR_TOUCH,
                        event.getActionMasked(), (int) event.getX(), (int) event.getY(),
                        mDisabled1, mDisabled2);
            }

        }

        if (SPEW) {
            Log.d(TAG, "Touch: rawY=" + event.getRawY() + " event=" + event + " mDisabled1="
                    + mDisabled1 + " mDisabled2=" + mDisabled2);
        } else if (CHATTY) {
            if (event.getAction() != MotionEvent.ACTION_MOVE) {
                Log.d(TAG, String.format(
                            "panel: %s at (%f, %f) mDisabled1=0x%08x mDisabled2=0x%08x",
                            MotionEvent.actionToString(event.getAction()),
                            event.getRawX(), event.getRawY(), mDisabled1, mDisabled2));
            }
        }

        if (DEBUG_GESTURES) {
            mGestureRec.add(event);
        }

        if (mBrightnessControl) {
            brightnessControl(event);
            if ((mDisabled1 & StatusBarManager.DISABLE_EXPAND) != 0) {
                return;
            }
        }

        final boolean upOrCancel =
                event.getAction() == MotionEvent.ACTION_UP ||
                event.getAction() == MotionEvent.ACTION_CANCEL;

        if (mStatusBarWindowState == WINDOW_STATE_SHOWING) {
            setInteracting(StatusBarManager.WINDOW_STATUS_BAR, !upOrCancel || mExpandedVisible);
        }
        if (mBrightnessChanged && upOrCancel) {
            mBrightnessChanged = false;
            if (mJustPeeked && mExpandedVisible) {
                mNotificationPanelViewController.fling(10, false);
            }
        }
    }

    boolean isSameStatusBarState(int state) {
        return mStatusBarWindowState == state;
    }

    public GestureRecorder getGestureRecorder() {
        return mGestureRec;
    }

    public BiometricUnlockController getBiometricUnlockController() {
        return mBiometricUnlockController;
    }

    void showTransientUnchecked() {
        if (!mTransientShown) {
            mTransientShown = true;
            mNoAnimationOnNextBarModeChange = true;
            maybeUpdateBarMode();
        }
    }


    void clearTransient() {
        if (mTransientShown) {
            mTransientShown = false;
            maybeUpdateBarMode();
        }
    }

    private void maybeUpdateBarMode() {
        final int barMode = barMode(mTransientShown, mAppearance);
        if (updateBarMode(barMode)) {
            mLightBarController.onStatusBarModeChanged(barMode);
            updateBubblesVisibility();
        }
    }

    private boolean updateBarMode(int barMode) {
        if (mStatusBarMode != barMode) {
            mStatusBarMode = barMode;
            checkBarModes();
            mAutoHideController.touchAutoHide();
            return true;
        }
        return false;
    }

    private @TransitionMode int barMode(boolean isTransient, int appearance) {
        final int lightsOutOpaque = APPEARANCE_LOW_PROFILE_BARS | APPEARANCE_OPAQUE_STATUS_BARS;
        if (mOngoingCallController.hasOngoingCall() && mIsFullscreen) {
            return MODE_SEMI_TRANSPARENT;
        } else if (isTransient) {
            return MODE_SEMI_TRANSPARENT;
        } else if ((appearance & lightsOutOpaque) == lightsOutOpaque) {
            return MODE_LIGHTS_OUT;
        } else if ((appearance & APPEARANCE_LOW_PROFILE_BARS) != 0) {
            return MODE_LIGHTS_OUT_TRANSPARENT;
        } else if ((appearance & APPEARANCE_OPAQUE_STATUS_BARS) != 0) {
            return MODE_OPAQUE;
        } else if ((appearance & APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS) != 0) {
            return MODE_SEMI_TRANSPARENT;
        } else {
            return MODE_TRANSPARENT;
        }
    }

    protected void showWirelessChargingAnimation(int batteryLevel) {
        showChargingAnimation(batteryLevel, UNKNOWN_BATTERY_LEVEL, 0);
    }

    protected void showChargingAnimation(int batteryLevel, int transmittingBatteryLevel,
            long animationDelay) {
        WirelessChargingAnimation.makeWirelessChargingAnimation(mContext, null,
                transmittingBatteryLevel, batteryLevel,
                new WirelessChargingAnimation.Callback() {
                    @Override
                    public void onAnimationStarting() {
                        mNotificationShadeWindowController.setRequestTopUi(true, TAG);
                    }

                    @Override
                    public void onAnimationEnded() {
                        mNotificationShadeWindowController.setRequestTopUi(false, TAG);
                    }
                }, false, sUiEventLogger).show(animationDelay);
    }

    protected BarTransitions getStatusBarTransitions() {
        return mNotificationShadeWindowViewController.getBarTransitions();
    }

    public void checkBarModes() {
        if (mDemoModeController.isInDemoMode()) return;
        if (mNotificationShadeWindowViewController != null && getStatusBarTransitions() != null) {
            checkBarMode(mStatusBarMode, mStatusBarWindowState, getStatusBarTransitions());
        }
        mNavigationBarController.checkNavBarModes(mDisplayId);
        mNoAnimationOnNextBarModeChange = false;
    }

    // Called by NavigationBarFragment
    public void setQsScrimEnabled(boolean scrimEnabled) {
        mNotificationPanelViewController.setQsScrimEnabled(scrimEnabled);
    }

    /** Temporarily hides Bubbles if the status bar is hidden. */
    void updateBubblesVisibility() {
        mBubblesOptional.ifPresent(bubbles -> bubbles.onStatusBarVisibilityChanged(
                mStatusBarMode != MODE_LIGHTS_OUT
                        && mStatusBarMode != MODE_LIGHTS_OUT_TRANSPARENT
                        && !mStatusBarWindowHidden));
    }

    void checkBarMode(@TransitionMode int mode, @WindowVisibleState int windowState,
            BarTransitions transitions) {
        final boolean anim = !mNoAnimationOnNextBarModeChange && mDeviceInteractive
                && windowState != WINDOW_STATE_HIDDEN;
        transitions.transitionTo(mode, anim);
    }

    private void finishBarAnimations() {
        if (mNotificationShadeWindowController != null
                && mNotificationShadeWindowViewController.getBarTransitions() != null) {
            mNotificationShadeWindowViewController.getBarTransitions().finishAnimations();
        }
        mNavigationBarController.finishBarAnimations(mDisplayId);
    }

    private final Runnable mCheckBarModes = this::checkBarModes;

    public void setInteracting(int barWindow, boolean interacting) {
        mInteractingWindows = interacting
                ? (mInteractingWindows | barWindow)
                : (mInteractingWindows & ~barWindow);
        if (mInteractingWindows != 0) {
            mAutoHideController.suspendAutoHide();
        } else {
            mAutoHideController.resumeSuspendedAutoHide();
        }
        checkBarModes();
    }

    private void dismissVolumeDialog() {
        if (mVolumeComponent != null) {
            mVolumeComponent.dismissNow();
        }
    }

    public static String viewInfo(View v) {
        return "[(" + v.getLeft() + "," + v.getTop() + ")(" + v.getRight() + "," + v.getBottom()
                + ") " + v.getWidth() + "x" + v.getHeight() + "]";
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mQueueLock) {
            pw.println("Current Status Bar state:");
            pw.println("  mExpandedVisible=" + mExpandedVisible);
            pw.println("  mDisplayMetrics=" + mDisplayMetrics);
            pw.println("  mStackScroller: " + viewInfo(mStackScroller));
            pw.println("  mStackScroller: " + viewInfo(mStackScroller)
                    + " scroll " + mStackScroller.getScrollX()
                    + "," + mStackScroller.getScrollY());
        }

        pw.print("  mInteractingWindows="); pw.println(mInteractingWindows);
        pw.print("  mStatusBarWindowState=");
        pw.println(windowStateToString(mStatusBarWindowState));
        pw.print("  mStatusBarMode=");
        pw.println(BarTransitions.modeToString(mStatusBarMode));
        pw.print("  mDozing="); pw.println(mDozing);
        pw.print("  mWallpaperSupported= "); pw.println(mWallpaperSupported);

        pw.println("  ShadeWindowView: ");
        if (mNotificationShadeWindowViewController != null) {
            mNotificationShadeWindowViewController.dump(fd, pw, args);
            dumpBarTransitions(pw, "PhoneStatusBarTransitions",
                    mNotificationShadeWindowViewController.getBarTransitions());
        }

        pw.println("  mMediaManager: ");
        if (mMediaManager != null) {
            mMediaManager.dump(fd, pw, args);
        }

        pw.println("  Panels: ");
        if (mNotificationPanelViewController != null) {
            pw.println("    mNotificationPanel="
                    + mNotificationPanelViewController.getView() + " params="
                    + mNotificationPanelViewController.getView().getLayoutParams().debug(""));
            pw.print  ("      ");
            mNotificationPanelViewController.dump(fd, pw, args);
        }
        pw.println("  mStackScroller: ");
        if (mStackScroller != null) {
            DumpUtilsKt.withIndenting(pw, ipw -> {
                // Triple indent until we rewrite the rest of this dump()
                ipw.increaseIndent();
                ipw.increaseIndent();
                mStackScroller.dump(fd, ipw, args);
                ipw.decreaseIndent();
                ipw.decreaseIndent();
            });
        }
        pw.println("  Theme:");
        String nightMode = mUiModeManager == null ? "null" : mUiModeManager.getNightMode() + "";
        pw.println("    dark theme: " + nightMode +
                " (auto: " + UiModeManager.MODE_NIGHT_AUTO +
                ", yes: " + UiModeManager.MODE_NIGHT_YES +
                ", no: " + UiModeManager.MODE_NIGHT_NO + ")");
        final boolean lightWpTheme = mContext.getThemeResId()
                == R.style.Theme_SystemUI_LightWallpaper;
        pw.println("    light wallpaper theme: " + lightWpTheme);

        if (mKeyguardIndicationController != null) {
            mKeyguardIndicationController.dump(fd, pw, args);
        }

        if (mScrimController != null) {
            mScrimController.dump(fd, pw, args);
        }

        if (mLightRevealScrim != null) {
            pw.println(
                    "mLightRevealScrim.getRevealEffect(): " + mLightRevealScrim.getRevealEffect());
            pw.println(
                    "mLightRevealScrim.getRevealAmount(): " + mLightRevealScrim.getRevealAmount());
        }

        if (mStatusBarKeyguardViewManager != null) {
            mStatusBarKeyguardViewManager.dump(pw);
        }

        mNotificationsController.dump(fd, pw, args, DUMPTRUCK);

        if (DEBUG_GESTURES) {
            pw.print("  status bar gestures: ");
            mGestureRec.dump(fd, pw, args);
        }

        if (mHeadsUpManager != null) {
            mHeadsUpManager.dump(fd, pw, args);
        } else {
            pw.println("  mHeadsUpManager: null");
        }

        if (mStatusBarTouchableRegionManager != null) {
            mStatusBarTouchableRegionManager.dump(fd, pw, args);
        } else {
            pw.println("  mStatusBarTouchableRegionManager: null");
        }

        if (mLightBarController != null) {
            mLightBarController.dump(fd, pw, args);
        }

        pw.println("SharedPreferences:");
        for (Map.Entry<String, ?> entry : Prefs.getAll(mContext).entrySet()) {
            pw.print("  "); pw.print(entry.getKey()); pw.print("="); pw.println(entry.getValue());
        }

        pw.println("Camera gesture intents:");
        pw.println("   Insecure camera: " + CameraIntents.getInsecureCameraIntent(mContext));
        pw.println("   Secure camera: " + CameraIntents.getSecureCameraIntent(mContext));
        pw.println("   Override package: "
                + CameraIntents.getOverrideCameraPackage(mContext));
    }

    public static void dumpBarTransitions(
            PrintWriter pw, String var, @Nullable BarTransitions transitions) {
        pw.print("  "); pw.print(var); pw.print(".BarTransitions.mMode=");
        if (transitions != null) {
            pw.println(BarTransitions.modeToString(transitions.getMode()));
        } else {
            pw.println("Unknown");
        }
    }

    public void createAndAddWindows(@Nullable RegisterStatusBarResult result) {
        makeStatusBarView(result);
        mNotificationShadeWindowController.attach();
        mStatusBarWindowController.attach();
    }

    // called by makeStatusbar and also by PhoneStatusBarView
    void updateDisplaySize() {
        mDisplay.getMetrics(mDisplayMetrics);
        mDisplay.getSize(mCurrentDisplaySize);
        if (DEBUG_GESTURES) {
            mGestureRec.tag("display",
                    String.format("%dx%d", mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels));
        }
    }

    float getDisplayDensity() {
        return mDisplayMetrics.density;
    }

    public float getDisplayWidth() {
        return mDisplayMetrics.widthPixels;
    }

    public float getDisplayHeight() {
        return mDisplayMetrics.heightPixels;
    }

    int getRotation() {
        return mDisplay.getRotation();
    }

    int getDisplayId() {
        return mDisplayId;
    }

    public void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            boolean dismissShade, int flags) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade,
                false /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */,
                flags, null /* animationController */);
    }

    public void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            boolean dismissShade) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade, 0);
    }

    void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            final boolean dismissShade, final boolean disallowEnterPictureInPictureWhileLaunching,
            final Callback callback, int flags,
            @Nullable ActivityLaunchAnimator.Controller animationController) {
        if (onlyProvisioned && !mDeviceProvisionedController.isDeviceProvisioned()) return;

        final boolean willLaunchResolverActivity =
                mActivityIntentHelper.wouldLaunchResolverActivity(intent,
                        mLockscreenUserManager.getCurrentUserId());

        boolean animate =
                animationController != null && !willLaunchResolverActivity && shouldAnimateLaunch(
                        true /* isActivityIntent */);
        ActivityLaunchAnimator.Controller animController =
                animationController != null ? wrapAnimationController(animationController,
                        dismissShade) : null;

        // If we animate, we will dismiss the shade only once the animation is done. This is taken
        // care of by the StatusBarLaunchAnimationController.
        boolean dismissShadeDirectly = dismissShade && animController == null;

        Runnable runnable = () -> {
            mAssistManagerLazy.get().hideAssist();
            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.addFlags(flags);
            int[] result = new int[]{ActivityManager.START_CANCELED};

            mActivityLaunchAnimator.startIntentWithAnimation(animController,
                    animate, intent.getPackage(), (adapter) -> {
                        ActivityOptions options = new ActivityOptions(
                                getActivityOptions(mDisplayId, adapter));
                        options.setDisallowEnterPictureInPictureWhileLaunching(
                                disallowEnterPictureInPictureWhileLaunching);
                        if (CameraIntents.isInsecureCameraIntent(intent)) {
                            // Normally an activity will set it's requested rotation
                            // animation on its window. However when launching an activity
                            // causes the orientation to change this is too late. In these cases
                            // the default animation is used. This doesn't look good for
                            // the camera (as it rotates the camera contents out of sync
                            // with physical reality). So, we ask the WindowManager to
                            // force the crossfade animation if an orientation change
                            // happens to occur during the launch.
                            options.setRotationAnimationHint(
                                    WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS);
                        }
                        if (Settings.Panel.ACTION_VOLUME.equals(intent.getAction())) {
                            // Settings Panel is implemented as activity(not a dialog), so
                            // underlying app is paused and may enter picture-in-picture mode
                            // as a result.
                            // So we need to disable picture-in-picture mode here
                            // if it is volume panel.
                            options.setDisallowEnterPictureInPictureWhileLaunching(true);
                        }

                        try {
                            result[0] = ActivityTaskManager.getService().startActivityAsUser(
                                    null, mContext.getBasePackageName(),
                                    mContext.getAttributionTag(),
                                    intent,
                                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                                    null, null, 0, Intent.FLAG_ACTIVITY_NEW_TASK, null,
                                    options.toBundle(), UserHandle.CURRENT.getIdentifier());
                        } catch (RemoteException e) {
                            Log.w(TAG, "Unable to start activity", e);
                        }
                        return result[0];
                    });

            if (callback != null) {
                callback.onActivityStarted(result[0]);
            }
        };
        Runnable cancelRunnable = () -> {
            if (callback != null) {
                callback.onActivityStarted(ActivityManager.START_CANCELED);
            }
        };
        executeRunnableDismissingKeyguard(runnable, cancelRunnable, dismissShadeDirectly,
                willLaunchResolverActivity, true /* deferred */, animate);
    }

    @Nullable
    private ActivityLaunchAnimator.Controller wrapAnimationController(
            ActivityLaunchAnimator.Controller animationController, boolean dismissShade) {
        View rootView = animationController.getLaunchContainer().getRootView();

        Optional<ActivityLaunchAnimator.Controller> controllerFromStatusBar =
                mStatusBarWindowController.wrapAnimationControllerIfInStatusBar(
                        rootView, animationController);
        if (controllerFromStatusBar.isPresent()) {
            return controllerFromStatusBar.get();
        }

        if (dismissShade && rootView == mNotificationShadeWindowView) {
            // We are animating a view in the shade. We have to make sure that we collapse it when
            // the animation ends or is cancelled.
            return new StatusBarLaunchAnimatorController(animationController, this,
                    true /* isLaunchForActivity */);
        }

        return animationController;
    }

    public void readyForKeyguardDone() {
        mStatusBarKeyguardViewManager.readyForKeyguardDone();
    }

    public void executeRunnableDismissingKeyguard(final Runnable runnable,
            final Runnable cancelAction,
            final boolean dismissShade,
            final boolean afterKeyguardGone,
            final boolean deferred) {
        executeRunnableDismissingKeyguard(runnable, cancelAction, dismissShade, afterKeyguardGone,
                deferred, false /* willAnimateOnKeyguard */);
    }

    public void executeRunnableDismissingKeyguard(final Runnable runnable,
            final Runnable cancelAction,
            final boolean dismissShade,
            final boolean afterKeyguardGone,
            final boolean deferred,
            final boolean willAnimateOnKeyguard) {
        OnDismissAction onDismissAction = new OnDismissAction() {
            @Override
            public boolean onDismiss() {
                if (runnable != null) {
                    if (mStatusBarKeyguardViewManager.isShowing()
                            && mStatusBarKeyguardViewManager.isOccluded()) {
                        mStatusBarKeyguardViewManager.addAfterKeyguardGoneRunnable(runnable);
                    } else {
                        mMainExecutor.execute(runnable);
                    }
                }
                if (dismissShade) {
                    if (mExpandedVisible && !mBouncerShowing) {
                        mShadeController.animateCollapsePanels(
                                CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL,
                                true /* force */, true /* delayed*/);
                    } else {

                        // Do it after DismissAction has been processed to conserve the needed
                        // ordering.
                        mMainExecutor.execute(mShadeController::runPostCollapseRunnables);
                    }
                } else if (StatusBar.this.isInLaunchTransition()
                        && mNotificationPanelViewController.isLaunchTransitionFinished()) {

                    // We are not dismissing the shade, but the launch transition is already
                    // finished,
                    // so nobody will call readyForKeyguardDone anymore. Post it such that
                    // keyguardDonePending gets called first.
                    mMainExecutor.execute(mStatusBarKeyguardViewManager::readyForKeyguardDone);
                }
                return deferred;
            }

            @Override
            public boolean willRunAnimationOnKeyguard() {
                return willAnimateOnKeyguard;
            }
        };
        dismissKeyguardThenExecute(onDismissAction, cancelAction, afterKeyguardGone);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Trace.beginSection("StatusBar#onReceive");
            if (DEBUG) Log.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                KeyboardShortcuts.dismiss();
                mRemoteInputManager.closeRemoteInputs();
                if (mBubblesOptional.isPresent() && mBubblesOptional.get().isStackExpanded()) {
                    mBubblesOptional.get().collapseStack();
                }
                if (mLockscreenUserManager.isCurrentProfile(getSendingUserId())) {
                    int flags = CommandQueue.FLAG_EXCLUDE_NONE;
                    String reason = intent.getStringExtra("reason");
                    if (reason != null && reason.equals(SYSTEM_DIALOG_REASON_RECENT_APPS)) {
                        flags |= CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL;
                    }
                    mShadeController.animateCollapsePanels(flags);
                }
            }
            else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (mNotificationShadeWindowController != null) {
                    mNotificationShadeWindowController.setNotTouchable(false);
                }
                if (mBubblesOptional.isPresent() && mBubblesOptional.get().isStackExpanded()) {
                    // Post to main thread, since updating the UI.
                    mMainExecutor.execute(() -> mBubblesOptional.get().collapseStack());
                }
                finishBarAnimations();
                resetUserExpandedStates();
            }
            else if (DevicePolicyManager.ACTION_SHOW_DEVICE_MONITORING_DIALOG.equals(action)) {
                mQSPanelController.showDeviceMonitoringDialog();
            }
            else if (lineageos.content.Intent.ACTION_SCREEN_CAMERA_GESTURE.equals(action)) {
                boolean userSetupComplete = Settings.Secure.getInt(mContext.getContentResolver(),
                        Settings.Secure.USER_SETUP_COMPLETE, 0) != 0;
                if (!userSetupComplete) {
                    if (DEBUG) Log.d(TAG, String.format(
                            "userSetupComplete = %s, ignoring camera launch gesture.",
                            userSetupComplete));
                    return;
                }

                // This gets executed before we will show Keyguard, so post it in order that the
                // state is correct.
                mMainExecutor.execute(() -> mCommandQueueCallbacks.onCameraLaunchGestureDetected(
                        StatusBarManager.CAMERA_LAUNCH_SOURCE_SCREEN_GESTURE));
            }
            Trace.endSection();
        }
    };

    private final BroadcastReceiver mDemoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            if (ACTION_FAKE_ARTWORK.equals(action)) {
                if (DEBUG_MEDIA_FAKE_ARTWORK) {
                    mPresenter.updateMediaMetaData(true, true);
                }
            }
        }
    };

    public void resetUserExpandedStates() {
        mNotificationsController.resetUserExpandedStates();
    }

    private void executeWhenUnlocked(OnDismissAction action, boolean requiresShadeOpen,
            boolean afterKeyguardGone) {
        if (mStatusBarKeyguardViewManager.isShowing() && requiresShadeOpen) {
            mStatusBarStateController.setLeaveOpenOnKeyguardHide(true);
        }
        dismissKeyguardThenExecute(action, null /* cancelAction */,
                afterKeyguardGone /* afterKeyguardGone */);
    }

    protected void dismissKeyguardThenExecute(OnDismissAction action, boolean afterKeyguardGone) {
        dismissKeyguardThenExecute(action, null /* cancelRunnable */, afterKeyguardGone);
    }

    @Override
    public void dismissKeyguardThenExecute(OnDismissAction action, Runnable cancelAction,
            boolean afterKeyguardGone) {
        if (mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_ASLEEP
                && mKeyguardStateController.canDismissLockScreen()
                && !mStatusBarStateController.leaveOpenOnKeyguardHide()
                && mDozeServiceHost.isPulsing()) {
            // Reuse the biometric wake-and-unlock transition if we dismiss keyguard from a pulse.
            // TODO: Factor this transition out of BiometricUnlockController.
            mBiometricUnlockController.startWakeAndUnlock(
                    BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING);
        }
        if (mStatusBarKeyguardViewManager.isShowing()) {
            mStatusBarKeyguardViewManager.dismissWithAction(action, cancelAction,
                    afterKeyguardGone);
        } else {
            action.onDismiss();
        }
    }
    /**
     * Notify the shade controller that the current user changed
     *
     * @param newUserId userId of the new user
     */
    public void setLockscreenUser(int newUserId) {
        if (mLockscreenWallpaper != null) {
            mLockscreenWallpaper.setCurrentUser(newUserId);
        }
        mScrimController.setCurrentUser(newUserId);
        if (mWallpaperSupported) {
            mWallpaperChangedReceiver.onReceive(mContext, null);
        }
    }

    /**
     * Reload some of our resources when the configuration changes.
     *
     * We don't reload everything when the configuration changes -- we probably
     * should, but getting that smooth is tough.  Someday we'll fix that.  In the
     * meantime, just update the things that we know change.
     */
    void updateResources() {
        // Update the quick setting tiles
        if (mQSPanelController != null) {
            mQSPanelController.updateResources();
        }

        if (mStatusBarWindowController != null) {
            mStatusBarWindowController.refreshStatusBarHeight();
        }

        if (mStatusBarView != null) {
            mStatusBarView.updateResources();
        }
        if (mNotificationPanelViewController != null) {
            mNotificationPanelViewController.updateResources();
        }
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.updateResources();
        }
        if (mStatusBarKeyguardViewManager != null) {
            mStatusBarKeyguardViewManager.updateResources();
        }

        mPowerButtonReveal = new PowerButtonReveal(mContext.getResources().getDimensionPixelSize(
                com.android.systemui.R.dimen.physical_power_button_center_screen_location_y));
    }

    // Visibility reporting
    protected void handleVisibleToUserChanged(boolean visibleToUser) {
        if (visibleToUser) {
            handleVisibleToUserChangedImpl(visibleToUser);
            mNotificationLogger.startNotificationLogging();
        } else {
            mNotificationLogger.stopNotificationLogging();
            handleVisibleToUserChangedImpl(visibleToUser);
        }
    }

    // Visibility reporting
    void handleVisibleToUserChangedImpl(boolean visibleToUser) {
        if (visibleToUser) {
            /* The LEDs are turned off when the notification panel is shown, even just a little bit.
             * See also StatusBar.setPanelExpanded for another place where we attempt to do this. */
            boolean pinnedHeadsUp = mHeadsUpManager.hasPinnedHeadsUp();
            boolean clearNotificationEffects =
                    !mPresenter.isPresenterFullyCollapsed() &&
                            (mState == StatusBarState.SHADE
                                    || mState == StatusBarState.SHADE_LOCKED);
            int notificationLoad = mNotificationsController.getActiveNotificationsCount();
            if (pinnedHeadsUp && mPresenter.isPresenterFullyCollapsed()) {
                notificationLoad = 1;
            }
            final int finalNotificationLoad = notificationLoad;
            mUiBgExecutor.execute(() -> {
                try {
                    mBarService.onPanelRevealed(clearNotificationEffects,
                            finalNotificationLoad);
                } catch (RemoteException ex) {
                    // Won't fail unless the world has ended.
                }
            });
        } else {
            mUiBgExecutor.execute(() -> {
                try {
                    mBarService.onPanelHidden();
                } catch (RemoteException ex) {
                    // Won't fail unless the world has ended.
                }
            });
        }

    }

    private void logStateToEventlog() {
        boolean isShowing = mStatusBarKeyguardViewManager.isShowing();
        boolean isOccluded = mStatusBarKeyguardViewManager.isOccluded();
        boolean isBouncerShowing = mStatusBarKeyguardViewManager.isBouncerShowing();
        boolean isSecure = mKeyguardStateController.isMethodSecure();
        boolean unlocked = mKeyguardStateController.canDismissLockScreen();
        int stateFingerprint = getLoggingFingerprint(mState,
                isShowing,
                isOccluded,
                isBouncerShowing,
                isSecure,
                unlocked);
        if (stateFingerprint != mLastLoggedStateFingerprint) {
            if (mStatusBarStateLog == null) {
                mStatusBarStateLog = new LogMaker(MetricsEvent.VIEW_UNKNOWN);
            }
            mMetricsLogger.write(mStatusBarStateLog
                    .setCategory(isBouncerShowing ? MetricsEvent.BOUNCER : MetricsEvent.LOCKSCREEN)
                    .setType(isShowing ? MetricsEvent.TYPE_OPEN : MetricsEvent.TYPE_CLOSE)
                    .setSubtype(isSecure ? 1 : 0));
            EventLogTags.writeSysuiStatusBarState(mState,
                    isShowing ? 1 : 0,
                    isOccluded ? 1 : 0,
                    isBouncerShowing ? 1 : 0,
                    isSecure ? 1 : 0,
                    unlocked ? 1 : 0);
            mLastLoggedStateFingerprint = stateFingerprint;

            StringBuilder uiEventValueBuilder = new StringBuilder();
            uiEventValueBuilder.append(isBouncerShowing ? "BOUNCER" : "LOCKSCREEN");
            uiEventValueBuilder.append(isShowing ? "_OPEN" : "_CLOSE");
            uiEventValueBuilder.append(isSecure ? "_SECURE" : "_INSECURE");
            sUiEventLogger.log(StatusBarUiEvent.valueOf(uiEventValueBuilder.toString()));
        }
    }

    /**
     * Returns a fingerprint of fields logged to eventlog
     */
    private static int getLoggingFingerprint(int statusBarState, boolean keyguardShowing,
            boolean keyguardOccluded, boolean bouncerShowing, boolean secure,
            boolean currentlyInsecure) {
        // Reserve 8 bits for statusBarState. We'll never go higher than
        // that, right? Riiiight.
        return (statusBarState & 0xFF)
                | ((keyguardShowing   ? 1 : 0) <<  8)
                | ((keyguardOccluded  ? 1 : 0) <<  9)
                | ((bouncerShowing    ? 1 : 0) << 10)
                | ((secure            ? 1 : 0) << 11)
                | ((currentlyInsecure ? 1 : 0) << 12);
    }

    @Override
    public void postQSRunnableDismissingKeyguard(final Runnable runnable) {
        mMainExecutor.execute(() -> {
            mStatusBarStateController.setLeaveOpenOnKeyguardHide(true);
            executeRunnableDismissingKeyguard(
                    () -> mMainExecutor.execute(runnable), null, false, false, false);
        });
    }

    @Override
    public void postStartActivityDismissingKeyguard(PendingIntent intent) {
        postStartActivityDismissingKeyguard(intent, null /* animationController */);
    }

    @Override
    public void postStartActivityDismissingKeyguard(final PendingIntent intent,
            @Nullable ActivityLaunchAnimator.Controller animationController) {
        mMainExecutor.execute(() -> startPendingIntentDismissingKeyguard(intent,
                null /* intentSentUiThreadCallback */, animationController));
    }

    @Override
    public void postStartActivityDismissingKeyguard(final Intent intent, int delay) {
        postStartActivityDismissingKeyguard(intent, delay, null /* animationController */);
    }

    @Override
    public void postStartActivityDismissingKeyguard(Intent intent, int delay,
            @Nullable ActivityLaunchAnimator.Controller animationController) {
        mMainExecutor.executeDelayed(
                () ->
                        startActivityDismissingKeyguard(intent, true /* onlyProvisioned */,
                                true /* dismissShade */,
                                false /* disallowEnterPictureInPictureWhileLaunching */,
                                null /* callback */,
                                0 /* flags */,
                                animationController),
                delay);
    }

    public void showKeyguard() {
        mStatusBarStateController.setKeyguardRequested(true);
        mStatusBarStateController.setLeaveOpenOnKeyguardHide(false);
        updateIsKeyguard();
        mAssistManagerLazy.get().onLockscreenShown();
    }

    public boolean hideKeyguard() {
        mStatusBarStateController.setKeyguardRequested(false);
        return updateIsKeyguard();
    }

    /**
     * stop(tag)
     * @return True if StatusBar state is FULLSCREEN_USER_SWITCHER.
     */
    public boolean isFullScreenUserSwitcherState() {
        return mState == StatusBarState.FULLSCREEN_USER_SWITCHER;
    }

    boolean updateIsKeyguard() {
        return updateIsKeyguard(false /* force */);
    }

    boolean updateIsKeyguard(boolean force) {
        boolean wakeAndUnlocking = mBiometricUnlockController.getMode()
                == BiometricUnlockController.MODE_WAKE_AND_UNLOCK;

        // For dozing, keyguard needs to be shown whenever the device is non-interactive. Otherwise
        // there's no surface we can show to the user. Note that the device goes fully interactive
        // late in the transition, so we also allow the device to start dozing once the screen has
        // turned off fully.
        boolean keyguardForDozing = mDozeServiceHost.getDozingRequested()
                && (!mDeviceInteractive || isGoingToSleep() && (isScreenFullyOff() || mIsKeyguard));
        boolean shouldBeKeyguard = (mStatusBarStateController.isKeyguardRequested()
                || keyguardForDozing) && !wakeAndUnlocking;
        if (keyguardForDozing) {
            updatePanelExpansionForKeyguard();
        }
        if (shouldBeKeyguard) {
            if (mUnlockedScreenOffAnimationController.isScreenOffAnimationPlaying()
                    || (isGoingToSleep()
                    && mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_TURNING_OFF)) {
                // Delay showing the keyguard until screen turned off.
            } else {
                showKeyguardImpl();
            }
        } else {
            return hideKeyguardImpl(force);
        }
        return false;
    }

    public void showKeyguardImpl() {
        Trace.beginSection("StatusBar#showKeyguard");
        mIsKeyguard = true;
        if (mKeyguardStateController.isLaunchTransitionFadingAway()) {
            mNotificationPanelViewController.cancelAnimation();
            onLaunchTransitionFadingEnded();
        }
        mMessageRouter.cancelMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        if (mUserSwitcherController != null && mUserSwitcherController.useFullscreenUserSwitcher()) {
            mStatusBarStateController.setState(StatusBarState.FULLSCREEN_USER_SWITCHER);
        } else if (!mLockscreenShadeTransitionController.isWakingToShadeLocked()) {
            mStatusBarStateController.setState(StatusBarState.KEYGUARD);
        }
        updatePanelExpansionForKeyguard();
        Trace.endSection();
    }

    private void updatePanelExpansionForKeyguard() {
        if (mState == StatusBarState.KEYGUARD && mBiometricUnlockController.getMode()
                != BiometricUnlockController.MODE_WAKE_AND_UNLOCK && !mBouncerShowing) {
            mShadeController.instantExpandNotificationsPanel();
        } else if (mState == StatusBarState.FULLSCREEN_USER_SWITCHER) {
            instantCollapseNotificationPanel();
        }
    }

    private void onLaunchTransitionFadingEnded() {
        mNotificationPanelViewController.setAlpha(1.0f);
        mNotificationPanelViewController.onAffordanceLaunchEnded();
        releaseGestureWakeLock();
        runLaunchTransitionEndRunnable();
        mKeyguardStateController.setLaunchTransitionFadingAway(false);
        mPresenter.updateMediaMetaData(true /* metaDataChanged */, true);
    }

    public boolean isInLaunchTransition() {
        return mNotificationPanelViewController.isLaunchTransitionRunning()
                || mNotificationPanelViewController.isLaunchTransitionFinished();
    }

    /**
     * Fades the content of the keyguard away after the launch transition is done.
     *
     * @param beforeFading the runnable to be run when the circle is fully expanded and the fading
     *                     starts
     * @param endRunnable the runnable to be run when the transition is done
     */
    public void fadeKeyguardAfterLaunchTransition(final Runnable beforeFading,
            Runnable endRunnable) {
        mMessageRouter.cancelMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        mLaunchTransitionEndRunnable = endRunnable;
        Runnable hideRunnable = () -> {
            mKeyguardStateController.setLaunchTransitionFadingAway(true);
            if (beforeFading != null) {
                beforeFading.run();
            }
            updateScrimController();
            mPresenter.updateMediaMetaData(false, true);
            mNotificationPanelViewController.setAlpha(1);
            mNotificationPanelViewController.fadeOut(
                    FADE_KEYGUARD_START_DELAY, FADE_KEYGUARD_DURATION,
                    this::onLaunchTransitionFadingEnded);
            mCommandQueue.appTransitionStarting(mDisplayId, SystemClock.uptimeMillis(),
                    LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        };
        if (mNotificationPanelViewController.isLaunchTransitionRunning()) {
            mNotificationPanelViewController.setLaunchTransitionEndRunnable(hideRunnable);
        } else {
            hideRunnable.run();
        }
    }

    /**
     * Fades the content of the Keyguard while we are dozing and makes it invisible when finished
     * fading.
     */
    public void fadeKeyguardWhilePulsing() {
        mNotificationPanelViewController.fadeOut(0, FADE_KEYGUARD_DURATION_PULSING,
                ()-> {
                hideKeyguard();
                mStatusBarKeyguardViewManager.onKeyguardFadedAway();
            }).start();
    }

    /**
     * Plays the animation when an activity that was occluding Keyguard goes away.
     */
    public void animateKeyguardUnoccluding() {
        mNotificationPanelViewController.setExpandedFraction(0f);
        mCommandQueueCallbacks.animateExpandNotificationsPanel();
        mScrimController.setUnocclusionAnimationRunning(true);
    }

    /**
     * Starts the timeout when we try to start the affordances on Keyguard. We usually rely that
     * Keyguard goes away via fadeKeyguardAfterLaunchTransition, however, that might not happen
     * because the launched app crashed or something else went wrong.
     */
    public void startLaunchTransitionTimeout() {
        mMessageRouter.sendMessageDelayed(
                MSG_LAUNCH_TRANSITION_TIMEOUT, LAUNCH_TRANSITION_TIMEOUT_MS);
    }

    private void onLaunchTransitionTimeout() {
        Log.w(TAG, "Launch transition: Timeout!");
        mNotificationPanelViewController.onAffordanceLaunchEnded();
        releaseGestureWakeLock();
        mNotificationPanelViewController.resetViews(false /* animate */);
    }

    private void runLaunchTransitionEndRunnable() {
        if (mLaunchTransitionEndRunnable != null) {
            Runnable r = mLaunchTransitionEndRunnable;

            // mLaunchTransitionEndRunnable might call showKeyguard, which would execute it again,
            // which would lead to infinite recursion. Protect against it.
            mLaunchTransitionEndRunnable = null;
            r.run();
        }
    }

    /**
     * @return true if we would like to stay in the shade, false if it should go away entirely
     */
    public boolean hideKeyguardImpl(boolean force) {
        mIsKeyguard = false;
        Trace.beginSection("StatusBar#hideKeyguard");
        boolean staying = mStatusBarStateController.leaveOpenOnKeyguardHide();
        int previousState = mStatusBarStateController.getState();
        if (!(mStatusBarStateController.setState(StatusBarState.SHADE, force))) {
            //TODO: StatusBarStateController should probably know about hiding the keyguard and
            // notify listeners.

            // If the state didn't change, we may still need to update public mode
            mLockscreenUserManager.updatePublicMode();
        }
        if (mStatusBarStateController.leaveOpenOnKeyguardHide()) {
            if (!mStatusBarStateController.isKeyguardRequested()) {
                mStatusBarStateController.setLeaveOpenOnKeyguardHide(false);
            }
            long delay = mKeyguardStateController.calculateGoingToFullShadeDelay();
            mLockscreenShadeTransitionController.onHideKeyguard(delay, previousState);

            // Disable layout transitions in navbar for this transition because the load is just
            // too heavy for the CPU and GPU on any device.
            mNavigationBarController.disableAnimationsDuringHide(mDisplayId, delay);
        } else if (!mNotificationPanelViewController.isCollapsing()) {
            instantCollapseNotificationPanel();
        }

        // Keyguard state has changed, but QS is not listening anymore. Make sure to update the tile
        // visibilities so next time we open the panel we know the correct height already.
        if (mQSPanelController != null) {
            mQSPanelController.refreshAllTiles();
        }
        mMessageRouter.cancelMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        releaseGestureWakeLock();
        mNotificationPanelViewController.onAffordanceLaunchEnded();
        mNotificationPanelViewController.cancelAnimation();
        mNotificationPanelViewController.setAlpha(1f);
        mNotificationPanelViewController.resetViewGroupFade();
        updateDozingState();
        updateScrimController();
        Trace.endSection();
        return staying;
    }

    private void releaseGestureWakeLock() {
        if (mGestureWakeLock.isHeld()) {
            mGestureWakeLock.release();
        }
    }

    /**
     * Notifies the status bar that Keyguard is going away very soon.
     */
    public void keyguardGoingAway() {
        // Treat Keyguard exit animation as an app transition to achieve nice transition for status
        // bar.
        mKeyguardStateController.notifyKeyguardGoingAway(true);
        mCommandQueue.appTransitionPending(mDisplayId, true /* forced */);
    }

    /**
     * Notifies the status bar the Keyguard is fading away with the specified timings.
     *  @param startTime the start time of the animations in uptime millis
     * @param delay the precalculated animation delay in milliseconds
     * @param fadeoutDuration the duration of the exit animation, in milliseconds
     * @param isBypassFading is this a fading away animation while bypassing
     */
    public void setKeyguardFadingAway(long startTime, long delay, long fadeoutDuration,
            boolean isBypassFading) {
        mCommandQueue.appTransitionStarting(mDisplayId, startTime + fadeoutDuration
                        - LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION,
                LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        mCommandQueue.recomputeDisableFlags(mDisplayId, fadeoutDuration > 0 /* animate */);
        mCommandQueue.appTransitionStarting(mDisplayId,
                    startTime - LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION,
                    LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        mKeyguardStateController.notifyKeyguardFadingAway(delay, fadeoutDuration, isBypassFading);
    }

    /**
     * Notifies that the Keyguard fading away animation is done.
     */
    public void finishKeyguardFadingAway() {
        mKeyguardStateController.notifyKeyguardDoneFading();
        mScrimController.setExpansionAffectsAlpha(true);
    }

    /**
     * Switches theme from light to dark and vice-versa.
     */
    protected void updateTheme() {
        // Lock wallpaper defines the color of the majority of the views, hence we'll use it
        // to set our default theme.
        final boolean lockDarkText = mColorExtractor.getNeutralColors().supportsDarkText();
        final int themeResId = lockDarkText ? R.style.Theme_SystemUI_LightWallpaper
                : R.style.Theme_SystemUI;
        if (mContext.getThemeResId() == themeResId) {
            return;
        }
        mContext.setTheme(themeResId);
        mConfigurationController.notifyThemeChanged();
    }

    private void updateDozingState() {
        Trace.traceCounter(Trace.TRACE_TAG_APP, "dozing", mDozing ? 1 : 0);
        Trace.beginSection("StatusBar#updateDozingState");

        boolean visibleNotOccluded = mStatusBarKeyguardViewManager.isShowing()
                && !mStatusBarKeyguardViewManager.isOccluded();
        // If we're dozing and we'll be animating the screen off, the keyguard isn't currently
        // visible but will be shortly for the animation, so we should proceed as if it's visible.
        boolean visibleNotOccludedOrWillBe =
                visibleNotOccluded || (mDozing && mDozeParameters.shouldControlUnlockedScreenOff());

        boolean wakeAndUnlock = mBiometricUnlockController.getMode()
                == BiometricUnlockController.MODE_WAKE_AND_UNLOCK;
        boolean animate = (!mDozing && mDozeServiceHost.shouldAnimateWakeup() && !wakeAndUnlock)
                || (mDozing && mDozeParameters.shouldControlScreenOff()
                && visibleNotOccludedOrWillBe);

        mNotificationPanelViewController.setDozing(mDozing, animate, mWakeUpTouchLocation);
        mVisualizerView.setDozing(mDozing);
        updateQsExpansionEnabled();
        Trace.endSection();
    }

    private void scheduleAutohide() {
        cancelAutohide();
        mHandler.postDelayed(mAutohide, AUTOHIDE_TIMEOUT_MS);
    }

    public void touchAutoDim() {
        if (mNavigationBar != null) {
            mNavigationBar.getBarTransitions().setAutoDim(false);
        }
        mHandler.removeCallbacks(mAutoDim);
        if (mState != StatusBarState.KEYGUARD && mState != StatusBarState.SHADE_LOCKED) {
            mHandler.postDelayed(mAutoDim, AUTOHIDE_TIMEOUT_MS);
        }
    }

    void checkUserAutohide(View v, MotionEvent event) {
        if ((mSystemUiVisibility & STATUS_OR_NAV_TRANSIENT) != 0  // a transient bar is revealed
                && event.getAction() == MotionEvent.ACTION_OUTSIDE // touch outside the source bar
                && event.getX() == 0 && event.getY() == 0  // a touch outside both bars
                && !mRemoteInputController.isRemoteInputActive()) { // not due to typing in IME
            userAutohide();
        }
    }

    private void checkRemoteInputOutside(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_OUTSIDE // touch outside the source bar
                && event.getX() == 0 && event.getY() == 0  // a touch outside both bars
                && mRemoteInputController.isRemoteInputActive()) {
            mRemoteInputController.closeRemoteInputs();
        }
    }

    private void userAutohide() {
        cancelAutohide();
        mHandler.postDelayed(mAutohide, 350); // longer than app gesture -> flag clear
    }

    private boolean areLightsOn() {
        return 0 == (mSystemUiVisibility & View.SYSTEM_UI_FLAG_LOW_PROFILE);
    }

    public void setLightsOn(boolean on) {
        Log.v(TAG, "setLightsOn(" + on + ")");
        if (on) {
            setSystemUiVisibility(0, 0, 0, View.SYSTEM_UI_FLAG_LOW_PROFILE,
                    mLastFullscreenStackBounds, mLastDockedStackBounds);
        } else {
            setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE, 0, 0,
                    View.SYSTEM_UI_FLAG_LOW_PROFILE, mLastFullscreenStackBounds,
                    mLastDockedStackBounds);
        }
    }

    private void notifyUiVisibilityChanged(int vis) {
        try {
            if (mLastDispatchedSystemUiVisibility != vis) {
                mWindowManagerService.statusBarVisibilityChanged(vis);
                mLastDispatchedSystemUiVisibility = vis;
            }
        } catch (RemoteException ex) {
        }
    }

    @Override
    public void topAppWindowChanged(boolean showMenu) {
        if (SPEW) {
            Log.d(TAG, (showMenu?"showing":"hiding") + " the MENU button");
        }

        // See above re: lights-out policy for legacy apps.
        if (showMenu) setLightsOn(true);
    }

    public static String viewInfo(View v) {
        return "[(" + v.getLeft() + "," + v.getTop() + ")(" + v.getRight() + "," + v.getBottom()
                + ") " + v.getWidth() + "x" + v.getHeight() + "]";
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mQueueLock) {
            pw.println("Current Status Bar state:");
            pw.println("  mExpandedVisible=" + mExpandedVisible
                    + ", mTrackingPosition=" + mTrackingPosition);
            pw.println("  mTracking=" + mTracking);
            pw.println("  mDisplayMetrics=" + mDisplayMetrics);
            pw.println("  mStackScroller: " + viewInfo(mStackScroller));
            pw.println("  mStackScroller: " + viewInfo(mStackScroller)
                    + " scroll " + mStackScroller.getScrollX()
                    + "," + mStackScroller.getScrollY());
        }
        pw.print("  mPendingNotifications=");
        if (mPendingNotifications.size() == 0) {
            pw.println("null");
        } else {
            for (Entry entry : mPendingNotifications.values()) {
                pw.println(entry.notification);
            }
        }

        pw.print("  mInteractingWindows="); pw.println(mInteractingWindows);
        pw.print("  mStatusBarWindowState=");
        pw.println(windowStateToString(mStatusBarWindowState));
        pw.print("  mStatusBarMode=");
        pw.println(BarTransitions.modeToString(mStatusBarMode));
        pw.print("  mDozing="); pw.println(mDozing);
        pw.print("  mZenMode=");
        pw.println(Settings.Global.zenModeToString(mZenMode));
        pw.print("  mUseHeadsUp=");
        pw.println(mUseHeadsUp);
        pw.print("  mKeyToRemoveOnGutsClosed=");
        pw.println(mKeyToRemoveOnGutsClosed);
        if (mStatusBarView != null) {
            dumpBarTransitions(pw, "mStatusBarView", mStatusBarView.getBarTransitions());
        }

        pw.print("  mMediaSessionManager=");
        pw.println(mMediaSessionManager);
        pw.print("  mMediaNotificationKey=");
        pw.println(mMediaNotificationKey);
        pw.print("  mMediaController=");
        pw.print(mMediaController);
        if (mMediaController != null) {
            pw.print(" state=" + mMediaController.getPlaybackState());
        }
        pw.println();
        pw.print("  mMediaMetadata=");
        pw.print(mMediaMetadata);
        if (mMediaMetadata != null) {
            pw.print(" title=" + mMediaMetadata.getText(MediaMetadata.METADATA_KEY_TITLE));
        }
        pw.println();

        pw.println("  Panels: ");
        if (mNotificationPanel != null) {
            pw.println("    mNotificationPanel=" +
                mNotificationPanel + " params=" + mNotificationPanel.getLayoutParams().debug(""));
            pw.print  ("      ");
            mNotificationPanel.dump(fd, pw, args);
        }
        pw.println("  mStackScroller: ");
        if (mStackScroller != null) {
            pw.print  ("      ");
            mStackScroller.dump(fd, pw, args);
        }
        pw.println("  Theme:");
        if (mOverlayManager == null) {
            pw.println("    overlay manager not initialized!");
        } else {
            pw.println("    dark overlay on: " + isUsingDarkTheme());
        }
        final boolean lightWpTheme = mContext.getThemeResId() == R.style.Theme_SystemUI_Light;
        pw.println("    light wallpaper theme: " + lightWpTheme);

        DozeLog.dump(pw);

        if (mFingerprintUnlockController != null) {
            mFingerprintUnlockController.dump(pw);
        }

        if (mScrimController != null) {
            mScrimController.dump(pw);
        }

        if (DUMPTRUCK) {
            synchronized (mNotificationData) {
                mNotificationData.dump(pw, "  ");
            }

            if (false) {
                pw.println("see the logcat for a dump of the views we have created.");
                // must happen on ui thread
                mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mStatusBarView.getLocationOnScreen(mAbsPos);
                            Log.d(TAG, "mStatusBarView: ----- (" + mAbsPos[0] + "," + mAbsPos[1]
                                    + ") " + mStatusBarView.getWidth() + "x"
                                    + getStatusBarHeight());
                            mStatusBarView.debug();
                        }
                    });
            }
        }

        if (DEBUG_GESTURES) {
            pw.print("  status bar gestures: ");
            mGestureRec.dump(fd, pw, args);
        }

        if (mHeadsUpManager != null) {
            mHeadsUpManager.dump(fd, pw, args);
        } else {
            pw.println("  mHeadsUpManager: null");
        }
        if (mGroupManager != null) {
            mGroupManager.dump(fd, pw, args);
        } else {
            pw.println("  mGroupManager: null");
        }

        if (mLightBarController != null) {
            mLightBarController.dump(fd, pw, args);
        }

        if (KeyguardUpdateMonitor.getInstance(mContext) != null) {
            KeyguardUpdateMonitor.getInstance(mContext).dump(fd, pw, args);
        }

        FalsingManager.getInstance(mContext).dump(pw);
        FalsingLog.dump(pw);

        pw.println("SharedPreferences:");
        for (Map.Entry<String, ?> entry : Prefs.getAll(mContext).entrySet()) {
            pw.print("  "); pw.print(entry.getKey()); pw.print("="); pw.println(entry.getValue());
        }

        if (mFlashlightController != null) {
            mFlashlightController.dump(fd, pw, args);
        }
    }

    static void dumpBarTransitions(PrintWriter pw, String var, BarTransitions transitions) {
        pw.print("  "); pw.print(var); pw.print(".BarTransitions.mMode=");
        pw.println(BarTransitions.modeToString(transitions.getMode()));
    }

    public void createAndAddWindows() {
        addStatusBarWindow();
    }

    private void addStatusBarWindow() {
        makeStatusBarView();
        mStatusBarWindowManager = Dependency.get(StatusBarWindowManager.class);
        mRemoteInputController = new RemoteInputController(mHeadsUpManager);
        mStatusBarWindowManager.add(mStatusBarWindow, getStatusBarHeight());
    }

    // called by makeStatusbar and also by PhoneStatusBarView
    void updateDisplaySize() {
        mDisplay.getMetrics(mDisplayMetrics);
        mDisplay.getSize(mCurrentDisplaySize);
        if (DEBUG_GESTURES) {
            mGestureRec.tag("display",
                    String.format("%dx%d", mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels));
        }
    }

    float getDisplayDensity() {
        return mDisplayMetrics.density;
    }

    public void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            boolean dismissShade) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade,
                false /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */);
    }

    public void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            final boolean dismissShade, final boolean disallowEnterPictureInPictureWhileLaunching,
            final Callback callback) {
        if (onlyProvisioned && !isDeviceProvisioned()) return;

        final boolean afterKeyguardGone = PreviewInflater.wouldLaunchResolverActivity(
                mContext, intent, mCurrentUserId);
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                mAssistManager.hideAssist();
                intent.setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                int result = ActivityManager.START_CANCELED;
                ActivityOptions options = new ActivityOptions(getActivityOptions());
                options.setDisallowEnterPictureInPictureWhileLaunching(
                        disallowEnterPictureInPictureWhileLaunching);
                if (intent == KeyguardBottomAreaView.INSECURE_CAMERA_INTENT) {
                    // Normally an activity will set it's requested rotation
                    // animation on its window. However when launching an activity
                    // causes the orientation to change this is too late. In these cases
                    // the default animation is used. This doesn't look good for
                    // the camera (as it rotates the camera contents out of sync
                    // with physical reality). So, we ask the WindowManager to
                    // force the crossfade animation if an orientation change
                    // happens to occur during the launch.
                    options.setRotationAnimationHint(
                            WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS);
                }
                try {
                    result = ActivityManager.getService().startActivityAsUser(
                            null, mContext.getBasePackageName(),
                            intent,
                            intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                            null, null, 0, Intent.FLAG_ACTIVITY_NEW_TASK, null,
                            options.toBundle(), UserHandle.CURRENT.getIdentifier());
                } catch (RemoteException e) {
                    Log.w(TAG, "Unable to start activity", e);
                }
                if (callback != null) {
                    callback.onActivityStarted(result);
                }
            }
        };
        Runnable cancelRunnable = new Runnable() {
            @Override
            public void run() {
                if (callback != null) {
                    callback.onActivityStarted(ActivityManager.START_CANCELED);
                }
            }
        };
        executeRunnableDismissingKeyguard(runnable, cancelRunnable, dismissShade,
                afterKeyguardGone, true /* deferred */);
    }

    public void readyForKeyguardDone() {
        mStatusBarKeyguardViewManager.readyForKeyguardDone();
    }

    public void executeRunnableDismissingKeyguard(final Runnable runnable,
            final Runnable cancelAction,
            final boolean dismissShade,
            final boolean afterKeyguardGone,
            final boolean deferred) {
        dismissKeyguardThenExecute(() -> {
            if (runnable != null) {
                if (mStatusBarKeyguardViewManager.isShowing()
                        && mStatusBarKeyguardViewManager.isOccluded()) {
                    mStatusBarKeyguardViewManager.addAfterKeyguardGoneRunnable(runnable);
                } else {
                    AsyncTask.execute(runnable);
                }
            }
            if (dismissShade) {
                if (mExpandedVisible) {
                    animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL, true /* force */,
                            true /* delayed*/);
                } else {

                    // Do it after DismissAction has been processed to conserve the needed ordering.
                    mHandler.post(this::runPostCollapseRunnables);
                }
            } else if (isInLaunchTransition() && mNotificationPanel.isLaunchTransitionFinished()) {

                // We are not dismissing the shade, but the launch transition is already finished,
                // so nobody will call readyForKeyguardDone anymore. Post it such that
                // keyguardDonePending gets called first.
                mHandler.post(mStatusBarKeyguardViewManager::readyForKeyguardDone);
            }
            return deferred;
        }, cancelAction, afterKeyguardGone);
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                KeyboardShortcuts.dismiss();
                if (mRemoteInputController != null) {
                    mRemoteInputController.closeRemoteInputs();
                }
                if (isCurrentProfile(getSendingUserId())) {
                    int flags = CommandQueue.FLAG_EXCLUDE_NONE;
                    String reason = intent.getStringExtra("reason");
                    if (reason != null && reason.equals(SYSTEM_DIALOG_REASON_RECENT_APPS)) {
                        flags |= CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL;
                    }
                    animateCollapsePanels(flags);
                }
            }
            else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                finishBarAnimations();
                resetUserExpandedStates();
            }
            else if (DevicePolicyManager.ACTION_SHOW_DEVICE_MONITORING_DIALOG.equals(action)) {
                mQSPanel.showDeviceMonitoringDialog();
            }
        }
    };

    private BroadcastReceiver mDemoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            if (ACTION_DEMO.equals(action)) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    String command = bundle.getString("command", "").trim().toLowerCase();
                    if (command.length() > 0) {
                        try {
                            dispatchDemoCommand(command, bundle);
                        } catch (Throwable t) {
                            Log.w(TAG, "Error running demo command, intent=" + intent, t);
                        }
                    }
                }
            } else if (ACTION_FAKE_ARTWORK.equals(action)) {
                if (DEBUG_MEDIA_FAKE_ARTWORK) {
                    updateMediaMetaData(true, true);
                }
            }
        }
    };

    public void resetUserExpandedStates() {
        ArrayList<Entry> activeNotifications = mNotificationData.getActiveNotifications();
        final int notificationCount = activeNotifications.size();
        for (int i = 0; i < notificationCount; i++) {
            NotificationData.Entry entry = activeNotifications.get(i);
            if (entry.row != null) {
                entry.row.resetUserExpansion();
            }
        }
    }

    protected void dismissKeyguardThenExecute(OnDismissAction action, boolean afterKeyguardGone) {
        dismissKeyguardThenExecute(action, null /* cancelRunnable */, afterKeyguardGone);
    }

    private void dismissKeyguardThenExecute(OnDismissAction action, Runnable cancelAction,
            boolean afterKeyguardGone) {
        if (mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_ASLEEP
                && mUnlockMethodCache.canSkipBouncer()
                && !mLeaveOpenOnKeyguardHide
                && isPulsing()) {
            // Reuse the fingerprint wake-and-unlock transition if we dismiss keyguard from a pulse.
            // TODO: Factor this transition out of FingerprintUnlockController.
            mFingerprintUnlockController.startWakeAndUnlock(
                    FingerprintUnlockController.MODE_WAKE_AND_UNLOCK_PULSING);
        }
        if (mStatusBarKeyguardViewManager.isShowing()) {
            mStatusBarKeyguardViewManager.dismissWithAction(action, cancelAction,
                    afterKeyguardGone);
        } else {
            action.onDismiss();
        }
    }

    // SystemUIService notifies SystemBars of configuration changes, which then calls down here
    @Override
    public void onConfigChanged(Configuration newConfig) {
        updateResources();
        updateDisplaySize(); // populates mDisplayMetrics

        if (DEBUG) {
            Log.v(TAG, "configuration changed: " + mContext.getResources().getConfiguration());
        }

        updateRowStates();
        mScreenPinningRequest.onConfigurationChanged();
    }

    public void userSwitched(int newUserId) {
        // Begin old BaseStatusBar.userSwitched
        setHeadsUpUser(newUserId);
        // End old BaseStatusBar.userSwitched
        if (MULTIUSER_DEBUG) mNotificationPanelDebugText.setText("USER " + newUserId);
        animateCollapsePanels();
        updatePublicMode();
        mNotificationData.filterAndSort();
        if (mReinflateNotificationsOnUserSwitched) {
            updateNotificationsOnDensityOrFontScaleChanged();
            mReinflateNotificationsOnUserSwitched = false;
        }
        updateNotificationShade();
        clearCurrentMediaNotification();
        setLockscreenUser(newUserId);
    }

    protected void setLockscreenUser(int newUserId) {
        mLockscreenWallpaper.setCurrentUser(newUserId);
        mScrimController.setCurrentUser(newUserId);
        updateMediaMetaData(true, false);
    }

    /**
     * Reload some of our resources when the configuration changes.
     *
     * We don't reload everything when the configuration changes -- we probably
     * should, but getting that smooth is tough.  Someday we'll fix that.  In the
     * meantime, just update the things that we know change.
     */
    void updateResources() {
        // Update the quick setting tiles
        if (mQSPanel != null) {
            mQSPanel.updateResources();
        }

        loadDimens();

        if (mNotificationPanel != null) {
            mNotificationPanel.updateResources();
        }
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.updateResources();
        }
    }

    protected void loadDimens() {
        final Resources res = mContext.getResources();

        int oldBarHeight = mNaturalBarHeight;
        mNaturalBarHeight = res.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        if (mStatusBarWindowManager != null && mNaturalBarHeight != oldBarHeight) {
            mStatusBarWindowManager.setBarHeight(mNaturalBarHeight);
        }
        mMaxAllowedKeyguardNotifications = res.getInteger(
                R.integer.keyguard_max_notification_count);

        mStatusBarHeaderHeight = res.getDimensionPixelSize(R.dimen.status_bar_header_height);

        if (DEBUG) Log.v(TAG, "defineSlots");
    }

    // Visibility reporting

    protected void handleVisibleToUserChanged(boolean visibleToUser) {
        if (visibleToUser) {
            handleVisibleToUserChangedImpl(visibleToUser);
            startNotificationLogging();
        } else {
            stopNotificationLogging();
            handleVisibleToUserChangedImpl(visibleToUser);
        }
    }

    void handlePeekToExpandTransistion() {
        try {
            // consider the transition from peek to expanded to be a panel open,
            // but not one that clears notification effects.
            int notificationLoad = mNotificationData.getActiveNotifications().size();
            mBarService.onPanelRevealed(false, notificationLoad);
        } catch (RemoteException ex) {
            // Won't fail unless the world has ended.
        }
    }

    /**
     * The LEDs are turned off when the notification panel is shown, even just a little bit.
     * See also StatusBar.setPanelExpanded for another place where we attempt to do this.
     */
    // Old BaseStatusBar.handleVisibileToUserChanged
    private void handleVisibleToUserChangedImpl(boolean visibleToUser) {
        try {
            if (visibleToUser) {
                boolean pinnedHeadsUp = mHeadsUpManager.hasPinnedHeadsUp();
                boolean clearNotificationEffects =
                        !isPanelFullyCollapsed() &&
                        (mState == StatusBarState.SHADE || mState == StatusBarState.SHADE_LOCKED);
                int notificationLoad = mNotificationData.getActiveNotifications().size();
                if (pinnedHeadsUp && isPanelFullyCollapsed())  {
                    notificationLoad = 1;
                }
                mBarService.onPanelRevealed(clearNotificationEffects, notificationLoad);
            } else {
                mBarService.onPanelHidden();
            }
        } catch (RemoteException ex) {
            // Won't fail unless the world has ended.
        }
    }

    private void stopNotificationLogging() {
        // Report all notifications as invisible and turn down the
        // reporter.
        if (!mCurrentlyVisibleNotifications.isEmpty()) {
            logNotificationVisibilityChanges(Collections.<NotificationVisibility>emptyList(),
                    mCurrentlyVisibleNotifications);
            recycleAllVisibilityObjects(mCurrentlyVisibleNotifications);
        }
        mHandler.removeCallbacks(mVisibilityReporter);
        mStackScroller.setChildLocationsChangedListener(null);
    }

    private void startNotificationLogging() {
        mStackScroller.setChildLocationsChangedListener(mNotificationLocationsChangedListener);
        // Some transitions like mVisibleToUser=false -> mVisibleToUser=true don't
        // cause the scroller to emit child location events. Hence generate
        // one ourselves to guarantee that we're reporting visible
        // notifications.
        // (Note that in cases where the scroller does emit events, this
        // additional event doesn't break anything.)
        mNotificationLocationsChangedListener.onChildLocationsChanged(mStackScroller);
    }

    private void logNotificationVisibilityChanges(
            Collection<NotificationVisibility> newlyVisible,
            Collection<NotificationVisibility> noLongerVisible) {
        if (newlyVisible.isEmpty() && noLongerVisible.isEmpty()) {
            return;
        }
        NotificationVisibility[] newlyVisibleAr =
                newlyVisible.toArray(new NotificationVisibility[newlyVisible.size()]);
        NotificationVisibility[] noLongerVisibleAr =
                noLongerVisible.toArray(new NotificationVisibility[noLongerVisible.size()]);
        try {
            mBarService.onNotificationVisibilityChanged(newlyVisibleAr, noLongerVisibleAr);
        } catch (RemoteException e) {
            // Ignore.
        }

        final int N = newlyVisible.size();
        if (N > 0) {
            String[] newlyVisibleKeyAr = new String[N];
            for (int i = 0; i < N; i++) {
                newlyVisibleKeyAr[i] = newlyVisibleAr[i].key;
            }

            setNotificationsShown(newlyVisibleKeyAr);
        }
    }

    // State logging

    private void logStateToEventlog() {
        boolean isShowing = mStatusBarKeyguardViewManager.isShowing();
        boolean isOccluded = mStatusBarKeyguardViewManager.isOccluded();
        boolean isBouncerShowing = mStatusBarKeyguardViewManager.isBouncerShowing();
        boolean isSecure = mUnlockMethodCache.isMethodSecure();
        boolean canSkipBouncer = mUnlockMethodCache.canSkipBouncer();
        int stateFingerprint = getLoggingFingerprint(mState,
                isShowing,
                isOccluded,
                isBouncerShowing,
                isSecure,
                canSkipBouncer);
        if (stateFingerprint != mLastLoggedStateFingerprint) {
            if (mStatusBarStateLog == null) {
                mStatusBarStateLog = new LogMaker(MetricsEvent.VIEW_UNKNOWN);
            }
            mMetricsLogger.write(mStatusBarStateLog
                    .setCategory(isBouncerShowing ? MetricsEvent.BOUNCER : MetricsEvent.LOCKSCREEN)
                    .setType(isShowing ? MetricsEvent.TYPE_OPEN : MetricsEvent.TYPE_CLOSE)
                    .setSubtype(isSecure ? 1 : 0));
            EventLogTags.writeSysuiStatusBarState(mState,
                    isShowing ? 1 : 0,
                    isOccluded ? 1 : 0,
                    isBouncerShowing ? 1 : 0,
                    isSecure ? 1 : 0,
                    canSkipBouncer ? 1 : 0);
            mLastLoggedStateFingerprint = stateFingerprint;
        }
    }

    /**
     * Returns a fingerprint of fields logged to eventlog
     */
    private static int getLoggingFingerprint(int statusBarState, boolean keyguardShowing,
            boolean keyguardOccluded, boolean bouncerShowing, boolean secure,
            boolean currentlyInsecure) {
        // Reserve 8 bits for statusBarState. We'll never go higher than
        // that, right? Riiiight.
        return (statusBarState & 0xFF)
                | ((keyguardShowing   ? 1 : 0) <<  8)
                | ((keyguardOccluded  ? 1 : 0) <<  9)
                | ((bouncerShowing    ? 1 : 0) << 10)
                | ((secure            ? 1 : 0) << 11)
                | ((currentlyInsecure ? 1 : 0) << 12);
    }

    //
    // tracing
    //

    void postStartTracing() {
        mHandler.postDelayed(mStartTracing, 3000);
    }

    void vibrate() {
        android.os.Vibrator vib = (android.os.Vibrator)mContext.getSystemService(
                Context.VIBRATOR_SERVICE);
        vib.vibrate(250, VIBRATION_ATTRIBUTES);
    }

    Runnable mStartTracing = new Runnable() {
        @Override
        public void run() {
            vibrate();
            SystemClock.sleep(250);
            Log.d(TAG, "startTracing");
            android.os.Debug.startMethodTracing("/data/statusbar-traces/trace");
            mHandler.postDelayed(mStopTracing, 10000);
        }
    };

    Runnable mStopTracing = new Runnable() {
        @Override
        public void run() {
            android.os.Debug.stopMethodTracing();
            Log.d(TAG, "stopTracing");
            vibrate();
        }
    };

    @Override
    public void postQSRunnableDismissingKeyguard(final Runnable runnable) {
        mHandler.post(() -> {
            mLeaveOpenOnKeyguardHide = true;
            executeRunnableDismissingKeyguard(() -> mHandler.post(runnable), null, false, false,
                    false);
        });
    }

    @Override
    public void postStartActivityDismissingKeyguard(final PendingIntent intent) {
        mHandler.post(() -> startPendingIntentDismissingKeyguard(intent));
    }

    @Override
    public void postStartActivityDismissingKeyguard(final Intent intent, int delay) {
        mHandler.postDelayed(() ->
                handleStartActivityDismissingKeyguard(intent, true /*onlyProvisioned*/), delay);
    }

    private void handleStartActivityDismissingKeyguard(Intent intent, boolean onlyProvisioned) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, true /* dismissShade */);
    }

    private static class FastColorDrawable extends Drawable {
        private final int mColor;

        public FastColorDrawable(int color) {
            mColor = 0xff000000 | color;
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawColor(mColor, PorterDuff.Mode.SRC);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }

        @Override
        public void setBounds(int left, int top, int right, int bottom) {
        }

        @Override
        public void setBounds(Rect bounds) {
        }
    }

    public void destroy() {
        // Begin old BaseStatusBar.destroy().
        mContext.unregisterReceiver(mBaseBroadcastReceiver);
        try {
            mNotificationListener.unregisterAsSystemService();
        } catch (RemoteException e) {
            // Ignore.
        }
        mDeviceProvisionedController.removeCallback(mDeviceProvisionedListener);
        // End old BaseStatusBar.destroy().
        if (mStatusBarWindow != null) {
            mWindowManager.removeViewImmediate(mStatusBarWindow);
            mStatusBarWindow = null;
        }
        if (mNavigationBarView != null) {
            mWindowManager.removeViewImmediate(mNavigationBarView);
            mNavigationBarView = null;
        }
        mContext.unregisterReceiver(mBroadcastReceiver);
        mContext.unregisterReceiver(mDemoReceiver);
        mAssistManager.destroy();

        if (mQSPanel != null && mQSPanel.getHost() != null) {
            mQSPanel.getHost().destroy();
        }
        Dependency.get(ActivityStarterDelegate.class).setActivityStarterImpl(null);
        mDeviceProvisionedController.removeCallback(mUserSetupObserver);
        Dependency.get(ConfigurationController.class).removeCallback(this);
    }

    private boolean mDemoModeAllowed;
    private boolean mDemoMode;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoModeAllowed) {
            mDemoModeAllowed = Settings.Global.getInt(mContext.getContentResolver(),
                    DEMO_MODE_ALLOWED, 0) != 0;
        }
        if (!mDemoModeAllowed) return;
        if (command.equals(COMMAND_ENTER)) {
            mDemoMode = true;
        } else if (command.equals(COMMAND_EXIT)) {
            mDemoMode = false;
            checkBarModes();
        } else if (!mDemoMode) {
            // automatically enter demo mode on first demo command
            dispatchDemoCommand(COMMAND_ENTER, new Bundle());
        }
        boolean modeChange = command.equals(COMMAND_ENTER) || command.equals(COMMAND_EXIT);
        if ((modeChange || command.equals(COMMAND_VOLUME)) && mVolumeComponent != null) {
            mVolumeComponent.dispatchDemoCommand(command, args);
        }
        if (modeChange || command.equals(COMMAND_CLOCK)) {
            dispatchDemoCommandToView(command, args, R.id.clock);
        }
        if (modeChange || command.equals(COMMAND_BATTERY)) {
            mBatteryController.dispatchDemoCommand(command, args);
        }
        if (modeChange || command.equals(COMMAND_STATUS)) {
            ((StatusBarIconControllerImpl) mIconController).dispatchDemoCommand(command, args);
        }
        if (mNetworkController != null && (modeChange || command.equals(COMMAND_NETWORK))) {
            mNetworkController.dispatchDemoCommand(command, args);
        }
        if (modeChange || command.equals(COMMAND_NOTIFICATIONS)) {
            View notifications = mStatusBarView == null ? null
                    : mStatusBarView.findViewById(R.id.notification_icon_area);
            if (notifications != null) {
                String visible = args.getString("visible");
                int vis = mDemoMode && "false".equals(visible) ? View.INVISIBLE : View.VISIBLE;
                notifications.setVisibility(vis);
            }
        }
        if (command.equals(COMMAND_BARS)) {
            String mode = args.getString("mode");
            int barMode = "opaque".equals(mode) ? MODE_OPAQUE :
                    "translucent".equals(mode) ? MODE_TRANSLUCENT :
                    "semi-transparent".equals(mode) ? MODE_SEMI_TRANSPARENT :
                    "transparent".equals(mode) ? MODE_TRANSPARENT :
                    "warning".equals(mode) ? MODE_WARNING :
                    -1;
            if (barMode != -1) {
                boolean animate = true;
                if (mStatusBarView != null) {
                    mStatusBarView.getBarTransitions().transitionTo(barMode, animate);
                }
                if (mNavigationBar != null) {
                    mNavigationBar.getBarTransitions().transitionTo(barMode, animate);
                }
            }
        }
    }

    private void dispatchDemoCommandToView(String command, Bundle args, int id) {
        if (mStatusBarView == null) return;
        View v = mStatusBarView.findViewById(id);
        if (v instanceof DemoMode) {
            ((DemoMode)v).dispatchDemoCommand(command, args);
        }
    }

    /**
     * @return The {@link StatusBarState} the status bar is in.
     */
    public int getBarState() {
        return mState;
    }

    public boolean isPanelFullyCollapsed() {
        return mNotificationPanel.isFullyCollapsed();
    }

    public void showKeyguard() {
        mKeyguardRequested = true;
        mLeaveOpenOnKeyguardHide = false;
        mPendingRemoteInputView = null;
        updateIsKeyguard();
        mAssistManager.onLockscreenShown();
    }

    public boolean hideKeyguard() {
        mKeyguardRequested = false;
        return updateIsKeyguard();
    }

    private boolean updateIsKeyguard() {
        boolean wakeAndUnlocking = mFingerprintUnlockController.getMode()
                == FingerprintUnlockController.MODE_WAKE_AND_UNLOCK;

        // For dozing, keyguard needs to be shown whenever the device is non-interactive. Otherwise
        // there's no surface we can show to the user. Note that the device goes fully interactive
        // late in the transition, so we also allow the device to start dozing once the screen has
        // turned off fully.
        boolean keyguardForDozing = mDozingRequested &&
                (!mDeviceInteractive || isGoingToSleep() && (isScreenFullyOff() || mIsKeyguard));
        boolean shouldBeKeyguard = (mKeyguardRequested || keyguardForDozing) && !wakeAndUnlocking;
        if (keyguardForDozing) {
            updatePanelExpansionForKeyguard();
        }
        if (shouldBeKeyguard) {
            if (isGoingToSleep()
                    && mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_TURNING_OFF) {
                // Delay showing the keyguard until screen turned off.
            } else {
                showKeyguardImpl();
            }
        } else {
            return hideKeyguardImpl();
        }
        return false;
    }

    public void showKeyguardImpl() {
        mIsKeyguard = true;
        if (mLaunchTransitionFadingAway) {
            mNotificationPanel.animate().cancel();
            onLaunchTransitionFadingEnded();
        }
        mHandler.removeMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        if (mUserSwitcherController != null && mUserSwitcherController.useFullscreenUserSwitcher()) {
            setBarState(StatusBarState.FULLSCREEN_USER_SWITCHER);
        } else {
            setBarState(StatusBarState.KEYGUARD);
        }
        updateKeyguardState(false /* goingToFullShade */, false /* fromShadeLocked */);
        updatePanelExpansionForKeyguard();
        if (mDraggedDownRow != null) {
            mDraggedDownRow.setUserLocked(false);
            mDraggedDownRow.notifyHeightChanged(false  /* needsAnimation */);
            mDraggedDownRow = null;
        }
    }

    private void updatePanelExpansionForKeyguard() {
        if (mState == StatusBarState.KEYGUARD && mFingerprintUnlockController.getMode()
                != FingerprintUnlockController.MODE_WAKE_AND_UNLOCK) {
            instantExpandNotificationsPanel();
        } else if (mState == StatusBarState.FULLSCREEN_USER_SWITCHER) {
            instantCollapseNotificationPanel();
        }
    }

    private void onLaunchTransitionFadingEnded() {
        mNotificationPanel.setAlpha(1.0f);
        mNotificationPanel.onAffordanceLaunchEnded();
        releaseGestureWakeLock();
        runLaunchTransitionEndRunnable();
        mLaunchTransitionFadingAway = false;
        mScrimController.forceHideScrims(false /* hide */, false /* animated */);
        updateMediaMetaData(true /* metaDataChanged */, true);
    }

    public boolean isCollapsing() {
        return mNotificationPanel.isCollapsing();
    }

    public void addPostCollapseAction(Runnable r) {
        mPostCollapseRunnables.add(r);
    }

    public boolean isInLaunchTransition() {
        return mNotificationPanel.isLaunchTransitionRunning()
                || mNotificationPanel.isLaunchTransitionFinished();
    }

    /**
     * Fades the content of the keyguard away after the launch transition is done.
     *
     * @param beforeFading the runnable to be run when the circle is fully expanded and the fading
     *                     starts
     * @param endRunnable the runnable to be run when the transition is done
     */
    public void fadeKeyguardAfterLaunchTransition(final Runnable beforeFading,
            Runnable endRunnable) {
        mHandler.removeMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        mLaunchTransitionEndRunnable = endRunnable;
        Runnable hideRunnable = new Runnable() {
            @Override
            public void run() {
                mLaunchTransitionFadingAway = true;
                if (beforeFading != null) {
                    beforeFading.run();
                }
                mScrimController.forceHideScrims(true /* hide */, false /* animated */);
                updateMediaMetaData(false, true);
                mNotificationPanel.setAlpha(1);
                mStackScroller.setParentNotFullyVisible(true);
                mNotificationPanel.animate()
                        .alpha(0)
                        .setStartDelay(FADE_KEYGUARD_START_DELAY)
                        .setDuration(FADE_KEYGUARD_DURATION)
                        .withLayer()
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                onLaunchTransitionFadingEnded();
                            }
                        });
                mCommandQueue.appTransitionStarting(SystemClock.uptimeMillis(),
                        LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
            }
        };
        if (mNotificationPanel.isLaunchTransitionRunning()) {
            mNotificationPanel.setLaunchTransitionEndRunnable(hideRunnable);
        } else {
            hideRunnable.run();
        }
    }

    /**
     * Fades the content of the Keyguard while we are dozing and makes it invisible when finished
     * fading.
     */
    public void fadeKeyguardWhilePulsing() {
        mNotificationPanel.notifyStartFading();
        mNotificationPanel.animate()
                .alpha(0f)
                .setStartDelay(0)
                .setDuration(FADE_KEYGUARD_DURATION_PULSING)
                .setInterpolator(ScrimController.KEYGUARD_FADE_OUT_INTERPOLATOR)
                .start();
    }

    /**
     * Plays the animation when an activity that was occluding Keyguard goes away.
     */
    public void animateKeyguardUnoccluding() {
        mScrimController.animateKeyguardUnoccluding(500);
        mNotificationPanel.setExpandedFraction(0f);
        animateExpandNotificationsPanel();
    }

    /**
     * Starts the timeout when we try to start the affordances on Keyguard. We usually rely that
     * Keyguard goes away via fadeKeyguardAfterLaunchTransition, however, that might not happen
     * because the launched app crashed or something else went wrong.
     */
    public void startLaunchTransitionTimeout() {
        mHandler.sendEmptyMessageDelayed(MSG_LAUNCH_TRANSITION_TIMEOUT,
                LAUNCH_TRANSITION_TIMEOUT_MS);
    }

    private void onLaunchTransitionTimeout() {
        Log.w(TAG, "Launch transition: Timeout!");
        mNotificationPanel.onAffordanceLaunchEnded();
        releaseGestureWakeLock();
        mNotificationPanel.resetViews();
    }

    private void runLaunchTransitionEndRunnable() {
        if (mLaunchTransitionEndRunnable != null) {
            Runnable r = mLaunchTransitionEndRunnable;

            // mLaunchTransitionEndRunnable might call showKeyguard, which would execute it again,
            // which would lead to infinite recursion. Protect against it.
            mLaunchTransitionEndRunnable = null;
            r.run();
        }
    }

    /**
     * @return true if we would like to stay in the shade, false if it should go away entirely
     */
    public boolean hideKeyguardImpl() {
        mIsKeyguard = false;
        Trace.beginSection("StatusBar#hideKeyguard");
        boolean staying = mLeaveOpenOnKeyguardHide;
        setBarState(StatusBarState.SHADE);
        View viewToClick = null;
        if (mLeaveOpenOnKeyguardHide) {
            if (!mKeyguardRequested) {
                mLeaveOpenOnKeyguardHide = false;
            }
            long delay = calculateGoingToFullShadeDelay();
            mNotificationPanel.animateToFullShade(delay);
            if (mDraggedDownRow != null) {
                mDraggedDownRow.setUserLocked(false);
                mDraggedDownRow = null;
            }
            if (!mKeyguardRequested) {
                viewToClick = mPendingRemoteInputView;
                mPendingRemoteInputView = null;
            }

            // Disable layout transitions in navbar for this transition because the load is just
            // too heavy for the CPU and GPU on any device.
            if (mNavigationBar != null) {
                mNavigationBar.disableAnimationsDuringHide(delay);
            }
        } else if (!mNotificationPanel.isCollapsing()) {
            instantCollapseNotificationPanel();
        }
        updateKeyguardState(staying, false /* fromShadeLocked */);

        if (viewToClick != null && viewToClick.isAttachedToWindow()) {
            viewToClick.callOnClick();
        }

        // Keyguard state has changed, but QS is not listening anymore. Make sure to update the tile
        // visibilities so next time we open the panel we know the correct height already.
        if (mQSPanel != null) {
            mQSPanel.refreshAllTiles();
        }
        mHandler.removeMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        releaseGestureWakeLock();
        mNotificationPanel.onAffordanceLaunchEnded();
        mNotificationPanel.animate().cancel();
        mNotificationPanel.setAlpha(1f);
        Trace.endSection();
        return staying;
    }

    private void releaseGestureWakeLock() {
        if (mGestureWakeLock.isHeld()) {
            mGestureWakeLock.release();
        }
    }

    public long calculateGoingToFullShadeDelay() {
        return mKeyguardFadingAwayDelay + mKeyguardFadingAwayDuration;
    }

    /**
     * Notifies the status bar that Keyguard is going away very soon.
     */
    public void keyguardGoingAway() {

        // Treat Keyguard exit animation as an app transition to achieve nice transition for status
        // bar.
        mKeyguardGoingAway = true;
        mKeyguardMonitor.notifyKeyguardGoingAway(true);
        mCommandQueue.appTransitionPending(true);
    }

    /**
     * Notifies the status bar the Keyguard is fading away with the specified timings.
     *
     * @param startTime the start time of the animations in uptime millis
     * @param delay the precalculated animation delay in miliseconds
     * @param fadeoutDuration the duration of the exit animation, in milliseconds
     */
    public void setKeyguardFadingAway(long startTime, long delay, long fadeoutDuration) {
        mKeyguardFadingAway = true;
        mKeyguardFadingAwayDelay = delay;
        mKeyguardFadingAwayDuration = fadeoutDuration;
        mWaitingForKeyguardExit = false;
        mCommandQueue.appTransitionStarting(startTime + fadeoutDuration
                        - LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION,
                LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        recomputeDisableFlags(fadeoutDuration > 0 /* animate */);
        mCommandQueue.appTransitionStarting(
                    startTime - LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION,
                    LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        mKeyguardMonitor.notifyKeyguardFadingAway(delay, fadeoutDuration);
    }

    public boolean isKeyguardFadingAway() {
        return mKeyguardFadingAway;
    }

    /**
     * Notifies that the Keyguard fading away animation is done.
     */
    public void finishKeyguardFadingAway() {
        mKeyguardFadingAway = false;
        mKeyguardGoingAway = false;
        mKeyguardMonitor.notifyKeyguardDoneFading();
    }

    public void stopWaitingForKeyguardExit() {
        mWaitingForKeyguardExit = false;
    }

    private void updatePublicMode() {
        final boolean showingKeyguard = mStatusBarKeyguardViewManager.isShowing();
        final boolean devicePublic = showingKeyguard
                && mStatusBarKeyguardViewManager.isSecure(mCurrentUserId);

        // Look for public mode users. Users are considered public in either case of:
        //   - device keyguard is shown in secure mode;
        //   - profile is locked with a work challenge.
        for (int i = mCurrentProfiles.size() - 1; i >= 0; i--) {
            final int userId = mCurrentProfiles.valueAt(i).id;
            boolean isProfilePublic = devicePublic;
            if (!devicePublic && userId != mCurrentUserId) {
                // We can't rely on KeyguardManager#isDeviceLocked() for unified profile challenge
                // due to a race condition where this code could be called before
                // TrustManagerService updates its internal records, resulting in an incorrect
                // state being cached in mLockscreenPublicMode. (b/35951989)
                if (mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)
                        && mStatusBarKeyguardViewManager.isSecure(userId)) {
                    isProfilePublic = mKeyguardManager.isDeviceLocked(userId);
                }
            }
            setLockscreenPublicMode(isProfilePublic, userId);
        }
    }

    protected void updateKeyguardState(boolean goingToFullShade, boolean fromShadeLocked) {
        Trace.beginSection("StatusBar#updateKeyguardState");
        if (mState == StatusBarState.KEYGUARD) {
            mKeyguardIndicationController.setVisible(true);
            mNotificationPanel.resetViews();
            if (mKeyguardUserSwitcher != null) {
                mKeyguardUserSwitcher.setKeyguard(true, fromShadeLocked);
            }
            if (mStatusBarView != null) mStatusBarView.removePendingHideExpandedRunnables();
            if (mAmbientIndicationContainer != null) {
                mAmbientIndicationContainer.setVisibility(View.VISIBLE);
            }
        } else {
            mKeyguardIndicationController.setVisible(false);
            if (mKeyguardUserSwitcher != null) {
                mKeyguardUserSwitcher.setKeyguard(false,
                        goingToFullShade ||
                        mState == StatusBarState.SHADE_LOCKED ||
                        fromShadeLocked);
            }
            if (mAmbientIndicationContainer != null) {
                mAmbientIndicationContainer.setVisibility(View.INVISIBLE);
            }
        }
        if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
            mScrimController.setKeyguardShowing(true);
        } else {
            mScrimController.setKeyguardShowing(false);
        }
        mNotificationPanel.setBarState(mState, mKeyguardFadingAway, goingToFullShade);
        updateTheme();
        updateDozingState();
        updatePublicMode();
        updateStackScrollerState(goingToFullShade, fromShadeLocked);
        updateNotifications();
        checkBarModes();
        updateMediaMetaData(false, mState != StatusBarState.KEYGUARD);
        mKeyguardMonitor.notifyKeyguardState(mStatusBarKeyguardViewManager.isShowing(),
                mUnlockMethodCache.isMethodSecure(),
                mStatusBarKeyguardViewManager.isOccluded());
        Trace.endSection();
    }

    /**
     * Switches theme from light to dark and vice-versa.
     */
    protected void updateTheme() {
        final boolean inflated = mStackScroller != null;

        // The system wallpaper defines if QS should be light or dark.
        WallpaperColors systemColors = mColorExtractor
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
        final boolean useDarkTheme = systemColors != null
                && (systemColors.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_THEME) != 0;
        if (isUsingDarkTheme() != useDarkTheme) {
            try {
                mOverlayManager.setEnabled("com.android.systemui.theme.dark",
                        useDarkTheme, mCurrentUserId);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change theme", e);
            }
        }

        // Lock wallpaper defines the color of the majority of the views, hence we'll use it
        // to set our default theme.
        final boolean lockDarkText = mColorExtractor.getColors(WallpaperManager.FLAG_LOCK, true
                /* ignoreVisibility */).supportsDarkText();
        final int themeResId = lockDarkText ? R.style.Theme_SystemUI_Light : R.style.Theme_SystemUI;
        if (mContext.getThemeResId() != themeResId) {
            mContext.setTheme(themeResId);
            if (inflated) {
                reinflateViews();
            }
        }

        if (inflated) {
            int which;
            if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
                which = WallpaperManager.FLAG_LOCK;
            } else {
                which = WallpaperManager.FLAG_SYSTEM;
            }
            final boolean useDarkText = mColorExtractor.getColors(which,
                    true /* ignoreVisibility */).supportsDarkText();
            mStackScroller.updateDecorViews(useDarkText);

            // Make sure we have the correct navbar/statusbar colors.
            mStatusBarWindowManager.setKeyguardDark(useDarkText);
        }
    }

    private void updateDozingState() {
        Trace.traceCounter(Trace.TRACE_TAG_APP, "dozing", mDozing ? 1 : 0);
        Trace.beginSection("StatusBar#updateDozingState");
        boolean animate = !mDozing && mDozeServiceHost.shouldAnimateWakeup();
        mNotificationPanel.setDozing(mDozing, animate);
        mStackScroller.setDark(mDozing, animate, mWakeUpTouchLocation);
        mScrimController.setDozing(mDozing);
        mKeyguardIndicationController.setDozing(mDozing);
        mNotificationPanel.setDark(mDozing, animate);
        updateQsExpansionEnabled();
        mDozeScrimController.setDozing(mDozing, animate);
        updateRowStates();
        Trace.endSection();
    }

    public void updateStackScrollerState(boolean goingToFullShade, boolean fromShadeLocked) {
        if (mStackScroller == null) return;
        boolean onKeyguard = mState == StatusBarState.KEYGUARD;
        boolean publicMode = isAnyProfilePublicMode();
        mStackScroller.setHideSensitive(publicMode, goingToFullShade);
        mStackScroller.setDimmed(onKeyguard, fromShadeLocked /* animate */);
        mStackScroller.setExpandingEnabled(!onKeyguard);
        ActivatableNotificationView activatedChild = mStackScroller.getActivatedChild();
        mStackScroller.setActivatedChild(null);
        if (activatedChild != null) {
            activatedChild.makeInactive(false /* animate */);
        }
    }

    public void userActivity() {
        if (mState == StatusBarState.KEYGUARD) {
            mKeyguardViewMediatorCallback.userActivity();
        }
    }

    public boolean interceptMediaKey(KeyEvent event) {
        return mState == StatusBarState.KEYGUARD
                && mStatusBarKeyguardViewManager.interceptMediaKey(event);
    }

    protected boolean shouldUnlockOnMenuPressed() {
        return mDeviceInteractive && mState != StatusBarState.SHADE
            && mStatusBarKeyguardViewManager.shouldDismissOnMenuPressed();
    }

    public boolean onMenuPressed() {
        if (shouldUnlockOnMenuPressed()) {
            animateCollapsePanels(
                    CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL /* flags */, true /* force */);
            return true;
        }
        return false;
    }

    public void endAffordanceLaunch() {
        releaseGestureWakeLock();
        mNotificationPanel.onAffordanceLaunchEnded();
    }

    public boolean onBackPressed() {
        if (mStatusBarKeyguardViewManager.onBackPressed()) {
            return true;
        }
        if (mNotificationPanel.isQsExpanded()) {
            if (mNotificationPanel.isQsDetailShowing()) {
                mNotificationPanel.closeQsDetail();
            } else {
                mNotificationPanel.animateCloseQs();
            }
            return true;
        }
        if (mState != StatusBarState.KEYGUARD && mState != StatusBarState.SHADE_LOCKED) {
            animateCollapsePanels();
            return true;
        }
        if (mKeyguardUserSwitcher != null && mKeyguardUserSwitcher.hideIfNotSimple(true)) {
            return true;
        }
        return false;
    }

    public boolean onSpacePressed() {
        if (mDeviceInteractive && mState != StatusBarState.SHADE) {
            animateCollapsePanels(
                    CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL /* flags */, true /* force */);
            return true;
        }
        return false;
    }

    private void showBouncerIfKeyguard() {
        if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
            showBouncer();
        }
    }

    protected void showBouncer() {
        mWaitingForKeyguardExit = mStatusBarKeyguardViewManager.isShowing();
        mStatusBarKeyguardViewManager.dismiss();
    }

    private void instantExpandNotificationsPanel() {
        // Make our window larger and the panel expanded.
        makeExpandedVisible(true);
        mNotificationPanel.expand(false /* animate */);
        recomputeDisableFlags(false /* animate */);
    }

    private void instantCollapseNotificationPanel() {
        mNotificationPanel.instantCollapse();
    }

    @Override
    public void onActivated(ActivatableNotificationView view) {
        onActivated((View)view);
        mStackScroller.setActivatedChild(view);
    }

    public void onActivated(View view) {
        mLockscreenGestureLogger.write(
                MetricsEvent.ACTION_LS_NOTE,
                0 /* lengthDp - N/A */, 0 /* velocityDp - N/A */);
        mKeyguardIndicationController.showTransientIndication(R.string.notification_tap_again);
        ActivatableNotificationView previousView = mStackScroller.getActivatedChild();
        if (previousView != null) {
            previousView.makeInactive(true /* animate */);
        }
    }

    /**
     * @param state The {@link StatusBarState} to set.
     */
    public void setBarState(int state) {
        // If we're visible and switched to SHADE_LOCKED (the user dragged
        // down on the lockscreen), clear notification LED, vibration,
        // ringing.
        // Other transitions are covered in handleVisibleToUserChanged().
        if (state != mState && mVisible && (state == StatusBarState.SHADE_LOCKED
                || (state == StatusBarState.SHADE && isGoingToNotificationShade()))) {
            clearNotificationEffects();
        }
        if (state == StatusBarState.KEYGUARD) {
            removeRemoteInputEntriesKeptUntilCollapsed();
            maybeEscalateHeadsUp();
        }
        mState = state;
        mGroupManager.setStatusBarState(state);
        mHeadsUpManager.setStatusBarState(state);
        mFalsingManager.setStatusBarState(state);
        mStatusBarWindowManager.setStatusBarState(state);
        mStackScroller.setStatusBarState(state);
        updateReportRejectedTouchVisibility();
        updateDozing();
        updateTheme();
        touchAutoDim();
        mNotificationShelf.setStatusBarState(state);
    }

    @Override
    public void onActivationReset(ActivatableNotificationView view) {
        if (view == mStackScroller.getActivatedChild()) {
            mStackScroller.setActivatedChild(null);
            onActivationReset((View)view);
        }
    }

    public void onActivationReset(View view) {
        mKeyguardIndicationController.hideTransientIndication();
    }

    public void onTrackingStarted() {
        runPostCollapseRunnables();
    }

    public void onClosingFinished() {
        runPostCollapseRunnables();
        if (!isPanelFullyCollapsed()) {
            // if we set it not to be focusable when collapsing, we have to undo it when we aborted
            // the closing
            mStatusBarWindowManager.setStatusBarFocusable(true);
        }
    }

    public void onUnlockHintStarted() {
        mFalsingManager.onUnlockHintStarted();
        mKeyguardIndicationController.showTransientIndication(R.string.keyguard_unlock);
    }

    public void onHintFinished() {
        // Delay the reset a bit so the user can read the text.
        mKeyguardIndicationController.hideTransientIndicationDelayed(HINT_RESET_DELAY_MS);
    }

    public void onCameraHintStarted() {
        mFalsingManager.onCameraHintStarted();
        mKeyguardIndicationController.showTransientIndication(R.string.camera_hint);
    }

    public void onVoiceAssistHintStarted() {
        mFalsingManager.onLeftAffordanceHintStarted();
        mKeyguardIndicationController.showTransientIndication(R.string.voice_hint);
    }

    public void onPhoneHintStarted() {
        mFalsingManager.onLeftAffordanceHintStarted();
        mKeyguardIndicationController.showTransientIndication(R.string.phone_hint);
    }

    public void onCustomHintStarted() {
        mKeyguardIndicationController.showTransientIndication(R.string.custom_hint);
    }

    public void onTrackingStopped(boolean expand) {
        if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
            if (!expand && !mUnlockMethodCache.canSkipBouncer()) {
                showBouncerIfKeyguard();
            }
        }
    }

    protected int getMaxKeyguardNotifications(boolean recompute) {
        if (recompute) {
            mMaxKeyguardNotifications = Math.max(1,
                    mNotificationPanel.computeMaxKeyguardNotifications(
                            mMaxAllowedKeyguardNotifications));
            return mMaxKeyguardNotifications;
        }
        return mMaxKeyguardNotifications;
    }

    public int getMaxKeyguardNotifications() {
        return getMaxKeyguardNotifications(false /* recompute */);
    }

    // TODO: Figure out way to remove these.
    public NavigationBarView getNavigationBarView() {
        return (mNavigationBar != null ? (NavigationBarView) mNavigationBar.getView() : null);
    }

    public View getNavigationBarWindow() {
        return mNavigationBarView;
    }

    /**
     * TODO: Remove this method. Views should not be passed forward. Will cause theme issues.
     * @return bottom area view
     */
    public KeyguardBottomAreaView getKeyguardBottomAreaView() {
        return mNotificationPanel.getKeyguardBottomAreaView();
    }

    // ---------------------- DragDownHelper.OnDragDownListener ------------------------------------


    /* Only ever called as a consequence of a lockscreen expansion gesture. */
    @Override
    public boolean onDraggedDown(View startingChild, int dragLengthY) {
        if (mState == StatusBarState.KEYGUARD
                && hasActiveNotifications() && (!isDozing() || isPulsing())) {
            mLockscreenGestureLogger.write(
                    MetricsEvent.ACTION_LS_SHADE,
                    (int) (dragLengthY / mDisplayMetrics.density),
                    0 /* velocityDp - N/A */);

            // We have notifications, go to locked shade.
            goToLockedShade(startingChild);
            if (startingChild instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) startingChild;
                row.onExpandedByGesture(true /* drag down is always an open */);
            }
            return true;
        } else {
            // abort gesture.
            return false;
        }
    }

    @Override
    public void onDragDownReset() {
        mStackScroller.setDimmed(true /* dimmed */, true /* animated */);
        mStackScroller.resetScrollPosition();
        mStackScroller.resetCheckSnoozeLeavebehind();
    }

    @Override
    public void onCrossedThreshold(boolean above) {
        mStackScroller.setDimmed(!above /* dimmed */, true /* animate */);
    }

    @Override
    public void onTouchSlopExceeded() {
        mStackScroller.removeLongPressCallback();
        mStackScroller.checkSnoozeLeavebehind();
    }

    @Override
    public void setEmptyDragAmount(float amount) {
        mNotificationPanel.setEmptyDragAmount(amount);
    }

    @Override
    public boolean isFalsingCheckNeeded() {
        return mState == StatusBarState.KEYGUARD;
    }

    /**
     * If secure with redaction: Show bouncer, go to unlocked shade.
     *
     * <p>If secure without redaction or no security: Go to {@link StatusBarState#SHADE_LOCKED}.</p>
     *
     * @param expandView The view to expand after going to the shade.
     */
    public void goToLockedShade(View expandView) {
        int userId = mCurrentUserId;
        ExpandableNotificationRow row = null;
        if (expandView instanceof ExpandableNotificationRow) {
            row = (ExpandableNotificationRow) expandView;
            row.setUserExpanded(true /* userExpanded */, true /* allowChildExpansion */);
            // Indicate that the group expansion is changing at this time -- this way the group
            // and children backgrounds / divider animations will look correct.
            row.setGroupExpansionChanging(true);
            if (row.getStatusBarNotification() != null) {
                userId = row.getStatusBarNotification().getUserId();
            }
        }
        boolean fullShadeNeedsBouncer = !userAllowsPrivateNotificationsInPublic(mCurrentUserId)
                || !mShowLockscreenNotifications || mFalsingManager.shouldEnforceBouncer();
        if (isLockscreenPublicMode(userId) && fullShadeNeedsBouncer) {
            mLeaveOpenOnKeyguardHide = true;
            showBouncerIfKeyguard();
            mDraggedDownRow = row;
            mPendingRemoteInputView = null;
        } else {
            mNotificationPanel.animateToFullShade(0 /* delay */);
            setBarState(StatusBarState.SHADE_LOCKED);
            updateKeyguardState(false /* goingToFullShade */, false /* fromShadeLocked */);
        }
    }

    public void onLockedNotificationImportanceChange(OnDismissAction dismissAction) {
        mLeaveOpenOnKeyguardHide = true;
        dismissKeyguardThenExecute(dismissAction, true /* afterKeyguardGone */);
    }

    protected void onLockedRemoteInput(ExpandableNotificationRow row, View clicked) {
        mLeaveOpenOnKeyguardHide = true;
        showBouncer();
        mPendingRemoteInputView = clicked;
    }

    protected void onMakeExpandedVisibleForRemoteInput(ExpandableNotificationRow row,
            View clickedView) {
        if (isKeyguardShowing()) {
            onLockedRemoteInput(row, clickedView);
        } else {
            row.setUserExpanded(true);
            row.getPrivateLayout().setOnExpandedVisibleListener(clickedView::performClick);
        }
    }

    protected boolean startWorkChallengeIfNecessary(int userId, IntentSender intendSender,
            String notificationKey) {
        // Clear pending remote view, as we do not want to trigger pending remote input view when
        // it's called by other code
        mPendingWorkRemoteInputView = null;
        // Begin old BaseStatusBar.startWorkChallengeIfNecessary.
        final Intent newIntent = mKeyguardManager.createConfirmDeviceCredentialIntent(null,
                null, userId);
        if (newIntent == null) {
            return false;
        }
        final Intent callBackIntent = new Intent(NOTIFICATION_UNLOCKED_BY_WORK_CHALLENGE_ACTION);
        callBackIntent.putExtra(Intent.EXTRA_INTENT, intendSender);
        callBackIntent.putExtra(Intent.EXTRA_INDEX, notificationKey);
        callBackIntent.setPackage(mContext.getPackageName());

        PendingIntent callBackPendingIntent = PendingIntent.getBroadcast(
                mContext,
                0,
                callBackIntent,
                PendingIntent.FLAG_CANCEL_CURRENT |
                        PendingIntent.FLAG_ONE_SHOT |
                        PendingIntent.FLAG_IMMUTABLE);
        newIntent.putExtra(
                Intent.EXTRA_INTENT,
                callBackPendingIntent.getIntentSender());
        try {
            ActivityManager.getService().startConfirmDeviceCredentialIntent(newIntent,
                    null /*options*/);
        } catch (RemoteException ex) {
            // ignore
        }
        return true;
        // End old BaseStatusBar.startWorkChallengeIfNecessary.
    }

    protected void onLockedWorkRemoteInput(int userId, ExpandableNotificationRow row,
            View clicked) {
        // Collapse notification and show work challenge
        animateCollapsePanels();
        startWorkChallengeIfNecessary(userId, null, null);
        // Add pending remote input view after starting work challenge, as starting work challenge
        // will clear all previous pending review view
        mPendingWorkRemoteInputView = clicked;
    }

    private boolean isAnyProfilePublicMode() {
        for (int i = mCurrentProfiles.size() - 1; i >= 0; i--) {
            if (isLockscreenPublicMode(mCurrentProfiles.valueAt(i).id)) {
                return true;
            }
        }
        return false;
    }

    protected void onWorkChallengeChanged() {
        updatePublicMode();
        updateNotifications();
        if (mPendingWorkRemoteInputView != null && !isAnyProfilePublicMode()) {
            // Expand notification panel and the notification row, then click on remote input view
            final Runnable clickPendingViewRunnable = new Runnable() {
                @Override
                public void run() {
                    final View pendingWorkRemoteInputView = mPendingWorkRemoteInputView;
                    if (pendingWorkRemoteInputView == null) {
                        return;
                    }

                    // Climb up the hierarchy until we get to the container for this row.
                    ViewParent p = pendingWorkRemoteInputView.getParent();
                    while (!(p instanceof ExpandableNotificationRow)) {
                        if (p == null) {
                            return;
                        }
                        p = p.getParent();
                    }

                    final ExpandableNotificationRow row = (ExpandableNotificationRow) p;
                    ViewParent viewParent = row.getParent();
                    if (viewParent instanceof NotificationStackScrollLayout) {
                        final NotificationStackScrollLayout scrollLayout =
                                (NotificationStackScrollLayout) viewParent;
                        row.makeActionsVisibile();
                        row.post(new Runnable() {
                            @Override
                            public void run() {
                                final Runnable finishScrollingCallback = new Runnable() {
                                    @Override
                                    public void run() {
                                        mPendingWorkRemoteInputView.callOnClick();
                                        mPendingWorkRemoteInputView = null;
                                        scrollLayout.setFinishScrollingCallback(null);
                                    }
                                };
                                if (scrollLayout.scrollTo(row)) {
                                    // It scrolls! So call it when it's finished.
                                    scrollLayout.setFinishScrollingCallback(
                                            finishScrollingCallback);
                                } else {
                                    // It does not scroll, so call it now!
                                    finishScrollingCallback.run();
                                }
                            }
                        });
                    }
                }
            };
            mNotificationPanel.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            if (mNotificationPanel.mStatusBar.getStatusBarWindow()
                                    .getHeight() != mNotificationPanel.mStatusBar
                                            .getStatusBarHeight()) {
                                mNotificationPanel.getViewTreeObserver()
                                        .removeOnGlobalLayoutListener(this);
                                mNotificationPanel.post(clickPendingViewRunnable);
                            }
                        }
                    });
            instantExpandNotificationsPanel();
        }
    }

    @Override
    public void onExpandClicked(Entry clickedEntry, boolean nowExpanded) {
        mHeadsUpManager.setExpanded(clickedEntry, nowExpanded);
        if (mState == StatusBarState.KEYGUARD && nowExpanded) {
            goToLockedShade(clickedEntry.row);
        }
    }

    /**
     * Goes back to the keyguard after hanging around in {@link StatusBarState#SHADE_LOCKED}.
     */
    public void goToKeyguard() {
        if (mState == StatusBarState.SHADE_LOCKED) {
            mStackScroller.onGoToKeyguard();
            setBarState(StatusBarState.KEYGUARD);
            updateKeyguardState(false /* goingToFullShade */, true /* fromShadeLocked*/);
        }
    }

    public long getKeyguardFadingAwayDelay() {
        return mKeyguardFadingAwayDelay;
    }

    public long getKeyguardFadingAwayDuration() {
        return mKeyguardFadingAwayDuration;
    }

    public void setBouncerShowing(boolean bouncerShowing) {
        mBouncerShowing = bouncerShowing;
        if (mStatusBarView != null) mStatusBarView.setBouncerShowing(bouncerShowing);
        updateHideIconsForBouncer(true /* animate */);
        recomputeDisableFlags(true /* animate */);
    }

    public void cancelCurrentTouch() {
        if (mNotificationPanel.isTracking()) {
            mStatusBarWindow.cancelCurrentTouch();
            if (mState == StatusBarState.SHADE) {
                animateCollapsePanels();
            }
        }
    }

    WakefulnessLifecycle.Observer mWakefulnessObserver = new WakefulnessLifecycle.Observer() {
        @Override
        public void onFinishedGoingToSleep() {
            mNotificationPanel.onAffordanceLaunchEnded();
            releaseGestureWakeLock();
            mLaunchCameraOnScreenTurningOn = false;
            mDeviceInteractive = false;
            mWakeUpComingFromTouch = false;
            mWakeUpTouchLocation = null;
            mStackScroller.setAnimationsEnabled(false);
            mVisualStabilityManager.setScreenOn(false);
            updateVisibleToUser();

            // We need to disable touch events because these might
            // collapse the panel after we expanded it, and thus we would end up with a blank
            // Keyguard.
            mNotificationPanel.setTouchDisabled(true);
            mStatusBarWindow.cancelCurrentTouch();
            if (mLaunchCameraOnFinishedGoingToSleep) {
                mLaunchCameraOnFinishedGoingToSleep = false;

                // This gets executed before we will show Keyguard, so post it in order that the state
                // is correct.
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onCameraLaunchGestureDetected(mLastCameraLaunchSource);
                    }
                });
            }
            updateIsKeyguard();
        }

        @Override
        public void onStartedGoingToSleep() {
            notifyHeadsUpGoingToSleep();
            dismissVolumeDialog();
        }

        @Override
        public void onStartedWakingUp() {
            mDeviceInteractive = true;
            mStackScroller.setAnimationsEnabled(true);
            mVisualStabilityManager.setScreenOn(true);
            mNotificationPanel.setTouchDisabled(false);

            maybePrepareWakeUpFromAod();

            mDozeServiceHost.stopDozing();
            updateVisibleToUser();
            updateIsKeyguard();
        }
    };

    ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurningOn() {
            mFalsingManager.onScreenTurningOn();
            mNotificationPanel.onScreenTurningOn();

            maybePrepareWakeUpFromAod();

            if (mLaunchCameraOnScreenTurningOn) {
                mNotificationPanel.launchCamera(false, mLastCameraLaunchSource);
                mLaunchCameraOnScreenTurningOn = false;
            }
        }

        @Override
        public void onScreenTurnedOn() {
            mScrimController.wakeUpFromAod();
            mDozeScrimController.onScreenTurnedOn();
        }

        @Override
        public void onScreenTurnedOff() {
            mFalsingManager.onScreenOff();
            // If we pulse in from AOD, we turn the screen off first. However, updatingIsKeyguard
            // in that case destroys the HeadsUpManager state, so don't do it in that case.
            if (!isPulsing()) {
                updateIsKeyguard();
            }
        }
    };

    public int getWakefulnessState() {
        return mWakefulnessLifecycle.getWakefulness();
    }

    private void maybePrepareWakeUpFromAod() {
        int wakefulness = mWakefulnessLifecycle.getWakefulness();
        if (mDozing && wakefulness == WAKEFULNESS_WAKING && !isPulsing()) {
            mScrimController.prepareWakeUpFromAod();
        }
    }

    private void vibrateForCameraGesture() {
        // Make sure to pass -1 for repeat so VibratorService doesn't stop us when going to sleep.
        mVibrator.vibrate(mCameraLaunchGestureVibePattern, -1 /* repeat */);
    }

    /**
     * @return true if the screen is currently fully off, i.e. has finished turning off and has
     *         since not started turning on.
     */
    public boolean isScreenFullyOff() {
        return mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_OFF;
    }

    @Override
    public void showScreenPinningRequest(int taskId) {
        if (mKeyguardMonitor.isShowing()) {
            // Don't allow apps to trigger this from keyguard.
            return;
        }
        // Show screen pinning request, since this comes from an app, show 'no thanks', button.
        showScreenPinningRequest(taskId, true);
    }

    public void showScreenPinningRequest(int taskId, boolean allowCancel) {
        mScreenPinningRequest.showPrompt(taskId, allowCancel);
    }

    public boolean hasActiveNotifications() {
        return !mNotificationData.getActiveNotifications().isEmpty();
    }

    public void wakeUpIfDozing(long time, View where) {
        if (mDozing) {
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            pm.wakeUp(time, "com.android.systemui:NODOZE");
            mWakeUpComingFromTouch = true;
            where.getLocationInWindow(mTmpInt2);
            mWakeUpTouchLocation = new PointF(mTmpInt2[0] + where.getWidth() / 2,
                    mTmpInt2[1] + where.getHeight() / 2);
            mStatusBarKeyguardViewManager.notifyDeviceWakeUpRequested();
            mFalsingManager.onScreenOnFromTouch();
        }
    }

    @Override
    public void appTransitionCancelled() {
        EventBus.getDefault().send(new AppTransitionFinishedEvent());
    }

    @Override
    public void appTransitionFinished() {
        EventBus.getDefault().send(new AppTransitionFinishedEvent());
    }

    @Override
    public void onCameraLaunchGestureDetected(int source) {
        mLastCameraLaunchSource = source;
        if (isGoingToSleep()) {
            if (DEBUG_CAMERA_LIFT) Slog.d(TAG, "Finish going to sleep before launching camera");
            mLaunchCameraOnFinishedGoingToSleep = true;
            return;
        }
        if (!mNotificationPanel.canCameraGestureBeLaunched(
                mStatusBarKeyguardViewManager.isShowing() && mExpandedVisible, source)) {
            if (DEBUG_CAMERA_LIFT) Slog.d(TAG, "Can't launch camera right now, mExpandedVisible: " +
                    mExpandedVisible);
            return;
        }
        if (!mDeviceInteractive) {
            PowerManager pm = mContext.getSystemService(PowerManager.class);
            pm.wakeUp(SystemClock.uptimeMillis(), "com.android.systemui:CAMERA_GESTURE");
            mStatusBarKeyguardViewManager.notifyDeviceWakeUpRequested();
        }
        vibrateForCameraGesture();
        if (!mStatusBarKeyguardViewManager.isShowing()) {
            startActivityDismissingKeyguard(KeyguardBottomAreaView.INSECURE_CAMERA_INTENT,
                    false /* onlyProvisioned */, true /* dismissShade */,
                    true /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */);
        } else {
            if (!mDeviceInteractive) {
                // Avoid flickering of the scrim when we instant launch the camera and the bouncer
                // comes on.
                mScrimController.dontAnimateBouncerChangesUntilNextFrame();
                mGestureWakeLock.acquire(LAUNCH_TRANSITION_TIMEOUT_MS + 1000L);
            }
            if (isScreenTurningOnOrOn()) {
                if (DEBUG_CAMERA_LIFT) Slog.d(TAG, "Launching camera");
                mNotificationPanel.launchCamera(mDeviceInteractive /* animate */, source);
            } else {
                // We need to defer the camera launch until the screen comes on, since otherwise
                // we will dismiss us too early since we are waiting on an activity to be drawn and
                // incorrectly get notified because of the screen on event (which resumes and pauses
                // some activities)
                if (DEBUG_CAMERA_LIFT) Slog.d(TAG, "Deferring until screen turns on");
                mLaunchCameraOnScreenTurningOn = true;
            }
        }
    }

    boolean isCameraAllowedByAdmin() {
        if (mDevicePolicyManager.getCameraDisabled(null, mCurrentUserId)) {
            return false;
        } else if (isKeyguardShowing() && isKeyguardSecure()) {
            // Check if the admin has disabled the camera specifically for the keyguard
            return (mDevicePolicyManager.getKeyguardDisabledFeatures(null, mCurrentUserId)
                    & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) == 0;
        }

        return true;
    }

    private boolean isGoingToSleep() {
        return mWakefulnessLifecycle.getWakefulness()
                == WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP;
    }

    private boolean isScreenTurningOnOrOn() {
        return mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_TURNING_ON
                || mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_ON;
    }

    public void notifyFpAuthModeChanged() {
        updateDozing();
    }

    private void updateDozing() {
        Trace.beginSection("StatusBar#updateDozing");
        // When in wake-and-unlock while pulsing, keep dozing state until fully unlocked.
        mDozing = mDozingRequested && mState == StatusBarState.KEYGUARD
                || mFingerprintUnlockController.getMode()
                        == FingerprintUnlockController.MODE_WAKE_AND_UNLOCK_PULSING;
        // When in wake-and-unlock we may not have received a change to mState
        // but we still should not be dozing, manually set to false.
        if (mFingerprintUnlockController.getMode() ==
                FingerprintUnlockController.MODE_WAKE_AND_UNLOCK) {
            mDozing = false;
        }
        mStatusBarWindowManager.setDozing(mDozing);
        mStatusBarKeyguardViewManager.setDozing(mDozing);
        if (mAmbientIndicationContainer instanceof DozeReceiver) {
            ((DozeReceiver) mAmbientIndicationContainer).setDozing(mDozing);
        }
        updateDozingState();
        Trace.endSection();
    }

    public boolean isKeyguardShowing() {
        if (mStatusBarKeyguardViewManager == null) {
            Slog.i(TAG, "isKeyguardShowing() called before startKeyguard(), returning true");
            return true;
        }
        return mStatusBarKeyguardViewManager.isShowing();
    }

    private final class DozeServiceHost implements DozeHost {
        private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();
        private boolean mAnimateWakeup;
        private boolean mIgnoreTouchWhilePulsing;

        @Override
        public String toString() {
            return "PSB.DozeServiceHost[mCallbacks=" + mCallbacks.size() + "]";
        }

        public void firePowerSaveChanged(boolean active) {
            for (Callback callback : mCallbacks) {
                callback.onPowerSaveChanged(active);
            }
        }

        public void fireNotificationHeadsUp() {
            for (Callback callback : mCallbacks) {
                callback.onNotificationHeadsUp();
            }
        }

        @Override
        public void addCallback(@NonNull Callback callback) {
            mCallbacks.add(callback);
        }

        @Override
        public void removeCallback(@NonNull Callback callback) {
            mCallbacks.remove(callback);
        }

        @Override
        public void startDozing() {
            if (!mDozingRequested) {
                mDozingRequested = true;
                DozeLog.traceDozing(mContext, mDozing);
                updateDozing();
                updateIsKeyguard();
            }
        }

        @Override
        public void pulseWhileDozing(@NonNull PulseCallback callback, int reason) {
            if (reason == DozeLog.PULSE_REASON_SENSOR_LONG_PRESS) {
                mPowerManager.wakeUp(SystemClock.uptimeMillis(), "com.android.systemui:NODOZE");
                startAssist(new Bundle());
                return;
            }

            mDozeScrimController.pulse(new PulseCallback() {

                @Override
                public void onPulseStarted() {
                    callback.onPulseStarted();
                    Collection<HeadsUpManager.HeadsUpEntry> pulsingEntries =
                            mHeadsUpManager.getAllEntries();
                    if (!pulsingEntries.isEmpty()) {
                        // Only pulse the stack scroller if there's actually something to show.
                        // Otherwise just show the always-on screen.
                        setPulsing(pulsingEntries);
                    }
                }

                @Override
                public void onPulseFinished() {
                    callback.onPulseFinished();
                    setPulsing(null);
                }

                private void setPulsing(Collection<HeadsUpManager.HeadsUpEntry> pulsing) {
                    mStackScroller.setPulsing(pulsing);
                    mNotificationPanel.setPulsing(pulsing != null);
                    mVisualStabilityManager.setPulsing(pulsing != null);
                    mIgnoreTouchWhilePulsing = false;
                }
            }, reason);
        }

        @Override
        public void stopDozing() {
            if (mDozingRequested) {
                mDozingRequested = false;
                DozeLog.traceDozing(mContext, mDozing);
                updateDozing();
            }
        }

        @Override
        public void onIgnoreTouchWhilePulsing(boolean ignore) {
            if (ignore != mIgnoreTouchWhilePulsing) {
                DozeLog.tracePulseTouchDisabledByProx(mContext, ignore);
            }
            mIgnoreTouchWhilePulsing = ignore;
            if (isDozing() && ignore) {
                mStatusBarWindow.cancelCurrentTouch();
            }
        }

        @Override
        public void dozeTimeTick() {
            mNotificationPanel.refreshTime();
        }

        @Override
        public boolean isPowerSaveActive() {
            return mBatteryController.isPowerSave();
        }

        @Override
        public boolean isPulsingBlocked() {
            return mFingerprintUnlockController.getMode()
                    == FingerprintUnlockController.MODE_WAKE_AND_UNLOCK;
        }

        @Override
        public boolean isProvisioned() {
            return mDeviceProvisionedController.isDeviceProvisioned()
                    && mDeviceProvisionedController.isCurrentUserSetup();
        }

        @Override
        public boolean isBlockingDoze() {
            if (mFingerprintUnlockController.hasPendingAuthentication()) {
                Log.i(TAG, "Blocking AOD because fingerprint has authenticated");
                return true;
            }
            return false;
        }

        @Override
        public void startPendingIntentDismissingKeyguard(PendingIntent intent) {
            StatusBar.this.startPendingIntentDismissingKeyguard(intent);
        }

        @Override
        public void abortPulsing() {
            mDozeScrimController.abortPulsing();
        }

        @Override
        public void extendPulse() {
            mDozeScrimController.extendPulse();
        }

        @Override
        public void setAnimateWakeup(boolean animateWakeup) {
            if (mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_AWAKE
                    || mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_WAKING) {
                // Too late to change the wakeup animation.
                return;
            }
            mAnimateWakeup = animateWakeup;
        }

        @Override
        public void onDoubleTap(float screenX, float screenY) {
            if (screenX > 0 && screenY > 0 && mAmbientIndicationContainer != null 
                && mAmbientIndicationContainer.getVisibility() == View.VISIBLE) {
                mAmbientIndicationContainer.getLocationOnScreen(mTmpInt2);
                float viewX = screenX - mTmpInt2[0];
                float viewY = screenY - mTmpInt2[1];
                if (0 <= viewX && viewX <= mAmbientIndicationContainer.getWidth()
                        && 0 <= viewY && viewY <= mAmbientIndicationContainer.getHeight()) {
                    dispatchDoubleTap(viewX, viewY);
                }
            }
        }

        @Override
        public void setDozeScreenBrightness(int value) {
            mStatusBarWindowManager.setDozeScreenBrightness(value);
        }

        @Override
        public void setAodDimmingScrim(float scrimOpacity) {
            mDozeScrimController.setAodDimmingScrim(scrimOpacity);
        }

        public void dispatchDoubleTap(float viewX, float viewY) {
            dispatchTap(mAmbientIndicationContainer, viewX, viewY);
            dispatchTap(mAmbientIndicationContainer, viewX, viewY);
        }

        private void dispatchTap(View view, float x, float y) {
            long now = SystemClock.elapsedRealtime();
            dispatchTouchEvent(view, x, y, now, MotionEvent.ACTION_DOWN);
            dispatchTouchEvent(view, x, y, now, MotionEvent.ACTION_UP);
        }

        private void dispatchTouchEvent(View view, float x, float y, long now, int action) {
            MotionEvent ev = MotionEvent.obtain(now, now, action, x, y, 0 /* meta */);
            view.dispatchTouchEvent(ev);
            ev.recycle();
        }

        private boolean shouldAnimateWakeup() {
            return mAnimateWakeup;
        }
    }

    public boolean shouldIgnoreTouch() {
        return isDozing() && mDozeServiceHost.mIgnoreTouchWhilePulsing;
    }

    // Begin Extra BaseStatusBar methods.

    protected CommandQueue mCommandQueue;
    protected IStatusBarService mBarService;

    // all notifications
    protected NotificationData mNotificationData;
    protected NotificationStackScrollLayout mStackScroller;

    protected NotificationGroupManager mGroupManager = new NotificationGroupManager();

    protected RemoteInputController mRemoteInputController;

    // for heads up notifications
    protected HeadsUpManager mHeadsUpManager;

    private AboveShelfObserver mAboveShelfObserver;

    // handling reordering
    protected VisualStabilityManager mVisualStabilityManager = new VisualStabilityManager();

    protected int mCurrentUserId = 0;
    final protected SparseArray<UserInfo> mCurrentProfiles = new SparseArray<UserInfo>();

    protected int mLayoutDirection = -1; // invalid
    protected AccessibilityManager mAccessibilityManager;

    protected boolean mDeviceInteractive;

    protected boolean mVisible;
    protected ArraySet<Entry> mHeadsUpEntriesToRemoveOnSwitch = new ArraySet<>();
    protected ArraySet<Entry> mRemoteInputEntriesToRemoveOnCollapse = new ArraySet<>();

    /**
     * Notifications with keys in this set are not actually around anymore. We kept them around
     * when they were canceled in response to a remote input interaction. This allows us to show
     * what you replied and allows you to continue typing into it.
     */
    protected ArraySet<String> mKeysKeptForRemoteInput = new ArraySet<>();

    // mScreenOnFromKeyguard && mVisible.
    private boolean mVisibleToUser;

    private Locale mLocale;

    protected boolean mUseHeadsUp = false;
    protected boolean mHeadsUpTicker = false;
    protected boolean mDisableNotificationAlerts = false;

    protected DevicePolicyManager mDevicePolicyManager;
    protected PowerManager mPowerManager;
    protected StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    // public mode, private notifications, etc
    private final SparseBooleanArray mLockscreenPublicMode = new SparseBooleanArray();
    private final SparseBooleanArray mUsersAllowingPrivateNotifications = new SparseBooleanArray();
    private final SparseBooleanArray mUsersAllowingNotifications = new SparseBooleanArray();

    private UserManager mUserManager;

    protected KeyguardManager mKeyguardManager;
    private LockPatternUtils mLockPatternUtils;
    private DeviceProvisionedController mDeviceProvisionedController
            = Dependency.get(DeviceProvisionedController.class);
    protected SystemServicesProxy mSystemServicesProxy;

    // UI-specific methods

    protected WindowManager mWindowManager;
    protected IWindowManager mWindowManagerService;

    protected Display mDisplay;

    protected RecentsComponent mRecents;

    protected int mZenMode;

    // which notification is currently being longpress-examined by the user
    private NotificationGuts mNotificationGutsExposed;
    private MenuItem mGutsMenuItem;

    private KeyboardShortcuts mKeyboardShortcuts;

    protected NotificationShelf mNotificationShelf;
    protected DismissView mDismissView;
    protected EmptyShadeView mEmptyShadeView;

    private NotificationClicker mNotificationClicker = new NotificationClicker();

    protected AssistManager mAssistManager;

    protected boolean mVrMode;

    private Set<String> mNonBlockablePkgs;

    public boolean isDeviceInteractive() {
        return mDeviceInteractive;
    }

    @Override  // NotificationData.Environment
    public boolean isDeviceProvisioned() {
        return mDeviceProvisionedController.isDeviceProvisioned();
    }

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            mVrMode = enabled;
        }
    };

    public boolean isDeviceInVrMode() {
        return mVrMode;
    }

    private final DeviceProvisionedListener mDeviceProvisionedListener =
            new DeviceProvisionedListener() {
        @Override
        public void onDeviceProvisionedChanged() {
            updateNotifications();
        }
    };

    protected final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            final int mode = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
            setZenMode(mode);

            updateLockscreenNotificationSetting();
        }
    };

    private final ContentObserver mLockscreenSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            // We don't know which user changed LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS or
            // LOCK_SCREEN_SHOW_NOTIFICATIONS, so we just dump our cache ...
            mUsersAllowingPrivateNotifications.clear();
            mUsersAllowingNotifications.clear();
            // ... and refresh all the notifications
            updateLockscreenNotificationSetting();
            updateNotifications();
        }
    };

    private ColtSettingsObserver mColtSettingsObserver = new ColtSettingsObserver(mHandler);

    private class ColtSettingsObserver extends ContentObserver {
        ColtSettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LAST_DOZE_AUTO_BRIGHTNESS),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.DOUBLE_TAP_SLEEP_NAVBAR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.DOUBLE_TAP_SLEEP_LOCKSCREEN),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.DOUBLE_TAP_SLEEP_GESTURE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_BRIGHTNESS_CONTROL),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.BATTERY_SAVER_MODE_COLOR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_MEDIA_METADATA),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.QS_ROWS_PORTRAIT),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.QS_ROWS_LANDSCAPE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.QS_COLUMNS_PORTRAIT),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.QS_COLUMNS_LANDSCAPE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_STOPLIST_VALUES), false, this);
            update();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.System.getUriFor(
                    Settings.System.LAST_DOZE_AUTO_BRIGHTNESS))) {
                updateDozeBrightness();
            } else  if (uri.equals(Settings.System.getUriFor(
                    Settings.System.DOUBLE_TAP_SLEEP_NAVBAR))) {
                    setDoubleTapNavbar();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.DOUBLE_TAP_SLEEP_LOCKSCREEN))) {
                setLockscreenDoubleTapToSleep();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.DOUBLE_TAP_SLEEP_GESTURE))) {
                setLockscreenDoubleTapToSleep();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_BRIGHTNESS_CONTROL))
                    || uri.equals(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS_MODE))) {
                setBrightnessSlider();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.BATTERY_SAVER_MODE_COLOR))) {
                    mBatterySaverWarningColor = Settings.System.getIntForUser(
                            mContext.getContentResolver(),
                            Settings.System.BATTERY_SAVER_MODE_COLOR, 0,
                            UserHandle.USER_CURRENT);
                    if (mBatterySaverWarningColor != 0) {
                        mBatterySaverWarningColor = Utils.getColorAttr(mContext, android.R.attr.colorError);
                    }
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_MEDIA_METADATA))) {
                setLockscreenMediaMetadata();
            } else if (uri.equals(Settings.System.getUriFor(Settings.System.QS_ROWS_PORTRAIT)) ||
                    uri.equals(Settings.System.getUriFor(Settings.System.QS_ROWS_LANDSCAPE)) ||
                    uri.equals(Settings.System.getUriFor(Settings.System.QS_COLUMNS_PORTRAIT)) ||
                    uri.equals(Settings.System.getUriFor(Settings.System.QS_COLUMNS_LANDSCAPE))) {
                setQsRowsColumns();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_STOPLIST_VALUES))) {
                final String stopString = Settings.System.getString(mContext.getContentResolver(),
                        Settings.System.HEADS_UP_STOPLIST_VALUES);
                splitAndAddToArrayList(mStoplist, stopString, "\\|");
            }
        }

        public void update() {
            updateDozeBrightness();
	    setDoubleTapNavbar();
            setLockscreenDoubleTapToSleep();
            setBrightnessSlider();
            setLockscreenMediaMetadata();
            setQsRowsColumns();
            setHeadsUpStoplist();
        }
    }

    private void updateDozeBrightness() {
        int defaultDozeBrightness = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessDoze);
        int lastValue = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LAST_DOZE_AUTO_BRIGHTNESS, defaultDozeBrightness,
                UserHandle.USER_CURRENT);
        mStatusBarWindowManager.updateDozeBrightness(lastValue);
    }

    private void setDoubleTapNavbar() {
        if (mNavigationBar != null) {
            mNavigationBar.setDoubleTapToSleep();
       }
    }

    private void setLockscreenDoubleTapToSleep() {
        if (mStatusBarWindow != null) {
            mStatusBarWindow.setLockscreenDoubleTapToSleep();
        }
    }

    private void setBrightnessSlider() {
        int mode = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                        UserHandle.USER_CURRENT);
        mAutomaticBrightness = mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
        mBrightnessControl = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.STATUS_BAR_BRIGHTNESS_CONTROL, 0,
                UserHandle.USER_CURRENT) == 1;
    }

    private void setLockscreenMediaMetadata() {
        mLockscreenMediaMetadata = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_MEDIA_METADATA, 0, UserHandle.USER_CURRENT) == 1;
    }

    private void setQsRowsColumns() {
        if (mQSPanel != null) {
            mQSPanel.updateResources();
        }
    }

    private void setHeadsUpStoplist() {
        final String stopString = Settings.System.getString(mContext.getContentResolver(),
                    Settings.System.HEADS_UP_STOPLIST_VALUES);
        splitAndAddToArrayList(mStoplist, stopString, "\\|");
    }

    private RemoteViews.OnClickHandler mOnClickHandler = new RemoteViews.OnClickHandler() {

        @Override
        public boolean onClickHandler(
                final View view, final PendingIntent pendingIntent, final Intent fillInIntent) {
            wakeUpIfDozing(SystemClock.uptimeMillis(), view);


            if (handleRemoteInput(view, pendingIntent, fillInIntent)) {
                return true;
            }

            if (DEBUG) {
                Log.v(TAG, "Notification click handler invoked for intent: " + pendingIntent);
            }
            logActionClick(view);
            // The intent we are sending is for the application, which
            // won't have permission to immediately start an activity after
            // the user switches to home.  We know it is safe to do at this
            // point, so make sure new activity switches are now allowed.
            try {
                ActivityManager.getService().resumeAppSwitches();
            } catch (RemoteException e) {
            }
            final boolean isActivity = pendingIntent.isActivity();
            if (isActivity) {
                final boolean keyguardShowing = mStatusBarKeyguardViewManager.isShowing();
                final boolean afterKeyguardGone = PreviewInflater.wouldLaunchResolverActivity(
                        mContext, pendingIntent.getIntent(), mCurrentUserId);
                dismissKeyguardThenExecute(new OnDismissAction() {
                    @Override
                    public boolean onDismiss() {
                        try {
                            ActivityManager.getService().resumeAppSwitches();
                        } catch (RemoteException e) {
                        }

                        boolean handled = superOnClickHandler(view, pendingIntent, fillInIntent);

                        // close the shade if it was open
                        if (handled && !mNotificationPanel.isFullyCollapsed()) {
                            animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL,
                                    true /* force */);
                            visibilityChanged(false);
                            mAssistManager.hideAssist();

                            // Wait for activity start.
                            return true;
                        } else {
                            return false;
                        }

                    }
                }, afterKeyguardGone);
                return true;
            } else {
                return superOnClickHandler(view, pendingIntent, fillInIntent);
            }
        }

        private void logActionClick(View view) {
            ViewParent parent = view.getParent();
            String key = getNotificationKeyForParent(parent);
            if (key == null) {
                Log.w(TAG, "Couldn't determine notification for click.");
                return;
            }
            int index = -1;
            // If this is a default template, determine the index of the button.
            if (view.getId() == com.android.internal.R.id.action0 &&
                    parent != null && parent instanceof ViewGroup) {
                ViewGroup actionGroup = (ViewGroup) parent;
                index = actionGroup.indexOfChild(view);
            }
            try {
                mBarService.onNotificationActionClick(key, index);
            } catch (RemoteException e) {
                // Ignore
            }
        }

        private String getNotificationKeyForParent(ViewParent parent) {
            while (parent != null) {
                if (parent instanceof ExpandableNotificationRow) {
                    return ((ExpandableNotificationRow) parent).getStatusBarNotification().getKey();
                }
                parent = parent.getParent();
            }
            return null;
        }

        private boolean superOnClickHandler(View view, PendingIntent pendingIntent,
                Intent fillInIntent) {
            return super.onClickHandler(view, pendingIntent, fillInIntent,
                    StackId.FULLSCREEN_WORKSPACE_STACK_ID);
        }
    }

    public boolean interceptMediaKey(KeyEvent event) {
        return mState == StatusBarState.KEYGUARD
                && mStatusBarKeyguardViewManager.interceptMediaKey(event);
    }

    /**
     * While IME is active and a BACK event is detected, check with
     * {@link StatusBarKeyguardViewManager#dispatchBackKeyEventPreIme()} to see if the event
     * should be handled before routing to IME, in order to prevent the user having to hit back
     * twice to exit bouncer.
     */
    public boolean dispatchKeyEventPreIme(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (mState == StatusBarState.KEYGUARD
                        && mStatusBarKeyguardViewManager.dispatchBackKeyEventPreIme()) {
                    return onBackPressed();
                }
        }
        return false;
    }

    protected boolean shouldUnlockOnMenuPressed() {
        return mDeviceInteractive && mState != StatusBarState.SHADE
            && mStatusBarKeyguardViewManager.shouldDismissOnMenuPressed();
    }

    public boolean onMenuPressed() {
        if (shouldUnlockOnMenuPressed()) {
            mShadeController.animateCollapsePanels(
                    CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL /* flags */, true /* force */);
            return true;
        }
        return false;
    }

    public void endAffordanceLaunch() {
        releaseGestureWakeLock();
        mNotificationPanelViewController.onAffordanceLaunchEnded();
    }

    public boolean onBackPressed() {
        boolean isScrimmedBouncer = mScrimController.getState() == ScrimState.BOUNCER_SCRIMMED;
        if (mStatusBarKeyguardViewManager.onBackPressed(isScrimmedBouncer /* hideImmediately */)) {
            if (isScrimmedBouncer) {
                mStatusBarStateController.setLeaveOpenOnKeyguardHide(false);
            } else {
                mNotificationPanelViewController.expandWithoutQs();
            }
            return true;
        }
        if (mNotificationPanelViewController.isQsCustomizing()) {
            mNotificationPanelViewController.closeQsCustomizer();
            return true;
        }
        if (mNotificationPanelViewController.isQsExpanded()) {
            if (mNotificationPanelViewController.isQsDetailShowing()) {
                mNotificationPanelViewController.closeQsDetail();
            } else {
                mNotificationPanelViewController.animateCloseQs(false /* animateAway */);
            }
            return true;
        }
        if (mNotificationPanelViewController.closeUserSwitcherIfOpen()) {
            return true;
        }
        if (mState != StatusBarState.KEYGUARD && mState != StatusBarState.SHADE_LOCKED) {
            if (mNotificationPanelViewController.canPanelBeCollapsed()) {
                mShadeController.animateCollapsePanels();
            }
            return true;
        }
        return false;
    }

    public boolean onSpacePressed() {
        if (mDeviceInteractive && mState != StatusBarState.SHADE) {
            mShadeController.animateCollapsePanels(
                    CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL /* flags */, true /* force */);
            return true;
        }
        return false;
    }

    private void showBouncerOrLockScreenIfKeyguard() {
        if (!mKeyguardViewMediator.isHiding()) {
            if (mState == StatusBarState.SHADE_LOCKED
                    && mKeyguardUpdateMonitor.isUdfpsEnrolled()) {
                // shade is showing while locked on the keyguard, so go back to showing the
                // lock screen where users can use the UDFPS affordance to enter the device
                mStatusBarKeyguardViewManager.reset(true);
            } else if ((mState == StatusBarState.KEYGUARD
                    && !mStatusBarKeyguardViewManager.bouncerIsOrWillBeShowing())
                    || mState == StatusBarState.SHADE_LOCKED) {
                mStatusBarKeyguardViewManager.showGenericBouncer(true /* scrimmed */);
            }
        }
    }

    /**
     * Show the bouncer if we're currently on the keyguard or shade locked and aren't hiding.
     * @param performAction the action to perform when the bouncer is dismissed.
     * @param cancelAction the action to perform when unlock is aborted.
     */
    public void showBouncerWithDimissAndCancelIfKeyguard(OnDismissAction performAction,
            Runnable cancelAction) {
        if ((mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED)
                && !mKeyguardViewMediator.isHiding()) {
            mStatusBarKeyguardViewManager.dismissWithAction(performAction, cancelAction,
                    false /* afterKeyguardGone */);
        } else if (cancelAction != null) {
            cancelAction.run();
        }
    }

    void instantCollapseNotificationPanel() {
        mNotificationPanelViewController.instantCollapse();
        mShadeController.runPostCollapseRunnables();
    }

    /**
     * Collapse the panel directly if we are on the main thread, post the collapsing on the main
     * thread if we are not.
     */
    void collapsePanelOnMainThread() {
        if (Looper.getMainLooper().isCurrentThread()) {
            mShadeController.collapsePanel();
        } else {
            mContext.getMainExecutor().execute(mShadeController::collapsePanel);
        }
    }

    /** Collapse the panel. The collapsing will be animated for the given {@code duration}. */
    void collapsePanelWithDuration(int duration) {
        mNotificationPanelViewController.collapseWithDuration(duration);
    }

    /**
     * Updates the light reveal effect to reflect the reason we're waking or sleeping (for example,
     * from the power button).
     * @param wakingUp Whether we're updating because we're waking up (true) or going to sleep
     *                 (false).
     */
    private void updateRevealEffect(boolean wakingUp) {
        if (mLightRevealScrim == null) {
            return;
        }

        final boolean wakingUpFromPowerButton = wakingUp
                && !(mLightRevealScrim.getRevealEffect() instanceof CircleReveal)
                && mWakefulnessLifecycle.getLastWakeReason()
                == PowerManager.WAKE_REASON_POWER_BUTTON;
        final boolean sleepingFromPowerButton = !wakingUp
                && mWakefulnessLifecycle.getLastSleepReason()
                == PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON;

        if (wakingUpFromPowerButton || sleepingFromPowerButton) {
            mLightRevealScrim.setRevealEffect(mPowerButtonReveal);
            mLightRevealScrim.setRevealAmount(1f - mStatusBarStateController.getDozeAmount());
        } else if (!wakingUp || !(mLightRevealScrim.getRevealEffect() instanceof CircleReveal)) {
            // If we're going to sleep, but it's not from the power button, use the default reveal.
            // If we're waking up, only use the default reveal if the biometric controller didn't
            // already set it to the circular reveal because we're waking up from a fingerprint/face
            // auth.
            mLightRevealScrim.setRevealEffect(LiftReveal.INSTANCE);
            mLightRevealScrim.setRevealAmount(1f - mStatusBarStateController.getDozeAmount());
        }
    }

    public LightRevealScrim getLightRevealScrim() {
        return mLightRevealScrim;
    }

    private void updateKeyguardState() {
        mKeyguardStateController.notifyKeyguardState(mStatusBarKeyguardViewManager.isShowing(),
                mStatusBarKeyguardViewManager.isOccluded());
    }

    public void onTrackingStarted() {
        mShadeController.runPostCollapseRunnables();
    }

    public void onClosingFinished() {
        mShadeController.runPostCollapseRunnables();
        if (!mPresenter.isPresenterFullyCollapsed()) {
            // if we set it not to be focusable when collapsing, we have to undo it when we aborted
            // the closing
            mNotificationShadeWindowController.setNotificationShadeFocusable(true);
        }
    }

    public void onUnlockHintStarted() {
        mFalsingCollector.onUnlockHintStarted();
        mKeyguardIndicationController.showActionToUnlock();
    }

    public void onHintFinished() {
        // Delay the reset a bit so the user can read the text.
        mKeyguardIndicationController.hideTransientIndicationDelayed(HINT_RESET_DELAY_MS);
    }

    public void onCameraHintStarted() {
        mFalsingCollector.onCameraHintStarted();
        mKeyguardIndicationController.showTransientIndication(R.string.camera_hint);
    }

    public void onVoiceAssistHintStarted() {
        mFalsingCollector.onLeftAffordanceHintStarted();
        mKeyguardIndicationController.showTransientIndication(R.string.voice_hint);
    }

    public void onPhoneHintStarted() {
        mFalsingCollector.onLeftAffordanceHintStarted();
        mKeyguardIndicationController.showTransientIndication(R.string.phone_hint);
    }

    public void onTrackingStopped(boolean expand) {
        if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
            if (!expand && !mKeyguardStateController.canDismissLockScreen()) {
                mStatusBarKeyguardViewManager.showBouncer(false /* scrimmed */);
            }
        }
    }

    // TODO: Figure out way to remove these.
    public NavigationBarView getNavigationBarView() {
        return mNavigationBarController.getNavigationBarView(mDisplayId);
    }

    public void showPinningEnterExitToast(boolean entering) {
        mNavigationBarController.showPinningEnterExitToast(mDisplayId, entering);
    }

    public void showPinningEscapeToast() {
        mNavigationBarController.showPinningEscapeToast(mDisplayId);
    }

    /**
     * TODO: Remove this method. Views should not be passed forward. Will cause theme issues.
     * @return bottom area view
     */
    public KeyguardBottomAreaView getKeyguardBottomAreaView() {
        return mNotificationPanelViewController.getKeyguardBottomAreaView();
    }

    /**
     * Propagation of the bouncer state, indicating that it's fully visible.
     */
    public void setBouncerShowing(boolean bouncerShowing) {
        mBouncerShowing = bouncerShowing;
        mKeyguardBypassController.setBouncerShowing(bouncerShowing);
        mPulseExpansionHandler.setBouncerShowing(bouncerShowing);
        setBouncerShowingForStatusBarComponents(bouncerShowing);
        mStatusBarHideIconsForBouncerManager.setBouncerShowingAndTriggerUpdate(bouncerShowing);
        mCommandQueue.recomputeDisableFlags(mDisplayId, true /* animate */);
        updateScrimController();
        if (!mBouncerShowing) {
            updatePanelExpansionForKeyguard();
        }
    }

    /**
     * Propagate the bouncer state to status bar components.
     *
     * Separate from {@link #setBouncerShowing} because we sometimes re-create the status bar and
     * should update only the status bar components.
     */
    private void setBouncerShowingForStatusBarComponents(boolean bouncerShowing) {
        int importance = bouncerShowing
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        if (mPhoneStatusBarViewController != null) {
            mPhoneStatusBarViewController.setImportantForAccessibility(importance);
        }
        mNotificationPanelViewController.setImportantForAccessibility(importance);
        mNotificationPanelViewController.setBouncerShowing(bouncerShowing);
    }

    /**
     * Collapses the notification shade if it is tracking or expanded.
     */
    public void collapseShade() {
        if (mNotificationPanelViewController.isTracking()) {
            mNotificationShadeWindowViewController.cancelCurrentTouch();
        }
        if (mPanelExpanded && mState == StatusBarState.SHADE) {
            mShadeController.animateCollapsePanels();
        }
    }

    @VisibleForTesting
    final WakefulnessLifecycle.Observer mWakefulnessObserver = new WakefulnessLifecycle.Observer() {
        @Override
        public void onFinishedGoingToSleep() {
            mNotificationPanelViewController.onAffordanceLaunchEnded();
            releaseGestureWakeLock();
            mLaunchCameraWhenFinishedWaking = false;
            mDeviceInteractive = false;
            mWakeUpComingFromTouch = false;
            mWakeUpTouchLocation = null;
            updateVisibleToUser();

            updateNotificationPanelTouchState();
            mNotificationShadeWindowViewController.cancelCurrentTouch();
            if (mLaunchCameraOnFinishedGoingToSleep) {
                mLaunchCameraOnFinishedGoingToSleep = false;

                // This gets executed before we will show Keyguard, so post it in order that the state
                // is correct.
                mMainExecutor.execute(() -> mCommandQueueCallbacks.onCameraLaunchGestureDetected(
                        mLastCameraLaunchSource));
            }

            if (mLaunchEmergencyActionOnFinishedGoingToSleep) {
                mLaunchEmergencyActionOnFinishedGoingToSleep = false;

                // This gets executed before we will show Keyguard, so post it in order that the
                // state is correct.
                mMainExecutor.execute(
                        () -> mCommandQueueCallbacks.onEmergencyActionLaunchGestureDetected());
            }
            updateIsKeyguard();
        }

        @Override
        public void onStartedGoingToSleep() {
            String tag = "StatusBar#onStartedGoingToSleep";
            DejankUtils.startDetectingBlockingIpcs(tag);
            updateRevealEffect(false /* wakingUp */);
            updateNotificationPanelTouchState();
            maybeEscalateHeadsUp();
            dismissVolumeDialog();
            mWakeUpCoordinator.setFullyAwake(false);
            mBypassHeadsUpNotifier.setFullyAwake(false);
            mKeyguardBypassController.onStartedGoingToSleep();

            // The screen off animation uses our LightRevealScrim - we need to be expanded for it to
            // be visible.
            if (mDozeParameters.shouldControlUnlockedScreenOff()) {
                makeExpandedVisible(true);
            }

            DejankUtils.stopDetectingBlockingIpcs(tag);
            if (Settings.System.getIntForUser(mContext.getContentResolver(),
                                              Settings.System.ARCANE_IDLE_MANAGER, 1,
                                              mLockscreenUserManager.getCurrentUserId()) == 1) {
                if (!isIdleManagerIstantiated) {
                    ArcaneIdleManager.initManager(mContext);
                    isIdleManagerIstantiated = true;
                    ArcaneIdleManager.executeManager();
                } else {
                    ArcaneIdleManager.executeManager();
                }
            }
        }

        @Override
        public void onStartedWakingUp() {
            String tag = "StatusBar#onStartedWakingUp";
            DejankUtils.startDetectingBlockingIpcs(tag);
            mNotificationShadeWindowController.batchApplyWindowLayoutParams(()-> {
                mDeviceInteractive = true;
                mWakeUpCoordinator.setWakingUp(true);
                if (!mKeyguardBypassController.getBypassEnabled()) {
                    mHeadsUpManager.releaseAllImmediately();
                }
                updateVisibleToUser();
                updateIsKeyguard();
                mDozeServiceHost.stopDozing();
                // This is intentionally below the stopDozing call above, since it avoids that we're
                // unnecessarily animating the wakeUp transition. Animations should only be enabled
                // once we fully woke up.
                updateRevealEffect(true /* wakingUp */);
                updateNotificationPanelTouchState();

                // If we are waking up during the screen off animation, we should undo making the
                // expanded visible (we did that so the LightRevealScrim would be visible).
                if (mUnlockedScreenOffAnimationController
                        .isScreenOffLightRevealAnimationPlaying()) {
                    makeExpandedInvisible();
                }

            });
            DejankUtils.stopDetectingBlockingIpcs(tag);
            if (Settings.System.getIntForUser(mContext.getContentResolver(),
                                              Settings.System.ARCANE_IDLE_MANAGER, 1,
                                              mLockscreenUserManager.getCurrentUserId()) == 1) {
                ArcaneIdleManager.haltManager();
            }
        }

        @Override
        public void onFinishedWakingUp() {
            mWakeUpCoordinator.setFullyAwake(true);
            mBypassHeadsUpNotifier.setFullyAwake(true);
            mWakeUpCoordinator.setWakingUp(false);
            if (isOccluded() && !mDozeParameters.canControlUnlockedScreenOff()) {
                // When the keyguard is occluded we don't use the KEYGUARD state which would
                // normally cause these redaction updates.  If AOD is on, the KEYGUARD state is used
                // to show the doze, AND UnlockedScreenOffAnimationController.onFinishedWakingUp()
                // would force a KEYGUARD state that would take care of recalculating redaction.
                // So if AOD is off or unsupported we need to trigger these updates at screen on
                // when the keyguard is occluded.
                mLockscreenUserManager.updatePublicMode();
                mNotificationPanelViewController.getNotificationStackScrollLayoutController()
                        .updateSensitivenessForOccludedWakeup();
            }
            if (mLaunchCameraWhenFinishedWaking) {
                mNotificationPanelViewController.launchCamera(
                        false /* animate */, mLastCameraLaunchSource);
                mLaunchCameraWhenFinishedWaking = false;
            }
            if (mLaunchEmergencyActionWhenFinishedWaking) {
                mLaunchEmergencyActionWhenFinishedWaking = false;
                Intent emergencyIntent = getEmergencyActionIntent();
                if (emergencyIntent != null) {
                    mContext.startActivityAsUser(emergencyIntent, UserHandle.CURRENT);
                }
            }
            updateScrimController();
        }
    };

    /**
     * We need to disable touch events because these might
     * collapse the panel after we expanded it, and thus we would end up with a blank
     * Keyguard.
     */
    void updateNotificationPanelTouchState() {
        boolean goingToSleepWithoutAnimation = isGoingToSleep()
                && !mDozeParameters.shouldControlScreenOff();
        boolean disabled = (!mDeviceInteractive && !mDozeServiceHost.isPulsing())
                || goingToSleepWithoutAnimation;
        mNotificationPanelViewController.setTouchAndAnimationDisabled(disabled);
        mNotificationIconAreaController.setAnimationsEnabled(!disabled);
    }

    final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurningOn() {
            mFalsingCollector.onScreenTurningOn();
            mNotificationPanelViewController.onScreenTurningOn();
        }

        @Override
        public void onScreenTurnedOn() {
            mScrimController.onScreenTurnedOn();
            mVisualizerView.setVisible(true);
        }

        @Override
        public void onScreenTurnedOff() {
            mFalsingCollector.onScreenOff();
            mScrimController.onScreenTurnedOff();
            mVisualizerView.setVisible(false);
            updateIsKeyguard();
        }
    };

    public int getWakefulnessState() {
        return mWakefulnessLifecycle.getWakefulness();
    }

    /**
     * @return true if the screen is currently fully off, i.e. has finished turning off and has
     * since not started turning on.
     */
    public boolean isScreenFullyOff() {
        return mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_OFF;
    }

    public void showScreenPinningRequest(int taskId, boolean allowCancel) {
        mScreenPinningRequest.showPrompt(taskId, allowCancel);
    }

    @Nullable Intent getEmergencyActionIntent() {
        Intent emergencyIntent = new Intent(EmergencyGesture.ACTION_LAUNCH_EMERGENCY);
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> emergencyActivities = pm.queryIntentActivities(emergencyIntent,
                PackageManager.MATCH_SYSTEM_ONLY);
        ResolveInfo resolveInfo = getTopEmergencySosInfo(emergencyActivities);
        if (resolveInfo == null) {
            Log.wtf(TAG, "Couldn't find an app to process the emergency intent.");
            return null;
        }
        emergencyIntent.setComponent(new ComponentName(resolveInfo.activityInfo.packageName,
                resolveInfo.activityInfo.name));
        emergencyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return emergencyIntent;
    }

    /**
     * Select and return the "best" ResolveInfo for Emergency SOS Activity.
     */
    private @Nullable ResolveInfo getTopEmergencySosInfo(List<ResolveInfo> emergencyActivities) {
        // No matched activity.
        if (emergencyActivities == null || emergencyActivities.isEmpty()) {
            return null;
        }

        // Of multiple matched Activities, give preference to the pre-set package name.
        String preferredAppPackageName =
                mContext.getString(R.string.config_preferredEmergencySosPackage);

        // If there is no preferred app, then return first match.
        if (TextUtils.isEmpty(preferredAppPackageName)) {
            return emergencyActivities.get(0);
        }

        for (ResolveInfo emergencyInfo: emergencyActivities) {
            // If activity is from the preferred app, use it.
            if (TextUtils.equals(emergencyInfo.activityInfo.packageName, preferredAppPackageName)) {
                return emergencyInfo;
            }
        }
        // No matching activity: return first match
        return emergencyActivities.get(0);
    }

    boolean isCameraAllowedByAdmin() {
        if (mDevicePolicyManager.getCameraDisabled(null,
                mLockscreenUserManager.getCurrentUserId())) {
            return false;
        } else if (mStatusBarKeyguardViewManager == null
                || (isKeyguardShowing() && isKeyguardSecure())) {
            // Check if the admin has disabled the camera specifically for the keyguard
            return (mDevicePolicyManager.getKeyguardDisabledFeatures(null,
                    mLockscreenUserManager.getCurrentUserId())
                    & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) == 0;
        }
        return true;
    }

    boolean isGoingToSleep() {
        return mWakefulnessLifecycle.getWakefulness()
                == WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP;
    }

    public void notifyBiometricAuthModeChanged() {
        mDozeServiceHost.updateDozing();
        updateScrimController();
    }

    @VisibleForTesting
    public void updateScrimController() {
        Trace.beginSection("StatusBar#updateScrimController");

        // We don't want to end up in KEYGUARD state when we're unlocking with
        // fingerprint from doze. We should cross fade directly from black.
        boolean unlocking = mBiometricUnlockController.isWakeAndUnlock()
                || mKeyguardStateController.isKeyguardFadingAway();

        // Do not animate the scrim expansion when triggered by the fingerprint sensor.
        boolean onKeyguardOrHidingIt = mKeyguardStateController.isShowing()
                || mKeyguardStateController.isKeyguardFadingAway()
                || mKeyguardStateController.isKeyguardGoingAway();
        mScrimController.setExpansionAffectsAlpha(!(mBiometricUnlockController.isBiometricUnlock()
                        && onKeyguardOrHidingIt));

        boolean launchingAffordanceWithPreview =
                mNotificationPanelViewController.isLaunchingAffordanceWithPreview();
        mScrimController.setLaunchingAffordanceWithPreview(launchingAffordanceWithPreview);

        if (mStatusBarKeyguardViewManager.isShowingAlternateAuth()) {
            if (mState == StatusBarState.SHADE || mState == StatusBarState.SHADE_LOCKED) {
                mScrimController.transitionTo(ScrimState.AUTH_SCRIMMED_SHADE);
            } else {
                mScrimController.transitionTo(ScrimState.AUTH_SCRIMMED);
            }
        } else if (mBouncerShowing) {
            // Bouncer needs the front scrim when it's on top of an activity,
            // tapping on a notification, editing QS or being dismissed by
            // FLAG_DISMISS_KEYGUARD_ACTIVITY.
            ScrimState state = mStatusBarKeyguardViewManager.bouncerNeedsScrimming()
                    ? ScrimState.BOUNCER_SCRIMMED : ScrimState.BOUNCER;
            mScrimController.transitionTo(state);
        } else if (launchingAffordanceWithPreview) {
            // We want to avoid animating when launching with a preview.
            mScrimController.transitionTo(ScrimState.UNLOCKED, mUnlockScrimCallback);
        } else if (mBrightnessMirrorVisible) {
            mScrimController.transitionTo(ScrimState.BRIGHTNESS_MIRROR);
        } else if (mState == StatusBarState.SHADE_LOCKED) {
            mScrimController.transitionTo(ScrimState.SHADE_LOCKED);
        } else if (mDozeServiceHost.isPulsing()) {
            mScrimController.transitionTo(ScrimState.PULSING,
                    mDozeScrimController.getScrimCallback());
        } else if (mDozeServiceHost.hasPendingScreenOffCallback()) {
            mScrimController.transitionTo(ScrimState.OFF, new ScrimController.Callback() {
                @Override
                public void onFinished() {
                    mDozeServiceHost.executePendingScreenOffCallback();
                }
            });
        } else if (mDozing && !unlocking) {
            mScrimController.transitionTo(ScrimState.AOD);
        } else if (mIsKeyguard && !unlocking) {
            mScrimController.transitionTo(ScrimState.KEYGUARD);
        } else {
            mScrimController.transitionTo(ScrimState.UNLOCKED, mUnlockScrimCallback);
        }
        updateLightRevealScrimVisibility();

        Trace.endSection();
    }

    public boolean isKeyguardShowing() {
        if (mStatusBarKeyguardViewManager == null) {
            Slog.i(TAG, "isKeyguardShowing() called before startKeyguard(), returning true");
            return true;
        }
        return mStatusBarKeyguardViewManager.isShowing();
    }

    public boolean shouldIgnoreTouch() {
        return (mStatusBarStateController.isDozing()
                && mDozeServiceHost.getIgnoreTouchWhilePulsing())
                || mUnlockedScreenOffAnimationController.isScreenOffAnimationPlaying();
    }

    // Begin Extra BaseStatusBar methods.

    protected final CommandQueue mCommandQueue;
    protected IStatusBarService mBarService;

    // all notifications
    protected NotificationStackScrollLayout mStackScroller;

    // handling reordering
    private final VisualStabilityManager mVisualStabilityManager;

    protected AccessibilityManager mAccessibilityManager;

    protected boolean mDeviceInteractive;

    protected boolean mVisible;

    // mScreenOnFromKeyguard && mVisible.
    private boolean mVisibleToUser;

    protected DevicePolicyManager mDevicePolicyManager;
    private final PowerManager mPowerManager;
    protected StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    protected KeyguardManager mKeyguardManager;
    private final DeviceProvisionedController mDeviceProvisionedController;

    private final NavigationBarController mNavigationBarController;
    private final AccessibilityFloatingMenuController mAccessibilityFloatingMenuController;
    private boolean mNeedsNavigationBar;

    // UI-specific methods

    protected WindowManager mWindowManager;
    protected IWindowManager mWindowManagerService;
    private IDreamManager mDreamManager;

    protected Display mDisplay;
    private int mDisplayId;

    protected NotificationShelfController mNotificationShelfController;

    private final Lazy<AssistManager> mAssistManagerLazy;

    public boolean isDeviceInteractive() {
        return mDeviceInteractive;
    }

    private EpicSettingsObserver mEpicSettingsObserver = new EpicSettingsObserver(mMainHandler);
    private class EpicSettingsObserver extends ContentObserver {
        EpicSettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_STOPLIST_VALUES), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_BLACKLIST_VALUES), false, this);
        }

        public void update() {
            setHeadsUpStoplist();
            setHeadsUpBlacklist();
        }
    }


    private void setHeadsUpStoplist() {
        if (mNotificationInterruptStateProvider != null)
            mNotificationInterruptStateProvider.setHeadsUpStoplist();
    }

    private void setHeadsUpBlacklist() {
        if (mNotificationInterruptStateProvider != null)
            mNotificationInterruptStateProvider.setHeadsUpBlacklist();
    }

    private final BroadcastReceiver mBannerActionBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BANNER_ACTION_CANCEL.equals(action) || BANNER_ACTION_SETUP.equals(action)) {
                NotificationManager noMan = (NotificationManager)
                        mContext.getSystemService(Context.NOTIFICATION_SERVICE);
                noMan.cancel(com.android.internal.messages.nano.SystemMessageProto.SystemMessage.
                        NOTE_HIDDEN_NOTIFICATIONS);

                Settings.Secure.putInt(mContext.getContentResolver(),
                        Settings.Secure.SHOW_NOTE_ABOUT_NOTIFICATION_HIDING, 0);
                if (BANNER_ACTION_SETUP.equals(action)) {
                    mShadeController.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL,
                            true /* force */);
                    mContext.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_REDACTION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    );
                }
            }
        }
    };

    public void setNotificationSnoozed(StatusBarNotification sbn, SnoozeOption snoozeOption) {
        mNotificationsController.setNotificationSnoozed(sbn, snoozeOption);
    }


    public void awakenDreams() {
        mUiBgExecutor.execute(() -> {
            try {
                mDreamManager.awaken();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        });
    }

    protected void toggleKeyboardShortcuts(int deviceId) {
        KeyboardShortcuts.toggle(mContext, deviceId);
    }

    protected void dismissKeyboardShortcuts() {
        KeyboardShortcuts.dismiss();
    }

    /**
     * Dismiss the keyguard then execute an action.
     *
     * @param action The action to execute after dismissing the keyguard.
     * @param collapsePanel Whether we should collapse the panel after dismissing the keyguard.
     * @param willAnimateOnKeyguard Whether {@param action} will run an animation on the keyguard if
     *                              we are locked.
     */
    private void executeActionDismissingKeyguard(Runnable action, boolean afterKeyguardGone,
            boolean collapsePanel, boolean willAnimateOnKeyguard) {
        if (!mDeviceProvisionedController.isDeviceProvisioned()) return;

        OnDismissAction onDismissAction = new OnDismissAction() {
            @Override
            public boolean onDismiss() {
                new Thread(() -> {
                    try {
                        // The intent we are sending is for the application, which
                        // won't have permission to immediately start an activity after
                        // the user switches to home.  We know it is safe to do at this
                        // point, so make sure new activity switches are now allowed.
                        ActivityManager.getService().resumeAppSwitches();
                    } catch (RemoteException e) {
                    }
                    action.run();
                }).start();

                return collapsePanel ? mShadeController.collapsePanel() : willAnimateOnKeyguard;
            }

            @Override
            public boolean willRunAnimationOnKeyguard() {
                return willAnimateOnKeyguard;
            }
        };
        dismissKeyguardThenExecute(onDismissAction, afterKeyguardGone);
    }

    @Override
    public void startPendingIntentDismissingKeyguard(final PendingIntent intent) {
        startPendingIntentDismissingKeyguard(intent, null);
    }

    @Override
    public void startPendingIntentDismissingKeyguard(
            final PendingIntent intent, @Nullable final Runnable intentSentUiThreadCallback) {
        startPendingIntentDismissingKeyguard(intent, intentSentUiThreadCallback,
                (ActivityLaunchAnimator.Controller) null);
    }

    @Override
    public void startPendingIntentDismissingKeyguard(PendingIntent intent,
            Runnable intentSentUiThreadCallback, View associatedView) {
        ActivityLaunchAnimator.Controller animationController = null;
        if (associatedView instanceof ExpandableNotificationRow) {
            animationController = mNotificationAnimationProvider.getAnimatorController(
                    ((ExpandableNotificationRow) associatedView));
        }

        startPendingIntentDismissingKeyguard(intent, intentSentUiThreadCallback,
                animationController);
    }

    @Override
    public void startPendingIntentDismissingKeyguard(
            final PendingIntent intent, @Nullable final Runnable intentSentUiThreadCallback,
            @Nullable ActivityLaunchAnimator.Controller animationController) {
        final boolean willLaunchResolverActivity = intent.isActivity()
                && mActivityIntentHelper.wouldLaunchResolverActivity(intent.getIntent(),
                mLockscreenUserManager.getCurrentUserId());

        boolean animate = !willLaunchResolverActivity
                && animationController != null
                && shouldAnimateLaunch(intent.isActivity());

        // If we animate, don't collapse the shade and defer the keyguard dismiss (in case we run
        // the animation on the keyguard). The animation will take care of (instantly) collapsing
        // the shade and hiding the keyguard once it is done.
        boolean collapse = !animate;
        executeActionDismissingKeyguard(() -> {
            try {
                // We wrap animationCallback with a StatusBarLaunchAnimatorController so that the
                // shade is collapsed after the animation (or when it is cancelled, aborted, etc).
                ActivityLaunchAnimator.Controller controller =
                        animationController != null ? new StatusBarLaunchAnimatorController(
                                animationController, this, intent.isActivity()) : null;

                mActivityLaunchAnimator.startPendingIntentWithAnimation(
                        controller, animate, intent.getCreatorPackage(),
                        (animationAdapter) -> intent.sendAndReturnResult(null, 0, null, null, null,
                                null, getActivityOptions(mDisplayId, animationAdapter)));
            } catch (PendingIntent.CanceledException e) {
                // the stack trace isn't very helpful here.
                // Just log the exception message.
                Log.w(TAG, "Sending intent failed: " + e);
                if (!collapse) {
                    // executeActionDismissingKeyguard did not collapse for us already.
                    collapsePanelOnMainThread();
                }
                // TODO: Dismiss Keyguard.
            }
            if (intent.isActivity()) {
                mAssistManagerLazy.get().hideAssist();
            }
            if (intentSentUiThreadCallback != null) {
                postOnUiThread(intentSentUiThreadCallback);
            }
        }, willLaunchResolverActivity, collapse, animate);
    }

    private void postOnUiThread(Runnable runnable) {
        mMainExecutor.execute(runnable);
    }

    /**
     * Returns an ActivityOptions bundle created using the given parameters.
     *
     * @param displayId The ID of the display to launch the activity in. Typically this would be the
     *                  display the status bar is on.
     * @param animationAdapter The animation adapter used to start this activity, or {@code null}
     *                         for the default animation.
     */
    public static Bundle getActivityOptions(int displayId,
            @Nullable RemoteAnimationAdapter animationAdapter) {
        ActivityOptions options = getDefaultActivityOptions(animationAdapter);
        options.setLaunchDisplayId(displayId);
        options.setCallerDisplayId(displayId);
        return options.toBundle();
    }

    /**
     * Returns an ActivityOptions bundle created using the given parameters.
     *
     * @param displayId The ID of the display to launch the activity in. Typically this would be the
     *                  display the status bar is on.
     * @param animationAdapter The animation adapter used to start this activity, or {@code null}
     *                         for the default animation.
     * @param isKeyguardShowing Whether keyguard is currently showing.
     * @param eventTime The event time in milliseconds since boot, not including sleep. See
     *                  {@link ActivityOptions#setSourceInfo}.
     */
    public static Bundle getActivityOptions(int displayId,
            @Nullable RemoteAnimationAdapter animationAdapter, boolean isKeyguardShowing,
            long eventTime) {
        ActivityOptions options = getDefaultActivityOptions(animationAdapter);
        options.setSourceInfo(isKeyguardShowing ? ActivityOptions.SourceInfo.TYPE_LOCKSCREEN
                : ActivityOptions.SourceInfo.TYPE_NOTIFICATION, eventTime);
        options.setLaunchDisplayId(displayId);
        options.setCallerDisplayId(displayId);
        return options.toBundle();
    }

    public static ActivityOptions getDefaultActivityOptions(
            @Nullable RemoteAnimationAdapter animationAdapter) {
        ActivityOptions options;
        if (animationAdapter != null) {
            options = ActivityOptions.makeRemoteAnimation(animationAdapter);
        } else {
            options = ActivityOptions.makeBasic();
        }
        return options;
    }

    void visibilityChanged(boolean visible) {
        if (mVisible != visible) {
            mVisible = visible;
            if (!visible) {
                mGutsManager.closeAndSaveGuts(true /* removeLeavebehind */, true /* force */,
                        true /* removeControls */, -1 /* x */, -1 /* y */, true /* resetMenu */);
            }
        }
        updateVisibleToUser();
    }

    protected void updateVisibleToUser() {
        boolean oldVisibleToUser = mVisibleToUser;
        mVisibleToUser = mVisible && mDeviceInteractive;

        if (oldVisibleToUser != mVisibleToUser) {
            handleVisibleToUserChanged(mVisibleToUser);
        }
    }

    /**
     * Clear Buzz/Beep/Blink.
     */
    public void clearNotificationEffects() {
        try {
            mBarService.clearNotificationEffects();
        } catch (RemoteException e) {
            // Won't fail unless the world has ended.
        }
    }

    /**
     * @return Whether the security bouncer from Keyguard is showing.
     */
    public boolean isBouncerShowing() {
        return mBouncerShowing;
    }

    /**
     * @return Whether the security bouncer from Keyguard is showing.
     */
    public boolean isBouncerShowingScrimmed() {
        return isBouncerShowing() && mStatusBarKeyguardViewManager.bouncerNeedsScrimming();
    }

    /**
     * When {@link KeyguardBouncer} starts to be dismissed, playing its animation.
     */
    public void onBouncerPreHideAnimation() {
        mNotificationPanelViewController.onBouncerPreHideAnimation();

    }

    /**
     * @return a PackageManger for userId or if userId is < 0 (USER_ALL etc) then
     *         return PackageManager for mContext
     */
    public static PackageManager getPackageManagerForUser(Context context, int userId) {
        Context contextForUser = context;
        // UserHandle defines special userId as negative values, e.g. USER_ALL
        if (userId >= 0) {
            try {
                // Create a context for the correct user so if a package isn't installed
                // for user 0 we can still load information about the package.
                contextForUser =
                        context.createPackageContextAsUser(context.getPackageName(),
                        Context.CONTEXT_RESTRICTED,
                        new UserHandle(userId));
            } catch (NameNotFoundException e) {
                // Shouldn't fail to find the package name for system ui.
            }
        }
        return contextForUser.getPackageManager();
    }

    public boolean isKeyguardSecure() {
        if (mStatusBarKeyguardViewManager == null) {
            // startKeyguard() hasn't been called yet, so we don't know.
            // Make sure anything that needs to know isKeyguardSecure() checks and re-checks this
            // value onVisibilityChanged().
            Slog.w(TAG, "isKeyguardSecure() called before startKeyguard(), returning false",
                    new Throwable());
            return false;
        }
        return mStatusBarKeyguardViewManager.isSecure();
    }
    public NotificationPanelViewController getPanelController() {
        return mNotificationPanelViewController;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (FORCE_SHOW_NAVBAR.equals(key) && mDisplayId == Display.DEFAULT_DISPLAY &&
                mWindowManagerService != null) {
            boolean forcedVisibility = mNeedsNavigationBar ||
                    TunerService.parseIntegerSwitch(newValue, false);
            boolean hasNavbar = getNavigationBarView() != null;
            if (forcedVisibility) {
                if (!hasNavbar) {
                    mNavigationBarController.onDisplayReady(mDisplayId);
                }
            } else {
                if (hasNavbar) {
                    mNavigationBarController.onDisplayRemoved(mDisplayId);
                }
            }
        } else if (SCREEN_BRIGHTNESS_MODE.equals(key)) {
            mAutomaticBrightness = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC ==
                    TunerService.parseInteger(newValue,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        } else if (STATUS_BAR_BRIGHTNESS_CONTROL.equals(key)) {
            mBrightnessControl = TunerService.parseIntegerSwitch(newValue, false);
        }
    }
    // End Extra BaseStatusBarMethods.

    public NotificationGutsManager getGutsManager() {
        return mGutsManager;
    }

    boolean isTransientShown() {
        return mTransientShown;
    }

    private void updateLightRevealScrimVisibility() {
        if (mLightRevealScrim == null) {
            // status bar may not be inflated yet
            return;
        }

        mLightRevealScrim.setAlpha(mScrimController.getState().getMaxLightRevealScrimAlpha());
    }

    private final KeyguardUpdateMonitorCallback mUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onDreamingStateChanged(boolean dreaming) {
                    if (dreaming) {
                        maybeEscalateHeadsUp();
                    }
                }

                // TODO: (b/145659174) remove when moving to NewNotifPipeline. Replaced by
                //  KeyguardCoordinator
                @Override
                public void onStrongAuthStateChanged(int userId) {
                    super.onStrongAuthStateChanged(userId);
                    mNotificationsController.requestNotificationUpdate("onStrongAuthStateChanged");
                }
            };


    private final FalsingManager.FalsingBeliefListener mFalsingBeliefListener =
            new FalsingManager.FalsingBeliefListener() {
                @Override
                public void onFalse() {
                    // Hides quick settings, bouncer, and quick-quick settings.
                    mStatusBarKeyguardViewManager.reset(true);
                }
            };

    // Notifies StatusBarKeyguardViewManager every time the keyguard transition is over,
    // this animation is tied to the scrim for historic reasons.
    // TODO: notify when keyguard has faded away instead of the scrim.
    private final ScrimController.Callback mUnlockScrimCallback = new ScrimController
            .Callback() {
        @Override
        public void onFinished() {
            if (mStatusBarKeyguardViewManager == null) {
                Log.w(TAG, "Tried to notify keyguard visibility when "
                        + "mStatusBarKeyguardViewManager was null");
                return;
            }
            if (mKeyguardStateController.isKeyguardFadingAway()) {
                mStatusBarKeyguardViewManager.onKeyguardFadedAway();
            }
        }

        @Override
        public void onCancelled() {
            onFinished();
        }
    };

    private final DeviceProvisionedListener mUserSetupObserver = new DeviceProvisionedListener() {
        @Override
        public void onUserSetupChanged() {
            final boolean userSetup = mDeviceProvisionedController.isCurrentUserSetup();
            Log.d(TAG, "mUserSetupObserver - DeviceProvisionedListener called for "
                    + "current user");
            if (MULTIUSER_DEBUG) {
                Log.d(TAG, String.format("User setup changed: userSetup=%s mUserSetup=%s",
                        userSetup, mUserSetup));
            }

            if (userSetup != mUserSetup) {
                mUserSetup = userSetup;
                if (!mUserSetup && mStatusBarView != null) {
                    animateCollapseQuickSettings();
                }
                if (mNotificationPanelViewController != null) {
                    mNotificationPanelViewController.setUserSetupComplete(mUserSetup);
                }
                updateQsExpansionEnabled();
            }
        }
    };

    private final BroadcastReceiver mWallpaperChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mWallpaperSupported) {
                // Receiver should not have been registered at all...
                Log.wtf(TAG, "WallpaperManager not supported");
                return;
            }
            WallpaperInfo info = mWallpaperManager.getWallpaperInfo(UserHandle.USER_CURRENT);
            mWallpaperController.onWallpaperInfoUpdated(info);

            final boolean deviceSupportsAodWallpaper = mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_dozeSupportsAodWallpaper);
            // If WallpaperInfo is null, it must be ImageWallpaper.
            final boolean supportsAmbientMode = deviceSupportsAodWallpaper
                    && (info != null && info.supportsAmbientMode());
    protected boolean shouldPeek(Entry entry, StatusBarNotification sbn) {

        // get the info from the currently running task
        List<ActivityManager.RunningTaskInfo> taskInfo = mAm.getRunningTasks(1);
        ComponentName componentInfo = taskInfo.get(0).topActivity;

        if(isPackageInStoplist(componentInfo.getPackageName())
                && !isDialerApp(sbn.getPackageName())) {
            return false;
        }

        if (!mUseHeadsUp || isDeviceInVrMode()) {
            if (DEBUG) Log.d(TAG, "No peeking: no huns or vr mode");
            return false;
        }

            mNotificationShadeWindowController.setWallpaperSupportsAmbientMode(supportsAmbientMode);
            mScrimController.setWallpaperSupportsAmbientMode(supportsAmbientMode);
            mKeyguardViewMediator.setWallpaperSupportsAmbientMode(supportsAmbientMode);
        }
    };

    private final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onConfigChanged(Configuration newConfig) {
            updateResources();
            updateDisplaySize(); // populates mDisplayMetrics

            if (DEBUG) {
                Log.v(TAG, "configuration changed: " + mContext.getResources().getConfiguration());
            }

            mViewHierarchyManager.updateRowStates();
            mScreenPinningRequest.onConfigurationChanged();
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            // TODO: Remove this.
            if (mBrightnessMirrorController != null) {
                mBrightnessMirrorController.onDensityOrFontScaleChanged();
            }
            // TODO: Bring these out of StatusBar.
            mUserInfoControllerImpl.onDensityOrFontScaleChanged();
            mUserSwitcherController.onDensityOrFontScaleChanged();
            mNotificationIconAreaController.onDensityOrFontScaleChanged(mContext);
            mHeadsUpManager.onDensityOrFontScaleChanged();
        }

        @Override
        public void onThemeChanged() {
            if (mBrightnessMirrorController != null) {
                mBrightnessMirrorController.onOverlayChanged();
            }
            // We need the new R.id.keyguard_indication_area before recreating
            // mKeyguardIndicationController
            mNotificationPanelViewController.onThemeChanged();

            if (mStatusBarKeyguardViewManager != null) {
                mStatusBarKeyguardViewManager.onThemeChanged();
            }
            if (mAmbientIndicationContainer instanceof AutoReinflateContainer) {
                ((AutoReinflateContainer) mAmbientIndicationContainer).inflateLayout();
            }
            mNotificationIconAreaController.onThemeChanged();
        }

        @Override
        public void onUiModeChanged() {
            if (mBrightnessMirrorController != null) {
                mBrightnessMirrorController.onUiModeChanged();
            }
        }
    };

    private StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStatePreChange(int oldState, int newState) {
                    // If we're visible and switched to SHADE_LOCKED (the user dragged
                    // down on the lockscreen), clear notification LED, vibration,
                    // ringing.
                    // Other transitions are covered in handleVisibleToUserChanged().
                    if (mVisible && (newState == StatusBarState.SHADE_LOCKED
                            || mStatusBarStateController.goingToFullShade())) {
                        clearNotificationEffects();
                    }
                    if (newState == StatusBarState.KEYGUARD) {
                        mRemoteInputManager.onPanelCollapsed();
                        maybeEscalateHeadsUp();
                    }
                }

                @Override
                public void onStateChanged(int newState) {
                    mState = newState;
                    updateReportRejectedTouchVisibility();
                    mDozeServiceHost.updateDozing();
                    updateTheme();
                    mNavigationBarController.touchAutoDim(mDisplayId);
                    Trace.beginSection("StatusBar#updateKeyguardState");
                    if (mState == StatusBarState.KEYGUARD && mStatusBarView != null) {
                        mNotificationPanelViewController.cancelPendingPanelCollapse();
                    }
                    updateDozingState();
                    checkBarModes();
                    updateScrimController();
                    mPresenter.updateMediaMetaData(false, mState != StatusBarState.KEYGUARD);
                    mVisualizerView.setStatusBarState(newState);
                    updateKeyguardState();
                    Trace.endSection();
                }

                @Override
                public void onDozeAmountChanged(float linear, float eased) {
                    if (mFeatureFlags.useNewLockscreenAnimations()
                            && !(mLightRevealScrim.getRevealEffect() instanceof CircleReveal)
                            && !mBiometricUnlockController.isWakeAndUnlock()) {
                        mLightRevealScrim.setRevealAmount(1f - linear);
                    }
                }
    private boolean isPackageInStoplist(String packageName) {
        return mStoplist.contains(packageName);
    }

    private boolean isDialerApp(String packageName) {
        return packageName.equals("com.android.dialer")
            || packageName.equals("com.google.android.dialer");
    }

    private void splitAndAddToArrayList(ArrayList<String> arrayList,
            String baseString, String separator) {
        // clear first
        arrayList.clear();
        if (baseString != null) {
            final String[] array = TextUtils.split(baseString, separator);
            for (String item : array) {
                arrayList.add(item.trim());
            }
        }
    }

    /**
     * @return Whether the security bouncer from Keyguard is showing.
     */
    public boolean isBouncerShowing() {
        return mBouncerShowing;
    }

                @Override
                public void onDozingChanged(boolean isDozing) {
                    Trace.beginSection("StatusBar#updateDozing");
                    mDozing = isDozing;

                    // Collapse the notification panel if open
                    boolean dozingAnimated = mDozeServiceHost.getDozingRequested()
                            && mDozeParameters.shouldControlScreenOff();
                    mNotificationPanelViewController.resetViews(dozingAnimated);

                    updateQsExpansionEnabled();
                    mKeyguardViewMediator.setDozing(mDozing);

                    mNotificationsController.requestNotificationUpdate("onDozingChanged");
                    updateDozingState();
                    mDozeServiceHost.updateDozing();
                    updateScrimController();
                    updateReportRejectedTouchVisibility();
                    Trace.endSection();
                }

                @Override
                public void onFullscreenStateChanged(boolean isFullscreen) {
                    mIsFullscreen = isFullscreen;
                    maybeUpdateBarMode();
                }
            };

    private final BatteryController.BatteryStateChangeCallback mBatteryStateChangeCallback =
            new BatteryController.BatteryStateChangeCallback() {
                @Override
                public void onPowerSaveChanged(boolean isPowerSave) {
                    mMainExecutor.execute(mCheckBarModes);
                    if (mDozeServiceHost != null) {
                        mDozeServiceHost.firePowerSaveChanged(isPowerSave);
                    }
                }
            };

    private final ActivityLaunchAnimator.Callback mKeyguardHandler =
            new ActivityLaunchAnimator.Callback() {
                @Override
                public boolean isOnKeyguard() {
                    return mKeyguardStateController.isShowing();
                }

                @Override
                public void hideKeyguardWithAnimation(IRemoteAnimationRunner runner) {
                    // We post to the main thread for 2 reasons:
                    //   1. KeyguardViewMediator is not thread-safe.
                    //   2. To ensure that ViewMediatorCallback#keyguardDonePending is called before
                    //      ViewMediatorCallback#readyForKeyguardDone. The wrong order could occur
                    //      when doing
                    //      dismissKeyguardThenExecute { hideKeyguardWithAnimation(runner) }.
                    mMainExecutor.execute(() -> mKeyguardViewMediator.hideWithAnimation(runner));
                }

                @Override
                public void setBlursDisabledForAppLaunch(boolean disabled) {
                    mKeyguardViewMediator.setBlursDisabledForAppLaunch(disabled);
                }

                @Override
                public int getBackgroundColor(TaskInfo task) {
                    if (!mStartingSurfaceOptional.isPresent()) {
                        Log.w(TAG, "No starting surface, defaulting to SystemBGColor");
                        return SplashscreenContentDrawer.getSystemBGColor();
                    }

                    return mStartingSurfaceOptional.get().getBackgroundColor(task);
                }
            };
}
