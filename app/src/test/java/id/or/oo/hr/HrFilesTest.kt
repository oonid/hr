package id.or.oo.hr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HrFilesTest {

    private val files = HrFiles(File("/data/data/id.or.oo.hr/files"))

    @Test
    fun theContainerLivesWherePrCliInstallsItsOciImages() {
        assertEquals(File("/data/data/id.or.oo.hr/files/usr"), files.prefixDir)
        assertEquals(
            File("/data/data/id.or.oo.hr/files/usr/var/lib/pr/containers/hermes/rootfs"),
            files.rootfs("hermes"),
        )
    }

    @Test
    fun hrKeepsItsOwnFilesOutsideTheContainer() {
        assertEquals(File("/data/data/id.or.oo.hr/files/hr/profile"), files.profileDir)
        assertEquals(File("/data/data/id.or.oo.hr/files/hr/api_server_key"), files.keyFile)
        assertEquals(File("/data/data/id.or.oo.hr/files/hr/image.properties"), files.imageRecord)
        assertEquals(File("/data/data/id.or.oo.hr/files/home"), files.homeDir)
    }
}
