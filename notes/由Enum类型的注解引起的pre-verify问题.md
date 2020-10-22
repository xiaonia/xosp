

### Android 5.0 以下系统，由Enum类型的注解引发的 pre-verified 问题分析



#### 相关链接

[DexPrepare.cpp](https://android.googlesource.com/platform/dalvik.git/+/refs/heads/kitkat-release/vm/analysis/DexPrepare.cpp)

[DexVerify.cpp](https://android.googlesource.com/platform/dalvik.git/+/refs/heads/kitkat-release/vm/analysis/DexVerify.cpp)

[CodeVerify.cpp](https://android.googlesource.com/platform/dalvik.git/+/refs/heads/kitkat-release/vm/analysis/CodeVerify.cpp)

[java_lang_reflect_Method.cpp](https://android.googlesource.com/platform/dalvik/+/refs/heads/kitkat-release/vm/native/java_lang_reflect_Method.cpp)

[Annotation.cpp](https://android.googlesource.com/platform/dalvik/+/refs/heads/kitkat-release/vm/reflect/Annotation.cpp)

[DexFile.h](https://android.googlesource.com/platform/dalvik/+/refs/heads/kitkat-release/libdex/DexFile.h)

[Resolve.cpp](https://android.googlesource.com/platform/dalvik.git/+/refs/heads/kitkat-release/vm/oo/Resolve.cpp)

[Optimize.cpp](https://android.googlesource.com/platform/dalvik.git/+/refs/heads/kitkat-release/vm/analysis/Optimize.cpp)

[Class.cpp](https://android.googlesource.com/platform/dalvik.git/+/refs/heads/kitkat-release/vm/oo/Class.cpp)



#### verify 验证过程

[DexPrepare.cpp](https://android.googlesource.com/platform/dalvik.git/+/refs/heads/kitkat-release/vm/analysis/DexPrepare.cpp)

```cpp
/*
 * Verify and/or optimize a specific class.
 */
static void verifyAndOptimizeClass(DexFile* pDexFile, ClassObject* clazz,
    const DexClassDef* pClassDef, bool doVerify, bool doOpt)
{
    ......
    if (doVerify) {
        if (dvmVerifyClass(clazz)) {
            /*
             * Set the "is preverified" flag in the DexClassDef.  We
             * do it here, rather than in the ClassObject structure,
             * because the DexClassDef is part of the odex file.
             */
            // 验证通过，打上 CLASS_ISPREVERIFIED 标志
            assert((clazz->accessFlags & JAVA_FLAGS_MASK) ==
                pClassDef->accessFlags);
            ((DexClassDef*)pClassDef)->accessFlags |= CLASS_ISPREVERIFIED;
            verified = true;
        } 
    }
    ......
}
```

[DexVerify.cpp](https://android.googlesource.com/platform/dalvik.git/+/refs/heads/kitkat-release/vm/analysis/DexVerify.cpp)

```cpp
/*
 * Verify a class.
 */
bool dvmVerifyClass(ClassObject* clazz)
{
    int i;
    if (dvmIsClassVerified(clazz)) {
        return true;
    }
    for (i = 0; i < clazz->directMethodCount; i++) {
        // 验证directMethod
        if (!verifyMethod(&clazz->directMethods[i])) {
            LOG_VFY("Verifier rejected class %s", clazz->descriptor);
            return false;
        }
    }
    for (i = 0; i < clazz->virtualMethodCount; i++) {
        // 验证virtualMethod
        if (!verifyMethod(&clazz->virtualMethods[i])) {
            LOG_VFY("Verifier rejected class %s", clazz->descriptor);
            return false;
        }
    }
    return true;
}
```

```cpp
/*
 * Perform verification on a single method.
 */
static bool verifyMethod(Method* meth)
{
    bool result = false;
    ......
    /*
     * Perform static instruction verification.  Also sets the "branch
     * target" flags.
     */
    if (!verifyInstructions(&vdata))
        goto bail;
    /*
     * Do code-flow analysis.
     *
     * We could probably skip this for a method with no registers, but
     * that's so rare that there's little point in checking.
     */
    if (!dvmVerifyCodeFlow(&vdata)) {
        //ALOGD("+++ %s failed code flow", meth->name);
        goto bail;
    }
success:
    result = true;
bail:
    dvmFreeVfyBasicBlocks(&vdata);
    dvmFreeUninitInstanceMap(vdata.uninitMap);
    free(vdata.insnFlags);
    return result;
}
```

[CodeVerify.cpp](https://android.googlesource.com/platform/dalvik.git/+/refs/heads/kitkat-release/vm/analysis/CodeVerify.cpp)

```cpp
/*
 * Entry point for the detailed code-flow analysis of a single method.
 */
bool dvmVerifyCodeFlow(VerifierData* vdata)
{
    bool result = false;
    ......
    /*
     * Run the verifier.
     */
    if (!doCodeVerification(vdata, &regTable))
        goto bail;
    ......
    /*
     * Success.
     */
    result = true;
bail:
    freeRegisterLineInnards(vdata);
    free(regTable.registerLines);
    free(regTable.lineAlloc);
    return result;
}
```

在 __dexopt__ 的过程中，Dalvik虚拟机会对 __class__ 进行验证和优化，其中对于验证通过的类都会打上 __CLASS_ISPREVERIFIED__ 标志。从上面的源码可以看出：

* 对于触发类加载、方法调用和变量调用的指令，都会验证引用类及被引用类是否都在同一个 dex 中

* 另外：该验证过程并没有验证类、方法和变量的注解



#### pre-verify 验证过程

[Resolve.cpp](https://android.googlesource.com/platform/dalvik.git/+/refs/heads/kitkat-release/vm/oo/Resolve.cpp)

```cpp
ClassObject* dvmResolveClass(const ClassObject* referrer, u4 classIdx,
    bool fromUnverifiedConstant)
{
    DvmDex* pDvmDex = referrer->pDvmDex;
    ClassObject* resClass;
    const char* className;
    /*
     * Check the table first -- this gets called from the other "resolve"
     * methods.
     */
    resClass = dvmDexGetResolvedClass(pDvmDex, classIdx);
    if (resClass != NULL)
        return resClass;
    LOGVV("--- resolving class %u (referrer=%s cl=%p)",
        classIdx, referrer->descriptor, referrer->classLoader);
    /*
     * Class hasn't been loaded yet, or is in the process of being loaded
     * and initialized now.  Try to get a copy.  If we find one, put the
     * pointer in the DexTypeId.  There isn't a race condition here --
     * 32-bit writes are guaranteed atomic on all target platforms.  Worst
     * case we have two threads storing the same value.
     *
     * If this is an array class, we'll generate it here.
     */
    className = dexStringByTypeIdx(pDvmDex->pDexFile, classIdx);
    if (className[0] != '\0' && className[1] == '\0') {
        /* primitive type */
        resClass = dvmFindPrimitiveClass(className[0]);
    } else {
        resClass = dvmFindClassNoInit(className, referrer->classLoader);
    }
    if (resClass != NULL) {
        /*
         * If the referrer was pre-verified, the resolved class must come
         * from the same DEX or from a bootstrap class.  The pre-verifier
         * makes assumptions that could be invalidated by a wacky class
         * loader.  (See the notes at the top of oo/Class.c.)
         */
         // 注意：这里对于 fromUnverifiedConstant 为false且打上 CLASS_ISPREVERIFIED 标志的类都会进行pre-verify的验证过程
         // 也就是检验这两个类是否在同一个dex中
        if (!fromUnverifiedConstant &&
            IS_CLASS_FLAG_SET(referrer, CLASS_ISPREVERIFIED))
        {
            ClassObject* resClassCheck = resClass;
            if (dvmIsArrayClass(resClassCheck))
                resClassCheck = resClassCheck->elementClass;
            if (referrer->pDvmDex != resClassCheck->pDvmDex &&
                resClassCheck->classLoader != NULL)
            {
                ALOGW("Class resolved by unexpected DEX:"
                     " %s(%p):%p ref [%s] %s(%p):%p",
                    referrer->descriptor, referrer->classLoader,
                    referrer->pDvmDex,
                    resClass->descriptor, resClassCheck->descriptor,
                    resClassCheck->classLoader, resClassCheck->pDvmDex);
                ALOGW("(%s had used a different %s during pre-verification)",
                    referrer->descriptor, resClass->descriptor);
                dvmThrowIllegalAccessError(
                    "Class ref in pre-verified class resolved to unexpected "
                    "implementation");
                return NULL;
            }
        }
        LOGVV("##### +ResolveClass(%s): referrer=%s dex=%p ldr=%p ref=%d",
            resClass->descriptor, referrer->descriptor, referrer->pDvmDex,
            referrer->classLoader, classIdx);
        /*
         * Add what we found to the list so we can skip the class search
         * next time through.
         *
         * TODO: should we be doing this when fromUnverifiedConstant==true?
         * (see comments at top of oo/Class.c)
         */
        dvmDexSetResolvedClass(pDvmDex, classIdx, resClass);
    } else {
        /* not found, exception should be raised */
        LOGVV("Class not found: %s",
            dexStringByTypeIdx(pDvmDex->pDexFile, classIdx));
        assert(dvmCheckException(dvmThreadSelf()));
    }
    return resClass;
}
```

另一方面，在Dalvik虚拟机触发类加载的时候，都会调用到 __dvmResolveClass()__ 方法，而在 __dvmResolveClass()__ 方法中，对于打上 __CLASS_ISPREVERIFIED__ 标志的类且 __fromUnverifiedConstant__ 参数为 __false__ 时，则会验证引用类和被引用类是否在同一个 dex 中，如果不在同一个 dex 中就会抛出 pre-verified 异常。



#### Enum类型的注解 pr-verified 异常的原因

```cpp
// Dalvik_java_lang_Class_getDeclaredAnnotations
// --dvmGetClassAnnotations                            //Annotation.cpp
// ----processAnnotationSet                            //Annotation.cpp
// ------processEncodedAnnotation                      //Annotation.cpp
// --------createAnnotationMember                      //Annotation.cpp
// ----------processAnnotationValue                    //Annotation.cpp
```

[Annotation.cpp](https://android.googlesource.com/platform/dalvik/+/refs/heads/kitkat-release/vm/reflect/Annotation.cpp)

```cpp
static bool processAnnotationValue(const ClassObject* clazz,
    const u1** pPtr, AnnotationValue* pValue,
    AnnotationResultStyle resultStyle) {
    ......
    // Class类型的注解
    case kDexAnnotationType:
        idx = readUnsignedInt(ptr, valueArg, false);
        if (resultStyle == kAllRaw) {
            pValue->value.i = idx;
        } else {
            // 注意：这里第三个参数（fromUnverifiedConstant）传的是 true
            // 也就说 dvmResolveClass() 方法【不会】进行 pre-verify 验证
            elemObj = (Object*) dvmResolveClass(clazz, idx, true);
            setObject = true;
            if (elemObj == NULL) {
                /* we're expected to throw a TypeNotPresentException here */
                DexFile* pDexFile = clazz->pDvmDex->pDexFile;
                const char* desc = dexStringByTypeIdx(pDexFile, idx);
                dvmClearException(self);
                dvmThrowTypeNotPresentException(desc);
                return false;
            } else {
                dvmAddTrackedAlloc(elemObj, self);      // balance the Release
            }
        }
        break;
    ......
    // 枚举类型的注解
    case kDexAnnotationEnum:
        /* enum values are the contents of a static field */
        idx = readUnsignedInt(ptr, valueArg, false);
        if (resultStyle == kAllRaw) {
            pValue->value.i = idx;
        } else {
            StaticField* sfield;
            // 注意：这里调用的是 dvmResolveStaticField() 方法来处理枚举类型
            sfield = dvmResolveStaticField(clazz, idx);
            if (sfield == NULL) {
                return false;
            } else {
                assert(sfield->clazz->descriptor[0] == 'L');
                elemObj = sfield->value.l;
                setObject = true;
                dvmAddTrackedAlloc(elemObj, self);      // balance the Release
            }
        }
        break;

}
```

对于类、方法或者变量上的注解的解析过程，最后会调用 __processAnnotationValue()__ 方法解析注解的值。而我们从源码中可以看出：对于值为 Class 类型的注解，会直接调用 __dvmResolveClass()__ 方法加载 class，同时我们也注意到这里第三个参数（__fromUnverifiedConstant__）传的是 __true__，也就是不会进行 pre-verify 的验证过程。而对于值为 Enum 类型的注解，则是通过调用 __dvmResolveStaticField()__ 方法解析该 Enum 类的值。

[Resolve.cpp](https://android.googlesource.com/platform/dalvik.git/+/refs/heads/kitkat-release/vm/oo/Resolve.cpp)

```cpp
StaticField* dvmResolveStaticField(const ClassObject* referrer, u4 sfieldIdx)
{
    DvmDex* pDvmDex = referrer->pDvmDex;
    ClassObject* resClass;
    const DexFieldId* pFieldId;
    StaticField* resField;
    pFieldId = dexGetFieldId(pDvmDex->pDexFile, sfieldIdx);
    // 注意：这里第三个参数（fromUnverifiedConstant）传的是 false
    // 也就说 dvmResolveClass() 方法【会】进行 pre-verify 的验证
    resClass = dvmResolveClass(referrer, pFieldId->classIdx, false);
    if (resClass == NULL) {
        assert(dvmCheckException(dvmThreadSelf()));
        return NULL;
    }
    ......
    return resField;
}
```

然而在 __dvmResolveStaticField()__ 方法中，虽然也是调用 __dvmResolveClass()__ 来加载 class，但是我们发现第三个参数（__fromUnverifiedConstant__）传的 __false__，也就是会进行 pre-verify 的验证过程。 



#### 总结

综上，该问题出现的原因是：

* 一方面，在 dexopt 的验证过程中，略过了对注解的验证

* 另一方面，触发注解解析的时候，如果注解的值是Enum类型的，又进行了 pre-verify 的验证过程

这样的话，就会导致如果：

* 使用该枚举注解的类与该Enum类不在同一个 dex 中

* 同时该类除了该Enum类之外，其他引用到的类都在同一个 dex 中 

那么当触发注解解析的时候，就会抛出 pre-verified 异常，通过插件的方式测试验证，确实证明了该问题是普遍存在的：

```cpp
W/dalvikvm: Class resolved by unexpected DEX: Lcom/test/plugin/EnumAnnotationUseCase;(0x41ab8c08):0x7adc7000 ref [Lcom/test/host/EnumTest;] Lcom/test/host/EnumTest;(0x41ab8c08):0x6c2f1000
W/dalvikvm: (Lcom/test/plugin/EnumAnnotationUseCase; had used a different Lcom/test/host/EnumTest; during pre-verification)
W/dalvikvm: Failed processing annotation value

D/AndroidRuntime: Shutting down VM
 java.lang.IllegalAccessError: Class ref in pre-verified class resolved to unexpected implementation
        at java.lang.Class.getDeclaredAnnotations(Native Method)
        at java.lang.Class.getAnnotations(Class.java:326)
```

既然这个问题是必现的，那么为什么现实中又是偶现的呢？通过测试发现：正常情况下，打包的时候会自动将使用该枚举注解的类和该Enum类放在同一个 dex 中，甚至都很难直接将这两个类分别打包到不同的 dex 中。

即使如此，我们也无法保证这两个类一定会在同一个 dex 中：

* 一方面，因为 dex 本身对类、方法及变量存在数量（65535）限制

* 另一方面，也存在对 dex 进行分包的场景（如 [dexSplitter](https://github.com/iqiyi/dexSplitter) ）



#### 解决

针对以上分析，目前可行的解决方案主要有：

* 通过配置，保证将这两个类放在同一个 dex 中

* 通过增加对其他类的引用（比如继承）阻止该类被打上 __CLASS_ISPREVERIFIED__ 标签

* 用其他类型的注解替代枚举类型的注解

