import config.BuildTimeConfig
import extension.buildConfigFieldStr
import extension.testCommonDependencies

/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

plugins {
    id("io.element.android-compose-library")
    id("kotlin-parcelize")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.element.android.libraries.matrix.api"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField(
            name = "OAUTH_CLIENT_URI_PATH",
            type = "String",
            value = BuildTimeConfig.OAUTH_CLIENT_URL_PATH?.let { "\"$it\"" } ?: "null",
        )
        buildConfigFieldStr(
            name = "CLIENT_URI",
            value = BuildTimeConfig.URL_WEBSITE ?: "https://haven.software" // Haven
        )
        buildConfigFieldStr(
            name = "LOGO_URI",
            value = BuildTimeConfig.URL_LOGO ?: "https://app.haven.software/vector-icons/512.png" // Haven
        )
        buildConfigFieldStr(
            name = "TOS_URI",
            value = BuildTimeConfig.URL_ACCEPTABLE_USE ?: "https://haven.software" // Haven: TODO point at Haven's terms once published
        )
        buildConfigFieldStr(
            name = "POLICY_URI",
            value = BuildTimeConfig.URL_POLICY ?: "https://haven.software" // Haven: TODO point at Haven's privacy policy once published
        )
    }
}

dependencies {
    api(projects.schildi.matrixcore)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(projects.libraries.androidutils)
    implementation(projects.libraries.architecture)
    implementation(projects.services.analytics.api)
    implementation(projects.libraries.sessionStorage.api)

    testCommonDependencies(libs)
    testImplementation(projects.libraries.matrix.test)
}
