# Wheel-contact wind API — version 1

`com.axes.pmweather_aeronautics.WheelContactWindApi` is an optional Java hook for
third-party Sable wheel/contact solvers. Its API version is independent of the three
existing packed APIs, which remain version 2.

Call `recordContactImpulse` for each fresh contact after composing the native impulse and
before that contact is submitted to Sable. Values are in body-local coordinates. Pass the
contact application point, a finite unit surface normal, the signed native normal impulse
(positive means compression), the native friction coefficient, and the complete native
combined impulse at that contact. A stable key unique within the body and physics substep
is required; equality is used, so an immutable block position can be used as a key.

The method returns `true` only while PMWeather Aeronautics has prepared the matching Sable
physics substep. Records are discarded after that substep and are never replayed from an
older contact. Invalid values, a stale body/step, or a call outside the active preparation
window returns `false`.

Weather breakaway handling is conditional. It compares aggregate projected weather impulse
demand against positive compression multiplied by the supplied friction values. The
weather demand is the net current aerodynamic pressure impulse minus the same-point
zero-weather baseline; both vectors are aggregated and capped before subtraction. If
demand does not exceed grip, the native contact impulse is unchanged. If it does, the
contact's original tangential component is limited to `friction × positive normal impulse`
and an additive correction is queued at the same point in the registered weather-wind
force group. The normal component is never rewritten. Body-pressure torque is not part of
the aggregate breakaway gate.

The API cannot verify that an addon supplied a physically correct friction coefficient or
complete native impulse. Independent integrations must audit their own call site and
contact semantics.
