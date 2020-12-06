###  插件化之 Inlined method resolution crossed dex file boundary 问题试分析

#### 相关链接

[improvements](https://source.android.com/devices/tech/dalvik/improvements#inline-caches-in-oat-files)

[jit-compiler](https://source.android.com/devices/tech/dalvik/jit-compiler)

[configure](https://source.android.com/devices/tech/dalvik/configure)

[Android运行时ART执行类方法的过程分析](https://blog.csdn.net/Luoshengyang/article/details/40289405)

[Android ART 虚拟机 AOT 和 JIT 内联机制(inline-cache)浅析](https://juejin.cn/post/6901936720904716296)

#### 问题

```java
09-17 14:18:18.742 29293 29293 W .xosp.example: Inlined method resolution crossed dex file boundary: from void com.xosp.example.app.MainActivity.<init>() in /data/app/com.xosp.example-0VCtVl6U_1zUobhX2ykrLw==/base.apk/0xf19a39d0 to void com.xosp.example.app.helper.j.b.<init>(android.content.Context) in /data/user/0/com.xosp.example/app_plugins/com.xosp.example.plugin.host.1.0.1.apk/0xf19a6ad0. This must be due to duplicate classes or playing wrongly with class loaders. The runtime is in an unsafe state.
```

#### 分析

首先，出现该问题的根本原因在于：本来应该加载插件中的类，由于某种原因却错误的加载了宿主中的类，类似于 [pre-verify](https://juejin.cn/post/6886280179917078542) 问题。而之所以会出现这个问题，大部分场景下都是因为在插件加载完成之前，提前触发了相关类的加载，从而加载了宿主中的代码。

事实上，由于目前插件化的技术已经相当成熟了，因此基本上可以排除逻辑上或者插件机制上的问题导致提前触发相关类的加载。同时我们也发现该问题出现的几个特点：

* 使用 R8 编译器会出现该问题，使用 D8 则未发现该问题

* 该问题为偶现问题，同一个版本，同一类型的机器，只有部分机器在某种场景之下才会出现该问题

* 一旦出现该问题，则之后为必现问题

* Android 8.0 及以上的系统才会出现该问题

结合以上几个特点，基本可以排除逻辑上或者插件机制上，甚至是系统机制上的问题了。

#### 猜想

dex2oat 编译优化，部分 class 的加载时机发生了变化（提前加载），从而导致 Inlined method resolution crossed dex file boundary 问题。

#### 测试验证

##### 测试代码

```java
    private void initViews() {
        mBtnExecFirstImpl.setOnClickListener(view -> {
            mTestImpl = new TestImplFirst();
            mHandler.post(mExecuteTestTask);
        });

        mBtnExecSecondImpl.setOnClickListener(view -> {
            mTestImpl = new TestImplSecond();
            mHandler.post(mExecuteTestTask);
        });
    }

    private final Runnable mExecuteTestTask = new Runnable() {
        @Override
        public void run() {
            Log.e(TAG, "execute test implementation start...");
            if (mTestImpl != null) {
                mTestImpl.test();
            }
            Log.e(TAG, "execute test implementation finish.");
        }
    };
```

#### 测试过程

* 打包编译release代码

* 启动app，点击 N 次 mBtnExecFirstImpl

* 运行【adb shell cmd package compile -m speed-profile -f com.xosp.test.inline】命令进行dex2oat 编译

* 再次启动app，首次点击 mBtnExecSecondImpl

##### 测试结果

```java
// 点击 mBtnExecSecondImpl，执行 onClick() 方法
// 加载 TestImplSecond 类及其父类
2020-12-03 09:19:06.927 25392-25392/com.xosp.test.inline E/AndroidNClassLoader: use androidN classloader, com.xosp.test.inline.impl.TestImplSecond
2020-12-03 09:19:06.928 25392-25392/com.xosp.test.inline E/AndroidNClassLoader: use androidN classloader, com.xosp.test.inline.impl.TestImplBase
2020-12-03 09:19:06.928 25392-25392/com.xosp.test.inline E/AndroidNClassLoader: use androidN classloader, com.xosp.test.inline.inter.ITest
// 执行 mExecuteTestTask 的 run() 方法
2020-12-03 09:19:06.929 25392-25392/com.xosp.test.inline E/MainActivity: execute test implementation start...
// 加载 TestImplFirst 类
2020-12-03 09:19:06.929 25392-25392/com.xosp.test.inline E/AndroidNClassLoader: use androidN classloader, com.xosp.test.inline.impl.TestImplFirst
```

##### 调用栈

```java
2020-12-03 09:19:07.124 25430-25430/? A/DEBUG:     #16 pc 000000000004da1a  /data/app/com.xosp.test.inline-GwSCS3Uk1ZWgeCzCG6Gdiw==/oat/x86_64/base.odex (offset 0x22000) (com.xosp.test.inline.patch.AndroidNClassLoader.findClass+906)
2020-12-03 09:19:07.124 25430-25430/? A/DEBUG:     #17 pc 00000000001894f5  /system/framework/x86_64/boot.oat (offset 0x110000) (java.lang.ClassLoader.loadClass+213)
2020-12-03 09:19:07.124 25430-25430/? A/DEBUG:     #18 pc 00000000001893f5  /system/framework/x86_64/boot.oat (offset 0x110000) (java.lang.ClassLoader.loadClass+53)
2020-12-03 09:19:07.124 25430-25430/? A/DEBUG:     #19 pc 00000000005c3ab4  /system/lib64/libart.so (art_quick_invoke_stub+756)
2020-12-03 09:19:07.124 25430-25430/? A/DEBUG:     #20 pc 00000000000cf5f2  /system/lib64/libart.so (art::ArtMethod::Invoke(art::Thread*, unsigned int*, unsigned int, art::JValue*, char const*)+226)
2020-12-03 09:19:07.125 25430-25430/? A/DEBUG:     #21 pc 00000000004b7569  /system/lib64/libart.so (art::(anonymous namespace)::InvokeWithArgArray(art::ScopedObjectAccessAlreadyRunnable const&, art::ArtMethod*, art::(anonymous namespace)::ArgArray*, art::JValue*, char const*)+89)
2020-12-03 09:19:07.125 25430-25430/? A/DEBUG:     #22 pc 00000000004b8bca  /system/lib64/libart.so (art::InvokeVirtualOrInterfaceWithVarArgs(art::ScopedObjectAccessAlreadyRunnable const&, _jobject*, _jmethodID*, __va_list_tag*)+442)
2020-12-03 09:19:07.125 25430-25430/? A/DEBUG:     #23 pc 0000000000370768  /system/lib64/libart.so (art::JNI::CallObjectMethodV(_JNIEnv*, _jobject*, _jmethodID*, __va_list_tag*)+808)
2020-12-03 09:19:07.125 25430-25430/? A/DEBUG:     #24 pc 00000000000ff682  /system/lib64/libart.so (art::(anonymous namespace)::CheckJNI::CallMethodV(char const*, _JNIEnv*, _jobject*, _jclass*, _jmethodID*, __va_list_tag*, art::Primitive::Type, art::InvokeType)+1042)
2020-12-03 09:19:07.125 25430-25430/? A/DEBUG:     #25 pc 00000000000ece04  /system/lib64/libart.so (art::(anonymous namespace)::CheckJNI::CallObjectMethodV(_JNIEnv*, _jobject*, _jmethodID*, __va_list_tag*)+36)
2020-12-03 09:19:07.125 25430-25430/? A/DEBUG:     #26 pc 0000000000120ad9  /system/lib64/libart.so (_JNIEnv::CallObjectMethod(_jobject*, _jmethodID*, ...)+153)
2020-12-03 09:19:07.125 25430-25430/? A/DEBUG:     #27 pc 000000000011efe3  /system/lib64/libart.so (art::ClassLinker::FindClass(art::Thread*, char const*, art::Handle<art::mirror::ClassLoader>)+3539)
2020-12-03 09:19:07.125 25430-25430/? A/DEBUG:     #28 pc 000000000013c51a  /system/lib64/libart.so (art::ClassLinker::DoResolveType(art::dex::TypeIndex, art::Handle<art::mirror::DexCache>, art::Handle<art::mirror::ClassLoader>)+154)
2020-12-03 09:19:07.125 25430-25430/? A/DEBUG:     #29 pc 00000000000d0164  /system/lib64/libart.so (art::ClassLinker::ResolveType(art::dex::TypeIndex, art::ArtMethod*)+420)
2020-12-03 09:19:07.125 25430-25430/? A/DEBUG:     #30 pc 000000000029f237  /system/lib64/libart.so (art::ResolveVerifyAndClinit(art::dex::TypeIndex, art::ArtMethod*, art::Thread*, bool, bool)+71)
2020-12-03 09:19:07.125 25430-25430/? A/DEBUG:     #31 pc 0000000000573513  /system/lib64/libart.so (artInitializeTypeFromCode+51)
// 触发类加载逻辑
2020-12-03 09:19:07.125 25430-25430/? A/DEBUG:     #32 pc 00000000005cb85f  /system/lib64/libart.so (art_quick_initialize_type+175)
// 执行 mExecuteTestTask 的 run() 方法
2020-12-03 09:19:07.125 25430-25430/? A/DEBUG:     #33 pc 000000000002241a  /data/app/com.xosp.test.inline-GwSCS3Uk1ZWgeCzCG6Gdiw==/oat/x86_64/base.odex (offset 0x22000) (com.xosp.test.inline.MainActivity$a.run+746)
2020-12-03 09:19:07.126 25430-25430/? A/DEBUG:     #34 pc 0000000000abcd56  /system/framework/x86_64/boot-framework.oat (offset 0x3c3000) (android.os.Handler.dispatchMessage+86)
2020-12-03 09:19:07.126 25430-25430/? A/DEBUG:     #35 pc 0000000000abfcff  /system/framework/x86_64/boot-framework.oat (offset 0x3c3000) (android.os.Looper.loop+1119)
2020-12-03 09:19:07.126 25430-25430/? A/DEBUG:     #36 pc 00000000008921f7  /system/framework/x86_64/boot-framework.oat (offset 0x3c3000) (android.app.ActivityThread.main+583)
```

#### 结论

从测试结果来看，不难发现：在本不应触发 class 加载的方法里，触发了 class 加载的过程。这也验证了之前的猜想：dex2oat 在编译优化的时候，确实有可能改变 class 的加载时机（例如 [inline-cache](https://juejin.cn/post/6901936720904716296/) 机制），甚至在代码逻辑上完全还没触及相关逻辑的时候就加载某个 class，从而导致部分 class 提前被加载，最后在插件化或者热修复的场景下，引起  Inlined method resolution crossed dex file boundary 问题。