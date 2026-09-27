package com.todoapp.data

import com.russhwolf.settings.PreferencesSettings
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals

/** 设置存储单测：配置归一化、持久化读回、lastSyncAt。 */
class SettingsStoreTest {

    private fun newNode() = Preferences.userRoot().node("/com/todoapp/test/store-${System.nanoTime()}")

    @Test
    fun `默认配置未设置`() {
        val store = SettingsStore(PreferencesSettings(newNode()))
        assertEquals("", store.config.value.serverUrl)
        assertEquals("", store.config.value.username)
        assertEquals("", store.config.value.password)
        assertEquals(WebDavConfig.DEFAULT_REMOTE_DIR, store.config.value.remoteDir)
        assertEquals(0L, store.lastSyncAt)
    }

    @Test
    fun `保存配置后可读回`() {
        val store = SettingsStore(PreferencesSettings(newNode()))
        store.saveConfig(
            WebDavConfig(serverUrl = "https://dav.example.com/dav/", username = "u", password = "p", remoteDir = "backup/todo"),
        )
        assertEquals("https://dav.example.com/dav", store.config.value.serverUrl)
        assertEquals("u", store.config.value.username)
        assertEquals("p", store.config.value.password)
        assertEquals("backup/todo", store.config.value.remoteDir)
    }

    @Test
    fun `保存时归一化地址与目录`() {
        val store = SettingsStore(PreferencesSettings(newNode()))
        store.saveConfig(
            WebDavConfig(serverUrl = " https://dav.example.com/dav/ ", username = "u", password = "p", remoteDir = "/backup/todo/"),
        )
        assertEquals("https://dav.example.com/dav", store.config.value.serverUrl)
        assertEquals("backup/todo", store.config.value.remoteDir)
    }

    @Test
    fun `空目录回退为默认目录`() {
        val store = SettingsStore(PreferencesSettings(newNode()))
        store.saveConfig(WebDavConfig(serverUrl = "https://dav.example.com", username = "u", password = "p", remoteDir = "  "))
        assertEquals(WebDavConfig.DEFAULT_REMOTE_DIR, store.config.value.remoteDir)
    }

    @Test
    fun `新实例从持久层读回配置`() {
        val node = newNode()
        val first = SettingsStore(PreferencesSettings(node))
        first.saveConfig(WebDavConfig(serverUrl = "https://dav.example.com", username = "u", password = "p", remoteDir = "ToDoApp"))
        first.lastSyncAt = 123_456L

        val second = SettingsStore(PreferencesSettings(node))

        assertEquals(first.config.value, second.config.value)
        assertEquals(123_456L, second.lastSyncAt)
    }

    @Test
    fun `lastSyncAt 可读写`() {
        val store = SettingsStore(PreferencesSettings(newNode()))
        store.lastSyncAt = 42L
        assertEquals(42L, store.lastSyncAt)
    }
}
