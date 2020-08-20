###                                    mapping.txt文件解读

#### 链接


[manual](https://www.guardsquare.com/en/products/proguard/manual/retrace)

[download](https://android.googlesource.com/platform/prebuilts/r8/+/master/retrace/)

[github](https://github.com/Guardsquare/proguard)



#### mapping文件格式

[ReTrace.java](https://github.com/Guardsquare/proguard/blob/master/retrace/src/proguard/retrace/ReTrace.java)

[MappingPrinter.java](https://github.com/Guardsquare/proguard/blob/master/base/src/proguard/obfuscate/MappingPrinter.java)



```
classline
    fieldline * N
    methodline * M
```

__mapping.txt__文件的格式如上所示：__classline__是类信息，紧接着是__N__个__fieldline__的变量信息，以及__M__个__methodline__的方法信息。



```
originalclassname -> obfuscatedclassname:
```

__classline__的格式如上所示：由一个 __-\>__ 分隔：前半部分是原始类名，后半部分是混淆之后的类名



```
    originalfieldtype originalfieldname -> obfuscatedfieldname
```

__fieldline__的格式如上所示：同样由 __->__ 分隔：前半部分是原始变量类型和变量名，后半部分是混淆之后的变量名，变量类型的混淆信息在其__classline__信息中。



```
    [startline:endline:]originalreturntype [originalclassname.]originalmethodname(originalargumenttype,...)[:originalstartline[:originalendline]] -> obfuscatedmethodname
```

__methodline__的格式较为复杂，但同样是通过 __->__ 分隔（这些都是在同一行输出的）：

* __[startline:endline:]__  一般表示混淆之后的代码行数(也就是日志中崩溃栈的行数)

* __originalreturntype__  表示原始的方法返回值类型

* __[originalclassname.]__  表示原始的方法定义类；如果是当前类，则可省略

* __originalmethodname__  表示原始的方法名称

* __(originalargumenttype,...)__  表示原始的方法参数列表

* __[:originalstartline[:originalendline]]__  表示原始的方法行数

* __obfuscatedmethodname__  表示混淆之后方法的名称

另外：

* 如果方法的行数保持不变，则 __[:originalstartline[:originalendline]]__ 信息可省略

* 如果方法的行数被删除，则省略 __[startline:endline:]__ 信息

* 如果是__inline__代码块，则只有 __[:originalstartline]__ 信息，表示被调用的(原始)行数

* 同一个方法的信息可能划分为多个 __methodline__ 输出（因为压缩了中间的冗余行数信息）

* 不同的方法行数可以相同，这个时候 __retrace__ 可以通过方法名称进行识别区分

区分是否是__inline__代码块：

如果__连续两个或以上__ __methodline__ 的 __[startline:endline:]__ 信息一样，而且除了第一行 __[:originalstartline[:originalendline]] __有两个值之外，后面的 __[:originalstartline[:originalendline]] __都只有一个值， 则表示这部分代码是__inline__代码。此时 __[startline:endline:]__ 不再是方法所在行数，而是方法的  __原始行数 + 1000 * K__（K可为0，这样的取值是为了区分这些__inline__代码块）。在这种情况下，方法所在的行数以及原始调用行数信息是放在 __[:originalstartline[:originalendline]]__ 上的。




#### 例子

```java
// Main类未混淆
com.example.application.Main -> com.example.application.Main:
    // 变量configuration混淆为a
    com.example.application.Configuration configuration -> a
    // 构造函数未混淆，其行数不变，与原始行数一样是50-60
    50:66:void <init>(com.example.application.Configuration) -> <init>
    // execute()方法混淆为a，代码行数不变，与原始行数一样是74-228
    74:228:void execute() -> a
    // 将GPL类的check()方法39-56行内联至execute()方法的76行
    2039:2056:void com.example.application.GPL.check():39:56 -> a
    2039:2056:void execute():76 -> a
    // 将当前类的printConfiguration()方法236-252行内联至execute()方法的80行
    2236:2252:void printConfiguration():236:252 -> a
    2236:2252:void execute():80 -> a
    // 这是一个嵌套内联
    // 将PrintWriter类的createPrintWriterOut方法内联至printConfiguration方法块的243行，然后该方法块又被内联至execute()方法的80行
    // 80和243指的是原始调用行数，40-42是代码的原始行数范围
    3040:3042:java.io.PrintWriter com.example.application.util.PrintWriterUtil.createPrintWriterOut(java.io.File):40:42 -> a
    3040:3042:void printConfiguration():243 -> a
    3040:3042:void execute():80 -> a
    // 将当前类的readInput()方法260-268行内联至execute()方法的97行
    3260:3268:void readInput():260:268 -> a
    3260:3268:void execute():97 -> a
```

从上面的信息中也可以看出 readInput() 方法和 printConfiguration() 方法均被删除并内联至调用的地方，当然代码删除信息，我们可以通过 __usage.txt__ 文件查询。



另外构造函数可能会存在方法行数跳变的情况，这是因为构造函数中除了显式写在构造函数中的代码，还有隐式调用的代码，如部分实例变量的初始化:

```java
49:    private static class ExtractedDex extends File {
50:        public long crc = NO_VALUE;

52:        public ExtractedDex(File dexDir, String fileName) {
53:            super(dexDir, fileName);
54:        }
55:    }
```

```java
android.support.multidex.MultiDexExtractor$ExtractedDex -> android.support.multidex.MultiDexExtractor$ExtractedDex:
    1:1:void <init>(java.io.File,java.lang.String):53:53 -> <init>
    2:2:void <init>(java.io.File,java.lang.String):50:50 -> <init>
```



#### retrace工具

retrace是官方提供的一款工具，可自动根据 __mapping.txt__ 和 __stacktrace.txt__ 转换崩溃栈信息。

```
java -jar retrace.jar[options...] mapping_file[stacktrace_file]
```

命令格式如上，另外：

* __-verbose__ 指定输出更详细的调用栈信息

* __-regex__ 指定的日志中特定的崩溃栈格式，默认解析常规的崩溃栈格式

详情参考[retrace](https://www.guardsquare.com/en/products/proguard/manual/retrace)

需要指出的是，__该工具不保证能完全解析所有的崩溃栈的信息，对于无法正确识别的崩溃栈信息，该工具会将所有可能的调用信息输出，需要我们自行结合代码逻辑进行判断__。

