package com.raghavsatyadev.grammarfix.engine

/** Absolute path of the directory the downloaded model is stored in. */
expect fun modelStorageDir(): String

/**
 * File name of the downloaded Qwen3 1.7B GGUF, shared by the downloader and the inference engine.
 */
const val MODEL_FILE_NAME = "qwen3-1.7b-int4.gguf"

/** Where the downloader writes the model and where the inference engine loads it from. */
fun defaultModelPath(): String = "${modelStorageDir()}/$MODEL_FILE_NAME"

expect fun getModelFileSize(path: String): Long

expect fun appendModelFileChunk(path: String, bytes: ByteArray, length: Int)

/**
 * Drops any existing content at [path] so a download can start from byte zero.
 *
 * Needed when a server answers a ranged request with `200 OK` (whole body) instead of `206 Partial
 * Content`: appending that body to a partial file would corrupt it.
 */
expect fun truncateModelFile(path: String)

expect fun removeModelFile(path: String)
