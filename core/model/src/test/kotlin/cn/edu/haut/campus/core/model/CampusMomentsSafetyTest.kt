package cn.edu.haut.campus.core.model

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test

class CampusMomentsSafetyTest {
    @Test fun generatedQrDecodesToConfiguredClaimUrlNotOwnerContact() {
        val tag = AntiLostTagItem("id", "HAUT-TAG-ABC123", "cup", ownerContact = "private-contact")
        val base = "https://synthetic.example/campus"
        val cells = tag.generateVisualMatrix(cloudBaseUrl = base)
        val width = cells.size * 4
        val pixels = IntArray(width * width) { index ->
            if (cells[(index / width) / 4][(index % width) / 4]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels))))
        assertEquals(tag.claimWebUrl(base), result.text)
        assertFalse(result.text.contains(tag.ownerContact))
    }
    @Test fun emptySnapshotNeverFabricatesLivePostsOrTags() {
        val snapshot = CampusMomentsSnapshot()
        assertTrue(snapshot.posts.isEmpty())
        assertTrue(snapshot.bookListings.isEmpty())
        assertTrue(snapshot.antiLostTags.isEmpty())
    }
    @Test(expected = IllegalArgumentException::class)
    fun claimCodeCannotInjectAPathOrQuery() {
        AntiLostTagItem("id", "../claim?secret", "cup").claimWebUrl()
    }
}
