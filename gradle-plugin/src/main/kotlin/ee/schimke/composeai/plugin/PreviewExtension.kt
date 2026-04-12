package ee.schimke.composeai.plugin

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class PreviewExtension @Inject constructor(objects: ObjectFactory) {
    val variant: Property<String> = objects.property(String::class.java).convention("debug")
    val sdkVersion: Property<Int> = objects.property(Int::class.java).convention(35)
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    /**
     * When true, each `renderAllPreviews` run archives every rendered PNG whose
     * content differs from the most recent entry into [historyDir]. Default: false.
     */
    val historyEnabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /**
     * Root directory for preview history snapshots. Each preview gets a
     * subfolder named after its id; inside, filenames are timestamps.
     * Lives outside `build/` by default so `./gradlew clean` doesn't wipe it.
     */
    val historyDir: DirectoryProperty = objects.directoryProperty()
}
