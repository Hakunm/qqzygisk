import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
//    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.java.zygisk)
    alias(libs.plugins.lsparanoid)
    alias(libs.plugins.compose.compiler)
}

zygisk {
    packages("com.tencent.mobileqq")

    id = "com_qm.qqzygisk"
    name = "qqhook"
    author = "night_star"
    description = "表情面板同步TG表情包"
    entrypoint = "com.qm.qqzygisk.Main"
    archiveName = "qqhook"
    dir = "adb/qqzygisk"
    updateJson = "https://raw.githubusercontent.com/Night-stars-1/qqzygisk/master/update.json"
}

android {
    namespace = "com.qm.qqzygisk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qm.qqzygisk"
        minSdk = 28
        targetSdk = 36
        versionCode = 7
        versionName = "1.8"
        ndk.abiFilters.addAll(arrayOf("armeabi-v7a", "arm64-v8a"))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
//    kotlinOptions {
//        jvmTarget = JavaVersion.VERSION_17.toString()
//    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        resources.excludes.addAll(
            arrayOf(
                "kotlin/**",
                "kotlin-tooling-metadata.json",
                "**.bin",
                "META-INF/xposed/**"
            )
        )
    }
//    applicationVariants.all {
//        val variant = name.capitalizeUS()
//        val copyMagiskProperties = tasks.register("copyMagiskProperties$variant", Copy::class) {
//                val outPath = layout.buildDirectory.dir("intermediates/java_res/${variant.lowercase()}/process${variant}JavaRes/out")
//
//                from(layout.buildDirectory.dir("generated/properties/${variant.lowercase()}"))
//                into(outPath)
//        }
//        val copyMagiskPackages = tasks.register("copyMagiskPackages$variant", Copy::class) {
//            val outPath = layout.buildDirectory.dir("intermediates/java_res/${variant.lowercase()}/process${variant}JavaRes/out/packages")
//
//            from(layout.buildDirectory.dir("generated/packages/${variant.lowercase()}"))
//            into(outPath)
//        }
//        afterEvaluate {
//            copyMagiskProperties.configure {
//                dependsOn(
//                    tasks.named("generateInitialPackages${variant}"),
//                    tasks.named("generateModuleProp${variant}"),
//                    tasks.named("merge${variant}JavaResource")
//                )
//            }
//            copyMagiskPackages.configure {
//                dependsOn(copyMagiskProperties)
//            }
//            tasks.named("package${variant}") {
//                dependsOn(copyMagiskProperties, copyMagiskPackages)
//            }
////            tasks.named("mergeMagisk${variant}") {
////                enabled = false
////            }
//        }
//    }

    androidResources.additionalParameters += listOf("--allow-reserved-package-id", "--package-id", "0x15")
}

/**
 * 解压 Zip 文件到指定目录。
 * @param zipFile 要解压的 Zip 文件。
 * @param destinationDir 解压的目标目录。
 * @throws IOException 如果解压过程中发生错误。
 */
fun unzip(zipFile: File, destinationDir: File) {
    if (!destinationDir.exists()) {
        destinationDir.mkdirs() // 创建目标目录
    }

    ZipFile(zipFile).use { zip ->
        zip.entries().asSequence().forEach { entry ->
            val entryFile = File(destinationDir, entry.name)
            if (entry.isDirectory) {
                entryFile.mkdirs() // 如果是目录，创建目录
            } else {
                entryFile.parentFile?.mkdirs() // 确保父目录存在
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(entryFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
/**
 * 将指定目录下的所有文件和子目录（递归）打包成 Zip 文件。
 * @param sourceDir 要打包的源目录。
 * @param zipFile 生成的 Zip 文件。
 * @param baseDirInZip Zip 文件中内容的根目录名（例如 "module/"）。如果为空，文件直接在 Zip 根目录。
 */
fun zipDirectory(sourceDir: File, zipFile: File, baseDirInZip: String = "") {
    ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
        // 使用 Set 跟踪已添加的 ZipEntry 名称，以防止 duplicate entry
        val addedEntries = mutableSetOf<String>()

        sourceDir.walkTopDown().forEach { file ->
            val relativePath = file.toRelativeString(sourceDir).replace(File.separatorChar, '/')
            val entryName = if (baseDirInZip.isEmpty()) relativePath else "$baseDirInZip/$relativePath"

            // 过滤掉空的目录名 (通常 ZipEntry 不应该以 / 结尾，除非是显式目录)
            if (entryName.isEmpty()) return@forEach // 跳过根目录自身

            // 检查是否已经添加过此条目名，防止 duplicate entry
            if (!addedEntries.add(entryName)) {
                println("警告：跳过重复的 ZipEntry: $entryName")
                return@forEach
            }

            if (file.isDirectory) {
                // 对于目录，只添加目录条目 (ZipEntry name 应该以 / 结尾)
                val dirEntryName = if (entryName.endsWith("/")) entryName else "$entryName/"
                if (addedEntries.add(dirEntryName)) { // 再次检查目录条目是否重复
                    zos.putNextEntry(ZipEntry(dirEntryName))
                    zos.closeEntry()
                }
            } else {
                // 对于文件，添加文件条目并写入内容
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }
}

fun normalizeShellLineEndings(moduleDir: File) {
    moduleDir.walkTopDown()
        .filter { file ->
            file.isFile && (
                file.extension == "sh" ||
                    file.toRelativeString(moduleDir).replace(File.separatorChar, '/') ==
                    "META-INF/com/google/android/update-binary"
                )
        }
        .forEach { file ->
            val content = file.readText(Charsets.UTF_8)
            val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
            if (content != normalized) {
                file.writeText(normalized, Charsets.UTF_8)
            }
        }
}

tasks.withType<Sync>().configureEach {
    if (name.startsWith("mergeMagisk")) {
        from(rootProject.layout.projectDirectory.dir("webroot/dist")) {
            into("webroot")
        }
        doLast {
            normalizeShellLineEndings(destinationDir)
        }
    }
}

tasks.register("MagiskZipTask") {
    description = ""
    doLast {
        val zipFile = File(layout.buildDirectory.asFile.get(), "outputs/magisk/release/qqhook.zip")
        val tempDir = Files.createTempDirectory("module_unzip_").toFile()
        unzip(zipFile, tempDir)
        val apk =
            listOf(
                File(layout.projectDirectory.asFile, "release/app-release.apk"),
                File(layout.buildDirectory.asFile.get(), "outputs/apk/release/app-release.apk"),
                File(layout.buildDirectory.asFile.get(), "outputs/apk/release/app-release-unsigned.apk"),
            ).firstOrNull { it.isFile } ?: error("未找到 app-release.apk")
        apk.copyTo(File(tempDir, "app-release.apk"), overwrite = true)
        File(layout.projectDirectory.asFile, "src/main/resources/customize.sh").copyTo(File(tempDir, "customize.sh"), overwrite = true)
        File(layout.projectDirectory.asFile, "src/main/resources/uninstall.sh").copyTo(File(tempDir, "uninstall.sh"), overwrite = true)
        normalizeShellLineEndings(tempDir)
        zipDirectory(tempDir, zipFile)
        tempDir.deleteOnExit()
    }
}

afterEvaluate {
    tasks.named("assembleRelease") {
        finalizedBy("MagiskZipTask")
    }
}

dependencies {
    implementation(libs.androidx.exifinterface)
    implementation(libs.r8.annotations)
    implementation(libs.androidvmtools)
    implementation(libs.zygote.runtime)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    implementation(libs.browser)
    implementation(libs.material.components)

    implementation(libs.appcompat)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    compileOnly(libs.xposed.api)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation("junit:junit:4.13.2")

//    implementation(files("libs/runtime-release.aar"))
}

//plugins.withId("io.github.nightstars1.ZygoteLoader") {
//    configurations.implementation {
//        exclude(group = "io.github.nightstars1.ZygoteLoader", module = "runtime")
//    }
//}
