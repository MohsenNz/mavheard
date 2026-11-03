package com.mavheart.h12

/** ArduCopter `custom_mode` numbers. [selectable] marks the modes the picker offers. */
enum class CopterMode(val id: Int, val label: String, val selectable: Boolean = false) {
    STABILIZE(0, "Stabilize", true),
    ACRO(1, "Acro"),
    ALT_HOLD(2, "AltHold", true),
    AUTO(3, "Auto", true),
    GUIDED(4, "Guided", true),
    LOITER(5, "Loiter", true),
    RTL(6, "RTL", true),
    CIRCLE(7, "Circle", true),
    LAND(9, "Land", true),
    DRIFT(11, "Drift", true),
    SPORT(13, "Sport", true),
    FLIP(14, "Flip"),
    AUTOTUNE(15, "AutoTune"),
    POS_HOLD(16, "PosHold", true),
    BRAKE(17, "Brake", true),
    THROW(18, "Throw"),
    AVOID_ADSB(19, "Avoid ADSB"),
    GUIDED_NOGPS(20, "Guided NoGPS"),
    SMART_RTL(21, "Smart RTL", true),
    FLOW_HOLD(22, "FlowHold"),
    FOLLOW(23, "Follow"),
    ZIGZAG(24, "ZigZag"),
    SYSTEM_ID(25, "SystemID"),
    HELI_AUTOROTATE(26, "Heli Autorotate"),
    AUTO_RTL(27, "Auto RTL");

    companion object {
        fun fromId(id: Int): CopterMode? = entries.firstOrNull { it.id == id }
        val selectableModes: List<CopterMode> = entries.filter { it.selectable }
    }
}

/** Mode name for a Copter's custom_mode, or "Mode N" for a number we don't know. */
fun copterModeName(id: Int): String = CopterMode.fromId(id)?.label ?: "Mode $id"

/** MAV_TYPE values flown by ArduCopter: quad, coax, heli, hexa, octo, tri, dodeca, deca. */
fun isCopterType(mavType: Int): Boolean = mavType in setOf(2, 3, 4, 13, 14, 15, 29, 35)
