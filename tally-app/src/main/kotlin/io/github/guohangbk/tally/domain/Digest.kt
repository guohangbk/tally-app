package io.github.guohangbk.tally.domain

import java.security.MessageDigest

/** 内容指纹：导入去重、报告缓存键、图片去重共用一套算法，避免各处实现漂移。 */
fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
