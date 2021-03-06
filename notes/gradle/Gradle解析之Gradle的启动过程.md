###                            Gradle之gradlew启动过程浅析



GradleWrapper是Gradle官方推出的便于我们切换运行Gradle的工具，对于我们来说只需要配置好__gradle-wrapper.properties__文件，然后运行__gradlew__即可 :




#### gradlew.sh

[gradlew.sh]()

```sh
APP_ARGS=$(save "$@")

# Collect all arguments for the java command, following the shell quoting and substitution rules
eval set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "\"-Dorg.gradle.appname=$APP_BASE_NAME\"" -classpath "\"$CLASSPATH\"" org.gradle.wrapper.GradleWrapperMain "$APP_ARGS"

# by default we should be in the correct project dir, but when run from Finder on Mac, the cwd is wrong
if [ "$(uname)" = "Darwin" ] && [ "$HOME" = "$PWD" ]; then
  cd "$(dirname "$0")"
fi

exec "$JAVACMD" "$@"
```

__gradlew __脚本搜集和配置环境变量，然后直接使用java命令运行__org.gradle.wrapper.GradleWrapperMain__，这个 __GradleWrapperMain__ 就是 __gradle-wrapper.jar__ 的入口：




####  GradleWrapperMain

[org.gradle.wrapper.GradleWrapperMain]()

```java
public class GradleWrapperMain {
    ......
    public static void main(String[] args) throws Exception {
        ......
        WrapperExecutor wrapperExecutor = WrapperExecutor.forWrapperPropertiesFile(propertiesFile);
        wrapperExecutor.execute(args, new Install(logger, new Download(logger, "gradlew", GradleWrapperMain.wrapperVersion()), new PathAssembler(gradleUserHome)), new BootstrapMainStarter());
    }
    ......
}
```

__GradleWrapperMain__ 先搜集命令行参数及环境变量，然后调用运行 __WrapperExecutor__:




####  WrapperExecutor

[org.gradle.wrapper.WrapperExecutor]()
```java
    public void execute(String[] args, Install install, BootstrapMainStarter bootstrapMainStarter) throws Exception {
        File gradleHome = install.createDist(this.config);
        bootstrapMainStarter.start(args, gradleHome);
    }
```

__WrapperExecutor __先解析 __gradle-wrapper.properties__ 文件，然后下载安装 __gradle-xxx-all.zip__，最后调用运行 __BootstrapMainStarter__:




####  BootstrapMainStarter

[org.gradle.wrapper.BootstrapMainStarter]()
```java
public class BootstrapMainStarter {
    public void start(String[] args, File gradleHome) throws Exception {
        File gradleJar = this.findLauncherJar(gradleHome);
        URLClassLoader contextClassLoader = new URLClassLoader(new URL[]{gradleJar.toURI().toURL()}, ClassLoader.getSystemClassLoader().getParent());
        Thread.currentThread().setContextClassLoader(contextClassLoader);
        Class<?> mainClass = contextClassLoader.loadClass("org.gradle.launcher.GradleMain");
        Method mainMethod = mainClass.getMethod("main", String[].class);
        mainMethod.invoke(null, new Object[]{args});
        if (contextClassLoader instanceof Closeable) {
            contextClassLoader.close();
        }
    }
    ......
}
```

__BootstrapMainStarter __先加载 __gradle-launcher-xxx.jar__，然后以反射的方式调用 __org.gradle.launcher.GradleMain__:




####  GradleMain

[org.gradle.launcher.GradleMain]()
```java
public class GradleMain {
    public static void main(String[] args) {
        try {
            UnsupportedJavaRuntimeException.assertUsingVersion("Gradle", JavaVersion.VERSION_1_8);
        } catch (UnsupportedJavaRuntimeException ex) {
            System.err.println(ex.getMessage());
            System.exit(1);
        }
        new ProcessBootstrap().run("org.gradle.launcher.Main", args);
    }
}
```

__GradleMain__ 简单的校验 Java 运行版本，然后通过 __ProcessBootstrap__ 初始化运行环境并以反射的方式调用__org.gradle.launcher.Main__: 



####  Main

[org.gradle.launcher.Main]()
```java
/**
 * The main command-line entry-point for Gradle.
 */
public class Main extends EntryPoint {
    public static void main(String[] args) {
        new Main().run(args);
    }

    @Override
    protected void doAction(String[] args, ExecutionListener listener) {
        createActionFactory().convert(Arrays.asList(args)).execute(listener);
    }

    CommandLineActionFactory createActionFactory() {
        return new DefaultCommandLineActionFactory();
    }
}
```

__Main __调用 __CommandLineActionFactory__ 先将输入参数封装为 __ParseAndBuildAction__，然后再包装为__WithLogging__ 并运行：




#### DefaultCommandLineActionFactory

[org.gradle.launcher.cli.DefaultCommandLineActionFactory$WithLogging]()

```java

    private static class WithLogging implements CommandLineExecution {
        ......
        @Override
        public void execute(ExecutionListener executionListener) {
            ......
            Action<ExecutionListener> exceptionReportingAction = new ExceptionReportingAction(action, reporter, loggingManager);
            try {
                NativeServices.initialize(buildLayout.getGradleUserHomeDir());
                loggingManager.attachProcessConsole(loggingConfiguration.getConsoleOutput());
                new WelcomeMessageAction(buildLayout).execute(Logging.getLogger(WelcomeMessageAction.class));
                exceptionReportingAction.execute(executionListener);
            } finally {
                loggingManager.stop();
            }
        }
    }
```

__WithLogging __顾名思义，就是配置 log 输出，此处不细究。



[org.gradle.launcher.cli.DefaultCommandLineActionFactory$ParseAndBuildAction]()

```java

    private class ParseAndBuildAction implements Action<ExecutionListener> {
        ......
        @Override
        public void execute(ExecutionListener executionListener) {
            List<CommandLineAction> actions = new ArrayList<CommandLineAction>();
            actions.add(new BuiltInActions());
            createActionFactories(loggingServices, actions);

            CommandLineParser parser = new CommandLineParser();
            for (CommandLineAction action : actions) {
                action.configureCommandLineParser(parser);
            }

            Action<? super ExecutionListener> action;
            try {
                ParsedCommandLine commandLine = parser.parse(args);
                action = createAction(actions, parser, commandLine);
            } catch (CommandLineArgumentException e) {
                action = new CommandLineParseFailureAction(parser, e);
            }

            action.execute(executionListener);
        }

        private Action<? super ExecutionListener> createAction(Iterable<CommandLineAction> factories, CommandLineParser parser, ParsedCommandLine commandLine) {
            for (CommandLineAction factory : factories) {
                Runnable action = factory.createAction(parser, commandLine);
                if (action != null) {
                    return Actions.toAction(action);
                }
            }
            throw new UnsupportedOperationException(...);
        }
    }
```

__ParseAndBuildAction__ 先调用 __CommandLineParser__ 解析输入参数，然后调用 __createAction()__ 方法创建一个可执行 __Action__ 并执行：




####  BuildActionsFactory

[org.gradle.launcher.cli.BuildActionsFactory]()

```java
class BuildActionsFactory implements CommandLineAction {
    ......
    @Override
    public Runnable createAction(CommandLineParser parser, ParsedCommandLine commandLine) {
        Parameters parameters = parametersConverter.convert(commandLine, new Parameters(fileCollectionFactory));

        parameters.getDaemonParameters().applyDefaultsFor(jvmVersionDetector.getJavaVersion(parameters.getDaemonParameters().getEffectiveJvm()));

        if (parameters.getDaemonParameters().isStop()) {
            return stopAllDaemons(parameters.getDaemonParameters());
        }
        if (parameters.getDaemonParameters().isStatus()) {
            return showDaemonStatus(parameters.getDaemonParameters());
        }
        if (parameters.getDaemonParameters().isForeground()) {
            ......
            return new ForegroundDaemonAction(loggingServices, conf);
        }
        if (parameters.getDaemonParameters().isEnabled()) {
            return runBuildWithDaemon(parameters.getStartParameter(), parameters.getDaemonParameters());
        }
        if (canUseCurrentProcess(parameters.getDaemonParameters())) {
            return runBuildInProcess(parameters.getStartParameter(), parameters.getDaemonParameters());
        }

        return runBuildInSingleUseDaemon(parameters.getStartParameter(), parameters.getDaemonParameters());
    }
    
    ......
    
    private Runnable runBuildInProcess(StartParameterInternal startParameter, DaemonParameters daemonParameters) {
        ServiceRegistry globalServices = ServiceRegistryBuilder.builder()
                .displayName("Global services")
                .parent(loggingServices)
                .parent(NativeServices.getInstance())
                .provider(new GlobalScopeServices(startParameter.isContinuous()))
                .build();

        // Force the user home services to be stopped first, the dependencies between the user home services and the global services are not preserved currently
        return runBuildAndCloseServices(startParameter, daemonParameters, globalServices.get(BuildExecuter.class), globalServices, globalServices.get(GradleUserHomeScopeServiceRegistry.class));
    }
}
```

__BuildActionsFactory__ 根据输入参数判断运行模式，然后创建和配置 __Action__；需要注意的是：对于非 __daemon模式__ ，__GlobalScopeServices__ 正是在这个地方创建并初始化的，关于daemon模式，参考 [gradle_daemon](https://docs.gradle.org/current/userguide/gradle_daemon.html)




####  GlobalScopeServices

[org.gradle.internal.service.scopes.GlobalScopeServices]()

```java
/**
 * Defines the extended global services of a given process. This includes the CLI, daemon and tooling API provider. The CLI
 * only needs these advances services if it is running in --no-daemon mode.
 */
public class GlobalScopeServices extends WorkerSharedGlobalScopeServices {
    ......
    void configure(ServiceRegistration registration, ClassLoaderRegistry classLoaderRegistry) {
        registration.add(ClassLoaderScopeListeners.class);
        final List<PluginServiceRegistry> pluginServiceFactories = new DefaultServiceLocator(classLoaderRegistry.getRuntimeClassLoader(), classLoaderRegistry.getPluginsClassLoader()).getAll(PluginServiceRegistry.class);
        for (PluginServiceRegistry pluginServiceRegistry : pluginServiceFactories) {
            registration.add(PluginServiceRegistry.class, pluginServiceRegistry);
            pluginServiceRegistry.registerGlobalServices(registration);
        }
    }
    ......    
}
```

[org.gradle.tooling.internal.provider.LauncherServices]()

```java
public class LauncherServices extends AbstractPluginServiceRegistry {
    @Override
    public void registerGlobalServices(ServiceRegistration registration) {
        registration.addProvider(new ToolingGlobalScopeServices());
    }
    ......
}
```

[org.gradle.tooling.internal.provider.LauncherServices$ToolingGlobalScopeServices]()

```java
    static class ToolingGlobalScopeServices {
        BuildExecuter createBuildExecuter(...) {
        ......
        }
    }
```

__GlobalScopeServices__ 是整个 Gradle 系统 Service 机制的入口，由 [Gradle之ServiceRegistry浅析]() 一文可知，__GlobalScopeServices __实例化的时候即会自动执行 __configure**()__ 类型的方法。因此这里：

* 先触发创建__LauncherServices__，

* 然后将 __ToolingGlobalScopeServices__ 注册到 __GlobalScopeServices__。

  

所以上文中 __globalServices.get(BuildExecuter.class)__ 方法调用实际上调用的是 __ToolingGlobalScopeServices.createBuildExecuter()__ 这个方法。




#### RunBuildAction

[org.gradle.launcher.cli.RunBuildAction]()

```java
public class RunBuildAction implements Runnable {
    ......
    @Override
    public void run() {
        try {
            BuildActionResult result = executer.execute(
                    new ExecuteBuildAction(startParameter),
                    new DefaultBuildRequestContext(new DefaultBuildRequestMetaData(clientMetaData, startTime, sharedServices.get(ConsoleDetector.class).isConsoleInput()), new DefaultBuildCancellationToken(), new NoOpBuildEventConsumer()),
                    buildActionParameters,
                    sharedServices);
        } finally {
            if (stoppable != null) {
                stoppable.stop();
            }
        }
    }
    ......
}
```

__RunBuildAction__ 将命令参数封装为 __ExecuteBuildAction__，然后调用 __BuildActionExecuter__ 执行该Action：




#### InProcessBuildActionExecuter

[org.gradle.launcher.exec.InProcessBuildActionExecuter]()

```java
    @Override
    public BuildActionResult execute(final BuildAction action, final BuildRequestContext buildRequestContext, BuildActionParameters actionParameters, ServiceRegistry contextServices) {
        BuildStateRegistry buildRegistry = contextServices.get(BuildStateRegistry.class);
        final PayloadSerializer payloadSerializer = contextServices.get(PayloadSerializer.class);
        BuildOperationNotificationValve buildOperationNotificationValve = contextServices.get(BuildOperationNotificationValve.class);

        buildOperationNotificationValve.start();
        try {
            RootBuildState rootBuild = buildRegistry.createRootBuild(BuildDefinition.fromStartParameter(action.getStartParameter(), null));
            return rootBuild.run(new Transformer<BuildActionResult, BuildController>() {
                @Override
                public BuildActionResult transform(BuildController buildController) {
                    BuildActionRunner.Result result = buildActionRunner.run(action, buildController);
                    if (result.getBuildFailure() == null) {
                        return BuildActionResult.of(payloadSerializer.serialize(result.getClientResult()));
                    }
                    if (buildRequestContext.getCancellationToken().isCancellationRequested()) {
                        return BuildActionResult.cancelled(payloadSerializer.serialize(result.getBuildFailure()));
                    }
                    return BuildActionResult.failed(payloadSerializer.serialize(result.getClientFailure()));
                }
            });
        } finally {
            buildOperationNotificationValve.stop();
        }
    }
```

我们以 __InProcessBuildActionExecuter__ 为例，看一下 __BuildActionExecuter__ 是如何执行 Action 的：

* 首先创建 __RootBuildState__
* 然后调用 __RootBuildState__ 执行 __Transformer__




####  DefaultIncludedBuildRegistry

[org.gradle.composite.internal.DefaultIncludedBuildRegistry]()

```java
public class DefaultIncludedBuildRegistry implements BuildStateRegistry, Stoppable {
    ......
    @Override
    public RootBuildState createRootBuild(BuildDefinition buildDefinition) {
        if (rootBuild != null) {
            throw new IllegalStateException("Root build already defined.");
        }
        rootBuild = new DefaultRootBuildState(buildDefinition, gradleLauncherFactory, listenerManager, rootServices);
        addBuild(rootBuild);
        return rootBuild;
    }
    ......
}
```

__DefaultIncludedBuildRegistry __负责创建 __RootBuildState__，__RootBuildState __represents the root build of a build tree。




####  DefaultRootBuildState

[org.gradle.composite.internal.DefaultRootBuildState]()

```java
    DefaultRootBuildState(BuildDefinition buildDefinition, GradleLauncherFactory gradleLauncherFactory, ListenerManager listenerManager, BuildTreeScopeServices parentServices) {
        this.listenerManager = listenerManager;
        gradleLauncher = gradleLauncherFactory.newInstance(buildDefinition, this, parentServices);
    }
    ......
    @Override
    public <T> T run(Transformer<T, ? super BuildController> buildAction) {
        final GradleBuildController buildController = new GradleBuildController(gradleLauncher);
        RootBuildLifecycleListener buildLifecycleListener = listenerManager.getBroadcaster(RootBuildLifecycleListener.class);
        GradleInternal gradle = buildController.getGradle();
        buildLifecycleListener.afterStart(gradle);
        try {
            return buildAction.transform(buildController);
        } finally {
            buildLifecycleListener.beforeComplete(gradle);
        }
    }
```

__DefaultRootBuildState __ 在实例化的时候会创建 __GradleLauncher__，同时run的时候会创建__GradleBuildController__。



####  DefaultGradleLauncherFactory

[org.gradle.initialization.DefaultGradleLauncherFactory]()

```java
public class DefaultGradleLauncherFactory implements GradleLauncherFactory {
    @Override
    public GradleLauncher newInstance(BuildDefinition buildDefinition, RootBuildState build, BuildTreeScopeServices parentRegistry) {
        ......
        DefaultGradleLauncher launcher = doNewInstance(buildDefinition, build, null, parentRegistry, ImmutableList.of(new Stoppable() {
            @Override
            public void stop() {
                rootBuild = null;
            }
        }));
        rootBuild = launcher;
        ......
        return launcher;
    }
    
    ......
    
    private DefaultGradleLauncher doNewInstance(BuildDefinition buildDefinition,
                                                BuildState build,
                                                @Nullable GradleLauncher parent,
                                                BuildTreeScopeServices buildTreeScopeServices,
                                                List<?> servicesToStop) {
        BuildScopeServices serviceRegistry = new BuildScopeServices(buildTreeScopeServices);
        serviceRegistry.add(BuildDefinition.class, buildDefinition);
        serviceRegistry.add(BuildState.class, build);
        NestedBuildFactoryImpl nestedBuildFactory = new NestedBuildFactoryImpl(buildTreeScopeServices);
        serviceRegistry.add(NestedBuildFactory.class, nestedBuildFactory);

        ListenerManager listenerManager = serviceRegistry.get(ListenerManager.class);
        for (Action<ListenerManager> action : serviceRegistry.getAll(BuildScopeListenerManagerAction.class)) {
            action.execute(listenerManager);
        }

        ScriptUsageLocationReporter usageLocationReporter = new ScriptUsageLocationReporter();
        listenerManager.addListener(usageLocationReporter);
        StartParameter startParameter = buildDefinition.getStartParameter();
        ShowStacktrace showStacktrace = startParameter.getShowStacktrace();
        switch (showStacktrace) {
            case ALWAYS:
            case ALWAYS_FULL:
                LoggingDeprecatedFeatureHandler.setTraceLoggingEnabled(true);
                break;
            default:
                LoggingDeprecatedFeatureHandler.setTraceLoggingEnabled(false);
        }

        DeprecatedUsageBuildOperationProgressBroadaster deprecationWarningBuildOperationProgressBroadaster = serviceRegistry.get(DeprecatedUsageBuildOperationProgressBroadaster.class);
        DeprecationLogger.init(usageLocationReporter, startParameter.getWarningMode(), deprecationWarningBuildOperationProgressBroadaster);

        GradleInternal parentBuild = parent == null ? null : parent.getGradle();

        SettingsPreparer settingsPreparer = serviceRegistry.get(SettingsPreparer.class);

        GradleInternal gradle = serviceRegistry.get(Instantiator.class).newInstance(DefaultGradle.class, parentBuild, startParameter, serviceRegistry.get(ServiceRegistryFactory.class));

        IncludedBuildControllers includedBuildControllers = gradle.getServices().get(IncludedBuildControllers.class);
        TaskExecutionPreparer taskExecutionPreparer = gradle.getServices().get(TaskExecutionPreparer.class);

        DefaultGradleLauncher gradleLauncher = new DefaultGradleLauncher(
            gradle,
            serviceRegistry.get(ProjectsPreparer.class),
            serviceRegistry.get(ExceptionAnalyser.class),
            gradle.getBuildListenerBroadcaster(),
            listenerManager.getBroadcaster(BuildCompletionListener.class),
            gradle.getServices().get(BuildWorkExecutor.class),
            serviceRegistry,
            servicesToStop,
            includedBuildControllers,
            settingsPreparer,
            taskExecutionPreparer,
            gradle.getServices().get(InstantExecution.class),
            gradle.getServices().get(BuildSourceBuilder.class));
        nestedBuildFactory.setParent(gradleLauncher);
        nestedBuildFactory.setBuildCancellationToken(buildTreeScopeServices.get(BuildCancellationToken.class));
        return gradleLauncher;
    }
    ......
}
```

需要注意的是 __DefaultGradleLauncherFactory__ 在创建 __DefaultGradleLauncher__ 的时候，即会触发创建__BuildScopeServices__，然后触发创建 __GradleInternal__，而 __GradleInternal__ 实例化的时候又会创建并注册__GradleScopeServices__，这个 __GradleScopeServices__ 正是任务执行的入口，详情参考 [Gradle之Task依赖构建浅析]()



#### Gradle运行的五个阶段

```java
public class DefaultGradleLauncher implements GradleLauncher {
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
    ....
}
```









