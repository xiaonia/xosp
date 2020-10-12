####                                      Gradle之ServiceRegistry浅析



####  从 DefaultGradle 说起

[org.gradle.invocation.DefaultGradle]()

```java
public abstract class DefaultGradle extends AbstractPluginAware implements GradleInternal {
    ......
    @Inject
    @Override
    public TaskExecutionGraphInternal getTaskGraph() {
        throw new UnsupportedOperationException();
    }
    ......
}
```

相信很多人对 __DefaultGradle__ 这个类并不陌生，因为当我们通过 __project.gradle__ 调用的时候，实际上调用的就是这个类。

然而，当我们第一次接触这个类的时候，肯定也会好奇：这个类是个抽象类，gradle 系统并没有任何该类的实现类，而且有很多 __getTaskGraph()__ 这样的抽象方法，它又是如何使整个 gradle 体系运行起来的呢？

如果我们在运行时，通过 __project.gradle.getClass()__ 输出其 Class 信息，我们会发现，这个类居然是 __org.gradle.invocation.DefaultGradle_Decorated__。

事实上，上面的所有问题都跟 gradle 系统__运行时动态生成字节码__有关：gradle系统可以自动生成抽象类的实现类，同时也可以根据注解自动生成成员变量和成员函数。



####  Class生成器：AbstractClassGenerator

[org.gradle.internal.instantiation.generator.AbstractClassGenerator]()

```java
    private GeneratedClassImpl generateUnderLock(Class<?> type) {
    ......
    InjectAnnotationPropertyHandler injectionHandler = new InjectAnnotationPropertyHandler();
    ......
    Class<?> generatedClass;
        try {
            ClassInspectionVisitor inspectionVisitor = start(type);
            // 解析type
            inspectType(type, validators, handlers, extensibleTypeHandler);
            for (ClassGenerationHandler handler : handlers) {
                handler.applyTo(inspectionVisitor);
            }
            // 生成代码
            ClassGenerationVisitor generationVisitor = inspectionVisitor.builder();
            for (ClassGenerationHandler handler : handlers) {
                handler.applyTo(generationVisitor);
            }
            // 生成构造函数
            if (type.isInterface()) {
                generationVisitor.addDefaultConstructor();
            } else {
                for (Constructor<?> constructor : type.getConstructors()) {
                    generationVisitor.addConstructor(constructor);
                }
            }
            // 输出generatedClass
            generatedClass = generationVisitor.generate();
        } catch (ClassGenerationException e) {
            throw e;
        } catch (Throwable e) {
            ......
        }

        .....
        // This is expensive to calculate, so cache the result
        Class<?> enclosingClass = type.getEnclosingClass();
        Class<?> outerType;
        if (enclosingClass != null && !Modifier.isStatic(type.getModifiers())) {
            outerType = enclosingClass;
        } else {
            outerType = null;
        }

        return new GeneratedClassImpl(generatedClass, outerType, injectionHandler.getInjectedServices(), annotationsTriggeringServiceInjection.build());
    }
```

__generateUnderLock(...)__ 方法是运行时生成字节码的核心处理方法：

* 调用 __inspectClass(...)__ 方法通过反射的方式解析输入的 Class

* 分发给 __ClassGenerationHandler__ 生成成员变量和成员函数

* 生成构造方法

  

#####  Class解析：ClassInspector

[org.gradle.internal.reflect.ClassInspector]()

```java
    private static void inspectClass(Class<?> type, MutableClassDetails classDetails) {
        // 注意这里只解析方法
        for (Method method : type.getDeclaredMethods()) {
            classDetails.method(method);

            // 略过private和static方法
            if (Modifier.isPrivate(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
                continue;
            }

            PropertyAccessorType accessorType = PropertyAccessorType.of(method);
            if (accessorType == PropertyAccessorType.GET_GETTER || accessorType == PropertyAccessorType.IS_GETTER) {
                // getter方法
                String propertyName = accessorType.propertyNameFor(method);
                classDetails.property(propertyName).addGetter(method);
            } else if (accessorType == PropertyAccessorType.SETTER) {
                // setter方法
                String propertyName = accessorType.propertyNameFor(method);
                classDetails.property(propertyName).addSetter(method);
            } else {
                // 其他实例方法
                classDetails.instanceMethod(method);
            }
        }
    }
```

__ClassInspector__ 是实际上负责解析 Class 的类，需要注意的是：

* 该方法__只解析Method__ 

* 对于 __Getter__ 和 __Setter__ 方法会被解析为相应的 __property__

  

##### @Inject 注解处理：InjectAnnotationPropertyHandler

[org.gradle.internal.instantiation.generator.AbstractClassGenerator$InjectAnnotationPropertyHandler]()

```java
    private static class InjectAnnotationPropertyHandler extends AbstractInjectedPropertyHandler {
    
        public InjectAnnotationPropertyHandler() {
            super(Inject.class);
        }

        @Override
        public void applyTo(ClassGenerationVisitor visitor) {
            for (PropertyMetadata property : serviceInjectionProperties) {
                visitor.applyServiceInjectionToProperty(property);
                for (MethodMetadata getter : property.getOverridableGetters()) {
                    visitor.applyServiceInjectionToGetter(property, getter);
                }
                for (Method setter : property.getOverridableSetters()) {
                    visitor.applyServiceInjectionToSetter(property, setter);
                }
            }
        }
    }
```

__InjectAnnotationPropertyHandler__ 主要负责处理 __@Inject__ 标注的成员变量或函数：

* 生成成员变量 **__<property_name>__**

* 覆写成员函数 **get<Property_name>**

* 覆写成员函数 **set<Property_name>**

  

##### abstract函数处理：AsmBackedClassGenerator$ClassBuilderImpl

[org.gradle.internal.instantiation.generator.AsmBackedClassGenerator$ClassBuilderImpl]()

```java

    private static class ClassBuilderImpl implements ClassGenerationVisitor {
        ......
        @Override
        public void applyServiceInjectionToGetter(PropertyMetadata property, MethodMetadata getter) {
            applyServiceInjectionToGetter(property, null, getter);
        }

        @Override
        public void applyServiceInjectionToGetter(PropertyMetadata property, @Nullable final Class<? extends Annotation> annotation, MethodMetadata getter) {
            // GENERATE public <type> <getter>() { if (<field> == null) { <field> = <services>>.get(<service-type>>); } return <field> }
            ......
        }
        
        private void putServiceRegistryOnStack(MethodVisitor methodVisitor) {
            if (requiresServicesMethod) {
                // this.<services_method>()
                methodVisitor.visitVarInsn(ALOAD, 0);
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, generatedType.getInternalName(), SERVICES_METHOD, RETURN_SERVICE_LOOKUP, false);
            } else {
                // this.getServices()
                methodVisitor.visitVarInsn(ALOAD, 0);
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, generatedType.getInternalName(), "getServices", RETURN_SERVICE_REGISTRY, false);
            }
        }

    }
```

由源码可以推断出，对于__abstract的get方法__最后生成的成员函数为：

```java
public <type> <getter>() {
    if (<field> == null) {
        <field> = getServices().get(<service-type>);
    }
    return <field>
}
```

可以看到 service 相关的对象都是通过 getServices().get() 获取的：



##### getServices方法

[org.gradle.invocation.DefaultGradle]()

```java
public abstract class DefaultGradle extends AbstractPluginAware implements GradleInternal {

    public DefaultGradle(GradleInternal parent, StartParameter startParameter, ServiceRegistryFactory parentRegistry) {
        this.parent = parent;
        this.startParameter = startParameter;
        this.services = parentRegistry.createFor(this);
        ......
    }
    
    @Override
    public ServiceRegistry getServices() {
        return services;
    }
}
```

对于 getTaskGraph() 这个方法来说，Class 生成器最后生成的方法调用的就是 DefaultGradle 的 getServices() 方法：

```java
    // 生成代码示例
    @Override
    public TaskExecutionGraphInternal getTaskGraph() {
        if (__taskGraph__ == null) {
            __taskGraph__ = getServices().get(TaskExecutionGraphInternal.class);
        }
        return __taskGraph__;
    }
```

以上就是一个完整的 service 调用过程，那么这个 service 又是在什么时候注册的呢？为什么在源码中找不到任何显式注册该 service 的地方呢？



####  Service的注册过程：DefaultServiceRegistry

##### BuildScopeServiceRegistryFactory

```java
    @Override
    public ServiceRegistry createFor(Object domainObject) {
        if (domainObject instanceof GradleInternal) {
            GradleScopeServices gradleServices = new GradleScopeServices(services, (GradleInternal) domainObject);
            registries.add(gradleServices);
            return gradleServices;
        }
        if (domainObject instanceof SettingsInternal) {
            SettingsScopeServices settingsServices = new SettingsScopeServices(services, (SettingsInternal) domainObject);
            registries.add(settingsServices);
            return settingsServices;
        }
        throw new IllegalArgumentException(String.format("Cannot create services for unknown domain object of type %s.",
                domainObject.getClass().getSimpleName()));
    }
```
从源码可以看出，对于 __GradleInternal__ 对象，创建的应是 __GradleScopeServices__：



##### DefaultServiceRegistry

虽然 __GradleScopeServices__ 继承自 __DefaultServiceRegistry__，但是我们会发现： __GradleScopeServices__ 没有任何 register service 的逻辑，而且我们看到在 __GradleScopeServices__ 定义了好多的方法，却找不到任何调用他们的地方。那么 __GradleScopeServices__ 又是如何运行起来的呢？

[org.gradle.internal.service.DefaultServiceRegistry]()

```java
    public DefaultServiceRegistry(String displayName, ServiceRegistry... parents) {
        ......
        findProviderMethods(this);
    }
    
        private void findProviderMethods(Object target) {
        Class<?> type = target.getClass();
        RelevantMethods methods = RelevantMethods.getMethods(type);
        for (ServiceMethod method : methods.decorators) {
            if (parentServices == null) {
                throw new ServiceLookupException(...);
            }
            ownServices.add(new FactoryMethodService(this, target, method));
        }
        for (ServiceMethod method : methods.factories) {
            ownServices.add(new FactoryMethodService(this, target, method));
        }
        for (ServiceMethod method : methods.configurers) {
            applyConfigureMethod(method, target);
        }
    }
    
```
实际上，这些逻辑都在其父类 __DefaultServiceRegistry__ 中，也就是在其构造方法直接调用的__findProviderMethods(...)__ 方法中。

__findProviderMethods(...)__ 通过反射的方式解析 class，然后根据 Method 的名称区分类型，将 __decorators__ 和 __factories__ 类型的 Method 封装成 __ServiceProvider__，而 __configurers__ 类型的方法会直接运行。

需要注意的是：如果 __configurers 或 factories__ 类型的方法有参数，那么这些参数也都是尝试从当前 __ServiceRegistry__ 中创建或者获取。



###### 附：RelevantMethods 

[org.gradle.internal.service.RelevantMethods]()

```java
    private static RelevantMethods buildRelevantMethods(Class<?> type) {
        RelevantMethodsBuilder builder = new RelevantMethodsBuilder(type);
        RelevantMethods relevantMethods;
        addDecoratorMethods(builder);
        addFactoryMethods(builder);
        addConfigureMethods(builder);
        relevantMethods = builder.build();
        return relevantMethods;
    }
    
    private static void addDecoratorMethods(RelevantMethodsBuilder builder) {
        Class<?> type = builder.type;
        Iterator<Method> iterator = builder.remainingMethods.iterator();
        while (iterator.hasNext()) {
            Method method = iterator.next();
            if (method.getName().startsWith("create") || method.getName().startsWith("decorate")) {
                if (method.getReturnType().equals(Void.TYPE)) {
                    throw new ServiceLookupException(...);
                }
                if (takesReturnTypeAsParameter(method)) {
                    builder.add(iterator, builder.decorators, method);
                }
            }
        }
    }
    ......
```

__RelevantMethods__ 这个类是实际负责解析 Class 的类，其中：

* 以 __create__ 和 __decorate__ 开头，同时参数中包含 _返回值类型的参数_ 的方法会被解析为 __DecoratorMethod__

* 以 __create__ 开头的非静态方法会被解析为 __FactoryMethod__

* 以 __configure__ 开头的方法会被解析为 __ConfigureMethod__












