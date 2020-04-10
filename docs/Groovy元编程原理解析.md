###                                      Groovy元编程原理解析

#### 引言

我们知道，Groovy和Java一样是运行在JVM上的，而且Groovy代码最终也是编译成Java字节码；但是Groovy却是一门动态语言，可以在运行时扩展程序，比如动态调用（拦截、注入、合成）方法，那么Groovy是如何实现这一切的呢？

#### 初探

其实这一切都要归功于Groovy编译器，Groovy编译器在编译Groovy代码的时候，并不是像Java一样，直接编译成字节码，而是编译成 “动态调用的字节码”。

例如下面这一段Groovy代码：

```Groovy
package groovy

println("Hello World!")
```

当我们用Groovy编译器编译之后，用反编译工具反编译之后：

```java
package groovy;

import groovy.lang.Binding;
import groovy.lang.GroovyObject;
import groovy.lang.MetaClass;
import groovy.lang.Script;
import java.lang.ref.SoftReference;
import org.codehaus.groovy.reflection.ClassInfo;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter;
import org.codehaus.groovy.runtime.callsite.CallSite;
import org.codehaus.groovy.runtime.callsite.CallSiteArray;

public class HelloGroovy extends Script {
    private static /* synthetic */ ClassInfo $staticClassInfo;
    public static transient /* synthetic */ boolean __$stMC;
    private static /* synthetic */ ClassInfo $staticClassInfo$;
    private static /* synthetic */ SoftReference $callSiteArray;

    public HelloGroovy() {
        CallSite[] arrcallSite = HelloGroovy.$getCallSiteArray();
    }

    public HelloGroovy(Binding context) {
        CallSite[] arrcallSite = HelloGroovy.$getCallSiteArray();
        super(context);
    }

    public static void main(String ... args) {
        CallSite[] arrcallSite = HelloGroovy.$getCallSiteArray();
        arrcallSite[0].call(InvokerHelper.class, HelloGroovy.class, (Object)args);
    }

    public Object run() {
        CallSite[] arrcallSite = HelloGroovy.$getCallSiteArray();
        return arrcallSite[1].callCurrent((GroovyObject)this, (Object)"Hello World!");
    }

    protected /* synthetic */ MetaClass $getStaticMetaClass() {
        if (((Object)((Object)this)).getClass() != HelloGroovy.class) {
            return ScriptBytecodeAdapter.initMetaClass((Object)((Object)this));
        }
        ClassInfo classInfo = $staticClassInfo;
        if (classInfo == null) {
            $staticClassInfo = classInfo = ClassInfo.getClassInfo(((Object)((Object)this)).getClass());
        }
        return classInfo.getMetaClass();
    }

    private static /* synthetic */ void $createCallSiteArray_1(String[] arrstring) {
        arrstring[0] = "runScript";
        arrstring[1] = "println";
    }

    private static /* synthetic */ CallSiteArray $createCallSiteArray() {
        String[] arrstring = new String[2];
        HelloGroovy.$createCallSiteArray_1(arrstring);
        return new CallSiteArray(HelloGroovy.class, arrstring);
    }

    private static /* synthetic */ CallSite[] $getCallSiteArray() {
        CallSiteArray callSiteArray;
        if ($callSiteArray == null || (callSiteArray = (CallSiteArray)$callSiteArray.get()) == null) {
            callSiteArray = HelloGroovy.$createCallSiteArray();
            $callSiteArray = new SoftReference<CallSiteArray>(callSiteArray);
        }
        return callSiteArray.array;
    }
}
```

简单的一行代码，经过Groovy编译器编译之后，变得如此复杂。而这就是Groovy编译器做的，普通的代码编译成可以动态调用的代码。

不难发现，经过编译之后，几乎所有的方法调用都变成通过CallSite进行了，这个CallSite就是实现动态调用的入口点，我们来看看这个CallSite都做了什么？

```Java
package org.codehaus.groovy.runtime.callsite;

/**
 * Base class for all call sites
 */
public class AbstractCallSite implements CallSite {
    ......
    public Object callCurrent(GroovyObject receiver, Object arg1) throws Throwable {
        CallSite stored = array.array[index];
        if (stored!=this) {
            return stored.callCurrent(receiver, arg1);
        }
        return callCurrent(receiver, ArrayUtil.createArray(arg1));
    }
    ......
}
```
CallSite主要是分发和缓存不同类型的方法调用逻辑，包括getProperty(),  callGetPropertySafe(),  callGetProperty(),  callGroovyObjectGetProperty(),  callGroovyObjectGetPropertySafe(),  call(),  callCurrent(),  callStatic(),  callConstructor()等

对于不同类型的方法调用需要通过不同的CallSite调用，针对不同类型的方法需要有不同的处理，否则可能会出现循环调用，最后抛出StackOverflow异常。例如对于当前对象(this)的方法调用需要通过callCurrent()，对于static类型方法需要通过callStatic()，而对于局部变量或者类变量则是通过call()；

同时，对于不同类型的对象，CallSite内部也有不同的处理逻辑，例如对于当前对象(this)的方法调用 callCurrent()：

```Java
package org.codehaus.groovy.runtime.callsite;

public final class CallSiteArray {
    ......
    private static CallSite createCallCurrentSite(CallSite callSite, GroovyObject receiver, Object[] args, Class sender) {
        CallSite site;
        if (receiver instanceof GroovyInterceptable)
          site = new PogoInterceptableSite(callSite);
        else {
            MetaClass metaClass = receiver.getMetaClass();
            if (receiver.getClass() != metaClass.getTheClass() && !metaClass.getTheClass().isInterface()) {
                site = new PogoInterceptableSite(callSite);
            }
            else
                if (metaClass instanceof MetaClassImpl) {
                    site = ((MetaClassImpl)metaClass).createPogoCallCurrentSite(callSite, sender, args);
                }
                else
                  site = new PogoMetaClassSite(callSite, metaClass);
        }

        replaceCallSite(callSite, site);
        return site;
    }
    ......
}
```

callCurrent()针对不同的目标对象的处理逻辑分别为：

* 对于实现了GroovyInterceptable接口的对象，创建的是PogoInterceptableSite，这也是为什么__实现了GroovyInterceptable接口的对象，任何方法调用最终都会调用invokeMethod方法的原因。__

* 对于getMetaClass方法返回的是MetaClassImpl及其子类的对象则是通过调用((MetaClassImpl)metaClass).createPogoCallCurrentSite()方法创建。

* 而对于普通的对象创建的则是PogoMetaClassSite：

```java
package org.codehaus.groovy.runtime.callsite;

public class PogoMetaClassSite extends MetaClassSite {
    ....
    public final Object call(Object receiver, Object[] args) throws Throwable {
        if (checkCall(receiver)) {
            try {
                try {
                    return metaClass.invokeMethod(receiver, name, args);
                } catch (MissingMethodException e) {
                    if (e instanceof MissingMethodExecutionFailed) {
                        throw (MissingMethodException)e.getCause();
                    } else if (receiver.getClass() == e.getType() && e.getMethod().equals(name)) {
                        // in case there's nothing else, invoke the object's own invokeMethod()
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

从PogoMetaClassSite的源码可以看出，方法调用逻辑最终是委托给MetaClass进行处理，如果MetaClass仍然无法处理，才抛出异常或者调用GroovyObject的invokeMethod方法。

__因此，可以这么说，经过Groovy编译器编译之后，所有的方法调用都会通过Groovy构建的系统进行调用，而这个系统正是实现其动态特性的关键。__

#### MetaClassImpl

接下来，我们来看一下MetaClass是如何分发方法调用的：

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
        MetaMethod method = getMetaMethod(sender, object, methodName, isCallToSuper, arguments);

        final boolean isClosure = object instanceof Closure;
        if (isClosure) {
            ......
        }

        if (method != null) {
            return method.doMethodInvoke(object, arguments);
        } else {
            return invokePropertyOrMissing(object, methodName, originalArguments, fromInsideClass, isCallToSuper);
        }
    }
    
    ......
    private MetaMethod getMetaMethod(Class sender, Object object, String methodName, boolean isCallToSuper, Object... arguments) {
        MetaMethod method = null;
        if (CLOSURE_CALL_METHOD.equals(methodName) && object instanceof GeneratedClosure) {
            method = getMethodWithCaching(sender, "doCall", arguments, isCallToSuper);
        }
        if (method==null) {
            method = getMethodWithCaching(sender, methodName, arguments, isCallToSuper);
        }
        MetaClassHelper.unwrap(arguments);

        if (method == null)
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
            return getMethodWithoutCaching(sender, methodName, MetaClassHelper.convertToTypeArray(arguments), isCallToSuper);
        } else {
            final MetaMethodIndex.Entry e = metaMethodIndex.getMethods(sender, methodName);
            if (e == null)
              return null;

            return isCallToSuper ? getSuperMethodWithCaching(arguments, e) : getNormalMethodWithCaching(arguments, e);
        }
    }
    
    ......  
}
```

首先，__通过调用 _getMetaMethod()_ 方法查找目标类及其父类是否存在该方法__:

* 如果调用的是this方法，则会__查找目标类及其父类的this方法(包括继承和覆盖的方法)、通过Category注入的方法以及通过MetaClass(ExpandoMetaClass)注入的方法__，如果同时存在则 _优先调用通过Category注入且是_覆盖_的方法_

* 如果调用的是super方法，则只会查找其父类的super方法

* 如果未找到相关方法并且方法参数只有一个List类型的参数，则会尝试展开该List并再次通过上面的逻辑进行查找。

接着，如果目标对象是闭包(Closure)，则需要走闭包的特殊处理逻辑，此处暂不讨论。

最后，如果找到了匹配的方法，则直接调用该方法；如果没有找到匹配的方法，则会调用invokePropertyOrMissing方法。

接着我们来看看invokePropertyOrMissing方法的处理逻辑：

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
        final MetaProperty metaProperty = this.getMetaProperty(methodName, false);
        if (metaProperty != null)
          value = metaProperty.getProperty(object);
        else {
            if (object instanceof Map)
              value = ((Map)object).get(methodName);
        }

        if (value instanceof Closure) {  // This test ensures that value != this If you ever change this ensure that value != this
            Closure closure = (Closure) value;
            MetaClass delegateMetaClass = closure.getMetaClass();
            return delegateMetaClass.invokeMethod(closure.getClass(), closure, CLOSURE_DO_CALL_METHOD, originalArguments, false, fromInsideClass);
        }

        if (object instanceof Script) {
            Object bindingVar = ((Script) object).getBinding().getVariables().get(methodName);
            if (bindingVar != null) {
                MetaClass bindingVarMC = ((MetaClassRegistryImpl) registry).getMetaClass(bindingVar);
                return bindingVarMC.invokeMethod(bindingVar, CLOSURE_CALL_METHOD, originalArguments);
            }
        }
        return invokeMissingMethod(object, methodName, originalArguments, null, isCallToSuper);
    }
    ......
}
```
invokePropertyOrMissing方法的调用过程：

* __查找是否存在以该方法名命名的Property，如果存在且该Property为Closure类型，则调用该Closure__。 需要注意的是：这里并不是通过MetaClass注入方法的实现逻辑，通过MetaClass注入的方法不会以Property的形式存在，而是以ClosureMetaMethod类型的数据缓存到目标类的方法信息中。

* 如果未找到相关Property，且目标对象是Script类型，则尝试查找是否存在以该方法名命名的BindingVariable，如果存在则调用其 call( ) 方法。

* 如果以上尝试均失败了，则调用 invokeMissingMethod() 方法

```java
package groovy.lang;

public class MetaClassImpl implements MetaClass, MutableMetaClass {
    ......
    private Object invokeMissingMethod(Object instance, String methodName, Object[] arguments, RuntimeException original, boolean isCallToSuper) {
        if (!isCallToSuper) {
            Class instanceKlazz = instance.getClass();
            if (theClass != instanceKlazz && theClass.isAssignableFrom(instanceKlazz))
              instanceKlazz = theClass;

            Class[] argClasses = MetaClassHelper.castArgumentsToClassArray(arguments);

            MetaMethod method = findMixinMethod(methodName, argClasses);
            if(method != null) {
                onMixinMethodFound(method);
                return method.invoke(instance, arguments);
            }

            method = findMethodInClassHierarchy(instanceKlazz, methodName, argClasses, this);
            if(method != null) {
                onSuperMethodFoundInHierarchy(method);
                return method.invoke(instance, arguments);
            }

            // still not method here, so see if there is an invokeMethod method up the hierarchy
            final Class[] invokeMethodArgs = {String.class, Object[].class};
            method = findMethodInClassHierarchy(instanceKlazz, INVOKE_METHOD_METHOD, invokeMethodArgs, this );
            if(method instanceof ClosureMetaMethod) {
                onInvokeMethodFoundInHierarchy(method);
                return method.invoke(instance, invokeMethodArgs);
            }

            // last resort look in the category
            if (method == null && GroovyCategorySupport.hasCategoryInCurrentThread()) {
                method = getCategoryMethodMissing(instanceKlazz);
                if (method != null) {
                    return method.invoke(instance, new Object[]{methodName, arguments});
                }
            }
        }

        if (methodMissing != null) {
            try {
                return methodMissing.invoke(instance, new Object[]{methodName, arguments});
            } catch (InvokerInvocationException iie) {
                if (methodMissing instanceof ClosureMetaMethod && iie.getCause() instanceof MissingMethodException) {
                    MissingMethodException mme =  (MissingMethodException) iie.getCause();
                    throw new MissingMethodExecutionFailed(mme.getMethod(), mme.getClass(),
                                                            mme.getArguments(),mme.isStatic(),mme);
                }
                throw iie;
            } catch (MissingMethodException mme) {
                if (methodMissing instanceof ClosureMetaMethod)
                    throw new MissingMethodExecutionFailed(mme.getMethod(), mme.getClass(),
                                                        mme.getArguments(),mme.isStatic(),mme);
                else
                    throw mme;
            }
        } else if (original != null) throw original;
        else throw new MissingMethodExceptionNoStack(methodName, theClass, arguments, false);
    } 
    ......
}
```

invokeMissingMethod方法的调用过程：

* __查找是否存在通过MixIn注入的方法，如果存在则调用该方法。__

* 查找目标类及其父类是否存在符合条件的SubClassMethod~~或者参数类型兼容的~~方法（如果存在多个，则返回匹配度最高的），如果存在则调用方法。_SubClassMethod是定义在目标类或者实例范围内的动态方法，其作用域仅限于目标类或实例_。

* 查找目标类及其父类中是否存在方法名为 _invokeMethod_ 的SubClassMethod或者参数类型兼容的方法（如果存在多个，则返回匹配度最高的），且该方法是 _ClosureMetaMethod_ 类型（即通过MetaClass注入的拦截方法，如果存在则调用该方法。

* 如果通过以上逻辑还是查找不到任何我们要调用的方法信息，那么就会__尝试调用  _methodMissing_ 方法__。_这里会优先调用通过Category注入的 _methodMissing_ 方法，如果未找到才调用目标对象或者通过MetaClass注入的 _methodMissing_ 方法_

* 最后，__如果 _methodMissing_ 方法也不存在，就抛出MissingMethodException异常__

至此，我们已经完整的走完了一次方法查找和分发的流程，也看到了Category和MixIn注入的方法是如何被调用到的。那么Groovy又是如何管理MetaClass的呢？

#### MetaClass

经过以上分析，我们知道MetaClass才是Groovy实现其动态特性的关键，接下来让我们来了解一下MetaClass从创建到初始化的过程。

以GroovyObjectSupport这个官方基类为例：

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

    public Object getProperty(String property) {
        return getMetaClass().getProperty(this, property);
    }

    public void setProperty(String property, Object newValue) {
        getMetaClass().setProperty(this, property, newValue);
    }

    public Object invokeMethod(String name, Object args) {
        return getMetaClass().invokeMethod(this, name, args);
    }

    public MetaClass getMetaClass() {
        return this.metaClass;
    }

    public void setMetaClass(MetaClass metaClass) {
        this.metaClass =
                null == metaClass
                    ? getDefaultMetaClass()
                    : metaClass;
    }

    private MetaClass getDefaultMetaClass() {
        return InvokerHelper.getMetaClass(this.getClass());
    }
}
```

默认的MetaClass是通过InvokerHelper.getMetaClass(this.getClass())获取的。

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

而InvokerHelper则是通过MetaClassRegistryImpl创建并获取MetaClass的。

接下来，我们来看一下MetaClassRegistryImpl是如何创建和初始化MetaClass的：

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

MetaClassRegistryImpl则是通过ClassInfo创建并获取MetaClass实例的。

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
        MetaClass answer = getStrongMetaClass();
        if (answer!=null) return answer;
        
        answer = getWeakMetaClass();
        final MetaClassRegistry metaClassRegistry = GroovySystem.getMetaClassRegistry();
        MetaClassRegistry.MetaClassCreationHandle mccHandle = metaClassRegistry.getMetaClassCreationHandler();
        
        if (isValidWeakMetaClass(answer, mccHandle)) {
            return answer;
        }

        answer = mccHandle.create(classRef.get(), metaClassRegistry);
        answer.initialize();

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
ClassInfo是实际上创建和缓存MetaClass的类，如果已创建则直接返回，如果未创建则创建并缓存MetaClass。每个类都绑定唯一的一个MetaClass实例以及一个ClassInfo实例，用于缓存相关信息。

接下来，我们来看一下MetaClassImpl初始化的过程：

```java
package groovy.lang;

public class MetaClassImpl implements MetaClass, MutableMetaClass {
    ......
    public synchronized void initialize() {
        if (!isInitialized()) {
            fillMethodIndex();
            try {
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
MetaClassImpl在初始化的时候就会通过反射解析目标类及其父类的所有方法(Methods)和属性(Fields和Getters)，甚至包括其父类已经动态注入的方法(getNewMetaMethods)，并缓存下来。_严格来说，这部分逻辑并不完善，如果父类在子类已经初始化MetaClass之后再动态注入的方法，子类就存在调用不到该方法的可能。_

至此，我们已经讲完了MetaClassImpl从创建到初始化的过程。但是还有几个问题没有弄清楚，比如Groovy官方提供的方法(例如use方法)是如何注入的呢？以及ExtensionModule又是如何注入的？


#### MetaClassRegistryImpl

我们不妨先来看一下MetaClassRegistryImpl在初始化的时候都做了什么？

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
            registerMethods(null, true, true, map);
            final Class[] additionals = DefaultGroovyMethods.ADDITIONAL_CLASSES;
            for (int i = 0; i != additionals.length; ++i) {
                createMetaMethodFromClass(map, additionals[i]);
            }

            Class[] pluginDGMs = VMPluginFactory.getPlugin().getPluginDefaultGroovyMethods();
            for (Class plugin : pluginDGMs) {
                registerMethods(plugin, false, true, map);
            }
            registerMethods(DefaultGroovyStaticMethods.class, false, false, map);
            Class[] staticPluginDGMs = VMPluginFactory.getPlugin().getPluginStaticGroovyMethods();
            for (Class plugin : staticPluginDGMs) {
                registerMethods(plugin, false, false, map);
            }

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
                            final NewInstanceMetaMethod metaMethod = new NewInstanceMetaMethod(method);
                            arr.add(metaMethod);
                            instanceMethods.add(metaMethod);
                        } else {
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

MetaClassRegistryImpl在其实例化的时候就会通过 _registerMethods()_ 方法去加载默认的方法，包括：

* __加载DefaultGroovyMethod即DgmMethod__。DgmMethod是一系列定义在org.codehaus.groovy.runtime包下，以dgm$n命名的方法类，其信息存储在/META-INF/dgminfo文件上。因此，registerMethods先读取该文件的内容，然后根据文件内容载入这些方法，并以NewInstanceMetaMethod实例方法的形式保存到各自定义类的方法信息上。这些方法是Groovy系统提供的一些动态方法，例如use，with等等。这就是为什么我们可以直接使用这些方法的原因，因为MetaClassRegistryImpl在实例化的时候就把这些方法加载进来了，而且其目标类普遍为基类，如Objec、String、Collection等等，这就保证了我们可以在这些类及其子类上正常的调用这些方法。

* 通过registerMethods加载VMPluginFactory.getPlugin().getPluginDefaultGroovyMethods()这个方法返回的所有类的静态方法，并以NewInstanceMetaMethod实例方法的形式保存到目标类(方法的第一个参数对应的类)的方法信息上，然后在调用的时候转换成调用静态方法，并插入调用的对象作为第一个参数(类似于通过Category注入方法)。这些方法是为了支持不同版本的JVM而提供的兼容方法，不需要过多关注。

* __通过registerMethods去加载DefaultGroovyStaticMethods类提供的静态方法__。这些静态方法会以NewStaticMetaMethod静态方法的形式保存到目标类(方法的第一个参数对应的类)的方法信息上，然后在调用的时候插入一个null值作为第一个参数，例如Thread.start()方法等等。

* 通过registerMethods加载VMPluginFactory.getPlugin().getPluginStaticGroovyMethods()这个方法返回的所有类的静态方法，以NewStaticMetaMethod静态方法的形式保存到目标类(方法的第一个参数对应的类)的方法信息上，然后在调用的时候插入一个null值作为第一个参数，例如Thread.start()方法等等。这些方法也是为了支持不同版本的JVM而提供的兼容方法不需要过多关注。

* 最后，__通过ExtensionModuleScanner加载默认的ExtensionModule方法__


#### ExpandoMetaClass

上文我们提到类或者实例默认绑定的MetaClass是MetaClassImpl类型的实例，但是事实上，当我们通过metaClass注入方法的时候，其实是通过ExpandoMetaClass注入的，我们不妨简单看一下ExpandoMetaClass的代码：

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
    public Object invokeMethod(Class sender, Object object, String methodName, Object[] originalArguments, boolean isCallToSuper, boolean fromInsideClass) {
        if (invokeMethodMethod != null) {
            MetaClassHelper.unwrap(originalArguments);
            return invokeMethodMethod.invoke(object, new Object[]{methodName, originalArguments});
        }
        return super.invokeMethod(sender, object, methodName, originalArguments, isCallToSuper, fromInsideClass);
    }
    
    ......
    public Object invokeStaticMethod(Object object, String methodName, Object[] arguments) {
        if (invokeStaticMethodMethod != null) {
            MetaClassHelper.unwrap(arguments);
            return invokeStaticMethodMethod.invoke(object, new Object[]{methodName, arguments});
        }
        return super.invokeStaticMethod(object, methodName, arguments);
    }
    ...... 
}
```
ExpandoMetaClass保存了动态注入的方法和属性的信息，其中包括：

* mixinClasses是通过MixIn动态注入的类信息

* invokeMethodMethod是动态注入的_ “invokeMethod”_ 实例方法

* invokeStaticMethodMethod也是动态注入的 _“invokeMethod”_ 静态方法

* expandoMethods是通过metaClass动态注入的方法

* expandoSubclassMethods是通过MetaClass注入的subclass方法

* beanPropertyCache是动态注入的实例属性

* staticBeanPropertyCache是动态注入的静态属性

另外，从ExpandoMetaClass的代码，我们也可以看出，__如果一个类或实例，通过metaClass注入了 _“invokeMethod”_ 拦截方法，那么任何的方法调用都会调用该方法；__

#### HandleMetaClass

既然类或者实例默认绑定的MetaClass是MetaClassImpl类的实例，那么当我们通过 _metaClass_ 动态注入方法的时候，又是如何切换到ExpandoMetaClass的呢？

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

我们发现，Groovy会将这个 _.metaClass_ 调用编译成  _getProperty(String.class，"metaClass")_。上文我们说到，MetaClassImplRegistry在实例化的时候，即会加载GroovyDefaultMethod，其中就包括定义在Class类及Object类上的 _getMetaClass()_ 方法。因此  _getProperty(String.class，"metaClass")_ 这个方法最终会调用到定义在DefaultGroovyMethods中的这个 _getMetaClass()_ 方法。

这里面涉及到一个知识点：
    _Groovy的getProperty() 方法除了查找类中定义的 Field 之外，还会查找动态注入的 Property 以及 Getter() 方法。
    于此同时getAttribute() 方法却只会查找类中定义的 Field。_

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
            return new HandleMetaClass(mc);
        }
    }
    
    public static MetaClass getMetaClass(Object obj) {
        MetaClass mc = InvokerHelper.getMetaClass(obj);
        return new HandleMetaClass(mc, obj);
    }
    
    public static MetaClass getMetaClass(GroovyObject obj) {
        // we need this method as trick to guarantee correct method selection
        return getMetaClass((Object)obj);
    }
    ......
}
```

因此，当我们以  _String.metaClass_ 这种形式访问 _metaClass_ 这个Property的时候，如果String这个类绑定的MetaClass不是ExpandoMetaClass类型的实例，那么就会创建并返回一个HandleMetaClass类的实例。

HandleMetaClass只是一个代理类，封装和延迟了创建ExpandoMetaClass的时机：

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
            if (!(delegate instanceof ExpandoMetaClass)) {
              delegate = new ExpandoMetaClass(delegate.getTheClass(), true, true);
              delegate.initialize();
            }
            DefaultGroovyMethods.setMetaClass(delegate.getTheClass(), delegate);
        }
        else {
          if (object != NONE) {
              final MetaClass metaClass = delegate;
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

HandleMetaClass在初始化或者调用到相关方法的时候，就会为这个类或实例创建一个新的ExpandoMetaClass，并更新该类或对象所绑定的MetaClass信息。此时，我们就可以通过metaClass动态注入方法了。

因为每个类或实例所绑定的ExpandoMetaClass是唯一的，由此可知 __在类上动态注入的方法是全局，而在实例上动态注入的方法则是临时的__。另外严格上来说，是有可能出现多线程同步或者时序原因导致的MetaClass不一致的问题。


#### Category

#### MixIn

#### ExtensionModule

接下来，我们来分析一下ExtensionModule是如何实现，RTFSC：

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
                    def entry = jar.getEntry(ExtensionModuleScanner.MODULE_META_INF_FILE)
                    if (!entry) {
                        entry = jar.getEntry(ExtensionModuleScanner.LEGACY_MODULE_META_INF_FILE)
                    }
                    if (entry) {
                        Properties props = new Properties()

                        try (InputStream is = jar.getInputStream(entry)) {
                            props.load(is)
                        }

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
Groovy在加载依赖的jar包的时候，会查找是否存在"META-INF/services/org.codehaus.groovy.runtime.ExtensionModule"或者"META-INF/groovy/org.codehaus.groovy.runtime.ExtensionModule"这两个文件，如果存在则说明定义了ExtensionModule，那么就会读取这两个文件的内容。

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
            if (moduleRegistry.hasModule(module.getName())) {
                ExtensionModule loadedModule = moduleRegistry.getModule(module.getName());
                if (loadedModule.getVersion().equals(module.getVersion())) {
                    // already registered
                    return;
                } else {
                    throw new GroovyRuntimeException("Conflicting module versions. Module [" + module.getName() + " is loaded in version " +
                            loadedModule.getVersion() + " and you are trying to load version " + module.getVersion());
                }
            }
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
                    staticMethods.add(metaMethod);
                } else {
                    instanceMethods.add(metaMethod);
                }
            }
        }
    }
}
```
读取ExtensionModule文件内容之后，就可以找到定义ExtensionModule方法的类，然后加载该类，并加载该类定义的静态，并分别以NewStaticMetaMethod静态方法和NewInstanceMetaMethod实例方法的形式保存到目标类(方法的第一个参数对应的类)的方法信息中。NewStaticMetaMethod静态方法和NewInstanceMetaMethod实例方法这两种方法在调用的时候会分别插入null值和当前对象作为方法的第一个参数，然后再通过反射调用真正的Method。

因此，当我们定义StaticExtensionMethod的时候，应慎用第一个参数，因为它的值可能为null，这个参数只是为了标识目标类。而当我们定义InstanceExtensionMethod的时候，其第一个参数就是目标类的实例对象。

####  未完待续




