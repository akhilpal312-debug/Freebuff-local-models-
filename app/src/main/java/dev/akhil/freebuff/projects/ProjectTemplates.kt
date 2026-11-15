package dev.akhil.freebuff.projects

import dev.akhil.freebuff.data.db.ProjectType

data class ProjectTemplate(
    val label: String,
    val description: String,
    val files: Map<String, String>,
)

/**
 * Starter templates. Everything here is real, self-contained source: the HTML
 * template renders in the in-app preview without any network access, and the
 * Android template is a valid Gradle project.
 */
object ProjectTemplates {

    val webBasic = ProjectTemplate(
        label = "Website",
        description = "HTML, CSS and JavaScript with a live preview",
        files = mapOf(
            "index.html" to """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                    <title>New site</title>
                    <link rel="stylesheet" href="styles.css" />
                  </head>
                  <body>
                    <main>
                      <p class="eyebrow">Freebuff Mobile</p>
                      <h1>Your first page</h1>
                      <p>Edit this file in the project editor, then tap Preview.</p>
                      <button id="action">Tap me</button>
                      <p id="out" class="out"></p>
                    </main>
                    <script src="app.js"></script>
                  </body>
                </html>
            """.trimIndent(),
            "styles.css" to """
                :root { color-scheme: light dark; }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  min-height: 100vh;
                  display: grid;
                  place-items: center;
                  font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
                  background: #fbfaf8;
                  color: #191c1b;
                }
                main { max-width: 34rem; padding: 2rem; }
                .eyebrow {
                  text-transform: uppercase;
                  letter-spacing: 0.14em;
                  font-size: 0.72rem;
                  color: #5a615d;
                }
                h1 { font-size: 2rem; margin: 0.4rem 0 0.8rem; letter-spacing: -0.02em; }
                p { line-height: 1.6; }
                button {
                  margin-top: 1rem;
                  padding: 0.7rem 1.1rem;
                  border-radius: 0.75rem;
                  border: 1px solid #d8dad5;
                  background: #1e6b5c;
                  color: #fff;
                  font-size: 0.95rem;
                }
                .out { font-size: 0.9rem; color: #1e6b5c; min-height: 1.4em; }
            """.trimIndent(),
            "app.js" to """
                const button = document.getElementById('action');
                const out = document.getElementById('out');
                let taps = 0;
                button?.addEventListener('click', () => {
                  taps += 1;
                  out.textContent = `Tapped ${'$'}{taps} time${'$'}{taps === 1 ? '' : 's'}.`;
                });
            """.trimIndent(),
        ),
    )

    val pythonBasic = ProjectTemplate(
        label = "Python",
        description = "A script folder; runs only where Python is available",
        files = mapOf(
            "main.py" to """
                def main() -> None:
                    print("Hello from Freebuff Mobile")


                if __name__ == "__main__":
                    main()
            """.trimIndent(),
            "README.md" to """
                # Python project

                Android does not ship a Python interpreter, so this project cannot be
                executed on the phone by the built-in terminal. The source is real and
                exports cleanly for running on a computer.
            """.trimIndent(),
        ),
    )

    val composeAndroid = ProjectTemplate(
        label = "Android (Compose)",
        description = "Kotlin + Jetpack Compose app module with Gradle wrapper config",
        files = androidFiles(compose = true),
    )

    val kotlinAndroid = ProjectTemplate(
        label = "Android (Views)",
        description = "Kotlin Android app using a plain Activity",
        files = androidFiles(compose = false),
    )

    val reactBasic = ProjectTemplate(
        label = "React",
        description = "React source with a package.json; building requires Node",
        files = mapOf(
            "package.json" to """
                {
                  "name": "freebuff-react-app",
                  "private": true,
                  "version": "0.1.0",
                  "type": "module",
                  "scripts": {
                    "dev": "vite",
                    "build": "vite build"
                  },
                  "dependencies": {
                    "react": "^18.3.1",
                    "react-dom": "^18.3.1"
                  },
                  "devDependencies": {
                    "vite": "^5.4.0",
                    "@vitejs/plugin-react": "^4.3.1"
                  }
                }
            """.trimIndent(),
            "index.html" to """
                <!doctype html>
                <html lang="en">
                  <head><meta charset="utf-8" /><title>React app</title></head>
                  <body><div id="root"></div><script type="module" src="/src/main.jsx"></script></body>
                </html>
            """.trimIndent(),
            "src/main.jsx" to """
                import React from 'react';
                import { createRoot } from 'react-dom/client';
                import App from './App.jsx';

                createRoot(document.getElementById('root')).render(<App />);
            """.trimIndent(),
            "src/App.jsx" to """
                import React, { useState } from 'react';

                export default function App() {
                  const [count, setCount] = useState(0);
                  return (
                    <main style={{ fontFamily: 'system-ui', padding: 24 }}>
                      <h1>React on Freebuff Mobile</h1>
                      <button onClick={() => setCount((c) => c + 1)}>Count: {count}</button>
                    </main>
                  );
                }
            """.trimIndent(),
            "README.md" to """
                # React project

                `npm install && npm run dev` builds this on a computer. The Android app
                cannot run a Node toolchain, and it will tell you so instead of pretending.
            """.trimIndent(),
        ),
    )

    val empty = ProjectTemplate(
        label = "Empty folder",
        description = "Start from nothing",
        files = mapOf("README.md" to "# New project\n"),
    )

    val all = listOf(webBasic, composeAndroid, kotlinAndroid, pythonBasic, reactBasic, empty)

    fun forType(type: ProjectType): ProjectTemplate = when (type) {
        ProjectType.HTML -> webBasic
        ProjectType.PYTHON -> pythonBasic
        ProjectType.KOTLIN_ANDROID -> kotlinAndroid
        ProjectType.COMPOSE_ANDROID -> composeAndroid
        ProjectType.REACT -> reactBasic
        ProjectType.EMPTY -> empty
    }

    fun typeFor(template: ProjectTemplate): ProjectType = when (template) {
        webBasic -> ProjectType.HTML
        pythonBasic -> ProjectType.PYTHON
        kotlinAndroid -> ProjectType.KOTLIN_ANDROID
        composeAndroid -> ProjectType.COMPOSE_ANDROID
        reactBasic -> ProjectType.REACT
        else -> ProjectType.EMPTY
    }

    private fun androidFiles(compose: Boolean): Map<String, String> {
        val packagePath = "dev/akhil/app"
        val files = mutableMapOf(
            "settings.gradle.kts" to """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositories { google(); mavenCentral() }
                }
                rootProject.name = "MyApp"
                include(":app")
            """.trimIndent(),
            "build.gradle.kts" to """
                plugins {
                    id("com.android.application") version "8.7.3" apply false
                    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
                }
            """.trimIndent(),
            "gradle.properties" to """
                org.gradle.jvmargs=-Xmx1024m
                android.useAndroidX=true
                kotlin.code.style=official
            """.trimIndent(),
            "app/build.gradle.kts" to buildString {
                appendLine("plugins {")
                appendLine("    id(\"com.android.application\")")
                appendLine("    id(\"org.jetbrains.kotlin.android\")")
                if (compose) appendLine("    id(\"org.jetbrains.kotlin.plugin.compose\")")
                appendLine("}")
                appendLine()
                appendLine("android {")
                appendLine("    namespace = \"dev.akhil.app\"")
                appendLine("    compileSdk = 35")
                appendLine()
                appendLine("    defaultConfig {")
                appendLine("        applicationId = \"dev.akhil.app\"")
                appendLine("        minSdk = 26")
                appendLine("        targetSdk = 35")
                appendLine("        versionCode = 1")
                appendLine("        versionName = \"1.0\"")
                appendLine("    }")
                appendLine()
                appendLine("    buildTypes { release { isMinifyEnabled = false } }")
                appendLine("    compileOptions {")
                appendLine("        sourceCompatibility = JavaVersion.VERSION_17")
                appendLine("        targetCompatibility = JavaVersion.VERSION_17")
                appendLine("    }")
                appendLine("    kotlinOptions { jvmTarget = \"17\" }")
                if (compose) appendLine("    buildFeatures { compose = true }")
                appendLine("}")
                appendLine()
                appendLine("dependencies {")
                appendLine("    implementation(\"androidx.core:core-ktx:1.15.0\")")
                appendLine("    implementation(\"androidx.appcompat:appcompat:1.7.0\")")
                if (compose) {
                    appendLine("    implementation(platform(\"androidx.compose:compose-bom:2024.10.01\"))")
                    appendLine("    implementation(\"androidx.compose.ui:ui\")")
                    appendLine("    implementation(\"androidx.compose.material3:material3\")")
                    appendLine("    implementation(\"androidx.activity:activity-compose:1.9.3\")")
                }
                appendLine("}")
            },
            "app/src/main/AndroidManifest.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application
                        android:label="MyApp"
                        android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent(),
            "README.md" to """
                # MyApp

                Generated by Freebuff Mobile. Open this folder in Android Studio to build.

                On-device Gradle builds are not supported: Android phones do not ship a
                JDK or the Android SDK, and a 2 GB device cannot run a reliable Gradle
                build. Use Build Mode to see the real detector output.
            """.trimIndent(),
        )

        if (compose) {
            files["app/src/main/java/$packagePath/MainActivity.kt"] = """
                package dev.akhil.app

                import android.os.Bundle
                import androidx.activity.ComponentActivity
                import androidx.activity.compose.setContent
                import androidx.compose.foundation.layout.Arrangement
                import androidx.compose.foundation.layout.Column
                import androidx.compose.foundation.layout.fillMaxSize
                import androidx.compose.foundation.layout.padding
                import androidx.compose.material3.MaterialTheme
                import androidx.compose.material3.Surface
                import androidx.compose.material3.Text
                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Alignment
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.unit.dp

                class MainActivity : ComponentActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContent {
                            MaterialTheme {
                                Surface(modifier = Modifier.fillMaxSize()) {
                                    Home()
                                }
                            }
                        }
                    }
                }

                @Composable
                private fun Home() {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Hello from Compose", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            """.trimIndent()
        } else {
            files["app/src/main/java/$packagePath/MainActivity.kt"] = """
                package dev.akhil.app

                import android.os.Bundle
                import android.widget.TextView
                import androidx.appcompat.app.AppCompatActivity

                class MainActivity : AppCompatActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(TextView(this).apply {
                            text = "Hello from Freebuff Mobile"
                            textSize = 20f
                        })
                    }
                }
            """.trimIndent()
        }
        return files
    }
}
