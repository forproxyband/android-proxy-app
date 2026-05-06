package com.proxyagent.app

import com.journeyapps.barcodescanner.CaptureActivity

// ZXing's default CaptureActivity is sensorLandscape — in a portrait-only app
// it triggers the "rotate phone" overlay and the camera preview never starts.
// Subclassing it and pinning to portrait via the manifest fixes the scan flow.
class PortraitCaptureActivity : CaptureActivity()
