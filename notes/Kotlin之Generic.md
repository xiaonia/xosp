###                                                         Kotlin之Generic


[generics.html](https://kotlinlang.org/docs/reference/generics.html)

[inline-functions.html](https://kotlinlang.org/docs/reference/inline-functions.html)

[Kotlin in Action](books/kotlin/Kotlin in Action.pdf)



#### 泛型擦除(type eraser)

* __泛型擦除__指的是泛型只存在于编译期，编译完之后，代码中不会保存泛型的信息，例如 List\<String>，编译完或者运行时只是 List\<Object>

  


#### subtype & subclass

* __subclass__即我们熟悉的类派生关系，也就是如果B是A的子类，那么B就是A的__subclass__

* __subtype__指的是类的可替代关系，也就是如果A出现的地方可以用B替代，那么B就是A的__subtype__

* 实际上泛型的协变、逆变和不变指的就是__subclass__和__subtype__之间的关系

  


#### 协变(covariant) & 逆变(contravariance) & 不变(invariant)

__不变__指的是__subtype__和__subclass__没有任何关系，例如：若 B extends A，则任何情况下 List\<A> 和 List\<B> 均不能互相替代。事实上，在Java中任何指定类型（不加任何特殊修饰符）的泛型都是__不变__，例如：List\<String>。正是由于Java中默认__不变__的存在，因此才需要__协变__和__逆变__，否则泛型几乎毫无灵活性可言。

所谓__协变__指的是__subtype__和__subclass__保持__相同__的从属关系，例如：若 B extends A，则使用 List\<A> 的地方可以用 List\<B> 替代。__协变__比较好理解，因为它符合我们对派生关系的认识。

而__逆变__则指的是__subtype__和__subclass__保持__相反__的从属关系，例如：若 B extends A，则使用 List\<B> 的可以用 List\<A> 替代。那么__逆变__又是为什么呢？其实这个就是由于上文我们讲到的__泛型擦除__，在运行时， List\<A> 和  List\<B> 都会以 List\<Object> 存在，那么就存在这样一种可能：如果相关的逻辑处理只需处理 A 相关的逻辑而不需要处理 B 额外拓展的逻辑，那么我们就可以使用  List\<A> 替代  List\<B>，例如：

```kotlin
open class A

class B : A()

fun printList(listB: MutableList<in B>) {
    listB.forEach {
        println(it?.toString())
    }
}

fun test() {
    val listA = mutableListOf<A>()
    printList(listA)
}
```

尽管 printList 声明处理 List\<in B> 类型的数据，但是显而易见  printlnList  传入 任何B类和B的父类 的 List 均可以，这就跟java代码声明 __List\<? super B> listB__是类似的，这就是逆变。




#### 声明处型变(declaration-site variance) & 使用处型变(use-site variance / type projections)

__声明处型变__指的是在定义类的时候声明型变，__声明处型变__表示这个类在任何地方都保持型变关系

```kotlin
class Herd<out T : Animal> (val animals: List<T>) {

    val size: Int get() = animals.size

    operator fun get(i: Int): T {
        return animals[i]
    }

}
```

__使用处型变__指的是在定义方法的时候声明型变，__使用处型变__表示仅在该方法中保持型变关系

```kotlin
fun <T> copyData(source: MutableList<out T>, destination: MutableList<T>) {
    for (item in source) {
        destination.add(item)
    }
}
```



#### in(consume) & out(produce)

__in__指的是在方法输入位置使用泛型，即作为(public/protected/internal)方法(包括隐式的setter和getter)的__参数__，

__out__指的是在方法输出位置使用泛型，即作为方法(public/protected/internal)方法(包括隐式的setter和getter)的__返回值__。

另外这些方法__不包括构造方法__，但是对于kotlin来说需要关注kotlin构造方法中关联的隐式__setter__和__getter__方法。



#### star-projections(\*)

通配符(\*)表示__某一未知类型__，注意__不是任何类型__，类似于Java中的 ? 。对于某一(未知)特定类型的泛型，不支持 增改 操作，因为这样存在风险。

* 对于MutableList\<T>，MutableList<\*> 表示__某一未知__类型元素的集合，相当于MutableList<out Any?>，此时可以__get__元素，但是不能__add__元素。

* 对于MutableList\<T>， MutableList<Any?> 表示__任何__类型元素的集合

* 对于Consumer\<in T>，Consumer<\*>相当于Consumer\<in Nothing>

* 对于Consumer\<in T : U>，Consumer<\*>相当于Consumer\<in Nothing>

* 对于Producer\<out T>，Consumer<\*>相当于Consumer\<out Any?>

* 对于Producer\<out T : U >，Consumer<\*>相当于Consumer\<out U>



#### 泛型限定(generic constraints)

单个泛型上界：

```kotlin
fun <T : Comparable<T>> sort(list: List<T>) {  ... }
```

多个泛型上界：

```kotlin
fun <T> copyWhenGreater(list: List<T>, threshold: T): List<String>
    where T : CharSequence,
          T : Comparable<T> {
    return list.filter { it > threshold }.map { it.toString() }
}
```






