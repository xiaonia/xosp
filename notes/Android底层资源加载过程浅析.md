###  Android底层资源加载过程浅析

#### 相关链接

[android_util_AssetManager.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.0_r1/core/jni/android_util_AssetManager.cpp)

[AssetManager.h](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.0_r1/include/androidfw/AssetManager.h)
[AssetManager.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.0_r1/libs/androidfw/AssetManager.cpp)

[ResourceTypes.h](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.0_r1/include/androidfw/ResourceTypes.h)
[ResourceTypes.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.0_r1/libs/androidfw/ResourceTypes.cpp)

[Asset.h](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.0_r1/include/androidfw/Asset.h)
[Asset.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.0_r1/libs/androidfw/Asset.cpp)


#### Android5.0资源加载过程

##### addAssetPath

```cpp
// core/jni/android_util_AssetManager.cpp
static jint android_content_AssetManager_addAssetPath(JNIEnv* env, jobject clazz,
                                                       jstring path)
{
    ScopedUtfChars path8(env, path);
    if (path8.c_str() == NULL) {
        return 0;
    }
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return 0;
    }
    int32_t cookie;
    bool res = am->addAssetPath(String8(path8.c_str()), &cookie);
    return (res) ? static_cast<jint>(cookie) : 0;
}

// this guy is exported to other jni routines
AssetManager* assetManagerForJavaObject(JNIEnv* env, jobject obj)
{
    jlong amHandle = env->GetLongField(obj, gAssetManagerOffsets.mObject);
    AssetManager* am = reinterpret_cast<AssetManager*>(amHandle);
    if (am != NULL) {
        return am;
    }
    jniThrowException(env, "java/lang/IllegalStateException", "AssetManager has been finalized!");
    return NULL;
}
```
当我们在 Java 层调用 _android.content.res.AssetManager.addAssetPath()_ 这个方法的时候，其实质是调用 native 层的 android_content_AssetManager_addAssetPath 方法，这个方法会将这个资源 path 添加到 native 层的 AssetManager 对象中。

```cpp
// libs/androidfw/AssetManager.cpp
bool AssetManager::addAssetPath(const String8& path, int32_t* cookie)
{
    ......
    // Skip if we have it already.
    for (size_t i=0; i<mAssetPaths.size(); i++) {
        if (mAssetPaths[i].path == ap.path) {
            if (cookie) {
                *cookie = static_cast<int32_t>(i+1);
            }
            return true;
        }
    }
    ALOGV("In %p Asset %s path: %s", this,
         ap.type == kFileTypeDirectory ? "dir" : "zip", ap.path.string());
    // Check that the path has an AndroidManifest.xml
    Asset* manifestAsset = const_cast<AssetManager*>(this)->openNonAssetInPathLocked(
            kAndroidManifest, Asset::ACCESS_BUFFER, ap);
    if (manifestAsset == NULL) {
        // This asset path does not contain any resources.
        delete manifestAsset;
        return false;
    }
    delete manifestAsset;
    mAssetPaths.add(ap);
    // new paths are always added at the end
    if (cookie) {
        *cookie = static_cast<int32_t>(mAssetPaths.size());
    }
#ifdef HAVE_ANDROID_OS
    // Load overlays, if any
    asset_path oap;
    for (size_t idx = 0; mZipSet.getOverlay(ap.path, idx, &oap); idx++) {
        mAssetPaths.add(oap);
    }
#endif
    if (mResources != NULL) {
        appendPathToResTable(ap);
    }
    return true;
}
```
_AssetManager::addAssetPath()_ 方法先判断该资源是否加载过了，如果加载过了就返回其 cookie；如果还没加载，就将资源 path 添加的 mAssetPaths 列表，并为其分配一个 cookie(即其在列表的 index + 1)，然后 __如果 mResources 不为空(已经加载过资源)，则调用 appendPathToResTable 方法加载(解析)该资源，__ 最后返回这个资源的cookie。

__需要注意的是，Android5.0以下系统，此处不会触发资源加载过程，每个 AssetManager 只会触发一次资源加载的过程。__

实际上，只有当真正要使用 resource 文件的时候才会去触发加载：

```cpp
// libs/androidfw/AssetManager.cpp
const ResTable* AssetManager::getResTable(bool required) const
{
    ResTable* rt = mResources;
    if (rt) {
        return rt;
    }
    // Iterate through all asset packages, collecting resources from each.
    AutoMutex _l(mLock);
    if (mResources != NULL) {
        return mResources;
    }
    if (required) {
        LOG_FATAL_IF(mAssetPaths.size() == 0, "No assets added to AssetManager");
    }
    if (mCacheMode != CACHE_OFF && !mCacheValid) {
        const_cast<AssetManager*>(this)->loadFileNameCacheLocked();
    }
    mResources = new ResTable();
    updateResourceParamsLocked();
    bool onlyEmptyResources = true;
    const size_t N = mAssetPaths.size();
    for (size_t i=0; i<N; i++) {
        bool empty = appendPathToResTable(mAssetPaths.itemAt(i));
        onlyEmptyResources = onlyEmptyResources && empty;
    }
    if (required && onlyEmptyResources) {
        ALOGW("Unable to find resources file resources.arsc");
        delete mResources;
        mResources = NULL;
    }
    return mResources;
}
```

##### appendPathToResTable

```cpp
// libs/androidfw/AssetManager.cpp

bool AssetManager::appendPathToResTable(const asset_path& ap) const {
    Asset* ass = NULL;
    ResTable* sharedRes = NULL;
    bool shared = true;
    bool onlyEmptyResources = true;
    MY_TRACE_BEGIN(ap.path.string());
    Asset* idmap = openIdmapLocked(ap);
    size_t nextEntryIdx = mResources->getTableCount();
    ALOGV("Looking for resource asset in '%s'\n", ap.path.string());
    if (ap.type != kFileTypeDirectory) {
        if (nextEntryIdx == 0) {
            // The first item is typically the framework resources,
            // which we want to avoid parsing every time.
            sharedRes = const_cast<AssetManager*>(this)->
                mZipSet.getZipResourceTable(ap.path);
            if (sharedRes != NULL) {
                // skip ahead the number of system overlay packages preloaded
                nextEntryIdx = sharedRes->getTableCount();
            }
        }
        if (sharedRes == NULL) {
            ass = const_cast<AssetManager*>(this)->
                mZipSet.getZipResourceTableAsset(ap.path);
            if (ass == NULL) {
                ALOGV("loading resource table %s\n", ap.path.string());
                ass = const_cast<AssetManager*>(this)->
                    openNonAssetInPathLocked("resources.arsc",
                                             Asset::ACCESS_BUFFER,
                                             ap);
                if (ass != NULL && ass != kExcludedAsset) {
                    ass = const_cast<AssetManager*>(this)->
                        mZipSet.setZipResourceTableAsset(ap.path, ass);
                }
            }
            
            if (nextEntryIdx == 0 && ass != NULL) {
                // If this is the first resource table in the asset
                // manager, then we are going to cache it so that we
                // can quickly copy it out for others.
                ALOGV("Creating shared resources for %s", ap.path.string());
                sharedRes = new ResTable();
                sharedRes->add(ass, idmap, nextEntryIdx + 1, false);
#ifdef HAVE_ANDROID_OS
                const char* data = getenv("ANDROID_DATA");
                LOG_ALWAYS_FATAL_IF(data == NULL, "ANDROID_DATA not set");
                String8 overlaysListPath(data);
                overlaysListPath.appendPath(kResourceCache);
                overlaysListPath.appendPath("overlays.list");
                addSystemOverlays(overlaysListPath.string(), ap.path, sharedRes, nextEntryIdx);
#endif
                sharedRes = const_cast<AssetManager*>(this)->
                    mZipSet.setZipResourceTable(ap.path, sharedRes);
            }
        }
    } else {
        ALOGV("loading resource table %s\n", ap.path.string());
        ass = const_cast<AssetManager*>(this)->
            openNonAssetInPathLocked("resources.arsc",
                                     Asset::ACCESS_BUFFER,
                                     ap);
        shared = false;
    }
    if ((ass != NULL || sharedRes != NULL) && ass != kExcludedAsset) {
        ALOGV("Installing resource asset %p in to table %p\n", ass, mResources);
        if (sharedRes != NULL) {
            ALOGV("Copying existing resources for %s", ap.path.string());
            mResources->add(sharedRes);
        } else {
            ALOGV("Parsing resources for %s", ap.path.string());
            mResources->add(ass, idmap, nextEntryIdx + 1, !shared);
        }
        onlyEmptyResources = false;
        if (!shared) {
            delete ass;
        }
    } else {
        ALOGV("Installing empty resources in to table %p\n", mResources);
        mResources->addEmpty(nextEntryIdx + 1);
    }
    if (idmap != NULL) {
        delete idmap;
    }
    MY_TRACE_END();
    return onlyEmptyResources;
}
```

appendPathToResTable 方法是加载(解析)资源文件的入口，其中 asset_path 对象里的 idmap 实际上保存的是 idmapPath，因此 _appendPathToResTable()_ 方法首先尝试加载该 idmap 文件，然后再通过 _ResTable::add()_ 方法去加载 resource 文件。_ResTable::add()_ 方法经过一系列预处理(头部解析)之后，最后会调用 _ResTable::parsePackage()_ 方法解析完整的 resource 文件(包括解析idmap文件)。

__idmap是根据新旧资源(overlay)生成的id映射表__：详情可参考_libs/androidfw/ResourceTypes.cpp#ResTable::createIdmap() _。

* 在加载新资源的时候，通过这个表可以将新资源的 entryList 加载到旧资源对应的 typeIndex 上，因为上层代码使用的是旧资源的 resId，需要根据旧资源的 packageId 和 typeId 查找。

* 而在查找资源的时候，通过这个表可以找到新资源的 entryIndex。IdmapEntries 的结构是__“稀疏列表”__：即开头不存在的映射数保存为 entryOffset，中间不存在的映射填充NO_ENTRY(0xffffffff)，尾端不存在的映射不保存，可以通过entryOffset + entryCount的范围进行判断。

* Android5.0以下的系统会根据新旧资源文件生成 idmap，而在Android5.0及以上系统，则将这部分逻辑移除，idmap文件需要根据命令行生成。[Runtime resource overlay](https://android.googlesource.com/platform/frameworks/base/+/48d22323ce39f9aab003dce74456889b6414af55)

##### parsePackage

```cpp
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
```
parsePackage 方法首先会校验数据格式，然后如果存在 idmap 的话，就调用 _parseIdmap()_ 方法解析 idmap 文件，并保存到 idmapEntries 中，并以 idmap 中的 targetPackageId 作为该资源新的packageId。

__如果 idmap 的 targetPackageId 为0，或者该资源的 packageId 为 0(即共享/动态的资源，如WebView的资源)，那么就以 _id = mNextPackageId++_ 的值作为该资源的 packageId，mNextPackageId 默认从 0x02 开始(因为 0x01 是系统资源)。__

```cpp
PackageGroup* group = NULL;
Package* package = new Package(this, header, pkg);
if (package == NULL) {
    return (mError=NO_MEMORY);
}
......
size_t idx = mPackageMap[id];
if (idx == 0) {
    idx = mPackageGroups.size() + 1;
    char16_t tmpName[sizeof(pkg->name)/sizeof(char16_t)];
    strcpy16_dtoh(tmpName, pkg->name, sizeof(pkg->name)/sizeof(char16_t));
    group = new PackageGroup(this, String16(tmpName), id);
    if (group == NULL) {
        delete package;
        return (mError=NO_MEMORY);
    }
    err = mPackageGroups.add(group);
    if (err < NO_ERROR) {
        return (mError=err);
    }
    mPackageMap[id] = static_cast<uint8_t>(idx);
    // Find all packages that reference this package
    size_t N = mPackageGroups.size();
    for (size_t i = 0; i < N; i++) {
        mPackageGroups[i]->dynamicRefTable.addMapping(group->name, static_cast<uint8_t>(group->id));
    }
} else {
    group = mPackageGroups.itemAt(idx - 1);
    if (group == NULL) {
        return (mError=UNKNOWN_ERROR);
    }
}
err = group->packages.add(package);
```

紧接着，为当前 resource 文件创建一个新的 Package，然后通过 mPackageMap 查找是否已存在PackageGroup，如果不存在则创建一个并添加到到 mPackageGroups，然后将这个 packageId : index 的对应关系保存到 mPackageMap 中。__mPackageMap 中保存着每个 packageId 对应的PackageGroup 的 index 信息。而 PackageGroup 保存着 packageId 相同的资源的信息，每个resource 文件都以 Package 的形式保存在 PackageGroup->packages 列表中。__

接下来就要进入正题了--- resource 文件的解析，先来看一下 arsc 文件的格式：

```java
    /*      Arsc struct
     *  +-----------------------+
     *  | Table Header          |
     *  +-----------------------+
     *  | Res string pool       |
     *  +-----------------------+
     *  | Package Header        | 
     *  +-----------------------+
     *  | Type strings          |
     *  +-----------------------+
     *  | Key strings           |
     *  +-----------------------+
     *  | DynamicRefTable chunk | 
     *  +-----------------------+
     *  | Type spec       |     |
     *  |-----------------| * N |
     *  | Type info  * M  |     | 
     *  +-----------------------+
     */
```
parsePackage 方法解析的是 _Type spec_ 和 _Type info_ 这一部分的内容，而前面的头部内容已经在 _ResTable::addInternal()_ 方法解析过了，这里就不再赘述。

 _Type spec_ 保存的是某一类资源的基础信息，而 _Type info_ 则是这一类资源的具体内容，比如String类型的数据。“N" 表示可能有多个不同类型的资源数据，如string，layout，anim等等。而 “M” 则表示每一类的资源，可能会有多种不同的配置(即适配资源)，资源查找的时候，会从这些资源中找到最为匹配的资源。因此从某种意义来说，__Android系统从一开始就支持加载 resId 相同的资源。__

当 ctype == RES_TABLE_TYPE_SPEC_TYPE 时，即开始解析 _Type spec_ 的内容，这个过程会重复 __N__ 次：

```cpp
uint8_t typeIndex = typeSpec->id - 1;
ssize_t idmapIndex = idmapEntries.indexOfKey(typeSpec->id);
if (idmapIndex >= 0) {
    typeIndex = idmapEntries[idmapIndex].targetTypeId() - 1;
}
```
__如果存在 idmap，那么先通过 idmap 将新资源的 typeIndex 转换为为旧资源的 typeIndex。这部分逻辑主要与资源查找的逻辑有关。__

```cpp
TypeList& typeList = group->types.editItemAt(typeIndex);
if (!typeList.isEmpty()) {
    const Type* existingType = typeList[0];
    if (existingType->entryCount != newEntryCount && idmapIndex < 0) {
         ALOGW("ResTable_typeSpec entry count inconsistent: given %d, previously %d", (int) newEntryCount, (int) existingType->entryCount);
         // We should normally abort here, but some legacy apps declare
         // resources in the 'android' package (old bug in AAPT).
     }
}
```
然后查询 _group->types_ 中是否已经有该 typeIndex 的资源存在，如果存在则校验 entryCount 是否一致。__值得注意的是，Android5.0以下系统是不支持多个 packageId 相同的资源加载的，仅支持 overlay 资源加载，因此如果 entryCount 不一致，则会中断解析并退出。而Android5.0及以上系统，则是支持多个 packageId 相同的资源加载，因此此处仅仅只是输出一条warn信息。__

```cpp
Type* t = new Type(header, package, newEntryCount);
t->typeSpec = typeSpec;
t->typeSpecFlags = (const uint32_t*)(((const uint8_t*)typeSpec) + dtohs(typeSpec->header.headerSize));
if (idmapIndex >= 0) {
    t->idmapEntries = idmapEntries[idmapIndex];
}
typeList.add(t);
group->largestTypeId = max(group->largestTypeId, typeSpec->id);
```
entryCount 校验~~一致~~之后，即为当前 typeId 的资源创建一个 Type 类型的数据结构，用以保存接下来要解析的具体资源数据。

 _Type spec_ 解析完之后，紧着就会开始解析 _Type info_ 的内容，此时 ctype == RES_TABLE_TYPE_TYPE。这个过程会重复 __M * N__ 次：

```cpp
uint8_t typeIndex = type->id - 1;
ssize_t idmapIndex = idmapEntries.indexOfKey(type->id);
if (idmapIndex >= 0) {
    typeIndex = idmapEntries[idmapIndex].targetTypeId() - 1;
}
```
首先，依然是根据 idmap 数据，转换 typeIndex，此处不再赘述。

```cpp
TypeList& typeList = group->types.editItemAt(typeIndex);
if (typeList.isEmpty()) {
    ALOGE("No TypeSpec for type %d", type->id);
    return (mError=BAD_TYPE);
}

Type* t = typeList.editItemAt(typeList.size() - 1);
if (newEntryCount != t->entryCount) {
    ALOGE("ResTable_type entry count inconsistent: given %d, previously %d", (int)newEntryCount, (int)t->entryCount);
    return (mError=BAD_TYPE);
}

if (t->package != package) {
    ALOGE("No TypeSpec for type %d", type->id);
    return (mError=BAD_TYPE);
}
t->configs.add(type);
```
紧接着依然是校验 entryCount 是不是一致。注意这里拿到的 Type 对象是上一步RES_TABLE_TYPE_SPEC_TYPE 时创建的，因此理论上来说不会存在 entryCount 不一致的问题，如果存在则说明 resource 文件有问题，因此此处直接中断并退出解析。

entryCount 校验一致之后，就将 ResTable_type 资源数据添加到 t->configs，__t->configs 存储的是同一 typeId  不同维度的资源，即针对不同版本、系统或者分辨率的适配资源。资源查找的时候，就是通过循环遍历这个列表，找到最适合的资源。__

```cpp
if (group->dynamicRefTable.entries().size() == 0) {
    status_t err = group->dynamicRefTable.load((const ResTable_lib_header*) chunk);
    if (err != NO_ERROR) {
        return (mError=err);
    }
    // Fill in the reference table with the entries we already know about.
    size_t N = mPackageGroups.size();
    for (size_t i = 0; i < N; i++) {
        group->dynamicRefTable.addMapping(mPackageGroups[i]->name, mPackageGroups[i]->id);
    }
}
```

最后，如果 ctype == RES_TABLE_LIBRARY_TYPE，说明这部分数据是共享(动态)资源映射表，这是Android5.0新加的数据类型，保存的是 packageName : packageId 的映射关系，表示该资源的 packageId 是动态分配的。其中 packageName 和 packageId 均为其他(共享)资源文件编译时的包名和ID，通过 _DynamicRefTable::load()_ 方法解析之后，保存在 mEntries 中。

然后将已知(即已经解析的resource)的运行时包名和ID的对应关系通过 _DynamicRefTable::addMappings()_ 方法更新并保存到 mLookupTable中。这样我们通过编译时packageId 就可以在 mLookupTable 中查询的到运行时对应的 packageId 了。packageName 只是作为建立这个对应关系的居间key值，并无其他特别意义。

例如 packageId 为 _0x7f_ 的资源文件可以  _@dref/0x7030005_ 的方式引用共享资源文件的资源 _0x7030005_，这个资源文件编译时 packageId 为 _0x70_，但是其运行时 packageId 可能还是 _0x70_，也有可能为其他动态分配的值。另外__当引用的共享(动态)资源的  packageId为 _0x00_ 时，如  _@dref/0x0030005_ 则表示引用的是当前资源文件的共享资源。__

##### DynamicRefTable

DynamicRefTable的数据结构如下：

```cpp
// include/androidfw/ResourceTypes.h
/**
 * Holds the shared library ID table. Shared libraries are assigned package IDs at
 * build time, but they may be loaded in a different order, so we need to maintain
 * a mapping of build-time package ID to run-time assigned package ID.
 *
 * Dynamic references are not currently supported in overlays. Only the base package
 * may have dynamic references.
 */
class DynamicRefTable
{
public:
    DynamicRefTable(uint8_t packageId);
    // Loads an unmapped reference table from the package.
    status_t load(const ResTable_lib_header* const header);
    // Adds mappings from the other DynamicRefTable
    status_t addMappings(const DynamicRefTable& other);
    // Creates a mapping from build-time package ID to run-time package ID for
    // the given package.
    status_t addMapping(const String16& packageName, uint8_t packageId);
    // Performs the actual conversion of build-time resource ID to run-time
    // resource ID.
    inline status_t lookupResourceId(uint32_t* resId) const;
    inline status_t lookupResourceValue(Res_value* value) const;
    inline const KeyedVector<String16, uint8_t>& entries() const {
        return mEntries;
    }
private:
    const uint8_t                   mAssignedPackageId;
    uint8_t                         mLookupTable[256];
    KeyedVector<String16, uint8_t>  mEntries;
};
```
其中，__mEntries 保存的是 packageName : packageId 键值对，而 mLookupTable 保存的则是 packageId : assignedPackageId__。 packageName 和  packageId 是指编译时包名和ID，而 assignedPackageId 是指运行时分配的ID。

#### 附注

Android5.0以下系统不支持加载 packageId 相同的 arsc 资源文件，但是支持加载 overlay 资源。另外Android系统本身是支持加载多个 resId 相同的资源，这些资源就是在同一个 arsc 文件里的适配资源。适配资源是指针对不同分辨率，不同系统版本而配置的资源，Android系统在查找资源的过程中，会从这些资源中找到最为匹配的资源，这也是Android系统底层为适配提供的技术支持。

Android5.0系统为了支持 __splits apk__ 的功能，修改了资源加载的机制，支持加载多个 packageId 相同的 arsc 资源文件，同时也支持加载共享资源(如webview资源，这类资源的packageId是动态变化的，与加载顺序有关，默认从0x02开始)。详情见：[Support multiple resource tables with same package](https://android.googlesource.com/platform/frameworks/base/+/f90f2f8dc36e7243b85e0b6a7fd5a590893c827e)

#### Android5.0 WebView(跨应用加载代码和资源)的加载过程

未完待续

#### " aapt -I " 命令 ( -I  add an existing package to base include set )

未完待续

#### xml文件引用系统资源，如  “@android:dimen/app_icon_size” 

因为__Android系统资源的ID是固定的__，因此，当我们在xml文件里引用系统资源时，aapt会直接将其转换成系统资源的ID，如 _“@android:dimen/app_icon_size”_ 转换成 _“ @ref/0x01050000”_ 。




