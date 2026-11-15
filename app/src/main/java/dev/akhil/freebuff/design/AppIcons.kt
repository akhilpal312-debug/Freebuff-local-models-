package dev.akhil.freebuff.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Small hand-vendored icon set so the app does not pull the ~30 MB
 * material-icons-extended artifact (which is costly to compile on low-end dev
 * machines and bloats the APK for devices with 2 GB of RAM).
 */
object AppIcons {

    private fun vector(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = SolidColor(Color.Black),
            )
        }.build()

    val Mic: ImageVector by lazy {
        vector(
            "Mic",
            "M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                "M17,11c0,2.76 -2.24,5 -5,5s-5,-2.24 -5,-5H5c0,3.53 2.61,6.43 6,6.92V21h2v-3.08" +
                "c3.39,-0.49 6,-3.39 6,-6.92h-2z",
        )
    }

    val MicOff: ImageVector by lazy {
        vector(
            "MicOff",
            "M19,11h-1.7c0,0.74 -0.16,1.43 -0.43,2.05l1.23,1.23c0.56,-0.98 0.9,-2.09 0.9,-3.28z" +
                "M14.98,11.17c0,0.06 0.02,0.11 0.02,0.17 0,1.66 -1.34,3 -3,3 -0.06,0 -0.11,-0.01 -0.17,-0.02" +
                "v2.02c0.55,0.11 1.1,0.17 1.67,0.17 0.06,0 0.11,-0.01 0.17,-0.02v2.7c0,0.55 -0.45,1 -1,1" +
                "s-1,-0.45 -1,-1v-2.06c-3.39,-0.49 -6,-3.39 -6,-6.92h-1.7c0,3.53 2.61,6.43 6,6.92V21h2v-3.08" +
                "c0.34,-0.05 0.67,-0.13 0.99,-0.22L4.27,3 3,4.27 14.98,16.25z" +
                "M15,4.5V5c0,1.66 -1.34,3 -3,3 -0.5,0 -0.97,-0.12 -1.38,-0.34L15,12.28V4.5z",
        )
    }

    val Stop: ImageVector by lazy { vector("Stop", "M6,6h12v12H6z") }

    val Terminal: ImageVector by lazy {
        vector(
            "Terminal",
            "M20,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2z" +
                "M20,18H4V8h16v10zM7.5,17l-1.41,-1.41L8.67,13l-2.58,-2.59L7.5,9l4,4 -4,4zM12,17h6v-2h-6v2z",
        )
    }

    val Code: ImageVector by lazy {
        vector(
            "Code",
            "M9.4,16.6L4.8,12l4.6,-4.6L8,6l-6,6 6,6 1.4,-1.4zM14.6,16.6l4.6,-4.6 -4.6,-4.6L16,6l6,6 -6,6 -1.4,-1.4z",
        )
    }

    val Think: ImageVector by lazy {
        vector(
            "Think",
            "M9,21c0,0.55 0.45,1 1,1h4c0.55,0 1,-0.45 1,-1v-1H9v1zM12,2C8.14,2 5,5.14 5,9" +
                "c0,2.38 1.19,4.47 3,5.74V17c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1v-2.26" +
                "c1.81,-1.27 3,-3.36 3,-5.74 0,-3.86 -3.14,-7 -7,-7z",
        )
    }

    val Globe: ImageVector by lazy {
        vector(
            "Globe",
            "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2z" +
                "M18.92,8h-2.95c-0.32,-1.25 -0.78,-2.45 -1.38,-3.56 1.84,0.63 3.37,1.91 4.33,3.56z" +
                "M12,4.04c0.83,1.2 1.48,2.53 1.91,3.96h-3.82c0.43,-1.43 1.08,-2.76 1.91,-3.96z" +
                "M4.26,14C4.1,13.36 4,12.69 4,12s0.1,-1.36 0.26,-2h3.38c-0.08,0.66 -0.14,1.32 -0.14,2 0,0.68 0.06,1.34 0.14,2H4.26z" +
                "M5.08,16h2.95c0.32,1.25 0.78,2.45 1.38,3.56 -1.84,-0.63 -3.37,-1.9 -4.33,-3.56z" +
                "M8.03,8H5.08c0.96,-1.66 2.49,-2.93 4.33,-3.56C8.81,5.55 8.35,6.75 8.03,8z" +
                "M12,19.96c-0.83,-1.2 -1.48,-2.53 -1.91,-3.96h3.82c-0.43,1.43 -1.08,2.76 -1.91,3.96z" +
                "M14.34,14H9.66c-0.09,-0.66 -0.16,-1.32 -0.16,-2 0,-0.68 0.07,-1.35 0.16,-2h4.68" +
                "c0.09,0.65 0.16,1.32 0.16,2 0,0.68 -0.07,1.34 -0.16,2z" +
                "M14.59,19.56c0.6,-1.11 1.06,-2.31 1.38,-3.56h2.95c-0.96,1.65 -2.49,2.93 -4.33,3.56z" +
                "M16.36,14c0.08,-0.66 0.14,-1.32 0.14,-2 0,-0.68 -0.06,-1.34 -0.14,-2h3.38" +
                "c0.16,0.64 0.26,1.31 0.26,2s-0.1,1.36 -0.26,2h-3.38z",
        )
    }

    val Cube: ImageVector by lazy {
        vector(
            "Cube",
            "M12,2L3,7v10l9,5 9,-5V7l-9,-5zM12,4.15L18.6,7.8 12,11.45 5.4,7.8 12,4.15z" +
                "M19,16.5l-6,3.33v-6.66l6,-3.33v6.66zM5,16.5v-6.66l6,3.33v6.66l-6,-3.33z",
        )
    }

    val Waveform: ImageVector by lazy {
        vector(
            "Waveform",
            "M3,10h2v4H3zM7,7h2v10H7zM11,4h2v16h-2zM15,8h2v8h-2zM19,10.5h2v3h-2z",
        )
    }

    val Cloud: ImageVector by lazy {
        vector(
            "Cloud",
            "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14" +
                "c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z",
        )
    }

    val Download: ImageVector by lazy {
        vector("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    }

    val Pause: ImageVector by lazy { vector("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }

    val Cpu: ImageVector by lazy {
        vector(
            "Cpu",
            "M9,9h6v6H9V9zM15,2v2h2V2h2v2h2v2h-2v2h2v2h-2v4h2v2h-2v2c0,1.1 -0.9,2 -2,2h-2v2h-2v-2h-4v2H7v-2H5v2H3v-2H1v-2h2v-4H1v-2h2V8H1V6h2V4h2V2h2v2h4V2h2z",
        )
    }

    val Folder: ImageVector by lazy {
        vector(
            "Folder",
            "M10,4H2C0.9,4 0,4.9 0,6v12c0,1.1 0.9,2 2,2h20c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2H12l-2,-2z",
        )
    }

    val Clock: ImageVector by lazy {
        vector(
            "Clock",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20" +
                "c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z" +
                "M12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
        )
    }

    val Sparkle: ImageVector by lazy {
        vector(
            "Sparkle",
            "M12,2l2.2,5.8L20,10l-5.8,2.2L12,18l-2.2,-5.8L4,10l5.8,-2.2L12,2z" +
                "M19,15l1,2.5 2.5,1 -2.5,1 -1,2.5 -1,-2.5 -2.5,-1 2.5,-1 1,-2.5z",
        )
    }

    val Layers: ImageVector by lazy {
        vector("Layers", "M12,2L2,8l10,6 10,-6 -10,-6zM2,12l10,6 10,-6 -2.5,-1.5L12,15 4.5,10.5 2,12zM2,16l10,6 10,-6 -2.5,-1.5L12,19 4.5,14.5 2,16z")
    }

    val Copy: ImageVector by lazy {
        vector(
            "Copy",
            "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11" +
                "c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z",
        )
    }

    val Shield: ImageVector by lazy {
        vector("Shield", "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zM12,11.99h7c-0.53,4.12 -3.28,7.79 -7,8.94V12H5V6.3l7,-3.11v8.8z")
    }
}
