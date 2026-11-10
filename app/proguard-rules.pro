# Navigation routes are @Serializable objects; keep their serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.hivpn.** {
    *** Companion;
}
-keepclasseswithmembers class app.hivpn.** {
    kotlinx.serialization.KSerializer serializer(...);
}
