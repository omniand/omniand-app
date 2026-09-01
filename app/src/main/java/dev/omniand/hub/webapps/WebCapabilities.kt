package dev.omniand.hub.webapps

/** Single capability vocabulary shared by catalog, installer, and installed-wrapper validation. */
object WebCapabilities {
    val known: Set<String> =
        setOf(
            "sms.read",
            "sms.send",
            "sms.modify",
            "contacts.read",
            "contacts.write",
            "media.read",
            "media.write",
            "files.read",
            "files.write",
            "camera.stream",
            "camera.capture",
        )
}
