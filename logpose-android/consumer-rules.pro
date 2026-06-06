# kotlinx.serialization keeps generated serializers; the host app's serialization
# rules normally cover this, but keep LogPose's wire models to be safe.
-keepclassmembers class io.github.siddharthjaswal.logpose.wire.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.siddharthjaswal.logpose.wire.** {
    kotlinx.serialization.KSerializer serializer(...);
}
