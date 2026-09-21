package de.miraculixx.chunkeditor.data

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

/**
 * 1.21's NBT getters answer a value whether or not the key is set, so the defaulting ones the
 * chunk reader is written against are reproduced here rather than spelled out at every call.
 */

fun CompoundTag.getStringOr(key: String, default: String): String =
    if (contains(key, Tag.TAG_STRING.toInt())) getString(key) else default

fun CompoundTag.getIntOr(key: String, default: Int): Int =
    if (contains(key, Tag.TAG_INT.toInt())) getInt(key) else default

fun CompoundTag.getLongOr(key: String, default: Long): Long =
    if (contains(key, Tag.TAG_LONG.toInt())) getLong(key) else default

fun CompoundTag.getDoubleOr(key: String, default: Double): Double =
    if (contains(key, Tag.TAG_DOUBLE.toInt())) getDouble(key) else default

fun CompoundTag.getByteOr(key: String, default: Byte): Byte =
    if (contains(key, Tag.TAG_BYTE.toInt())) getByte(key) else default

fun CompoundTag.getCompoundOrEmpty(key: String): CompoundTag = getCompound(key)

/** The element type is only known to the caller, so the tag is taken as it lies */
fun CompoundTag.getListOrEmpty(key: String): ListTag = get(key) as? ListTag ?: ListTag()

fun ListTag.getStringOr(index: Int, default: String): String =
    if (index in indices) getString(index) else default

fun ListTag.getCompoundOrEmpty(index: Int): CompoundTag = getCompound(index)

fun ListTag.getDoubleOr(index: Int, default: Double): Double =
    if (index in indices) getDouble(index) else default

/** 1.21 answers 0 for an unset int, so "was it there" needs the type check */
fun CompoundTag.intOrNull(key: String): Int? =
    if (contains(key, Tag.TAG_INT.toInt())) getInt(key) else null
