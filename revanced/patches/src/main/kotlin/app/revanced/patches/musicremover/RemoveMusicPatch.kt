package app.revanced.patches.musicremover

import app.revanced.patcher.extensions.instructionsOrNull
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.w3c.dom.Element

private const val EXTENSION_PACKAGE = "Lapp/revanced/extension/musicremover/"
private const val EXTENSION_CLASS_DESCRIPTOR = "${EXTENSION_PACKAGE}MusicRemoverPatch;"
private const val AUDIO_TRACK = "Landroid/media/AudioTrack;"

private const val EXTENSION_JAVA_PACKAGE = "app.revanced.extension.musicremover"
private const val TILE_ICON = "revanced_music_remover_tile"

/**
 * The AudioTrack methods whose calls are redirected to the extension,
 * identified by name, parameter types and return type.
 */
private val hookedMethods = setOf(
    Triple("write", listOf("Ljava/nio/ByteBuffer;", "I", "I"), "I"),
    Triple("flush", emptyList(), "V"),
    Triple("release", emptyList(), "V"),
)

private fun Instruction.isHookedAudioTrackCall(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val reference = methodReference ?: return false
    if (reference.definingClass != AUDIO_TRACK) return false

    return Triple(
        reference.name,
        reference.parameterTypes.map { it.toString() },
        reference.returnType,
    ) in hookedMethods
}

/**
 * Turns `invoke-virtual {track, args...}, AudioTrack->method(args)` into
 * `invoke-static {track, args...}, MusicRemoverPatch->method(AudioTrack, args)`.
 * Both use the same registers, so the surrounding code stays valid.
 */
private fun Instruction.toExtensionCall(): BuilderInstruction {
    val reference = methodReference!!
    val extensionReference = ImmutableMethodReference(
        EXTENSION_CLASS_DESCRIPTOR,
        reference.name,
        listOf(AUDIO_TRACK) + reference.parameterTypes.map { it.toString() },
        reference.returnType,
    )

    return when (this) {
        is Instruction35c -> BuilderInstruction35c(
            Opcode.INVOKE_STATIC,
            registerCount,
            registerC,
            registerD,
            registerE,
            registerF,
            registerG,
            extensionReference,
        )

        is Instruction3rc -> BuilderInstruction3rc(
            Opcode.INVOKE_STATIC_RANGE,
            startRegister,
            registerCount,
            extensionReference,
        )

        else -> throw PatchException("Unexpected instruction format $opcode")
    }
}

private val musicRemoverTilePatch = resourcePatch {
    apply {
        get("res/drawable/$TILE_ICON.xml").apply {
            parentFile.mkdirs()
            writeText(
                """
                <vector xmlns:android="http://schemas.android.com/apk/res/android"
                    android:width="24dp"
                    android:height="24dp"
                    android:viewportWidth="24"
                    android:viewportHeight="24">
                    <path
                        android:fillColor="#FFFFFFFF"
                        android:pathData="M4.27,3L3,4.27l9,9v0.28c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4v-1.73L19.73,21 21,19.73 4.27,3zM14,7h4V3h-6v5.18l2,2z" />
                </vector>
                """.trimIndent(),
            )
        }

        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as Element

            fun element(tag: String, vararg attributes: Pair<String, String>, children: List<Element> = emptyList()) =
                document.createElement(tag).apply {
                    attributes.forEach { (name, value) -> setAttribute(name, value) }
                    children.forEach(::appendChild)
                }

            application.appendChild(
                element(
                    "service",
                    "android:name" to "$EXTENSION_JAVA_PACKAGE.MusicRemoverTileService",
                    "android:exported" to "true",
                    "android:icon" to "@drawable/$TILE_ICON",
                    "android:label" to "No music",
                    "android:permission" to "android.permission.BIND_QUICK_SETTINGS_TILE",
                    children = listOf(
                        element(
                            "intent-filter",
                            children = listOf(
                                element("action", "android:name" to "android.service.quicksettings.action.QS_TILE"),
                            ),
                        ),
                        element(
                            "meta-data",
                            "android:name" to "android.service.quicksettings.TOGGLEABLE_TILE",
                            "android:value" to "true",
                        ),
                    ),
                ),
            )

            application.appendChild(
                element(
                    "activity",
                    "android:name" to "$EXTENSION_JAVA_PACKAGE.MusicRemoverSettingsActivity",
                    "android:exported" to "true",
                    "android:excludeFromRecents" to "true",
                    "android:label" to "No music",
                    "android:theme" to "@android:style/Theme.Translucent.NoTitleBar",
                    children = listOf(
                        element(
                            "intent-filter",
                            children = listOf(
                                element(
                                    "action",
                                    "android:name" to "android.service.quicksettings.action.QS_TILE_PREFERENCES",
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
    }
}

@Suppress("unused")
val removeMusicPatch = bytecodePatch(
    name = "Remove music",
    description = "Removes background music from videos while keeping voices. " +
        "Add the \"No music\" tile to Quick Settings to turn it on or off, long press the tile to change the strength.",
) {
    compatibleWith("com.google.android.youtube")

    dependsOn(musicRemoverTilePatch)

    extendWith("extensions/musicremover.rve")

    apply {
        var hookedCalls = 0

        // Snapshot, because making a class mutable replaces it in the set.
        classDefs.toList()
            .filter { !it.type.startsWith(EXTENSION_PACKAGE) }
            .filter { classDef ->
                classDef.methods.any { method ->
                    method.instructionsOrNull?.any { it.isHookedAudioTrackCall() } == true
                }
            }.forEach { classDef ->
                classDefs.getOrReplaceMutable(classDef).methods.forEach { method ->
                    val instructions = method.instructionsOrNull ?: return@forEach
                    instructions.indices
                        .filter { index -> instructions[index].isHookedAudioTrackCall() }
                        .forEach { index ->
                            method.replaceInstruction(index, instructions[index].toExtensionCall())
                            hookedCalls++
                        }
                }
            }

        if (hookedCalls == 0) throw PatchException("Could not find where the app plays audio")
    }
}
