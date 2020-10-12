###         Gradle之Task创建过程浅析



#### Task创建方式：create & register

gradle中任务的创建方式有两种：

* 直接创建该Task

* 注册Task，即创建TaskProvider，真正使用到该Task时才创建



#### Task注册过程

当我们通过  __project.tasks.registerTask()__ 注册任务的时候，实际上调用的是 __TaskContainer__ 中的方法，其默认实现是 __DefaultTaskContainer__。

[org.gradle.api.internal.tasks.DefaultTaskContainer](https://github.com/gradle/gradle/blob/master/subprojects/core/src/main/java/org/gradle/api/internal/tasks/DefaultTaskContainer.java)

```java
private <T extends Task> TaskProvider<T> registerTask(final String name, final Class<T> type, @Nullable final Action<? super T> configurationAction, final Object... constructorArgs) {
        if (hasWithName(name)) {
            failOnDuplicateTask(name);
        }

        final TaskIdentity<T> identity = TaskIdentity.create(name, type, project);

        // 创建并返回TaskProvider
        TaskProvider<T> provider = buildOperationExecutor.call(new CallableBuildOperation<TaskProvider<T>>() {
            @Override
            public BuildOperationDescriptor.Builder description() {
                return registerDescriptor(identity);
            }

            @Override
            public TaskProvider<T> call(BuildOperationContext context) {
                TaskProvider<T> provider = Cast.uncheckedNonnullCast(
                    getInstantiator().newInstance(
                        TaskCreatingProvider.class, DefaultTaskContainer.this, identity, configurationAction, constructorArgs
                    )
                );
                addLaterInternal(provider);
                context.setResult(REGISTER_RESULT);
                return provider;
            }
        });

        if (eagerlyCreateLazyTasks) {
            provider.get();
        }

        return provider;
    }
```

任务注册的时候，只是创建了一个 __TaskProvider__ 实例，只有当实际需要使用该 Task 的时候才会触发任务创建过程。



```java
private <T extends Task> T createTask(TaskIdentity<T> identity, @Nullable Object[] constructorArgs) throws InvalidUserDataException {
        if (constructorArgs != null) {
            for (int i = 0; i < constructorArgs.length; i++) {
                if (constructorArgs[i] == null) {
                    throw new NullPointerException(String.format("Received null for %s constructor argument #%s", identity.type.getName(), i + 1));
                }
            }
        }
        // 创建Task实例
        return taskFactory.create(identity, constructorArgs);
    }
```

实际上，__registerTask()__ 最后调用的也还是  __createTask()__ 方法，  __createTask()__ 方法通过 __ITaskFactory__ 创建Task实例。__ITaskFactory__ 顾名思义就是创建 Task 的工厂类：



#### Task 创建过程

__ITaskFactory__ 这个接口，默认有三个实现类，那么我们究竟使用的是哪一个实现类呢？事实上，这三个实现类是以责任链的方式串联在一起，分别负责处理 Task 创建的不同过程：

[org.gradle.internal.service.scopes.BuildScopeServices]()

```java
 protected ITaskFactory createITaskFactory(Instantiator instantiator, TaskClassInfoStore taskClassInfoStore, PropertyWalker propertyWalker) {
        return new AnnotationProcessingTaskFactory(
            instantiator,
            taskClassInfoStore,
            new PropertyAssociationTaskFactory(
                new TaskFactory(),
                propertyWalker
            ));
    }
```



##### AnnotationProcessingTaskFactory 

[org.gradle.api.internal.project.taskfactory.AnnotationProcessingTaskFactory]()

```
/**
 * A {@link ITaskFactory} which determines task actions, inputs and outputs based on annotation attached to the task properties. Also provides some validation based on these annotations.
 */
```
如注释所说，__AnnotationProcessingTaskFactory__ 这个类主要是负责解析 Task 类的注解，处理标识 Task 内容的注解，例如处理 __@Action__ 注解，将改注解标识的方法封装成 Action 实例，并添加到 actionList 中



##### PropertyAssociationTaskFactory

[org.gradle.api.internal.project.taskfactory.PropertyAssociationTaskFactory]()

```java
private static class Listener implements PropertyVisitor {
        private final Task task;

        public Listener(Task task) {
            this.task = task;
        }

        @Override
        public boolean visitOutputFilePropertiesOnly() {
            return true;
        }
        
        @Override
        public void visitOutputFileProperty(String propertyName, boolean optional, PropertyValue value, OutputFilePropertyType filePropertyType) {
            // 注意：此处将task与property绑定在一起
            value.attachProducer(task);
        }
    }
```

而 __PropertyAssociationTaskFactory__ 则是负责遍历及初始化 __property__，其中最重要就是 __PropertyAssociationTaskFactory$Listener__ 这个内部类，这个内部类负责初始化 __@OutputFile__ 注解标识的 __property__，也就是将当前 task 实例绑定到该 __Property__ 中。

后续我们会讲到，这个 __Property__ 是连接两个不同的任务的纽带，也就是生产者生产和消费者消费的 __产品__ 。而恰恰是这个地方，将两个任务连接起来，消费者通过这个 __Property__ 便可以获取到生产者，从而建立起 __任务依赖关系__。



##### TaskFactory

[org.gradle.api.internal.project.taskfactory.TaskFactory]()

而 __TaskFactory__ 这个类才是真正负责实例化 Task 的类：

```java
public <S extends Task> S create(final TaskIdentity<S> identity, @Nullable final Object[] constructorArgs) {
        ......
        final Class<? extends AbstractTask> implType;
        if (identity.type.isAssignableFrom(DefaultTask.class)) {
            implType = DefaultTask.class;
        } else {
            implType = identity.type.asSubclass(AbstractTask.class);
        }

        return AbstractTask.injectIntoNewInstance(project, identity, new Callable<S>() {
            @Override
            public S call() {
                try {
                    Task instance;
                    if (constructorArgs != null) {
                        // 有构造参数
                        instance = instantiationScheme.instantiator().newInstance(implType, constructorArgs);
                    } else {
                        // 无构造参数
                        instance = instantiationScheme.deserializationInstantiator().newInstance(implType, AbstractTask.class);
                    }
                    return identity.type.cast(instance);
                } catch (ObjectInstantiationException e) {
                    ......
                }
            }
        });
    }
```

以上任务创建实例化的过程，需要注意的是：这里并不是简简单单的实例化 __implType__，而是动态的生成 __implType __ 类的子类，例如  __implType_Decorated__ 、 __implType$Decorated__ 或 __implType$Inject__。

为什么需要这么做呢？一方面，__implType__ 类很有可能只是抽象类，是无法直接实例化；另一方面，自动生成的装饰类也会添加一些辅助(DSL)方法：



#### 动态生成 Task 子类的过程

##### DefaultInstantiationScheme$DefaultDeserializationInstantiator

[org.gradle.internal.instantiation.generator.DefaultInstantiationScheme$DefaultDeserializationInstantiator]()

```java
@Override
        public <T> T newInstance(Class<T> implType, Class<? super T> baseClass) {
            // TODO - The baseClass can be inferred from the implType, so attach the serialization constructor onto the GeneratedClass rather than parameterizing and caching here
            try {
                ClassGenerator.SerializationConstructor<?> constructor = constructorCache.get(implType, type -> classGenerator.generate(implType).getSerializationConstructor(baseClass));
                return implType.cast(constructor.newInstance(services, nestedGenerator));
            } catch (InvocationTargetException e) {
                throw new ObjectInstantiationException(implType, e.getCause());
            } catch (Exception e) {
                throw new ObjectInstantiationException(implType, e);
            }
        }
```

当我们以无参数的方式实例化 Task 的时候，即会通过 DefaultDeserializationInstantiator 实例化该Task，而 DefaultDeserializationInstantiator 则会动态的生成 Task 的子类：



##### AbstractClassGenerator

[org.gradle.internal.instantiation.generator.AbstractClassGenerator]()

```java
/**
 * Generates a subclass of the target class to mix-in some DSL behaviour.
 *
 * <ul>
 * <li>For each property, a convention mapping is applied. These properties may have a setter method.</li>
 * <li>For each property whose getter is annotated with {@code Inject}, a service instance will be injected instead. These properties may have a setter method and may be abstract.</li>
 * <li>For each mutable property as set method is generated.</li>
 * <li>For each method whose last parameter is an {@link org.gradle.api.Action}, an override is generated that accepts a {@link groovy.lang.Closure} instead.</li>
 * <li>Coercion from string to enum property is mixed in.</li>
 * <li>{@link groovy.lang.GroovyObject} and {@link DynamicObjectAware} is mixed in to the class.</li>
 * <li>An {@link ExtensionAware} implementation is added, unless {@link NonExtensible} is attached to the class.</li>
 * <li>An {@link IConventionAware} implementation is added, unless {@link NoConventionMapping} is attached to the class.</li>
 * </ul>
 */
abstract class AbstractClassGenerator implements ClassGenerator {
    ......
}
```

我们重点来看一下，kotlin 代码中的 “ __abstract val property;__ ” 是最终动态生成的代码是什么？



##### AsmBackedClassGenerator

[org.gradle.internal.instantiation.generator.AsmBackedClassGenerator]()

```java
     private static class ClassBuilderImpl implements ClassGenerationVisitor {
        ......
        private String propFieldName(PropertyMetadata property) {
            return "__" + property.getName() + "__";
        }
        ......
        @Override
        public void applyReadOnlyManagedStateToGetter(PropertyMetadata property, Method getter) {
            // GENERATE public <type> <getter>() {
            //     if (<field> == null) {
            //         <field> = getFactory().newInstance(this, <display-name>, <type>, <prop-name>);
            //     }
            //     return <field>;
            // }
            ......
        } 
 
    }
```

从上面的代码可以看出，gradle 会为 __property__，动态生成一个 **"\__property__"** 的成员变量，并通过 __getFactory().newInstance(...)__ 初始化该变量，而 __getFactory()__ 返回的是 __ManagedObjectFactory__ 的实例：



##### ManagedObjectFactory

[org.gradle.internal.instantiation.generator.ManagedObjectFactory]()

```java
// Called from generated code
    public Object newInstance(GeneratedSubclass owner, @Nullable Describable ownerDisplayName, String propertyName, Class<?> type) {
        if (type.isAssignableFrom(ConfigurableFileCollection.class)) {
            return attachOwner(owner, ownerDisplayName, propertyName, getObjectFactory().fileCollection());
        }
        if (type.isAssignableFrom(ConfigurableFileTree.class)) {
            return attachOwner(owner, ownerDisplayName, propertyName, getObjectFactory().fileTree());
        }
        if (type.isAssignableFrom(DirectoryProperty.class)) {
            return attachOwner(owner, ownerDisplayName, propertyName, getObjectFactory().directoryProperty());
        }
        if (type.isAssignableFrom(RegularFileProperty.class)) {
            return attachOwner(owner, ownerDisplayName, propertyName, getObjectFactory().fileProperty());
        }
        return instantiator.newInstanceWithDisplayName(type, displayNameFor(owner, ownerDisplayName, propertyName));
    }
```
__ManagedObjectFactory__ 自动根据 __property__ 的类型，为其创建不同类型的实例，例如对于 ConfigurableFileCollection 创建的是 DefaultConfigurableFileCollection 实例；对于 DirectoryProperty 创建的是 DefaultDirectoryVar 实例等等。



