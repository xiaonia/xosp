####  Android消息机制

#### 关键字

Handler、Looper、MessageQueue、IdleHandler、epoll、pipe

##### 链接

[Android应用程序消息处理机制（Looper、Handler）分析](https://blog.csdn.net/Luoshengyang/article/details/6817933)

[Looper.cpp](https://android.googlesource.com/platform/system/core/+/master/libutils/Looper.cpp)

[android_os_MessageQueue.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/jni/android_os_MessageQueue.cpp)

#### 挂起/唤醒

__借助Linux系统的 _epoll_ 机制(I/O事件通知机制)，通过 _pipe_ 进行通信__

