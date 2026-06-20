# bionic-fg on a wrapper-ICD (Winlator/Turnip) — loads & inits, but present path deadlocks

Draft report / GitHub issue for **xXJSONDeruloXx/bionic-fg**. Posted only after review.
Context: integrating bionic-fg into **Bannerlator** (Winlator-lineage Android app; Wine + box64/arm64ec +
DXVK, presenting through a **wrapper ICD → Turnip (Mesa) via adrenotools**). Author previously granted
permission to bundle (submodule + README credit). Three on-device runs, full logcats retained.

## TL;DR
The layer **works up to the first present**: our NDK build loads in the guest's (glibc) Vulkan loader,
the implicit layer activates, `Device created`, all embedded SPIR-V load, and a **real `VkSwapchainKHR`
is hooked** (`SwapchainState ready: 1280x720 mult=2 provisionedOutputs=3`). Then the first interpolated
present **hangs forever** and the app is ANR-killed. The hang is **architectural**, not a single bad wait.

## Environment
- App device (the game's): Wine/DXVK → `wrapper_icd.aarch64.json` ICD, `GALLIUM_DRIVER=zink`,
  `WRAPPER_VK_VERSION=1.3.354`, real driver = Turnip (`libvulkan_freedreno.so`) via adrenotools.
  AHB is fully available app-side: `VK_ANDROID_external_memory_android_hardware_buffer` present,
  DRI3 creating pixmaps from `AHardwareBuffer`.
- Adreno 750 / AYANEO Pocket FIT, Android 14.

## What we already fixed locally (offered as PRs)
1. **Manifest:** implicit layer was skipped by the loader — manifest lacked the spec-required
   `disable_environment` key (only had `enable_environment`). Adding
   `"disable_environment": {"BIONIC_FG_DISABLE":"1"}` fixed discovery.
2. **`vk_impl.cpp`:** `kRequiredDeviceExts[]` lists two *instance* extensions
   (`VK_KHR_external_memory_capabilities`, `VK_KHR_get_physical_device_properties2`); they always
   report "Device ext not available" (cosmetic — filtered before `vkCreateDevice`). Removed.
3. **`layer.cpp` present path:** bounded the three `vkWaitForFences(..., UINT64_MAX)` waits to 250 ms
   with graceful degrade. **This did NOT stop the hang** (none of the bounded waits were the culprit),
   which is the key diagnostic below.

## The deadlock
bionic-fg creates its **own separate `VkInstance` + `VkDevice`** (`vk_impl.cpp Device::create`, "first
compute-capable GPU") and shares frames with the app's render device via AHB + `VK_QUEUE_FAMILY_EXTERNAL`
ownership-transfer barriers. On this stack **both devices are independent `wrapper_icd → Turnip`
instances**. After `SwapchainState ready` the process goes silent — no present-path log, none of our
bounded fence-wait timeouts fire — so it blocks at an *un-bounded* wait:
- `FramegenContext::present` → `framegen_context.cpp:829` `fr.fence.wait()` → `vk_impl.cpp:435`
  `vkWaitForFences(..., UINT64_MAX)` on bionic-fg's own compute device, and/or
- `fgCtx->waitIdle()` → `framegen_context.cpp:977` `vkQueueWaitIdle(computeQueue)` (no timeout param).

The interpolation compute submit never completes — consistent with the cross-instance AHB /
external-queue-family ownership transfer never resolving when the two logical devices are distinct
wrapper-ICD instances that don't share a real underlying queue/timeline.

## Question for you
Is a **single-device / in-context mode** feasible — i.e. hook the application's *own* `VkDevice` and run
the interpolation passes on its queue, instead of creating a second instance/device + AHB cross-device
sharing? On this GPU stack the same LSFG engine works when integrated in-context (single device), which
is why we suspect the standalone-device model is the specific incompatibility. Happy to do the work and
PR it if you can point at the right seam; we have full logcats + can run any instrumented build on device.
