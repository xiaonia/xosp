#### Gradle之Task依赖构建浅析 



#### Gradle的运行过程

[org.gradle.initialization.DefaultGradleLauncher]() 

```java
    // 五个阶段
    private enum Stage {
        LoadSettings, Configure, TaskGraph, RunTasks() {
            @Override
            String getDisplayName() {
                return "Build";
            }
        }, Finished;

        String getDisplayName() {
            return name();
        }
    }
    
    ......
    private void runWork() {
        if (stage != Stage.TaskGraph) {
            throw new IllegalStateException("Cannot execute tasks: current stage = " + stage);
        }

        // 执行action
        List<Throwable> taskFailures = new ArrayList<Throwable>();
        buildExecuter.execute(gradle, taskFailures);
        if (!taskFailures.isEmpty()) {
            throw new MultipleBuildFailures(taskFailures);
        }

        stage = Stage.RunTasks;
    }
```

在整个 gradle 运行过程中，总共包含5个阶段：LoadSettings、Configure、TaskGraph、RunTasks、Finished。而在 Configure->TaskGraph 这个阶段，就是解析并建立 DAG 任务关系依赖图的过程。我们先来看一下这个 action 是什么时候创建的：



#### TaskNameResolvingBuildConfigurationAction的创建过程

[org.gradle.initialization.DefaultGradleLauncherFactory]()

```java
    private DefaultGradleLauncher doNewInstance(BuildDefinition buildDefinition,
                                                BuildState build,
                                                @Nullable GradleLauncher parent,
                                                BuildTreeScopeServices buildTreeScopeServices,
                                                List<?> servicesToStop) {
        ......
        GradleInternal gradle = serviceRegistry.get(Instantiator.class).newInstance(DefaultGradle.class, parentBuild, startParameter, serviceRegistry.get(ServiceRegistryFactory.class));
        ......
        TaskExecutionPreparer taskExecutionPreparer = gradle.getServices().get(TaskExecutionPreparer.class);
        DefaultGradleLauncher gradleLauncher = new DefaultGradleLauncher(......);
        ......
        return gradleLauncher;
    }
```

由 [Gradle之ServiceRegistry浅析]() 一文我们知道，以 create 开头的方法都会被封装成 FactoryMethod，当我们调用 __getService().get(TaskExecutionPreparer.class)__ 方法时即会调用 __createTaskExecutionPreparer()__ 方法。



[org.gradle.internal.service.scopes.GradleScopeServices]() 

```java
    BuildConfigurationActionExecuter createBuildConfigurationActionExecuter(CommandLineTaskParser commandLineTaskParser, TaskSelector taskSelector, ProjectConfigurer projectConfigurer, ProjectStateRegistry projectStateRegistry) {
        List<BuildConfigurationAction> taskSelectionActions = new LinkedList<BuildConfigurationAction>();
        taskSelectionActions.add(new DefaultTasksBuildExecutionAction(projectConfigurer));
        taskSelectionActions.add(new TaskNameResolvingBuildConfigurationAction(commandLineTaskParser));
        return new DefaultBuildConfigurationActionExecuter(Arrays.asList(new ExcludedTaskFilteringBuildConfigurationAction(taskSelector)), taskSelectionActions, projectStateRegistry);
    }
```
createTaskExecutionPreparer() 方法最后会调用 __createBuildConfigurationActionExecuter()__ 方法，这个方法会创建 TaskNameResolvingBuildConfigurationAction 并将其添加到 taskSelectionActions 中。TaskNameResolvingBuildConfigurationAction 顾名思义，就是负责解析并构建任务依赖关系的 Action：



[org.gradle.execution.TaskNameResolvingBuildConfigurationAction]()

```java
    @Override
    public void configure(BuildExecutionContext context) {
        GradleInternal gradle = context.getGradle();
        TaskExecutionGraphInternal taskGraph = gradle.getTaskGraph();

        List<TaskExecutionRequest> taskParameters = gradle.getStartParameter().getTaskRequests();
        for (TaskExecutionRequest taskParameter : taskParameters) {
            List<TaskSelector.TaskSelection> taskSelections = commandLineTaskParser.parseTasks(taskParameter);
            for (TaskSelector.TaskSelection taskSelection : taskSelections) {
                LOGGER.info("Selected primary task '{}' from project {}", taskSelection.getTaskName(), taskSelection.getProjectPath());
                taskGraph.addEntryTasks(taskSelection.getTasks());
            }
        }

        context.proceed();
    }
```

由源码可知：__TaskNameResolvingBuildConfigurationAction__ 先解析命令参数，搜集需要执行的 tasks，然后调用 __addEntryTasks __ 将这些 tasks 添加到 __taskGraph__ 中。这个 __taskGraph__ 就是 __DefaultTaskExecutionGraph__，详情参考：

[Gradle解析之深入理解ServiceRegistry]()

略

[org.gradle.invocation.DefaultGradle]()

略

[org.gradle.internal.service.scopes.GradleScopeServices]()

略



#### 添加及解析 Task 依赖关系

##### DefaultTaskExecutionGraph

[org.gradle.execution.taskgraph.DefaultTaskExecutionGraph]()

```java
    @Override
    public void addEntryTasks(Iterable<? extends Task> tasks) {
        assert tasks != null;

        final Timer clock = Time.startTimer();

        Set<Task> taskSet = new LinkedHashSet<Task>();
        for (Task task : tasks) {
            taskSet.add(task);
            requestedTasks.add(task);
        }

        executionPlan.addEntryTasks(taskSet);
        graphState = GraphState.DIRTY;
    }
```
DefaultTaskExecutionGraph 的 addEntryTasks() 方法稍加处理便将 tasks 添加到 executionPlan 中：



#####  DefaultExecutionPlan

[org.gradle.execution.plan.DefaultExecutionPlan]()

```java
    public void addEntryTasks(Collection<? extends Task> tasks) {
        final Deque<Node> queue = new ArrayDeque<>();

        List<Task> sortedTasks = new ArrayList<>(tasks);
        Collections.sort(sortedTasks);
        for (Task task : sortedTasks) {
            TaskNode node = taskNodeFactory.getOrCreateNode(task);
            ......
            entryNodes.add(node);
            queue.add(node);
        }

        doAddNodes(queue);
    }

    private void doAddNodes(Deque<Node> queue) {
        Set<Node> nodesInUnknownState = Sets.newLinkedHashSet();
        final Set<Node> visiting = Sets.newHashSet();

        while (!queue.isEmpty()) {
            Node node = queue.getFirst();
            ......

            if (visiting.add(node)) {
                // Have not seen this node before - add its dependencies to the head of the queue and leave this
                // node in the queue
                // Make sure it has been configured
                node.prepareForExecution();
                // 解析任务依赖关系
                node.resolveDependencies(dependencyResolver, targetNode -> {
                    // 注意这里会尝试将依赖的任务添加到queue中，递归的解析
                    if (!visiting.contains(targetNode)) {
                        queue.addFirst(targetNode);
                    }
                });
                ......
            } else {
                // Have visited this node's dependencies - add it to the graph
                queue.removeFirst();
                visiting.remove(node);
                node.dependenciesProcessed();
            }
        }
        resolveNodesInUnknownState(nodesInUnknownState);
    }
```

DefaultExecutionPlan 的 addEntryTasks() 方法先将 task 封装成 TaskNode，然后调用 doAddNodes() 方法添加并解析其依赖关系。



##### LocalTaskNode

[org.gradle.execution.plan.LocalTaskNode]()

```java
        @Override
    public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
        // dependsOn
        for (Node targetNode : getDependencies(dependencyResolver)) {
            addDependencySuccessor(targetNode);
            processHardSuccessor.execute(targetNode);
        }
        // finalizedBy
        for (Node targetNode : getFinalizedBy(dependencyResolver)) {
            if (!(targetNode instanceof TaskNode)) {
                throw new IllegalStateException("Only tasks can be finalizers: " + targetNode);
            }
            addFinalizerNode((TaskNode) targetNode);
            processHardSuccessor.execute(targetNode);
        }
        // mustRunAfter
        for (Node targetNode : getMustRunAfter(dependencyResolver)) {
            addMustSuccessor((TaskNode) targetNode);
        }
        // shouldRunAfter
        for (Node targetNode : getShouldRunAfter(dependencyResolver)) {
            addShouldSuccessor(targetNode);
        }
    }
    ......
    
    private Set<Node> getDependencies(TaskDependencyResolver dependencyResolver) {
        return dependencyResolver.resolveDependenciesFor(task, task.getTaskDependencies());
    }
```
对于同一个工程的 task，这个 Node 就是 __LocalTaskNode__ 。 __LocalTaskNode__ 解析任务依赖关系的过程类似于虚拟机垃圾回收的标记过程：以 根任务 为起点，递归的遍历解析其依赖的任务，直至所有依赖的任务都被遍历解析。



#####  AbstractTask

[org.gradle.api.internal.AbstractTask]()

```java

    private AbstractTask(TaskInfo taskInfo) {
        ......
        TaskContainerInternal tasks = project.getTasks();
        this.mustRunAfter = new DefaultTaskDependency(tasks);
        this.finalizedBy = new DefaultTaskDependency(tasks);
        this.shouldRunAfter = new DefaultTaskDependency(tasks);
        this.services = project.getServices();

        PropertyWalker propertyWalker = services.get(PropertyWalker.class);
        FileCollectionFactory fileCollectionFactory = services.get(FileCollectionFactory.class);
        taskMutator = new TaskMutator(this);
        // 隐式依赖
        taskInputs = new DefaultTaskInputs(this, taskMutator, propertyWalker, fileCollectionFactory);
        taskOutputs = new DefaultTaskOutputs(this, taskMutator, propertyWalker, fileCollectionFactory);
        taskDestroyables = new DefaultTaskDestroyables(taskMutator);
        taskLocalState = new DefaultTaskLocalState(taskMutator);

        this.dependencies = new DefaultTaskDependency(tasks, ImmutableSet.of(taskInputs));

        this.timeout = project.getObjects().property(Duration.class);
    }
    
    @Override
    public TaskDependencyInternal getTaskDependencies() {
        return dependencies;
    }
    
    // 显式依赖
    @Override
    public Task dependsOn(final Object... paths) {
        taskMutator.mutate("Task.dependsOn(Object...)", new Runnable() {
            @Override
            public void run() {
                dependencies.add(paths);
            }
        });
        return this;
    }
```

__getTaskDependencies()__ 方法返回的值，不仅包含显示指定的任务依赖(__dependsOn__)，也包括隐式依赖如 __@Input__ 标注的成员函数或者成员变量。



#####  TaskDependencyResolver

[org.gradle.execution.plan.TaskDependencyResolver]()

```java
    public Set<Node> resolveDependenciesFor(@Nullable TaskInternal task, Object dependencies) {
        return context.getDependencies(task, dependencies);
    }
```

[org.gradle.api.internal.tasks.CachingTaskDependencyResolveContext]()

```java
    public Set<T> getDependencies(@Nullable Task task, Object dependencies) {
        this.task = task;
        try {
            walker.add(dependencies);
            return walker.findValues();
        } catch (Exception e) {
            throw new TaskDependencyResolveException(...);
        } finally {
            queue.clear();
            this.task = null;
        }
    }
```

[org.gradle.internal.graph.CachingDirectedGraphWalker]()

```java
    private Set<T> doSearch() {
        ......
          graph.getNodeValues(node, details.values, details.successors);
        ......
    }
```

[org.gradle.api.internal.tasks.CachingTaskDependencyResolveContext$TaskGraphImpl]()

```java
        @Override
        public void getNodeValues(Object node, final Collection<? super T> values, Collection<? super Object> connectedNodes) {
            if (node instanceof TaskDependencyContainer) {
                TaskDependencyContainer taskDependency = (TaskDependencyContainer) node;
                queue.clear();
                taskDependency.visitDependencies(CachingTaskDependencyResolveContext.this);
                connectedNodes.addAll(queue);
            } else if (node instanceof Buildable) {
                Buildable buildable = (Buildable) node;
                connectedNodes.add(buildable.getBuildDependencies());
            } 
            ......
        }
```

TaskDependencyResolver 最后是通过 TaskGraphImpl 的 getNodeValues() 方法解析依赖关系：

* 对于 TaskDependencyContainer，通过调用 visitDependencies() 方法解析
* 对于 Buildable，通过调用 getBuildDependencies() 方法解析
* 对于其他类型，则委托给 workResolvers 进行处理



#### 显式依赖的解析过程

略



#### 隐式依赖的解析过程

#####  DefaultTaskInputs

[org.gradle.api.internal.tasks.DefaultTaskInputs]()

```java
    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        TaskPropertyUtils.visitProperties(propertyWalker, task, new PropertyVisitor.Adapter() {
            @Override
            public void visitInputProperty(String propertyName, PropertyValue value, boolean optional) {
                context.add(value.getTaskDependencies());
            }

            @Override
            public void visitInputFileProperty(final String propertyName, boolean optional, boolean skipWhenEmpty, boolean incremental, @Nullable Class<? extends FileNormalizer> fileNormalizer, PropertyValue value, InputFilePropertyType filePropertyType) {
                FileCollection actualValue = FileParameterUtils.resolveInputFileValue(fileCollectionFactory, filePropertyType, value);
                context.add(actualValue);
            }
        });
    }
```

DefaultTaskInputs 类继承自 TaskDependencyContainer 类，因此会通过调用 visitDependencies() 方法解析其依赖关系，而 visitDependencies() 方法则会遍历并解析 @Input 注解标记的属性。



#### Property依赖关系的解析过程

#####  TaskDependencyContainer

[org.gradle.api.internal.tasks.TaskDependencyContainer]()
```java
/**
 * An object that has task dependencies associated with it.
 */
public interface TaskDependencyContainer {
    /**
     * Adds the dependencies from this container to the given context. Failures to calculate the build dependencies are supplied to the context.
     */
    void visitDependencies(TaskDependencyResolveContext context);
}
```

[org.gradle.api.internal.provider.AbstractMinimalProvider]()

```java
    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        // When used as an input, add the producing tasks if known
        maybeVisitBuildDependencies(context);
    }
```

[org.gradle.api.internal.provider.AbstractProperty]()

```java

    private Task producer;
    
    @Override
    public void attachProducer(Task task) {
        if (this.producer != null && this.producer != task) {
            throw new IllegalStateException(...);
        }
        this.producer = task;
    }
    
    @Override
    public boolean maybeVisitBuildDependencies(TaskDependencyResolveContext context) {
        if (producer != null) {
            context.add(producer);
            return true;
        }
        return getSupplier().maybeVisitBuildDependencies(context);
    }
```

__AbstractProperty__ 继承自 __TaskDependencyContainer__，因此该类型的 __@Input__ 也会在构建任务依赖关系的时候被解析到，而这个 __producer__ 是在 Task 实例化的时候绑定的，详细可参考 [Gradle之Task创建过程浅析]()



#### DefaultConfigurableFileCollection依赖关系的解析过程

#####  Buildable

[org.gradle.api.tasks.TaskDependency.Buildable]()

```java
/**
 * A {@code Buildable} represents an artifact or set of artifacts which are built by one or more {@link Task}
 * instances.
 */
public interface Buildable {
    /**
     * Returns a dependency which contains the tasks which build this artifact. All {@code Buildable} implementations
     * must ensure that the returned dependency object is live, so that it tracks changes to the dependencies of this
     * buildable.
     *
     * @return The dependency. Never returns null. Returns an empty dependency when this artifact is not built by any
     *         tasks.
     */
    TaskDependency getBuildDependencies();
}
```

[org.gradle.api.internal.file.CompositeFileCollection]()

```java
    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        BuildDependenciesOnlyFileCollectionResolveContext fileContext = new BuildDependenciesOnlyFileCollectionResolveContext(context);
        visitContents(fileContext);
    }
```

[org.gradle.api.internal.file.collections.DefaultConfigurableFileCollection]()

```java
    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        context.add(buildDependency);
        super.visitDependencies(context);
    }
```

__DefaultConfigurableFileCollection__ 既继承自 __TaskDependencyContainer__，也继承自 __Buildable__，毫无疑问，它所代表的 __@Input__ 也会在构建任务依赖关系的时候被解析到。




