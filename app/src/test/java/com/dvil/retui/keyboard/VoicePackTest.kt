package com.dvil.retui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class VoicePackTest {
    private val dir = File("pack")

    private fun manifest(type: String, files: String, extra: String = "") = """
        {"schema": 1, "kind": "voice", "id": "test-pack", "name": "Test", "type": "$type",
         "languages": ["en"], "files": {$files} $extra}
    """.trimIndent()

    @Test
    fun parsesMergedMoonshine() {
        val pack = VoicePackManager.parseManifest(
            manifest("moonshine", """"encoder": "e.ort", "mergedDecoder": "d.ort", "tokens": "tokens.txt""""),
            dir
        )
        assertEquals("test-pack", pack.id)
        assertEquals(File(dir, "e.ort"), pack.files["encoder"])
        assertEquals(listOf("en"), pack.languages)
    }

    @Test
    fun parsesWhisperOptions() {
        val pack = VoicePackManager.parseManifest(
            manifest("whisper", """"encoder": "e.onnx", "decoder": "d.onnx", "tokens": "t.txt"""",
                """, "options": {"language": "de"}"""),
            dir
        )
        assertEquals("de", pack.option("language"))
        assertEquals("", pack.option("task"))
    }

    @Test
    fun rejectsMissingRoles() {
        assertThrows(IllegalArgumentException::class.java) {
            VoicePackManager.parseManifest(manifest("transducer", """"encoder": "e.onnx", "tokens": "t.txt""""), dir)
        }
    }

    @Test
    fun rejectsPathTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            VoicePackManager.parseManifest(
                manifest("sense_voice", """"model": "../evil.onnx", "tokens": "t.txt""""),
                dir
            )
        }
    }

    @Test
    fun rejectsLanguagePacksAndUnknownTypes() {
        assertThrows(IllegalArgumentException::class.java) {
            VoicePackManager.parseManifest("""{"schema": 1, "id": "fa-ir", "type": "whisper"}""", dir)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoicePackManager.parseManifest(manifest("wav2vec", """"model": "m.onnx", "tokens": "t.txt""""), dir)
        }
    }
}
