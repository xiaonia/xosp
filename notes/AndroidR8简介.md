### Android R8 简介

搬运自官方文档：[shrink-code](https://developer.android.com/studio/build/shrink-code)

相关链接：[proguard](https://www.guardsquare.com/en/products/proguard/manual)     [retrace](https://www.guardsquare.com/en/products/proguard/manual/retrace)



#### 简介

AndroidGradlePlugin3.4.0之后，默认使用R8替代原来的ProGuard，R8在编译过程中主要执行：

* Code shrinking (or tree-shaking): 检测及删除无用代码

* Resource shrinking: 检测及删除无用资源，包括 Code shrinking 删除的代码中引用到的资源

* Obfuscation: 混淆即使用简单字符替代原来的类名、方法名和变量名，减少（复用）字符串常量

* Optimization: 代码优化，例如方法内联等

另外R8兼容ProGuard的所有配置，因此升级之后无需对原来的ProGuard配置做额外的适配工作。



#### 打包配置

```groovy
android {
    buildTypes {
        release {
            // Enables code shrinking, obfuscation, and optimization for only
            // your project's release build type.
            minifyEnabled true

            // Enables resource shrinking, which is performed by the
            // Android Gradle plugin.
            shrinkResources true

            // Includes the default ProGuard rules files that are packaged with
            // the Android Gradle plugin. To learn more, go to the section about
            // R8 configuration files.
            proguardFiles getDefaultProguardFile(
                    'proguard-android-optimize.txt'),
                    'proguard-rules.pro'
        }
    }
    ...
}
```



#### R8 configuration files

R8默认的配置文件主要包括：

* __\<module-dir>/proguard-rules.pro__  每个module下默认的混淆规则文件

* __proguard-android-optimize.txt__   AndroidGradlePlugin自动生成的Android项目混淆规则文件

* __\<library-dir>/proguard.txt__   aar依赖包含的混淆规则文件

* __\<library-dir>/META-INF/proguard/__ jar包中包含的混淆规则文件

* __\<module-dir>/build/intermediates/proguard-rules/debug/aapt_rules.txt__ AAPT2根据资源文件的信息，自动生成的混淆规则文件例如Activity类等

__需要注意的是，以上任意一个混淆规则文件都会应用到整个工程项目中，因此对于一些影响范围较大的混淆规则或者配置应慎用。__

```
// You can specify any path and filename.
-printconfiguration ~/tmp/full-r8-config.txt
```
另外，可以在混淆规则文件添加如上配置，输出merge之后的混淆规则信息。



#### Include additional configurations

```groovy
android {
    ...
    buildTypes {
        release {
            minifyEnabled true
            proguardFiles getDefaultProguardFile(
              'proguard-android-optimize.txt'),
              // List additional ProGuard rules for the given build type here. By default,
              // Android Studio creates and includes an empty rules file for you (located
              // at the root directory of each module).
              'proguard-rules.pro'
        }
    }
    flavorDimensions "version"
    productFlavors {
        flavor1 {
          ...
        }
        flavor2 {
            proguardFile 'flavor2-rules.pro'
        }
    }
}
```



#### Shrink your code

代码压缩的逻辑类似于虚拟机的垃圾回收机制-标记清除法：基于入口点(entry point)构建出引用链，不在引用链上的类、方法和变量都会被删除。入口点(entry point)主要是基于上文的混淆规则文件(-keep规则)生成。

##### seeds.txt

可以在混淆规则文件配置
```
-printseeds <output-dir>/seeds.txt
```
指定生成seeds.txt文件，默认路径为： __\<module- name>/build/outputs/mapping/\<build-type>/__。顾名思义，该文件记录的是被视为入口点(entry point)的类、方法和变量信息。

##### Customize which code to keep

AndroidGradlePlugin自动生成的混淆规则中，不包括反射调用和JNI层调用的java代码，因此需要手动增加相应的keep规则，相关文档参考[proguard](https://www.guardsquare.com/en/products/proguard/manual)

##### usage.txt

可以通过在混淆规则文件配置
```
 -printusage <output-dir>/usage.txt
```
指定生成usage.txt文件，默认路径为： __\<module- name>/build/outputs/mapping/\<build-type>/__。该文件记录了被删除的类、方法和变量信息。



#### Strip native libraries

By default, native code libraries are stripped in release builds of your app. This stripping consists of removing the symbol table and debugging information contained in any native libraries used by your app. Stripping native code libraries results in significant size savings; however, it's impossible to debug your code due to the missing information (such as class and function names).



#### Shrink your resources

资源压缩需与代码压缩配合，代码压缩过程中被删掉的代码，如果其引用的资源其他代码均未引用到，该资源同样会被删除。

##### Customize which resources to keep 

资源压缩时，也可以通过keep文件来配置哪些资源需要保留，哪些资源需要被删除。不同的是，资源的压缩规则是配置在xml文件中，该xml放在任意Android资源目录均可，另外该xml最终也会被剔除。

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@layout/l_used*_c,@layout/l_used_a,@layout/l_used_b*"
    tools:discard="@layout/unused2" />
```

* 通过__tools:keep__来指定需要保留的资源

* 通过__tools:discard__来指定需要删除的资源

需要注意的是，官方文档有指出__删除资源这个功能在多渠道打包时可以用来区分不同渠道的资源。__

##### Enable strict reference checks

默认情况下，R8除了根据资源ID查找引用关系之外，还会查找__Resources.getIdentifier()__方法调用，例如：

```java
String name = String.format("img_%1d", angle + 1);
res = getResources().getIdentifier(name, "drawable", getPackageName());
```
上面这种场景，所有以__img__开头的资源都会被保留下来。

除此之外，R8还会分析代码中的字符串信息，找到所有__URL__格式的字符串，解析并保留相关资源，例如：

```java
file:///android_res/drawable//ic_plus_anim_016.png
```

这种非精确匹配固然可以减少出错的概率，但同时也会产生很多落网之鱼，因此可以通过xml配置启用严格模式，即精确匹配：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:shrinkMode="strict" />
```

##### resources.txt

默认情况下，开启资源压缩会自动在  __\<module- name>/build/outputs/mapping/\<build-type>/__ 目录下生成resources.txt文件，该文件记录了各个资源的引用信息以及删除信息。

另外这些信息也可以直接使用AndroidStudio查看，详情见链接[roubleshoot-resource-shrink](https://developer.android.com/studio/build/shrink-code#troubleshoot-resource-shrink)

##### Remove unused alternative resources

资源压缩只会删除无用资源，而适配类型的资源需要通过手动配置来过滤：

```groovy
android {
    defaultConfig {
        ...
        resConfigs "en", "fr"
    }
}
```



#### Obfuscate your code

The purpose of obfuscation is to reduce your app size by shortening the names of your app’s classes, methods, and fields. 

##### Decode an obfuscated stack trace

R8混淆过程除了重命名类名、方法名和变量名之外，还会修改__LineNumberTable__信息，这就会导致__日志中输出的崩溃栈与实际代码行数存在差异__。因此打包的时候会在 __\<module- name>/build/outputs/mapping/\<build-type>/__目录自动生成__mapping.txt__文件： 记录重命名的类名、方法名和变量名信息，以及代码行数映射信息。可以通过这个文件找到对于的崩溃栈信息。

另外官方文档也指出可以使用 [retrace](https://www.guardsquare.com/en/products/proguard/manual/retrace) 工具自动转换崩溃栈信息。下载链接：[proguard](https://github.com/Guardsquare/proguard)



#### Code optimization

代码优化方面，R8通过分析代码，可以更精确的删除无用代码，甚至重写代码，例如：

* 删除无用冗余的else分支 

* 如果某一个方法只有一个地方用到了，则删除原方法，重写为内联代码块

* 如果一个类只有一个子类，且该子类无子子类，则合并这两个类

需要注意的是，ProGuard优化相关的设置对R8是不起作用的，例如__-optimizations__和__ - optimizationpasses__。

##### Enable more aggressive optimizations

可以通过设置编译参数开启激进式的R8，即FullMode。类似于资源压缩的StrictMode，对于非明确指定保留的无用代码，都会被删除。

```
android.enableR8.fullMode=true
```




