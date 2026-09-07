package com.milkcocoa.info.sapphire.core.snapshot

/** Aggregate health state assigned to a disk by a source or evaluation policy. */
enum class DiskHealth {
    /** No issue was reported or assigned by the producing source or policy. */
    GOOD,
    /** The disk is usable but one or more indicators need attention. */
    CAUTION,
    /** At least one indicator represents a disk-health failure. */
    BAD,
    /** There was not enough trustworthy information to classify the disk. */
    UNKNOWN
}
