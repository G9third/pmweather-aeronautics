package com.axes.pmweather_aeronautics;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3dc;

/**
 * Optional integration hook for third-party Sable wheel/contact solvers.
 *
 * Call once for each contact after the native impulse has been fully composed and before it is
 * submitted to Sable. Records are accepted only while PMWeather Aeronautics has prepared the
 * matching physics substep; contacts are never retained for a later tick.
 */
public final class WheelContactWindApi {
    public static final int API_VERSION = 1;

    private WheelContactWindApi() {
    }

    /**
     * Records a native contact impulse for conditional weather breakaway handling.
     *
     * @param body current Sable rigid body
     * @param contact stable unique key for this contact within the current body/substep; equality
     *                semantics are used, so a block position can serve as the key
     * @param bodyLocalPosition application point in the body's local coordinates
     * @param bodyLocalNormal unit surface normal in the body's local coordinates
     * @param signedNormalImpulse native normal impulse along the supplied normal; positive is
     *                            compression and negative tension is preserved as a signed value
     * @param friction native friction coefficient for this contact
     * @param nativeCombinedImpulse complete native contact impulse in the body's local coordinates
     * @return true when this current prepared physics substep accepted the contact record
     */
    public static boolean recordContactImpulse(final ServerSubLevel body,
                                               final Object contact,
                                               final Vector3dc bodyLocalPosition,
                                               final Vector3dc bodyLocalNormal,
                                               final double signedNormalImpulse,
                                               final double friction,
                                               final Vector3dc nativeCombinedImpulse) {
        final boolean accepted = WeatherForceApplier.recordWheelContactImpulse(
                body,
                contact,
                bodyLocalPosition,
                bodyLocalNormal,
                signedNormalImpulse,
                friction,
                nativeCombinedImpulse
        );
        if (body != null) IntegrationHealth.contact(body.getLevel().getServer().getTickCount(), accepted);
        return accepted;
    }
}
