### mockk

#### 常见问题汇总

- `getProperty` 的 private property 必须要有 `getter` 方法，否则会报 `missing method` 异常

- `mockkConstructor()` 不能 mock 在构造函数执行的方法

- vararg 函数的 capture 可以利用 varargAll { } 实现

- extension function 
  
    可以使用 every { any<Clazz>().extensionFun() } 来进行 mock

- spyk 和 lazy 

    `lazy { }` 代码块引用的是其外部类的对象，而 `spyk()`方法 mock 的是该方法返回的对象（原对象的代理），因此对该对象的 mock 操作不适用于 `lazy { }` 代码块内的逻辑。可以通过 `mockkConstructor()` 方法来实现。

#### 其他