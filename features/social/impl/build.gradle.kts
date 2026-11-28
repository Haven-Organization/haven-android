/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
import extension.setupDependencyInjection

plugins {
    id("io.element.android-compose-library")
    id("kotlin-parcelize")
}

android {
    namespace = "io.element.android.features.social.impl"
}

setupDependencyInjection()

dependencies {
    api(projects.features.social.api)
    implementation(projects.features.joinroom.api)
    implementation(projects.libraries.mediaviewer.api)
    implementation(projects.appnav)
    implementation(projects.features.roomdirectory.api)
    implementation(projects.services.analytics.api)
    implementation(projects.libraries.core)
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.matrix.api)
    implementation(projects.libraries.matrixui)
    implementation(projects.libraries.matrixmedia.api)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.uiStrings)
    implementation(projects.libraries.emoji.api)
    implementation(projects.libraries.htmlrenderer.api)
    // Haven: emoji-only posts (its Unicode emoji check)
    implementation(projects.schildi.matrixsdk)
    // The stock message reaction pills and reaction summary sheet, reused for posts' reactions shelf (like Haven Desktop).
    implementation(projects.features.messages.impl)
    // Element X's poll card, for posts that are polls.
    implementation(projects.features.poll.api)
    implementation(projects.libraries.dateformatter.api)
    implementation(projects.libraries.preferences.api)
    // Quote posts with an attachment: media pre-processing and sending.
    implementation(projects.libraries.mediaupload.api)
    implementation(platform(libs.network.okhttp.bom))
    implementation(libs.network.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.jsoup)
}
