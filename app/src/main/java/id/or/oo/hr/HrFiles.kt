package id.or.oo.hr

import java.io.File

class HrFiles(filesDir: File) {
    val prefixDir = File(filesDir, "usr")
    val homeDir = File(filesDir, "home")
    val hrDir = File(filesDir, "hr")
    val profileDir = File(hrDir, "profile")
    val keyFile = File(hrDir, "api_server_key")
    val imageRecord = File(hrDir, "image.properties")

    fun containerDir(alias: String) = File(prefixDir, "var/lib/pr/containers/$alias")

    fun rootfs(alias: String) = File(containerDir(alias), "rootfs")
}
