package dev.omniand.hub.wrappers

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import dev.omniand.hub.webapps.WebApp
import dev.omniand.hub.webapps.WebAppInstaller
import dev.omniand.hub.webapps.displayName
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * Produces asset-bearing Android application wrappers from the generic packaged template.
 *
 * Generation rewrites only fixed-width binary-manifest placeholders and the icon, then signs the
 * result with the non-exportable per-installation Android-Keystore key. Validated Web files are
 * copied under assets/webapp and are later served by OmniAnd without loading wrapper code.
 */
object WrapperInstaller {
    private const val NOTIFICATION_RELAY_VERSION = 1
    private const val TEMPLATE_PACKAGE =
        "dev.omniand.generated.placeholderxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    private const val TEMPLATE_LABEL =
        "OMNIAND_WRAPPER_LABEL_PLACEHOLDER_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
    private const val TEMPLATE_APP_ID =
        "OMNIAND_APP_ID_PLACEHOLDER_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
    private const val TEMPLATE_PLATFORM_CERT =
        "OMNIAND_PLATFORM_CERT_PLACEHOLDER_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
    private const val TEMPLATE_VERSION_NAME =
        "OMNIAND_VERSION_NAME_PLACEHOLDER_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
    private const val TEMPLATE_STARTUP_AUTHORITY = "$TEMPLATE_PACKAGE.androidx-startup"
    private const val TEMPLATE_DYNAMIC_PERMISSION =
        "$TEMPLATE_PACKAGE.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    private const val KEY_ALIAS = "omniand-generated-wrappers"

    data class State(val supported: Boolean, val installed: Boolean)

    fun relayState(context: Context, appId: String): JSONObject {
        val packageName = packageName(appId)
        val info =
            runCatching {
                    context.packageManager.getApplicationInfo(
                        packageName,
                        PackageManager.GET_META_DATA,
                    )
                }
                .getOrNull()
        val version = info?.metaData?.getInt("dev.omniand.RELAY_VERSION", 0) ?: 0
        val notificationsPermission =
            info != null &&
                (Build.VERSION.SDK_INT < 33 ||
                    context.packageManager.checkPermission(
                        Manifest.permission.POST_NOTIFICATIONS,
                        packageName,
                    ) == PackageManager.PERMISSION_GRANTED)
        return JSONObject()
            .put("installed", info != null)
            .put(
                "available",
                info != null && version == NOTIFICATION_RELAY_VERSION && notificationsPermission,
            )
            .put("protocolVersion", version)
            .put("notificationsPermission", notificationsPermission)
            .put(
                "permissionRequired",
                info != null && version == NOTIFICATION_RELAY_VERSION && !notificationsPermission,
            )
            .put("updateAvailable", info != null && version != NOTIFICATION_RELAY_VERSION)
    }

    fun isTrustedWrapper(context: Context, appId: String): Boolean =
        runCatching {
                val flags =
                    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                    else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
                val info = context.packageManager.getPackageInfo(packageName(appId), flags)
                val actual =
                    if (Build.VERSION.SDK_INT >= 28)
                        info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                    else @Suppress("DEPRECATION") info.signatures?.firstOrNull()?.toByteArray()
                val expected =
                    (wrapperKeyStore().getCertificate(KEY_ALIAS) as X509Certificate).encoded
                actual != null &&
                    MessageDigest.isEqual(
                        MessageDigest.getInstance("SHA-256").digest(actual),
                        MessageDigest.getInstance("SHA-256").digest(expected),
                    )
            }
            .getOrDefault(false)

    fun state(context: Context, app: WebApp): State {
        val installed =
            runCatching { context.packageManager.getPackageInfo(packageName(app.id), 0) }.isSuccess
        return State(supported = true, installed = installed)
    }

    fun install(context: Context, validated: WebAppInstaller.ValidatedPackage): JSONObject {
        if (!context.packageManager.canRequestPackageInstalls()) {
            openUnknownSourcesSettings(context)
            error("Allow OmniAnd to install unknown applications in Android settings")
        }
        val metadata = validated.metadata
        val app =
            WebApp(
                metadata.id,
                metadata.name,
                metadata.version,
                metadata.permissions,
                iconPath =
                    JSONObject(File(validated.root, "manifest.json").readText())
                        .optString("icon")
                        .takeIf(String::isNotBlank),
                localizedNames = metadata.localizedNames,
            )

        val directory = File(context.cacheDir, "wrappers")
        check(directory.exists() || directory.mkdirs()) { "Unable to prepare Android integration" }
        val unsignedApk = File(directory, ".${app.id}-unsigned.apk")
        val signedApk = File(directory, "${app.id}.apk")
        generateUnsigned(context, app, validated.root, unsignedApk)
        try {
            sign(unsignedApk, signedApk)
        } finally {
            unsignedApk.delete()
        }

        val operation = InstallOperations.create(context, app.id, "install")
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName(app.id))
        val sessionId = context.packageManager.packageInstaller.createSession(params)
        context.packageManager.packageInstaller.openSession(sessionId).use { session ->
            signedApk.inputStream().use { input ->
                session.openWrite("base.apk", 0, signedApk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val resultIntent =
                Intent(context, PackageInstallResultReceiver::class.java)
                    .putExtra(
                        PackageInstallResultReceiver.EXTRA_OPERATION_ID,
                        operation.getString("operationId"),
                    )
            val sender =
                PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    .intentSender
            session.commit(sender)
        }
        signedApk.delete()
        return operation
    }

    fun openUnknownSourcesSettings(context: Context) {
        context.startActivity(
            Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun requestUninstall(context: Context, app: WebApp) {
        check(state(context, app).installed) { "Android integration is not installed" }
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:${packageName(app.id)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun generateUnsigned(context: Context, app: WebApp, webRoot: File, output: File) {
        context.assets.open("wrappers/template.apk").use { template ->
            ZipInputStream(template).use { input ->
                ZipOutputStream(FileOutputStream(output)).use { zip ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (entry.name.startsWith("META-INF/")) continue
                        val content = input.readBytes()
                        val generated =
                            if (entry.name == "AndroidManifest.xml") {
                                patchManifest(context, content, app)
                            } else if (entry.name == "res/drawable/icon.png") {
                                readIcon(context, app, webRoot) ?: content
                            } else content
                        zip.putNextEntry(
                            ZipEntry(entry.name).apply {
                                time = 0L
                                // Android 11+ requires resources.arsc to remain stored and aligned;
                                // apksig performs the alignment when it signs this intermediate
                                // APK.
                                if (entry.name == "resources.arsc") {
                                    method = ZipEntry.STORED
                                    size = generated.size.toLong()
                                    compressedSize = generated.size.toLong()
                                    crc = CRC32().apply { update(generated) }.value
                                }
                            }
                        )
                        zip.write(generated)
                        zip.closeEntry()
                    }
                    webRoot.walkTopDown().filter(File::isFile).forEach { file ->
                        val relative = file.relativeTo(webRoot).invariantSeparatorsPath
                        check(!relative.startsWith("../") && !relative.contains("/../"))
                        zip.putNextEntry(ZipEntry("assets/webapp/$relative").apply { time = 0L })
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    private fun patchManifest(context: Context, manifest: ByteArray, app: WebApp): ByteArray =
        manifest.copyOf().also { output ->
            replaceBinaryXmlString(output, TEMPLATE_PACKAGE, packageName(app.id))
            replaceBinaryXmlString(
                output,
                TEMPLATE_STARTUP_AUTHORITY,
                "${packageName(app.id)}.androidx-startup",
            )
            // Both manifest attributes reference one deduplicated string-pool entry.
            replaceBinaryXmlString(
                output,
                TEMPLATE_DYNAMIC_PERMISSION,
                "${packageName(app.id)}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            )
            replaceBinaryXmlString(
                output,
                TEMPLATE_LABEL,
                app.displayName(Locale.getDefault().toLanguageTag()).take(80),
            )
            replaceBinaryXmlString(output, TEMPLATE_APP_ID, app.id)
            replaceBinaryXmlString(output, TEMPLATE_VERSION_NAME, app.version.take(80))
            replaceBinaryXmlInt(output, 0x0101021b, nextVersionCode(context, app.id))
            replaceBinaryXmlString(
                output,
                TEMPLATE_PLATFORM_CERT,
                platformCertificateFingerprint(context),
            )
        }

    private fun nextVersionCode(context: Context, appId: String): Int =
        runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName(appId), 0).versionCode + 1
            }
            .getOrDefault(1)

    /** Rewrites an integer Android-manifest attribute identified by its framework resource ID. */
    private fun replaceBinaryXmlInt(document: ByteArray, resourceId: Int, replacement: Int) {
        var offset = 8
        var resourceIndex = -1
        while (offset + 8 <= document.size) {
            val type = document.u16(offset)
            val size = document.i32(offset + 4)
            check(size >= 8 && offset + size <= document.size) { "Invalid wrapper manifest" }
            if (type == 0x0180) {
                val count = (size - 8) / 4
                resourceIndex =
                    (0 until count).firstOrNull { document.i32(offset + 8 + it * 4) == resourceId }
                        ?: -1
            } else if (type == 0x0102 && resourceIndex >= 0) {
                val attributeStart = document.u16(offset + 24)
                val attributeSize = document.u16(offset + 26)
                val attributeCount = document.u16(offset + 28)
                repeat(attributeCount) { index ->
                    val attribute = offset + 16 + attributeStart + index * attributeSize
                    if (document.i32(attribute + 4) == resourceIndex) {
                        document[attribute + 15] = 0x10
                        document.putI32(attribute + 16, replacement)
                        return
                    }
                }
            }
            offset += size
        }
        error("Wrapper integer manifest attribute is missing")
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.i32(offset: Int): Int = u16(offset) or (u16(offset + 2) shl 16)

    private fun ByteArray.putI32(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

    private fun platformCertificateFingerprint(context: Context): String {
        val flags =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val certificate =
            if (Build.VERSION.SDK_INT >= 28)
                info.signingInfo!!.apkContentsSigners.first().toByteArray()
            else @Suppress("DEPRECATION") info.signatures!!.first().toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") {
            "%02x".format(it)
        }
    }

    private fun readIcon(context: Context, app: WebApp, webRoot: File): ByteArray? =
        runCatching {
                val iconPath = app.iconPath ?: return null
                if (app.assetRoot != null)
                    context.assets.open("${app.assetRoot}/$iconPath").use { it.readBytes() }
                else File(webRoot, iconPath).readBytes()
            }
            .getOrNull()

    /** Rewrites a UTF-16 string-pool entry while preserving all binary XML offsets. */
    private fun replaceBinaryXmlString(
        document: ByteArray,
        placeholder: String,
        replacement: String,
    ) {
        val oldBytes = placeholder.toByteArray(Charsets.UTF_16LE)
        val newBytes = replacement.toByteArray(Charsets.UTF_16LE)
        check(newBytes.size <= oldBytes.size && replacement.length < 0x8000) {
            "Wrapper value is too long"
        }
        val offset = document.indexOf(oldBytes)
        check(offset >= 2) { "Wrapper template placeholder is missing" }
        val encodedLength =
            (document[offset - 2].toInt() and 0xff) or
                ((document[offset - 1].toInt() and 0xff) shl 8)
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
        val signer =
            ApkSigner.SignerConfig.Builder(
                    "OmniAnd wrapper",
                    KeyConfig.Jca(privateKey),
                    listOf(certificate),
                )
                .build()
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
                initialize(
                    KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                        )
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setCertificateSubject(
                            javax.security.auth.x500.X500Principal("CN=OmniAnd Generated Wrappers")
                        )
                        .setCertificateSerialNumber(BigInteger.ONE)
                        .setCertificateNotBefore(start.time)
                        .setCertificateNotAfter(end.time)
                        .build()
                )
                generateKeyPair()
            }
        }
        return keyStore
    }

    fun packageName(appId: String): String = "dev.omniand.generated.${appId.replace('-', '_')}"

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (start in 0..size - needle.size) {
            for (index in needle.indices) if (this[start + index] != needle[index]) continue@outer
            return start
        }
        return -1
    }
}
