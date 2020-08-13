### Activity的启动过程

[Instrumentation.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.1_r1/core/java/android/app/Instrumentation.java)

[ActivityManager.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.1_r1/core/java/android/app/ActivityManager.java)

[ActivityManagerNative.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.1_r1/core/java/android/app/ActivityManagerNative.java)

[ActivityManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.1_r1/services/core/java/com/android/server/am/ActivityManagerService.java)

[ActivityStackSupervisor.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.1_r1/services/core/java/com/android/server/am/ActivityStackSupervisor.java)

[ActivityStack.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.1_r1/services/core/java/com/android/server/am/ActivityStack.java)

[ApplicationThreadNative.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.1_r1/core/java/android/app/ApplicationThreadNative.java)

[ActivityThread.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.1_r1/core/java/android/app/ActivityThread.java)


Activity的启动过程就是一个典型的Client和Server通信的过程：发起和执行均在Client，而管理则在Server。

* Activity：调用Instrumentation的execStartActivity方法

* Instrumentation：调用ActivityManagerProxy的startActivity方法(Binder)，封装并发送IPC请求

* ActivityManagerNative：onTransact方法收到IPC请求，解析并调用ActivityManagerService的startActivity方法

* ActivityManagerService：调用ActivityStackSupervisor的startActivityMayWait方法

* ActivityStackSupervisor：调用ActivityStack的resumeTopActivityLocked方法

* ActivityStack：调用ActivityStackSupervisor的startSpecificActivityLocked方法

* ActivityStackSupervisor：调用realStartActivityLocked方法

* ActivityStackSupervisor：调用ApplicationThreadProxy的scheduleLaunchActivity方法

* ApplicationThreadNative：onTransact方法收到IPC回调，解析并调用ApplicationThread的scheduleLaunchActivity方法

* ApplicationThread：发送消息至ActivityThread的H

* H：handler收到消息，调用ActivityThread的handleLaunchActivity方法

* ActivityThread：调用performLaunchActivity方法

* ActivityThread：依次调用Instrumentation的newActivity、callActivityOnCreate和callActivityOnRestoreInstanceState方法

#### Instrumentation

```java
    public ActivityResult execStartActivity(
            Context who, IBinder contextThread, IBinder token, Activity target,
            Intent intent, int requestCode, Bundle options) {
         ......
         try {
            intent.migrateExtraStreamToClipData();
            intent.prepareToLeaveProcess();
            int result = ActivityManagerNative.getDefault()
                .startActivity(whoThread, who.getBasePackageName(), intent,
                        intent.resolveTypeIfNeeded(who.getContentResolver()),
                        token, target != null ? target.mEmbeddedID : null,
                        requestCode, 0, null, options);
            checkStartActivityResult(result, intent);
        } catch (RemoteException e) {
        }
        return null;
    }
```


#### ActivityManagerNative$ActivityManagerProxy

```java
public int startActivity(IApplicationThread caller, String callingPackage, Intent intent,
            String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, ProfilerInfo profilerInfo, Bundle options) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        ......
        mRemote.transact(START_ACTIVITY_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }
```

#### ActivityManagerNative

```java
 @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        switch (code) {
        case START_ACTIVITY_TRANSACTION:
        {
            ......
            int result = startActivity(app, callingPackage, intent, resolvedType,
                    resultTo, resultWho, requestCode, startFlags, profilerInfo, options);
            reply.writeNoException();
            reply.writeInt(result);
            return true;
        }
        ......
    }
```

#### ActivityManagerService

```java
    @Override
    public final int startActivityAsUser(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, ProfilerInfo profilerInfo, Bundle options, int userId) {
        enforceNotIsolatedCaller("startActivity");
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, ALLOW_FULL_ONLY, "startActivity", null);
        // TODO: Switch to user app stacks here.
        return mStackSupervisor.startActivityMayWait(caller, -1, callingPackage, intent,
                resolvedType, null, null, resultTo, resultWho, requestCode, startFlags,
                profilerInfo, null, null, options, userId, null, null);
    }
```

#### ActivityStackSupervisor

```java
    final int startActivityMayWait(IApplicationThread caller, int callingUid,
            String callingPackage, Intent intent, String resolvedType,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            IBinder resultTo, String resultWho, int requestCode, int startFlags,
            ProfilerInfo profilerInfo, WaitResult outResult, Configuration config,
            Bundle options, int userId, IActivityContainer iContainer, TaskRecord inTask) {
        ......
        // Collect information about the target of the Intent.
        ActivityInfo aInfo = resolveActivity(intent, resolvedType, startFlags,
                profilerInfo, userId);
        ......
            int res = startActivityLocked(caller, intent, resolvedType, aInfo,
                    voiceSession, voiceInteractor, resultTo, resultWho,
                    requestCode, callingPid, callingUid, callingPackage,
                    realCallingPid, realCallingUid, startFlags, options,
                    componentSpecified, null, container, inTask);
            Binder.restoreCallingIdentity(origId);
        .......
    }
```

#### ActivityStack

```java
    final boolean resumeTopActivityLocked(ActivityRecord prev, Bundle options) {
        ......
        boolean result = false;
        try {
            // Protect against recursion.
            inResumeTopActivity = true;
            result = resumeTopActivityInnerLocked(prev, options);
        } finally {
            inResumeTopActivity = false;
        }
        return result;
    }
```

#### ActivityStackSupervisor

```java
    final boolean realStartActivityLocked(ActivityRecord r,
            ProcessRecord app, boolean andResume, boolean checkConfig)
            throws RemoteException {
            ......
            app.thread.scheduleLaunchActivity(new Intent(r.intent), r.appToken,
                    System.identityHashCode(r), r.info, new Configuration(mService.mConfiguration),
                    r.compat, r.task.voiceInteractor, app.repProcState, r.icicle, r.persistentState,
                    results, newIntents, !andResume, mService.isNextTransitionForward(),
                    profilerInfo);
            ......
    }
```


#### ApplicationThreadNative$ApplicationThreadProxy

```java
 public final void scheduleLaunchActivity(Intent intent, IBinder token, int ident,
            ActivityInfo info, Configuration curConfig, CompatibilityInfo compatInfo,
            IVoiceInteractor voiceInteractor, int procState, Bundle state,
            PersistableBundle persistentState, List<ResultInfo> pendingResults,
            List<Intent> pendingNewIntents, boolean notResumed, boolean isForward,
            ProfilerInfo profilerInfo) throws RemoteException {
        Parcel data = Parcel.obtain();
        ......
        mRemote.transact(SCHEDULE_LAUNCH_ACTIVITY_TRANSACTION, data, null,
                IBinder.FLAG_ONEWAY);
        data.recycle();
    }
```

#### ApplicationThreadNative

```kotlin
    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        switch (code) {
        case SCHEDULE_LAUNCH_ACTIVITY_TRANSACTION:
        {
            data.enforceInterface(IApplicationThread.descriptor);
            Intent intent = Intent.CREATOR.createFromParcel(data);
            IBinder b = data.readStrongBinder();
            ......
            scheduleLaunchActivity(intent, b, ident, info, curConfig, compatInfo, voiceInteractor,
                    procState, state, persistentState, ri, pi, notResumed, isForward, profilerInfo);
            return true;
        }
    }
```

#### ActivityThread$ApplicationThread

```java
        // we use token to identify this activity without having to send the
        // activity itself back to the activity manager. (matters more with ipc)
        public final void scheduleLaunchActivity(Intent intent, IBinder token, int ident,
                ActivityInfo info, Configuration curConfig, CompatibilityInfo compatInfo,
                IVoiceInteractor voiceInteractor, int procState, Bundle state,
                PersistableBundle persistentState, List<ResultInfo> pendingResults,
                List<Intent> pendingNewIntents, boolean notResumed, boolean isForward,
                ProfilerInfo profilerInfo) {
            updateProcessState(procState, false);
            ActivityClientRecord r = new ActivityClientRecord();
            ......
            sendMessage(H.LAUNCH_ACTIVITY, r);
        }
```

#### ActivityThread$H

```java
        public void handleMessage(Message msg) {
            if (DEBUG_MESSAGES) Slog.v(TAG, ">>> handling: " + codeToString(msg.what));
            switch (msg.what) {
                case LAUNCH_ACTIVITY: {
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "activityStart");
                    final ActivityClientRecord r = (ActivityClientRecord) msg.obj;
                    r.packageInfo = getPackageInfoNoCheck(
                            r.activityInfo.applicationInfo, r.compatInfo);
                    handleLaunchActivity(r, null);
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                } break;
                ......
            }
        }
```

#### ActivityThread

```java
    private void handleLaunchActivity(ActivityClientRecord r, Intent customIntent) {
        ......
        // Make sure we are running with the most recent config.
        handleConfigurationChanged(null, null);
        Activity a = performLaunchActivity(r, customIntent);
        if (a != null) {
            r.createdConfig = new Configuration(mConfiguration);
            Bundle oldState = r.state;
            handleResumeActivity(r.token, false, r.isForward,
                    !r.activity.mFinished && !r.startsNotResumed);
            if (!r.activity.mFinished && r.startsNotResumed) {
                try {
                    r.activity.mCalled = false;
                    mInstrumentation.callActivityOnPause(r.activity);
                } catch (Exception e) {
                    ......
                }
                r.paused = true;
            }
        } else {
            // If there was an error, for any reason, tell the activity
            // manager to stop us.
            try {
                ActivityManagerNative.getDefault()
                    .finishActivity(r.token, Activity.RESULT_CANCELED, null, false);
            } catch (RemoteException ex) {
                // Ignore
            }
        }
    }
```

#### ActivityThread

```java

    private Activity performLaunchActivity(ActivityClientRecord r, Intent customIntent) {
        ......
        Activity activity = null;
        try {
            java.lang.ClassLoader cl = r.packageInfo.getClassLoader();
            activity = mInstrumentation.newActivity(
                    cl, component.getClassName(), r.intent);
            ......
        } catch (Exception e) {
            ......
        }
        try {
            Application app = r.packageInfo.makeApplication(false, mInstrumentation);
            if (activity != null) {
                Context appContext = createBaseContextForActivity(r, activity);
                ......
                int theme = r.activityInfo.getThemeResource();
                if (theme != 0) {
                    activity.setTheme(theme);
                }
                activity.mCalled = false;
                if (r.isPersistable()) {
                    mInstrumentation.callActivityOnCreate(activity, r.state, r.persistentState);
                } else {
                    mInstrumentation.callActivityOnCreate(activity, r.state);
                }
                r.activity = activity;
                r.stopped = true;
                if (!r.activity.mFinished) {
                    activity.performStart();
                    r.stopped = false;
                }
                if (!r.activity.mFinished) {
                    if (r.isPersistable()) {
                        if (r.state != null || r.persistentState != null) {
                            mInstrumentation.callActivityOnRestoreInstanceState(activity, r.state,
                                    r.persistentState);
                        }
                    } else if (r.state != null) {
                        mInstrumentation.callActivityOnRestoreInstanceState(activity, r.state);
                    }
                }
                if (!r.activity.mFinished) {
                    activity.mCalled = false;
                    if (r.isPersistable()) {
                        mInstrumentation.callActivityOnPostCreate(activity, r.state,
                                r.persistentState);
                    } else {
                        mInstrumentation.callActivityOnPostCreate(activity, r.state);
                    }
                    if (!activity.mCalled) {
                        ......
                    }
                }
            }
            r.paused = true;
            mActivities.put(r.token, r);
        } catch (Exception e) {
            ......
        }
        return activity;
    }
```

