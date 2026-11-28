package cn.edu.haut.campus.core.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object BackupZip {
    private val ENTRY_NAME = Regex("(manifest\.json|data\.json|blobs/[0-9a-f]{64})")

    fun create(
        manifestText: String,
        dataText: String,
        blobs: Map<String, ByteArray>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            writeEntry(zip, BackupFormatConstants.MANIFEST_ENTRY, manifestText.toByteArray(Charsets.UTF_8))
            writeEntry(zip, BackupFormatConstants.DATA_ENTRY, dataText.toByteArray(Charsets.UTF_8))
            blobs.keys.sorted().forEach { sha ->
                writeEntry(zip, BackupWireCodec.blobPathFor(sha), blobs.getValue(sha))
            }
        }
        return out.toByteArray()
    }

    fun read(zipBytes: ByteArray): Map<String, ByteArray> {
        if (zipBytes.size.toLong() > BackupFormatConstants.MAX_ARCHIVE_BYTES) {
            throw BackupCorruptedException("ZIP 明文超过大小上限")
        }
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) throw BackupCorruptedException("ZIP 不允许目录项")
                    val name = entry.name
                    if (!ENTRY_NAME.matches(name)) throw BackupCorruptedException("ZIP 包含未登记条目: " + name)
                    if (entries.containsKey(name)) throw BackupCorruptedException("ZIP 条目重复: " + name)
                    val remaining = BackupFormatConstants.MAX_ARCHIVE_BYTES - total
                    val bytes = readEntryLimited(zip, remaining)
                    total += bytes.size
                    entries[name] = bytes
                    zip.closeEntry()
                }
            }
        } catch (error: BackupException) {
            throw error
        } catch (error: Throwable) {
            throw BackupCorruptedException("ZIP 容器无法读取", error)
        }
        if (!entries.containsKey(BackupFormatConstants.MANIFEST_ENTRY) || !entries.containsKey(BackupFormatConstants.DATA_ENTRY)) {
            throw BackupCorruptedException("ZIP 缺少 manifest.json 或 data.json")
        }
        return entries
    }

    fun readInputLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > BackupFormatConstants.MAX_ARCHIVE_BYTES * 2) {
                    throw BackupCorruptedException("加密备份超过大小上限")
                }
                out.write(buffer, 0, read)
            }
        } catch (error: BackupException) {
            throw error
        } catch (error: Throwable) {
            throw BackupCorruptedException("备份输入流读取失败", error)
        }
        return out.toByteArray()
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.time = 0L
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readEntryLimited(input: InputStream, remainingTotal: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > BackupFormatConstants.MAX_ARCHIVE_BYTES || total > remainingTotal) {
                throw BackupCorruptedException("ZIP 条目超过明文大小上限")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
