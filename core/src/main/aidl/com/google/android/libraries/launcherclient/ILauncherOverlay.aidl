/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.libraries.launcherclient;

import android.os.Bundle;
import android.view.WindowManager.LayoutParams;
import com.google.android.libraries.launcherclient.ILauncherOverlayCallback;

/**
 * The launcher's handle on a -1 screen ("feed") provider.
 *
 * This is Google's launcher overlay protocol: the package, the interface name and the method
 * order are the wire format, since transaction codes are assigned in declaration order. Launcher3
 * carries an identical copy; never reorder, insert or remove methods in only one of them.
 */
interface ILauncherOverlay {
    oneway void startScroll();

    oneway void onScroll(in float progress);

    oneway void endScroll();

    /** Pre api 3 form of windowAttached2. */
    oneway void windowAttached(in LayoutParams lp, in ILauncherOverlayCallback cb, in int flags);

    oneway void windowDetached(in boolean isChangingConfigurations);

    /** flags: bit 0 animate, bits 2+ duration in ms. */
    oneway void closeOverlay(in int flags);

    /** Pre api 4 form of setActivityState. */
    oneway void onPause();

    /** Pre api 4 form of setActivityState. */
    oneway void onResume();

    /** flags: bit 0 animate. */
    oneway void openOverlay(in int flags);

    oneway void requestVoiceDetection(in boolean start);

    String getVoiceSearchLanguage();

    boolean isVoiceDetectionRunning();

    boolean hasOverlayContent();

    /**
     * Keys: "layout_params" (WindowManager.LayoutParams), "configuration" (Configuration) and
     * "client_options" (int, bit 0 enables the feed).
     */
    oneway void windowAttached2(in Bundle bundle, in ILauncherOverlayCallback cb);

    oneway void unusedMethod();

    /** state: bit 0 started, bit 1 resumed. */
    oneway void setActivityState(in int state);

    boolean startSearch(in byte[] data, in Bundle bundle);
}
