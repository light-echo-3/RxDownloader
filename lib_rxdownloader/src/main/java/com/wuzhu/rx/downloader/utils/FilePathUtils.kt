package com.wuzhu.rx.downloader.utils

object FilePathUtils {
    private const val NO_MD5 = "nomd5"

    @JvmStatic
    fun getTempFileName(localFileName: String, md5: String?): String {
        val md5L = if (md5.isNullOrBlank()) {
            NO_MD5
        } else {
            md5
        }
        return "$localFileName.tmp.$md5L"
    }

    /**
     * 从临时文件名中，获取md5
     */
    fun getCurrentMd5(tempFileName: String): String? {
        return try {
            val md5 = tempFileName.substring(tempFileName.lastIndexOf(".") + 1, tempFileName.length)
            if (md5 == NO_MD5) {
                null
            } else {
                md5
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

}