package dev.omniand.launcher.wrappers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.FileProvider
import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import dev.omniand.launcher.webapps.WebApp
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

object WrapperInstaller {
    private const val TEMPLATE_PACKAGE = "dev.omniand.generated.placeholderxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    private const val TEMPLATE_LABEL = "OMNIAND_WRAPPER_LABEL_PLACEHOLDER_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
    private const val TEMPLATE_APP_ID = "OMNIAND_APP_ID_PLACEHOLDER_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
    private const val KEY_ALIAS = "omniand-generated-wrappers"

    data class State(val supported: Boolean, val installed: Boolean)

    fun state(context: Context, app: WebApp): State {
        val installed = runCatching { context.packageManager.getPackageInfo(packageName(app.id), 0) }.isSuccess
        return State(supported = true, installed = installed)
    }

    fun install(context: Context, app: WebApp): String {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "permission-required"
        }

        val directory = File(context.cacheDir, "wrappers")
        check(directory.exists() || directory.mkdirs()) { "Unable to prepare Android integration" }
        val unsignedApk = File(directory, ".${app.id}-unsigned.apk")
        val signedApk = File(directory, "${app.id}.apk")
        generateUnsigned(context, app, unsignedApk)
        try {
            sign(unsignedApk, signedApk)
        } finally {
            unsignedApk.delete()
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", signedApk)
        context.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return "installer-opened"
    }

    private fun generateUnsigned(context: Context, app: WebApp, output: File) {
        context.assets.open("wrappers/template.apk").use { template ->
            ZipInputStream(template).use { input ->
                ZipOutputStream(FileOutputStream(output)).use { zip ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (entry.name.startsWith("META-INF/")) continue
                        val content = input.readBytes()
                        val generated = if (entry.name == "AndroidManifest.xml") {
                            patchManifest(content, app)
                        } else if (entry.name == "res/drawable/icon.png") {
                            readIcon(context, app) ?: content
                        } else content
                        zip.putNextEntry(ZipEntry(entry.name).apply {
                            time = 0L
                            if (entry.name == "resources.arsc") {
                                method = ZipEntry.STORED
                                size = generated.size.toLong()
                                compressedSize = generated.size.toLong()
                                crc = CRC32().apply { update(generated) }.value
                            }
                        })
                        zip.write(generated)
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    private fun patchManifest(manifest: ByteArray, app: WebApp): ByteArray = manifest.copyOf().also { output ->
        replaceBinaryXmlString(output, TEMPLATE_PACKAGE, packageName(app.id))
        replaceBinaryXmlString(output, TEMPLATE_LABEL, app.name.take(80))
        replaceBinaryXmlString(output, TEMPLATE_APP_ID, app.id)
    }

    private fun readIcon(context: Context, app: WebApp): ByteArray? = runCatching {
        val iconPath = app.iconPath ?: return null
        if (app.assetRoot != null) context.assets.open("${app.assetRoot}/$iconPath").use { it.readBytes() }
        else File(app.fileRoot ?: return null, iconPath).readBytes()
    }.getOrNull()

    /** Rewrites a UTF-16 string-pool entry while preserving all binary XML offsets. */
    private fun replaceBinaryXmlString(document: ByteArray, placeholder: String, replacement: String) {
        val oldBytes = placeholder.toByteArray(Charsets.UTF_16LE)
        val newBytes = replacement.toByteArray(Charsets.UTF_16LE)
        check(newBytes.size <= oldBytes.size && replacement.length < 0x8000) { "Wrapper value is too long" }
        val offset = document.indexOf(oldBytes)
        check(offset >= 2) { "Wrapper template placeholder is missing" }
        val encodedLength = (document[offset - 2].toInt() and 0xff) or ((document[offset - 1].toInt() and 0xff) shl 8)
        check(encodedLength == placeholder.length) {
            "Unsupported wrapper template string encoding"
        }
        document[offset - 2] = (replacement.length and 0xff).toByte()
        document[offset - 1] = (replacement.length ushr 8).toByte()
        newBytes.copyInto(document, offset)
        document.fill(0, offset + newBytes.size, offset + oldBytes.size)
    }

    private fun sign(unsignedApk: File, signedApk: File) {
        val keyStore = wrapperKeyStore()
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val certificate = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
        val signer = ApkSigner.SignerConfig.Builder(
            "OmniAnd wrapper",
            KeyConfig.Jca(privateKey),
            listOf(certificate)
        ).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
            .sign()
    }

    private fun wrapperKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=OmniAnd Generated Wrappers"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(start.time)
                    .setCertificateNotAfter(end.time)
                    .build())
                generateKeyPair()
            }
        }
        return keyStore
    }

    private fun packageName(appId: String): String = "dev.omniand.generated.${appId.replace('-', '_')}"

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (start in 0..size - needle.size) {
            for (index in needle.indices) if (this[start + index] != needle[index]) continue@outer
            return start
        }
        return -1
    }
}
