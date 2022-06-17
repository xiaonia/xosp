
## Android ART 解释执行过程源码探析

[TOC] 

---

### 一、相关源码

#### 1.1 链接

- [quick\_trampoline\_entrypoints.cc](https://android.googlesource.com/platform/art/+/master/runtime/entrypoints/quick/quick_trampoline_entrypoints.cc)

- [interpreter.cc](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter.cc)

- [dex\_instruction\_list.h](https://android.googlesource.com/platform/art/+/master/libdexfile/dex/dex_instruction_list.h)

- [interpreter\_switch\_impl-inl.h](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter_switch_impl-inl.h)

- [interpreter\_common.h](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter_common.h)

- [interpreter\_common.cc](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter_common.cc)

- [common\_dex\_operations.h](https://android.googlesource.com/platform/art/+/master/runtime/common_dex_operations.h)

- [interpreter.cc](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter.cc)

- [interpreter\_intrinsics.cc](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter_intrinsics.cc)

- [intrinsics\_enum.h](https://android.googlesource.com/platform/art/+/master/runtime/intrinsics_enum.h)

- [intrinsics\_list.h](https://android.googlesource.com/platform/art/+/master/runtime/intrinsics_list.h)

--

### 二、解释执行过程

#### 2.1 artQuickToInterpreterBridge

- **artQuickToInterpreterBridge** 是**编译执行**跳转到**解释执行**的入口函数，这个函数定义在 
[quick\_trampoline\_entrypoints.cc](https://android.googlesource.com/platform/art/+/master/runtime/entrypoints/quick/quick_trampoline_entrypoints.cc) :

```cpp
extern "C" uint64_t artQuickToInterpreterBridge(ArtMethod* method, Thread* self, ArtMethod** sp)
  ......
    
  // >>> 解析 Method 和 CodeItem
  ArtMethod* non_proxy_method = method->GetInterfaceMethodIfProxy(kRuntimePointerSize);
  DCHECK(non_proxy_method->GetCodeItem() != nullptr) << method->PrettyMethod();
  CodeItemDataAccessor accessor(non_proxy_method->DexInstructionData());
  const char* shorty = non_proxy_method->GetShorty(&shorty_len);
  
  JValue result;
  bool force_frame_pop = false;
  if (UNLIKELY(deopt_frame != nullptr)) {
    HandleDeoptimization(&result, method, deopt_frame, &fragment);
  } else {
    ......
    // Push a transition back into managed code onto the linked list in thread.
    self->PushManagedStackFragment(&fragment);
    self->PushShadowFrame(shadow_frame);
    self->EndAssertNoThreadSuspension(old_cause);
    if (NeedsClinitCheckBeforeCall(method)) {
      ObjPtr<mirror::Class> declaring_class = method->GetDeclaringClass();
      if (UNLIKELY(!declaring_class->IsVisiblyInitialized())) {
        // Ensure static method's class is initialized.
        StackHandleScope<1> hs(self);
        
        // >>> 类初始化
        Handle<mirror::Class> h_class(hs.NewHandle(declaring_class));
        if (!Runtime::Current()->GetClassLinker()->EnsureInitialized(self, h_class, true, true)) {
          DCHECK(Thread::Current()->IsExceptionPending()) << method->PrettyMethod();
          self->PopManagedStackFragment(fragment);
          return 0;
        }
      }
    }
    
    // >>> 调用 EnterInterpreterFromEntryPoint 方法
    result = interpreter::EnterInterpreterFromEntryPoint(self, accessor, shadow_frame);
    force_frame_pop = shadow_frame->GetForcePopFrame();
  }
  
  // Pop transition.
  self->PopManagedStackFragment(fragment);
  
  // >>> Deoptimization
  // Check if caller needs to be deoptimized for instrumentation reasons.
  ......
  
  // No need to restore the args since the method has already been run by the interpreter.
  return result.GetJ();
}
```

#### 2.2 EnterInterpreterFromEntryPoint

- [interpreter.cc](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter.cc)

```cpp
JValue EnterInterpreterFromEntryPoint(Thread* self, const CodeItemDataAccessor& accessor,
                                      ShadowFrame* shadow_frame) {
  ......
  jit::Jit* jit = Runtime::Current()->GetJit();
  if (jit != nullptr) {
    // >>> 通知 JIT 从编译执行跳转到解释执行
    jit->NotifyCompiledCodeToInterpreterTransition(self, shadow_frame->GetMethod());
  }
  
  // 调用 Execute 方法
  return Execute(self, accessor, *shadow_frame, JValue());
}
```


#### 2.3 ArtInterpreterToInterpreterBridge

- **ArtInterpreterToInterpreterBridge** 是**解释执行**跳转到**解释执行**的入口函数，这个函数定义在
[interpreter.cc](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter.cc):

```cpp
void ArtInterpreterToInterpreterBridge(Thread* self,
                                       const CodeItemDataAccessor& accessor,
                                       ShadowFrame* shadow_frame,
                                       JValue* result) {
  ......
  self->PushShadowFrame(shadow_frame);
  ArtMethod* method = shadow_frame->GetMethod();
  
  // >>> 如果是 static 方法调用，则需要确保相关的类已经初始化
  // Ensure static methods are initialized.
  const bool is_static = method->IsStatic();
  if (is_static) {
    ObjPtr<mirror::Class> declaring_class = method->GetDeclaringClass();
    if (UNLIKELY(!declaring_class->IsVisiblyInitialized())) {
      StackHandleScope<1> hs(self);
      Handle<mirror::Class> h_class(hs.NewHandle(declaring_class));
      if (UNLIKELY(!Runtime::Current()->GetClassLinker()->EnsureInitialized(
                        self, h_class, /*can_init_fields=*/ true, /*can_init_parents=*/ true))) {
        DCHECK(self->IsExceptionPending());
        self->PopShadowFrame();
        return;
      }
      DCHECK(h_class->IsInitializing());
    }
  }
  
  if (LIKELY(!shadow_frame->GetMethod()->IsNative())) {
    // >>> 非 Native 方法，调用 Execute 方法
    result->SetJ(Execute(self, accessor, *shadow_frame, JValue()).GetJ());
  } else {
    // >>> Native 方法，调用 UnstartedRuntime::Jni 方法
    // We don't expect to be asked to interpret native code (which is entered via a JNI compiler
    // generated stub) except during testing and image writing.
    CHECK(!Runtime::Current()->IsStarted());
    ObjPtr<mirror::Object> receiver = is_static ? nullptr : shadow_frame->GetVRegReference(0);
    uint32_t* args = shadow_frame->GetVRegArgs(is_static ? 0 : 1);
    UnstartedRuntime::Jni(self, shadow_frame->GetMethod(), receiver.Ptr(), args, result);
  }
  self->PopShadowFrame();
}
```

#### 2.4 Execute

- 事实上，无论从哪个入口跳转到解释器执行，最后都是调用 **Execute** 方法，这个方法定义在 
[interpreter.cc](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter.cc) ：

```cpp
static inline JValue Execute(
    Thread* self,
    const CodeItemDataAccessor& accessor,
    ShadowFrame& shadow_frame,
    JValue result_register,
    bool stay_in_interpreter = false,
    bool from_deoptimize = false) REQUIRES_SHARED(Locks::mutator_lock_) {
  ......  
  if (LIKELY(!from_deoptimize)) {  // Entering the method, but not via deoptimization.
    ......
    ArtMethod *method = shadow_frame.GetMethod();
    
    // >>> 检查是否可以执行 JIT 代码
    // If we can continue in JIT and have JITed code available execute JITed code.
    if (!stay_in_interpreter && !self->IsForceInterpreter() && !shadow_frame.GetForcePopFrame()) {
      jit::Jit* jit = Runtime::Current()->GetJit();
      if (jit != nullptr) {
        // >>> 通知 JIT 方法执行事件
        jit->MethodEntered(self, shadow_frame.GetMethod());
        if (jit->CanInvokeCompiledCode(method)) {
          JValue result;
          // Pop the shadow frame before calling into compiled code.
          self->PopShadowFrame();
          // Calculate the offset of the first input reg. The input registers are in the high regs.
          // It's ok to access the code item here since JIT code will have been touched by the
          // interpreter and compiler already.
          uint16_t arg_offset = accessor.RegistersSize() - accessor.InsSize();
          // >>> 执行 JIT 编译的代码
          ArtInterpreterToCompiledCodeBridge(self, nullptr, &shadow_frame, arg_offset, &result);
          // Push the shadow frame back as the caller will expect it.
          self->PushShadowFrame(&shadow_frame);
          return result;
        }
      }
    }
    
    instrumentation::Instrumentation* instrumentation = Runtime::Current()->GetInstrumentation();
    if (UNLIKELY(instrumentation->HasMethodEntryListeners() || shadow_frame.GetForcePopFrame())) {
      // 通知 Instrumentation MethodEnter 事件
      instrumentation->MethodEnterEvent(self, method);
      ......
    }
  }
  
  // >>> 调用 ExecuteSwitch 解释执行DEX指令
  ArtMethod* method = shadow_frame.GetMethod();  
  return ExecuteSwitch(
      self, accessor, shadow_frame, result_register, /*interpret_one_instruction=*/ false);
}
```

#### 2.5 ExecuteSwitch

- **ExecuteSwitch** 方法主要根据系统配置和方法配置，分别选择执行不同的模版方法，这个方法定义在
[interpreter.cc](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter.cc) :

```cpp
static JValue ExecuteSwitch(Thread* self,
                            const CodeItemDataAccessor& accessor,
                            ShadowFrame& shadow_frame,
                            JValue result_register,
                            bool interpret_one_instruction) REQUIRES_SHARED(Locks::mutator_lock_) {
  if (Runtime::Current()->IsActiveTransaction()) {
    if (shadow_frame.GetMethod()->SkipAccessChecks()) {
      return ExecuteSwitchImpl<false, true>(
          self, accessor, shadow_frame, result_register, interpret_one_instruction);
    } else {
      return ExecuteSwitchImpl<true, true>(
          self, accessor, shadow_frame, result_register, interpret_one_instruction);
    }
  } else {
    if (shadow_frame.GetMethod()->SkipAccessChecks()) {
      return ExecuteSwitchImpl<false, false>(
          self, accessor, shadow_frame, result_register, interpret_one_instruction);
    } else {
      return ExecuteSwitchImpl<true, false>(
          self, accessor, shadow_frame, result_register, interpret_one_instruction);
    }
  }
}
```

#### 2.6 ExecuteSwitchImpl

- **ExecuteSwitchImpl** 主要是对 **ExecuteSwitchImplCpp** 做了一层包装，实际执行DEX指令的是 **ExecuteSwitchImplCpp**。这个方法定义在
[interpreter\_switch\_impl.h](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter_switch_impl.h) : 

```cpp
// Wrapper around the switch interpreter which ensures we can unwind through it.
template<bool do_access_check, bool transaction_active>
ALWAYS_INLINE JValue ExecuteSwitchImpl(Thread* self, const CodeItemDataAccessor& accessor,
                                       ShadowFrame& shadow_frame, JValue result_register,
                                       bool interpret_one_instruction)
  REQUIRES_SHARED(Locks::mutator_lock_) {
  SwitchImplContext ctx {
    .self = self,
    .accessor = accessor,
    .shadow_frame = shadow_frame,
    .result_register = result_register,
    .interpret_one_instruction = interpret_one_instruction,
    .result = JValue(),
  };
  void* impl = reinterpret_cast<void*>(&ExecuteSwitchImplCpp<do_access_check, transaction_active>);
  const uint16_t* dex_pc = ctx.accessor.Insns();
  ExecuteSwitchImplAsm(&ctx, impl, dex_pc);
  return ctx.result;
}
```

#### 2.7 ExecuteSwitchImplCpp

- **DEX指令集**定义在 [dex\_instruction\_list.h](https://android.googlesource.com/platform/art/+/master/libdexfile/dex/dex_instruction_list.h)

- **ExecuteSwitchImplCpp** 是实际执行指令的方法，在这个方法里，通过循环的方式执行该代码块里的指令，直到方法return 退出，这个方法定义在 
[interpreter\_switch\_impl-inl.h](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter_switch_impl-inl.h) : 

```cpp
template<bool do_access_check, bool transaction_active>
void ExecuteSwitchImplCpp(SwitchImplContext* ctx) {
  // >>> 解析上下文
  Thread* self = ctx->self;
  const CodeItemDataAccessor& accessor = ctx->accessor;
  ShadowFrame& shadow_frame = ctx->shadow_frame;
  self->VerifyStack();
  uint32_t dex_pc = shadow_frame.GetDexPC();
  const auto* const instrumentation = Runtime::Current()->GetInstrumentation();
  const uint16_t* const insns = accessor.Insns();
  const Instruction* next = Instruction::At(insns + dex_pc);
  DCHECK(!shadow_frame.GetForceRetryInstruction())
      << "Entered interpreter from invoke without retry instruction being handled!";
  bool const interpret_one_instruction = ctx->interpret_one_instruction;
  
  // >>> 循环执行DEX指令
  while (true) {
    const Instruction* const inst = next;
    dex_pc = inst->GetDexPc(insns);
    shadow_frame.SetDexPC(dex_pc);
    TraceExecution(shadow_frame, inst, dex_pc);
    uint16_t inst_data = inst->Fetch16(0);
    bool exit = false;
    bool success;  // Moved outside to keep frames small under asan.
    
    // >>> 执行 InstructionHandler.Preamble() 方法，每个指令执行之前都会先执行一次该方法
    if (InstructionHandler<do_access_check, transaction_active, Instruction::kInvalidFormat>(
            ctx, instrumentation, self, shadow_frame, dex_pc, inst, inst_data, next, exit).
            Preamble()) {
      DCHECK_EQ(self->IsExceptionPending(), inst->Opcode(inst_data) == Instruction::MOVE_EXCEPTION);
      switch (inst->Opcode(inst_data)) {
      
      // >>> 通过宏定义批量生成 switch 分支
#define OPCODE_CASE(OPCODE, OPCODE_NAME, NAME, FORMAT, i, a, e, v)                                \
        case OPCODE: {                                                                            \
          next = inst->RelativeAt(Instruction::SizeInCodeUnits(Instruction::FORMAT));             \
          success = OP_##OPCODE_NAME<do_access_check, transaction_active>(                        \
              ctx, instrumentation, self, shadow_frame, dex_pc, inst, inst_data, next, exit);     \
          if (success && LIKELY(!interpret_one_instruction)) {                                    \
            continue;                                                                             \
          }                                                                                       \
          break;                                                                                  \
        }
  DEX_INSTRUCTION_LIST(OPCODE_CASE)
#undef OPCODE_CASE
      }
    }
    
    // >>> 判断是否执行完成并退出
    if (exit) {
      shadow_frame.SetDexPC(dex::kDexNoIndex);
      return;  // Return statement or debugger forced exit.
    }
    ......
  }
}  // NOLINT(readability/fn_size)


// >>> 通过宏定义批量生成 OP_##OPCODE_NAME 方法
#define OPCODE_CASE(OPCODE, OPCODE_NAME, NAME, FORMAT, i, a, e, v)                                \
template<bool do_access_check, bool transaction_active>                                           \
ASAN_NO_INLINE static bool OP_##OPCODE_NAME(                                                      \
    SwitchImplContext* ctx,                                                                       \
    const instrumentation::Instrumentation* instrumentation,                                      \
    Thread* self,                                                                                 \
    ShadowFrame& shadow_frame,                                                                    \
    uint16_t dex_pc,                                                                              \
    const Instruction* inst,                                                                      \
    uint16_t inst_data,                                                                           \
    const Instruction*& next,                                                                     \
    bool& exit) REQUIRES_SHARED(Locks::mutator_lock_) {                                           \
  InstructionHandler<do_access_check, transaction_active, Instruction::FORMAT> handler(           \
      ctx, instrumentation, self, shadow_frame, dex_pc, inst, inst_data, next, exit);             \
  // >>> 最后调用的是 InstructionHandler 的 ‘OPCODE_NAME ’ 方法    
  return LIKELY(handler.OPCODE_NAME());                                                           \
}
DEX_INSTRUCTION_LIST(OPCODE_CASE)
#undef OPCODE_CASE
```


#### 2.8 InstructionHandler

- **InstructionHandler**定义了每个DEX指令对应的处理方法。这个类定义在 
[interpreter\_switch\_impl-inl.h](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter_switch_impl-inl.h) : 

```cpp
template<bool do_access_check, bool transaction_active, Instruction::Format kFormat>
class InstructionHandler {
 public:
 
 ......
 
 HANDLER_ATTRIBUTES bool INVOKE_VIRTUAL() {
    return HandleInvoke<kVirtual, /*is_range=*/ false>();
  }

  HANDLER_ATTRIBUTES bool INVOKE_SUPER() {
    return HandleInvoke<kSuper, /*is_range=*/ false>();
  }

  HANDLER_ATTRIBUTES bool INVOKE_DIRECT() {
    return HandleInvoke<kDirect, /*is_range=*/ false>();
  }

  HANDLER_ATTRIBUTES bool INVOKE_INTERFACE() {
    return HandleInvoke<kInterface, /*is_range=*/ false>();
  }
  
  HANDLER_ATTRIBUTES bool INVOKE_STATIC() {
    // >>> 执行 HandleInvoke 方法
    return HandleInvoke<kStatic, /*is_range=*/ false>();
  }
  
 ......
 
 template<InvokeType type, bool is_range>
  HANDLER_ATTRIBUTES bool HandleInvoke() {
    // >>> 执行 DoInvoke 方法
    bool success = DoInvoke<type, is_range, do_access_check, /*is_mterp=*/ false>(
        Self(), shadow_frame_, inst_, inst_data_, ResultRegister());
    return PossiblyHandlePendingExceptionOnInvoke(!success);
  }
  
  ......
}
```

#### 2.9 DoInvoke

- [interpreter\_common.h](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter_common.h)

```cpp
// Handles all invoke-XXX/range instructions except for invoke-polymorphic[/range].
// Returns true on success, otherwise throws an exception and returns false.
template<InvokeType type, bool is_range, bool do_access_check, bool is_mterp>
static ALWAYS_INLINE bool DoInvoke(Thread* self,
                                   ShadowFrame& shadow_frame,
                                   const Instruction* inst,
                                   uint16_t inst_data,
                                   JValue* result)
    REQUIRES_SHARED(Locks::mutator_lock_) {
  ......
  
  const uint32_t method_idx = (is_range) ? inst->VRegB_3rc() : inst->VRegB_35c();
  const uint32_t vregC = (is_range) ? inst->VRegC_3rc() : inst->VRegC_35c();
  ArtMethod* sf_method = shadow_frame.GetMethod();
  
  // Try to find the method in small thread-local cache first (only used when
  // nterp is not used as mterp and nterp use the cache in an incompatible way).
  InterpreterCache* tls_cache = self->GetInterpreterCache();
  size_t tls_value;
  ArtMethod* resolved_method;
  if (!IsNterpSupported() && LIKELY(tls_cache->Get(self, inst, &tls_value))) {
    // >>> 使用缓存的 method 数据
    resolved_method = reinterpret_cast<ArtMethod*>(tls_value);
  } else {
    ClassLinker* const class_linker = Runtime::Current()->GetClassLinker();
    constexpr ClassLinker::ResolveMode resolve_mode =
        do_access_check ? ClassLinker::ResolveMode::kCheckICCEAndIAE
                        : ClassLinker::ResolveMode::kNoChecks;
    // >>> 加载 method （如果 class 未加载，则先加载 class）                    
    resolved_method = class_linker->ResolveMethod<resolve_mode>(self, method_idx, sf_method, type);
    if (UNLIKELY(resolved_method == nullptr)) {
      CHECK(self->IsExceptionPending());
      result->SetJ(0);
      return false;
    }
    if (!IsNterpSupported()) {
      // >>> 缓存 method 数据
      tls_cache->Set(self, inst, reinterpret_cast<size_t>(resolved_method));
    }
  }
  
  // Null pointer check and virtual method resolution.
  // >>> 解析 receiver
  ObjPtr<mirror::Object> receiver =
      (type == kStatic) ? nullptr : shadow_frame.GetVRegReference(vregC);
  ArtMethod* called_method;
  
  // >>> 方法查找，根据 receiver 的类型，确定实际调用的方法
  // >>> 如 resolved_method 可能是接口方法，则 called_method 表示其实现方法
  called_method = FindMethodToCall<type, do_access_check>(
      method_idx, resolved_method, &receiver, sf_method, self);
  ......
  
  jit::Jit* jit = Runtime::Current()->GetJit();
  if (is_mterp && !is_range && called_method->IsIntrinsic()) {
    // >>> 部分 method 特殊处理（提高性能?）
    if (MterpHandleIntrinsic(&shadow_frame, called_method, inst, inst_data,
                             shadow_frame.GetResultRegister())) {
      if (jit != nullptr && sf_method != nullptr) {
        jit->NotifyInterpreterToCompiledCodeTransition(self, sf_method);
      }
      return !self->IsExceptionPending();
    }
  }
  
  // >>> 调用 DoCall 执行该方法
  return DoCall<is_range, do_access_check>(called_method, self, shadow_frame, inst, inst_data,
                                           result);
}
```

- **is_mterp**为**true**的情况下，部分方法会通过**MterpHandleIntrinsic**方法执行，相关源码在：

	- [interpreter\_intrinsics.cc](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter_intrinsics.cc)

	- [intrinsics\_enum.h](https://android.googlesource.com/platform/art/+/master/runtime/intrinsics_enum.h)

	- [intrinsics\_list.h](https://android.googlesource.com/platform/art/+/master/runtime/intrinsics_list.h)

#### 2.10 DoCall

- [interpreter\_common.cc](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter_common.cc)

```cpp
template<bool is_range, bool do_assignability_check>
bool DoCall(ArtMethod* called_method, Thread* self, ShadowFrame& shadow_frame,
            const Instruction* inst, uint16_t inst_data, JValue* result) {
  ......
  
  // >>> 调用 DoCallCommon 执行该方法
  return DoCallCommon<is_range, do_assignability_check>(
      called_method, self, shadow_frame,
      result, number_of_inputs, arg, vregC);
}
```

#### 2.11 DoCallCommon

- [interpreter\_common.cc](https://android.googlesource.com/platform/art/+/master/runtime/interpreter/interpreter_common.cc)

```cpp
template <bool is_range, bool do_assignability_check>
static inline bool DoCallCommon(ArtMethod* called_method,
                                Thread* self,
                                ShadowFrame& shadow_frame,
                                JValue* result,
                                uint16_t number_of_inputs,
                                uint32_t (&arg)[Instruction::kMaxVarArgRegs],
                                uint32_t vregC) {
  bool string_init = false;
  // Replace calls to String.<init> with equivalent StringFactory call.
  ......
  
  // Compute method information.
  CodeItemDataAccessor accessor(called_method->DexInstructionData());
  // Number of registers for the callee's call frame.
  uint16_t num_regs;
  
  // Test whether to use the interpreter or compiler entrypoint, and save that result to pass to
  // PerformCall. A deoptimization could occur at any time, and we shouldn't change which
  // entrypoint to use once we start building the shadow frame.
  const bool use_interpreter_entrypoint = ShouldStayInSwitchInterpreter(called_method);
  ......
  
  // Hack for String init:
  // Rewrite invoke-x java.lang.String.<init>(this, a, b, c, ...) into:
  //         invoke-x StringFactory(a, b, c, ...)
  ......
  
  // Parameter registers go at the end of the shadow frame.
  DCHECK_GE(num_regs, number_of_inputs);
  size_t first_dest_reg = num_regs - number_of_inputs;
  DCHECK_NE(first_dest_reg, (size_t)-1);
  // Allocate shadow frame on the stack.
  ......
  
  // >>> 初始化 shadow frame
  // Initialize new shadow frame by copying the registers from the callee shadow frame.
  ......
   
  // >>> 调用 PerformCall 执行该方法
  PerformCall(self,
              accessor,
              shadow_frame.GetMethod(),
              first_dest_reg,
              new_shadow_frame,
              result,
              use_interpreter_entrypoint);
  if (string_init && !self->IsExceptionPending()) {
    SetStringInitValueToAllAliases(&shadow_frame, string_init_vreg_this, *result);
  }
  return !self->IsExceptionPending();
}
```

#### 2.12 PerformCall

- [common\_dex\_operations.h](https://android.googlesource.com/platform/art/+/master/runtime/common_dex_operations.h)

```cpp
inline void PerformCall(Thread* self,
                        const CodeItemDataAccessor& accessor,
                        ArtMethod* caller_method,
                        const size_t first_dest_reg,
                        ShadowFrame* callee_frame,
                        JValue* result,
                        bool use_interpreter_entrypoint)
    REQUIRES_SHARED(Locks::mutator_lock_) {
    
  if (LIKELY(Runtime::Current()->IsStarted())) {
    if (use_interpreter_entrypoint) {
      // >>> 跳转到 ArtInterpreterToInterpreterBridge 依然解释执行
      interpreter::ArtInterpreterToInterpreterBridge(self, accessor, callee_frame, result);
    } else {
       // >>> 跳转到 ArtInterpreterToCompiledCodeBridge 编译执行
      interpreter::ArtInterpreterToCompiledCodeBridge(
          self, caller_method, callee_frame, first_dest_reg, result);
    }
  } else {
    interpreter::UnstartedRuntime::Invoke(self, accessor, callee_frame, result, first_dest_reg);
  }
}
```







