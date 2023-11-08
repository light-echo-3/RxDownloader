package com.wuzhu.rx.downloader

import android.util.Log
import com.wuzhu.rx.downloader.utils.FilePathUtils
import com.wuzhu.rx.downloader.utils.MD5Utils
import java.io.File
import java.io.FileInputStream

class DownloadTaskBusiness {
    companion object {
        private const val TAG = "---DownloadTask"
    }

    /**
     * 检查文件名是否过长。
     * 限定文件名长度200字符。
     * @return true:文件名超长
     */
    fun checkFileNameTooLong(localFileName: String): Boolean {
        return localFileName.length > 200
    }

    /***
     * 用md5检查已存在的本地文件
     * @return true：验证通过
     */
    fun checkExistedLocalFile(localFileName: String, md5: String): Boolean {
        val localFile = File(localFileName)
        return localFile.exists() && localFile.length() > 0 && md5.equals(MD5Utils.calculateMD5(localFile), true)
    }

    /***
     * 用md5检查temp文件是否已经下载完成，就差改个名字了
     * @return true：验证通过
     */
    fun checkTempFileIsDownloadComplete(tempFile: File, md5: String): Boolean {
        return tempFile.exists() && tempFile.length() > 0 && md5.equals(MD5Utils.calculateMD5(tempFile), true)
    }

    /**
     * 下载前，检查md5
     * 如果检查不通过，删除临时文件
     */
    fun checkMd5BeforeDownload(tempFile: File, md5: String) {
        val oldMd5 = FilePathUtils.getCurrentMd5(tempFile.name)
        if (!md5.equals(oldMd5, true)) {//文件已经改变，不能继续断点续传
            tempFile.deleteOnExit()
        }
    }

    /**
     * 下载完成后，检查md5
     * 会同时检测 外部传入的md5 和 response header 的 "content-md5"，只要有一个通过，即为检测成功
     * @return true:md5检测通过 || 没有md5
     */
    fun checkMd5AfterDownloadComplete(localFile: File, outerServerMd5: String?, contentMd5: String?): Boolean {
        if (outerServerMd5.isNullOrBlank() && contentMd5.isNullOrBlank()) {
            return true
        } //md5为null，不检测
        val fileMd5 = MD5Utils.calculateMD5(localFile)
        val contentMd5Hex = MD5Utils.transContentMD5HeaderToHex(contentMd5)
        if (!contentMd5Hex.isNullOrBlank() && contentMd5Hex.equals(fileMd5, true)) {
            return true
        }
        if (!outerServerMd5.isNullOrBlank() && outerServerMd5.equals(fileMd5, true)) {
            return true
        }
        return false
    }

    /**
     * test资源：
     * curl -i https://image.toptop.net/20230510/game/game_dominobattle_unity/Android/202305101031/ab/sound.ab
     * curl -i https://image.toptop.net/20230510/game/game_dominobattle_unity/Android/202305101017/ab/sound.ab
     */
    @Suppress("unused")
    private fun test(localFile: File) {
        if (localFile.absolutePath.endsWith("/ab/sound.ab")) {
            Log.e(
                TAG, "download:MD5Utils.computeContentMD5Header=${
                    MD5Utils.computeContentMD5Header(
                        FileInputStream(localFile)
                    )
                }"
            )
        }
    }
}