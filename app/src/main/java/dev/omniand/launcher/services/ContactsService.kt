package dev.omniand.launcher.services

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Base64
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps capability-approved HTTP operations onto Android's aggregate Contacts Provider.
 *
 * Lookup keys and opaque raw-source IDs preserve aggregate identity; revisions prevent stale
 * writes, and every write retains read-only sources while mutating only writable provider rows.
 */
class ContactsService(private val context: Context) {
    class PermissionMissing : Exception()

    class InvalidInput : Exception()

    class NotFound : Exception()

    class Conflict : Exception()

    class ReadOnly : Exception()

    private val resolver = context.contentResolver

    fun list(query: String?, offset: Int, limit: Int): JSONObject {
        readRequired()
        if (offset < 0 || limit !in 1..100) throw InvalidInput()
        val rows = mutableListOf<JSONObject>()
        val uri =
            query
                ?.takeIf { it.isNotBlank() }
                ?.let { Uri.withAppendedPath(Contacts.CONTENT_FILTER_URI, Uri.encode(it)) }
                ?: Contacts.CONTENT_URI
        resolver
            .query(
                uri,
                arrayOf(
                    Contacts.LOOKUP_KEY,
                    Contacts.DISPLAY_NAME_PRIMARY,
                    Contacts.HAS_PHONE_NUMBER,
                    Contacts.PHOTO_THUMBNAIL_URI,
                    Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
                ),
                null,
                null,
                "${Contacts.SORT_KEY_PRIMARY} COLLATE LOCALIZED ASC",
            )
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.string(1)
                    rows +=
                        JSONObject()
                            .put("lookupKey", cursor.string(0))
                            .put("displayName", name)
                            .put("hasPhone", cursor.getInt(2) != 0)
                            .put(
                                "photo",
                                cursor.stringOrNull(3)?.let { photoPath(cursor.string(0)) }
                                    ?: JSONObject.NULL,
                            )
                            .put("revision", cursor.getLong(4).toString())
                }
            }
        return JSONObject()
            .put("contacts", JSONArray(rows.drop(offset).take(limit)))
            .put("offset", offset)
            .put("limit", limit)
            .put("total", rows.size)
    }

    fun detail(lookupKey: String): JSONObject {
        readRequired()
        val contact =
            resolver
                .query(
                    Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, Uri.encode(lookupKey)),
                    arrayOf(
                        Contacts._ID,
                        Contacts.LOOKUP_KEY,
                        Contacts.DISPLAY_NAME_PRIMARY,
                        Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
                    ),
                    null,
                    null,
                    null,
                )
                ?.use {
                    if (it.moveToFirst())
                        arrayOf(it.getLong(0), it.string(1), it.string(2), it.getLong(3))
                    else null
                } ?: throw NotFound()
        val data = queryData(contact[0] as Long)
        return JSONObject()
            .put("lookupKey", contact[1])
            .put("displayName", contact[2])
            .put("revision", contact[3].toString())
            .put("name", data.name)
            .put("company", data.company)
            .put("phones", data.phones)
            .put("emails", data.emails)
            .put("addresses", data.addresses)
            .put("birthday", data.birthday)
            .put("notes", data.notes)
            .put("photo", if (data.hasPhoto) photoPath(contact[1] as String) else JSONObject.NULL)
            .put("sources", data.sources)
    }

    fun accounts(): JSONArray {
        readRequired()
        val found = linkedMapOf<String, JSONObject>()
        resolver
            .query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts._ID, RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE),
                "${RawContacts.DELETED}=0",
                null,
                null,
            )
            ?.use { c ->
                while (c.moveToNext()) {
                    val name = c.stringOrNull(1)
                    val type = c.stringOrNull(2)
                    val writable = isAccountWritable(type)
                    if (!writable) continue
                    val id = accountId(name, type)
                    found[id] =
                        JSONObject()
                            .put("id", id)
                            .put("name", name ?: "Device")
                            .put("type", type ?: "local")
                }
            }
        if (found.isEmpty())
            found[accountId(null, null)] =
                JSONObject()
                    .put("id", accountId(null, null))
                    .put("name", "Device")
                    .put("type", "local")
        return JSONArray(found.values)
    }

    fun create(payload: JSONObject): JSONObject {
        writeRequired()
        validate(payload)
        val account = resolveAccount(payload.optString("accountId"))
        val ops =
            arrayListOf(
                ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_NAME, account.first)
                    .withValue(RawContacts.ACCOUNT_TYPE, account.second)
                    .build()
            )
        appendRows(ops, 0, payload)
        val result = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawId = ContentUris.parseId(result[0].uri ?: throw InvalidInput())
        val lookup = lookupForRaw(rawId) ?: throw NotFound()
        rememberAccount(payload.optString("accountId"))
        return detail(lookup)
    }

    fun update(
        lookupKey: String,
        sourceId: String,
        revision: String,
        payload: JSONObject,
    ): JSONObject {
        writeRequired()
        validate(payload)
        assertRevision(lookupKey, revision)
        val rawId = decodeSource(sourceId)
        assertSource(lookupKey, rawId)
        val ops = arrayListOf<ContentProviderOperation>()
        EDITABLE_MIMES.forEach { mime ->
            ops +=
                ContentProviderOperation.newDelete(Data.CONTENT_URI)
                    .withSelection(
                        "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?",
                        arrayOf(rawId.toString(), mime),
                    )
                    .build()
        }
        appendRows(ops, rawId, payload, backReference = false)
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        return detail(lookupKey)
    }

    fun delete(lookupKey: String, revision: String): JSONObject {
        writeRequired()
        assertRevision(lookupKey, revision)
        val writable = rawSources(contactId(lookupKey)).filter { it.second }
        if (writable.isEmpty()) throw ReadOnly()
        writable.forEach {
            resolver.delete(
                ContentUris.withAppendedId(RawContacts.CONTENT_URI, it.first),
                null,
                null,
            )
        }
        return JSONObject()
            .put("deleted", true)
            .put("remainingReadOnly", rawSourcesOrEmpty(lookupKey).any { !it.second })
    }

    fun photo(lookupKey: String): ByteArray {
        readRequired()
        val id = contactId(lookupKey)
        return Contacts.openContactPhotoInputStream(
                resolver,
                ContentUris.withAppendedId(Contacts.CONTENT_URI, id),
                true,
            )
            ?.use { it.readBytes() } ?: throw NotFound()
    }

    fun setPhoto(
        lookupKey: String,
        sourceId: String,
        revision: String,
        bytes: ByteArray?,
    ): JSONObject {
        writeRequired()
        assertRevision(lookupKey, revision)
        val rawId = decodeSource(sourceId)
        assertSource(lookupKey, rawId)
        resolver.delete(
            Data.CONTENT_URI,
            "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?",
            arrayOf(rawId.toString(), Photo.CONTENT_ITEM_TYPE),
        )
        if (bytes != null)
            resolver.insert(
                Data.CONTENT_URI,
                ContentValues().apply {
                    put(Data.RAW_CONTACT_ID, rawId)
                    put(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                    put(Photo.PHOTO, bytes)
                },
            )
        return detail(lookupKey)
    }

    fun match(numbers: JSONArray): JSONArray {
        readRequired()
        if (numbers.length() > 100) throw InvalidInput()
        return JSONArray().apply {
            for (i in 0 until numbers.length()) {
                val number = numbers.optString(i)
                val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(number))
                val match =
                    resolver
                        .query(
                            uri,
                            arrayOf(Phone.LOOKUP_KEY, Phone.DISPLAY_NAME_PRIMARY),
                            null,
                            null,
                            null,
                        )
                        ?.use {
                            if (it.moveToFirst())
                                JSONObject()
                                    .put("lookupKey", it.string(0))
                                    .put("name", it.string(1))
                                    .put("photo", photoPath(it.string(0)))
                            else null
                        }
                put(JSONObject().put("number", number).put("contact", match ?: JSONObject.NULL))
            }
        }
    }

    private data class Aggregate(
        val name: JSONObject,
        val company: String?,
        val phones: JSONArray,
        val emails: JSONArray,
        val addresses: JSONArray,
        val birthday: String?,
        val notes: String?,
        val hasPhoto: Boolean,
        val sources: JSONArray,
    )

    private fun queryData(contactId: Long): Aggregate {
        var given = ""
        var family = ""
        var company: String? = null
        var birthday: String? = null
        var notes: String? = null
        var hasPhoto = false
        val phones = JSONArray()
        val emails = JSONArray()
        val addresses = JSONArray()
        resolver
            .query(
                Data.CONTENT_URI,
                arrayOf(
                    Data.RAW_CONTACT_ID,
                    Data.MIMETYPE,
                    Data.DATA1,
                    Data.DATA2,
                    Data.DATA3,
                    Data.DATA4,
                    Data.IS_PRIMARY,
                ),
                "${Data.CONTACT_ID}=?",
                arrayOf(contactId.toString()),
                null,
            )
            ?.use { c ->
                while (c.moveToNext()) {
                    when (c.string(1)) {
                        StructuredName.CONTENT_ITEM_TYPE -> {
                            given = c.stringOrNull(3).orEmpty()
                            family = c.stringOrNull(4).orEmpty()
                        }
                        Organization.CONTENT_ITEM_TYPE -> company = company ?: c.stringOrNull(2)
                        Phone.CONTENT_ITEM_TYPE -> phones.put(typed(c.stringOrNull(2), c.getInt(3)))
                        Email.CONTENT_ITEM_TYPE -> emails.put(typed(c.stringOrNull(2), c.getInt(3)))
                        StructuredPostal.CONTENT_ITEM_TYPE ->
                            addresses.put(
                                JSONObject()
                                    .put("formatted", c.stringOrNull(2).orEmpty())
                                    .put("type", c.getInt(3))
                            )
                        Event.CONTENT_ITEM_TYPE ->
                            if (c.getInt(3) == Event.TYPE_BIRTHDAY) birthday = c.stringOrNull(2)
                        Note.CONTENT_ITEM_TYPE -> notes = notes ?: c.stringOrNull(2)
                        Photo.CONTENT_ITEM_TYPE -> hasPhoto = true
                    }
                }
            }
        val sources =
            JSONArray(
                rawSources(contactId).map { (id, writable) ->
                    JSONObject().put("id", sourceId(id)).put("writable", writable)
                }
            )
        return Aggregate(
            JSONObject().put("given", given).put("family", family),
            company,
            phones,
            emails,
            addresses,
            birthday,
            notes,
            hasPhoto,
            sources,
        )
    }

    private fun appendRows(
        ops: ArrayList<ContentProviderOperation>,
        raw: Long,
        p: JSONObject,
        backReference: Boolean = true,
    ) {
        fun add(mime: String, values: Map<String, Any?>) {
            val b =
                ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.MIMETYPE, mime)
            if (backReference) b.withValueBackReference(Data.RAW_CONTACT_ID, raw.toInt())
            else b.withValue(Data.RAW_CONTACT_ID, raw)
            values.forEach { (k, v) -> b.withValue(k, v) }
            ops += b.build()
        }
        val name = p.optJSONObject("name") ?: JSONObject()
        add(
            StructuredName.CONTENT_ITEM_TYPE,
            mapOf(
                StructuredName.GIVEN_NAME to name.optString("given"),
                StructuredName.FAMILY_NAME to name.optString("family"),
            ),
        )
        p.optString("company")
            .takeIf { it.isNotBlank() }
            ?.let { add(Organization.CONTENT_ITEM_TYPE, mapOf(Organization.COMPANY to it)) }
        appendTyped(
            p.optJSONArray("phones"),
            Phone.CONTENT_ITEM_TYPE,
            Phone.NUMBER,
            Phone.TYPE,
            ::add,
        )
        appendTyped(
            p.optJSONArray("emails"),
            Email.CONTENT_ITEM_TYPE,
            Email.ADDRESS,
            Email.TYPE,
            ::add,
        )
        p.optJSONArray("addresses")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                add(
                    StructuredPostal.CONTENT_ITEM_TYPE,
                    mapOf(
                        StructuredPostal.FORMATTED_ADDRESS to it.optString("formatted"),
                        StructuredPostal.TYPE to it.optInt("type", 0),
                    ),
                )
            }
        }
        p.optString("birthday")
            .takeIf { it.isNotBlank() }
            ?.let {
                add(
                    Event.CONTENT_ITEM_TYPE,
                    mapOf(Event.START_DATE to it, Event.TYPE to Event.TYPE_BIRTHDAY),
                )
            }
        p.optString("notes")
            .takeIf { it.isNotBlank() }
            ?.let { add(Note.CONTENT_ITEM_TYPE, mapOf(Note.NOTE to it)) }
    }

    private fun appendTyped(
        a: JSONArray?,
        mime: String,
        valueCol: String,
        typeCol: String,
        add: (String, Map<String, Any?>) -> Unit,
    ) {
        if (a != null)
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                add(mime, mapOf(valueCol to it.optString("value"), typeCol to it.optInt("type", 0)))
            }
    }

    private fun typed(value: String?, type: Int) =
        JSONObject().put("value", value.orEmpty()).put("type", type)

    private fun validate(p: JSONObject) {
        if (p.toString().length > 64 * 1024) throw InvalidInput()
        if (
            p.optJSONArray("phones")?.length() ?: 0 > 50 ||
                p.optJSONArray("emails")?.length() ?: 0 > 50
        )
            throw InvalidInput()
    }

    private fun readRequired() {
        if (
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
                PackageManager.PERMISSION_GRANTED
        )
            throw PermissionMissing()
    }

    private fun writeRequired() {
        readRequired()
        if (
            context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) !=
                PackageManager.PERMISSION_GRANTED
        )
            throw PermissionMissing()
    }

    private fun contactId(key: String): Long =
        resolver
            .query(
                Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, Uri.encode(key)),
                arrayOf(Contacts._ID),
                null,
                null,
                null,
            )
            ?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: throw NotFound()

    private fun assertRevision(key: String, revision: String) {
        val current = detail(key).getString("revision")
        if (current != revision) throw Conflict()
    }

    private fun rawSources(id: Long): List<Pair<Long, Boolean>> {
        val out = mutableListOf<Pair<Long, Boolean>>()
        resolver
            .query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts._ID),
                "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0",
                arrayOf(id.toString()),
                null,
            )
            ?.use {
                while (it.moveToNext()) {
                    val rawId = it.getLong(0)
                    out += rawId to isRawWritable(rawId)
                }
            }
        return out
    }

    private fun rawSourcesOrEmpty(key: String) =
        runCatching { rawSources(contactId(key)) }.getOrDefault(emptyList())

    private fun assertSource(key: String, raw: Long) {
        if (rawSources(contactId(key)).none { it.first == raw && it.second }) throw ReadOnly()
    }

    private fun lookupForRaw(raw: Long): String? =
        resolver
            .query(
                ContentUris.withAppendedId(RawContacts.CONTENT_URI, raw),
                arrayOf(RawContacts.CONTACT_ID),
                null,
                null,
                null,
            )
            ?.use {
                if (it.moveToFirst())
                    resolver
                        .query(
                            ContentUris.withAppendedId(Contacts.CONTENT_URI, it.getLong(0)),
                            arrayOf(Contacts.LOOKUP_KEY),
                            null,
                            null,
                            null,
                        )
                        ?.use { c -> if (c.moveToFirst()) c.string(0) else null }
                else null
            }

    private fun resolveAccount(id: String): Pair<String?, String?> {
        if (id.isBlank() || id == accountId(null, null)) return null to null
        resolver
            .query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts._ID, RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE),
                "${RawContacts.DELETED}=0",
                null,
                null,
            )
            ?.use {
                while (it.moveToNext()) {
                    val n = it.stringOrNull(1)
                    val t = it.stringOrNull(2)
                    if (isRawWritable(it.getLong(0)) && accountId(n, t) == id) return n to t
                }
            }
        throw InvalidInput()
    }

    private fun isRawWritable(rawId: Long): Boolean =
        resolver
            .query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts.ACCOUNT_TYPE),
                "${RawContacts._ID}=?",
                arrayOf(rawId.toString()),
                null,
            )
            ?.use { rows ->
                rows.moveToFirst() && isAccountWritable(rows.stringOrNull(0))
            } ?: false

    private fun isAccountWritable(accountType: String?): Boolean =
        accountType == null ||
            android.content.ContentResolver.getSyncAdapterTypes().any {
                it.authority == ContactsContract.AUTHORITY &&
                    it.accountType == accountType &&
                    it.supportsUploading()
            }

    private fun rememberAccount(id: String) {
        if (id.isNotBlank())
            context.getSharedPreferences("contacts", 0).edit().putString("last-account", id).apply()
    }

    private fun accountId(n: String?, t: String?) =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest("${t.orEmpty()}\u0000${n.orEmpty()}".toByteArray())
                .copyOf(12),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    private fun sourceId(id: Long) =
        Base64.encodeToString(
            id.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    private fun decodeSource(value: String) =
        runCatching { String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)).toLong() }
            .getOrElse { throw InvalidInput() }

    private fun photoPath(key: String) = "/api/contacts/${Uri.encode(key)}/photo"

    private fun android.database.Cursor.string(i: Int) = getString(i) ?: ""

    private fun android.database.Cursor.stringOrNull(i: Int): String? =
        if (isNull(i)) null else getString(i)

    companion object {
        private val EDITABLE_MIMES =
            listOf(
                StructuredName.CONTENT_ITEM_TYPE,
                Organization.CONTENT_ITEM_TYPE,
                Phone.CONTENT_ITEM_TYPE,
                Email.CONTENT_ITEM_TYPE,
                StructuredPostal.CONTENT_ITEM_TYPE,
                Event.CONTENT_ITEM_TYPE,
                Note.CONTENT_ITEM_TYPE,
            )
    }
}
