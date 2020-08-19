### Android 垂直同步 

#### VSYNC

[VSYNC](https://source.android.com/devices/graphics/implement-vsync)
[深入研究源码：DispSync详解](https://juejin.im/post/6844903986194022414)

垂直同步主要是同步：屏幕刷新频率(Hardware Composer (HWC))，GPU合成频率(SurfaceFlinger composition)，App绘制频率(app rendering)。这三个频率不同步，则可能会产生卡顿(跳帧)、图像撕裂等问题。

#### DispSync

[Android Systrace 基础知识 - Vsync 解读](https://androidperformance.com/2019/12/01/Android-Systrace-Vsync/)

__DispSync__接收硬件产生的__VSYNC__信号，并根据设置的延时__计算及模拟____SurfaceFlinger__和__Choreographer__使用的__VSYNC__信号。

#### Choreographer

[Android 基于 Choreographer 的渲染机制详解](https://www.androidperformance.com/2019/10/22/Android-Choreographer)

[Choreographer.java](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/Choreographer.java)

[android_view_DisplayEventReceiver.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/jni/android_view_DisplayEventReceiver.cpp)

[DisplayEventReceiver.cpp](https://android.googlesource.com/platform/frameworks/native/+/master/libs/gui/DisplayEventReceiver.cpp)

* __Choreographer__负责管理App层的UI事件，并将这些事件与__VSYNC__做同步。

* 另外__Choreographer__只有在注册__FrameCallback__的时候，才会触发__VSYNC__同步信号，也就是说这是一种__按需同步__。

* 这些注册的__FrameCallback__只有等到__VSYNC__信号到来之后才会执行，例如触摸/点击事件(CALLBACK_INPUT)、动画(CALLBACK_ANIMATION)、渲染(CALLBACK_TRAVERSAL)等等

#### ViewRootImpl

[ViewRootImpl.java](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/ViewRootImpl.java)

```java
// ViewRootImpl.java
    ......
    @UnsupportedAppUsage
    void scheduleTraversals() {
        if (!mTraversalScheduled) {
            mTraversalScheduled = true;
            mTraversalBarrier = mHandler.getLooper().getQueue().postSyncBarrier();
            mChoreographer.postCallback(
                    Choreographer.CALLBACK_TRAVERSAL, mTraversalRunnable, null);
            ......
        }
    }
    
    void unscheduleTraversals() {
        if (mTraversalScheduled) {
            mTraversalScheduled = false;
            mHandler.getLooper().getQueue().removeSyncBarrier(mTraversalBarrier);
            mChoreographer.removeCallbacks(
                    Choreographer.CALLBACK_TRAVERSAL, mTraversalRunnable, null);
        }
    }
```

__ViewRootImpl__开始触发渲染事件的时候，即会调用__MessageQueue__的__postSyncBarrier__方法，该方法会阻塞所有未设置__FLAG_ASYNCHRONOUS__的消息，直至渲染结束。

#### MessageQueue

[MessageQueue.java](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/os/MessageQueue.java)

```java
// MessageQueue.java
    private int postSyncBarrier(long when) {
        // Enqueue a new sync barrier token.
        // We don't need to wake the queue because the purpose of a barrier is to stall it.
        synchronized (this) {
            final int token = mNextBarrierToken++;
            final Message msg = Message.obtain();
            msg.markInUse();
            msg.when = when;
            msg.arg1 = token;
            Message prev = null;
            Message p = mMessages;
            if (when != 0) {
                while (p != null && p.when <= when) {
                    prev = p;
                    p = p.next;
                }
            }
            if (prev != null) { // invariant: p == prev.next
                msg.next = p;
                prev.next = msg;
            } else {
                msg.next = p;
                mMessages = msg;
            }
            return token;
        }
    }
    ......
    Message next() {
        ......
        for (;;) {
            ......
            synchronized (this) {
                // Try to retrieve the next message.  Return if found.
                final long now = SystemClock.uptimeMillis();
                Message prevMsg = null;
                Message msg = mMessages;
                if (msg != null && msg.target == null) {
                    // Stalled by a barrier.  Find the next asynchronous message in the queue.
                    do {
                        prevMsg = msg;
                        msg = msg.next;
                    } while (msg != null && !msg.isAsynchronous());
                    ......
                }
                ......
            }
            ......
        }
        ......
    }
```

__postSyncBarrier__的操作很简单：在消息队列的头部嵌一个特殊的__Message__。当消息队列运行的时候，如果检查到头部是这个特殊的__Message__，则会查找下一个设置__FLAG_ASYNCHRONOUS__的消息，中间的这些消息都会阻塞，直至调用__removeSyncBarrier__方法。




