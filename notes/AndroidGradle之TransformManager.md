###                         AndroidGradle之TransformManager




####  TransformManager 

[com.android.build.gradle.internal.pipeline.TransformManager]()

```java
/**
 * Manages the transforms for a variant.
 *
 * <p>The actual execution is handled by Gradle through the tasks.
 * Instead it's a means to more easily configure a series of transforms that consume each other's
 * inputs when several of these transform are optional.
 */
public class TransformManager extends FilterableStreamCollection {
    ......
}
```

__TransformManager__管理及维护__Transform__之间的依赖关系，__Transform__之间依赖关系的建立是基于__生产者-消费者模型__:




#### addTransform()

```java
    /**
     * Adds a Transform.
     *
     * <p>This makes the current transform consumes whatever Streams are currently available and
     * creates new ones for the transform output.
     *
     * <p>his also creates a {@link TransformTask} to run the transform and wire it up with the
     * dependencies of the consumed streams.
     */
    @NonNull
    public <T extends Transform> Optional<TaskProvider<TransformTask>> addTransform(
            @NonNull TaskFactory taskFactory,
            @NonNull VariantScope scope,
            @NonNull T transform,
            @Nullable PreConfigAction preConfigAction,
            @Nullable TaskConfigAction<TransformTask> configAction,
            @Nullable TaskProviderCallback<TransformTask> providerCallback) {
                ......
                List<TransformStream> inputStreams = Lists.newArrayList();
        String taskName = scope.getTaskName(getTaskNamePrefix(transform));

        // get referenced-only streams
        List<TransformStream> referencedStreams = grabReferencedStreams(transform);

        // find input streams, and compute output streams for the transform.
        IntermediateStream outputStream = findTransformStreams(
                transform,
                scope,
                inputStreams,
                taskName,
                scope.getGlobalScope().getBuildDir());
                ......
                transforms.add(transform);

        // create the task...
        return Optional.of(
                taskFactory.register(
                        new TransformTask.CreationAction<>(
                                scope.getFullVariantName(),
                                taskName,
                                transform,
                                inputStreams,
                                referencedStreams,
                                outputStream,
                                recorder),
                        preConfigAction,
                        configAction,
                        providerCallback));
            }
```

* find referenced streams

* find input streams and register output stream

* register TransformTask

  


#### grabReferencedStreams

```java
    @NonNull
    private List<TransformStream> grabReferencedStreams(@NonNull Transform transform) {
        ......
    }
```


* find reference streams before they were consumed




#### findTransformStreams

```java
    /**
     * Finds the stream(s) the transform consumes, and return them.
     *
     * <p>This also removes them from the instance list. They will be replaced with the output
     * stream(s) from the transform.
     */
    @Nullable
    private IntermediateStream findTransformStreams(
            @NonNull Transform transform,
            @NonNull TransformVariantScope scope,
            @NonNull List<TransformStream> inputStreams,
            @NonNull String taskName,
            @NonNull File buildDir) {

        ......
    }
```

* find and consume output streams of registered transforms which had not been consumed.
* create and register output stream of current transform




#### consumeStreams

```java
    /**
     * <p>This method will remove all streams matching the specified scopes and types from the
     * available streams.
     *
     * @deprecated Use this method only for migration from transforms to tasks.
     */
    @Deprecated
    public void consumeStreams(
            @NonNull Set<? super Scope> requestedScopes, @NonNull Set<ContentType> requestedTypes) {
        consumeStreams(requestedScopes, requestedTypes, new ArrayList<>());
    }

    private void consumeStreams(
            @NonNull Set<? super Scope> requestedScopes,
            @NonNull Set<ContentType> requestedTypes,
            @NonNull List<TransformStream> inputStreams) {
        ......
    }
```

* find and consume complete or partial output stream 



####  StreamBasedTask

[com.android.build.gradle.internal.pipeline.StreamBasedTask]()

```java
/**
 * A base task with stream fields that properly use Gradle's input/output annotations to return the
 * stream's content as input/output.
 */
public abstract class StreamBasedTask extends AndroidVariantTask {

    /** Registered as task input in {@link #registerConsumedAndReferencedStreamInputs()}. */
    protected Collection<TransformStream> consumedInputStreams;
    /** Registered as task input in {@link #registerConsumedAndReferencedStreamInputs()}. */
    protected Collection<TransformStream> referencedInputStreams;

    protected IntermediateStream outputStream;
    
    ......
}
```

* __consumedInputStreams__ are consumed output streams of other transforms. See detail in [TransformManager.consumeStreams]()
* __referencedInputStreams__ are referenced output streams of other transforms. See detail in [TransformManager.grabReferencedStreams]()
* __outputStream__ is output stream of current transform. See detail in [TransformManager.findTransformStreams]()







