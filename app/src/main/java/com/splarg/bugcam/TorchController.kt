package com.splarg.bugcam

/** Implement only after measuring rear torch during camera capture on the Pixel. */
interface TorchController {
    val capability: String
    fun setEnabled(enabled: Boolean): TorchResult
}

data class TorchResult(val httpStatus: Int, val message: String)

class UntestedTorchController : TorchController {
    override val capability = "not_tested"
    override fun setEnabled(enabled: Boolean) = TorchResult(501,
        "Rear torch with active rear camera has not been tested; torch control is disabled in v1.")
}
