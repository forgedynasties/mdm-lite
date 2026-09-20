# aio-mdm-lite

The AIO MDM-lite library: gives any app, on any Android 10+ device, AIO MDM's vitals, crash
reporting, security posture and a few remote controls (screenshot, reload, clear cache,
update check, restart) with no Device Owner, no adb and no root. It speaks the same device
protocol as the MDM's other clients and has no third-party dependencies.

Build the AAR: `./gradlew :mdm-lite:assembleRelease` → `mdm-lite/build/outputs/aar/`

Integration, from the host's `Application.onCreate`:

    AioMdm.init(this, MdmConfig(serverUrl, enrollToken, serial = hostSerial))
    AioMdm.attachWebView(webView)          // remote reload / clear cache
    AioMdm.setUpdateCheck { /* ... */ }    // remote "check for update"

plus the `report*` hooks where the host already sees failures. `AioMdm.status()` gives a
snapshot for a status screen. See `mdm-lite/src/main/kotlin/com/aioapp/mdmlite/AioMdm.kt`.

Device identity: `MdmConfig.serial` wins; otherwise the hardware serial (`Build.getSerial()`,
then `ro.serialno` / `ro.boot.serialno`) when the OS lets an ordinary app read it, else
`android-<ANDROID_ID>`. What a device enrolled with is then kept for good, with one exception:
a device pinned to an `android-...` id moves onto its hardware serial the first time that serial
becomes readable, re-enrolling under the new identity. The old `android-...` device is left on
the server and can be deleted there.

Test app: `../aio-mdm-client-lite` (builds against this checkout directly).
