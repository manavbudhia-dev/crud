package com.papervault.scanner.ui.qr

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import com.google.mlkit.vision.barcode.common.Barcode
import org.json.JSONArray
import org.json.JSONObject

enum class CodeKind(val label: String) {
    URL("Link"), WIFI("Wi-Fi"), CONTACT("Contact"), EMAIL("Email"), PHONE("Phone"), SMS("SMS"),
    GEO("Location"), EVENT("Calendar event"), PRODUCT("Product"), TEXT("Text"),
}

/** A decoded code, flattened from ML Kit's [Barcode] into what the UI needs. */
data class CodeResult(
    val kind: CodeKind,
    val title: String,
    val value: String,
    val details: List<Pair<String, String>> = emptyList(),
    val raw: String = value,
    val format: String = "",
    val time: Long = System.currentTimeMillis(),
)

object QrParser {
    fun from(b: Barcode): CodeResult {
        val raw = b.rawValue.orEmpty()
        val format = formatName(b.format)
        return when (b.valueType) {
            Barcode.TYPE_URL -> CodeResult(CodeKind.URL, b.url?.title?.ifBlank { null } ?: "Link", b.url?.url ?: raw, raw = raw, format = format)
            Barcode.TYPE_WIFI -> {
                val w = b.wifi
                val sec = when (w?.encryptionType) { Barcode.WiFi.TYPE_WPA -> "WPA/WPA2"; Barcode.WiFi.TYPE_WEP -> "WEP"; else -> "Open" }
                CodeResult(CodeKind.WIFI, w?.ssid.orEmpty().ifBlank { "Wi-Fi network" }, w?.password.orEmpty(),
                    listOf("Network" to w?.ssid.orEmpty(), "Password" to w?.password.orEmpty(), "Security" to sec), raw, format)
            }
            Barcode.TYPE_CONTACT_INFO -> {
                val c = b.contactInfo
                val name = c?.name?.formattedName.orEmpty()
                CodeResult(CodeKind.CONTACT, name.ifBlank { "Contact" }, listOfNotNull(c?.phones?.firstOrNull()?.number, c?.emails?.firstOrNull()?.address).joinToString("\n"),
                    buildList {
                        if (name.isNotBlank()) add("Name" to name)
                        c?.organization?.takeIf { it.isNotBlank() }?.let { add("Company" to it) }
                        c?.phones?.forEach { add("Phone" to it.number.orEmpty()) }
                        c?.emails?.forEach { add("Email" to it.address.orEmpty()) }
                        c?.urls?.forEach { add("Website" to it) }
                    }, raw, format)
            }
            Barcode.TYPE_EMAIL -> CodeResult(CodeKind.EMAIL, b.email?.address.orEmpty(), b.email?.body.orEmpty(),
                listOf("To" to b.email?.address.orEmpty(), "Subject" to b.email?.subject.orEmpty()), raw, format)
            Barcode.TYPE_PHONE -> CodeResult(CodeKind.PHONE, b.phone?.number.orEmpty(), b.phone?.number.orEmpty(), raw = raw, format = format)
            Barcode.TYPE_SMS -> CodeResult(CodeKind.SMS, b.sms?.phoneNumber.orEmpty(), b.sms?.message.orEmpty(), raw = raw, format = format)
            Barcode.TYPE_GEO -> CodeResult(CodeKind.GEO, "Location", "${b.geoPoint?.lat}, ${b.geoPoint?.lng}", raw = raw, format = format)
            Barcode.TYPE_CALENDAR_EVENT -> CodeResult(CodeKind.EVENT, b.calendarEvent?.summary.orEmpty().ifBlank { "Event" },
                b.calendarEvent?.description.orEmpty(), listOf("Where" to b.calendarEvent?.location.orEmpty()), raw, format)
            Barcode.TYPE_PRODUCT, Barcode.TYPE_ISBN -> CodeResult(CodeKind.PRODUCT, raw, raw, raw = raw, format = format)
            else -> CodeResult(CodeKind.TEXT, "Text", raw, raw = raw, format = format)
        }
    }

    private fun formatName(f: Int) = when (f) {
        Barcode.FORMAT_QR_CODE -> "QR code"; Barcode.FORMAT_EAN_13 -> "EAN-13"; Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"; Barcode.FORMAT_UPC_E -> "UPC-E"; Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"; Barcode.FORMAT_CODE_93 -> "Code 93"; Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_PDF417 -> "PDF417"; Barcode.FORMAT_AZTEC -> "Aztec"; Barcode.FORMAT_ITF -> "ITF"; Barcode.FORMAT_CODABAR -> "Codabar"
        else -> "Barcode"
    }
}

/** Primary actions for a result. Each returns an Intent or null when not applicable. */
object QrActions {
    fun primary(r: CodeResult): Pair<String, Intent>? = when (r.kind) {
        CodeKind.URL -> "Open link" to Intent(Intent.ACTION_VIEW, Uri.parse(withScheme(r.value)))
        CodeKind.WIFI -> "Wi-Fi settings" to Intent(Settings.ACTION_WIFI_SETTINGS)
        CodeKind.CONTACT -> "Add contact" to Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE).apply {
            r.details.firstOrNull { it.first == "Name" }?.let { putExtra(ContactsContract.Intents.Insert.NAME, it.second) }
            r.details.firstOrNull { it.first == "Phone" }?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it.second) }
            r.details.firstOrNull { it.first == "Email" }?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it.second) }
            r.details.firstOrNull { it.first == "Company" }?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it.second) }
        }
        CodeKind.EMAIL -> "Send email" to Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${r.title}"))
            .putExtra(Intent.EXTRA_SUBJECT, r.details.firstOrNull { it.first == "Subject" }?.second.orEmpty()).putExtra(Intent.EXTRA_TEXT, r.value)
        CodeKind.PHONE -> "Call" to Intent(Intent.ACTION_DIAL, Uri.parse("tel:${r.value}"))
        CodeKind.SMS -> "Send SMS" to Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${r.title}")).putExtra("sms_body", r.value)
        CodeKind.GEO -> "Open in Maps" to Intent(Intent.ACTION_VIEW, Uri.parse("geo:${r.value.replace(" ", "")}"))
        CodeKind.EVENT -> "Add to calendar" to Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, r.title).putExtra(CalendarContract.Events.DESCRIPTION, r.value)
        CodeKind.PRODUCT, CodeKind.TEXT -> "Search web" to Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", r.value)
    }

    private fun withScheme(u: String) = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(u)) u else "https://$u"
}

/** Last 50 scans, newest first, kept in private preferences. */
class QrHistory(context: Context) {
    private val prefs = context.getSharedPreferences("qr_history", Context.MODE_PRIVATE)

    fun load(): List<CodeResult> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CodeResult(
                CodeKind.valueOf(o.getString("kind")), o.getString("title"), o.getString("value"),
                o.optJSONArray("details")?.let { d -> (0 until d.length()).map { j -> d.getJSONArray(j).let { it.getString(0) to it.getString(1) } } }.orEmpty(),
                o.optString("raw"), o.optString("format"), o.getLong("time"),
            )
        }
    }.getOrDefault(emptyList())

    fun add(r: CodeResult) = save(listOf(r) + load().filterNot { it.raw == r.raw }.take(49))
    fun clear() = save(emptyList())

    private fun save(list: List<CodeResult>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("kind", r.kind.name); put("title", r.title); put("value", r.value); put("raw", r.raw); put("format", r.format); put("time", r.time)
                put("details", JSONArray().apply { r.details.forEach { put(JSONArray().put(it.first).put(it.second)) } })
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
