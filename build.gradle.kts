plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

tasks.register("bumpVersion") {
    description = "Increment versionCode and patch version in version.properties"
    doLast {
        val propsFile = file("version.properties")
        val lines = propsFile.readLines()
        val map = lines.filter { it.contains("=") && !it.startsWith("#") }.associate {
            val (k, v) = it.split("=", limit = 2)
            k.trim() to v.trim()
        }.toMutableMap()
        val oldCode = map["versionCode"]?.toInt() ?: 1
        val oldName = map["versionName"] ?: "1.$oldCode"
        val newCode = oldCode + 1
        val newName = "1.$newCode"
        propsFile.writeText("versionCode=$newCode\nversionName=$newName\n")
        println("Version bumped: $oldName ($oldCode) -> $newName ($newCode)")
        println("IMPORTANT: Run assembleDebug as a SEPARATE command to pick up the new version.")
        println("  ./gradlew bumpVersion && ./gradlew assembleDebug")
    }
}
