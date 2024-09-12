## dex2oat机器码解读

### 基础知识

#### 寄存器

- 每个指令都是 32 位宽。

- ARM64 有 31 个通用寄存器：X0-X30，每个都是 64 位。低 32 位可以通过 W0-W30 来访问，当写入 Wy 时，Xy 的高 32 位会被置 0。

- 提供 32 个 128 位的独立的寄存器, 用于浮点数以及向量操作：Qx 表示 128 位，Dx 表示 64 位，以此类推。

- ZXR/WZR 始终为 0，写入该寄存器的值会被忽略。

- SP (Stack Pointer) 栈指针寄存器，load 和 store 的基址，指向栈顶。

- X29 用来表示 FP (Frame Pointer)，方法调用的时候，指向栈基址，用于方法调用后恢复栈。

- X30 被用作 LR (Link Register)，也可以通过 LR 来使用，在方法调用前, 保存返回地址。


#### bl 指令的执行过程

- 跳转之前会把函数调用后面地址 (也就是 bl 的下一条指令的地址) 存放到 LR (Link register) 中。

- PC 被 bl 的参数替换，就是 PC 指向了 bl 的参数，通常是一个函数 label，对应着一个地址。

- 目标函数开始执行。

- 目标函数执行完，调用 ret 指令，ret 会把 LR 拷贝到 PC。

- 程序继续执行 PC 所指向的指令，也就是执行原来 bl 下一条指令。


#### 方法执行约定

- 把需要保存的寄存器值入栈，避免被即将调用的函数修改。

- X0-X7 8 个通用寄存器用来保存函数调用的前 8 个参数，超过 8 个的，通过入栈来传递。

- 返回值默认存入 X0 寄存器中。

- 执行 bl 跳转，跳转到目标函数。

- 目标函数如果有返回值，把返回值放入 X0，然后执行 ret。

- 取出返回值，然后出栈，恢复寄存器中的值。


### 参考链接

- [asm_support_gen.h](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/generated/asm_support_gen.h)

- [quick_entrypoints_arm64.S](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/arch/arm64/quick_entrypoints_arm64.S)

- [intrinsics_utils.h](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/compiler/optimizing/intrinsics_utils.h)

- [code_generator_arm64.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/compiler/optimizing/code_generator_arm64.cc)

- [quick_dexcache_entrypoints.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/entrypoints/quick/quick_dexcache_entrypoints.cc)

- [entrypoint_utils-inl.h](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/entrypoints/entrypoint_utils-inl.h)

### 实例对照

- [code_generator_arm64.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/compiler/optimizing/code_generator_arm64.cc)

```cpp
    CODE: (code_offset=0x001334d0 size_offset=0x001334cc size=340)...
      // GenerateFrameEntry
      0x001334d0: d1400bf0	sub x16, sp, #0x2000 (8192) // doOverflowCheck
      0x001334d4: b940021f	ldr wzr, [x16]
      // SaveLiveRegisters
      // 将 x0 写入 [sp - 48] 即 栈顶，并将 sp - 48 写入 sp 即分配栈空间
      // 这里 x0 保存的是当前调用的 ArtMethod，栈回溯的时候利用的就是这个值
      0x001334d8: f81d0fe0	str x0, [sp, #-48]!
      0x001334dc: f9000ff5	str x21, [sp, #24]
      // 这里将 LR 也保存到栈里，函数返回的时候亦是从此恢复
      0x001334e0: a9027bf6	stp x22, lr, [sp, #32]
      // GenerateSuspendCheck
      0x001334e4: 79400270	ldrh w16, [tr] ; state_and_flags
      0x001334e8: 350008b0	cbnz w16, #+0x114 (addr 0x1335fc)
      // GenerateFieldLoadWithBakerReadBarrier
      0x001334ec: 1000007e	adr lr, #+0xc (addr 0x1334f8)
      0x001334f0: b539dd14	cbnz x20, #+0x73ba0 (addr 0x1a7090)
      0x001334f4: b9402035	ldr w21, [x1, #32] // getFieldByOffset
      // MoveArguments
      0x001334f8: aa0103f6	mov x22, x1
      0x001334fc: aa1503e1	mov x1, x21
      // VisitInvokeInterface
      0x00133500: d28009d1	mov x17, #0x4e // saveMethodIndexByConstant
      0x00133504: b9400020	ldr w0, [x1] // getClassFromObjectByOffset
      0x00133508: f9404000	ldr x0, [x0, #128] // getImTableByOffset
      // 注意到这里 x0 保存的是要调用的 ArtMethod
      0x0013350c: f9408c00	ldr x0, [x0, #280] // getArtMethodByOffset
      0x00133510: f940101e	ldr lr, [x0, #32] // getStubMethodByOffset
      0x00133514: d63f03c0	blr lr
      // VisitInvokeStaticOrDirect
      0x00133518: 900017c0	adrp x0, #+0x2f8000 (addr 0x42b000) // getClassFromBss
      0x0013351c: f9401400	ldr x0, [x0, #40] // getArtMethodByOffset
      0x00133520: f940101e	ldr lr, [x0, #32] // getStubMethodByOffset
      0x00133524: d63f03c0	blr lr
      // MoveArguments
      0x00133584: aa1603e1	mov x1, x22
      // VisitInvokeVirtual
      0x00133588: b9400020	ldr w0, [x1] // getClassFromObjectByOffset
      0x0013358c: f9407c00	ldr x0, [x0, #248] // getArtMethodByOffset
      0x00133590: f940101e	ldr lr, [x0, #32] // getStubMethodByOffset
      0x00133594: d63f03c0	blr lr
      // RestoreLiveRegisters    
      0x001335ec: f9400ff5	ldr x21, [sp, #24]
      0x001335f0: a9427bf6	ldp x22, lr, [sp, #32]
      // GenerateFrameExit
      // 回收栈帧
      0x001335f4: 9100c3ff	add sp, sp, #0x30 (48)
      0x001335f8: d65f03c0	ret
      
      // MoveFromReturnRegister
      0x00133614: 2a0003e2	mov w2, w0
      // jump
      0x00133618: 17ffffef	b #-0x44 (addr 0x1335d4)
```

### 内联

- [quick_dexcache_entrypoints.cc](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/entrypoints/quick/quick_dexcache_entrypoints.cc)

- [entrypoint_utils-inl.h](https://android.googlesource.com/platform/art/+/refs/heads/pie-r2-release/runtime/entrypoints/entrypoint_utils-inl.h)

```c++
    // OuterMethod 
    CODE: (code_offset=0x00371384 size_offset=0x00371380 size=208)...
      0x00371384: d1400bf0	sub x16, sp, #0x2000 (8192)
      0x00371388: b940021f	ldr wzr, [x16]
        StackMap [native_pc=0x37138c] (dex_pc=0x0, native_pc_offset=0x8, dex_register_map_offset=0xffffffff, inline_info_offset=0xffffffff, register_mask=0x0, stack_mask=0b000000000)
      0x0037138c: f81c0fe0	str x0, [sp, #-64]!
      0x00371390: a902d7f4	stp x20, x21, [sp, #40]
      0x00371394: f9001ffe	str lr, [sp, #56]
      0x00371398: 79400270	ldrh w16, [tr] ; state_and_flags
      0x0037139c: 35000330	cbnz w16, #+0x64 (addr 0x371400)
      0x003713a0: aa0103f4	mov x20, x1
      0x003713a4: aa0203f5	mov x21, x2
      // CheckClass(CallerMethod)
      0x003713a8: f9401001	ldr x1, [x0, #32]
      0x003713ac: b940bc21	ldr w1, [x1, #188]
      0x003713b0: 34000361	cbz w1, #+0x6c (addr 0x37141c)
      0x003713b4: 1101e030	add w16, w1, #0x78 (120)
      0x003713b8: 88dffe10	ldar w16, [x16]
      0x003713bc: 71002a1f	cmp w16, #0xa (10)
      0x003713c0: 540002eb	b.lt #+0x5c (addr 0x37141c)
      // CheckClass(InlineMethod)
      0x003713c4: f9401001	ldr x1, [x0, #32]
      0x003713c8: b940b821	ldr w1, [x1, #184]
      0x003713cc: 34000361	cbz w1, #+0x6c (addr 0x371438)
      0x003713d0: 1101e030	add w16, w1, #0x78 (120)
      0x003713d4: 88dffe10	ldar w16, [x16]
      0x003713d8: 71002a1f	cmp w16, #0xa (10)
      0x003713dc: 540002eb	b.lt #+0x5c (addr 0x371438)
      // GetStatic
      0x003713e0: d0001800	adrp x0, #+0x302000 (addr 0x673000)
      0x003713e4: f9439000	ldr x0, [x0, #1824]
      0x003713e8: f940181e	ldr lr, [x0, #48]
      0x003713ec: d63f03c0	blr lr
       
       ......
       
      // LoadClass(CallerMethod)
      0x0037141c: f90007e0	str x0, [sp, #8]
      0x00371420: 528005e0	mov w0, #0x2f
      0x00371424: f9410a7e	ldr lr, [tr, #528] ; pInitializeStaticStorage
      0x00371428: d63f03c0	blr lr
        StackMap [native_pc=0x37142c] (dex_pc=0x0, native_pc_offset=0xa8, dex_register_map_offset=0x0, inline_info_offset=0xffffffff, register_mask=0x300000, stack_mask=0b000000000)
          v0: in register (20)	[entry 0]
          v1: in register (21)	[entry 1]
      0x0037142c: 2a0003e1	mov w1, w0
      0x00371430: f94007e0	ldr x0, [sp, #8]
      0x00371434: 17ffffe4	b #-0x70 (addr 0x3713c4)
      // LoadClass(InlineMethod)
      0x00371438: f90007e0	str x0, [sp, #8]
      0x0037143c: 528005c0	mov w0, #0x2e
      0x00371440: f9410a7e	ldr lr, [tr, #528] ; pInitializeStaticStorage
      0x00371444: d63f03c0	blr lr
        StackMap [native_pc=0x371448] (dex_pc=0x0, native_pc_offset=0xc4, dex_register_map_offset=0x0, inline_info_offset=0x5, register_mask=0x300000, stack_mask=0b000000000)
          v0: in register (20)	[entry 0]
          v1: in register (21)	[entry 1]
        InlineInfo with depth 1
         At depth 0 (dex_pc=0x0, method_index=93, invoke_type=static)
      0x00371448: 2a0003e1	mov w1, w0
      0x0037144c: f94007e0	ldr x0, [sp, #8]
      0x00371450: 17ffffe4	b #-0x70 (addr 0x3713e0)
```