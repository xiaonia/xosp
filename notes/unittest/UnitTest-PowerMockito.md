### PowerMockito

#### 常见问题汇总

- mockStatic

    静态方法的 mock，可以先进行 mock，然后再单独调用那个方法，特别是无返回值的静态方法：
    
    when(StaticClass.class).doAnswer(...)
    StaticClass.staticMethod()

- SuppressStaticInitializationFor
    
    使用该注解可以跳过相关类的静态初始化逻辑，主要是由于部分类的静态初始化逻辑无法或者很难进行 mock

#### 其他