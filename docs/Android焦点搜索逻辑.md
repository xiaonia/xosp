###                                                                        Android焦点搜索逻辑



#### 引言

上一篇文章[Android焦点分发逻辑](./Android焦点分发逻辑.md)，我们简单的梳理了一下焦点分发的逻辑，这一次，我们再详细的探讨一下焦点搜索的逻辑。



#### focusSearch 

在上一篇文章[Android焦点分发逻辑](./Android焦点分发逻辑.md)，我们说过焦点的搜索都是通过调用 _focusSearch(...)_ 方法，那么这个方法都做了些什么呢？

[ViewRootImpl.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/android/view/ViewRootImpl.java)
```java
    public View focusSearch(View focused, int direction) {
        checkThread();
        if (!(mView instanceof ViewGroup)) {
            return null;
        }
        return FocusFinder.getInstance().findNextFocus((ViewGroup) mView, focused, direction);
    }
```
我们先来看看 _ViewRootImpl_  这个类的 _focusSearch(...)_ 方法，如上代码所示： 对于 _ViewRootImpl_ 这个类来说， _mView_ 就是我们添加到 _Window_ 的 _RootView_，因此正常来说 _mView_ 都是 _ViewGroup_ 类型，所以这个 _focusSearch(...)_ 方法最后会将查找逻辑外包给了 _FocusFinder.getInstance().findNextFocus(...)_。 


[View.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/android/view/View.java)
```java
    /**
     * Find the nearest view in the specified direction that can take focus.
     * This does not actually give focus to that view.
     */
    public View focusSearch(@FocusRealDirection int direction) {
        if (mParent != null) {
            return mParent.focusSearch(this, direction);
        } else {
            return null;
        }
    }
```

接着我们再来看看 _View_ 的 _focusSearch(...)_ 方法： _View_ 的 _focusSearch(...)_ 方法逻辑很简单，直接调用 _mParent.focusSearch(...)_ 方法，向上层传递。

[ViewGroup.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/android/view/ViewGroup.java)
```java
    /**
     * Find the nearest view in the specified direction that wants to take
     * focus.
     */
    public View focusSearch(View focused, int direction) {
        if (isRootNamespace()) {
            // root namespace means we should consider ourselves the top of the
            // tree for focus searching; otherwise we could be focus searching
            // into other tabs.  see LocalActivityManager and TabHost for more info
            return FocusFinder.getInstance().findNextFocus(this, focused, direction);
        } else if (mParent != null) {
            return mParent.focusSearch(focused, direction);
        }
        return null;
    }
```
显然最后做处理的还是 _ViewGroup_ 的 _focusSearch(...)_ 方法：

如果 _isRootNamespace()_ 是 _true_ ，同样也是将查找逻辑外包给 _FocusFinder.getInstance().findNextFocus(...)_。而如果 _isRootNamespace()_ 是 _false_，依然还是调用 _mParent.focusSearch(...)_ 向上一级传递。那么 _isRootNamespace()_又是表示什么呢？实际上，如其名，_isRootNamespace()_ 表示当前 _ViewGroup_ 是否是 ViewTree 的根节点：

[View.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/android/view/View.java)

```java
    public void setIsRootNamespace(boolean isRoot) {
        if (isRoot) {
            mPrivateFlags |= PFLAG_IS_ROOT_NAMESPACE;
        } else {
            mPrivateFlags &= ~PFLAG_IS_ROOT_NAMESPACE;
        }
    }
    
    public boolean isRootNamespace() {
        return (mPrivateFlags&PFLAG_IS_ROOT_NAMESPACE) != 0;
    }
```
[PhoneWindow.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/com/android/internal/policy/PhoneWindow.java)
```java
    private void installDecor() {
        if (mDecor == null) {
            mDecor = generateDecor();
            mDecor.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            mDecor.setIsRootNamespace(true);
            if (!mInvalidatePanelMenuPosted && mInvalidatePanelMenuFeatures != 0) {
                mDecor.postOnAnimation(mInvalidatePanelMenuRunnable);
            }
        }
        ......
    }
```
从 _PhoneWindow_ 的源代码可以看出 ，_PhoneWindow$DecorView_ 就是ViewTree上 _isRootNamespace()_ 为 _true_ 的View，也就是 ViewTree 的根节点。

综上，__*focusSearch(...)* 方法最终都是将查找逻辑外包给 *FocusFinder*__。



#### FocusFinder

是时候进入正题了，我们来分析一下 _FocusFinder_ 是如何搜集并筛选"候选View"的。

[FocusFinder.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/android/view/FocusFinder.java)

```java
private View findNextFocus(ViewGroup root, View focused, Rect focusedRect, int direction) {
        View next = null;
        if (focused != null) {
            // 查找是否自定义了焦点分发逻辑
            next = findNextUserSpecifiedFocus(root, focused, direction);
        }
        if (next != null) {
            return next;
        }
        // 搜集所有(可获焦)的候选View
        ArrayList<View> focusables = mTempList;
        try {
            focusables.clear();
            root.addFocusables(focusables, direction);
            if (!focusables.isEmpty()) {
                // 找出最合适的候选View
                next = findNextFocus(root, focused, focusedRect, direction, focusables);
            }
        } finally {
            focusables.clear();
        }
        return next;
    }
```

首先，_findUserSetNextFocus(...)_ 会查找是否存在自定义的焦点分发逻辑，即通过 _setNextFocusXxxxId(...)_ 方法设置的焦点分发逻辑，如果存在则返回该View。也就是说我们__可以通过_setNextFocusXxxxId(...)_这种方式来自定义焦点分发逻辑__。

其次，如果没有自定义焦点分发逻辑，那么就会走系统默认的焦点分发逻辑，主要分两步：

* 一是，搜集所有的候选View，即通过递归的调用 _addFocusables(...)_ 方法搜集所有可获焦的View

* 二是，遍历所有的候选View，即通过调用 _findNextFocus(...)_ 方法筛选出最合适的候选View

  

#### addFocusables

[View.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/android/view/View.java)
```java
    /**
     * Adds any focusable views that are descendants of this view (possibly
     * including this view if it is focusable itself) to views. 
     */
    public void addFocusables(ArrayList<View> views, @FocusDirection int direction,
            @FocusableMode int focusableMode) {
        if (views == null) {
            return;
        }
        if (!isFocusable()) {
            return;
        }
        if ((focusableMode & FOCUSABLES_TOUCH_MODE) == FOCUSABLES_TOUCH_MODE
                && isInTouchMode() && !isFocusableInTouchMode()) {
            return;
        }
        views.add(this);
    }
```

_View_ 类中的 _addFocusables(...)_ 方法相对简单：判断当前View是否可获焦，如果可以获焦，则将当前View添加到 _focusables_ 中。


[ViewGroup.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/android/view/ViewGroup.java)
```java
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        final int focusableCount = views.size();
        final int descendantFocusability = getDescendantFocusability();
        if (descendantFocusability != FOCUS_BLOCK_DESCENDANTS) {
            if (shouldBlockFocusForTouchscreen()) {
                focusableMode |= FOCUSABLES_TOUCH_MODE;
            }
            // 添加子View
            final int count = mChildrenCount;
            final View[] children = mChildren;
            for (int i = 0; i < count; i++) {
                final View child = children[i];
                if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE) {
                    child.addFocusables(views, direction, focusableMode);
                }
            }
        }
        // we add ourselves (if focusable) in all cases except for when we are
        // FOCUS_AFTER_DESCENDANTS and there are some descendants focusable.  this is
        // to avoid the focus search finding layouts when a more precise search
        // among the focusable children would be more interesting.
        if ((descendantFocusability != FOCUS_AFTER_DESCENDANTS
                // No focusable descendants
                || (focusableCount == views.size())) &&
                (isFocusableInTouchMode() || !shouldBlockFocusForTouchscreen())) {
            // 添加当前View
            super.addFocusables(views, direction, focusableMode);
        }
    }
```
而 _ViewGroup_ 类中的 _addFocusables(...)_ 方法则相对复杂。它会根据我们设置的 _descendantFocusability_ 策略做不同的处理：

* __*FOCUS_BLOCK_DESCENDANTS* 即拦截ChildView获焦__：仅调用当前View的 _addFocusables(...)_ 方法(即仅添加当前View)。

* __*FOCUS_BEFORE_DESCENDANTS* 即ParentView优先获焦__：先递归调用所有可见ChildView的 _addFocusables(...)_ 方法(即添加可见ChildView)，然后调用当前View的 _addFocusables(...)_ 方法(即添加当前View)。
* __*FOCUS_AFTER_DESCENDANTS* 即ChildView优先获焦__：先递归调用所有可见ChildView的 _addFocusables(...)_ 方法(即添加可见ChildView)，然后__如果没有添加任何ChildView__，才调用当前View的 _addFocusables(...)_ 方法(即添加当前View)。

因此__如果我们需要自定义焦点分发逻辑，也可以通过覆写 _addFocusables(...)_ 方法来实现。 __



#### findNextFocus

[FocusFinder.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r81/core/java/android/view/FocusFinder.java)

```java
private View findNextFocus(ViewGroup root, View focused, Rect focusedRect,
            int direction, ArrayList<View> focusables) {
        // 初始化 focusedRect
        if (focused != null) {
            if (focusedRect == null) {
                focusedRect = mFocusedRect;
            }
            // fill in interesting rect from focused
            focused.getFocusedRect(focusedRect);
            root.offsetDescendantRectToMyCoords(focused, focusedRect);
        } else {
            if (focusedRect == null) {
                focusedRect = mFocusedRect;
                // make up a rect at top left or bottom right of root
                switch (direction) {
                    case View.FOCUS_RIGHT:
                    case View.FOCUS_DOWN:
                        // 左上角
                        setFocusTopLeft(root, focusedRect);
                        break;
                    case View.FOCUS_FORWARD:
                        if (root.isLayoutRtl()) {
                            setFocusBottomRight(root, focusedRect);
                        } else {
                            setFocusTopLeft(root, focusedRect);
                        }
                        break;
                    case View.FOCUS_LEFT:
                    case View.FOCUS_UP:
                        // 右下角
                        setFocusBottomRight(root, focusedRect);
                        break;
                    case View.FOCUS_BACKWARD:
                        if (root.isLayoutRtl()) {
                            setFocusTopLeft(root, focusedRect);
                        } else {
                            setFocusBottomRight(root, focusedRect);
                        break;
                    }
                }
            }
        }
        // 筛选候选View
        switch (direction) {
            case View.FOCUS_FORWARD:
            case View.FOCUS_BACKWARD:
                return findNextFocusInRelativeDirection(focusables, root, focused, focusedRect,
                        direction);
            case View.FOCUS_UP:
            case View.FOCUS_DOWN:
            case View.FOCUS_LEFT:
            case View.FOCUS_RIGHT:
                return findNextFocusInAbsoluteDirection(focusables, root, focused,
                        focusedRect, direction);
            default:
                throw new IllegalArgumentException("Unknown direction: " + direction);
        }
    }
```
_findNextFocus(...)_方法同样是分两步走，__首先，计算当前焦点的获焦区域__，为什么一定要有这个获焦区域呢？因为后续我们判断哪个候选View更合适的时候，需要找到位置与这个区域最近的View(包括__方位和距离__)：

* 如果当前存在焦点，则直接通过 _getFocusedRect(...)_获取其获焦区域，并将其转换到 _RootView_ 的坐标系下(一般是PhoneWindow$DecorView)。

* 如果不存在焦点，则根据焦点分发事件初始化一个默认的获焦区域：

    * 对于_FOCUS_RIGHT_ 或 _FOCUS_DOWN_ 事件则设置为 _RootView_ 左上角的位置(包含scrollX和scrollY)。

    * 而对于 _FOCUS_LEFT_ 或  _FOCUS_UP_ 事件则设置为 _RootView_ 右下角的位置(包含scrollX和scrollY)。

    * 另外，对于 _FOCUS_FORWARD_ 或 _FOCUS_BACKWARD_事件，这个位置则跟布局方向( _isLayoutRtl()_ )息息相关，此处不再详述。 

__接着，开始筛选最合适的候选View__，如果是 _FOCUS_FORWARD_ 或 _FOCUS_BACKWARD_ 事件，则调用 _findNextFocusInRelativeDirection(...)_ 方法进行比较筛选；其他的事件则调用 _findNextFocusInAbsoluteDirection(...)_进行比较筛选。从方法名我们也可以看出来，向前与向后是筛选出相对位置最合适的View，而上下左右则是筛选出绝对位置最合适的View。



#### findNextFocusInRelativeDirection

```java
    private View findNextFocusInRelativeDirection(ArrayList<View> focusables, ViewGroup root,
            View focused, Rect focusedRect, int direction) {
        try {
            // Note: This sort is stable.
            // 根据 DrawingRect 进行排序
            mSequentialFocusComparator.setRoot(root);
            mSequentialFocusComparator.setIsLayoutRtl(root.isLayoutRtl());
            Collections.sort(focusables, mSequentialFocusComparator);
        } finally {
            mSequentialFocusComparator.recycle();
        }
        // 查找相对位置(前后)的View
        final int count = focusables.size();
        switch (direction) {
            case View.FOCUS_FORWARD:
                return getNextFocusable(focused, focusables, count);
            case View.FOCUS_BACKWARD:
                return getPreviousFocusable(focused, focusables, count);
        }
        return focusables.get(count - 1);
    }
```

首先，_mSequentialFocusComparator_ 根据每个候选View的 __getDrawingRect(...)__ (统一变换到 _RootView_ 的坐标系) 进行排序，当然这个顺序跟布局方向( _isLayoutRtl()_ )是有关的:

* 根据 _DrawingRect.top_ 排序：按 _top_ 值升序排列。

* 根据 _DrawingRect.left_ 排序，这个顺序跟布局方向有关：如果布局方向是从左到右则按 _left_ 值升序排列；如果布局方向是从右到左则按 _left_ 值降序排列。

* 根据  _DrawingRect.bottom_ 排序：按 _bottom_ 值升序排列。

* 根据  _DrawingRect.right_ 排序，这个也跟布局方向有关：如果布局方向是从左到右则按 _right_ 值升序排列；如果布局方向是从右到左则按 _right_ 值降序排列。

接着，调用 _getNextFocusable(...)_ (或_getPreviousFocusable(...)_)方法找到当前获焦View的前一个(或后一个)候选View，如果当前获焦的View不在 _focusables_ 里面，则返回第一个(或最后一个)候选View。



#### findNextFocusInAbsoluteDirection

```java
    View findNextFocusInAbsoluteDirection(ArrayList<View> focusables, ViewGroup root, View focused,
            Rect focusedRect, int direction) {
        // initialize the best candidate to something impossible
        // (so the first plausible view will become the best choice)
        // 初始化 mBestCandidateRect
        mBestCandidateRect.set(focusedRect);
        switch(direction) {
            case View.FOCUS_LEFT:
                mBestCandidateRect.offset(focusedRect.width() + 1, 0);
                break;
            case View.FOCUS_RIGHT:
                mBestCandidateRect.offset(-(focusedRect.width() + 1), 0);
                break;
            case View.FOCUS_UP:
                mBestCandidateRect.offset(0, focusedRect.height() + 1);
                break;
            case View.FOCUS_DOWN:
                mBestCandidateRect.offset(0, -(focusedRect.height() + 1));
        }
        // 对比筛选最优解
        View closest = null;
        int numFocusables = focusables.size();
        for (int i = 0; i < numFocusables; i++) {
            View focusable = focusables.get(i);
            // only interested in other non-root views
            if (focusable == focused || focusable == root) continue;
            // get focus bounds of other view in same coordinate system
            focusable.getFocusedRect(mOtherRect);
            root.offsetDescendantRectToMyCoords(focusable, mOtherRect);
            if (isBetterCandidate(direction, focusedRect, mOtherRect, mBestCandidateRect)) {
                mBestCandidateRect.set(mOtherRect);
                closest = focusable;
            }
        }
        return closest;
    }
```

_findNextFocusInAbsoluteDirection(...)_ 方法的逻辑稍微复杂一点，因为这个方法涉及筛选出方位和距离最合适的View。

__首先，计算出一个极限区域作为初始 *mBestCandidateRect* __，当然因为这个区域完全不符合条件，因此任何满足条件的候选View都将取代它(这就好比我们要筛选最小值时，将这个值初始化为最大值，然后再去跟其他值比较)。

那么怎么计算呢？找到相反方向第一个不满足条件的区域即可。例如焦点事件为 _FOCUS_LEFT_ 时，则将当前焦点区域往右侧平移 _focusedRect.width() + 1_，这个即是极限区域。

__接着，循环遍历 *focusables* 里所有的View(不包括当前FocusedView和RootView)，找到方位和距离最合适的候选View__。__注意，这里我们比较的是 *getFocusedRect(...)* __。那么又是通过哪些条件判断出哪个View是最合适的呢？




#### isBetterCandidate

```java
// 判断rect1是否比rect2更合适，返回true表示rect1更合适，返回false表示rect2更合适
boolean isBetterCandidate(int direction, Rect source, Rect rect1, Rect rect2) {
        // 首先通过相对于当前获焦区域的方向判断
        // to be a better candidate, need to at least be a candidate in the first
        // place :)
        if (!isCandidate(source, rect1, direction)) {
            return false;
        }
        // we know that rect1 is a candidate.. if rect2 is not a candidate,
        // rect1 is better
        if (!isCandidate(source, rect2, direction)) {
            return true;
        }
        // 然后通过相对于当前获焦区域的位置判断
        // if rect1 is better by beam, it wins
        if (beamBeats(direction, source, rect1, rect2)) {
            return true;
        }
        // if rect2 is better, then rect1 cant' be :)
        if (beamBeats(direction, source, rect2, rect1)) {
            return false;
        }
        // 最后通过相对于当前获焦区域的距离判断
        // otherwise, do fudge-tastic comparison of the major and minor axis
        return (getWeightedDistanceFor(
                        majorAxisDistance(direction, source, rect1),
                        minorAxisDistance(direction, source, rect1))
                < getWeightedDistanceFor(
                        majorAxisDistance(direction, source, rect2),
                        minorAxisDistance(direction, source, rect2)));
    }
```
__首先，通过 _isCandidate(...)_ 方法判断候选区域相对于当前获焦区域的方向上是否满足条件，即候选区域是不是比当前获焦区域更靠近目标方向。__

接着，如果两个候选区域都满足条件，则 __*beamBeats(...)* 会通过更加严苛的位置条件来进行判断__，详细逻辑移步  #beamBeats#：

最后，如果还是无法判断哪个候选区域更合适，则__*getWeightedDistanceFor(...)* 方法会以一定的权重比来计算两个候选区域距离当前获焦区域的距离，并选择距离更近的候选区域__。注意这个距离不是简单地计算两个中心点之间的距离，而是包含特殊权重比的距离。



#### beamBeats

```java
    // 判断rect1是否比rect2更合适，返回true表示rect1更合适；返回false表示无法判断或者rect2更合适
    boolean beamBeats(int direction, Rect source, Rect rect1, Rect rect2) {
        // 判断rect1与source在非目标方向上是否有交集
        final boolean rect1InSrcBeam = beamsOverlap(direction, source, rect1);
        // 判断rect2与source在非目标方向上是否有交集
        final boolean rect2InSrcBeam = beamsOverlap(direction, source, rect2);
        // 如果rect1与source在非目标方向上没有交集 或者 rect2与source在非目标方向上存在交集，则无法判断
        if (rect2InSrcBeam || !rect1InSrcBeam) {
            return false;
        }
        // rect1与source在非目标方向上存在交集 并且 rect2与source在非目标方向上没有交集
        // 此时，如果rect2与source在目标方向上存在交集，则表示rect1更合适
        if (!isToDirectionOf(direction, source, rect2)) {
            return true;
        }
        // 对于水平方向上的移动，"左边或者右边"优先于"左上(下)角或者右上(下)角"
        if ((direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)) {
            return true;
        }        
        // 对于垂直方向上的移动，还需要通过边界距离来判断 "上边或者下边" 与 "左(右)上角或者左(右)下角" 哪个候选区域更合适
        return (majorAxisDistance(direction, source, rect1)
                < majorAxisDistanceToFarEdge(direction, source, rect2));
    }
```

总结一下 _beamBeats(...)_ 的判断逻辑：

* 如果两个候选区域与当前获焦区域在目标方向上均有交集，则无法判断，需要最后一步通过距离判断。

* 如果一个候选区域(__rect1__)与当前获焦区域在目标方向上存在交集，而另一个候选区域(__rect2__)与当前获焦区域在目标方向上不存在交集，则：

    * 如果__rect2__与当前获焦区域在非目标方向上存在交集，则__rect1__更合适，为什么呢？因为此时__rect2__更适合作为非目标方向上焦点移动的候选View。
    
    * 如果__rect2__与当前获焦区域在非目标方向上不存在交集（此时__rect2__在对角上），则：
    
        * 如果是水平方向上的移动，则__rect1__更合适
        
        * 如果是垂直方向上的移动，则判断__rect1__是否比__rect2__在垂直方向上距离当前获焦区域更近（当前获焦区域到__rect1__近边界的距离小于到__rect2__远边界的。
        
        * 也就是说__水平方向上允许跨列移动焦点，垂直方向上不允许跨行移动焦点__ 
        
          

举个栗子：

```java
          |     *******    *******            |        
          |     * 2.1 *    * 2.2 *            |  
          |     *******    *******            |
          |       |           |               |
          | *******           |               |
----------|-* 1.2 *-----------*****************----------------------
          | *******           *               *
          |                   *               *
    *******      <===============  focused    *
    * 1.1 *       FOCUS_LEFT  *               *
    *******                   *               *
------------------------------*****************-----------------------
                              |               |
                              |               |
                              |               |
                              |               |
```

如图所示：

* 区域1.1和1.2，因为水平方向上与当前获焦区域均有交集，因此只能通过带权重比的距离判断

* 区域2.2，因为垂直方向上与当前获焦区域有交集，而水平方向上没有，更适合作为 *FOCUS_UP* 的候选区域。

* 区域1.1和2.1，因为 __水平方向允许跨列移动焦点__ 且2.2会作为 *FOCUS_UP* 的候选区域， 因此此处选择1.1

再举个栗子：

```java
                           *******           
                           * 2.2 *     
                           *******               |
                              |      *******     |
       *******----------------|------* 2.1 *     |
       * 1.2 *                |      *******     |  
       *******                |                  |
                              |     FOCUS_UP     |
                              |        ^         |
       *******                |        |         |
-------* 1.1 *----------------*********|**********---------------------
       *******                *        |         *
                              *                  *
                              *      focused     *
                              *                  *
                              *                  *
------------------------------********************----------------------
                              |                  |
                              |                  |
                              |                  |
                              |                  |
```

如图所示：

* 区域2.1和2.2，由于垂直方向上与当前获焦区域均有交集，只能通过带权重比的距离判断

* 区域1.1，由于垂直方向上与当前获焦区域无交集，但是水平方向上有交集，因此更适合作为 *FOCUS_LEFT* 的候选区域

* 区域2.1与1.2，由于2.1与1.2处于同一行，因此区域2.1更合适

* 区域2.2与1.2，由于2.2与1.2不在同一行，且 __垂直方向上不允许跨行移动焦点__，因此区域1.2更合适。



#### 附：FocusFinder部分方法解析



##### isCandidate

```java
    boolean isCandidate(Rect srcRect, Rect destRect, int direction) {
        switch (direction) {
            case View.FOCUS_LEFT:
                return (srcRect.right > destRect.right || srcRect.left >= destRect.right) 
                        && srcRect.left > destRect.left;
            ......
    }
```
_isCandidate(...)_ 方法主要是__判断候选区域(dest)是否比当前获焦区域(src)更靠近目标方向__。

例如 _FOCUS_LEFT_ 焦点事件，通过判断候选区域的左边界是不是比当前获焦区域左边界更靠左，同时右边界也需要比当前获焦区域的右边界更靠左（__如果获焦区域width为0则右边界允许重合__），如图所示：

```java
        |        |
        |  src   |
        |        |
      * |      * | 
      * |      * |
      *        *
      *  dest  *
      *        *
```



##### beamsOverlap

```java
    boolean beamsOverlap(int direction, Rect rect1, Rect rect2) {
        switch (direction) {
            case View.FOCUS_LEFT:
            case View.FOCUS_RIGHT:
                return (rect2.bottom >= rect1.top) && (rect2.top <= rect1.bottom);
            case View.FOCUS_UP:
            case View.FOCUS_DOWN:
                return (rect2.right >= rect1.left) && (rect2.left <= rect1.right);
        }
        throw new IllegalArgumentException("direction must be one of "
                + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
    }
```
_beamsOverlap(...)_ 方法主要是__判断候选区域(dest)跟当前获焦区域(src)在目标方向上是否 *存在交集* __。

* 对于 _FOCUS_LEFT_ 或者 _FOCUS_RIGHT_，判断这个区域跟当前获焦区域在水平方向上是否有交集，如图所示：

* 对于 _FOCUS_UP_ 或者 _FOCUS_DOWN_，判断这个区域跟当前获焦区域在垂直方向上是否有交集。

```java
      -----------      **********               **********
**********                    ----------         dest  ----------
  dest      src   或    dest      src       或   **********  src
**********                    ----------               ----------
      -----------      ********** 
```



##### isToDirectionOf

```java
    /**
     * e.g for left, is 'to left of'
     */
    boolean isToDirectionOf(int direction, Rect src, Rect dest) {
        switch (direction) {
            case View.FOCUS_LEFT:
                return src.left >= dest.right;
            case View.FOCUS_RIGHT:
                return src.right <= dest.left;
            case View.FOCUS_UP:
                return src.top >= dest.bottom;
            case View.FOCUS_DOWN:
                return src.bottom <= dest.top;
        }
        throw new IllegalArgumentException("direction must be one of "
                + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
    }
```

_isToDirectionOf(...)_ 方法主要是__判断候选区域(dest)与当前获焦区域(src)在非目标方向上是否 *不存在交集(边界允许重合)*__，例如 _FOCUS_LEFT_ 时，判断该区域是否完全在当前获焦区域的左侧(toLeftOf)，如图所示：

```java
      *      *                    |     |
      *      *                    |     |
      * dest *     to left of     | src |
      *      *                    |     |
      *      *                    |     |
```



##### majorAxisDistance

```java
    /**
     * @return The distance from the edge furthest in the given direction
     *   of source to the edge nearest in the given direction of dest.  If the
     *   dest is not in the direction from source, return 0.
     */
    static int majorAxisDistance(int direction, Rect source, Rect dest) {
        return Math.max(0, majorAxisDistanceRaw(direction, source, dest));
    }
    static int majorAxisDistanceRaw(int direction, Rect source, Rect dest) {
        switch (direction) {
            case View.FOCUS_LEFT:
                return source.left - dest.right;
            case View.FOCUS_RIGHT:
                return dest.left - source.right;
            case View.FOCUS_UP:
                return source.top - dest.bottom;
            case View.FOCUS_DOWN:
                return dest.top - source.bottom;
        }
        throw new IllegalArgumentException("direction must be one of "
                + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
    }
```
_majorAxisDistance(...)_ 方法主要是__计算当前焦点区域(src)到候选区域(dest)在目标方向上较近的那条边界的距离__，例如 _FOCUS_LEFT_时，计算当前焦点区域左边界到候选区域右边界的距离(若小于0则返回0)，如下图所示：


```java
      *      * <--majorAxisDistance--- |     |
      *      *                         |     |
      * dest *                         | src |
      *      *                         |     |
      *      *                         |     |
```



##### majorAxisDistanceToFarEdge

```java
    /**
     * @return The distance along the major axis w.r.t the direction from the
     *   edge of source to the far edge of dest. If the
     *   dest is not in the direction from source, return 1 (to break ties with
     *   {@link #majorAxisDistance}).
     */
    static int majorAxisDistanceToFarEdge(int direction, Rect source, Rect dest) {
        return Math.max(1, majorAxisDistanceToFarEdgeRaw(direction, source, dest));
    }
    static int majorAxisDistanceToFarEdgeRaw(int direction, Rect source, Rect dest) {
        switch (direction) {
            case View.FOCUS_LEFT:
                return source.left - dest.left;
            case View.FOCUS_RIGHT:
                return dest.right - source.right;
            case View.FOCUS_UP:
                return source.top - dest.top;
            case View.FOCUS_DOWN:
                return dest.bottom - source.bottom;
        }
        throw new IllegalArgumentException("direction must be one of "
                + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
    }
```

_majorAxisDistanceToFarEdge(...)_ 方法主要是 __计算当前焦点区域(src)到候选区域(dest)在目标方向上较远的那条边界的距离__。例如 _FOCUS_LEFT_ 时，计算当前焦点区域左边界到候选区域左边界的距离(若小于1则返回1)，如下图所示：

```java
      * <-------majorAxisDistanceToFarEdge---|     |
      *      *                               |     |
      * dest *                               | src |
      *      *                               |     |
      *      *                               |     |
```



##### minorAxisDistance

```java
    /**
     * Find the distance on the minor axis w.r.t the direction to the nearest
     * edge of the destination rectangle.
     * @param direction the direction (up, down, left, right)
     * @param source The source rect.
     * @param dest The destination rect.
     * @return The distance.
     */
    static int minorAxisDistance(int direction, Rect source, Rect dest) {
        switch (direction) {
            case View.FOCUS_LEFT:
            case View.FOCUS_RIGHT:
                // the distance between the center verticals
                return Math.abs(
                        ((source.top + source.height() / 2) -
                        ((dest.top + dest.height() / 2))));
            case View.FOCUS_UP:
            case View.FOCUS_DOWN:
                // the distance between the center horizontals
                return Math.abs(
                        ((source.left + source.width() / 2) -
                        ((dest.left + dest.width() / 2))));
        }
        throw new IllegalArgumentException("direction must be one of "
                + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
    }
```

_minorAxisDistance(...)_ 方法主要是__计算当前获焦区域(src)中心点到候选区域(dest)中心点非目标方向上的距离__：

* 对于 _FOCUS_LEFT_ 或 _FOCUS_RIGHT_，则计算当前获焦区域中心点到候选区域中心点垂直方向上的距离(可为负值)，如下图所示；
* 对于 _FOCUS_UP_ 或 _FOCUS_DOWN_ ，则计算当前获焦区域到候选区域中心点水平方向上的距离(可为负值)。

```java
                                           src
                                       ===========
                                        
                                        
        dest              ------------------x        
    ***********           |                    
                  minorAxisDistance    
                          |            ===========
         x-----------------                
         
         
    ***********
```



#####  getWeightedDistanceFor

```java
    /**
     * Fudge-factor opportunity: how to calculate distance given major and minor
     * axis distances.  Warning: this fudge factor is finely tuned, be sure to
     * run all focus tests if you dare tweak it.
     */
    int getWeightedDistanceFor(int majorAxisDistance, int minorAxisDistance) {
        return 13 * majorAxisDistance * majorAxisDistance
                + minorAxisDistance * minorAxisDistance;
    }
```

_getWeightedDistanceFor(...)_ 方法__以 *13:1* 的特殊权重比计算目标方向及非目标方向上的距离__。

