package com.wuzhu.rx.downloader.utils

import android.os.Build
import android.util.Base64
import android.util.Log
import okhttp3.Headers
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException


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
                Log.d(
                    TAG,
                    "calculateMD5: file=$file,fileLength=${file.length()},md5=$it,耗时：${System.currentTimeMillis() - tempTime}毫秒"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun getContentMD5Header(headers: Headers): String? {
        return headers.get("content-md5")
    }

    fun transContentMD5HeaderToHex(contentMd5: String?): String? {
        if (!contentMd5.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO) {
            val byteArray = Base64.decode(contentMd5, Base64.DEFAULT)
            return byteArray.joinToString("") {
                "%02x".format(it)
            }
        }
        return null
    }

    /**
     * http response header Content-MD5 的计算方式
     * Consume the stream and return its Base-64 encoded MD5 checksum.
     * [link](https://docs.developer.amazonservices.com/zh_CN/dev_guide/DG_MD5.html)
     */
    fun computeContentMD5Header(inputStream: InputStream?): String? {
        // Consume the stream to compute the MD5 as a side effect.
        val s: DigestInputStream
        return try {
            s = DigestInputStream(inputStream, MessageDigest.getInstance("MD5"))
            // drain the buffer, as the digest is computed as a side-effect
            val buffer = ByteArray(8192)
            while (s.read(buffer) > 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO) {
                Base64.encodeToString(s.messageDigest.digest(), Base64.DEFAULT)
            } else {
                null
            }
        } catch (e: NoSuchAlgorithmException) {
            null
        } catch (e: IOException) {
            null
        }
    }

}