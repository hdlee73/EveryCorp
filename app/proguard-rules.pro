-keepattributes SourceFile,LineNumberTable,*Annotation*,InnerClasses,Signature,Exceptions

# kotlinx.serialization: @Serializable DTO 보존
-keepclassmembers class io.github.hdlee73.everycorp.data.api.dto.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.hdlee73.everycorp.data.api.dto.**$$serializer { *; }

# Retrofit
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
