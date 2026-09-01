package com.atxx.jhmcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestParsingTest {
    private val manifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app" android:versionName="1.2.3" android:versionCode="45">
            <uses-permission android:name="android.permission.INTERNET"/>
            <uses-permission android:name="android.permission.CAMERA"/>
            <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="34"/>
            <application android:label="X">
                <activity android:name="com.example.app.MainActivity">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN"/>
                        <category android:name="android.intent.category.LAUNCHER"/>
                    </intent-filter>
                </activity>
                <activity android:name=".Second"/>
                <service android:name="com.example.app.Svc"/>
            </application>
        </manifest>
    """.trimIndent()

    @Test
    fun `summary extracts package version and permissions`() {
        val jsonText = parseManifestSummary(manifest).toString()
        assertTrue(jsonText.contains("com.example.app"))
        assertTrue(jsonText.contains("1.2.3"))
        assertTrue(jsonText.contains("android.permission.INTERNET"))
        assertTrue(jsonText.contains("android.permission.CAMERA"))
        assertTrue(jsonText.contains("\"target_sdk\":\"34\""))
        assertTrue(jsonText.contains("\"min_sdk\":\"21\""))
    }

    @Test
    fun `finds the launcher activity`() {
        assertEquals("com.example.app.MainActivity", findLauncherActivity(manifest))
    }

    @Test
    fun `returns null when there is no launcher activity`() {
        val m = "<manifest package=\"a\"><application><activity android:name=\"a.B\"></activity></application></manifest>"
        assertNull(findLauncherActivity(m))
    }

    @Test
    fun `slices permissions only`() {
        val out = sliceManifest(manifest, "permissions")!!
        assertTrue(out.contains("INTERNET"))
        assertTrue(out.contains("CAMERA"))
        assertFalse(out.contains("<service"))
    }

    @Test
    fun `slices services`() {
        assertTrue(sliceManifest(manifest, "services")!!.contains("com.example.app.Svc"))
    }

    @Test
    fun `slices both activities (pair-closed and self-closed)`() {
        val out = sliceManifest(manifest, "activities")!!
        assertTrue(out.contains("MainActivity"))
        assertTrue(out.contains(".Second"))
    }

    @Test
    fun `unknown section returns null`() {
        assertNull(sliceManifest(manifest, "bogus"))
    }

    @Test
    fun `absolute class name handles dotted relative and simple forms`() {
        assertEquals("com.example.app.MainActivity", absoluteClassName("com.example.app", "com.example.app.MainActivity"))
        assertEquals("com.example.app.Second", absoluteClassName("com.example.app", ".Second"))
        assertEquals("com.example.app.Foo", absoluteClassName("com.example.app", "Foo"))
        assertEquals("", absoluteClassName("com.example.app", ""))
    }
}
