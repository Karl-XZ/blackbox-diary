package com.blackbox.diary.inference;

/** Same CPU backend as the verified 1.2.0-spark-sme2 build; no network or tools. */
public final class SparkSmeBridge {
    public static final boolean available;
    public static volatile boolean resident = false;
    private static volatile String lastMetrics="{}";
    static {
        boolean loaded;
        try { System.loadLibrary("blackbox_spark_runtime"); loaded=true; }
        catch (UnsatisfiedLinkError e) { loaded=false; }
        available=loaded;
    }
    public static String generate(String path,String prompt,int cap) {
        if(!available) throw new IllegalStateException("SME_RUNTIME_UNAVAILABLE");
        String result=nativeGenerate(path,prompt,cap);
        lastMetrics=nativeMetrics();
        resident=true;
        return result;
    }
    public static void cancel() { if(available)nativeCancel(); }
    public static void setThreads(int count) { if(available)nativeSetThreads(count); }
    public static void release() { if(available)nativeRelease(); resident=false; }
    public static String metrics() {return lastMetrics;}
    public static String systemInfo() {return available?nativeSystemInfo():"unavailable";}
    private static native String nativeGenerate(String path,String prompt,int maxTokens);
    private static native void nativeCancel();
    private static native void nativeSetThreads(int count);
    private static native void nativeRelease();
    private static native String nativeMetrics();
    private static native String nativeSystemInfo();
}
