package com.xiaoxin.voicetotext.android.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadManagerTest {
    @Test
    fun formatBytesUsesTheCorrectBinaryUnit() {
        assertEquals("0 B", ModelDownloadManager.formatBytes(0L))
        assertEquals("1023 B", ModelDownloadManager.formatBytes(1023L))
        assertEquals("1.0 KB", ModelDownloadManager.formatBytes(1024L))
        assertEquals("1.0 MB", ModelDownloadManager.formatBytes(1024L * 1024L))
        assertEquals("1.0 GB", ModelDownloadManager.formatBytes(1024L * 1024L * 1024L))
        assertEquals("141.1 MB", ModelDownloadManager.formatBytes(147_951_465L))
    }
}
