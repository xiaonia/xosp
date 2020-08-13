
####              Gradle任务关系构建浅析 


#####  1  DefaultGradleLauncher

[org.gradle.initialization.DefaultGradleLauncher]()

##### 2  GradleScopeServices

[org.gradle.internal.service.scopes.GradleScopeServices]()

##### 3  TaskNameResolvingBuildConfigurationAction

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

__TaskNameResolvingBuildConfigurationAction__解析命令参数，搜集需要执行的task，并调用__addEntryTasks__添加到__taskGraph__中。

##### 4  DefaultGradle

[org.gradle.invocation.DefaultGradle]()

```java
    @Inject
    @Override
    public TaskExecutionGraphInternal getTaskGraph() {
        throw new UnsupportedOperationException();
    }
```

参考[Gradle运行体系之ServiceRegistry]()



#####  5  GradleScopeServices

[org.gradle.internal.service.scopes.GradleScopeServices]()

```java
TaskExecutionGraphInternal createTaskExecutionGraph(
        PlanExecutor planExecutor,
        List<NodeExecutor> nodeExecutors,
        BuildOperationExecutor buildOperationExecutor,
        ListenerBuildOperationDecorator listenerBuildOperationDecorator,
        ResourceLockCoordinationService coordinationService,
        GradleInternal gradleInternal,
        TaskNodeFactory taskNodeFactory,
        TaskDependencyResolver dependencyResolver,
        ListenerBroadcast<TaskExecutionListener> taskListeners,
        ListenerBroadcast<TaskExecutionGraphListener> graphListeners,
        ProjectStateRegistry projectStateRegistry,
        ServiceRegistry gradleScopedServices
    ) {
        return new DefaultTaskExecutionGraph(planExecutor, nodeExecutors, buildOperationExecutor, listenerBuildOperationDecorator, coordinationService, gradleInternal, taskNodeFactory, dependencyResolver, graphListeners, taskListeners, projectStateRegistry, gradleScopedServices);
}
```

参考[Gradle运行体系之ServiceRegistry]()



##### 6  DefaultTaskExecutionGraph

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

        LOGGER.debug("Timing: Creating the DAG took " + clock.getElapsed());
    }
```

##### 7  DefaultExecutionPlan

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
                node.resolveDependencies(dependencyResolver, targetNode -> {
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


##### 8  LocalTaskNode

[org.gradle.execution.plan.LocalTaskNode]()

```java
        @Override
    public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
        for (Node targetNode : getDependencies(dependencyResolver)) {
            addDependencySuccessor(targetNode);
            processHardSuccessor.execute(targetNode);
        }
        for (Node targetNode : getFinalizedBy(dependencyResolver)) {
            if (!(targetNode instanceof TaskNode)) {
                throw new IllegalStateException("Only tasks can be finalizers: " + targetNode);
            }
            addFinalizerNode((TaskNode) targetNode);
            processHardSuccessor.execute(targetNode);
        }
        for (Node targetNode : getMustRunAfter(dependencyResolver)) {
            addMustSuccessor((TaskNode) targetNode);
        }
        for (Node targetNode : getShouldRunAfter(dependencyResolver)) {
            addShouldSuccessor(targetNode);
        }
    }
    ......
    private Set<Node> getDependencies(TaskDependencyResolver dependencyResolver) {
        return dependencyResolver.resolveDependenciesFor(task, task.getTaskDependencies());
    }
```



#####  9  AbstractTask

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
```

__getTaskDependencies()__不仅包含显示指定的任务依赖(dependsOn)，也包括隐式依赖如 @Input 标注的成员函数或者成员变量。


#####  10  DefaultTaskInputs

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

#####  11  TaskDependencyResolver

[org.gradle.execution.plan.TaskDependencyResolver]()

```java
    public Set<Node> resolveDependenciesFor(@Nullable TaskInternal task, Object dependencies) {
        return context.getDependencies(task, dependencies);
    }
```

#####  12  CachingTaskDependencyResolveContext

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

##### 13  CachingDirectedGraphWalker

[org.gradle.internal.graph.CachingDirectedGraphWalker]()

```java
    private Set<T> doSearch() {
        ......
          graph.getNodeValues(node, details.values, details.successors);
        ......
    }
```

##### 14  CachingTaskDependencyResolveContext$TaskGraphImpl

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

##### 15  TaskDependencyContainer

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

__DirectoryProperty__继承自__TaskDependencyContainer__，因此该类型的 @Input 也会在构建任务依赖关系的时候被解析到，而这个__producer__是在Task实例化的时候绑定的，详细可参考[Gradle任务创建过程浅析]()


##### 16  Buildable

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

[org.gradle.api.internal.file.collections.DefaultConfigurableFileCollection]()

```java
    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        context.add(buildDependency);
        super.visitDependencies(context);
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

[org.gradle.api.internal.file.collections.BuildDependenciesOnlyFileCollectionResolveContext]()

```java
    @Override
    public boolean maybeAdd(Object element) {
        if (element instanceof ProviderInternal) {
            ProviderInternal provider = (ProviderInternal) element;
            return provider.maybeVisitBuildDependencies(taskContext);
        } else if (element instanceof TaskDependencyContainer || element instanceof Buildable) {
            taskContext.add(element);
        } else if (!(element instanceof MinimalFileCollection)) {
            throw new IllegalArgumentException("Don't know how to determine the build dependencies of " + element);
        } // else ignore
        return true;
    }
```
__DefaultConfigurableFileCollection__既继承自__TaskDependencyContainer__，也继承自__Buildable__，因此毫无疑问，它所代表的的 @Input 也会在构建任务依赖关系的时候被解析到。




