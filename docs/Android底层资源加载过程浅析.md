###                                                              Android底层资源加载过程浅析



#### 参考文件

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
当我们在Java层调用_android.content.res.AssetManager.addAssetPath()_ 这个方法的时候，其实质是调用native层的android_content_AssetManager_addAssetPath方法，这个方法会将这个资源path添加到native层的AssetManager对象中。



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
_AssetManager::addAssetPath()_ 方法先判断该资源是否加载过了，如果加载过了就返回其cookie；如果还没加载，就将资源path添加的mAssetPaths列表，并为其分配一个cookie(即其在列表的index + 1)，然后 __如果mResources不为空(已经加载过资源)，则调用appendPathToResTable方法加载(解析)该资源，__ 最后返回这个资源的cookie。

__需要注意的是，Android5.0以下系统，此处不会触发资源加载过程，每个AssetManager只会触发一次资源加载的过程。__



实际上，只有当真正要使用resource文件的时候才会去触发加载：

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

appendPathToResTable方法是加载(解析)资源文件的入口，其中asset_path对象里的idmap实际上保存的是idmapPath，因此 _appendPathToResTable()_ 方法首先尝试加载该idmap文件，然后再通过 _ResTable::add()_ 方法去加载resource文件。_ResTable::add()_ 方法经过一系列预处理(头部解析)之后，最后会调用 _ResTable::parsePackage()_ 方法解析完整的resource文件(包括解析idmap文件)。

__idmap是根据新旧资源(overlay)生成的id映射表__：_详情可参考 libs/androidfw/ResourceTypes.cpp#ResTable::createIdmap() _。

* 在加载新资源的时候，通过这个表可以将新资源的entryList加载到旧资源对应的typeIndex上，因为上层代码使用的是旧资源的resId，需要根据旧资源的packageId和typeId查找。

* 而在查找资源的时候，通过这个表可以找到新资源的entryIndex。IdmapEntries的结构是__“稀疏列表”__：即开头不存在的映射数保存为entryOffset，中间不存在的映射填充空值(或0xffffffff)，尾端不存在的映射不保存，可以通过entryOffset + entryCount的范围进行判断。



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
parsePackage方法首先会校验数据格式，然后如果存在idmap的话，就调用 _parseIdmap()_ 方法解析idmap文件，并保存到idmapEntries中，并以idmap中的targetPackageId作为该资源新的packageId。

__如果idmap的targetPackageId为0，或者该资源的packageId为0(即共享的资源，如WebView的资源)，那么就以 _id = mNextPackageId++_ 的值作为该资源的packageId，mNextPackageId默认从0x02开始(因为0x01是系统资源)。__



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

紧接着，为当前resource文件创建一个新的Package，然后通过mPackageMap查找是否已存在PackageGroup，如果不存在则创建一个并添加到到mPackageGroups，然后将这个packageId : index 的对应关系保存到mPackageMap中。__mPackageMap中保存着每个packageId对应的PackageGroup的index信息。而PackageGroup保存着packageId相同的资源的信息，每个resource文件都以Package的形式保存在PackageGroup->packages列表中。__



接下来就要进入正题了---resource文件的解析，先来看一下arsc文件的格式：

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
parsePackage方法解析的是 _Type spec_ 和 _Type info_ 这一部分的内容，而前面的头部内容已经在 _ResTable::addInternal()_ 方法解析过了，这里就不再赘述。

 _Type spec_ 保存的是某一类资源的基础信息，而 _Type info_ 则是这一类资源的具体内容，比如String类型的数据。“N" 表示可能有多个不同类型的资源数据，如string，layout，anim等等。而 “M” 则表示每一类的资源，可能会有多种不同的配置(即适配资源)，资源查找的时候，会从这些资源中找到最为匹配的资源。因此可以说，__Android系统从一开始就支持加载resId相同的资源。__



当ctype == RES_TABLE_TYPE_SPEC_TYPE时，即开始解析 _Type spec_ 的内容，这个过程会重复 __N__ 次：

```cpp
uint8_t typeIndex = typeSpec->id - 1;
ssize_t idmapIndex = idmapEntries.indexOfKey(typeSpec->id);
if (idmapIndex >= 0) {
    typeIndex = idmapEntries[idmapIndex].targetTypeId() - 1;
}
```
__如果存在idmap，那么先通过idmap将新资源的typeIndex转换为为旧资源的typeIndex。这部分逻辑主要与资源查找的逻辑有关。__

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
然后查询 _group->types_ 中是否已经有该typeIndex的资源存在，如果存在则校验entryCount是否一致。__值得注意的是，Android5.0以下系统是不支持多个packageId相同的资源加载的，仅支持overlay资源加载，因此如果entryCount不一致，则会中断解析并退出。而Android5.0及以上系统，则是支持多个packageId相同的资源加载，因此此处仅仅只是输出一条warn信息。__

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
entryCount校验~~一致~~之后，即为当前typeId的资源创建一个Type类型的数据结构，用以保存接下来要解析的具体资源数据。



 _Type spec_ 解析完之后，紧着就会开始解析 _Type info_ 的内容，此时ctype == RES_TABLE_TYPE_TYPE。这个过程会重复 __M * N__ 次：

```cpp
uint8_t typeIndex = type->id - 1;
ssize_t idmapIndex = idmapEntries.indexOfKey(type->id);
if (idmapIndex >= 0) {
    typeIndex = idmapEntries[idmapIndex].targetTypeId() - 1;
}
```
首先，依然是根据idmap数据，转换typeIndex，此处不再赘述。

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
紧接着依然是校验entryCount是不是一致。注意这里拿到的Type对象是上一步RES_TABLE_TYPE_SPEC_TYPE时创建的，因此理论上来说不会存在entryCount不一致的问题，如果存在则说明resource文件有问题，因此此处直接中断并退出解析。

entryCount校验一致之后，就将ResTable_type资源数据添加到 t->configs，__t->configs 存储的是同一typeId的资源，即针对不同版本、系统或者分辨率的适配资源。资源查找的时候，就是通过循环遍历这个列表，找到最适合的资源。__



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

最后，如果ctype == RES_TABLE_LIBRARY_TYPE，说明这部分数据是共享资源映射表，这是Android5.0新加的数据类型，保存的是 packageName : packageId 的映射关系。其中 packageName 和 packageId 均为其他(共享)资源文件编译时的包名和ID，通过 _DynamicRefTable::load()_ 方法解析之后，保存在mEntries中。

然后将已知(即已经解析的resource)的运行时包名和ID的对应关系通过 _DynamicRefTable::addMappings()_ 方法更新并保存到mLookupTable中。这样我们通过编译时packageId就可以在mLookupTable中查询的到运行时对应的packageId了。packageName只是作为建立这个对应关系的居间key值，并无其他特别用处。

例如packageId为 _0x7f_ 的资源文件可以  _@dref/0x7030005_ 的方式引用共享资源文件的资源 _0x7030005_，这个资源文件编译时packageId为 _0x70_，但是其运行时packageId可能还是 _0x70_，也有可能为其他动态分配的值。另外__当引用的共享资源的packageId为 _0x00_ 时，如  _@dref/0x0030005_ 则表示引用的是当前资源文件的共享资源。__



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
其中，__mEntries保存的是 packageName : packageId 键值对，而mLookupTable保存的则是 packageId : assignedPackageId__。 packageName 和  packageId 是指编译时包名和ID，而 assignedPackageId 是指运行时分配的ID。



#### 附注

Android5.0以下系统不支持加载packageId相同的arsc资源文件，但是支持加载overlay资源。另外Android系统本身是支持加载多个resId相同的资源，这些资源就是在同一个arsc文件里的适配资源。适配资源是指针对不同分辨率，不同系统版本而配置的资源，Android系统在查找资源的过程中，会从这些资源中找到最为匹配的资源，这也是Android系统底层为适配提供的技术支持。

Android5.0系统为了支持__splits apk__的功能，修改了资源加载的机制，支持加载多个packageId相同的arsc资源文件，同时也支持加载共享资源(如webview资源，这类资源的packageId是动态变化的，与加载顺序有关，默认从0x02开始)。详情见：[Support multiple resource tables with same package](https://android.googlesource.com/platform/frameworks/base/+/f90f2f8dc36e7243b85e0b6a7fd5a590893c827e)



#### Android5.0 WebView(跨应用加载代码和资源)的加载过程

未完待续

