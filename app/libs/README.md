# Mi Push SDK input

Place the official mainland-China Mi Push client AAR here as exactly
`MiPush_SDK_Client.aar`. Download it from the Xiaomi developer console for the
registered `dev.chanooh.alert` app; do not substitute a third-party mirror.

Before committing the AAR, calculate its SHA-256 and set the GitHub repository
secret `MIPUSH_SDK_SHA256` to that lower-case digest. CI validates the file when
it is present. `AppSecret` belongs only on the Alert server and is never placed
in this directory or in Android build secrets.
