###  插件化：由动态 PackageId 引起的插件资源异常问题分析

#### 现场还原

```java
java.lang.RuntimeException: Package not found: com.google.android.webview
	at android.webkit.WebViewDelegate.getPackageId(WebViewDelegate.java:138)
	at com.android.webview.chromium.WebViewDelegateFactory$ProxyDelegate.getPackageId(WebViewDelegateFactory.java:16)
	at com.android.webview.chromium.WebViewChromiumAwInit$1.run(WebViewChromiumAwInit.java:9)
	at java.lang.Thread.run(Thread.java:818)
```

```java
android.content.res.Resources$NotFoundException: Resource ID #0x78080007
	at android.content.res.Resources.getValue(Resources.java:1351)
	at android.content.res.Resources.loadXmlResourceParser(Resources.java:2737)
	at android.content.res.Resources.getLayout(Resources.java:1165)
	at android.view.LayoutInflater.inflate(LayoutInflater.java:421)
	at android.view.LayoutInflater.inflate(LayoutInflater.java:374)
```

在小米 5.0 和 6.0 的设备上，经常会出现 插件资源找不到的问题 或者 插件资源错乱的问题 以及 WebView 资源异常的问题，异常信息如上：

#### 日志分析

```java
	12764 12764 W ResourceType: For resource 0x780a03bf, entry index(959) is beyond type entryCount(1)
	12764 12764 W ResourceType: For resource 0x7805067d, entry index(1661) is beyond type entryCount(87)
	12764 12764 W ResourceType: For resource 0x7808013a, entry index(314) is beyond type entryCount(9)
	12764 12764 W ResourceType: For resource 0x7807071a, entry index(1818) is beyond type entryCount(4)
	12764 12764 W ResourceType: For resource 0x780700c9, entry index(201) is beyond type entryCount(4)
	12764 12764 W ResourceType: For resource 0x78030388, entry index(904) is beyond type entryCount(11)
```

```java
	8874  8874 W ResourceType: ResTable_typeSpec entry count inconsistent: given 8, previously 31
	8874  8874 W ResourceType: ResTable_typeSpec entry count inconsistent: given 20, previously 84
	8874  8874 W ResourceType: ResTable_typeSpec entry count inconsistent: given 11, previously 1201
	8874  8874 W ResourceType: ResTable_typeSpec entry count inconsistent: given 1, previously 192
	8874  8874 W ResourceType: ResTable_typeSpec entry count inconsistent: given 87, previously 3113
	8874  8874 W ResourceType: ResTable_typeSpec entry count inconsistent: given 11, previously 1633
	8874  8874 W ResourceType: ResTable_typeSpec entry count inconsistent: given 4, previously 1943
	8874  8874 W ResourceType: ResTable_typeSpec entry count inconsistent: given 9, previously 407
	8874  8874 W ResourceType: ResTable_typeSpec entry count inconsistent: given 47, previously 5
	8874  8874 W ResourceType: ResTable_typeSpec entry count inconsistent: given 1, previously 1850
```
同时，也发现在出现异常的日志中，经常伴有 ResourceType 的异常日志信息，虽然这些信息不会直接导致 crash，但是也可以看出应该跟 Android 系统底层资源加载有关系。

#### 源码

[ResourceTypes.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.0_r1/libs/androidfw/ResourceTypes.cpp)

```cpp
status_t ResTable::parsePackage(const ResTable_package* const pkg,
                                const Header* const header)
{
    const uint8_t* base = (const uint8_t*)pkg;
    ......
    uint32_t id = dtohl(pkg->id);
    KeyedVector<uint8_t, IdmapEntries> idmapEntries;
    if (header->resourceIDMap != NULL) {
        uint8_t targetPackageId = 0;
        status_t err = parseIdmap(header->resourceIDMap, header->resourceIDMapSize, &targetPackageId, &idmapEntries);
        if (err != NO_ERROR) {
            ALOGW("Overlay is broken");
            return (mError=err);
        }
        id = targetPackageId;
    }
    if (id >= 256) {
        LOG_ALWAYS_FATAL("Package id out of range");
        return NO_ERROR;
    } else if (id == 0) {
        // This is a library so assign an ID
        id = mNextPackageId++;
    }
    ......
}
```

我们知道，Android 的资源信息是 0xPPTTEEEE 的形式，其中 PP 是 PackageId，TT 是 TypeIndex，EEEE 是EntryIndex。 翻看 Android 底层资源加载的源码，可以看出并不是所有资源的 PackageId 都是固定，如果 PP 是 00，则表示该资源是共享资源，也就是说它的 PackageId 的动态分配的，一般是从 0x02 开始（0x01是系统资源）。

#### 测试验证

天猫7.0设备：

```java
04-10 14:05:18.975 8198-8198/? I/TestActivity: [TID 8198] getAssignedPackageIdentifiers{1=android, 2=com.android.webview, 12=com.yunos, 114=com.xosp.test.plugin1, 116=com.xosp.test.plugin2, 127=com.xosp.test}
```

小米6.0设备：

```java
04-10 14:45:36.346 8589-8589/? I/TestActivity: [TID 8589] getAssignedPackageIdentifiers{1=android, 114=com.xosp.test.plugin1, 116=com.xosp.test.plugin2, 117=com.google.android.webview, 127=com.xosp.test}
```

```java
04-10 14:46:14.221 9103-9103/? I/TestActivity: [TID 9103] getAssignedPackageIdentifiers{1=android, 114=com.xosp.test.plugin1, 115=com.google.android.webview, 116=com.xosp.test.plugin2, 127=com.xosp.test}
```

经过猜想和验证发现，小米 5.0 和 6.0 的设备，动态 PackageId 的分配与 Android 原生的系统逻辑并不一致，其动态 PackageId 为上一个加载资源的 PackageId 上加 1，比如：先加载了插件 plugin，它的 PackageId 为 0x20，然后再去加载 WebView.apk，则此时分配给 WebView 的 PackageId 为 0x21。

这样的话，如果存在多个插件，他们的 PackageId 是连续，那么就存在一种可能：在某些场景下，动态分配给WebView 的 PackageId 与插件的 PackageId 是一样的，这样就会出现资源混乱的情况，也就会出现上文提到的各种异常。

#### 总结

考虑到提前加载 WebView.apk 带来的内存和性能上的影响，同时目前动态 PackageId 的场景仅为WebView 一种，因此可以通过设置不连续的插件 PackageId 来简单的规避该问题。

