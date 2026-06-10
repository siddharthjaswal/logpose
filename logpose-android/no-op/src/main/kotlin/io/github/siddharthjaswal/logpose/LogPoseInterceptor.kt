package io.github.siddharthjaswal.logpose

import okhttp3.Interceptor
import okhttp3.Response

/**
 * No-op replacement for the real `LogPoseInterceptor`, shipped in the `logpose-no-op`
 * artifact for release builds.
 *
 * It mirrors the real interceptor's public API exactly, so the same call site compiles
 * across variants:
 *
 * ```kotlin
 * // app/build.gradle.kts
 * debugImplementation("com.github.siddharthjaswal.logpose:logpose-android:<tag>")
 * releaseImplementation("com.github.siddharthjaswal.logpose:logpose-no-op:<tag>")
 *
 * // shared code — compiles against whichever variant is on the classpath
 * OkHttpClient.Builder()
 *     .addInterceptor(LogPoseInterceptor(LogPoseConfig(enabled = BuildConfig.DEBUG)))
 *     .build()
 * ```
 *
 * In release builds this does nothing but pass the chain through: no capture, no logcat
 * output, no kotlinx-serialization on the classpath, zero transitive dependencies. OkHttp
 * is `compileOnly`, so it pins no version on the consumer.
 */
class LogPoseInterceptor @JvmOverloads constructor(
    @Suppress("UNUSED_PARAMETER") config: LogPoseConfig = LogPoseConfig(),
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
