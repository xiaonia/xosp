###  Android ART 虚拟机 AOT 和 JIT 内联机制(inline-cache)浅析



#### inline 简介

在 C 或者 C++ 语言中，有一种 [inline_function](https://en.wikipedia.org/wiki/Inline_function) 函数，顾名思义，inline_function 就是在编译的时候，直接用函数的代码替换函数调用，例如：

函数声明定义：

```c++
inline void swap(int *m, int *n)
{
    int tmp = *m;
    *m = *n;
    *n = tmp;
}
```

函数调用：

```c++
swap(&x, &y);
```

编译之后：

```c++
int tmp = x;
x = y;
y = tmp;
```

通过代码内联就可以省去一次函数调用，提高代码的执行效率。



#### inline_caching 简介

[inline_caching](https://en.wikipedia.org/wiki/Inline_caching) 也是一种类似的优化方式，只不过 inline_caching 是专门针对动态类型语言做的优化。动态类型的语言在运行时存在一个方法查找的过程，如果每次调用方法都要进行方法查找，对于代码的运行效率影响还是比较大的，inline_caching 正是为此而生，其基本实现逻辑是：

* 首次调用方法，则进入方法查找的过程，然后将结果缓存到 CallSite。

* 非首次调用方法，则先在 CallSite 里查找是否有匹配的缓存；如果没有再进行方法查找，并再次缓存到 CallSite。

同时，由于无法确定某个类或接口总共会有多少了派生类，因此也不能无限制的缓存所有的情况，需要有一个合适缓存策略，这就是 inline_caching 的四种状态：

* __uninitialized__：已缓存 __0__ 个派生类的查找结果

* __monomorphic__：有且仅缓存 __1__ 个派生类的查找结果

* __polymorphic__：有且缓存 __2个以上，N个之内__ 个派生类的查找结果

* __megamorphic__：缓存 __N(及N个以上)__ 派生类的查找结果

其中 N 为最大缓存限制，超过 N 个则不再缓存查找结果。事实上，Android ART 虚拟机在 AOT 或者 JIT 编译的时候，也仅会针对 __monomorphic__ 和 __polymorphic__ 进行内联，对于 __uninitialized__ 和 __megamorphic__ 均不会进行处理。



#### ART 虚拟机 inline caching 的实现方式

上文说到，AOT 或 JIT 编译的时候，仅对  __monomorphic__ 和 __polymorphic__ 进行内联，那么是如何进行内联的呢？

* 在解释器解释执行的时候，记录统计方法的执行次数，达到一定次数则标记为 warm-method
* 记录下 warm-method 内 virtual-method 和 interface-method 方法调用的查找结果，并保存到 profile 文件，一般在 /data/misc/profiles/.. 
* 在 AOT 或 JIT 编译的时候，通过添加类型检查指令和分支指令，将 Callee(inlined-method) 方法的代码内联(替换)至 Caller(outer-method) 方法

举个栗子：

[Implement polymorphic inlining.](https://android.googlesource.com/platform/art/+/916cc1d504f10a24f43b384e035fdecbe6a74b4c)

```java
// For example, before:
HInvokeVirtual

// After:
If (receiver == Foo) {
  // inlined code.
} else if (receiver == Bar) {
  // inlined code
} else {
  // HInvokeVirtual or HDeoptimize(receiver != Baz)
}
```

另外也可以看到，修改之后的指令逻辑，最后有一个容错处理分支，也就是在未匹配到任何类型之后，会执行原来未优化的指令，从而保证代码不会产生异常。



#### 记录过程

##### 创建 ProfilingInfo

[interpreter.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/interpreter/interpreter.cc)

```cpp
static inline JValue Execute(
    Thread* self,
    const CodeItemDataAccessor& accessor,
    ShadowFrame& shadow_frame,
    JValue result_register,
    bool stay_in_interpreter = false) REQUIRES_SHARED(Locks::mutator_lock_) {
  DCHECK(!shadow_frame.GetMethod()->IsAbstract());
  DCHECK(!shadow_frame.GetMethod()->IsNative());
  if (LIKELY(shadow_frame.GetDexPC() == 0)) {  // Entering the method, but not via deoptimization.
    if (kIsDebugBuild) {
      self->AssertNoPendingException();
    }
    instrumentation::Instrumentation* instrumentation = Runtime::Current()->GetInstrumentation();
    ArtMethod *method = shadow_frame.GetMethod();
    if (UNLIKELY(instrumentation->HasMethodEntryListeners())) {
      // 分发 MethodEnter 事件
      instrumentation->MethodEnterEvent(self,
                                        shadow_frame.GetThisObject(accessor.InsSize()),
                                        method,
                                        0);
      if (UNLIKELY(self->IsExceptionPending())) {
        instrumentation->MethodUnwindEvent(self,
                                           shadow_frame.GetThisObject(accessor.InsSize()),
                                           method,
                                           0);
        return JValue();
      }
    }
    if (!stay_in_interpreter) {
      jit::Jit* jit = Runtime::Current()->GetJit();
      if (jit != nullptr) {
        // 分发 MethodEnter 事件
        jit->MethodEntered(self, shadow_frame.GetMethod());
        if (jit->CanInvokeCompiledCode(method)) {
          JValue result;
          // Pop the shadow frame before calling into compiled code.
          self->PopShadowFrame();
          // Calculate the offset of the first input reg. The input registers are in the high regs.
          // It's ok to access the code item here since JIT code will have been touched by the
          // interpreter and compiler already.
          uint16_t arg_offset = accessor.RegistersSize() - accessor.InsSize();
          ArtInterpreterToCompiledCodeBridge(self, nullptr, &shadow_frame, arg_offset, &result);
          // Push the shadow frame back as the caller will expect it.
          self->PushShadowFrame(&shadow_frame);
          return result;
        }
      }
    }
  }
  ......
}
```
[instrumentation.h](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/instrumentation.h)
[instrumentation.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/instrumentation.cc)

```cpp
void Instrumentation::MethodEnterEventImpl(Thread* thread,
                                           ObjPtr<mirror::Object> this_object,
                                           ArtMethod* method,
                                           uint32_t dex_pc) const {
  DCHECK(!method->IsRuntimeMethod());
  if (HasMethodEntryListeners()) {
    Thread* self = Thread::Current();
    StackHandleScope<1> hs(self);
    Handle<mirror::Object> thiz(hs.NewHandle(this_object));
    for (InstrumentationListener* listener : method_entry_listeners_) {
      if (listener != nullptr) {
        // 继续分发 MethodEnter 事件
        listener->MethodEntered(thread, thiz, method, dex_pc);
      }
    }
  }
}
```

[jit.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/jit/jit.cc)

```cpp
void Jit::MethodEntered(Thread* thread, ArtMethod* method) {
  Runtime* runtime = Runtime::Current();
  if (UNLIKELY(runtime->UseJitCompilation() && runtime->GetJit()->JitAtFirstUse())) {
    // 首次调用就启用JIT编译，默认该配置为false
    // The compiler requires a ProfilingInfo object.
    ProfilingInfo::Create(thread,
                          method->GetInterfaceMethodIfProxy(kRuntimePointerSize),
                          /* retry_allocation */ true);
    JitCompileTask compile_task(method, JitCompileTask::kCompile);
    compile_task.Run(thread);
    return;
  }
  
  ProfilingInfo* profiling_info = method->GetProfilingInfo(kRuntimePointerSize);
  // Update the entrypoint if the ProfilingInfo has one. The interpreter will call it
  // instead of interpreting the method.
  if ((profiling_info != nullptr) && (profiling_info->GetSavedEntryPoint() != nullptr)) {
    // 非首次调用，更新方法信息
    Runtime::Current()->GetInstrumentation()->UpdateMethodsCode(
        method, profiling_info->GetSavedEntryPoint());
  } else {
    // 首次调用，记录或创建 ProfilingInfo 对象
    AddSamples(thread, method, 1, /* with_backedges */false);
  }
}
```

```cpp
void Jit::AddSamples(Thread* self, ArtMethod* method, uint16_t count, bool with_backedges) {
  if (thread_pool_ == nullptr) {
    // Should only see this when shutting down.
    DCHECK(Runtime::Current()->IsShuttingDown(self));
    return;
  }
  if (method->IsClassInitializer() || !method->IsCompilable()) {
    // We do not want to compile such methods.
    return;
  }
  if (hot_method_threshold_ == 0) {
    // Tests might request JIT on first use (compiled synchronously in the interpreter).
    return;
  }
  DCHECK(thread_pool_ != nullptr);
  DCHECK_GT(warm_method_threshold_, 0);
  DCHECK_GT(hot_method_threshold_, warm_method_threshold_);
  DCHECK_GT(osr_method_threshold_, hot_method_threshold_);
  DCHECK_GE(priority_thread_weight_, 1);
  DCHECK_LE(priority_thread_weight_, hot_method_threshold_);
  // starting_count 为方法执行的次数记录
  int32_t starting_count = method->GetCounter();
  if (Jit::ShouldUsePriorityThreadWeight(self)) {
    // 线程权重计算，主线程权重一般为：hot_method_threshold / 20
    // 也就是说在主线程重复执行某个方法超过 20 次，就会达到JIT阈值
    count *= priority_thread_weight_;
  }
  // new_count 为计算上权重之后，目前方法的总执行次数
  int32_t new_count = starting_count + count;   // int32 here to avoid wrap-around;
  // Note: Native method have no "warm" state or profiling info.
  if (LIKELY(!method->IsNative()) && starting_count < warm_method_threshold_) {
    if ((new_count >= warm_method_threshold_) &&
        (method->GetProfilingInfo(kRuntimePointerSize) == nullptr)) {
      // 方法执行次数达到 warm_method_threshold 
      // 则创建 ProfilingInfo 对象
      bool success = ProfilingInfo::Create(self, method, /* retry_allocation */ false);
      if (success) {
        VLOG(jit) << "Start profiling " << method->PrettyMethod();
      }
      if (thread_pool_ == nullptr) {
        // Calling ProfilingInfo::Create might put us in a suspended state, which could
        // lead to the thread pool being deleted when we are shutting down.
        DCHECK(Runtime::Current()->IsShuttingDown(self));
        return;
      }
      if (!success) {
        // We failed allocating. Instead of doing the collection on the Java thread, we push
        // an allocation to a compiler thread, that will do the collection.
        thread_pool_->AddTask(self, new JitCompileTask(method, JitCompileTask::kAllocateProfile));
      }
    }
    // Avoid jumping more than one state at a time.
    new_count = std::min(new_count, hot_method_threshold_ - 1);
  } else if (use_jit_compilation_) {
    if (starting_count < hot_method_threshold_) {
      if ((new_count >= hot_method_threshold_) &&
          !code_cache_->ContainsPc(method->GetEntryPointFromQuickCompiledCode())) {
        DCHECK(thread_pool_ != nullptr);
        // 方法执行次数达到hot_method_threshold_
        // 触发JIT编译任务
        thread_pool_->AddTask(self, new JitCompileTask(method, JitCompileTask::kCompile));
      }
      // Avoid jumping more than one state at a time.
      new_count = std::min(new_count, osr_method_threshold_ - 1);
    } else if (starting_count < osr_method_threshold_) {
      if (!with_backedges) {
        // If the samples don't contain any back edge, we don't increment the hotness.
        return;
      }
      DCHECK(!method->IsNative());  // No back edges reported for native methods.
      if ((new_count >= osr_method_threshold_) &&  !code_cache_->IsOsrCompiled(method)) {
        DCHECK(thread_pool_ != nullptr);
        thread_pool_->AddTask(self, new JitCompileTask(method, JitCompileTask::kCompileOsr));
      }
    }
  }
  // Update hotness counter
  // 更新方法执行次数信息
  method->SetCounter(new_count);
}
```

[profiling_info.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/jit/profiling_info.cc)

```cpp
bool ProfilingInfo::Create(Thread* self, ArtMethod* method, bool retry_allocation) {
  // 记录方法中虚方法或者接口方法的调用信息
  // 后续的 inline-cache 正是针对这些方法做的缓存
  // Walk over the dex instructions of the method and keep track of
  // instructions we are interested in profiling.
  DCHECK(!method->IsNative());
  std::vector<uint32_t> entries;
  for (const DexInstructionPcPair& inst : method->DexInstructions()) {
    switch (inst->Opcode()) {
      case Instruction::INVOKE_VIRTUAL:
      case Instruction::INVOKE_VIRTUAL_RANGE:
      case Instruction::INVOKE_VIRTUAL_QUICK:
      case Instruction::INVOKE_VIRTUAL_RANGE_QUICK:
      case Instruction::INVOKE_INTERFACE:
      case Instruction::INVOKE_INTERFACE_RANGE:
        entries.push_back(inst.DexPc());
        break;
      default:
        break;
    }
  }
  // We always create a `ProfilingInfo` object, even if there is no instruction we are
  // interested in. The JIT code cache internally uses it.
  // Allocate the `ProfilingInfo` object int the JIT's data space.
  jit::JitCodeCache* code_cache = Runtime::Current()->GetJit()->GetCodeCache();
  return code_cache->AddProfilingInfo(self, method, entries, retry_allocation) != nullptr;
}
```

* 当 ART 虚拟机通过解释器执行代码的时候，每一次执行方法的时候，即会向 __jit__ 分发 MethodEnter 事件；

* __jit__ 接收到该事件之后，会计算并保存方法的总执行次数；

* 如果方法的总执行次数达到 warm_method_threshold 就会创建一个 ProfilingInfo 对象，用以缓存方法分发的信息；

* 如果方法的总执行次数达到 hot_method_threshold，则会触发 JIT 编译任务

##### 缓存方法调用信息

[interpreter_common.h](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/interpreter/interpreter_common.h)

```cpp
// Handles all invoke-XXX/range instructions except for invoke-polymorphic[/range].
// Returns true on success, otherwise throws an exception and returns false.
template<InvokeType type, bool is_range, bool do_access_check>
static inline bool DoInvoke(Thread* self,
                            ShadowFrame& shadow_frame,
                            const Instruction* inst,
                            uint16_t inst_data,
                            JValue* result) {
  // Make sure to check for async exceptions before anything else.
  if (UNLIKELY(self->ObserveAsyncException())) {
    return false;
  }
  const uint32_t method_idx = (is_range) ? inst->VRegB_3rc() : inst->VRegB_35c();
  const uint32_t vregC = (is_range) ? inst->VRegC_3rc() : inst->VRegC_35c();
  ObjPtr<mirror::Object> receiver =
      (type == kStatic) ? nullptr : shadow_frame.GetVRegReference(vregC);
  ArtMethod* sf_method = shadow_frame.GetMethod();
  ArtMethod* const called_method = FindMethodFromCode<type, do_access_check>(
      method_idx, &receiver, sf_method, self);
  // The shadow frame should already be pushed, so we don't need to update it.
  if (UNLIKELY(called_method == nullptr)) {
    CHECK(self->IsExceptionPending());
    result->SetJ(0);
    return false;
  } else if (UNLIKELY(!called_method->IsInvokable())) {
    called_method->ThrowInvocationTimeError();
    result->SetJ(0);
    return false;
  } else {
    jit::Jit* jit = Runtime::Current()->GetJit();
    if (jit != nullptr && (type == kVirtual || type == kInterface)) {
      // 分发虚方法或者接口方法的执行事件
      jit->InvokeVirtualOrInterface(receiver, sf_method, shadow_frame.GetDexPC(), called_method);
    }
    // TODO: Remove the InvokeVirtualOrInterface instrumentation, as it was only used by the JIT.
    if (type == kVirtual || type == kInterface) {
      instrumentation::Instrumentation* instrumentation = Runtime::Current()->GetInstrumentation();
      if (UNLIKELY(instrumentation->HasInvokeVirtualOrInterfaceListeners())) {
        // 分发虚方法或者接口方法的执行事件
        instrumentation->InvokeVirtualOrInterface(
            self, receiver.Ptr(), sf_method, shadow_frame.GetDexPC(), called_method);
      }
    }
    return DoCall<is_range, do_access_check>(called_method, self, shadow_frame, inst, inst_data,
                                             result);
  }
}
```

[jit.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/jit/jit.cc)

```cpp
void Jit::InvokeVirtualOrInterface(ObjPtr<mirror::Object> this_object,
                                   ArtMethod* caller,
                                   uint32_t dex_pc,
                                   ArtMethod* callee ATTRIBUTE_UNUSED) {
  ScopedAssertNoThreadSuspension ants(__FUNCTION__);
  DCHECK(this_object != nullptr);
  ProfilingInfo* info = caller->GetProfilingInfo(kRuntimePointerSize);
  if (info != nullptr) {
    // 更新记录虚方法或接口方法的执行信息
    info->AddInvokeInfo(dex_pc, this_object->GetClass());
  }
}
```

[profiling_info.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/jit/profiling_info.cc)

```cpp
void ProfilingInfo::AddInvokeInfo(uint32_t dex_pc, mirror::Class* cls) {
  InlineCache* cache = GetInlineCache(dex_pc);
  // 将虚方法或接口方法，实际调用的类信息记录在cache中
  // 为什么只需要记录类信息呢？因为在 dex2oat 的时候，通过这个类就可以找到真正调用的方法
  for (size_t i = 0; i < InlineCache::kIndividualCacheSize; ++i) {
    mirror::Class* existing = cache->classes_[i].Read<kWithoutReadBarrier>();
    mirror::Class* marked = ReadBarrier::IsMarked(existing);
    if (marked == cls) {
      // Receiver type is already in the cache, nothing else to do.
      return;
    } else if (marked == nullptr) {
      // Cache entry is empty, try to put `cls` in it.
      // Note: it's ok to spin on 'existing' here: if 'existing' is not null, that means
      // it is a stalled heap address, which will only be cleared during SweepSystemWeaks,
      // *after* this thread hits a suspend point.
      GcRoot<mirror::Class> expected_root(existing);
      GcRoot<mirror::Class> desired_root(cls);
      auto atomic_root = reinterpret_cast<Atomic<GcRoot<mirror::Class>>*>(&cache->classes_[i]);
      if (!atomic_root->CompareAndSetStrongSequentiallyConsistent(expected_root, desired_root)) {
        // Some other thread put a class in the cache, continue iteration starting at this
        // entry in case the entry contains `cls`.
        --i;
      } else {
        // We successfully set `cls`, just return.
        return;
      }
    }
  }
  // Unsuccessfull - cache is full, making it megamorphic. We do not DCHECK it though,
  // as the garbage collector might clear the entries concurrently.
}
```

目标方法内代码指令执行的时候，如果是虚方法或者接口方法的调用，则将该调用信息记录到 InlineCache 中，主要是记录 receiver 的类型，也就是实际调用时派生类的信息。在 AOT 或 JIT 编译的时候，通过该派生类就可以找到真正调用的方法，同时也会根据该派生类信息生成类型检查指令。

##### 保存profile文件

[profile_saver.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/jit/profile_saver.cc)

```cpp
bool ProfileSaver::ProcessProfilingInfo(bool force_save, /*out*/uint16_t* number_of_new_methods) {
    ......
}
```

[profile_compilation_info.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/jit/profile_compilation_info.cc)

```cpp
/**
 * Serialization format:
 * [profile_header, zipped[[profile_line_header1, profile_line_header2...],[profile_line_data1,
 *    profile_line_data2...]]]
 * profile_header:
 *   magic,version,number_of_dex_files,uncompressed_size_of_zipped_data,compressed_data_size
 * profile_line_header:
 *   dex_location,number_of_classes,methods_region_size,dex_location_checksum,num_method_ids
 * profile_line_data:
 *   method_encoding_1,method_encoding_2...,class_id1,class_id2...,startup/post startup bitmap
 * The method_encoding is:
 *    method_id,number_of_inline_caches,inline_cache1,inline_cache2...
 * The inline_cache is:
 *    dex_pc,[M|dex_map_size], dex_profile_index,class_id1,class_id2...,dex_profile_index2,...
 *    dex_map_size is the number of dex_indeces that follows.
 *       Classes are grouped per their dex files and the line
 *       `dex_profile_index,class_id1,class_id2...,dex_profile_index2,...` encodes the
 *       mapping from `dex_profile_index` to the set of classes `class_id1,class_id2...`
 *    M stands for megamorphic or missing types and it's encoded as either
 *    the byte kIsMegamorphicEncoding or kIsMissingTypesEncoding.
 *    When present, there will be no class ids following.
 **/
bool ProfileCompilationInfo::Save(int fd) {
    .......
}
```

profile 文件保存的过程，此处不再深入，主要是保存 dex、method 和 class 的简略信息，AOT 编译时会通过读取该文件获取热代码的信息，感兴趣的同学可以自行阅读相关源码。



#### 编译过程

[inliner.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/compiler/optimizing/inliner.cc)

```cpp
bool HInliner::TryInline(HInvoke* invoke_instruction) {
  if (invoke_instruction->IsInvokeUnresolved() ||
      invoke_instruction->IsInvokePolymorphic()) {
    return false;  // Don't bother to move further if we know the method is unresolved or an
                   // invoke-polymorphic.
  }
  ScopedObjectAccess soa(Thread::Current());
  uint32_t method_index = invoke_instruction->GetDexMethodIndex();
  const DexFile& caller_dex_file = *caller_compilation_unit_.GetDexFile();
  LOG_TRY() << caller_dex_file.PrettyMethod(method_index);
  ArtMethod* resolved_method = invoke_instruction->GetResolvedMethod();
  if (resolved_method == nullptr) {
    DCHECK(invoke_instruction->IsInvokeStaticOrDirect());
    DCHECK(invoke_instruction->AsInvokeStaticOrDirect()->IsStringInit());
    LOG_FAIL_NO_STAT() << "Not inlining a String.<init> method";
    return false;
  }
  ArtMethod* actual_method = nullptr;
  // 尝试确认是否是编译时可以确定的方法
  if (invoke_instruction->IsInvokeStaticOrDirect()) {
    actual_method = resolved_method;
  } else {
    // Check if we can statically find the method.
    actual_method = FindVirtualOrInterfaceTarget(invoke_instruction, resolved_method);
  }
  bool cha_devirtualize = false;
  if (actual_method == nullptr) {
    ArtMethod* method = TryCHADevirtualization(resolved_method);
    if (method != nullptr) {
      cha_devirtualize = true;
      actual_method = method;
      LOG_NOTE() << "Try CHA-based inlining of " << actual_method->PrettyMethod();
    }
  }
    
  // 编译时可以确定的方法，比如 final 方法，直接尝试 inline-replace
  if (actual_method != nullptr) {
    // Single target.
    bool result = TryInlineAndReplace(invoke_instruction,
                                      actual_method,
                                      ReferenceTypeInfo::CreateInvalid(),
                                      /* do_rtp */ true,
                                      cha_devirtualize);
    if (result) {
      // Successfully inlined.
      if (!invoke_instruction->IsInvokeStaticOrDirect()) {
        if (cha_devirtualize) {
          // Add dependency due to devirtualization. We've assumed resolved_method
          // has single implementation.
          outermost_graph_->AddCHASingleImplementationDependency(resolved_method);
          MaybeRecordStat(stats_, MethodCompilationStat::kCHAInline);
        } else {
          MaybeRecordStat(stats_, MethodCompilationStat::kInlinedInvokeVirtualOrInterface);
        }
      }
    } else if (!cha_devirtualize && AlwaysThrows(compiler_driver_, actual_method)) {
      // Set always throws property for non-inlined method call with single target
      // (unless it was obtained through CHA, because that would imply we have
      // to add the CHA dependency, which seems not worth it).
      invoke_instruction->SetAlwaysThrows(true);
    }
    return result;
  }
  DCHECK(!invoke_instruction->IsInvokeStaticOrDirect());
  
  // 编译时无法确定的方法调用，尝试 inline-cache
  // Try using inline caches.
  return TryInlineFromInlineCache(caller_dex_file, invoke_instruction, resolved_method);
}
```

```cpp
bool HInliner::TryInlineFromInlineCache(const DexFile& caller_dex_file,
                                        HInvoke* invoke_instruction,
                                        ArtMethod* resolved_method)
    REQUIRES_SHARED(Locks::mutator_lock_) {
  if (Runtime::Current()->IsAotCompiler() && !kUseAOTInlineCaches) {
    return false;
  }
  StackHandleScope<1> hs(Thread::Current());
  Handle<mirror::ObjectArray<mirror::Class>> inline_cache;
  InlineCacheType inline_cache_type = Runtime::Current()->IsAotCompiler()
      ? GetInlineCacheAOT(caller_dex_file, invoke_instruction, &hs, &inline_cache)
      : GetInlineCacheJIT(invoke_instruction, &hs, &inline_cache);
  switch (inline_cache_type) {
    case kInlineCacheNoData: {
      // 未加载到相关数据或加载失败
      ......
      return false;
    }
    case kInlineCacheUninitialized: {
      // Uninitialized 未统计到任何子类的方法调用
      ......
      return false;
    }
    case kInlineCacheMonomorphic: {
      // Monomorphic 即目前仅统计到 1 个子类方法调用
      MaybeRecordStat(stats_, MethodCompilationStat::kMonomorphicCall);
      if (UseOnlyPolymorphicInliningWithNoDeopt()) {
        return TryInlinePolymorphicCall(invoke_instruction, resolved_method, inline_cache);
      } else {
        return TryInlineMonomorphicCall(invoke_instruction, resolved_method, inline_cache);
      }
    }
    case kInlineCachePolymorphic: {
      // Polymorphic 即目前仅统计到 2-4 个子类方法调用
      MaybeRecordStat(stats_, MethodCompilationStat::kPolymorphicCall);
      return TryInlinePolymorphicCall(invoke_instruction, resolved_method, inline_cache);
    }
    case kInlineCacheMegamorphic: {
      // Megamorphic 即目前以统计到 5 个及以上的子类方法调用
      // 见 https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/jit/profiling_info.h
      // kIndividualCacheSize = 5;
      ......
      return false;
    }
    case kInlineCacheMissingTypes: {
      // MissingTypes 即部分类加载失败
      ......
      return false;
    }
  }
  UNREACHABLE();
}
```

```cpp
bool HInliner::TryInlineMonomorphicCall(HInvoke* invoke_instruction,
                                        ArtMethod* resolved_method,
                                        Handle<mirror::ObjectArray<mirror::Class>> classes) {
  DCHECK(invoke_instruction->IsInvokeVirtual() || invoke_instruction->IsInvokeInterface())
      << invoke_instruction->DebugName();
    
  // 解析class_index
  dex::TypeIndex class_index = FindClassIndexIn(
      GetMonomorphicType(classes), caller_compilation_unit_);
  if (!class_index.IsValid()) {
    LOG_FAIL(stats_, MethodCompilationStat::kNotInlinedDexCache)
        << "Call to " << ArtMethod::PrettyMethod(resolved_method)
        << " from inline cache is not inlined because its class is not"
        << " accessible to the caller";
    return false;
  }
  ClassLinker* class_linker = caller_compilation_unit_.GetClassLinker();
  PointerSize pointer_size = class_linker->GetImagePointerSize();
  Handle<mirror::Class> monomorphic_type = handles_->NewHandle(GetMonomorphicType(classes));
    
  // 解析实际调用的方法 resolved_method
  resolved_method = ResolveMethodFromInlineCache(
      monomorphic_type, resolved_method, invoke_instruction, pointer_size);
  LOG_NOTE() << "Try inline monomorphic call to " << resolved_method->PrettyMethod();
  if (resolved_method == nullptr) {
    // Bogus AOT profile, bail.
    DCHECK(Runtime::Current()->IsAotCompiler());
    return false;
  }
  HInstruction* receiver = invoke_instruction->InputAt(0);
  HInstruction* cursor = invoke_instruction->GetPrevious();
  HBasicBlock* bb_cursor = invoke_instruction->GetBlock();
    
  // 修改和替换指令
  if (!TryInlineAndReplace(invoke_instruction,
                           resolved_method,
                           ReferenceTypeInfo::Create(monomorphic_type, /* is_exact */ true),
                           /* do_rtp */ false,
                           /* cha_devirtualize */ false)) {
    return false;
  }
  
  // 添加class检查分支
  // We successfully inlined, now add a guard.
  AddTypeGuard(receiver,
               cursor,
               bb_cursor,
               class_index,
               monomorphic_type,
               invoke_instruction,
               /* with_deoptimization */ true);
  // Run type propagation to get the guard typed, and eventually propagate the
  // type of the receiver.
  ReferenceTypePropagation rtp_fixup(graph_,
                                     outer_compilation_unit_.GetClassLoader(),
                                     outer_compilation_unit_.GetDexCache(),
                                     handles_,
                                     /* is_first_run */ false);
  rtp_fixup.Run();
  MaybeRecordStat(stats_, MethodCompilationStat::kInlinedMonomorphicCall);
  return true;
}
```

```cpp
HInstruction* HInliner::AddTypeGuard(HInstruction* receiver,
                                     HInstruction* cursor,
                                     HBasicBlock* bb_cursor,
                                     dex::TypeIndex class_index,
                                     Handle<mirror::Class> klass,
                                     HInstruction* invoke_instruction,
                                     bool with_deoptimization) {
  ClassLinker* class_linker = caller_compilation_unit_.GetClassLinker();
  // 添加class解析指令，即 receiver.getClass() f方法 
  HInstanceFieldGet* receiver_class = BuildGetReceiverClass(
      class_linker, receiver, invoke_instruction->GetDexPc());
  if (cursor != nullptr) {
    bb_cursor->InsertInstructionAfter(receiver_class, cursor);
  } else {
    bb_cursor->InsertInstructionBefore(receiver_class, bb_cursor->GetFirstInstruction());
  }
  const DexFile& caller_dex_file = *caller_compilation_unit_.GetDexFile();
  bool is_referrer;
  ArtMethod* outermost_art_method = outermost_graph_->GetArtMethod();
  if (outermost_art_method == nullptr) {
    DCHECK(Runtime::Current()->IsAotCompiler());
    // We are in AOT mode and we don't have an ART method to determine
    // if the inlined method belongs to the referrer. Assume it doesn't.
    is_referrer = false;
  } else {
    is_referrer = klass.Get() == outermost_art_method->GetDeclaringClass();
  }
  
  // 添加class加载指令
  // Note that we will just compare the classes, so we don't need Java semantics access checks.
  // Note that the type index and the dex file are relative to the method this type guard is
  // inlined into.
  HLoadClass* load_class = new (graph_->GetAllocator()) HLoadClass(graph_->GetCurrentMethod(),
                                                                   class_index,
                                                                   caller_dex_file,
                                                                   klass,
                                                                   is_referrer,
                                                                   invoke_instruction->GetDexPc(),
                                                                   /* needs_access_check */ false);
  // LoadKind 应为 KBssEntry
  HLoadClass::LoadKind kind = HSharpening::ComputeLoadClassKind(
      load_class, codegen_, compiler_driver_, caller_compilation_unit_);
  DCHECK(kind != HLoadClass::LoadKind::kInvalid)
      << "We should always be able to reference a class for inline caches";
  // Load kind must be set before inserting the instruction into the graph.
  load_class->SetLoadKind(kind);
  bb_cursor->InsertInstructionAfter(load_class, receiver_class);
  // In AOT mode, we will most likely load the class from BSS, which will involve a call
  // to the runtime. In this case, the load instruction will need an environment so copy
  // it from the invoke instruction.
  if (load_class->NeedsEnvironment()) {
    DCHECK(Runtime::Current()->IsAotCompiler());
    load_class->CopyEnvironmentFrom(invoke_instruction->GetEnvironment());
  }
    
  // 添加class比较检查指令
  HNotEqual* compare = new (graph_->GetAllocator()) HNotEqual(load_class, receiver_class);
  bb_cursor->InsertInstructionAfter(compare, load_class);
  
  if (with_deoptimization) {
    // 添加Deoptimize指令，当前置的内联分支未匹配到的时候，则会调用到该指令，作为最后的容错处理
    HDeoptimize* deoptimize = new (graph_->GetAllocator()) HDeoptimize(
        graph_->GetAllocator(),
        compare,
        receiver,
        Runtime::Current()->IsAotCompiler()
            ? DeoptimizationKind::kAotInlineCache
            : DeoptimizationKind::kJitInlineCache,
        invoke_instruction->GetDexPc());
    bb_cursor->InsertInstructionAfter(deoptimize, compare);
    deoptimize->CopyEnvironmentFrom(invoke_instruction->GetEnvironment());
    DCHECK_EQ(invoke_instruction->InputAt(0), receiver);
    receiver->ReplaceUsesDominatedBy(deoptimize, deoptimize);
    deoptimize->SetReferenceTypeInfo(receiver->GetReferenceTypeInfo());
  }
  return compare;
}
```

[nodes.h](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/compiler/optimizing/nodes.h)

```cpp
/**
 * Instruction to load a Class object.
 */
class HLoadClass FINAL : public HInstruction {
    ......
}
```

[code_generator_x86_64.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/compiler/optimizing/code_generator_x86_64.cc)

```cpp
void InstructionCodeGeneratorX86_64::VisitLoadClass(HLoadClass* cls) NO_THREAD_SAFETY_ANALYSIS {
  HLoadClass::LoadKind load_kind = cls->GetLoadKind();
  if (load_kind == HLoadClass::LoadKind::kRuntimeCall) {
    // 调用 kQuickInitializeType 方法
    codegen_->GenerateLoadClassRuntimeCall(cls);
    return;
  }
  
  DCHECK(!cls->NeedsAccessCheck());
  LocationSummary* locations = cls->GetLocations();
  Location out_loc = locations->Out();
  CpuRegister out = out_loc.AsRegister<CpuRegister>();
  const ReadBarrierOption read_barrier_option = cls->IsInBootImage()
      ? kWithoutReadBarrier
      : kCompilerReadBarrierOption;
  bool generate_null_check = false;
  switch (load_kind) {
    case HLoadClass::LoadKind::kReferrersClass: {
      ......
      break;
    }
    case HLoadClass::LoadKind::kBootImageLinkTimePcRelative:
      ......
      break;
    case HLoadClass::LoadKind::kBootImageAddress: {
      ......
      break;
    }
    case HLoadClass::LoadKind::kBootImageClassTable: {
      ......
      break;
    }
    case HLoadClass::LoadKind::kBssEntry: {
      // 先尝试从 .bss 中加载，对于 AOT 或 JIT 内联场景下的 HLoadClass 指令，应为此项
      Address address = Address::Absolute(CodeGeneratorX86_64::kDummy32BitOffset,
                                          /* no_rip */ false);
      Label* fixup_label = codegen_->NewTypeBssEntryPatch(cls);
      // /* GcRoot<mirror::Class> */ out = *address  /* PC-relative */
      GenerateGcRootFieldLoad(cls, out_loc, address, fixup_label, read_barrier_option);
      generate_null_check = true;
      break;
    }
    case HLoadClass::LoadKind::kJitTableAddress: {
      ......
      break;
    }
    default:
      LOG(FATAL) << "Unexpected load kind: " << cls->GetLoadKind();
      UNREACHABLE();
  }
  
  if (generate_null_check || cls->MustGenerateClinitCheck()) {
    DCHECK(cls->CanCallRuntime());
    // 先尝试从 .bss 中加载，若为 null 则调用 kQuickInitializeType 方法
    SlowPathCode* slow_path = new (codegen_->GetScopedAllocator()) LoadClassSlowPathX86_64(
        cls, cls, cls->GetDexPc(), cls->MustGenerateClinitCheck());
    codegen_->AddSlowPath(slow_path);
    if (generate_null_check) {
      __ testl(out, out);
      __ j(kEqual, slow_path->GetEntryLabel());
    }
    if (cls->MustGenerateClinitCheck()) {
      GenerateClassInitializationCheck(slow_path, out);
    } else {
      __ Bind(slow_path->GetExitLabel());
    }
  }
}
```

[code_generator.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/compiler/optimizing/code_generator.cc)

```cpp
void CodeGenerator::GenerateLoadClassRuntimeCall(HLoadClass* cls) {
  DCHECK_EQ(cls->GetLoadKind(), HLoadClass::LoadKind::kRuntimeCall);
  LocationSummary* locations = cls->GetLocations();
  MoveConstant(locations->GetTemp(0), cls->GetTypeIndex().index_);
  if (cls->NeedsAccessCheck()) {
    CheckEntrypointTypes<kQuickInitializeTypeAndVerifyAccess, void*, uint32_t>();
    InvokeRuntime(kQuickInitializeTypeAndVerifyAccess, cls, cls->GetDexPc());
  } else if (cls->MustGenerateClinitCheck()) {
    CheckEntrypointTypes<kQuickInitializeStaticStorage, void*, uint32_t>();
    InvokeRuntime(kQuickInitializeStaticStorage, cls, cls->GetDexPc());
  } else {
    // 类加载指令最后编译的代码会编译成调用 art_quick_initialize_type 即对应于 artInitializeTypeFromCode 方法
    CheckEntrypointTypes<kQuickInitializeType, void*, uint32_t>();
    InvokeRuntime(kQuickInitializeType, cls, cls->GetDexPc());
  }
}
```

从上面的源码可以看到，Android ART 虚拟机在编译时存在两种内联方式：

* __inline__：即将 Callee 方法(inlined-method)内的代码内联至 Caller 方法(outer-method)中，替换掉原来对 Callee 方法的调用指令

* __inline-cache__：即在分析热代码方法分发信息的基础上，通过添加类型检查和分支指令，直接将该方法的代码内联，从而省略方法查找和调用的过程

编译之后的指令，可以参考官方测试用例 [Main.java](https://android.googlesource.com/platform/art/+/refs/heads/master/test/638-checker-inline-caches/src/Main.java)

```java
  /// CHECK-START: int Main.inlineMonomorphicSubA(Super) inliner (before)
  /// CHECK:       InvokeVirtual method_name:Super.getValue
  /// CHECK-START: int Main.inlineMonomorphicSubA(Super) inliner (after)
  /// CHECK:  <<SubARet:i\d+>>      IntConstant 42
  /// CHECK:  <<Obj:l\d+>>          NullCheck
  /// CHECK:  <<ObjClass:l\d+>>     InstanceFieldGet [<<Obj>>] field_name:java.lang.Object.shadow$_klass_
  /// CHECK:  <<InlineClass:l\d+>>  LoadClass class_name:SubA
  /// CHECK:  <<Test:z\d+>>         NotEqual [<<InlineClass>>,<<ObjClass>>]
  /// CHECK:  <<DefaultRet:i\d+>>   InvokeVirtual [<<Obj>>] method_name:Super.getValue
  /// CHECK:  <<Ret:i\d+>>          Phi [<<SubARet>>,<<DefaultRet>>]
  /// CHECK:                        Return [<<Ret>>]
  /// CHECK-NOT:                    Deoptimize
  public static int inlineMonomorphicSubA(Super a) {
    return a.getValue();
  }
```



#### 执行过程

[quick_dexcache_entrypoints.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/entrypoints/quick/quick_dexcache_entrypoints.cc)

```cpp
extern "C" mirror::Class* artInitializeTypeFromCode(uint32_t type_idx, Thread* self)
    REQUIRES_SHARED(Locks::mutator_lock_) {
  // Called when the .bss slot was empty or for main-path runtime call.
  ScopedQuickEntrypointChecks sqec(self);
  // 由于内联之后，实际的方法调用可能并不存在，存在的仅是方法内的代码
  // 因此需要通过该方法来拿到内联之前的方法信息
  auto caller_and_outer = GetCalleeSaveMethodCallerAndOuterMethod(
      self, CalleeSaveType::kSaveEverythingForClinit);
  ArtMethod* caller = caller_and_outer.caller;
  // 触发类加载过程
  ObjPtr<mirror::Class> result = ResolveVerifyAndClinit(dex::TypeIndex(type_idx),
                                                        caller,
                                                        self,
                                                        /* can_run_clinit */ false,
                                                        /* verify_access */ false);
  if (LIKELY(result != nullptr) && CanReferenceBss(caller_and_outer.outer_method, caller)) {
    // 将 result 缓存到 .bss 中
    StoreTypeInBss(caller_and_outer.outer_method, dex::TypeIndex(type_idx), result);
  }
  return result.Ptr();
}

```

[entrypoint_utils.cc)](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/entrypoints/entrypoint_utils.cc)

```cpp
CallerAndOuterMethod GetCalleeSaveMethodCallerAndOuterMethod(Thread* self, CalleeSaveType type) {
  CallerAndOuterMethod result;
  ScopedAssertNoThreadSuspension ants(__FUNCTION__);
  ArtMethod** sp = self->GetManagedStack()->GetTopQuickFrameKnownNotTagged();
  // 解析 outer-method
  auto outer_caller_and_pc = DoGetCalleeSaveMethodOuterCallerAndPc(sp, type);
  result.outer_method = outer_caller_and_pc.first;
  uintptr_t caller_pc = outer_caller_and_pc.second;
  // 解析 inlined-method
  result.caller =
      DoGetCalleeSaveMethodCaller(result.outer_method, caller_pc, /* do_caller_check */ true);
  return result;
}
```

```cpp
static inline ArtMethod* DoGetCalleeSaveMethodCaller(ArtMethod* outer_method,
                                                     uintptr_t caller_pc,
                                                     bool do_caller_check)
    REQUIRES_SHARED(Locks::mutator_lock_) {
  ArtMethod* caller = outer_method;
  if (LIKELY(caller_pc != reinterpret_cast<uintptr_t>(GetQuickInstrumentationExitPc()))) {
    if (outer_method != nullptr) {
      const OatQuickMethodHeader* current_code = outer_method->GetOatQuickMethodHeader(caller_pc);
      DCHECK(current_code != nullptr);
      DCHECK(current_code->IsOptimized());
      uintptr_t native_pc_offset = current_code->NativeQuickPcOffset(caller_pc);
      CodeInfo code_info = current_code->GetOptimizedCodeInfo();
      MethodInfo method_info = current_code->GetOptimizedMethodInfo();
      CodeInfoEncoding encoding = code_info.ExtractEncoding();
      StackMap stack_map = code_info.GetStackMapForNativePcOffset(native_pc_offset, encoding);
      DCHECK(stack_map.IsValid());
      if (stack_map.HasInlineInfo(encoding.stack_map.encoding)) {
        InlineInfo inline_info = code_info.GetInlineInfoOf(stack_map, encoding);
        // 从 inline_info 中解析 inlined-method，此时该方法的代码已经被内联，且也没有此方法的栈帧
        caller = GetResolvedMethod(outer_method,
                                   method_info,
                                   inline_info,
                                   encoding.inline_info.encoding,
                                   inline_info.GetDepth(encoding.inline_info.encoding) - 1);
      }
    }
    if (kIsDebugBuild && do_caller_check) {
      // Note that do_caller_check is optional, as this method can be called by
      // stubs, and tests without a proper call stack.
      NthCallerVisitor visitor(Thread::Current(), 1, true);
      visitor.WalkStack();
      CHECK_EQ(caller, visitor.caller);
    }
  } else {
    // We're instrumenting, just use the StackVisitor which knows how to
    // handle instrumented frames.
    NthCallerVisitor visitor(Thread::Current(), 1, true);
    visitor.WalkStack();
    caller = visitor.caller;
  }
  return caller;
}
```

[entrypoint_utils-inl.h](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/entrypoints/entrypoint_utils-inl.h)

```cpp
inline ArtMethod* GetResolvedMethod(ArtMethod* outer_method,
                                    const MethodInfo& method_info,
                                    const InlineInfo& inline_info,
                                    const InlineInfoEncoding& encoding,
                                    uint8_t inlining_depth)
    REQUIRES_SHARED(Locks::mutator_lock_) {
  DCHECK(!outer_method->IsObsolete());
  // This method is being used by artQuickResolutionTrampoline, before it sets up
  // the passed parameters in a GC friendly way. Therefore we must never be
  // suspended while executing it.
  ScopedAssertNoThreadSuspension sants(__FUNCTION__);
  if (inline_info.EncodesArtMethodAtDepth(encoding, inlining_depth)) {
    return inline_info.GetArtMethodAtDepth(encoding, inlining_depth);
  } 
  uint32_t method_index = inline_info.GetMethodIndexAtDepth(encoding, method_info, inlining_depth);
  
  // 特殊处理 String.charAt() 方法
  if (inline_info.GetDexPcAtDepth(encoding, inlining_depth) == static_cast<uint32_t>(-1)) {
    // "charAt" special case. It is the only non-leaf method we inline across dex files.
    ArtMethod* inlined_method = jni::DecodeArtMethod(WellKnownClasses::java_lang_String_charAt);
    DCHECK_EQ(inlined_method->GetDexMethodIndex(), method_index);
    return inlined_method;
  }
  
  // 遍历解析 inlined-method
  // Find which method did the call in the inlining hierarchy.
  ClassLinker* class_linker = Runtime::Current()->GetClassLinker();
  ArtMethod* method = outer_method;
  for (uint32_t depth = 0, end = inlining_depth + 1u; depth != end; ++depth) {
    DCHECK(!inline_info.EncodesArtMethodAtDepth(encoding, depth));
    DCHECK_NE(inline_info.GetDexPcAtDepth(encoding, depth), static_cast<uint32_t>(-1));
    method_index = inline_info.GetMethodIndexAtDepth(encoding, method_info, depth);
    ArtMethod* inlined_method = class_linker->LookupResolvedMethod(method_index,
                                                                   method->GetDexCache(),
                                                                   method->GetClassLoader());
    if (UNLIKELY(inlined_method == nullptr)) {
      LOG(FATAL) << "Could not find an inlined method from an .oat file: "
                 << method->GetDexFile()->PrettyMethod(method_index) << " . "
                 << "This must be due to duplicate classes or playing wrongly with class loaders";
      UNREACHABLE();
    }
    DCHECK(!inlined_method->IsRuntimeMethod());
    
    // 检查 outer_method 和 inlined_method 是否在同一个 dex 中
    if (UNLIKELY(inlined_method->GetDexFile() != method->GetDexFile())) {
      // TODO: We could permit inlining within a multi-dex oat file and the boot image,
      // even going back from boot image methods to the same oat file. However, this is
      // not currently implemented in the compiler. Therefore crossing dex file boundary
      // indicates that the inlined definition is not the same as the one used at runtime.
      LOG(FATAL) << "Inlined method resolution crossed dex file boundary: from "
                 << method->PrettyMethod()
                 << " in " << method->GetDexFile()->GetLocation() << "/"
                 << static_cast<const void*>(method->GetDexFile())
                 << " to " << inlined_method->PrettyMethod()
                 << " in " << inlined_method->GetDexFile()->GetLocation() << "/"
                 << static_cast<const void*>(inlined_method->GetDexFile()) << ". "
                 << "This must be due to duplicate classes or playing wrongly with class loaders";
      UNREACHABLE();
    }
    method = inlined_method;
  }
  return method;
}
```
实际上，__HLoadClass__ 指令最后会编译并调用 art_quick_initialize_type 方法，这个方法就是 __artInitializeTypeFromCode(...)__ 方法，artInitializeTypeFromCode(...) 方法则会触发类加载及初始化逻辑。

同时，我们也看到由于内联之后，可能调用 Caller 方法(inlined_method)的指令已经被 Caller 方法(inlined_method)中的指令所替代，因此需要调用 GetCalleeSaveMethodCallerAndOuterMethod 方法，从方法栈帧和代码信息中解析出 Caller 方法(inlined_method)的信息。

也正是在这个过程中，存在一个检查 inlined_method 和 outer_method 是否在同一个 dex 中的逻辑，如果不在同一个dex中，则输出 “ __Inlined method resolution crossed dex file boundary__ ” 信息。



