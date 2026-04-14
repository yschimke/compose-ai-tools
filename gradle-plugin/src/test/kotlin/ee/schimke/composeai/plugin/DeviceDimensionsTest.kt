package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceDimensionsTest {

    @Test
    fun `explicit dimensions override device spec`() {
        val spec = DeviceDimensions.resolve("id:pixel_6", widthDp = 200, heightDp = 300)
        assertThat(spec.widthDp).isEqualTo(200)
        assertThat(spec.heightDp).isEqualTo(300)
    }

    @Test
    fun `known device ID resolves correctly`() {
        val spec = DeviceDimensions.resolve("id:pixel_6")
        assertThat(spec.widthDp).isEqualTo(411)
        assertThat(spec.heightDp).isEqualTo(891)
    }

    @Test
    fun `spec string is parsed`() {
        val spec = DeviceDimensions.resolve("spec:width=320dp,height=480dp")
        assertThat(spec.widthDp).isEqualTo(320)
        assertThat(spec.heightDp).isEqualTo(480)
    }

    @Test
    fun `wear device returns wear defaults`() {
        val spec = DeviceDimensions.resolve("id:wearos_large_round")
        assertThat(spec.widthDp).isEqualTo(227)
        assertThat(spec.heightDp).isEqualTo(227)
    }

    @Test
    fun `unknown wear device returns wear defaults`() {
        val spec = DeviceDimensions.resolve("id:some_wear_device")
        assertThat(spec.widthDp).isEqualTo(227)
        assertThat(spec.heightDp).isEqualTo(227)
    }

    @Test
    fun `null device returns phone defaults`() {
        val spec = DeviceDimensions.resolve(null)
        assertThat(spec.widthDp).isEqualTo(400)
        assertThat(spec.heightDp).isEqualTo(800)
    }
}
