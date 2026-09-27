package com.todoapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebDavConfigTest {

    private fun config(serverUrl: String, remoteDir: String = "ToDoApp") =
        WebDavConfig(serverUrl = serverUrl, username = "u", password = "p", remoteDir = remoteDir)

    @Test
    fun `https地址视为已配置`() {
        assertTrue(config("https://dav.example.com/dav/").isConfigured)
    }

    @Test
    fun `http地址视为已配置`() {
        assertTrue(config("http://192.168.1.2:5005/dav/").isConfigured)
    }

    @Test
    fun `非http协议不视为已配置`() {
        assertFalse(config("ftp://dav.example.com").isConfigured)
    }

    @Test
    fun `http开头的任意字符串不算合法地址`() {
        assertFalse(config("httpfoo").isConfigured)
    }

    @Test
    fun `空地址未配置`() {
        assertFalse(config("").isConfigured)
    }

    @Test
    fun `纯空白地址未配置`() {
        assertFalse(config("   ").isConfigured)
    }

    @Test
    fun `远程目录为空白则未配置`() {
        assertFalse(config("https://dav.example.com", remoteDir = "  ").isConfigured)
    }

    @Test
    fun `默认远程目录常量`() {
        assertEquals("ToDoApp", WebDavConfig.DEFAULT_REMOTE_DIR)
    }
}
