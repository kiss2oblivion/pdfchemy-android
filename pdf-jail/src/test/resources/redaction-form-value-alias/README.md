# Synthetic AcroForm alias fixture

`no-view-indirect-value.pdf` is a generated security regression input, containing no user document data. Its SHA-256 is `3dbaf3ead5ea96d34b39aa01a1f239ee7ed632079a71c3c06e56cdb222a53abb`.

The hidden text field's indirect `/V` string is also reachable from the catalog's `/AlternatePayload` key. `FormValueAliasTest` verifies that the objects initially share identity, performs redaction, saves and reloads the result, and checks that both the catalog alias and the synthetic marker `FOLLOW10_PRIVATE_VALUE_482719` are absent.

The same bytes were used in the earlier isolated FOLLOW-10 correction and its actual Editor/Search export checks. This test-resource copy keeps the exact serialized object-reference case in the resulting branch's JVM suite.
