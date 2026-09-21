package com.ptc.dbcapture.diagnostics;

/**
 * Read-only linkage check of installed API signatures. Class initialization and all
 * native method calls are deliberately avoided: this does not enable the profiler.
 */
public final class ProfilerApiCheck {
   public static void main(String[] args) throws Exception {
      new WindchillRequestProbe();
      ClassLoader loader = ProfilerApiCheck.class.getClassLoader();
      Class<?> profiler = Class.forName("wt.tools.profiler.WindchillProfiler", false, loader);
      profiler.getMethod("main", String[].class);
      Class<?> service = Class.forName("wt.tools.profiler.ProfilerService", false, loader);
      Class<?> key = Class.forName("[Lwt.tools.profiler.ProfilingKey;", false, loader);
      service.getMethod("enableAdapters", key);
      service.getMethod("disableAdapters", key);
      service.getMethod("pollProfData", Boolean.class, String.class, Boolean.class);
      Class<?> listener = Class.forName("wt.tools.profiler.ProfilingListener", false, loader);
      listener.getMethod("setEnabled", boolean.class);
      System.out.println("ProfilerApiCheck: installed profiler, native servlet completion, and non-creating "
            + "MethodContext API signatures verified without invoking or initializing them.");
   }
}
