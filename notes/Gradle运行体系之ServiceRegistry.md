####                                      Gradle运行体系之ServiceRegistry




#####  1 DefaultGradle

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

相信很多人对__DefaultGradle__这个类并不陌生，因为当我们通过 __project.gradle__调用的时候，实际上调用的就是这个类。

然而，当我们第一次接触这个类的时候，肯定也会好奇：这个类是个抽象类，gradle系统并没有任何该类的实现类，而且有很多__getTaskGraph()__这样的方法，它又是如何使整个gradle体系运行起来的呢？

如果我们在运行时，通过 __project.gradle.getClass()__输出其Class信息，我们会发现，这个类居然是 __org.gradle.invocation.DefaultGradle_Decorated__。

事实上，上面的所有问题都跟gradle系统__运行时动态生成字节码__有关：gradle系统可以自动生成抽象类的抽象方法，同时也可以根据注解自动生成成员变量和成员函数。



#####  2 AbstractClassGenerator

[org.gradle.internal.instantiation.generator.AbstractClassGenerator]()

```java
    private GeneratedClassImpl generateUnderLock(Class<?> type) {
    ......
    InjectAnnotationPropertyHandler injectionHandler = new InjectAnnotationPropertyHandler();
    ......
    Class<?> generatedClass;
        try {
            ClassInspectionVisitor inspectionVisitor = start(type);

            inspectType(type, validators, handlers, extensibleTypeHandler);
            for (ClassGenerationHandler handler : handlers) {
                handler.applyTo(inspectionVisitor);
            }

            ClassGenerationVisitor generationVisitor = inspectionVisitor.builder();
            for (ClassGenerationHandler handler : handlers) {
                handler.applyTo(generationVisitor);
            }
            if (type.isInterface()) {
                generationVisitor.addDefaultConstructor();
            } else {
                for (Constructor<?> constructor : type.getConstructors()) {
                    generationVisitor.addConstructor(constructor);
                }
            }

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

__generateUnderLock__ 方法是运行时生成字节码的核心处理方法：

* 首先调用 __inspectClass()__ 方法通过反射解析Class

* 然后分发给 __ClassGenerationHandler__ 生成成员变量和成员函数

* 最后生成构造方法

  

######  2.1 ClassInspector

[org.gradle.internal.reflect.ClassInspector]()

```java
    private static void inspectClass(Class<?> type, MutableClassDetails classDetails) {
        for (Method method : type.getDeclaredMethods()) {
            classDetails.method(method);

            if (Modifier.isPrivate(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
                continue;
            }

            PropertyAccessorType accessorType = PropertyAccessorType.of(method);
            if (accessorType == PropertyAccessorType.GET_GETTER || accessorType == PropertyAccessorType.IS_GETTER) {
                String propertyName = accessorType.propertyNameFor(method);
                classDetails.property(propertyName).addGetter(method);
            } else if (accessorType == PropertyAccessorType.SETTER) {
                String propertyName = accessorType.propertyNameFor(method);
                classDetails.property(propertyName).addSetter(method);
            } else {
                classDetails.instanceMethod(method);
            }
        }
    }
```

__ClassInspector__是实际上负责解析Class的，需要注意的该方法__只会解析Method__，对于__Getter__和__Setter__方法还会被解析为相应的__property__



###### 2.2 InjectAnnotationPropertyHandler

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

__InjectAnnotationPropertyHandler__ 主要负责处理 __@Inject__ 标注的成员函数：

* 生成成员变量 **__<property_name>__**
* 覆写成员函数 **get<Property_name>**
* 覆写成员函数 **set<Property_name>**



###### 2.3 AsmBackedClassGenerator$ClassBuilderImpl

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

最后生成的成员函数为：

```java
public <type> <getter>() {
    if (<field> == null) {
        <field> = getServices().get(<service-type>);
    }
    return <field>
}
```



##### 3 DefaultGradle

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

绕了一圈，我们又回到了最开始的地方！




##### 4 DefaultServiceRegistry

虽然__BuildTreeScopeServices__继承自__DefaultServiceRegistry__，但是我们会发现：__BuildTreeScopeServices__却没有任何register的逻辑，我们能看到的只是在__BuildTreeScopeServices__定义了好多的方法，却找不到任何调用他们的地方。

那么__BuildTreeScopeServices__又是如何运行起来的呢？

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
实际上，这些逻辑都在其父类__DefaultServiceRegistry__中，也就是在构造方法直接调用的__findProviderMethods()__方法。

__findProviderMethods__通过反射的方式解析class，然后根据Method的名称区分类型，将__decorators__和__factories__类型的Method封装成__ServiceProvider__，而__configurers__类型的方法会直接运行。

需要注意的是，如果__configurers__类型的方法有参数，那么这些参数都是尝试从当前__ServiceRegistry__中创建或者获取。



###### 4.1 RelevantMethods 

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

__RelevantMethods__这个类是实际负责解析Class的，其中：

* 以__create__和__decorate__开头，同时参数中包含返回值类型的参数 的方法会被解析为__DecoratorMethod__
* 以__create__开头的非静态方法会被解析为__FactoryMethod__
* 以__configure__开头的方法会被解析为__ConfigureMethod__












