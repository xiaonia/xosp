### 插件styleable资源问题



#### 在宿主中定义和使用styleable资源

``` xml
    <declare-styleable name="ContentView">
        <attr name="layout_empty" format="reference"/>
        <attr name="layout_error" format="reference"/>
        <attr name="layout_loading" format="reference"/>
    </declare-styleable>
```

```java
    protected void init(Context context, AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ContentView);
            int emptyResId = a.getResourceId(R.styleable.ContentView_layout_empty, 0);
            int errorResId = a.getResourceId(R.styleable.ContentView_layout_error, 0);
            int loadingResId = a.getResourceId(R.styleable.ContentView_layout_loading, 0);
            a.recycle();
        }
    }
```

__因为styleable资源是定义在宿主中，而ContentView也是定义在宿主中，因此ContentView使用的都是宿主的资源ID。__

#### 在插件中应用styleable资源

```xml
    <com.test.ContentView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:layout_loading="@drawable/test_drawable"
        app:layout_error="@drawable/test_drawable"
        app:layout_empty="@drawable/test_drawable"/>
```

__在插件中使用该styleable资源的时候，如果插件资源独立，也就是为这个styleable(attr)重新分配资源ID，那么该资源ID将与宿主资源ID不一致。__



#### Android底层AXML解析

[core/jni/android_util_AssetManager.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.0_r1/core/jni/android_util_AssetManager.cpp)
```cpp

static jboolean android_content_AssetManager_applyStyle(JNIEnv* env, jobject clazz,
                                                        jlong themeToken,
                                                        jint defStyleAttr,
                                                        jint defStyleRes,
                                                        jlong xmlParserToken,
                                                        jintArray attrs,
                                                        jintArray outValues,
                                                        jintArray outIndices)
{
......
// Now iterate through all of the attributes that the client has requested,
    // filling in each with whatever data we can find.
    ssize_t block = 0;
    uint32_t typeSetFlags;
    for (jsize ii=0; ii<NI; ii++) {
        const uint32_t curIdent = (uint32_t)src[ii];
        DEBUG_STYLES(ALOGI("RETRIEVING ATTR 0x%08x...", curIdent));
        // Try to find a value for this attribute...  we prioritize values
        // coming from, first XML attributes, then XML style, then default
        // style, and finally the theme.
        value.dataType = Res_value::TYPE_NULL;
        value.data = 0;
        typeSetFlags = 0;
        config.density = 0;
        // Skip through XML attributes until the end or the next possible match.
        while (ix < NX && curIdent > curXmlAttr) {
            ix++;
            curXmlAttr = xmlParser->getAttributeNameResID(ix);
        }
        // Retrieve the current XML attribute if it matches, and step to next.
        if (ix < NX && curIdent == curXmlAttr) {
            block = kXmlBlock;
            xmlParser->getAttributeValue(ix, &value);
            ix++;
            curXmlAttr = xmlParser->getAttributeNameResID(ix);
            DEBUG_STYLES(ALOGI("-> From XML: type=0x%x, data=0x%08x",
                    value.dataType, value.data));
        }
        ......
    }
......
}
```

[libs/androidfw/ResourceTypes.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.0_r1/libs/androidfw/ResourceTypes.cpp)
```cpp
uint32_t ResXMLParser::getAttributeNameResID(size_t idx) const
{
    int32_t id = getAttributeNameID(idx);
    if (id >= 0 && (size_t)id < mTree.mNumResIds) {
        return dtohl(mTree.mResIds[id]);
    }
    return 0;
}


int32_t ResXMLParser::getAttributeNameID(size_t idx) const
{
    if (mEventCode == START_TAG) {
        const ResXMLTree_attrExt* tag = (const ResXMLTree_attrExt*)mCurExt;
        if (idx < dtohs(tag->attributeCount)) {
            const ResXMLTree_attribute* attr = (const ResXMLTree_attribute*)
                (((const uint8_t*)tag)
                 + dtohs(tag->attributeStart)
                 + (dtohs(tag->attributeSize)*idx));
            return dtohl(attr->name.index);
        }
    }
    return -1;
}

status_t ResXMLTree::setTo(const void* data, size_t size, bool copyData)
{
    ......
        if (type == RES_STRING_POOL_TYPE) {
            mStrings.setTo(chunk, size);
        } else if (type == RES_XML_RESOURCE_MAP_TYPE) {
            mResIds = (const uint32_t*)
                (((const uint8_t*)chunk)+dtohs(chunk->headerSize));
            mNumResIds = (dtohl(chunk->size)-dtohs(chunk->headerSize))/sizeof(uint32_t);
        } else if (type >= RES_XML_FIRST_CHUNK_TYPE
                   && type <= RES_XML_LAST_CHUNK_TYPE) {
            ......
            break;
        } else {
            XML_NOISY(printf("Skipping unknown chunk!\n"));
        }
    ......
}
```

然而我们发现，__Android系统在解析Android binary xml文件的时候，是根据attr资源的ID进行匹配和解析__，那么由上文可知：

* ContentView使用宿主的styleable资源ID进行解析

* 插件重新为attr资源分配资源ID

这种情况产生的结果就是__该styleable(attr)资源无法被正确解析__




#### 解决

* 保持宿主和插件styleable(attr)资源ID一致
* 禁止在插件中使用宿主定义的styleable资源
* 其他待续






