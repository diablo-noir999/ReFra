/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.decoder.glide

import com.bumptech.glide.load.Option

/**
 * Glide request option: when set to true, an encrypted vault model is served from its small
 * pre-generated encrypted thumbnail instead of decrypting the whole original file.
 *
 * The grid sets this on every vault request (motion tier always, refined tier for stills).
 * Full-screen viewers never set it, so they keep decoding the original at full resolution.
 */
val VaultThumbnailOption: Option<Boolean> =
    Option.memory("com.dot.gallery.vault.thumbnail", false)
