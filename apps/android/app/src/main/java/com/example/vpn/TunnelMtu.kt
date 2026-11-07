package com.example.vpn

/**
 * The MTU handed to the tunnel interface.
 *
 * It used to be the constant 1420, which was measured to be larger than the link underneath it: on
 * the operator's own LTE connection the interface reports 1358, and everything above that is not
 * slowed down but discarded outright — a 1378-byte packet came back as 100% loss while 1358 passed.
 * A path that refuses fragments punishes an oversized inner packet twice: the payload is lost, and
 * the recovery is a retransmission timer walking up to tens of seconds.
 *
 * So the tunnel asks the network it is actually sitting on and leaves room for the record layer that
 * wraps it, instead of assuming the Ethernet number.
 */
object TunnelMtu {

    /**
     * Bytes the encrypted tunnel adds around an inner packet: outer IP and TCP, the TLS record and
     * framing, and the xray transport header. Generous on purpose — under-sizing this is the exact
     * mistake that produced the measurement above.
     */
    const val ENCAPSULATION_BYTES = 120

    /**
     * Offered when the underlying link cannot be asked at all: the number the measured cellular link
     * produces, which is the tightest common case worth guessing blind. Ethernet's 1500 is not the
     * default, because that is the assumption that produced the measurement above.
     */
    const val FALLBACK = 1238

    /**
     * Never asked to go below this. A link this tight is already abnormal, and a TLS record has to
     * fit somewhere; the accepted cost of the floor is that such a link may still fragment.
     */
    const val HARD_FLOOR = 1180

    /** Never offered above this, even on a 1500-byte link: the further hops are not ours to see. */
    const val MAXIMUM = 1400

    /** [linkMtu] of the network underneath, or 0/-1 when it is unknown. */
    fun forLink(linkMtu: Int): Int =
        if (linkMtu <= 0) {
            FALLBACK
        } else {
            (linkMtu - ENCAPSULATION_BYTES)
                .coerceAtLeast(HARD_FLOOR)
                .coerceAtMost(MAXIMUM)
        }
}
