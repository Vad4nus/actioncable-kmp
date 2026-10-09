package io.github.vad4nus.actioncable.check.legacy

import io.github.vad4nus.actioncable.ActionCableClient

fun legacyClient(url: String): ActionCableClient = ActionCableClient(url)
