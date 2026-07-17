package com.atxx.jhmcp

/** Standalone proof that fat obfuscated classes cannot hang forever. */
fun main(args: Array<String>) {
    val apk = args[0]
    val timeout = if (args.size > 1) args[1].toLong() else 15_000L
    println("loading $apk timeout=$timeout")
    val t0 = System.currentTimeMillis()
    val s = JadxSession.open(apk, maxSourceBytes = 50_000, decompileTimeoutMs = timeout)
    println("loaded in ${System.currentTimeMillis() - t0}ms classes=${s.classes.size}")
    val fqn = "gcash.module.otp.msisdn.code.OtpCodeViewModel"
    val cls = s.findClass(fqn) ?: error("missing $fqn")
    // Do NOT call cls.methods.size unguarded — that also forces full decompile on this class.
    val t1 = System.currentTimeMillis()
    val smali = s.getClassSmali(cls, 20_000)
    val dt = System.currentTimeMillis() - t1
    println("smali_call_ms=$dt")
    println(smali.lines().take(12).joinToString("\n"))
    println("starts_with_error=" + smali.trimStart().startsWith("// ERROR"))
    s.close()
    if (dt > timeout + 8_000) {
        System.err.println("FAIL: call took longer than timeout budget: $dt > ${timeout + 8000}")
        kotlin.system.exitProcess(1)
    }
    println("PASS: returned within timeout budget (dt=${dt}ms, limit=${timeout}ms)")
}
