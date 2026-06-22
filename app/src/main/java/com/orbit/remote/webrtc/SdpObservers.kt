package com.orbit.remote.webrtc

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** SdpObserver that only cares about create success/failure. */
open class CreateSdpObserver(
    private val onCreate: (SessionDescription) -> Unit,
    private val onError: (String) -> Unit = {}
) : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) = onCreate(desc)
    override fun onCreateFailure(error: String?) = onError(error ?: "create failed")
    override fun onSetSuccess() {}
    override fun onSetFailure(error: String?) {}
}

/** SdpObserver that only cares about set success/failure. */
open class SetSdpObserver(
    private val onSet: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetSuccess() = onSet()
    override fun onSetFailure(error: String?) = onError(error ?: "set failed")
}
