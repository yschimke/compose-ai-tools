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

package androidx.compose.remote.player.compose.embedded

/**
 * Platform-neutral supertype of [RcImageLoader], so code that only *carries* an image loader does
 * not have to name its platform-typed API.
 *
 * [GraphContext] is the case this exists for. It holds a loader purely as a slot — `RcPlayer` sets
 * one, the canvas draw path reads it back — and never calls it. But `RcImageLoader.loadImage`
 * returns a `State<Drawable?>`, so naming that type pinned the evaluator, and the entire
 * state/expression path reaching it through `LocalGraphContext`, to Android over a field it does
 * not use.
 *
 * The narrowing is deliberate: this interface has no members. Anything that actually *loads* an
 * image casts back to [RcImageLoader], which is honest — decoding an image is genuinely
 * platform-bound, and pretending otherwise behind a neutral signature would just move the problem.
 * When the jvm target grows a real image path, this is the seam it plugs into.
 */
public interface RcImageSource
