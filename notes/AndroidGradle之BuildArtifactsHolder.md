###                                       AndroidGradle之BuildArtifactsHolder


[com.android.build.gradle.internal.scope.BuildArtifactsHolder]()


#### producesDir

```java
    /**
     * Registers a new [Directory] producer for a particular [ArtifactType]. The producer is
     * identified by a [TaskProvider] to avoid configuring the task until the produced [Directory]
     * is required by another [Task].
     */
    fun <T: Task> producesDir(
        artifactType: ArtifactType<Directory>,
        operationType: OperationType,
        taskProvider: TaskProvider<out T>,
        productProvider: (T) -> DirectoryProperty,
        buildDirectory: String? = null,
        fileName: String = "out"
    ) {

        produces(artifactType,
            directoryProducersMap,
            operationType,
            taskProvider,
            productProvider,
            project.objects.directoryProperty(),
            fileName,
            buildDirectory)
    }
```



#### produces

```java
    private fun <T : FileSystemLocation, U: Task> produces(artifactType: ArtifactType<T>,
        producersMap: ProducersMap<T>,
        operationType: OperationType,
        taskProvider: TaskProvider<out U>,
        productProvider: (U) -> Property<T>,
        settableFileLocation: Property<T>,
        fileName: String,
        buildDirectory: String? = null) {
        ......
        val producers = producersMap.getProducers(artifactType, buildDirectory)
        val product= taskProvider.map { productProvider(it) }

        checkOperationType(operationType, artifactType, producers, taskProvider)
        producers.add(settableFileLocation, product, taskProvider.name, fileName)

        // note that this configuration block may be called immediately in case the task has
        // already been initialized.
        taskProvider.configure {

            product.get().set(settableFileLocation)

            // add a new configuration action to make sure the producers are configured even
            // if no one injects the result. The task is being configured so it will be executed
            // and output folders must be set correctly.
            // this can happen when users request an intermediary task execution (instead of
            // assemble for instance).
            producers.resolveAllAndReturnLast()
        }
    }
```

* 创建或者获取已创建的 __Producers__
* 创建并注册的 __Producer__
* 配置 __ConfigureAction__，这个 action 在任务创建的时候执行，如果已创建则立即执行




#### setTaskInputToFinalProduct

```java
    /**
     * Sets a [Property] value to the final producer for the given artifact type.
     */
    fun <T: FileSystemLocation> setTaskInputToFinalProduct(artifactType: ArtifactType<T>, taskInputProperty: Property<T>) {
        val finalProduct = getFinalProduct<T>(artifactType)
        taskInputProperty.set(finalProduct)
    }
```



#### getFinalProduct

```java
    /**
     * Returns a [Provider] of either a [Directory] or a [RegularFile] depending on the passed
     * [ArtifactType.Kind]. The [Provider] will represent the final value of the built artifact
     * irrespective of when this call is made.
     *
     * If there are more than one producer appending artifacts for the passed type, calling this
     * method will generate an error and [getFinalProducts] should be used instead.
     *
     * The simplest way to use the mechanism is as follow :
     */
    fun <T: FileSystemLocation> getFinalProduct(artifactType: ArtifactType): Provider<T> {
        val producers = getProducerMap(artifactType).getProducers(artifactType)
        ......
        return producers.injectable as Provider<T>
    }
```



#### getFinalProduct

```java
    /**
     * Returns a [Provider] of either a [Directory] or a [RegularFile] depending on the passed
     * [ArtifactKind]. The [Provider] will represent the final value of the built artifact
     * irrespective of when this call is made.
     */
    fun <T: FileSystemLocation> getFinalProduct(artifactType: ArtifactType<T>): Provider<T> {
        val producers = getProducerMap(artifactType).getProducers(artifactType)
        if (producers.size > 1) {
            throw java.lang.RuntimeException(...)
        }
        return producers.injectable
    }
```



#### getProducers

```java
/**
     * Returns a [Producers] instance (possibly empty of any [Producer]) for a passed
     * [ArtifactType]
     *
     * @param artifactType the artifact type for looked up producers.
     * @param buildLocation intended location for the artifact or not provided if using the default.
     * @return a [Producers] instance for that [ArtifactType]
     */
    internal fun getProducers(artifactType: ArtifactType<T>, buildLocation: String? = null): Producers<T> {

        val outputLocationResolver: (Producers<T>, Producer<T>) -> Provider<T> =
            if (buildLocation != null) {
                { _, producer -> producer.resolve(buildDirectory.dir(buildLocation).get().asFile) }
            } else {
                { producers, producer ->
                    val outputLocation = artifactType.getOutputDirectory(
                        buildDirectory,
                        identifier(),
                        if (producers.hasMultipleProducers()) producer.taskName else "")
                    producer.resolve(outputLocation) }
            }

        return producersMap.getOrPut(artifactType) {
            Producers(...)
        }!!
    }
```

* 创建或者获取已创建的 __Producers__

* 调用并返回 __injectable__，__injectable__ 会触发 __Producer__ 的 __resolve()__ 方法，该方法主要是配置及初始化文件夹路径



#### Task创建和配置执行顺序 

[com.android.build.gradle.internal.tasks.factory.TaskFactoryUtils.kt]()

```kotlin
/**
 * Extension function for [TaskContainer] to add a way to create a task with our
 * [VariantTaskCreationAction] without having a [TaskFactory]
 */
fun <T : Task> TaskContainer.registerTask(
    creationAction: TaskCreationAction<T>,
    secondaryPreConfigAction: PreConfigAction? = null,
    secondaryAction: TaskConfigAction<in T>? = null,
    secondaryProviderCallback: TaskProviderCallback<T>? = null
): TaskProvider<T> {
    val actionWrapper = TaskAction(creationAction, secondaryPreConfigAction, secondaryAction, secondaryProviderCallback)
    return this.register(creationAction.name, creationAction.type, actionWrapper)
        .also { provider ->
            actionWrapper.postRegisterHook(provider)
        }
}
```

```kotlin
/**
 * Wrapper for the [VariantTaskCreationAction] as a simple [Action] that is passed
 * to [TaskContainer.register].
 *
 * If the task is configured during the register then [VariantTaskCreationAction.preConfigure] is called
 * right away.
 *
 * After register, if it has not been called then it is called,
 * alongside [VariantTaskCreationAction.handleProvider]
 */
private class TaskAction<T: Task>(
    val creationAction: TaskCreationAction<T>,
    val secondaryPreConfigAction: PreConfigAction? = null,
    val secondaryAction: TaskConfigAction<in T>? = null,
    val secondaryProviderCallback: TaskProviderCallback<T>? = null
) : Action<T> {

    var hasRunPreConfig = false

    override fun execute(task: T) {
        doPreConfig(task.name)

        creationAction.configure(task)
        secondaryAction?.configure(task)
    }

    fun postRegisterHook(taskProvider: TaskProvider<out T>) {
        doPreConfig(taskProvider.name)

        creationAction.handleProvider(taskProvider)
        secondaryProviderCallback?.handleProvider(taskProvider)
    }

    private fun doPreConfig(taskName: String) {
        if (!hasRunPreConfig) {
            creationAction.preConfigure(taskName)
            secondaryPreConfigAction?.preConfigure(taskName)
            hasRunPreConfig = true
        }
    }

}
```

执行顺序：

* 创建 __TaskProvider__

* 执行 __CreationAction.handleProvider(taskProvider)__

* 调用 __TaskProvider.get()__，触发创建 __Task__，参考 [Gradle之Task创建过程浅析]()

* Task创建之后执行 __CreationAction.configure(task)__

