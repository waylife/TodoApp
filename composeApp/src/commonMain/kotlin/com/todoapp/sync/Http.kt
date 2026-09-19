package com.todoapp.sync

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout

/** 三端共用的 HttpClient 配置：跟随重定向 + 超时。 */
internal fun HttpClientConfig<*>.todoHttpConfig() {
    followRedirects = true
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
    }
}
