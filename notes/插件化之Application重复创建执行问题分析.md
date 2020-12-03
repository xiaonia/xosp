### 插件化 Application 重复创建执行问题分析

#### 源码分析

[ActivityThread.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-4.4_r1.0.1/core/java/android/app/ActivityThread.java)

```java
    private Activity performLaunchActivity(ActivityClientRecord r, Intent customIntent) {
        ActivityInfo aInfo = r.activityInfo;
        if (r.packageInfo == null) {
            // 调用 getPackageInfo() 方法创建或者获取 LoadedApk
            r.packageInfo = getPackageInfo(aInfo.applicationInfo, r.compatInfo,
                    Context.CONTEXT_INCLUDE_CODE);
        }
        
        try {
            // 调用 makeApplication() 方法创建或者获取 Application
            Application app = r.packageInfo.makeApplication(false, mInstrumentation);
            ......
            if (activity != null) {
                Context appContext = createBaseContextForActivity(r, activity);
                ......
                activity.attach(appContext, this, getInstrumentation(), r.token,
                        r.ident, app, r.intent, r.activityInfo, title, r.parent,
                        r.embeddedID, r.lastNonConfigurationInstances, config);
                ......
                mInstrumentation.callActivityOnCreate(activity, r.state);
                ......
            }
            r.paused = true;
            mActivities.put(r.token, r);
        } 
        ......
        return activity;
    }
```
从源码上可以看出，performLaunchActivity() 方法的执行过程：

* 如果 r.packageInfo 为空，则__调用 getPackageInfo() 方法__创建或获取 LoadedApk

* __调用 makeApplication() 方法__创建或者获取已创建的 Application，__那么显然问题应该就出现在这里__

* 实例化 Activity，并执行 attach() 和 onCreate() 等方法

PS：实际上，并不限于 performLaunchActivity() 方法，handleReceiver()、handleCreateService() 方法均有类似的逻辑。



[ActivityThread.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-4.4_r1.0.1/core/java/android/app/ActivityThread.java)

```java
    private LoadedApk getPackageInfo(ApplicationInfo aInfo, CompatibilityInfo compatInfo,
            ClassLoader baseLoader, boolean securityViolation, boolean includeCode) {
        synchronized (mResourcesManager) {
            WeakReference<LoadedApk> ref;
            if (includeCode) {
                ref = mPackages.get(aInfo.packageName);
            } else {
                ref = mResourcePackages.get(aInfo.packageName);
            }
            LoadedApk packageInfo = ref != null ? ref.get() : null;
            if (packageInfo == null || (packageInfo.mResources != null
                    && !packageInfo.mResources.getAssets().isUpToDate())) {
                // 如果 packageInfo 为空，或者 isUpToDate() 返回 false，则重新创建一个 LoadedApk 
                packageInfo =
                    new LoadedApk(this, aInfo, compatInfo, this, baseLoader,
                            securityViolation, includeCode &&
                            (aInfo.flags&ApplicationInfo.FLAG_HAS_CODE) != 0);
                if (includeCode) {
                    mPackages.put(aInfo.packageName,
                            new WeakReference<LoadedApk>(packageInfo));
                } else {
                    mResourcePackages.put(aInfo.packageName,
                            new WeakReference<LoadedApk>(packageInfo));
                }
            }
            return packageInfo;
        }
    }
```

从源码来看，getPackageInfo() 方法的执行过程：

* 先尝试从 mPackages 缓存中获取 packageInfo

* 如果 packageInfo 为 __空__ 或者 packageInfo 绑定的 AssetManager 的 __isUpToDate()__ 方法返回 __false__，则重新创建一个 packageInfo

而 packageInfo 为空的可能性比较低，因为：

* 进程启动的时候，在 handleBindApplication() 方法中即创建了 packageInfo

* 所有的 Application 在 mAllApplications 中都有强引用，因此不可能由于 WeakReference 而被回收

* handleDispatchPackageBroadcast() 清除，见下文分析

因此最有可能的原因是： __AssetManager 的 isUpToDate() 方法返回 false__



#### handleDispatchPackageBroadcast() 方法

[ActivityThread.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-4.4.2_r1.0.1/core/java/android/app/ActivityThread.java)

```java
    final void handleDispatchPackageBroadcast(int cmd, String[] packages) {
        boolean hasPkgInfo = false;
        if (packages != null) {
            for (int i=packages.length-1; i>=0; i--) {
                //Slog.i(TAG, "Cleaning old package: " + packages[i]);
                if (!hasPkgInfo) {
                    WeakReference<LoadedApk> ref;
                    ref = mPackages.get(packages[i]);
                    if (ref != null && ref.get() != null) {
                        hasPkgInfo = true;
                    } else {
                        ref = mResourcePackages.get(packages[i]);
                        if (ref != null && ref.get() != null) {
                            hasPkgInfo = true;
                        }
                    }
                }
                // 移除 LoadedApk 缓存
                mPackages.remove(packages[i]);
                mResourcePackages.remove(packages[i]);
            }
        }
        ApplicationPackageManager.handlePackageBroadcast(cmd, packages,
                hasPkgInfo);
    }
```

handleDispatchPackageBroadcast() 方法一般是应用卸载或者重装的时候，由 AMS 发送的广播，正常情况下，AMS 会先 stop 当前进程，因此这里出现问题的概率也很低，此处不深入分析，详见 [ActivityManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-4.4.2_r1.0.1/services/java/com/android/server/am/ActivityManagerService.java) 及 [PackageManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-4.4.2_r1.0.1/services/java/com/android/server/pm/PackageManagerService.java)



#### isUpToDate() 方法

[Resources.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-4.4.2_r1.0.1/core/java/android/content/res/Resources.java)

[AssetManager.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-4.4.2_r1.0.1/core/java/android/content/res/AssetManager.java)

[android_util_AssetManager.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-4.4.2_r1.0.1/core/jni/android_util_AssetManager.cpp)

[AssetManager.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-4.4.2_r1.0.1/libs/androidfw/AssetManager.cpp)

```cpp
bool AssetManager::isUpToDate()
{
    AutoMutex _l(mLock);
    return mZipSet.isUpToDate();
}

bool AssetManager::ZipSet::isUpToDate()
{
    const size_t N = mZipFile.size();
    for (size_t i=0; i<N; i++) {
        if (mZipFile[i] != NULL && !mZipFile[i]->isUpToDate()) {
            return false;
        }
    }
    return true;
}

bool AssetManager::SharedZip::isUpToDate()
{
    time_t modWhen = getFileModDate(mPath.string());
    return mModWhen == modWhen;
}
```
实际上，当我们通过 AssetManager 将 .apk 文件的资源加载进来的时候，在系统底层都会为其创建并维护一个 SharedZip 类型的数据结构，详细过程见 [AssetManager.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-4.4.2_r1.0.1/libs/androidfw/AssetManager.cpp)。

从源码可以看出，isUpToDate() 是通过判断各个 apk 文件是否有__变化__来判断资源是否有变化，从逻辑上来看该方法出现问题的可能性比较大：因为每个子插件都是一个独立的 apk ，因此只要有一个插件的 apk 在加载完之后__更新或者删除__了，那么该方法就会返回false，从而导致 Application 重复创建并执行。



#### 测试验证

在 Android 4.4 盒子上验证：

```java
// 加载子插件
11-11 10:20:41.490 3551-3551/com.test.example E/plugin: >>> Resources = com.test.example.PluginResources@4204eec8
11-11 10:20:41.491 3551-3551/com.test.example E/plugin: >>> AssetManager = android.content.res.AssetManager@42c6fd00
11-11 10:20:41.497 3551-3551/com.test.example E/plugin: >>> isUpToDate = true
// 更新或者删除子插件的 apk 文件
11-11 10:21:00.156 3551-3551/com.test.example E/plugin: >>> Resources = com.test.example.PluginResources@4204eec8
11-11 10:21:00.156 3551-3551/com.test.example E/plugin: >>> AssetManager = android.content.res.AssetManager@42c6fd00
11-11 10:21:00.169 3551-3551/com.test.example E/plugin: >>> isUpToDate = false
// 打开搜索页
11-11 10:21:08.914 3551-3551/com.test.example I/plugin: [ HandlerCallback ] : handling: PAUSE_ACTIVITY
11-11 10:21:08.982 3551-3551/com.test.example I/plugin: [ HandlerCallback ] : handling: LAUNCH_ACTIVITY
// 重复创建并执行 Application
11-11 10:21:09.050 3551-3551/com.test.example D/HostApp: attach base context
11-11 10:21:09.050 3551-3551/com.test.example I/HostApp: SDK_INT -> 19
11-11 10:21:09.064 3551-3551/com.test.example D/HostApp: on create
```
