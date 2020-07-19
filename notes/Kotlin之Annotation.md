###  Kotlin之Annotation

[annotations.html](https://kotlinlang.org/docs/reference/annotations.html)
[Kotlin in Action]()

#### declaring and applying annotations

* types of parameters ：primitive types, strings, enums, class references, other annotation classes, and arrays thereof.

* To specify a class as an annotation argument, put __::class__ after the class name: @MyAnnotation(MyClass::class).

* To specify another annotation as an argument, don’t put the @ character before the
annotation name.

* To specify an array as an argument, use the __arrayOf__ function: @RequestMapping(path = arrayOf("/foo", "/bar")).

* To use a property as an annotation argument, you need to mark it with a __const__ modifier, which tells the compiler that the property is a compile-time constant. 

*  Because annotation classes are only used to define the structure of metadata associated with declarations and expressions, they can’t contain any code. Therefore, the compiler prohibits specifying a body for an annotation class

* For annotations that have parameters, the parameters are declared in the primary constructor of the class:

```kotlin
annotation class JsonName(val name: String)
```

You use the regular primary constructor declaration syntax. The val keyword is mandatory for all parameters of an annotation class

* Note how the Java annotation has a method called __value__, whereas the Kotlin annotation has a __name__ property. 

*  If you need to apply an annotation declared in Java to a Kotlin element, however, you’re required to use the named-argument syntax for all arguments except value, which Kotlin also recognizes as special.

#### use-site target declaration

* The use-site target is placed between the @ sign and the annotation name and is separated from the name with a colon. 

```kotlin
@get:Rule
```
* property—Java annotations can’t be applied with this use-site target.

* field—Field generated for the property.

* get—Property getter.

* set—Property setter.

* receiver—Receiver parameter of an extension function or property.

* param—Constructor parameter.

* setparam—Property setter parameter.

* delegate—Field storing the delegate instance for a delegated property.

* file—Class containing top-level functions and properties declared in the file.

#### controlling the Java API with annotations

Note that unlike Java, Kotlin allows you to apply annotations to arbitrary expressions, not only to class and function declarations or types. 

* @JvmName changes the name of a Java method or field generated from a Kotlin declaration.

* @JvmStatic can be applied to methods of an object declaration or a companion object to expose them as static Java methods.

* @JvmOverloads, mentioned in section 3.2.2, instructs the Kotlin compiler to generate overloads for a function that has default parameter values.

* @JvmField can be applied to a property to expose that property as a public Java field with no getters or setters.

####  classes as annotation parameters

```kotlin
annotation class DeserializeInterface(val targetClass: KClass<out Any>)
```

```kotlin
annotation class CustomSerializer(
    val serializerClass: KClass<out ValueSerializer<*>>
)
```