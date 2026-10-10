/*
 * MIT License
 *
 * Copyright (c) 2023 劉強東 https://github.com/liangjingkanji
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.drake.net.cookie

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.Closeable

/**
 * 持久化存储Cookie
 * @param dbName 数据库名称, 设置多个名称可以让不同的客户端共享不同的cookies
 */
class PersistentCookieJar(
    val context: Context,
    val dbName: String = "net_cookies",
) : CookieJar, Closeable {

    private var sqlHelper: SQLiteOpenHelper = object : SQLiteOpenHelper(context, dbName, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            // Cookie 的身份由域名、名称和路径共同决定，同名不同路径必须同时保留。
            db.execSQL("CREATE TABLE IF NOT EXISTS cookies (url TEXT, name TEXT, path TEXT, cookie TEXT, PRIMARY KEY(url, name, path))")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // 原版本只保存响应来源域名，迁移时从 Cookie 内容恢复实际的 domain/path。
                // 使用来源域名解析，才能保留 hostOnly 与会话 Cookie 的原有语义。
                db.execSQL("ALTER TABLE cookies RENAME TO cookies_v1")
                onCreate(db)
                db.rawQuery("SELECT url, cookie FROM cookies_v1", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val sourceUrl = runCatching {
                            HttpUrl.Builder().scheme("https").host(cursor.getString(0)).build()
                        }.getOrNull() ?: continue
                        val cookie = Cookie.parse(sourceUrl, cursor.getString(1)) ?: continue
                        if (cookie.expiresAt > System.currentTimeMillis()) {
                            store(db, cookie)
                        }
                    }
                }
                db.execSQL("DROP TABLE cookies_v1")
            }
        }
    }

    /**
     * 添加Cookie到指定域名下
     */
    fun addAll(url: HttpUrl, cookies: List<Cookie>) {
        val db = sqlHelper.writableDatabase
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            if (cookie.expiresAt > now) {
                store(db, cookie)
            } else {
                // Set-Cookie: Max-Age=0 用于删除已有 Cookie，不能仅跳过此次写入。
                db.delete("cookies", "url = ? AND name = ? AND path = ?",
                    arrayOf(cookie.domain, cookie.name, cookie.path))
            }
        }
    }

    private fun store(db: SQLiteDatabase, cookie: Cookie) {
        db.replace("cookies", null, ContentValues().apply {
            put("url", cookie.domain)
            put("name", cookie.name)
            put("path", cookie.path)
            put("cookie", cookie.toString())
        })
    }

    /**
     * 获取指定域名下的所有Cookie
     */
    fun getAll(url: HttpUrl): List<Cookie> {
        val db = sqlHelper.writableDatabase
        // 同时读取当前主机及父域的数据，之后由 OkHttp 判断 hostOnly 与域匹配。
        // 使用参数化查询，域名永远不直接拼入 SQL。
        val domains = mutableListOf(url.host)
        var parentDomain = url.host
        while ('.' in parentDomain) {
            parentDomain = parentDomain.substringAfter('.')
            domains.add(parentDomain)
        }
        val placeholders = domains.joinToString(",") { "?" }
        db.rawQuery("SELECT url, name, path, cookie FROM cookies WHERE url IN ($placeholders)",
            domains.toTypedArray()).use { cursor ->
            val cookies = mutableListOf<Cookie>()
            val now = System.currentTimeMillis()
            while (cursor.moveToNext()) {
                val storedDomain = cursor.getString(0)
                // hostOnly Cookie 的文本不包含 Domain，需要在它原来的域名下解析。
                val sourceUrl = url.newBuilder().host(storedDomain).build()
                val cookie = Cookie.parse(sourceUrl, cursor.getString(3))
                if (cookie == null || cookie.expiresAt <= now) {
                    db.delete("cookies", "url = ? AND name = ? AND path = ?",
                        arrayOf(storedDomain, cursor.getString(1), cursor.getString(2)))
                    continue
                }
                if (cookie.hostOnly && cookie.domain != url.host) continue
                cookies.add(cookie)
            }
            return cookies
        }
    }

    /**
     * 删除指定域名的所有Cookie
     */
    fun remove(url: HttpUrl) {
        removeMatching(url)
    }

    /**
     * 删除指定域名下的指定cookie
     */
    fun remove(url: HttpUrl, cookieName: String) {
        removeMatching(url, cookieName)
    }

    private fun removeMatching(url: HttpUrl, cookieName: String? = null) {
        val db = sqlHelper.writableDatabase
        // getAll 已按域名和 hostOnly 过滤，删除范围与这个域名可读取的 Cookie 保持一致。
        // 按完整身份删除，保留兄弟域的 hostOnly Cookie，同时移除同名不同路径的全部值。
        getAll(url).filter { cookieName == null || it.name == cookieName }.forEach { cookie ->
            db.delete("cookies", "url = ? AND name = ? AND path = ?",
                arrayOf(cookie.domain, cookie.name, cookie.path))
        }
    }

    /**
     * 清除应用所有Cookie
     */
    fun clear() {
        sqlHelper.writableDatabase.delete("cookies", null, null)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        // 由 OkHttp 的公开匹配规则统一处理域名、路径边界和 secure 属性。
        // 更具体的路径先发送，符合 RFC 6265 对同名 Cookie 的顺序约定。
        return getAll(url).filter { it.matches(url) }.sortedByDescending { it.path.length }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        addAll(url, cookies)
    }

    /** 释放数据库连接；同一个 CookieJar 在关闭后不应继续用于请求。 */
    override fun close() {
        sqlHelper.close()
    }
}
