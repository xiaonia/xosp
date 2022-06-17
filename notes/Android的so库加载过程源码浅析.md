### Android ART so库加载过程源码浅析

[TOC]

#### 相关链接

- [System.java](https://android.googlesource.com/platform/libcore/+/refs/heads/master/ojluni/src/main/java/java/lang/System.java)

- [Runtime.java](https://android.googlesource.com/platform/libcore/+/refs/heads/master/ojluni/src/main/java/java/lang/Runtime.java)

- [BaseDexClassLoader.java](https://android.googlesource.com/platform/libcore/+/master/dalvik/src/main/java/dalvik/system/BaseDexClassLoader.java)

- [DexPathList.java](https://android.googlesource.com/platform/libcore/+/master/dalvik/src/main/java/dalvik/system/DexPathList.java)

- [Runtime.c](https://android.googlesource.com/platform/libcore/+/refs/heads/master/ojluni/src/main/native/Runtime.c)

- [java\_vm\_ext.cc](https://android.googlesource.com/platform/art/+/refs/heads/master/runtime/jni/java_vm_ext.cc)


- [native_loader.cpp](https://android.googlesource.com/platform/art/+/refs/heads/master/libnativeloader/native_loader.cpp)

- [native\_loader\_namespace.cpp](https://android.googlesource.com/platform/art/+/refs/heads/master/libnativeloader/native_loader_namespace.cpp)

- [linker.cpp](https://android.googlesource.com/platform/bionic/+/master/linker/linker.cpp)


#### System

#### Runtime


