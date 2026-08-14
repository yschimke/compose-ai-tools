/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("RestrictedApiAndroidX")

package androidx.compose.remote.player.compose.embedded

import androidx.compose.remote.core.RemoteClock
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.core.VariableSupport
import androidx.compose.remote.core.operations.FloatExpression
import androidx.compose.remote.core.operations.ShaderData
import androidx.compose.remote.core.operations.utilities.ArrayAccess
import androidx.compose.remote.core.operations.utilities.DataMap
import androidx.compose.remote.core.types.LongConstant

/**
 * A [RemoteContext] whose entire state lives in the shared [RemoteContext.mRemoteComposeState]
 * store and the [RemoteContext.mDocument] — with **no platform dependency at all**.
 *
 * ## Why this exists
 *
 * [GraphContext] previously extended `AndroidRemoteContext`, which pinned it — and therefore the
 * whole state/expression path that reaches it through `LocalGraphContext` — to `androidMain`. It
 * never needed to: it overrides every platform-bound member away (`loadBitmap` and `loadShader` are
 * empty bodies) and shares the store explicitly.
 *
 * ## Why this is a safe substitution
 *
 * `AndroidRemoteContext` is **not really an Android class**. Of its 63 methods, exactly five touch
 * the platform, and four of those (`setAndroidContext`, `setBitmapLoader`, `setTypefaceResolver`,
 * `useCanvas`) are its own API rather than the [RemoteContext] contract. The only *contract* method
 * that touches Android is [loadBitmap], which decodes to an `android.graphics.Bitmap`.
 *
 * Everything else — the reads, the writes, the named-override family, `updateOps`, `listensTo`,
 * `runAction`, `hapticEffect` — is delegation to the core store or the core document. So the bodies
 * below are ports of upstream's, not reimplementations: each mirrors `AndroidRemoteContext` at the
 * commit pinned in `PROVENANCE.md`, so a context built on this resolves values identically. That
 * matters more than it looks — [GraphContext]'s leaf reads call `super.getFloat`/`getText`/… , so a
 * divergence here would change every computed value, which is every pixel.
 *
 * [loadBitmap] is the one method that cannot come along; it defaults to a no-op here, which is what
 * [GraphContext] already overrode it to. A subclass that genuinely needs image decode overrides it.
 *
 * Keep this in sync with upstream on a snapshot refresh, the same as the vendored sources.
 */
internal abstract class StoreBackedRemoteContext(clock: RemoteClock) : RemoteContext(clock) {

  /** Variable-name registry backing [getVariableId] and the named-override family. */
  private val varNames = HashMap<String, ArrayList<VarName>>()

  private class VarName(@JvmField val name: String, @JvmField val id: Int, @JvmField val type: Int)

  // ---------------------------------------------------------------- reads

  override fun getFloat(id: Int): Float = mRemoteComposeState.getFloat(id)

  override fun getInteger(id: Int): Int = mRemoteComposeState.getInteger(id)

  override fun getColor(id: Int): Int = mRemoteComposeState.getColor(id)

  override fun getText(id: Int): String? = mRemoteComposeState.getFromId(id) as String?

  override fun getLong(id: Int): Long =
    (mRemoteComposeState.getObject(id) as LongConstant).getValue()

  override fun getObject(id: Int): Any? = mRemoteComposeState.getObject(id)

  override fun getDataMap(id: Int): DataMap? = mRemoteComposeState.getDataMap(id)

  override fun getShader(id: Int): ShaderData? = mRemoteComposeState.getFromId(id) as ShaderData?

  override fun getPathData(instanceId: Int): FloatArray? =
    mRemoteComposeState.getPathData(instanceId)

  // --------------------------------------------------------------- writes

  override fun loadFloat(id: Int, value: Float) {
    mRemoteComposeState.updateFloat(id, value)
  }

  override fun loadInteger(id: Int, value: Int) {
    mRemoteComposeState.updateInteger(id, value)
  }

  override fun loadColor(id: Int, color: Int) {
    mRemoteComposeState.updateColor(id, color)
  }

  override fun loadText(id: Int, text: String) {
    // Upstream distinguishes first write from update; `updateData` on an absent id would not
    // register it.
    if (!mRemoteComposeState.containsId(id)) {
      mRemoteComposeState.cacheData(id, text)
    } else {
      mRemoteComposeState.updateData(id, text)
    }
  }

  override fun loadPathData(instanceId: Int, winding: Int, floatPath: FloatArray) {
    mRemoteComposeState.putPathData(instanceId, floatPath)
    mRemoteComposeState.putPathWinding(instanceId, winding)
  }

  override fun loadShader(id: Int, value: ShaderData) {
    mRemoteComposeState.cacheData(id, value)
  }

  override fun loadAnimatedFloat(id: Int, animatedFloat: FloatExpression) {
    mRemoteComposeState.cacheData(id, animatedFloat)
  }

  override fun addCollection(id: Int, collection: ArrayAccess) {
    mRemoteComposeState.addCollection(id, collection)
  }

  override fun putObject(id: Int, value: Any) {
    mRemoteComposeState.updateObject(id, value)
  }

  override fun putDataMap(id: Int, map: DataMap) {
    mRemoteComposeState.putDataMap(id, map)
  }

  /**
   * No-op: decoding bytes to an image is the one genuinely platform-bound member of the contract.
   * [GraphContext] overrides it to nothing anyway — it evaluates values and never paints.
   */
  override fun loadBitmap(
    imageId: Int,
    encoding: Short,
    type: Short,
    width: Int,
    height: Int,
    data: ByteArray,
  ) {}

  // ------------------------------------------------------------ evaluation

  override fun listensTo(id: Int, variableSupport: VariableSupport) {
    mRemoteComposeState.listenToVar(id, variableSupport)
  }

  override fun updateOps(): Int = mRemoteComposeState.getOpsToUpdate(this, currentTime)

  override fun markVariableDirty(id: Int) {
    mRemoteComposeState.markVariableDirty(id)
  }

  // -------------------------------------------------------------- overrides

  override fun overrideFloat(id: Int, value: Float) {
    mRemoteComposeState.overrideFloat(id, value)
  }

  override fun overrideInteger(id: Int, value: Int) {
    mRemoteComposeState.overrideInteger(id, value)
  }

  override fun overrideText(id: Int, text: Int) {
    mRemoteComposeState.overrideData(id, text)
  }

  private fun overrideData(id: Int, value: Any) {
    mRemoteComposeState.overrideData(id, value)
  }

  private fun clearDataOverride(id: Int) {
    mRemoteComposeState.clearDataOverride(id)
  }

  private fun clearFloatOverride(id: Int) {
    mRemoteComposeState.clearFloatOverride(id)
  }

  private fun clearIntegerOverride(id: Int) {
    mRemoteComposeState.clearIntegerOverride(id)
  }

  // --------------------------------------------------------- variable names

  override fun loadVariableName(varName: String, varId: Int, varType: Int) {
    val list = varNames.getOrPut(varName) { ArrayList() }
    // Avoid duplicates if re-initializing the same document.
    if (list.none { it.id == varId }) {
      list.add(VarName(varName, varId, varType))
    }
  }

  override fun clearVariables() {
    varNames.clear()
  }

  /** Mirrors upstream, including throwing rather than returning a sentinel for an unknown name. */
  fun getVariableId(name: String): Int {
    val list = varNames[name]
    if (list.isNullOrEmpty()) {
      throw NoSuchElementException("Variable $name not found")
    }
    return list[0].id
  }

  private inline fun forEachNamed(name: String, action: (Int) -> Unit) {
    varNames[name]?.forEach { action(it.id) }
  }

  override fun setNamedStringOverride(stringName: String, value: String) =
    forEachNamed(stringName) { overrideData(it, value) }

  override fun clearNamedStringOverride(stringName: String) =
    forEachNamed(stringName) { clearDataOverride(it) }

  override fun setNamedFloatOverride(floatName: String, value: Float) =
    forEachNamed(floatName) { mRemoteComposeState.overrideFloat(it, value) }

  override fun clearNamedFloatOverride(floatName: String) =
    forEachNamed(floatName) { clearFloatOverride(it) }

  override fun setNamedIntegerOverride(integerName: String, value: Int) =
    forEachNamed(integerName) { mRemoteComposeState.overrideInteger(it, value) }

  override fun clearNamedIntegerOverride(integerName: String) =
    forEachNamed(integerName) { clearIntegerOverride(it) }

  override fun setNamedBooleanOverride(booleanName: String, value: Boolean) =
    setNamedIntegerOverride(booleanName, if (value) 1 else 0)

  override fun clearNamedBooleanOverride(booleanName: String) =
    clearNamedIntegerOverride(booleanName)

  override fun setNamedColorOverride(colorName: String, color: Int) =
    forEachNamed(colorName) { mRemoteComposeState.overrideColor(it, color) }

  override fun setNamedDataOverride(dataName: String, value: Any) =
    forEachNamed(dataName) { overrideData(it, value) }

  override fun clearNamedDataOverride(dataName: String) =
    forEachNamed(dataName) { clearDataOverride(it) }

  override fun setNamedLong(name: String, value: Long) =
    forEachNamed(name) { (mRemoteComposeState.getObject(it) as LongConstant).setValue(value) }

  // ------------------------------------------------------------ host actions

  override fun runAction(id: Int, metadata: String) {
    mDocument.performClick(this, id, metadata)
  }

  override fun runNamedAction(id: Int, value: Any?) {
    val text = getText(id)
    if (text != null) {
      mDocument.runNamedAction(text, value)
    }
  }

  override fun addClickArea(
    id: Int,
    contentDescriptionId: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    metadataId: Int,
  ) {
    val contentDescription = mRemoteComposeState.getFromId(contentDescriptionId) as String?
    val metadata = mRemoteComposeState.getFromId(metadataId) as String?
    mDocument.addClickArea(id, contentDescription, left, top, right, bottom, metadata)
  }

  override fun hapticEffect(type: Int) {
    mDocument.haptic(type)
  }
}
