###  kotlin之IntrinsicFunction



__Intrinsic Function__是由kotlin编译器支持的一类特殊函数，这些函数都是由编译器根据平台语言动态生成的，在kotlin的代码中不需要任何实现。




#### for example

例如kotlin协程中最关键的__suspendCoroutineUninterceptedOrReturn__函数：

[Intrinsics.kt](https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/coroutines-experimental/src/kotlin/coroutines/experimental/intrinsics/Intrinsics.kt)

```kotlin
/**
 * Obtains the current continuation instance inside suspend functions and either suspends
 * currently running coroutine or returns result immediately without suspension.
 *
 * Unlike [suspendCoroutineOrReturn] it does not intercept continuation.
 */
@SinceKotlin("1.2")
@kotlin.internal.InlineOnly
@Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
public suspend inline fun <T> suspendCoroutineUninterceptedOrReturn(crossinline block: (Continuation<T>) -> Any?): T =
    throw NotImplementedError("Implementation of suspendCoroutineUninterceptedOrReturn is intrinsic")
```



#### under the hook

[coroutineCodegenUtil.kt](https://github.com/JetBrains/kotlin/blob/master/compiler/backend/src/org/jetbrains/kotlin/codegen/coroutines/coroutineCodegenUtil.kt)

```kotlin
fun createMethodNodeForSuspendCoroutineUninterceptedOrReturn(languageVersionSettings: LanguageVersionSettings): MethodNode {
    val node =
        MethodNode(
            Opcodes.API_VERSION,
            Opcodes.ACC_STATIC,
            "fake",
            Type.getMethodDescriptor(OBJECT_TYPE, AsmTypes.FUNCTION1, languageVersionSettings.continuationAsmType()),
            null, null
        )

    with(InstructionAdapter(node)) {
        load(0, OBJECT_TYPE) // block
        load(1, OBJECT_TYPE) // continuation

        // block.invoke(continuation)
        invokeinterface(
            AsmTypes.FUNCTION1.internalName,
            OperatorNameConventions.INVOKE.identifier,
            "($OBJECT_TYPE)$OBJECT_TYPE"
        )

        if (languageVersionSettings.supportsFeature(LanguageFeature.ReleaseCoroutines)) {
            val elseLabel = Label()
            // if (result === COROUTINE_SUSPENDED) {
            dup()
            loadCoroutineSuspendedMarker(languageVersionSettings)
            ifacmpne(elseLabel)
            //   DebugProbesKt.probeCoroutineSuspended(continuation)
            load(1, OBJECT_TYPE) // continuation
            checkcast(languageVersionSettings.continuationAsmType())
            invokestatic(
                languageVersionSettings.coroutinesJvmInternalPackageFqName().child(Name.identifier("DebugProbesKt")).topLevelClassAsmType().internalName,
                "probeCoroutineSuspended",
                "(${languageVersionSettings.continuationAsmType()})V",
                false
            )
            // }
            mark(elseLabel)
        }
    }

    node.visitInsn(Opcodes.ARETURN)
    node.visitMaxs(3, 2)

    return node
}
```

当我们通过kotlin编译器编译运行在JVM平台上的代码时，会自动为该方法生成函数：

```java
public Object suspendCoroutineUninterceptedOrReturn(Function1 block, Continuation continuation) {
    Object result = block.invoke(continuation)
    ......
}
```

值得注意的是，该方法仅存在于编译期，因为这个方法是一个__inline__方法，kotlin代码经编译之后便不存在对方法的直接调用。