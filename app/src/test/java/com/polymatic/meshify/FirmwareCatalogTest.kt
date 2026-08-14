package com.polymatic.meshify

import com.polymatic.meshify.firmware.FirmwareCatalog
import com.polymatic.meshify.firmware.FirmwareRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareCatalogTest {
    @Test
    fun selectsLatestMergedImageForEachBoardAndRole() {
        val releases = FirmwareCatalog.parse(
            """[
              {"version":"v1.9.0","type":"companion","name":"Companion","notes":"","files":[
                {"name":"Heltec_v3_companion-v1.9.0-aaaa-merged.bin","url":"/old.bin"},
                {"name":"Heltec_v3_companion-v1.9.0-aaaa.bin","url":"/old-app.bin"},
                {"name":"Heltec_v3_companion-v1.9.0-aaaa.uf2","url":"/old.uf2"}
              ]},
              {"version":"v1.17.0","type":"companion","name":"Companion","notes":"","files":[
                {"name":"Heltec_v3_companion-v1.17.0-bbbb-merged.bin","url":"/new.bin"}
              ]},
              {"version":"v1.17.0","type":"repeater","name":"Repeater","notes":"","files":[
                {"name":"Heltec_v3_repeater-v1.17.0-bbbb-merged.bin","url":"/repeater.bin"}
              ]}
            ]""",
        )

        val images = FirmwareCatalog.latestMergedImages(releases, null, "heltec v3")

        assertEquals(2, images.size)
        assertTrue(images.any { it.release.role == FirmwareRole.CompanionFirmware && it.release.version == "v1.17.0" })
        assertTrue(images.any { it.release.role == FirmwareRole.Repeater })
        assertTrue(images.all { it.file.absoluteUrl.startsWith("https://flasher.meshcore.io/") })
    }

    @Test
    fun ignoresNonEspAndUnknownRoleFiles() {
        val releases = FirmwareCatalog.parse(
            """[
              {"version":"v1.0.0","type":"room-server","name":"Room","notes":"","files":[
                {"name":"PicoW_room_server-v1.0.0.uf2","url":"/pico.uf2"},
                {"name":"Xiao_S3_room_server-v1.0.0-aaaa-merged.bin","url":"/esp.bin"}
              ]},
              {"version":"v1.0.0","type":"unknown","name":"Unknown","notes":"","files":[]}
            ]""",
        )

        val images = FirmwareCatalog.latestMergedImages(releases, FirmwareRole.RoomServer, "")

        assertEquals(1, images.size)
        assertEquals("Xiao S3", images.single().board)
    }

    @Test
    fun sortsUpdateImagesNewestFirstAndCanFilterInstalledVersion() {
        val releases = FirmwareCatalog.parse(
            """[
              {"version":"v1.9.0","type":"companion","name":"Companion","notes":"","files":[{"name":"Heltec_v3_companion-v1.9.0-a-merged.bin","url":"/old.bin"}]},
              {"version":"v1.17.0","type":"companion","name":"Companion","notes":"","files":[{"name":"Heltec_v3_companion-v1.17.0-a-merged.bin","url":"/new.bin"}]},
              {"version":"v1.10.0","type":"repeater","name":"Repeater","notes":"","files":[{"name":"TBeam_repeater-v1.10.0-a-merged.bin","url":"/repeat.bin"}]}
            ]""",
        )

        val all = FirmwareCatalog.mergedImages(releases, null, "")
        val updates = FirmwareCatalog.mergedImages(releases, null, "", newerThan = "MeshCore v1.10.0")

        assertEquals(listOf("v1.17.0", "v1.10.0", "v1.9.0"), all.map { it.release.version })
        assertEquals(listOf("v1.17.0"), updates.map { it.release.version })
    }

    @Test
    fun updateModeUsesApplicationImageOnlyWhenMergedEspPairExists() {
        val releases = FirmwareCatalog.parse(
            """[
              {"version":"v1.17.0","type":"companion","name":"Companion","notes":"","files":[
                {"name":"Heltec_v3_companion-v1.17.0-a-merged.bin","url":"/merged.bin"},
                {"name":"Heltec_v3_companion-v1.17.0-a.bin","url":"/app.bin"},
                {"name":"PicoW_companion-v1.17.0-a.bin","url":"/pico.bin"}
              ]}
            ]""",
        )

        val updates = FirmwareCatalog.images(releases, null, "", fullReflash = false)
        val full = FirmwareCatalog.images(releases, null, "", fullReflash = true)

        assertEquals(listOf("Heltec v3"), updates.map { it.board })
        assertEquals(listOf("Heltec v3"), full.map { it.board })
    }

    @Test
    fun parsesSingleReleaseObjectAndRoleAliases() {
        val companion = FirmwareCatalog.parse(
            """{
              "version":"v1.17.0","type":"companion_radio_ble","name":"Companion","notes":"","files":[
                {"name":"Ebyte_EoRa-S3_companion_radio_ble-v1.17.0-a-merged.bin","url":"/merged.bin"},
                {"name":"Ebyte_EoRa-S3_companion_radio_ble-v1.17.0-a.bin","url":"/app.bin"}
              ]
            }""",
        )
        val repeater = FirmwareCatalog.parse(
            """{"items":[{
              "version":"v1.17.0","type":"repeater-firmware","name":"Repeater","notes":"","files":[
                {"name":"Heltec_v3_repeater-v1.17.0-a-merged.bin","url":"/repeater.bin"}
              ]
            }]}""",
        )

        assertEquals(FirmwareRole.CompanionFirmware, companion.single().role)
        assertEquals(listOf("Ebyte EoRa-S3"), FirmwareCatalog.images(companion, null, "", fullReflash = false).map { it.board })
        assertEquals(FirmwareRole.Repeater, repeater.single().role)
        assertEquals(listOf("Heltec v3"), FirmwareCatalog.mergedImages(repeater, null, "").map { it.board })
    }
}
