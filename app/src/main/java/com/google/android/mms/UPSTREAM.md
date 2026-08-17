# AOSP MMS PDU subset

These Java sources are copied without package changes from Android Open Source Project
`platform/frameworks/opt/mms`, branch `l-preview`, commit
`64817e848552fd0a429a3e026b7b1562103c56bb`.

Upstream: <https://android.googlesource.com/platform/frameworks/opt/mms/+/64817e848552fd0a429a3e026b7b1562103c56bb/src/java/com/google/android/mms/>

License: Apache License 2.0. Each source file retains its upstream copyright and license header.

Included are the PDU model, parser, composer, encodings, content types, and their three root
exceptions/value files. `PduPersister` and the `util` package are intentionally excluded: they rely
on obsolete or hidden framework APIs. OmniAnd provider persistence is implemented through its own
narrow adapter using public `Telephony` contracts.

Local compatibility change: the two Java 8-era underscore catch-variable names in
`EncodedStringValue` are renamed to `ignored` for Java 17 compilation; behavior is unchanged.
`PduParser` always parses standard Content-Disposition fields instead of consulting AOSP's hidden
`com.android.internal.R` carrier toggle; malformed values still follow the upstream parser's
bounded skip behavior.
