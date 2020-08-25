###                                                                          Groovy解析之元编程原理

#### 引言

我们知道，Groovy 和 Java 一样是运行在 JVM上 的，而且 Groovy 代码最终也是编译成 Java 字节码；但是 Groovy 却是一门动态语言，可以在运行时扩展程序，比如动态调用（拦截、注入、合成）方法，那么 Groovy 是如何实现这一切的呢？

#### 动态调用入口：CallSite

其实这一切都要归功于 Groovy 编译器，Groovy 编译器在编译 Groovy 代码的时候，并不是像 Java 一样，直接编译成字节码，而是编译成 “__动态调用的字节码__”。

例如下面这一段 Groovy 代码：

```Groovy
package groovy

println("Hello World!")
```

当我们用Groovy编译器编译之后，就会变成：

```java
package groovy;

......

public class HelloGroovy extends Script {
    private static /* synthetic */ ClassInfo $staticClassInfo;
    public static transient /* synthetic */ boolean __$stMC;
    private static /* synthetic */ ClassInfo $staticClassInfo$;
    private static /* synthetic */ SoftReference $callSiteArray;
    ......
    public static void main(String ... args) {
        // 调用runScript()方法
        CallSite[] arrcallSite = HelloGroovy.$getCallSiteArray();
        arrcallSite[0].call(InvokerHelper.class, HelloGroovy.class, (Object)args);
    }

    public Object run() {
        // 调用println()方法
        CallSite[] arrcallSite = HelloGroovy.$getCallSiteArray();
        return arrcallSite[1].callCurrent((GroovyObject)this, (Object)"Hello World!");
    }
    ......
    private static /* synthetic */ void $createCallSiteArray_1(String[] arrstring) {
        arrstring[0] = "runScript";
        arrstring[1] = "println";
    }
    ......
}
```

简单的一行代码，经过 Groovy 编译器编译之后，变得如此复杂。而这就是 Groovy 编译器做的，将普通的代码编译成可以动态调用的代码。

不难发现，经过编译之后，几乎所有的方法调用都变成通过 __CallSite__进行了，这个 CallSite 就是实现动态调用的入口，我们来看看这个 CallSite 都做了什么？

##### AbstractCallSite

```Java
package org.codehaus.groovy.runtime.callsite;

/**
 * Base class for all call sites
 */
public class AbstractCallSite implements CallSite {
    ......
    // call()方法是运行时方法调用的时候才触发的
    public Object call(Object receiver, Object arg1) throws Throwable {
        CallSite stored = this.array.array[this.index];
        return stored != this ? stored.call(receiver, arg1) : this.call(receiver, ArrayUtil.createArray(arg1));
    }
    ......
    public Object call(Object receiver, Object[] args) throws Throwable {
        return CallSiteArray.defaultCall(this, receiver, args);
    }
}
```
__CallSite __ 主要负责__分发和缓存不同类型的方法调用逻辑__，包括 callGetPropertySafe(),  callGetProperty(),  callGroovyObjectGetProperty(),  callGroovyObjectGetPropertySafe(),  call(),  callCurrent(),  callStatic(),  callConstructor()等等

对于不同类型的方法调用需要通过不同的 CallSite 调用，因为针对不同类型的方法需要有不同的处理逻辑，否则可能会出现循环调用，抛出 StackOverflow 异常。例如对于当前对象(this)的方法调用需要通过 callCurrent()，对于static类型方法需要通过 callStatic()，而对于局部变量或者实例变量则是通过 call()；

以 call(...) 方法为例，可以看出：call(...) 方法首先判断是否存在缓存，如果存在则直接调用，否则调用CallSiteArray.defaultCall(...) 方法创建并缓存 CallSite：

##### CallSiteArray

```Java
package org.codehaus.groovy.runtime.callsite;

public final class CallSiteArray {
    ......
    private static CallSite createCallSite(CallSite callSite, Object receiver, Object[] args) {
        if (receiver == null) {
            // null值
            return new NullCallSite(callSite);
        } else {
            CallSite site;
            if (receiver instanceof Class) {
                // 静态方法
                site = createCallStaticSite(callSite, (Class)receiver, args);
            } else if (receiver instanceof GroovyObject) {
                // Groovy对象方法
                site = createPogoSite(callSite, receiver, args);
            } else {
                // Java对象方法
                site = createPojoSite(callSite, receiver, args);
            }

            // 缓存CallSite
            replaceCallSite(callSite, site);
            return site;
        }
    }
    ......
    private static CallSite createPogoSite(CallSite callSite, Object receiver, Object[] args) {
        if (receiver instanceof GroovyInterceptable) {
            // 直接创建PogoInterceptableSite
            return new PogoInterceptableSite(callSite);
        } else {
            MetaClass metaClass = ((GroovyObject)receiver).getMetaClass();
            // 调用MetaClassImpl的createPogoCallSite()方法 或 直接创建PogoMetaClassSite
            return (CallSite)(metaClass instanceof MetaClassImpl ? ((MetaClassImpl)metaClass).createPogoCallSite(callSite, args) : new PogoMetaClassSite(callSite, metaClass));
        }
    }
    ......
}
```

createCallSite(...) 方法针对不同的目标对象的分别创建不同的CallSite，简单的来说：

* 对于 null 值，创建 NullCallSite

* 对于静态方法，创建 StaticMetaClassSite

* 对于实现了 GroovyInterceptable 接口的 Groovy 对象，创建的是 PogoInterceptableSite。这也是为什么__实现了 GroovyInterceptable 接口的对象，任何方法调用最终都会调用 invokeMethod(...) 方法的原因。__

* 对于普通 Groovy 对象的方法，创建的是 PogoMetaClassSite：

* 对于普通 Java 对象的方法，创建的则是 PojoMetaClassSite

当然实际上，这个过程依然保持足够的开放和灵活性，并不是简单的根据方法类型来创建 CallSite，此处不再深入讨论，感兴趣的同学可以通过阅读源码一探究竟~

接下来，我们来看一下以 PogoMetaClassSite 为例，分析一下 CallSite 内部的方法调用分发逻辑：

##### PogoMetaClassSite

```java
package org.codehaus.groovy.runtime.callsite;

public class PogoMetaClassSite extends MetaClassSite {
    ....
    public final Object call(Object receiver, Object[] args) throws Throwable {
        if (checkCall(receiver)) {
            try {
                try {
                    // 调用metaClass的invokeMethod方法
                    return metaClass.invokeMethod(receiver, name, args);
                } catch (MissingMethodException e) {
                    // metaClass中未找到相应的方法，调用失败
                    if (e instanceof MissingMethodExecutionFailed) {
                        throw (MissingMethodException)e.getCause();
                    } else if (receiver.getClass() == e.getType() && e.getMethod().equals(name)) {
                        // in case there's nothing else, invoke the object's own invokeMethod()
                        // 调用GroovyObject的invokeMethod方法
                        return ((GroovyObject)receiver).invokeMethod(name, args);
                    } else {
                        throw e;
                    }
                }
            } catch (GroovyRuntimeException gre) {
                throw ScriptBytecodeAdapter.unwrap(gre);
            }
        } else {
          return CallSiteArray.defaultCall(this, receiver, args);
        }
    }
    ......
}
```

PogoMetaClassSite 内部的逻辑比较简单，可以看出，方法调用逻辑最终是委托给 __MetaClass__ 进行处理，如果 MetaClass 无法处理，则抛出异常或者调用 __GroovyObject__ 的 __invokeMethod__(...) 方法。

看到这里，其实我们已经可以看出 Groovy 实现动态特性的基本原理了：__经过 Groovy 编译器编译之后，所有的方法调用都会通过 Groovy 构建的系统进行调用，而这个系统正是实现其动态特性的关键。__

接下来，我们更进一步，分析一下 MetaClass 是如何分发方法调用的：

#### MetaClassImpl方法分发过程

##### invokeMethod

```java
package groovy.lang;

/**
 * Allows methods to be dynamically added to existing classes at runtime
 * @see groovy.lang.MetaClass
 */
public class MetaClassImpl implements MetaClass, MutableMetaClass {
    ......
    public Object invokeMethod(Object object, String methodName, Object[] originalArguments) {
        return invokeMethod(theClass, object, methodName, originalArguments, false, false);
    }
    
    ......
    public Object invokeMethod(Class sender, Object object, String methodName, Object[] originalArguments, boolean isCallToSuper, boolean fromInsideClass) {
        ......
        // 查找是否存在符合条件的方法
        MetaMethod method = getMetaMethod(sender, object, methodName, isCallToSuper, arguments);

        final boolean isClosure = object instanceof Closure;
        if (isClosure) {
            // 针对closure的处理逻辑，此处省略
            ......
        }

        if (method != null) {
            // 方法存在，调用该方法
            return method.doMethodInvoke(object, arguments);
        } else {
            // 方法不存在，调用invokePropertyOrMissing()方法
            return invokePropertyOrMissing(object, methodName, originalArguments, fromInsideClass, isCallToSuper);
        }
    }
    
    ......
    private MetaMethod getMetaMethod(Class sender, Object object, String methodName, boolean isCallToSuper, Object... arguments) {
        MetaMethod method = null;
        if (CLOSURE_CALL_METHOD.equals(methodName) && object instanceof GeneratedClosure) {
            // 如果调用的是GeneratedClosure的call()方法，则查找doCall()方法
            method = getMethodWithCaching(sender, "doCall", arguments, isCallToSuper);
        }
        if (method==null) {
            // 查找方法(允许优先使用前次查找的缓存)
            method = getMethodWithCaching(sender, methodName, arguments, isCallToSuper);
        }
        MetaClassHelper.unwrap(arguments);

        if (method == null)
            // 如果参数是List，则展开该List，再次查找
            method = tryListParamMetaMethod(sender, methodName, isCallToSuper, arguments);
        return method;
    }
    
    ......
    private MetaMethod tryListParamMetaMethod(Class sender, String methodName, boolean isCallToSuper, Object[] arguments) {
        MetaMethod method = null;
        if (arguments.length == 1 && arguments[0] instanceof List) {
            Object[] newArguments = ((List) arguments[0]).toArray();
            method = createTransformMetaMethod(getMethodWithCaching(sender, methodName, newArguments, isCallToSuper));
        }
        return method;
    }
    
    ......
    public MetaMethod getMethodWithCaching(Class sender, String methodName, Object[] arguments, boolean isCallToSuper) {
        // let's try use the cache to find the method
        if (!isCallToSuper && GroovyCategorySupport.hasCategoryInCurrentThread()) {
            // 查找方法(不允许优先使用前次查找的缓存，因为通过Category注入的方法优先级最高，需要重新查找)
            return getMethodWithoutCaching(sender, methodName, MetaClassHelper.convertToTypeArray(arguments), isCallToSuper);
        } else {
            final MetaMethodIndex.Entry e = metaMethodIndex.getMethods(sender, methodName);
            if (e == null)
              return null;

            // 查找super方法或者普通方法(允许优先使用前次查找的缓存)
            return isCallToSuper ? getSuperMethodWithCaching(arguments, e) : getNormalMethodWithCaching(arguments, e);
        }
    }
    
    ......  
}
```

首先，__通过调用 _getMetaMethod(...)_ 方法查找目标类及其父类是否存在该方法（包括参数类型兼容的方法）__:

* 如果调用的是__GeneratedClosure__的 _call(...)_ 方法，则转换成查找 _doCall(...)_ 方法.
* 如果调用的是 __this__ 方法，则__其优先级依次为 1.目标类及其父类通过 Category 注入的方法、2.目标类及其父类通过 MetaClass(ExpandoMetaClass) 注入的方法、3.目标类及其父类定义的方法。同时还需 *遵循定义在子类的方法优先于(覆盖)定义在父类的方法的原则* 。__
* __注意__：针对 __this__ 方法，此处只会查找 Category 注入的和已经在__方法列表(metaMethodIndex)__里的方法，而不会实时查找。实时查找的过程在下文的 __invokeMissingMethod(...)__ 方法触发，另外实时查找会把找到(新添加)的方法保存到这个方法列表中。
* 如果调用的是 __super(...)__ 方法，则只会查找其父类(当前类的 MetaClassImpl 初始化的时候)已有的方法，也就是说 super 的调用是__部分动态调用__。（调用 super 方法的时候，如果父类不存在该方法，则依然会走invokePropertyOrMissing 方法(子类 property 优先)）。
* 如果未找到相关方法并且方法参数只有一个 List 类型的参数，则会__尝试展开该List__并再次通过上面的逻辑进行查找。

接着，如果目标对象是闭包(Closure)，则需要走闭包的特殊处理逻辑，此处暂不讨论。

最后，如果找到了匹配的方法，则直接调用该方法；如果没有找到匹配的方法，则会调用 invokePropertyOrMissing(...) 方法。

接着我们来看看 invokePropertyOrMissing(...) 方法的处理逻辑：

##### invokePropertyOrMissing

```java
package groovy.lang;

/**
 * Allows methods to be dynamically added to existing classes at runtime
 * @see groovy.lang.MetaClass
 */
public class MetaClassImpl implements MetaClass, MutableMetaClass {
    ......
    private Object invokePropertyOrMissing(Object object, String methodName, Object[] originalArguments, boolean fromInsideClass, boolean isCallToSuper) {
        // if no method was found, try to find a closure defined as a field of the class and run it
        Object value = null;
        // 查找property
        final MetaProperty metaProperty = this.getMetaProperty(methodName, false);
        if (metaProperty != null)
          value = metaProperty.getProperty(object);
        else {
            // 注意此处针对Map的特殊处理逻辑
            if (object instanceof Map)
              value = ((Map)object).get(methodName);
        }

        // 如果property是Closure，则调用其doCall()方法
        if (value instanceof Closure) {  // This test ensures that value != this If you ever change this ensure that value != this
            Closure closure = (Closure) value;
            MetaClass delegateMetaClass = closure.getMetaClass();
            return delegateMetaClass.invokeMethod(closure.getClass(), closure, CLOSURE_DO_CALL_METHOD, originalArguments, false, fromInsideClass);
        }

        // 如果目标对象是Script，则查找binding variables，并调用其call()方法
        if (object instanceof Script) {
            Object bindingVar = ((Script) object).getBinding().getVariables().get(methodName);
            if (bindingVar != null) {
                MetaClass bindingVarMC = ((MetaClassRegistryImpl) registry).getMetaClass(bindingVar);
                return bindingVarMC.invokeMethod(bindingVar, CLOSURE_CALL_METHOD, originalArguments);
            }
        }
        
        // 调用invokeMissingMethod()方法
        return invokeMissingMethod(object, methodName, originalArguments, null, isCallToSuper);
    }
    ......
}
```
invokePropertyOrMissing(...) 方法的调用过程：

* __查找目标类及其父类是否存在与该方法同名的 Property，且该 Property 为 Closure 类型，则调用该Closure__。 __注意__：这里并不是通过 MetaClass 注入方法的实现逻辑，通过 MetaClass 注入的方法不会以Property 的形式存在(getter和setter方法除外)，而是以 ClosureMetaMethod 类型的数据保存在到目标类的方法列表中。

* 如果未找到相关 Property，且目标对象是 Script 类型，则尝试查找是否存在以该方法名命名的BindingVariable，如果存在则调用其 call (...) 方法。

* 如果以上尝试均失败了，则调用 invokeMissingMethod(...) 方法

##### invokeMissingMethod

```java
package groovy.lang;

public class MetaClassImpl implements MetaClass, MutableMetaClass {
    ......
    private Object invokeMissingMethod(Object instance, String methodName, Object[] arguments, RuntimeException original, boolean isCallToSuper) {
        // 注意这段逻辑，仅针对非super.xxx() 调用
        if (!isCallToSuper) {
            Class instanceKlazz = instance.getClass();
            if (theClass != instanceKlazz && theClass.isAssignableFrom(instanceKlazz))
              instanceKlazz = theClass;

            Class[] argClasses = MetaClassHelper.castArgumentsToClassArray(arguments);
            
            // 查找并调用MixIn注入的方法
            MetaMethod method = findMixinMethod(methodName, argClasses);
            if(method != null) {
                onMixinMethodFound(method); // 空函数
                return method.invoke(instance, arguments);
            }

            // 遍历ClassHierarchy，再一次查找并调用MetaMethod或者SubClassMethod
            method = findMethodInClassHierarchy(instanceKlazz, methodName, argClasses, this);
            if(method != null) {
                // 将找到的方法保存到方法列表中
                onSuperMethodFoundInHierarchy(method);
                return method.invoke(instance, arguments);
            }

            // 遍历ClassHierarchy，查找并调用动态注入的invokeMethod()方法
            // still not method here, so see if there is an invokeMethod method up the hierarchy
            final Class[] invokeMethodArgs = {String.class, Object[].class};
            method = findMethodInClassHierarchy(instanceKlazz, INVOKE_METHOD_METHOD, invokeMethodArgs, this );
            if(method instanceof ClosureMetaMethod) {
                // 将找到的方法保存到方法列表中
                onInvokeMethodFoundInHierarchy(method);
                return method.invoke(instance, invokeMethodArgs);
            }

            // 查找并调用Category注入的methodMissing()方法
            // last resort look in the category
            if (method == null && GroovyCategorySupport.hasCategoryInCurrentThread()) {
                method = getCategoryMethodMissing(instanceKlazz);
                if (method != null) {
                    return method.invoke(instance, new Object[]{methodName, arguments});
                }
            }
        }

        // 尝试调用methodMissing()方法
        if (methodMissing != null) {
            try {
                return methodMissing.invoke(instance, new Object[]{methodName, arguments});
            } catch (InvokerInvocationException iie) {
                ......
            }
        }
    } 
    ......
}
```

invokeMissingMethod(...) 方法的调用过程如下，__如果调用的是 this 方法，则：__

* __查找是否存在通过 MixIn 注入的方法，如果存在则调用该方法。__

* 遍历 ClassHierarchy（__superClasses__和__interfaces__），再一次查找是否存在符合条件的 MetaMethod 或者SubClassMethod 方法（如果存在多个，则返回匹配度最高的）。__SubClassMethod 是定义在目标类或者实例范围内的动态方法，其作用域仅限于目标类或实例__。

* 遍历 ClassHierarchy，查找是否存在 __invokeMethod(...)__ 方法（如果存在多个，则返回匹配度最高的），且该方法是 _ClosureMetaMethod_ 类型(即通过 MetaClass 注入的拦截方法)，如果存在则调用该方法。

* 如果通过以上逻辑还是查找不到任何我们要调用的方法信息，那么就会__尝试调用  _methodMissing(...)_ 方法__。__这里会优先调用通过Category注入的 _methodMissing(...)_ 方法，如果未找到才调用(定义或注入的) _methodMissing(...)_ 方法__

以上几步均是针对this方法的处理逻辑，__如果调用的是 super(...) 方法，那么会直接尝试调用(定义或注入的) _methodMissing(...)_ 方法__

最后，__如果 _methodMissing(...)_ 方法也不存在，就抛出 MissingMethodException 异常__

至此，我们已经完整的分析了方法查找和分发的流程，也看到了__Category __和 __MixIn__ 注入的方法是如何被调用到的。那么 Groovy 又是如何将 MetaClass 和类或者实例绑定在一起的呢？

#### MetaClass的初始化过程

首先，我们来分析一下 MetaClass 从创建到初始化的过程，以 GroovyObjectSupport 这个官方基类为例：

##### GroovyObjectSupport

```java
package groovy.lang;

/**
 * A useful base class for Java objects wishing to be Groovy objects
 */
public abstract class GroovyObjectSupport implements GroovyObject {
    // never persist the MetaClass
    private transient MetaClass metaClass;

    public GroovyObjectSupport() {
        this.metaClass = getDefaultMetaClass();
    }
    ......
    public Object invokeMethod(String name, Object args) {
        return getMetaClass().invokeMethod(this, name, args);
    }
    ......
    private MetaClass getDefaultMetaClass() {
        return InvokerHelper.getMetaClass(this.getClass());
    }
}
```

默认的 MetaClass 是通过 InvokerHelper.getMetaClass(this.getClass()) 获取的。

##### InvokerHelper

```java
package org.codehaus.groovy.runtime;

/**
 * A static helper class to make bytecode generation easier and act as a facade over the Invoker
 */
public class InvokerHelper {
    ......
    public static final MetaClassRegistry metaRegistry = GroovySystem.getMetaClassRegistry();
    ......
    public static MetaClass getMetaClass(Class cls) {
        return metaRegistry.getMetaClass(cls);
    }
    ......
}
```

而 InvokerHelper 则是通过 MetaClassRegistryImpl 创建并获取 MetaClass 的。接下来，我们来看一下 MetaClassRegistryImpl 是如何创建和初始化 MetaClass 的：

##### MetaClassRegistryImpl

```java
package org.codehaus.groovy.runtime.metaclass;

/**
 * A registry of MetaClass instances which caches introspection &
 * reflection information and allows methods to be dynamically added to
 * existing classes at runtime
 */
public class MetaClassRegistryImpl implements MetaClassRegistry{
    ......
    public final MetaClass getMetaClass(Class theClass) {
        return ClassInfo.getClassInfo(theClass).getMetaClass();
    }
    ......
}
```

MetaClassRegistryImpl 是通过 ClassInfo 创建并获取 MetaClass 实例的：

##### ClassInfo

```java
package org.codehaus.groovy.reflection;

/**
 * Handle for all information we want to keep about the class
 */
public class ClassInfo implements Finalizable {
    ......
    public final MetaClass getMetaClass() {
        MetaClass answer = getMetaClassForClass();
        if (answer != null) return answer;

        lock();
        try {
            return getMetaClassUnderLock();
        } finally {
            unlock();
        }
    }
    
    ......
    private MetaClass getMetaClassUnderLock() {
        // 是否已经创建有缓存
        MetaClass answer = getStrongMetaClass();
        if (answer!=null) return answer;
        
        answer = getWeakMetaClass();
        final MetaClassRegistry metaClassRegistry = GroovySystem.getMetaClassRegistry();
        MetaClassRegistry.MetaClassCreationHandle mccHandle = metaClassRegistry.getMetaClassCreationHandler();
        
        if (isValidWeakMetaClass(answer, mccHandle)) {
            return answer;
        }

        // 创建MetaClass实例
        answer = mccHandle.create(classRef.get(), metaClassRegistry);
        answer.initialize();

        // 缓存MetaClass实例
        if (GroovySystem.isKeepJavaMetaClasses()) {
            setStrongMetaClass(answer);
        } else {
            setWeakMetaClass(answer);
        }
        return answer;
    }
    ......
}
```
__ClassInfo是实际上创建和保存MetaClass的类，如果已创建则直接返回；如果未创建则创建并保存MetaClass。每个类都绑定一个MetaClass实例以及一个ClassInfo实例，用于保存相关信息__。

接下来，我们来看一下MetaClassImpl初始化的过程：

##### MetaClassImpl

```java
package groovy.lang;

public class MetaClassImpl implements MetaClass, MutableMetaClass {
    ......
    public synchronized void initialize() {
        if (!isInitialized()) {
            // 解析目标类及其父类的所有方法，并保存到方法列表
            fillMethodIndex();
            try {
                // 解析目标类及其父类的所有属性，并保存到属性列表
                addProperties();
            } catch (Throwable e) {
                if (!AndroidSupport.isRunningAndroid()) {
                    UncheckedThrow.rethrow(e);
                }
                // Introspection failure...
                // May happen in Android
            }
            initialized = true;
        }
    }
    ......
}
```
MetaClassImpl 在初始化的时候就会通过反射解析目标类及其父类的所有方法（Methods）和属性（Fields和Setters以及Getters），甚至包括其父类已经动态注入的方法（getNewMetaMethods），并保存到方法列表和属性列表。

那么，如果父类在子类已经初始化 MetaClass 之后再动态注入的方法，子类是不是就存在调用不到该方法的可能呢？事实上，在上文我们分析 __invokeMissingMethod(...)__ 这个方法的时候，提到__如果调用的是 this 方法，则会遍历 ClassHierarchy 再次查找方法，其中就包含 superClasses 或者 interfaces 在初始化之后动态注入的方法。而如果是 super(...) 方法，则无此逻辑。__因此，可以看出，在子类初始化之后再注入父类的方法其优先级是非常低的。

至此，我们已经讲完了 MetaClassImpl 从创建到初始化的过程。但是还有几个问题没有弄清楚，比如 Groovy 官方提供的方法（例如use方法）是如何注入的呢？以及 ExtensionModule 又是如何注入的？


#### Groovy系统方法的初始化过程

##### MetaClassRegistryImpl

我们不妨先来看一下 MetaClassRegistryImpl 在初始化的时候都做了什么？

```java
package org.codehaus.groovy.runtime.metaclass;

/**
 * A registry of MetaClass instances which caches introspection &
 * reflection information and allows methods to be dynamically added to
 * existing classes at runtime
 */
public class MetaClassRegistryImpl implements MetaClassRegistry{
    ......
    public MetaClassRegistryImpl(final int loadDefault, final boolean useAccessible) {
        this.useAccessible = useAccessible;

        if (loadDefault == LOAD_DEFAULT) {
            final Map<CachedClass, List<MetaMethod>> map = new HashMap<CachedClass, List<MetaMethod>>();

            // let's register the default methods
            // 注册DefaultGroovyMethods
            registerMethods(null, true, true, map);
            final Class[] additionals = DefaultGroovyMethods.ADDITIONAL_CLASSES;
            for (int i = 0; i != additionals.length; ++i) {
                createMetaMethodFromClass(map, additionals[i]);
            }

            // 注册java版本兼容实例方法
            Class[] pluginDGMs = VMPluginFactory.getPlugin().getPluginDefaultGroovyMethods();
            for (Class plugin : pluginDGMs) {
                registerMethods(plugin, false, true, map);
            }
            
            // 注册DefaultGroovyStaticMethods
            registerMethods(DefaultGroovyStaticMethods.class, false, false, map);
            
            // 注册java版本兼容静态方法
            Class[] staticPluginDGMs = VMPluginFactory.getPlugin().getPluginStaticGroovyMethods();
            for (Class plugin : staticPluginDGMs) {
                registerMethods(plugin, false, false, map);
            }

            // 解析注册ExtensionModule方法
            ExtensionModuleScanner scanner = new ExtensionModuleScanner(new DefaultModuleListener(map), this.getClass().getClassLoader());
            scanner.scanClasspathModules();

            refreshMopMethods(map);

        }

        installMetaClassCreationHandle();

        final MetaClass emcMetaClass = metaClassCreationHandle.create(ExpandoMetaClass.class, this);
        emcMetaClass.initialize();
        ClassInfo.getClassInfo(ExpandoMetaClass.class).setStrongMetaClass(emcMetaClass);
        ......
    }
    
    ......
    private void registerMethods(final Class theClass, final boolean useMethodWrapper, final boolean useInstanceMethods, Map<CachedClass, List<MetaMethod>> map) {
        if (useMethodWrapper) {
            // Here we instantiate objects representing MetaMethods for DGM methods.
            // Calls for such meta methods done without reflection, so more effectively.

            try {
                // 解析dgminfo文件，并注册相关方法
                List<GeneratedMetaMethod.DgmMethodRecord> records = GeneratedMetaMethod.DgmMethodRecord.loadDgmInfo();

                for (GeneratedMetaMethod.DgmMethodRecord record : records) {
                    Class[] newParams = new Class[record.parameters.length - 1];
                    System.arraycopy(record.parameters, 1, newParams, 0, record.parameters.length-1);

                    MetaMethod method = new GeneratedMetaMethod.Proxy(
                            record.className,
                            record.methodName,
                            ReflectionCache.getCachedClass(record.parameters[0]),
                            record.returnType,
                            newParams
                    );
                    final CachedClass declClass = method.getDeclaringClass();
                    List<MetaMethod> arr = map.get(declClass);
                    if (arr == null) {
                        arr = new ArrayList<MetaMethod>(4);
                        map.put(declClass, arr);
                    }
                    arr.add(method);
                    instanceMethods.add(method);
                }
            } catch (Throwable e) {
                e.printStackTrace();
                // we print the error, but we don't stop with an exception here
                // since it is more comfortable this way for development
            }
        } else {
            CachedMethod[] methods = ReflectionCache.getCachedClass(theClass).getMethods();

            for (CachedMethod method : methods) {
                final int mod = method.getModifiers();
                if (Modifier.isStatic(mod) && Modifier.isPublic(mod) && method.getCachedMethod().getAnnotation(Deprecated.class) == null) {
                    CachedClass[] paramTypes = method.getParameterTypes();
                    if (paramTypes.length > 0) {
                        List<MetaMethod> arr = map.get(paramTypes[0]);
                        if (arr == null) {
                            arr = new ArrayList<MetaMethod>(4);
                            map.put(paramTypes[0], arr);
                        }
                        if (useInstanceMethods) {
                            // 实例方法
                            final NewInstanceMetaMethod metaMethod = new NewInstanceMetaMethod(method);
                            arr.add(metaMethod);
                            instanceMethods.add(metaMethod);
                        } else {
                            // 静态方法
                            final NewStaticMetaMethod metaMethod = new NewStaticMetaMethod(method);
                            arr.add(metaMethod);
                            staticMethods.add(metaMethod);
                        }
                    }
                }
            }
        }
    }
}
```

MetaClassRegistryImpl 在其实例化的时候就会通过 __registerMethods(...)__ 方法去加载默认的方法，包括：

* __加载 DefaultGroovyMethod 即 DgmMethod__。DgmMethod 是一系列定义在 org.codehaus.groovy.runtime 包下，以 dgm$n 命名的方法类，其信息存储在 __/META-INF/dgminfo__ 文件上。registerMethods(...) 先读取该文件的内容，然后根据文件内容载入这些方法，并以 __NewInstanceMetaMethod(实例方法)__ 的形式保存到各自定义类的方法列表上。这些方法是 Groovy 系统提供的一些动态方法，例如use，with等等。这就是为什么我们可以直接使用这些方法的原因：MetaClassRegistryImpl 在实例化的时候就把这些方法加载进来了，而且其目标类普遍为基类，如Objec、String、Collection等等，这就保证了我们可以在这些类及其子类上正常的调用这些方法。

* 通过 registerMethods(...) 加载 VMPluginFactory.getPlugin().getPluginDefaultGroovyMethods() 这个方法返回的所有类的静态方法，并以 NewInstanceMetaMethod(实例方法) 的形式保存到目标类(方法的第一个参数对应的类)的方法信息上，然后在调用的时候转换成调用静态方法，并插入调用的对象作为第一个参数(类似于通过Category注入方法)。这些方法是为了支持不同版本的JVM而提供的兼容方法，不需要过多关注。

* __通过 registerMethods(...) 去加载 DefaultGroovyStaticMethods 类提供的静态方法__。这些静态方法会以 __NewStaticMetaMethod(静态方法)__ 的形式保存到目标类(方法的第一个参数对应的类)的方法列表上，然后在调用的时候插入一个null值作为第一个参数，例如 Thread.start() 方法等等。

* 通过 registerMethods(...) 加载 VMPluginFactory.getPlugin().getPluginStaticGroovyMethods() 这个方法返回的所有类的静态方法，以 NewStaticMetaMethod(静态方法) 的形式保存到目标类(方法的第一个参数对应的类)的方法信息上，然后在调用的时候插入一个 null 值作为第一个参数。这些方法也是为了支持不同版本的JVM而提供的兼容方法，不需要过多关注。

* 最后，__通过 ExtensionModuleScanner 加载默认的 ExtensionModule 方法__


#### 动态注入原理：ExpandoMetaClass

##### ExpandoMetaClass

上文我们提到类或者实例默认绑定的 MetaClass 是 MetaClassImpl 类型的实例，但是事实上，__当我们通 metaClass 注入方法的时候，其实是通过 ExpandoMetaClass 注入的__，我们不妨简单看一下 ExpandoMetaClass 的代码：

```java
package groovy.lang;

public class ExpandoMetaClass extends MetaClassImpl implements GroovyObject {
    ......
    private final Set<MetaMethod> inheritedMetaMethods = new HashSet<MetaMethod>();
    private final Map<String, MetaProperty> beanPropertyCache = new ConcurrentHashMap<String, MetaProperty>(16, 0.75f, 1);
    private final Map<String, MetaProperty> staticBeanPropertyCache = new ConcurrentHashMap<String, MetaProperty>(16, 0.75f, 1);
    private final Map<MethodKey, MetaMethod> expandoMethods = new ConcurrentHashMap<MethodKey, MetaMethod>(16, 0.75f, 1);

    public Collection getExpandoSubclassMethods() {
        return expandoSubclassMethods.values();
    }

    private final ConcurrentHashMap expandoSubclassMethods = new ConcurrentHashMap(16, 0.75f, 1);
    private final Map<String, MetaProperty> expandoProperties = new ConcurrentHashMap<String, MetaProperty>(16, 0.75f, 1);
    private ClosureStaticMetaMethod invokeStaticMethodMethod;
    private final Set<MixinInMetaClass> mixinClasses = new LinkedHashSet<MixinInMetaClass>();
    
    ......
    // 调用实例方法
    public Object invokeMethod(Class sender, Object object, String methodName, Object[] originalArguments, boolean isCallToSuper, boolean fromInsideClass) {
        // 判断是否通过metaClass注册了 invokeMethod() 方法
        if (invokeMethodMethod != null) {
            MetaClassHelper.unwrap(originalArguments);
            return invokeMethodMethod.invoke(object, new Object[]{methodName, originalArguments});
        }
        return super.invokeMethod(sender, object, methodName, originalArguments, isCallToSuper, fromInsideClass);
    }
    
    ......
    // 调用静态方法
    public Object invokeStaticMethod(Object object, String methodName, Object[] arguments) {
        // 判断是否通过metaClass注册了 invokeStaticMethod() 方法
        if (invokeStaticMethodMethod != null) {
            MetaClassHelper.unwrap(arguments);
            return invokeStaticMethodMethod.invoke(object, new Object[]{methodName, arguments});
        }
        return super.invokeStaticMethod(object, methodName, arguments);
    }
    
    ...... 
    // 动态注入方法
    public void setProperty(String property, Object newValue) {
        if (newValue instanceof Closure) {
            if (property.equals(CONSTRUCTOR)) {
                property = GROOVY_CONSTRUCTOR;
            }
            Closure callable = (Closure) newValue;
            final List<MetaMethod> list = ClosureMetaMethod.createMethodList(property, theClass, callable);
            for (MetaMethod method : list) {
                // here we don't care if the method exists or not we assume the
                // developer is responsible and wants to override methods where necessary
                // 注册绑定类实例方法
                registerInstanceMethod(method);
            }
        } else {
            registerBeanProperty(property, newValue);
        }
    }
    
    ......
    // 动态注入方法
     public Object invokeMethod(String name, Object args) {
        final Object[] argsArr = args instanceof Object[] ? (Object[]) args : new Object[]{args};
        MetaMethod metaMethod = myMetaClass.getMetaMethod(name, argsArr);
        if (metaMethod != null) {
            // we have to use doMethodInvoke here instead of simply invoke,
            // because getMetaMethod may provide a method that can not be called
            // without further argument transformation, which is done only in 
            // doMethodInvoke
            return metaMethod.doMethodInvoke(this, argsArr);
        }

        if (argsArr.length == 2 && argsArr[0] instanceof Class && argsArr[1] instanceof Closure) {
            if (argsArr[0] == theClass)
                // 注册绑定类的实例方法
                registerInstanceMethod(name, (Closure) argsArr[1]);
            else {
                // 注册Subclass实例方法
                registerSubclassInstanceMethod(name, (Class) argsArr[0], (Closure) argsArr[1]);
            }
            return null;
        }

        if (argsArr.length == 1 && argsArr[0] instanceof Closure) {
            registerInstanceMethod(name, (Closure) argsArr[0]);
            return null;
        }

        throw new MissingMethodException(name, getClass(), argsArr);
    }
    ......
}
```
ExpandoMetaClass 保存了动态注入的方法和属性的信息，其中包括：

* mixinClasses 是通过 MixIn 动态注入的类信息

* invokeMethodMethod 是动态注入的 __“invokeMethod(...)”__ 方法

* invokeStaticMethodMethod 是动态注入的 __“invokeStaticMethod(...)”__ 方法

* expandoMethods 是通过 metaClass 动态注入的方法集合

* expandoSubclassMethods 是通过 metaClass动态 注入的 subclass 方法集合

* beanPropertyCache 是动态注入的实例属性集合

* staticBeanPropertyCache 是动态注入的静态属性集合

另外，从 ExpandoMetaClass 的代码，我们也可以看出，__如果一个类或实例，通过 metaClass 注入了 _“invokeMethod(...)”_ 拦截方法，那么任何的方法调用都会调用该方法；__

由上文可知，当我们设置属性值或者调用方法的时候，如果该属性或者方法不存在，则会调用 __setProperty(...)__ 和 __invokeMethod(...)__（注意区分是哪个invokeMethod），这条规则当然也适用于 ExpandoMetaClass。而 ExpandoMetaClass 正是利用这条规则实现动态注入方法：在调用 __setProperty(...)__ 和 __invokeMethod(...)__ 时检查参数是否是 Closure 类型的，如果是且符合其他条件，则将该 Closure 封装成 MetaMethod 并保存到方法列表中。

当然 ExpandoMetaClass 实现动态注入方法的逻辑远不止如此（例如MixIn），此处不再深入分析，感兴趣的同学可以阅读 ExpandoMetaClass 源码，一探究竟。

##### HandleMetaClass

既然类或者实例默认绑定的 MetaClass 是 MetaClassImpl 类的实例，那么当我们通过 _metaClass_ 动态注入方法的时候，又是__如何切换到 ExpandoMetaClass 的__呢？

事实上，当我们通过metaClass注入方法的时候，例如下面这一段代码:

```java
String.metaClass.sayHello = { ->
    "Hello ${delegate}!"
}
```
Groovy编译器会将其编译成：

```java
......
public Object run() {
    _run_closure1 _run_closure12 = new _run_closure1((Object)this, (Object)this);
    ScriptBytecodeAdapter.setProperty(
        (Object)((Object)_run_closure12), null,
        ( Object)arrcallSite[1].callGetProperty(String.class),
        (String)"sayHello"
    );       
}

......
private static /* synthetic */ void $createCallSiteArray_1(String[] arrstring) {
    arrstring[0] = "runScript";
    arrstring[1] = "metaClass";
}
```

我们发现，Groovy会将这个 __.metaClass__ 调用编译成  __getProperty(String.class，"metaClass")__。上文我们说到，MetaClassImplRegistry 在实例化的时候，即会加载 GroovyDefaultMethod，其中就包括定义在 Class 类及Object 类上的 __getMetaClass(...)__ 方法。因此  __getProperty(String.class，"metaClass")__ 这个方法最终会调用到定义在 DefaultGroovyMethods 中的这个 __getMetaClass(...)__ 方法。

这里面涉及到一个知识点：
    __Groovy的getProperty(...) 方法除了查找类中定义的 Field 之外，还会查找动态注入的 Property 以及 Getter() 方法。
    与此同时 getAttribute(...) 方法却只会查找类中定义的 Field。__

```java
package org.codehaus.groovy.runtime;

public class DefaultGroovyMethods extends DefaultGroovyMethodsSupport {
    ......
    public static MetaClass getMetaClass(Class c) {
        MetaClassRegistry metaClassRegistry = GroovySystem.getMetaClassRegistry();
        MetaClass mc = metaClassRegistry.getMetaClass(c);
        if (mc instanceof ExpandoMetaClass
                || mc instanceof DelegatingMetaClass && ((DelegatingMetaClass) mc).getAdaptee() instanceof ExpandoMetaClass)
            return mc;
        else {
            // 创建HandleMetaClass
            return new HandleMetaClass(mc);
        }
    }
    
    public static MetaClass getMetaClass(Object obj) {
        MetaClass mc = InvokerHelper.getMetaClass(obj);
        // 创建HandleMetaClass
        return new HandleMetaClass(mc, obj);
    }
    
    public static MetaClass getMetaClass(GroovyObject obj) {
        // we need this method as trick to guarantee correct method selection
        return getMetaClass((Object)obj);
    }
    ......
}
```

从源码可以看出，当我们以  _String.metaClass_ 这种形式访问 _metaClass_ 这个 Property 的时候，如果 String 这个类绑定的 MetaClass 不是 ExpandoMetaClass 类型的实例，那么就会创建并返回一个 HandleMetaClass 类的实例。

__HandleMetaClass 只是一个代理类，封装和延迟了创建 ExpandoMetaClass 的时机__：

```java
package org.codehaus.groovy.runtime;

public class HandleMetaClass extends DelegatingMetaClass {
    ......
    public void initialize() {
        replaceDelegate();
        delegate.initialize();
    }

    /**
     * 创建和更新类或实例绑定的MetaClass
     */
    public GroovyObject replaceDelegate() {
        if (object == null) {
            // 绑定类
            if (!(delegate instanceof ExpandoMetaClass)) {
              // 创建和初始化ExpandoMetaClass
              delegate = new ExpandoMetaClass(delegate.getTheClass(), true, true);
              delegate.initialize();
            }
            DefaultGroovyMethods.setMetaClass(delegate.getTheClass(), delegate);
        }
        else {
          // 绑定实例
          if (object != NONE) {
              final MetaClass metaClass = delegate;
              // 创建和初始化ExpandoMetaClass
              delegate = new ExpandoMetaClass(delegate.getTheClass(), false, true);
              if (metaClass instanceof ExpandoMetaClass) {
                  ExpandoMetaClass emc = (ExpandoMetaClass) metaClass;
                  for (MetaMethod method : emc.getExpandoMethods())
                    ((ExpandoMetaClass)delegate).registerInstanceMethod(method);
              }
              delegate.initialize();
              MetaClassHelper.doSetMetaClass(object, delegate);
              object = NONE;
          }
        }
        return (GroovyObject)delegate;
    }

    /** 
     * 方法调用也会触发更新MetaClass
     */
    public Object invokeMethod(String name, Object args) {
        return replaceDelegate().invokeMethod(name, args);
    }
    ......
}
```

HandleMetaClass 在初始化或者调用到相关方法的时候，就会为这个类或实例创建一个新的ExpandoMetaClass，并更新该类或对象所绑定的 MetaClass 信息。此时，我们就可以通过 metaClass 动态注入方法了。

因为每个类或实例所绑定的 ExpandoMetaClass 是唯一的，由此可知 __在类上动态注入的方法是全局，而在实例上动态注入的方法则是局部的__。

#### Category实现原理

略

#### MixIn实现原理

略

#### ExtensionModule实现原理

接下来，我们来分析一下 ExtensionModule 是如何实现，RTFSC：

```java
package groovy.grape

/**
 * Implementation supporting {@code @Grape} and {@code @Grab} annotations based on Ivy.
 */
class GrapeIvy implements GrapeEngine {
    ......
    @CompileStatic
    private processCategoryMethods(ClassLoader loader, File file) {
        // register extension methods if jar
        if (file.name.toLowerCase().endsWith(".jar")) {
            def mcRegistry = GroovySystem.metaClassRegistry
            if (mcRegistry instanceof MetaClassRegistryImpl) {
                try (JarFile jar = new JarFile(file)) {
                    // 查找META-INF文件夹
                    def entry = jar.getEntry(ExtensionModuleScanner.MODULE_META_INF_FILE)
                    if (!entry) {
                        entry = jar.getEntry(ExtensionModuleScanner.LEGACY_MODULE_META_INF_FILE)
                    }
                    if (entry) {
                        Properties props = new Properties()

                        // 读取ExtensionModule文件
                        try (InputStream is = jar.getInputStream(entry)) {
                            props.load(is)
                        }

                        // 解析并注册相关方法
                        Map<CachedClass, List<MetaMethod>> metaMethods = new HashMap<CachedClass, List<MetaMethod>>()
                        mcRegistry.registerExtensionModuleFromProperties(props, loader, metaMethods)
                        // add old methods to the map
                        metaMethods.each { CachedClass c, List<MetaMethod> methods ->
                            // GROOVY-5543: if a module was loaded using grab, there are chances that subclasses
                            // have their own ClassInfo, and we must change them as well!
                            Set<CachedClass> classesToBeUpdated = [c].toSet()
                            ClassInfo.onAllClassInfo { ClassInfo info ->
                                if (c.theClass.isAssignableFrom(info.cachedClass.theClass)) {
                                    classesToBeUpdated << info.cachedClass
                                }
                            }
                            classesToBeUpdated*.addNewMopMethods(methods)
                        }
                    }
                } catch(ZipException zipException) {
                    throw new RuntimeException("Grape could not load jar '$file'", zipException)
                }
            }
        }
    }
    ......
}
```
Groovy  在加载依赖的 jar 包的时候，会查找是否存在 __"META-INF/services/org.codehaus.groovy.runtime.ExtensionModule"__ 或者 __"META-INF/groovy/org.codehaus.groovy.runtime.ExtensionModule"__ 这两个文件，如果存在则说明定义了ExtensionModule，那么就会读取这两个文件的内容。

```java
package org.codehaus.groovy.runtime.metaclass;

/**
 * A registry of MetaClass instances which caches introspection &
 * reflection information and allows methods to be dynamically added to
 * existing classes at runtime
 */
public class MetaClassRegistryImpl implements MetaClassRegistry{
    ......
    public void registerExtensionModuleFromProperties(final Properties properties, final ClassLoader classLoader, final Map<CachedClass, List<MetaMethod>> map) {
        ExtensionModuleScanner scanner = new ExtensionModuleScanner(new DefaultModuleListener(map), classLoader);
        scanner.scanExtensionModuleFromProperties(properties);
    }
    
    ......
    private class DefaultModuleListener implements ExtensionModuleScanner.ExtensionModuleListener {
        private final Map<CachedClass, List<MetaMethod>> map;

        public DefaultModuleListener(final Map<CachedClass, List<MetaMethod>> map) {
            this.map = map;
        }

        public void onModule(final ExtensionModule module) {
            ......
            moduleRegistry.addModule(module);
            // register MetaMethods
            List<MetaMethod> metaMethods = module.getMetaMethods();
            for (MetaMethod metaMethod : metaMethods) {
                CachedClass cachedClass = metaMethod.getDeclaringClass();
                List<MetaMethod> methods = map.get(cachedClass);
                if (methods == null) {
                    methods = new ArrayList<MetaMethod>(4);
                    map.put(cachedClass, methods);
                }
                methods.add(metaMethod);
                if (metaMethod.isStatic()) {
                    // 静态方法
                    staticMethods.add(metaMethod);
                } else {
                    // 实例方法
                    instanceMethods.add(metaMethod);
                }
            }
        }
    }
}
```
读取 __ExtensionModule文件__ 内容之后，就可以找到定义 ExtensionModule 方法的类，然后加载该类及其中定义的静态方法，并分别以 NewStaticMetaMethod(静态方法) 和 NewInstanceMetaMethod(实例方法) 的形式保存到目标类(方法的第一个参数对应的类)的方法列表中。__NewStaticMetaMethod(静态方法) __和 __NewInstanceMetaMethod(实例方法) __这两种方法在调用的时候会分别插入 __null值__ 和 __当前对象__ 作为方法的第一个参数，然后再通过反射调用真正的 Method。

因此，当我们定义 StaticExtensionMethod 的时候，应慎用第一个参数，因为它的值可能为 null，这个参数只是为了标识目标类。而当我们定义 InstanceExtensionMethod 的时候，其第一个参数就是目标类的实例对象。







