package com.wuzhu.rx.downloader

import com.wuzhu.rx.downloader.utils.FilePathUtils
import com.wuzhu.rx.downloader.utils.MD5Utils
import java.io.File

class DownloadTaskBusiness {

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
     * 下载完成后，检查问阿金md5
     * @return true:md5检测通过 || 没有md5
     */
    fun checkMd5AfterDownloadComplete(localFile: File, md5: String?): Boolean {
        if (md5 == null) {
            return true
        } //md5为null，不检测
        val fileMd5 = MD5Utils.calculateMD5(localFile)
        return md5.equals(fileMd5, true)
    }
}