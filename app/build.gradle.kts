import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 发版签名从不入库的 keystore.properties 读；没有这个文件时 release 包不签名
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// 版本号只改这一处。versionCode 由它算出（1.2.3 → 10203），保证每次发版都比上一版大，手机上能直接覆盖升级
val appVersion = "1.0.3"
val appVersionCode = appVersion.split('.').map(String::toInt).let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

android {
    namespace = "dev.yrei.achroma"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.yrei.achroma"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        create("release") {
            keystoreProperties.getProperty("storeFile")?.let { path ->
                storeFile = file(path)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // debug 包换个包名，能和正式版并存，测试时不碰正式版里的存档和登录
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            if (release.storeFile != null) signingConfig = release
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

// 游戏文件是原作者的代码，不入库：assets/ach/ 里还没有时，构建前先跑 sync-web.sh 从网页版拉一份。
// 已经有了就不再联网；想换成线上最新版，直接跑 scripts/sync-web.sh
val syncWeb = tasks.register<Exec>("syncWeb") {
    description = "从网页版拉取游戏文件到 app/src/main/assets/ach/"
    val index = layout.projectDirectory.file("src/main/assets/ach/index.html").asFile
    onlyIf("assets/ach/ 里还没有游戏文件") { !index.exists() }
    workingDir = rootDir
    commandLine("bash", "scripts/sync-web.sh")
}
tasks.named("preBuild") { dependsOn(syncWeb) }

// 启动图标的前景层和单色层（主题图标用）就是游戏 favicon.svg 的像素画，属于原作者的美术，也不入库：
// 构建时从拉下来的 favicon.svg 生成到 build/ 下，res/mipmap-anydpi/ic_launcher.xml 只引用它们
abstract class LauncherIcon : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val favicon: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val svg = favicon.get().asFile.readText()
        fun fail(why: String): Nothing = throw GradleException("favicon.svg $why，网页版的图标格式可能变了")
        val (w, h) = Regex("""viewBox="0 0 (\d+) (\d+)"""").find(svg)?.destructured?.let { (w, h) -> w.toInt() to h.toInt() }
            ?: fail("没有 viewBox")
        // 颜色 → 行号 → 这一行里的像素格；颜色按在图里第一次出现的顺序排
        val pixels = linkedMapOf<String, MutableMap<Int, MutableSet<Int>>>()
        for (rect in Regex("""<rect\b([^>]*)>""").findAll(svg)) {
            val attr = Regex("""([\w-]+)="([^"]*)"""").findAll(rect.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            fun num(name: String) = attr[name]?.let { it.toIntOrNull() ?: fail("的 $name=\"$it\" 不是整数") } ?: 0
            val (x, y, rw, rh) = listOf(num("x"), num("y"), num("width"), num("height"))
            if (rw >= w && rh >= h) continue // 铺满整张图的圆角底色，换成自适应图标的背景色
            val fill = attr["fill"]?.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) } ?: fail("里有认不出的颜色")
            val rows = pixels.getOrPut("#FF" + fill.drop(1).uppercase()) { sortedMapOf() }
            for (py in y until y + rh) rows.getOrPut(py) { sortedSetOf() }.addAll(x until x + rw)
        }
        if (pixels.isEmpty()) fail("里没有像素")

        // 每行连续的像素合成一段
        fun pathOf(rows: Map<Int, Set<Int>>) = buildString {
            for ((py, xs) in rows) {
                var start = -1
                var prev = -2
                for (px in xs + Int.MAX_VALUE) {
                    if (px != prev + 1) {
                        if (start >= 0) append("M$start,${py}h${prev - start + 1}v1h-${prev - start + 1}z")
                        start = px
                    }
                    prev = px
                }
            }
        }
        // 缩放到自适应图标 108dp 画布中间 72dp 的安全区
        val scale = 72.0 / maxOf(w, h)
        fun vector(paths: List<Pair<String, String>>) = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<!-- 构建时由网页 favicon.svg 的像素画生成（app/build.gradle.kts 的 LauncherIcon），不要手改 -->\n")
            append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
            append("    android:width=\"108dp\"\n    android:height=\"108dp\"\n")
            append("    android:viewportWidth=\"108\"\n    android:viewportHeight=\"108\">\n")
            append("    <group\n        android:scaleX=\"$scale\"\n        android:scaleY=\"$scale\"\n")
            append("        android:translateX=\"${(108 - w * scale) / 2}\"\n        android:translateY=\"${(108 - h * scale) / 2}\">\n")
            for ((color, data) in paths) {
                append("        <path\n            android:fillColor=\"$color\"\n            android:pathData=\"$data\" />\n")
            }
            append("    </group>\n</vector>\n")
        }

        val dir = outputDir.get().asFile.resolve("drawable")
        dir.deleteRecursively()
        dir.mkdirs()
        dir.resolve("ic_launcher_foreground.xml").writeText(vector(pixels.map { (color, rows) -> color to pathOf(rows) }))
        // 单色层只取彩色部分，中间的白柱留空，不然整个图标会糊成一块
        val nearWhite = { color: String -> color.drop(3).chunked(2).all { it.toInt(16) >= 0xE0 } }
        val mono = pixels.filterKeys { !nearWhite(it) }.values.joinToString("") { pathOf(it) }
        dir.resolve("ic_launcher_monochrome.xml").writeText(vector(listOf("#FFFFFFFF" to mono)))
    }
}

androidComponents {
    onVariants { variant ->
        val icon = tasks.register<LauncherIcon>("generate${variant.name.replaceFirstChar(Char::uppercaseChar)}LauncherIcon") {
            dependsOn(syncWeb)
            favicon.set(layout.projectDirectory.file("src/main/assets/ach/favicon.svg"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(icon, LauncherIcon::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)
}
