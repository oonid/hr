package id.or.oo.hr

import java.io.File

fun writePrivate(file: File, text: String) {
    val dir = file.absoluteFile.parentFile!!
    check(dir.isDirectory || dir.mkdirs()) { "could not create $dir" }
    val tmp = File(dir, ".${file.name}.tmp")
    tmp.delete()
    check(tmp.createNewFile()) { "could not create $tmp" }
    tmp.setReadable(false, false)
    tmp.setWritable(false, false)
    tmp.setExecutable(false, false)
    tmp.setReadable(true, true)
    tmp.setWritable(true, true)
    tmp.writeText(text)
    check(tmp.renameTo(file)) { "could not write $file" }
}
