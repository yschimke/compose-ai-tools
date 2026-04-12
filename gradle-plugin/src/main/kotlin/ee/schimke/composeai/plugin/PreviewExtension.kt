package ee.schimke.composeai.plugin

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class PreviewExtension @Inject constructor(objects: ObjectFactory) {
    val variant: Property<String> = objects.property(String::class.java).convention("debug")
    val sdkVersion: Property<Int> = objects.property(Int::class.java).convention(35)
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
}
