## AndroidART编译执行过程源码浅析

[TOC]

### 相关源码

#### 链接

- [instruction\_builder.cc](https://android.googlesource.com/platform/art/+/master/compiler/optimizing/instruction_builder.cc)

- [nodes.h](https://android.googlesource.com/platform/art/+/master/compiler/optimizing/nodes.h)

- [code\_generator.cc](https://android.googlesource.com/platform/art/+/master/compiler/optimizing/code_generator.cc)

- [quick\_default\_init\_entrypoints.h](https://android.googlesource.com/platform/art/+/master/runtime/entrypoints/quick/quick_default_init_entrypoints.h)

- [quick\_entrypoints.h](https://android.googlesource.com/platform/art/+/master/runtime/entrypoints/quick/quick_entrypoints.h)

- [quick\_entrypoints\_list.h](https://android.googlesource.com/platform/art/+/master/runtime/entrypoints/quick/quick_entrypoints_list.h)

- [quick\_entrypoints\_enum.h](https://android.googlesource.com/platform/art/+/master/runtime/entrypoints/quick/quick_entrypoints_enum.h)

- [quick\_trampoline\_entrypoints.cc](https://android.googlesource.com/platform/art/+/master/runtime/entrypoints/quick/quick_trampoline_entrypoints.cc)

- [quick\_dexcache\_entrypoints.cc](https://android.googlesource.com/platform/art/+/master/runtime/entrypoints/quick/quick_dexcache_entrypoints.cc)

- [entrypoint\_utils-inl.h](https://android.googlesource.com/platform/art/+/master/runtime/entrypoints/entrypoint_utils-inl.h)

- [class\_linker.cc](https://android.googlesource.com/platform/art/+/master/runtime/class_linker.cc)

