###                                                                             Groovy解析之Gradle探究


#### 从build.gradle说起

闲话少说，先来看一段代码：

```groovy
// build.gradle

dependencies {
    compile gradleApi()
    compile localGroovy()
    compileOnly 'com.android.tools.build:gradle:3.5.2'
}
```

gradle 是一套支持 Groovy 语言进行配置的打包编译系统，上面这段代码经编译之后，会变成调用 _dependencies (Closure closure)_ 方法。另外我们知道 build.gradle 文件编译成的类会继承 _ProjectScipt_ 这个类。由 [Groovy元编程原理]() 一文可知，**未定义这个方法的话，最终会调用 _invokeMethod(String name, Object args)_** ：

#### BasicScript

```java
package org.gradle.api.internal.project;

public abstract class BasicScript extends org.gradle.groovy.scripts.Script implements org.gradle.api.Script, DynamicObjectAware {
    
    ......
    private ScriptDynamicObject dynamicObject = new ScriptDynamicObject(this);
     
    ......
    @Override
    public Object invokeMethod(String name, Object args) {
        return dynamicObject.invokeMethod(name, (Object[]) args);
    }
}
```

_ProjectScript_ 这个类继承自 _BasicScript_ ，由此可以判断最后调用的是 _BasicScript_ 类的 _invokeMethod(...)_ 方法，亦即调用 _dynamicObject.invokeMethod(...)_ 方法。_DynamicObject_ 是 gradle 定义的一套支持动态调用的协议，我们接着往下看：

##### ScriptDynamicObject

```java
package org.gradle.groovy.scripts;

private static final class BasicScript$ScriptDynamicObject extends AbstractDynamicObject {
        ......
        private final Binding binding;
        private final DynamicObject scriptObject;
        private DynamicObject dynamicTarget;

        ScriptDynamicObject(BasicScript script) {
            this.binding = script.getBinding();
            // 初始化 scriptObject 和 dynamicTarget
            scriptObject = new BeanDynamicObject(script).withNotImplementsMissing();
            dynamicTarget = scriptObject;
        }

        public void setTarget(Object target) {
            // 设置dynamicTarget，target 一般为 Project 或 Gradle 
            dynamicTarget = DynamicObjectUtil.asDynamicObject(target);
        }
        
        @Override
        public DynamicInvokeResult tryInvokeMethod(String name, Object... arguments) {
            // 先尝试分发给 scriptObject
            DynamicInvokeResult result = scriptObject.tryInvokeMethod(name, arguments);
            if (result.isFound()) {
                return result;
            }
            // 再尝试分发给 dynamicTarget
            return dynamicTarget.tryInvokeMethod(name, arguments);
        }

        @Override
        public DynamicInvokeResult tryGetProperty(String property) {
            if (binding.hasVariable(property)) {
                return DynamicInvokeResult.found(binding.getVariable(property));
            }
            DynamicInvokeResult result = scriptObject.tryGetProperty(property);
            if (result.isFound()) {
                return result;
            }
            return dynamicTarget.tryGetProperty(property);
        }

        @Override
        public DynamicInvokeResult trySetProperty(String property, Object newValue) {
            return dynamicTarget.trySetProperty(property, newValue);
        }
}
```

_ScriptDynamicObject_ 是定义在 _BasicScript_ 的静态内部类，其中：

* __scriptObject__ 代理的是当前 Script(ProjectScript) 对象，

* __dynamicTarget__ 代理的是当前Script所对应的 Project(DefaultProject，严格上来说是 extensibleDynamicObject，见下文分析) 或者 Gradle(DefaultGradle) 对象。

由源码可知，tryInvokeMethod() 先尝试分发给 scriptObject 处理，然后才分发给 dynamicTarget 进行处理。而 scriptObject 和 dynamicTarget 都是 DynamicObject，我们来看一下 dynamicTarget 具体是什么 ：

#####  DynamicObjectUtil

```java
package org.gradle.internal.metaobject;

public abstract class DynamicObjectUtil {
    public static DynamicObject asDynamicObject(Object object) {
        if (object instanceof DynamicObject) {
            // DynamicObject
            return (DynamicObject)object;
        } else if (object instanceof DynamicObjectAware) {
            // DynamicObjectAware
            return ((DynamicObjectAware) object).getAsDynamicObject();
        } else {
            // 其他
            return new BeanDynamicObject(object);
        }
    }
}
```

_DynamicObjectUtil_ 根据对象的类型，返回不同类型的 DynamicObject，其中 _BeanDynamicObject_ 针对的是普通 Java 对象。

// TODO BeanDynamicObject

##### DefaultProject

```java
package org.gradle.api.internal.project;

public class DefaultProject extends AbstractPluginAware implements ProjectInternal, DynamicObjectAware, FileOperations, ProcessOperations {
    
    ......
    private ExtensibleDynamicObject extensibleDynamicObject;
    
    ......
    @Override
    public DynamicObject getAsDynamicObject() {
        return extensibleDynamicObject;
    }
}
```

而对于 _DefaultProject_ 来说，由于 _DefaultProject_ 这个类继承自 __AbstractPluginAware__，因此上文提到的 __dynamicTarget 就是 getAsDynamicObject() 这个方法返回的 (即ExtensibleDynamicObject) 对象__ 。

__由于 _ProjectScript_ 没有处理 _dependencies(...)_ 方法的逻辑，因此该方法最终会分发给 ExtensibleDynamicObject 进行处理。__

__ExtensibleDynamicObject__ 是 _DefaultProject_ 实现动态调用的关键，其主要逻辑是按一定的优先级分发方法给不同的对象：

PS：gradle 在编译的时候会自动生成 Project 等类的装饰类，如 _DefaultProject_Decorated_，这些装饰类都是继承自这几个类，逻辑基本不变，我们看这几个类的代码即可。

#### ExtensibleDynamicObject

```java
package org.gradle.internal.extensibility;

public class ExtensibleDynamicObject extends MixInClosurePropertiesAsMethodsDynamicObject implements HasConvention {

    private final AbstractDynamicObject dynamicDelegate;
    private DynamicObject parent;
    private Convention convention;
    private DynamicObject beforeConvention;
    private DynamicObject afterConvention;
    private DynamicObject extraPropertiesDynamicObject;
    
    ......
    public DynamicObject getInheritable() {
        return new InheritedDynamicObject();
    }
}
```

_ExtensibleDynamicObject_ 是承接 gradle 方法调用逻辑的枢纽，我们先来了解一下各个变量的含义：

* __dynamicDelegate__：代理的是当前 Module 的 Project 或 Gradle 对象。

* __extraPropertiesDynamicObject__：代理的是当前 Module 的 ExtraPropertyExtension，当我们调用 __project.ext__ 定义的 __property__ 时，就会涉及到这个对象。

* __beforeConvention__：前置拦截器，一般（有可能为空，暂且略过不深入分析）为 _new BeanDynamicObject(**buildScript**).withNoProperties().withNotImplementsMissing()_，即代理的是当前 Module 所对应的 Scrip 对象(build.gradle编译成的类)。

* __convention__：管理 Plugins 和 Extensions 的类，一般为 DefaultConvention。当我们通过 _android.defaultConfig_ 这种方式进行调用的时候，就会涉及到这个对象。

* __afterConvention__：后置拦截器，一般为 _**taskContainer**.getTasksAsDynamicObject()_ 。

* __parent__：代理的是上一级 Module 的 _ExtensibleDynamicObject_ 对象通过  _getInheritable()_ 方法对外提供的对象，ChildProject 就是通过这个对象直接调用定义在 RootProject 上的方法的。

这个顺序也是 _ExtensibleDynamicObject_ 方法分发的顺序，简单的来说就是依次调用 project/gradle、extraProperties、buildScript、extensions/plugins、taskContainer，parentProjec/parentGradle。


##### DefaultConvention

```java
package org.gradle.internal.extensibility;

public class DefaultConvention implements Convention, ExtensionContainerInternal {

    private static final TypeOf<ExtraPropertiesExtension> EXTRA_PROPERTIES_EXTENSION_TYPE = typeOf(ExtraPropertiesExtension.class);
    private final DefaultConvention.ExtensionsDynamicObject extensionsDynamicObject = new ExtensionsDynamicObject();
    private final ExtensionsStorage extensionsStorage = new ExtensionsStorage();
    private final ExtraPropertiesExtension extraProperties = new DefaultExtraPropertiesExtension();
    private final Instantiator instantiator;

    private Map<String, Object> plugins;
    private Map<Object, BeanDynamicObject> dynamicObjects;

    public DefaultConvention(Instantiator instantiator) {
        this.instantiator = instantiator;
        // EXTENSION_NAME = "ext";
        add(EXTRA_PROPERTIES_EXTENSION_TYPE, ExtraPropertiesExtension.EXTENSION_NAME, extraProperties);
    }
    ......
}
```

_DefaultConvention_ 负责查找和分发方法给 Plugin 和 Extension，其中：

* __extensionsDynamicObject__ :  _DefaultConvention_ 对外提供的动态调用对象，即调用入口，详见 __ExtensibleDynamicObject__ 源码。

* __plugins__ : 保存当前project的所有plugin对象

* __extensionsStorage__ : 保存当前 project 的所有 extension 对象，包括 _extraProperties_

* __extraProperties__ : 即保存当前 project 的 extraProperties 的 extension 对象 (project.ext)

需要注意的是：_DefaultConvention_ 对于 extension 只支持通过 _EXTENSION_NAME { ... }_ 进行配置或者通过 Extension 对象直接调用如 _project.EXTENSION_NAME.METHOD_NAME(...)_。而对于plugin，则会按照其添加顺序依次分发方法调用。

##### CompositeDynamicObject

```java
package org.gradle.internal.metaobject;

public abstract class CompositeDynamicObject extends AbstractDynamicObject {
    
    @Override
    public DynamicInvokeResult tryGetProperty(String name) {
        for (DynamicObject object : objects) {
            DynamicInvokeResult result = object.tryGetProperty(name);
            if (result.isFound()) {
                return result;
            }
        }
        return DynamicInvokeResult.notFound();
    }

    @Override
    public DynamicInvokeResult trySetProperty(String name, Object value) {
        for (DynamicObject object : updateObjects) {
            DynamicInvokeResult result = object.trySetProperty(name, value);
            if (result.isFound()) {
                return result;
            }
        }
        return DynamicInvokeResult.notFound();
    }
    
        @Override
    public DynamicInvokeResult tryInvokeMethod(String name, Object... arguments) {
        // 按顺序分发方法调用
        for (DynamicObject object : objects) {
            DynamicInvokeResult result = object.tryInvokeMethod(name, arguments);
            if (result.isFound()) {
                return result;
            }
        }
        return DynamicInvokeResult.notFound();
    }
}
```

_ExtensibleDynamicObject_ 继承自 _CompositeDynamicObject_，_CompositeDynamicObject_ 类如其名，即负责组织和分发方法给多个DynamicObject。

__分析可知，dependencies(...) 方法会优先分发给 Project 进行处理，而我们发现 Project 恰好定义了一个 dependencies 方法：__

#### DefaultProject

```java
package org.gradle.api.internal.project;

public class DefaultProject extends AbstractPluginAware implements ProjectInternal, DynamicObjectAware, FileOperations, ProcessOperations {
    
    ......
    @Override
    public void dependencies(Closure configureClosure) {
        ConfigureUtil.configure(configureClosure, getDependencies());
    }
}
```

兜兜转转，我们又回到了 _DefaultProject_。由上文可以推断出，_dependencies { ... }_ 这个方法会被分发到 _DefaultProject_，即调用 _dependencies(...)_ 方法。我们不妨接着往下看，看这个方法又是如何跟 _DefaultDependencyHandler_ 关联到一起的：

##### ConfigureUtil

```java
package org.gradle.util;

public class ConfigureUtil {

    ......
    public static <T> T configure(@Nullable Closure configureClosure, T target) {
        if (configureClosure == null) {
            return target;
        }

        if (target instanceof Configurable) {
            ((Configurable) target).configure(configureClosure);
        } else {
            configureTarget(configureClosure, target, new ConfigureDelegate(configureClosure, target));
        }

        return target;
    }
    
    ......
    private static <T> void configureTarget(Closure configureClosure, T target, ConfigureDelegate closureDelegate) {
        // 注意这里对非GeneratedClosure的特殊处理逻辑
        if (!(configureClosure instanceof GeneratedClosure)) {
            new ClosureBackedAction<T>(configureClosure, Closure.DELEGATE_FIRST, false).execute(target);
            return;
        }

        // Hackery to make closure execution faster, by short-circuiting the expensive property and method lookup on Closure
        Closure withNewOwner = configureClosure.rehydrate(target, closureDelegate, configureClosure.getThisObject());
        new ClosureBackedAction<T>(withNewOwner, Closure.OWNER_ONLY, false).execute(target);
    }
}
```

简而言之，_ConfigureUtil_ 所做的工作就是替换 _configureClosure_ 的 _delegate_ 为 _target(DefaultDependencyHandler)_，替换 _owner_ 为 _ConfigureDelegate_，而 _this_ 则保持不变，同时设置其 _resolveStrategy_ 为 _Closure.OWNER_ONLY_。**因此  _configureClosure_ 内的方法都会被分发给  _ConfigureDelegate_ 进行处理**：

##### ConfigureDelegate

```java
package org.gradle.internal.metaobject;

@NotThreadSafe
public class ConfigureDelegate extends GroovyObjectSupport {
    
    protected final DynamicObject _owner;
    protected final DynamicObject _delegate;
    private boolean _configuring;

    public ConfigureDelegate(Closure configureClosure, Object delegate) {
        // _owner 和 _delegate都是通过 DynamicObjectUtil 转换的
        _owner = DynamicObjectUtil.asDynamicObject(configureClosure.getOwner());
        _delegate = DynamicObjectUtil.asDynamicObject(delegate);
    }
    
    ......
    @Override
    public Object invokeMethod(String name, Object paramsObj) {
        Object[] params = (Object[])paramsObj;

        boolean isAlreadyConfiguring = _configuring;
        _configuring = true;
        try {
            // 尝试分发给 _delegate (target)
            DynamicInvokeResult result = _delegate.tryInvokeMethod(name, params);
            if (result.isFound()) {
                return result.getValue();
            }

            MissingMethodException failure = null;
            if (!isAlreadyConfiguring) {
                // Try to configure element
                try {
                    result = _configure(name, params);
                } catch (MissingMethodException e) {
                    // Workaround for backwards compatibility. Previously, this case would unintentionally cause the method to be invoked on the owner
                    // continue below
                    failure = e;
                }
                if (result.isFound()) {
                    return result.getValue();
                }
            }

            // 尝试分发给 _owner
            // try the owner
            result = _owner.tryInvokeMethod(name, params);
            if (result.isFound()) {
                return result.getValue();
            }

            if (failure != null) {
                throw failure;
            }

            throw _delegate.methodMissingException(name, params);
        } finally {
            _configuring = isAlreadyConfiguring;
        }
    }
}
```

__*ConfigureDelegate* 的作用主要是简化方法分发逻辑，按顺序分发给 target/delegate (*Dependencyhandler*) 和 owner (*ProjectScript*)__。

#### DefaultDependencyHandler

```java
package org.gradle.api.internal.artifacts.dsl.dependencies;

public class DefaultDependencyHandler implements DependencyHandler, MethodMixIn {

    ......
    private final DynamicAddDependencyMethods dynamicMethods;
    
    ......
    public DefaultDependencyHandler(...) {
        // 注意这个dynamicMethods(MethodMixIn相关)
        dynamicMethods = new DynamicAddDependencyMethods(configurationContainer, new DirectDependencyAdder());
    }
    
    ......
        @Override
    public MethodAccess getAdditionalMethods() {
        return dynamicMethods;
    }
    
    ......
    private Dependency doAdd(Configuration configuration, Object dependencyNotation, Closure configureClosure) {
        if (dependencyNotation instanceof Configuration) {
            Configuration other = (Configuration) dependencyNotation;
            if (!configurationContainer.contains(other)) {
                throw new UnsupportedOperationException("Currently you can only declare dependencies on configurations from the same project.");
            }
            configuration.extendsFrom(other);
            return null;
        }

        // 创建及添加dependency配置
        Dependency dependency = create(dependencyNotation, configureClosure);
        configuration.getDependencies().add(dependency);
        return dependency;
    }
    
    ......
    private class DirectDependencyAdder implements DynamicAddDependencyMethods.DependencyAdder<Dependency> {

        @Override
        public Dependency add(Configuration configuration, Object dependencyNotation, @Nullable Closure configureAction) {
            return doAdd(configuration, dependencyNotation, configureAction);
        }
    }
}
```

言而总之，总而言之，**_dependencies {...}_ 这个方法的 _Closure_ 内的方法调用都会被分发到DefaultDependencyHandler。**当然 DefaultDependencyHandler 中并没有直接定义compileOnly、implementation、api 这些方法，而是通过 __MethodMixIn__ 协议动态转换: 

##### MethodMixIn

```java
package org.gradle.internal.metaobject;

public class BeanDynamicObject extends AbstractDynamicObject {
    ......
        ......
        if (bean instanceof MethodMixIn) {
            // If implements MethodMixIn, do not attempt to locate opaque method, as this is expensive
            MethodMixIn methodMixIn = (MethodMixIn) bean;
            return methodMixIn.getAdditionalMethods().tryInvokeMethod(name, arguments);
        }
        ......
    ......
}
```
__MethodMixIn协议__：当一个对象未定义相关方法，同时又继承自 _MethodMixIn_ 接口的时候，就会直接调用 _MethodAccess_ (getAdditionalMethods的返回值) 的 _tryInvokeMethod(...)_ 方法。

##### DynamicAddDependencyMethods

```java
package org.gradle.api.internal.artifacts.dsl.dependencies;

class DynamicAddDependencyMethods implements MethodAccess {
    
    private ConfigurationContainer configurationContainer;
    private DependencyAdder dependencyAdder;
    
    ......
    @Override
    public DynamicInvokeResult tryInvokeMethod(String name, Object... arguments) {
        if (arguments.length == 0) {
            return DynamicInvokeResult.notFound();
        }
        // 先查找是否已经创建相应的Configuration
        Configuration configuration = configurationContainer.findByName(name);
        if (configuration == null) {
            return DynamicInvokeResult.notFound();
        }

        List<?> normalizedArgs = CollectionUtils.flattenCollections(arguments);
        // 调用 add() -> doAdd() 
        if (normalizedArgs.size() == 2 && normalizedArgs.get(1) instanceof Closure) {
            return DynamicInvokeResult.found(dependencyAdder.add(configuration, normalizedArgs.get(0), (Closure) normalizedArgs.get(1)));
        } else if (normalizedArgs.size() == 1) {
            return DynamicInvokeResult.found(dependencyAdder.add(configuration, normalizedArgs.get(0), null));
        } else {
            for (Object arg : normalizedArgs) {
                dependencyAdder.add(configuration, arg, null);
            }
            return DynamicInvokeResult.found();
        }
    }
}
```

因此，_denpencies {...}_ 这个 Closure 里面的方法最终会被分发到 _DynamicAddDependencyMethods_，而这个类的处理逻辑简单来说就是将方法调用转换成调用 _DefaultDependencyhandler_ 的 _add(...)_ 方法，最后_add(...)_ 方法才把这个依赖配置解析并添加到相应的 _Configuration_ 中。

