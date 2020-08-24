###                       Android 5.0~6.0系统，由于硬件加速引起的内存泄漏问题



#### 实例

![图001](./mml_hardwareaccelerate.png)

从 hprof 文件可以看到：这些bitmap除了一个 __JNI Global__ 的引用之外，已经没有其他的引用了，而正是由于这个 GC root 引用，导致这些 bitmap 无法被及时回收。



####  原因

[DisplayListCanvas.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/libs/hwui/DisplayListCanvas.cpp)

```java
......
void DisplayListCanvas::drawBitmap(const SkBitmap* bitmap, const SkPaint* paint) {
    bitmap = refBitmap(*bitmap);
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawBitmapOp(bitmap, paint));
}
```

硬件加速原理此处不深入讨论，主要是将绘制操作分别保存到 DisplayListData 中，这样如果某个 ChildView 更新了，那么只需更新该 ChildView 对应的DisplayListData 就行，不需要更新整个 ViewTree。例如上面这段__绘制 bitmap __的代码，__DisplayListCanvas 会将 bitmap 保存到 DisplayListData 中__，换句话说就是 DisplayListData 存在对 bitmap 的引用。那么这个DisplayListData 什么时候会释放呢？

[View.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/android/view/View.java)

```java
    ......
    private void cleanupDraw() {
        resetDisplayList();
        if (mAttachInfo != null) {
            mAttachInfo.mViewRootImpl.cancelInvalidate(this);
        }
    }
    
    ......
    @CallSuper
    protected void destroyHardwareResources() {
        resetDisplayList();
    }
    
    ......
    private void resetDisplayList() {
        if (mRenderNode.isValid()) {
            mRenderNode.destroyDisplayListData();
        }
        if (mBackgroundRenderNode != null && mBackgroundRenderNode.isValid()) {
            mBackgroundRenderNode.destroyDisplayListData();
        }
    }
```

从 View 的源代码可以看出，__View 在 detach 或者不可见(GONE或INVISIBLE)的时候，都会调用 resetDisplayList()__。这样来看，似乎逻辑上并没有什么问题，但是我们不妨接着往下看：

[RenderNode.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/android/view/RenderNode.java)

```java
    ......
    public void destroyDisplayListData() {
        if (!mValid) return;
        nSetDisplayListData(mNativeRenderNode, 0);
        mValid = false;
    }
```

[android_view_RenderNode.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/jni/android_view_RenderNode.cpp) 

```java
......
static void android_view_RenderNode_destroyRenderNode(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->decStrong(0);
}

...... 
static void android_view_RenderNode_setDisplayListData(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jlong newDataPtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    DisplayListData* newData = reinterpret_cast<DisplayListData*>(newDataPtr);
    renderNode->setStagingDisplayList(newData);
}
```
RenderNode.java 的代码相对比较简单，可以看出 _destroyDisplayListData()_ 方法最后调用的是 __setStagingDisplayList()__ 方法。

[RenderNode.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/libs/hwui/RenderNode.cpp)

```java
......
// 更新mStagingDisplayListData
void RenderNode::setStagingDisplayList(DisplayListData* data) {
    // 注意这里将mNeedsDisplayListDataSync置为true
    mNeedsDisplayListDataSync = true;
    delete mStagingDisplayListData;
    mStagingDisplayListData = data;
}
```

_setStagingDisplayList()_  方法的逻辑也相对比较简单，但是问题恰恰就是因为这个逻辑太过于简单了：我们发现 _setStagingDisplayList()_ 方法仅仅只是清除了 __mStagingDisplayListData__，然而这个只是 staging 状态的缓存，对于已经绘制过的 View 来说，真正保存数据的是 _mDisplayListData_，而 __mDisplayListData 并没有被清除__。那么 _mDisplayListData_ 什么时候会被清除呢？



[ViewRootImpl.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/android/view/ViewRootImpl.java)

[ThreadedRenderer.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/android/view/ThreadedRenderer.java)

[android_view_ThreadedRenderer.cpp)](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/jni/android_view_ThreadedRenderer.cpp)

[RenderProxy.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/libs/hwui/renderthread/RenderProxy.cpp)

[DrawFrameTask.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/libs/hwui/renderthread/DrawFrameTask.cpp)

```cpp
// 绘制任务
void DrawFrameTask::run() {
    ......
    {
        TreeInfo info(TreeInfo::MODE_FULL, mRenderThread->renderState());
        canUnblockUiThread = syncFrameState(info);
        canDrawThisFrame = info.out.canDrawThisFrame;
    }
    ......
}

bool DrawFrameTask::syncFrameState(TreeInfo& info) {
    ......
    for (size_t i = 0; i < mLayers.size(); i++) {
        mContext->processLayerUpdate(mLayers[i].get());
    }
    mLayers.clear();
    mContext->prepareTree(info, mFrameInfo, mSyncQueued);
    .....
}
```

[RenderNode.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/libs/hwui/RenderNode.cpp)

```cpp

void RenderNode::prepareTree(TreeInfo& info) {
    ......
    prepareTreeImpl(info, functorsNeedLayer);
}

void RenderNode::prepareTreeImpl(TreeInfo& info, bool functorsNeedLayer) {
    ......
    prepareLayer(info, animatorDirtyMask);
    if (info.mode == TreeInfo::MODE_FULL) {
        pushStagingDisplayListChanges(info);
    }
    prepareSubTree(info, childFunctorsNeedLayer, mDisplayListData);
    pushLayerUpdate(info);
    info.damageAccumulator->popTransform();
}

// 1、重新绘制
void RenderNode::pushStagingDisplayListChanges(TreeInfo& info) {
    if (mNeedsDisplayListDataSync) {
        // 注意这里将mNeedsDisplayListDataSync置为false
        mNeedsDisplayListDataSync = false;
        if (mStagingDisplayListData) {
            for (size_t i = 0; i < mStagingDisplayListData->children().size(); i++) {
                mStagingDisplayListData->children()[i]->mRenderNode->incParentRefCount();
            }
        }
        ......
        // 更新DisplayListData
        deleteDisplayListData();
        ......
        mDisplayListData = mStagingDisplayListData;
        mStagingDisplayListData = nullptr;
        ......
    }
}

// 释放资源，清除DisplayList
void RenderNode::deleteDisplayListData() {
    if (mDisplayListData) {
        for (size_t i = 0; i < mDisplayListData->children().size(); i++) {
            // 父节点释放对子节点的引用
            mDisplayListData->children()[i]->mRenderNode->decParentRefCount();
        }
        if (mDisplayListData->functors.size()) {
            Caches::getInstance().unregisterFunctors(mDisplayListData->functors.size());
        }
    }
    // 清除mDisplayListData
    delete mDisplayListData;
    mDisplayListData = nullptr;
}

void RenderNode::decParentRefCount() {
    LOG_ALWAYS_FATAL_IF(!mParentCount, "already 0!");
    mParentCount--;
    if (!mParentCount) {
        destroyHardwareResources();
    }
}

void RenderNode::destroyHardwareResources() {
    if (mLayer) {
        LayerRenderer::destroyLayer(mLayer);
        mLayer = nullptr;
    }
    if (mDisplayListData) {
        for (size_t i = 0; i < mDisplayListData->children().size(); i++) {
            mDisplayListData->children()[i]->mRenderNode->destroyHardwareResources();
        }
        if (mNeedsDisplayListDataSync) {
            deleteDisplayListData();
        }
    }
}

// 2、析构函数
RenderNode::~RenderNode() {
    deleteDisplayListData();
    delete mStagingDisplayListData;
    if (mLayer) {
        ALOGW("Memory Warning: Layer %p missed its detachment, held on to for far too long!", mLayer);
        mLayer->postDecStrong();
        mLayer = nullptr;
    }
}
```

从代码上看，有三个场景会__清除 mDisplayListData__：

* 子节点 View __刷新重绘__时，清除旧数据

* 子节点 RenderNode 执行__析构函数__的时候

* 父节点清除 mDisplayListData 的时候，如果子节点的 mParentCount 为 0（即子节点没有关联到任何父节点，这种情况一般出现在View被移除、隐藏、超出显示区域的时候），__并且 mNeedsDisplayListDataSync 为 true(即子节点数据刷新待重绘)__。而事实上，这种场景建立在前面两种场景之上的。

问题就出在 __setStagingDisplayList()__ 方法和 __decParentRefCount()__ 方法调用的 __先后顺序__ 上。如果先调用了 decParentRefCount() 方法，而此时 mNeedsDisplayListDataSync 为 false，则不会清除 mDisplayListData，这种情况下就只能指望析构函数了。



#### 总结

出现场景：__在某一个View不会再次绘制的情况下，才去释放其对 bitmap 资源的引用__。最常见的场景例如：对于 ViewPager + FrameLayout + ImageView 这样结构的布局，如果我们在 FrameLayout 移出显示范围之后，才去释放 bitmap 资源，那么这个 bitmap 资源将无法及时被回收，只有等到FrameLayout 被复用或者回收的时候，这个bitmap 资源才能被回收。

幸运的是，绝大多数场景下，要么不需要使用 ViewPager，要么 FrameLayout 是复用的，要么及时刷新重绘，因此即使有内存泄漏也只是短暂的（退出页面的时候也可以被回收）。




#### 解决

尽管这个问题不是特别严重，但是依然会__占用消耗内存资源__，因此针对该问题出现的场景，有两种方案可以选择：

* 其一，在父节点重绘(注意这里__特指即释放子节点引用的那次重绘__)之前，及时释放资源，例 [ViewPagerCompat.java](./ViewPagerCompat.java)

* 其二，复用或者释放相关的 View，尽管这样做可以减少内存泄漏，但是依然还是存在内存泄漏。

另外该问题只出现在 5.0 和 6.0 的系统上，7.0之后的版本Google官方已做修复，详情见：

[Free DisplayListData for Views with GONE parents](https://android.googlesource.com/platform/frameworks/base/+/9dea0d53f598d8fa98d9b50899fc9c7559f7a1a1)

```java
void RenderNode::setStagingDisplayList(DisplayList* displayList) {
    mNeedsDisplayListSync = true;
    delete mStagingDisplayList;
    mStagingDisplayList = displayList;
    // If mParentCount == 0 we are the sole reference to this RenderNode,
    // so immediately free the old display list
    if (!mParentCount && !mStagingDisplayList) {
        deleteDisplayList();
    }
}
```
可以看到，关键正是在 _setStagingDisplayLis()_ 方法增加了判断及清除 _mDisplayListData_ 的逻辑。

附其他相关commit链接：

[Fix some edge cases](https://android.googlesource.com/platform/frameworks/base/+/51f2d606dcbfba3cc5b03dfea37c1304b91c232f)

[Add a callback for rendernode parentcount=0](https://android.googlesource.com/platform/frameworks/base/+/44b49f070aafe8ad44efae87341121cce49ff11c)



__Android 5.0以下系统暂未发现该问题，是因为Android 5.0对硬件加速模块做了一次较大的重构__，详见链接 [Switch DisplayListData to a staging model](https://android.googlesource.com/platform/frameworks/base/+/8de65a8e05285df52a1e6f0c1d5616dd233298a7)



但是从log上看，__Android 4.4以下也存在类似问题__，此处不再讨论，详见链接  [Fix hardware layers lifecycle](https://android.googlesource.com/platform/frameworks/base/+/46bfc4811094e5b1e3196246e457d4c6b58332ec)



#### ChildView 不会被绘制(不是内存泄漏)的场景例举

首先，无需赘言，当 ChildView __被移除__的时候，这个 ChildView 将不会被绘制。

[ViewGroup.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/android/view/ViewGroup.java)

```java
    @Override
    protected void dispatchDraw(Canvas canvas) {
    ......
        for (int i = 0; i < childrenCount; i++) {
            int childIndex = customOrder ? getChildDrawingOrder(childrenCount, i) : i;
            final View child = (preorderedList == null)
                    ? children[childIndex] : preorderedList.get(childIndex);
            if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE
                || child.getAnimation() != null) {
                more |= drawChild(canvas, child, drawingTime);
            }
        }
    ......
    }
```

其次，从 ViewGroup 的 dispatchDraw 方法来看，如果其 ChildView __不可见且不是在执行动画__，则该 ChildView 不会被绘制。


[View.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/android/view/View.java)

```java
    boolean draw(Canvas canvas, ViewGroup parent, long drawingTime) {
    ......
        concatMatrix |= !childHasIdentityMatrix;
        // Sets the flag as early as possible to allow draw() implementations
        // to call invalidate() successfully when doing animations
        mPrivateFlags |= PFLAG_DRAWN;
        if (!concatMatrix &&
                (parentFlags & (ViewGroup.FLAG_SUPPORT_STATIC_TRANSFORMATIONS |
                        ViewGroup.FLAG_CLIP_CHILDREN)) == ViewGroup.FLAG_CLIP_CHILDREN &&
                canvas.quickReject(mLeft, mTop, mRight, mBottom, Canvas.EdgeType.BW) &&
                (mPrivateFlags & PFLAG_DRAW_ANIMATION) == 0) {
            mPrivateFlags2 |= PFLAG2_VIEW_QUICK_REJECTED;
            return more;
        }
        mPrivateFlags2 &= ~PFLAG2_VIEW_QUICK_REJECTED;
    ......
    }

```

另外，从 View 的 draw（drawChild 的时候调用）方法可以看出，当 ChildView __超出显示区域__ 的时候，该 ChildView 也不会被绘制。需要注意的是：当 ViewGroup 设置 __clipChildren__ 为 false 的时候，因为这种场景无法判断这个 ChildView 的显示区域，因此这种情况下，会尝试绘制该 ChildView。



#### 附： ViewPagerCompat：

```java
package com.xosp.hwademo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.v4.view.ViewPager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * @author xuqingqi01@gmail.com
 * 兼容处理 Android5.0-6.0系统，由于硬件加速引起的内存泄漏的问题
 */
public class ViewPagerCompat extends ViewPager {

    private static final String TAG = "ViewPagerCompat";

    private static final boolean TRICK_ENABLED = false;

    private static final int PFLAG2_VIEW_QUICK_REJECTED_COPY = 0x10000000; //View.PFLAG2_VIEW_QUICK_REJECTED

    private static final int LOLLIPOP = 21; // Build.VERSION_CODES.LOLLIPOP

    private static final int MARSHMALLOW = 23; // Build.VERSION_CODES.M

    public ViewPagerCompat(Context context) {
        super(context);
    }

    public ViewPagerCompat(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        final boolean more = super.drawChild(canvas, child, drawingTime);
        if (!TRICK_ENABLED) {
            return more;
        }

        if (Build.VERSION.SDK_INT >= LOLLIPOP && Build.VERSION.SDK_INT <= MARSHMALLOW
                && canvas.isHardwareAccelerated() && child.isHardwareAccelerated()
                && isViewQuickRejected(child)
        ) {
            resetDisplayList(child);
        }

        return more;
    }

    /**
     * check whether the view failed the quickReject() check in draw()
     */
    private static boolean isViewQuickRejected(@NonNull View view) {
        try {
            Field field = View.class.getDeclaredField("mPrivateFlags2");
            field.setAccessible(true);
            int flags = (int) field.get(view);
            return (flags & PFLAG2_VIEW_QUICK_REJECTED_COPY ) == PFLAG2_VIEW_QUICK_REJECTED_COPY;
        } catch (Exception ignore) {
            //ignore.printStackTrace();
        }
        return false;
    }

    /**
     * release display list data
     */
    @SuppressLint("PrivateApi")
    private static void resetDisplayList(@NonNull View view) {
        Log.d(TAG, "resetDisplayList, view=" + view);
        try {
            Method method = View.class.getDeclaredMethod("resetDisplayList");
            method.setAccessible(true);
            method.invoke(view);
        } catch (Exception ignore) {
            //ignore.printStackTrace();
        }
    }

}

```
