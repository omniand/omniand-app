package dev.omniand.hub.camera

import org.json.JSONObject

/** Strictly validates the bounded version-one browser-to-phone signaling vocabulary. */
object CameraSignalValidator {
    const val MAX_SDP = 200 * 1024
    const val MAX_CANDIDATE = 8 * 1024
    const val MAX_PENDING_CANDIDATES = 128

    fun error(value: JSONObject): String? {
        if (value.optInt("version", -1) != 1) return "invalid-version"
        return when (value.optString("type")) {
            "offer" -> validateOffer(value)
            "ice-candidate" -> validateCandidate(value.optJSONObject("candidate"))
            "control" -> validateControlShape(value)
            "stop" -> if (onlyKeys(value, "version", "type")) null else "invalid-stop"
            else -> "invalid-type"
        }
    }

    private fun validateOffer(value: JSONObject): String? {
        if (!onlyKeys(value, "version", "type", "sdp")) return "invalid-offer"
        val sdp = value.opt("sdp") as? String ?: return "invalid-offer"
        return if (sdp.length in 1..MAX_SDP) null else "invalid-offer"
    }

    private fun validateCandidate(value: JSONObject?): String? {
        value ?: return "invalid-candidate"
        val names = value.keys().asSequence().toSet()
        if (names.any { it !in CANDIDATE_KEYS }) return "invalid-candidate"
        val candidate = value.opt("candidate") as? String ?: return "invalid-candidate"
        val line = value.opt("sdpMLineIndex") as? Number ?: return "invalid-candidate"
        if (value.has("sdpMid") && value.opt("sdpMid") !is String) return "invalid-candidate"
        if (value.has("usernameFragment")) {
            val fragment = value.opt("usernameFragment")
            if (fragment != JSONObject.NULL && (fragment !is String || fragment.length > 256))
                return "invalid-candidate"
        }
        return if (candidate.length in 1..MAX_CANDIDATE && line.toInt() in 0..64) null
        else "invalid-candidate"
    }

    private fun validateControlShape(value: JSONObject): String? {
        if (!onlyKeys(value, "version", "type", "camera", "torch", "zoom", "microphone"))
            return "invalid-control"
        if (value.length() <= 2) return "invalid-control"
        if (value.has("camera") && value.opt("camera") !is String) return "invalid-control"
        if (value.has("torch") && value.opt("torch") !is Boolean) return "invalid-control"
        if (value.has("zoom") && value.opt("zoom") !is Number) return "invalid-control"
        if (value.has("microphone") && value.opt("microphone") !is Boolean) return "invalid-control"
        return null
    }

    private fun onlyKeys(value: JSONObject, vararg allowed: String): Boolean {
        val names = value.keys().asSequence().toSet()
        return names.all { it in allowed } && "version" in names && "type" in names
    }

    private val CANDIDATE_KEYS = setOf("sdpMid", "sdpMLineIndex", "candidate", "usernameFragment")
}
