package com.wuzhu.rx.downloader.utils
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object MD5Utils {

    private const val TAG = "---DownloadMD5"

    fun calculateMD5(file: File): String? {
        try {
            val tempTime = System.currentTimeMillis()
            val digest = MessageDigest.getInstance("MD5")
            val inputStream = FileInputStream(file)
            val buffer = ByteArray(8192)
            var read = inputStream.read(buffer)
            while (read > 0) {
                digest.update(buffer, 0, read)
                read = inputStream.read(buffer)
            }
            inputStream.close()
            val md5sum = digest.digest()
            val hexChars = CharArray(md5sum.size * 2)
            for (i in md5sum.indices) {
                val v = md5sum[i].toInt() and 0xFF
                hexChars[i * 2] = "0123456789abcdef"[v ushr 4]
                hexChars[i * 2 + 1] = "0123456789abcdef"[v and 0x0F]
            }
            return String(hexChars).also {
                Log.d(TAG, "calculateMD5: file=$file,fileLength=${file.length()},md5=$it,耗时：${System.currentTimeMillis() - tempTime}毫秒")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

}