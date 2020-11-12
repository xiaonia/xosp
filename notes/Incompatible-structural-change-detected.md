###  插件化 Incompatible structural change detected 问题分析

#### 现场还原

```java
11-06 16:19:51.273 13300 13300 W art     : Incompatible structural change detected: Structural change of com.test.example.TestSuper is hazardous (/data/app/com.test.example-1/oat/arm/base.odex at compile time, /data/user/0/com.test.example/app_plugins/com.test.exapmle.plugin/1.0.1/com.test.example.plugin.1.0.1.dex at runtime): Static field count off: 1 vs 0
```

#### 源码分析

[class_linker.cc](https://android.googlesource.com/platform/art/+/refs/tags/android-6.0.0_r7/runtime/class_linker.cc)

```cpp
// Checks whether a the super-class changed from what we had at compile-time. This would
// invalidate quickening.
// klass 表示当前加载的类
// dex_file 为当前加载的类所在的 dex 文件
// class_def 表示当前类在 dex 文件中的数据结构
// super_class 表示当前加载的父类，正常来说(app_image的场景除外)这个类是按照双亲委托模型加载的父类，而要校验的正是这个类
static bool CheckSuperClassChange(Handle<mirror::Class> klass,
                                  const DexFile& dex_file,
                                  const DexFile::ClassDef& class_def,
                                  mirror::Class* super_class)
    SHARED_LOCKS_REQUIRED(Locks::mutator_lock_) {
  // Check for unexpected changes in the superclass.
  // Quick check 1) is the super_class class-loader the boot class loader? This always has
  // precedence.
  // 校验 classloader
  if (super_class->GetClassLoader() != nullptr &&
      // Quick check 2) different dex cache? Breaks can only occur for different dex files,
      // which is implied by different dex cache.
      // 校验 dexCache，每个 dexFile 都对应有一个 dexCache
      klass->GetDexCache() != super_class->GetDexCache()) {
    // Now comes the expensive part: things can be broken if (a) the klass' dex file has a
    // definition for the super-class, and (b) the files are in separate oat files. The oat files
    // are referenced from the dex file, so do (b) first. Only relevant if we have oat files.
    // OatDexFile 表示 .art、.odex、.vdex 的集合
    const OatDexFile* class_oat_dex_file = dex_file.GetOatDexFile();
    // OatFile 表示 .odex 文件
    const OatFile* class_oat_file = nullptr;
    if (class_oat_dex_file != nullptr) {
      class_oat_file = class_oat_dex_file->GetOatFile();
    }
    if (class_oat_file != nullptr) {
      const OatDexFile* loaded_super_oat_dex_file = super_class->GetDexFile().GetOatDexFile();
      const OatFile* loaded_super_oat_file = nullptr;
      if (loaded_super_oat_dex_file != nullptr) {
        loaded_super_oat_file = loaded_super_oat_dex_file->GetOatFile();
      }
      if (loaded_super_oat_file != nullptr && class_oat_file != loaded_super_oat_file) {
        // Now check (a).
        const DexFile::ClassDef* super_class_def = dex_file.FindClassDef(class_def.superclass_idx_);
        if (super_class_def != nullptr) {
          // Uh-oh, we found something. Do our check.
          std::string error_msg;
          // 校验当前加载的父类与编译时(dex2oat)的父类是否一致(odex中的数据结构)
          if (!SimpleStructuralCheck(dex_file, *super_class_def,
                                     super_class->GetDexFile(), *super_class->GetClassDef(),
                                     &error_msg)) {
            // Print a warning to the log. This exception might be caught, e.g., as common in test
            // drivers. When the class is later tried to be used, we re-throw a new instance, as we
            // only save the type of the exception.
            LOG(WARNING) << "Incompatible structural change detected: " <<
                StringPrintf(
                    "Structural change of %s is hazardous (%s at compile time, %s at runtime): %s",
                    PrettyType(super_class_def->class_idx_, dex_file).c_str(),
                    class_oat_file->GetLocation().c_str(),
                    loaded_super_oat_file->GetLocation().c_str(),
                    error_msg.c_str());
            ThrowIncompatibleClassChangeError(klass.Get(),
                "Structural change of %s is hazardous (%s at compile time, %s at runtime): %s",
                PrettyType(super_class_def->class_idx_, dex_file).c_str(),
                class_oat_file->GetLocation().c_str(),
                loaded_super_oat_file->GetLocation().c_str(),
                error_msg.c_str());
            return false;
          }
        }
      }
    }
  }
  return true;
}
```
从Android底层的源码可见，在 Class 加载的过程，存在一个校验 SuperClass 的过程，也就是 CheckSuperClassChange() 方法做的事：

* 校验当前加载的父类与编译时(dex2oat)的父类是否一致(odex中的数据结构) 

```java
// Very simple structural check on whether the classes match. Only compares the number of
// methods and fields.
static bool SimpleStructuralCheck(const DexFile& dex_file1, const DexFile::ClassDef& dex_class_def1,
                                  const DexFile& dex_file2, const DexFile::ClassDef& dex_class_def2,
                                  std::string* error_msg) {
    ......
}
```

#### 原因

通过源码以及APK包代码分析，出现该问题的原因是：

* 宿主打包时，将 com.test.example.Test 和 com.test.example.TestSuper 都打到 Apk 包中

* 插件打包时，未将 com.test.example.Test 这个类打到插件中，同时将其父类 com.test.example.TestSuper 打到插件中

在这种情况下，如果运行时触发了 com.test.example.Test  类的相关逻辑，则会加载宿主中的该类（即 base.odex），而该类的父类 com.test.example.TestSuper 则会加载插件中的类（即plugin.odex），因而在 CheckSuperClassChange() 方法中就抛出了异常。

