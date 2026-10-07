/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.libraries.launcherclient;

/** The feed provider's handle back on the launcher. Wire format, see ILauncherOverlay. */
interface ILauncherOverlayCallback {
    /** progress: 0 closed to 1 fully open. */
    oneway void overlayScrollChanged(float progress);

    /** status: bit 0 overlay attached, bits 3 and 4 persistent server flags. */
    oneway void overlayStatusChanged(int status);
}
