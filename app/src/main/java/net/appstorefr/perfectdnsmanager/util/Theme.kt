package net.appstorefr.perfectdnsmanager.util

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import net.appstorefr.perfectdnsmanager.R

/**
 * Accès aux couleurs du thème PDM. Suit automatiquement le mode dark/light du système
 * via les ressources values/ et values-night/.
 *
 * Utilisable en deux formes :
 *   - dans une Activity : `pdmAccent()` (Activity = Context)
 *   - dans un `apply {}` sur une View : `pdmAccent()` (View receiver, via extension sur View)
 */

// Receiver : Context (Activity, Service, etc.)
fun Context.pdmBackground(): Int = ContextCompat.getColor(this, R.color.pdm_background)
fun Context.pdmSurface(): Int = ContextCompat.getColor(this, R.color.pdm_surface)
fun Context.pdmSurfaceElevated(): Int = ContextCompat.getColor(this, R.color.pdm_surface_elevated)
fun Context.pdmSurfaceInput(): Int = ContextCompat.getColor(this, R.color.pdm_surface_input)
fun Context.pdmBorder(): Int = ContextCompat.getColor(this, R.color.pdm_border)
fun Context.pdmTextPrimary(): Int = ContextCompat.getColor(this, R.color.pdm_text_primary)
fun Context.pdmTextSecondary(): Int = ContextCompat.getColor(this, R.color.pdm_text_secondary)
fun Context.pdmTextDisabled(): Int = ContextCompat.getColor(this, R.color.pdm_text_disabled)
fun Context.pdmTextOnAccent(): Int = ContextCompat.getColor(this, R.color.pdm_text_on_accent)
fun Context.pdmAccent(): Int = ContextCompat.getColor(this, R.color.pdm_accent)
fun Context.pdmAccentPressed(): Int = ContextCompat.getColor(this, R.color.pdm_accent_pressed)
fun Context.pdmAccentAlt(): Int = ContextCompat.getColor(this, R.color.pdm_accent_alt)
fun Context.pdmAccentInfo(): Int = ContextCompat.getColor(this, R.color.pdm_accent_info)
fun Context.pdmAccentSupport(): Int = ContextCompat.getColor(this, R.color.pdm_accent_support)
fun Context.pdmAccentGold(): Int = ContextCompat.getColor(this, R.color.pdm_accent_gold)
fun Context.pdmAccentPurple(): Int = ContextCompat.getColor(this, R.color.pdm_accent_purple)
fun Context.pdmDanger(): Int = ContextCompat.getColor(this, R.color.pdm_danger)
fun Context.pdmWarning(): Int = ContextCompat.getColor(this, R.color.pdm_warning)
fun Context.pdmTintNeutral(): Int = ContextCompat.getColor(this, R.color.pdm_tint_neutral)
fun Context.pdmTintAccent(): Int = ContextCompat.getColor(this, R.color.pdm_tint_accent)
fun Context.pdmTintInfo(): Int = ContextCompat.getColor(this, R.color.pdm_tint_info)
fun Context.pdmTintWarning(): Int = ContextCompat.getColor(this, R.color.pdm_tint_warning)
fun Context.pdmTintDanger(): Int = ContextCompat.getColor(this, R.color.pdm_tint_danger)

// Receiver : View (utilisable directement dans un apply { } sur une View)
fun View.pdmBackground(): Int = context.pdmBackground()
fun View.pdmSurface(): Int = context.pdmSurface()
fun View.pdmSurfaceElevated(): Int = context.pdmSurfaceElevated()
fun View.pdmSurfaceInput(): Int = context.pdmSurfaceInput()
fun View.pdmBorder(): Int = context.pdmBorder()
fun View.pdmTextPrimary(): Int = context.pdmTextPrimary()
fun View.pdmTextSecondary(): Int = context.pdmTextSecondary()
fun View.pdmTextDisabled(): Int = context.pdmTextDisabled()
fun View.pdmTextOnAccent(): Int = context.pdmTextOnAccent()
fun View.pdmAccent(): Int = context.pdmAccent()
fun View.pdmAccentPressed(): Int = context.pdmAccentPressed()
fun View.pdmAccentAlt(): Int = context.pdmAccentAlt()
fun View.pdmAccentInfo(): Int = context.pdmAccentInfo()
fun View.pdmAccentSupport(): Int = context.pdmAccentSupport()
fun View.pdmAccentGold(): Int = context.pdmAccentGold()
fun View.pdmAccentPurple(): Int = context.pdmAccentPurple()
fun View.pdmDanger(): Int = context.pdmDanger()
fun View.pdmWarning(): Int = context.pdmWarning()
fun View.pdmTintNeutral(): Int = context.pdmTintNeutral()
fun View.pdmTintAccent(): Int = context.pdmTintAccent()
fun View.pdmTintInfo(): Int = context.pdmTintInfo()
fun View.pdmTintWarning(): Int = context.pdmTintWarning()
fun View.pdmTintDanger(): Int = context.pdmTintDanger()
