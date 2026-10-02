# R8 规则。各依赖（Compose、Ktor、OkHttp、coroutines、serialization-core）
# 自带 consumer 规则，这里只补项目自身需要的部分。

# kotlinx.serialization 官方兜底：插件生成的 serializer 均被调用点直接引用，
# 正常可达；这两条防止未来改成反射式序列化时被 full-mode R8 误删。
-keepclassmembers @kotlinx.serialization.Serializable class com.todoapp.** {
    *** Companion;
}
-keepclasseswithmembers @kotlinx.serialization.Serializable class com.todoapp.** {
    kotlinx.serialization.KSerializer serializer(...);
}
