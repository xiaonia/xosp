###  Kotlin之static

[object-declarations.html](https://kotlinlang.org/docs/reference/object-declarations.html)

[extensions.html](https://kotlinlang.org/docs/reference/extensions.html)

[functions.html](https://kotlinlang.org/docs/reference/functions.html)



#### static

kotlin中没有__static__这个关键字，取而代之的是 __top-level function__、__extension function__ 和 __companion object__



#### top-level function

源代码：

```kotlin
fun topLevelFun() { ... }
```

编译之后：

```java
public final class LearnKotlinKt {
   public static final void topLevelFun() { ... }
}
```



#### extension function

源代码：

```kotlin
fun String.extensionFun() { ... }
```

编译之后：

```java
public final class LearnKotlinKt {
   public static final void extensionFun(@NotNull String $this$extensionFun) {
      Intrinsics.checkParameterIsNotNull($this$extensionFun, "$this$extensionFun");
   }
}
```



#### companion object

源代码：

```kotlin
class TestClass {
    companion object {
        val companionField: String = "companionField"

        fun companionFun() { ... }
    }
}
```

编译之后：

```java
public final class TestClass {
   @NotNull
   private static final String companionField = "companionField";
   
   public static final TestClass.Companion Companion = new TestClass.Companion((DefaultConstructorMarker)null);

   @Metadata(...)
   public static final class Companion {
      @NotNull
      public final String getCompanionField() {
         return TestClass.companionField;
      }

      public final void companionFun() { ... }
      
      private Companion() { ... }

      public Companion(DefaultConstructorMarker $constructor_marker) {
         this();
      }
   }
}
```



#### @JvmStatic

@JvmStatic注解用来指示编译器将一个方法或者变量编译成Java的static方法或者变量，例如：

源代码：

```kotlin
class TestClass {
    companion object {
        @JvmStatic
        val staticField: String = "staticField"
        
        @JvmStatic
        fun staticFun() { ... }
    }
}
```

编译之后：

```java
public final class TestClass {
   @NotNull
   private static final String staticField = "staticField";
   
   public static final TestClass.Companion Companion = new TestClass.Companion((DefaultConstructorMarker)null);

   @NotNull
   public static final String getStaticField() {
      TestClass.Companion var10000 = Companion;
      return staticField;
   }
   
   @JvmStatic
   public static final void staticFun() {
      Companion.staticFun();
   }

   @Metadata(...)
   public static final class Companion {
      /** @deprecated */
      @JvmStatic
      public static void staticField$annotations() { ... }

      @NotNull
      public final String getStaticField() {
         return TestClass.staticField;
      }
      
      @JvmStatic
      public final void staticFun() { ... }

      private Companion() { ... }

      public Companion(DefaultConstructorMarker $constructor_marker) {
         this();
      }
   }
}
```

同时我们注意到无论是否加@JvmStatic注解，companion object中的变量都会以__static field__的形式存在，而区别在于是否有__static的setter和getter__方法