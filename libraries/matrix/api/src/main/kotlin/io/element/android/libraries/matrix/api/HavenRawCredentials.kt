/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.matrix.api

/**
 * Haven: what is needed to call the Client-Server API directly. See [MatrixClient.havenRawCredentials].
 */
class HavenRawCredentials(
    val homeserverUrl: String,
    val accessToken: String,
) {
    // Never include the access token in logs or crash reports.
    override fun toString() = "HavenRawCredentials(homeserverUrl=$homeserverUrl)"
}
