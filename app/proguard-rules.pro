# sshj finds algorithms by reflection and through factories R8 cannot see.
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
# The full BouncyCastle provider is installed at startup (see LlmWizardApp) and registers
# its algorithms by class name.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.security.**
-dontwarn java.lang.management.**
-dontwarn javax.security.auth.login.**
