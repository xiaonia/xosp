###  Kotlin之Suspend Function



#### suspend

kotlin的__suspend__方法是kotlin协程的核心，任何一个方法以__suspend__关键字修饰，则表示该方法支持以__非阻塞__的方式__挂起__和__恢复__方法调用过程。__suspend__方法的定义与普通方法一样，唯一的区别在于只有__suspend__方法才可以调用__suspend__方法。



#### compile

Kotlin代码：

```kotlin
suspend fun testSuspend1(value: Any?) {
    println("before testSuspend2")
    testSuspend2("2")
    println("after testSuspend2")
}
```

编译之后的Java代码：
```java
 public static final Object testSuspend1(@Nullable Object value, @NotNull Continuation $completion) {
      Object $continuation = new ContinuationImpl($completion) {
            // $FF: synthetic field
            Object result;
            int label;
            Object L$0;

            @Nullable
            public final Object invokeSuspend(@NotNull Object $result) {
               this.result = $result;
               this.label |= Integer.MIN_VALUE;
               return LearnKotlinKt.testSuspend1((Object)null, this);
            }
         };
      }

      Object $result = ((<undefinedtype>)$continuation).result;
      Object var6 = IntrinsicsKt.getCOROUTINE_SUSPENDED();
      String var2;
      boolean var3;
      switch(((<undefinedtype>)$continuation).label) {
      case 0:
         ResultKt.throwOnFailure($result);
         var2 = "before testSuspend2";
         var3 = false;
         System.out.println(var2);
         ((<undefinedtype>)$continuation).L$0 = value;
         ((<undefinedtype>)$continuation).label = 1;
         if (testSuspend2("2", (Continuation)$continuation) == var6) {
            return var6;
         }
         break;
      case 1:
         value = ((<undefinedtype>)$continuation).L$0;
         ResultKt.throwOnFailure($result);
         break;
      default:
         throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
      }

      var2 = "after testSuspend2";
      var3 = false;
      System.out.println(var2);
      return Unit.INSTANCE;
   }
```

事实上，__suspend__方法的实现主要在kotlin编译器:

* 对于任何一个以__suspend__关键字修饰的方法，kotlin在编译的时候都会在该方法中拓展一个参数：__Continuation__，该参数是方法挂起之后恢复执行的回调。

* 对于__suspend__方法中调用到的其他__suspend__方法，kotlin在编译的时候会以该方法作为分界点将代码块一分为二，并创建一个匿名内部类__ContinuationImpl__保存方法执行时的变量(保护现场)。

  

#### Continuation

```kotlin
/**
 * Interface representing a continuation after a suspension point that returns a value of type `T`.
 */
@SinceKotlin("1.3")
public interface Continuation<in T> {
    /**
     * The context of the coroutine that corresponds to this continuation.
     */
    public val context: CoroutineContext

    /**
     * Resumes the execution of the corresponding coroutine passing a successful or failed [result] as the
     * return value of the last suspension point.
     */
    public fun resumeWith(result: Result<T>)
}
```

__Continuation__是__suspend__方法挂起之后恢复执行的回调，也是kotlin编译器自动为每个__suspend__方法添加的参数，恢复(回调)的入口点是__resumeWith__方法，其参数为下一层级的__suspend__方法执行结果。

#### ContinuationImpl

__ContinuationImpl__继承自__BaseContinuationImpl__，从上文编译之后的代码可以看到，每一个上级__suspend__方法传递进来的__Continuation__都会被封装保存到__ContinuationImpl__中，通过__ContinuationImpl__来控制__suspend__方法恢复(回调)逻辑。


#### BaseContinuationImpl

```kotlin
// This implementation is final. This fact is used to unroll resumeWith recursion.
    public final override fun resumeWith(result: Result<Any?>) {
        // This loop unrolls recursion in current.resumeWith(param) to make saner and shorter stack traces on resume
        var current = this
        var param = result
        while (true) {
            // Invoke "resume" debug probe on every resumed continuation, so that a debugging library infrastructure
            // can precisely track what part of suspended callstack was already resumed
            probeCoroutineResumed(current)
            with(current) {
                val completion = completion!! // fail fast when trying to resume continuation without completion
                val outcome: Result<Any?> =
                    try {
                        val outcome = invokeSuspend(param)
                        if (outcome === COROUTINE_SUSPENDED) return
                        Result.success(outcome)
                    } catch (exception: Throwable) {
                        Result.failure(exception)
                    }
                releaseIntercepted() // this state machine instance is terminating
                if (completion is BaseContinuationImpl) {
                    // unrolling recursion via loop
                    current = completion
                    param = outcome
                } else {
                    // top-level completion reached -- invoke and return
                    completion.resumeWith(outcome)
                    return
                }
            }
        }
    }
```

__BaseContinuationImpl__恢复(回调)执行逻辑：

* 由下往上一级一级恢复(回调)__suspend方法__即调用__invokeSuspend()__方法

* 如果当前层级的__suspend__方法恢复(回调)之后挂起，则中断恢复(回调)过程

* 如果当前层级的__suspend__方法恢复(回调)之后未挂起，则封装该返回值并恢复(回调)上一层级的__suspend__方法

* 如果当前层级的__suspend__方法抛出异常，则catch该异常并恢复(回调)上一层级的__suspend__方法，上一层级的__suspend__方法收到该异常之后将重新抛出该异常，如此层层上传。








