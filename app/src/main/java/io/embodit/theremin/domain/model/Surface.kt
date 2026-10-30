package io.embodit.theremin.domain.model

/** A pointing ray in Stage space. */
data class PointerRay(val origin: Vec3, val direction: Vec3)

/** Why head tracking cannot be used (the provider's support state, mapped). */
enum class BlockReason { NOT_IN_FULL_SPACE, WITHOUT_PERMISSION, DEVICE_NOT_SUPPORTED }
