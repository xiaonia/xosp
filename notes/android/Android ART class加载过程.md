###  Android ART class加载过程



#### 相关链接

[java_lang_VMClassLoader.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/native/java_lang_VMClassLoader.cc)

[BaseDexClassLoader.java](https://android.googlesource.com/platform/libcore/+/master/dalvik/src/main/java/dalvik/system/BaseDexClassLoader.java)

[DexPathList.java](https://android.googlesource.com/platform/libcore/+/master/dalvik/src/main/java/dalvik/system/DexPathList.java)

[DexFile.java](https://android.googlesource.com/platform/libcore/+/master/dalvik/src/main/java/dalvik/system/DexFile.java)

[dalvik_system_DexFile.cc](https://android.googlesource.com/platform/art/+/master/runtime/native/dalvik_system_DexFile.cc)

[dex_file.cc](https://android.googlesource.com/platform/art/+/refs/heads/master/libdexfile/dex/dex_file.cc)

[oat_file.cc](https://android.googlesource.com/platform/art/+/refs/heads/master/runtime/oat_file.cc)

[class_linker.cc](https://android.googlesource.com/platform/art/+/master/runtime/class_linker.cc)

[class_loader_utils.h](https://android.googlesource.com/platform/art/+/master/runtime/class_loader_utils.h)



#### Java层的双亲委托模型

[ClassLoader.java](https://android.googlesource.com/platform/libcore/+/master/ojluni/src/main/java/java/lang/ClassLoader.java)

```java
protected Class<?> loadClass(String name, boolean resolve)
        throws ClassNotFoundException
    {
            // First, check if the class has already been loaded
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    if (parent != null) {
                        c = parent.loadClass(name, false);
                    } else {
                        c = findBootstrapClassOrNull(name);
                    }
                } catch (ClassNotFoundException e) {
                    // ClassNotFoundException thrown if class not found
                    // from the non-null parent class loader
                }
                if (c == null) {
                    // If still not found, then invoke findClass in order
                    // to find the class.
                    c = findClass(name);
                }
            }
            return c;
    }
```



#### findLoadedClass

[java_lang_VMClassLoader.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/native/java_lang_VMClassLoader.cc)

```cpp
// VMClassLoader_findLoadedClass           #java_lang_VMClassLoader.cc
// --LookupClass                           #java_lang_VMClassLoader.cc
// ----LookupClass                         #class_linker.cc
// --FindClassInPathClassLoader            #java_lang_VMClassLoader.cc
// ----FindClassInBaseDexClassLoader       #class_linker.cc
```



#### loadClass

##### findClass

```cpp
// findClass                                  #BaseDexClassLoader.java
// --findClass                                #DexPathList.java
// ----loadClassBinaryName                    #DexFile.java
// ------defineClass                          #DexFile.java
// --------defineClassNative                  #DexFile.java

```
##### defineClassNative

```cpp
// DexFile_defineClassNative                 #dalvik_system_DexFile.cc
// --OatDexFile::FindClassDef                #oat_file.cc
// ----ClassLinker::DefineClass              #class_linker.cc
```
##### DefineClass

```cpp
// ClassLinker::DefineClass                  #class_linker.cc
// --ClassLinker::LoadClass                  #class_linker.cc
// ----OatFile::FindOatClass                 #oat_file.cc
// ----ClassLinker::LoadField                #class_linker.cc
// ----ClassLinker::LoadMethod               #class_linker.cc
// ----ClassLinker::LinkCode                 #class_linker.cc
// --ClassLinker::LoadSuperAndInterfaces     #class_linker.cc
```



#### ResolveType

```cpp
// ClassLinker::DoResolveType                        #class_linker.cc
// --ClassLinker::FindClass                          #class_linker.cc
// --~~ClassLinker::FindPrimitiveClass               #class_linker.cc
// --~~ClassLinker::LookupClass                      #class_linker.cc
// --~~ClassLinker::CreateArrayClass                 #class_linker.cc
// --~~ClassLinker::FindClassInBaseDexClassLoader    #class_linker.cc
// --~~ClassLoader.loadClass                         #ClassLoader.java

/**
 * CallObjectMethod(class_loader_object.get(), WellKnownClasses::java_lang_ClassLoader_loadClass, class_name_object.get())
 * /
```

```cpp
// FindClassInBaseDexClassLoader                 #class_linker.cc
// --FindClassInBaseDexClassLoaderClassPath      #class_linker.cc
// ----VisitClassLoaderDexFiles                  #class_loader_utils.h
// ------OatDexFile::FindClassDef                #oat_file.cc
// ------ClassLinker::DefineClass                #class_linker.cc
```



#### Native层的双亲委托模型

[class_linker](https://android.googlesource.com/platform/art/+/master/runtime/class_linker.cc)

```cpp

bool ClassLinker::FindClassInBaseDexClassLoader(ScopedObjectAccessAlreadyRunnable& soa,
                                                Thread* self,
                                                const char* descriptor,
                                                size_t hash,
                                                Handle<mirror::ClassLoader> class_loader,
                                                /*out*/ ObjPtr<mirror::Class>* result) {
  // Termination case: boot class loader.
  if (IsBootClassLoader(soa, class_loader.Get())) {
    *result = FindClassInBootClassLoaderClassPath(self, descriptor, hash);
    return true;
  }
  if (IsPathOrDexClassLoader(soa, class_loader) || IsInMemoryDexClassLoader(soa, class_loader)) {
    // For regular path or dex class loader the search order is:
    //    - parent
    //    - shared libraries
    //    - class loader dex files
    // Handles as RegisterDexFile may allocate dex caches (and cause thread suspension).
    StackHandleScope<1> hs(self);
    Handle<mirror::ClassLoader> h_parent(hs.NewHandle(class_loader->GetParent()));
    if (!FindClassInBaseDexClassLoader(soa, self, descriptor, hash, h_parent, result)) {
      return false;  // One of the parents is not supported.
    }
    if (*result != nullptr) {
      return true;  // Found the class up the chain.
    }
    if (!FindClassInSharedLibraries(soa, self, descriptor, hash, class_loader, result)) {
      return false;  // One of the shared library loader is not supported.
    }
    if (*result != nullptr) {
      return true;  // Found the class in a shared library.
    }
    // Search the current class loader classpath.
    *result = FindClassInBaseDexClassLoaderClassPath(soa, descriptor, hash, class_loader);
    return !soa.Self()->IsExceptionPending();
  }
  if (IsDelegateLastClassLoader(soa, class_loader)) {
    // For delegate last, the search order is:
    //    - boot class path
    //    - shared libraries
    //    - class loader dex files
    //    - parent
    *result = FindClassInBootClassLoaderClassPath(self, descriptor, hash);
    if (*result != nullptr) {
      return true;  // The class is part of the boot class path.
    }
    if (self->IsExceptionPending()) {
      // Pending exception means there was an error other than ClassNotFound that must be returned
      // to the caller.
      return false;
    }
    if (!FindClassInSharedLibraries(soa, self, descriptor, hash, class_loader, result)) {
      return false;  // One of the shared library loader is not supported.
    }
    if (*result != nullptr) {
      return true;  // Found the class in a shared library.
    }
    *result = FindClassInBaseDexClassLoaderClassPath(soa, descriptor, hash, class_loader);
    if (*result != nullptr) {
      return true;  // Found the class in the current class loader
    }
    if (self->IsExceptionPending()) {
      // Pending exception means there was an error other than ClassNotFound that must be returned
      // to the caller.
      return false;
    }
    // Handles as RegisterDexFile may allocate dex caches (and cause thread suspension).
    StackHandleScope<1> hs(self);
    Handle<mirror::ClassLoader> h_parent(hs.NewHandle(class_loader->GetParent()));
    return FindClassInBaseDexClassLoader(soa, self, descriptor, hash, h_parent, result);
  }
  // Unsupported class loader.
  *result = nullptr;
  return false;
}
```




