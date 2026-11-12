package rocks.gorjan.gokixp.apps.mm2cars

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The toy itself: Midtown Madness 2's cars on a two-lane San Francisco street along the
 * taskbar, with its jump trailers for ramps and its race checkpoints to drive through.
 *
 * The far lane drives right, the near lane left, and a car that leaves the screen comes back
 * the other way. Ramps grow out of the road now and then; cars that see one floor it, take
 * off, and pick a stunt. Nothing about how a stunt ends is scripted. Every car is a rigid
 * body standing on springs at its wheels and at the corners of its body, so a flip that comes
 * round lands on its wheels and drives on, and one that doesn't lands on its roof - and a car
 * that gets hit hard enough goes over to MM2's damaged paint, sheds the pieces MM2 lets break
 * off, and when it is done for, burns, loses its wheels, and is replaced.
 *
 * Everything here runs on TaskbarCarsView's render thread; [draw] hands it all to a
 * [CarsRenderer]. World units are metres: X along the taskbar, Y up, Z out of the screen.
 */
internal class CarsWorld(
    models: List<Mm2CarModel>,
    /** MM2's race checkpoint, pt_check. */
    private val gateModel: Mm2Prop,
    /** MM2's jump trailer, sp_jumptrailer_f: the ramps. */
    private val trailerModel: Mm2Prop,
    /** The San Francisco street: road, sidewalks, trees, Victorian row houses. */
    private val street: Mm2Street,
    private val sounds: CarSounds,
    private val camera: Camera
) {
    private val halfWorld get() = camera.halfWorld

    private val cars = ArrayList<Car>()
    private val debris = ArrayList<Debris>()
    private val ramps = ArrayList<Ramp>()
    private val particles = ArrayList<Particle>()
    private var nextRampIn = 4f
    private val gates = ArrayList<Gate>()
    private var nextGateIn = 6f
    private var derbyLeft = 0f

    /** Scratch rotation for whatever is being drawn. */
    private val pm = FloatArray(9)

    init {
        models.forEachIndexed { i, model -> cars.add(Car(model, i)) }
    }

    /** The camera has a strip to look at (again): put the cars on the road, and lay the street out to fit. */
    fun layout() {
        if (camera.width <= 0f) return
        // Spread the cars out the first time there is a road to put them on
        if (cars.all { it.state == State.WAITING }) {
            cars.forEachIndexed { i, car -> car.respawn(entering = i % 2 == 0, offset = i * 7f) }
        }
        if (streetGround == null || halfWorld > builtHalf) buildStreet()
    }

    // ---------------------------------------------------------------- toy controls

    /** A ramp, now, somewhere a car is about to find it. */
    fun buildRamp() {
        spawnRamp(force = true)
    }

    /** A checkpoint gate, now - in place of the one there is, as there is only ever one. */
    fun buildGate() {
        gates.clear()
        spawnGate(force = true)
    }

    /** Every car into the same lane, flat out, for a while. */
    fun demolitionDerby() {
        derbyLeft = DERBY_SECONDS
        for (car in cars) if (car.state == State.DRIVING) car.cruise = car.jumpSpeed
    }

    val carCount get() = cars.size

    /**
     * Where each car is on screen, left, top, right, bottom in strip pixels - NaN for one
     * that can't be kicked just now.
     */
    fun carRects(out: FloatArray) {
        cars.forEachIndexed { i, car ->
            val live = car.state == State.DRIVING || car.state == State.WRECKED
            out[i * 4] = if (live) car.screenLeft else Float.NaN
            out[i * 4 + 1] = car.screenTop
            out[i * 4 + 2] = car.screenRight
            out[i * 4 + 3] = car.screenBottom
        }
    }

    /** A tap on car [index]: it takes off. */
    fun kick(index: Int) {
        val car = cars.getOrNull(index) ?: return
        if (car.state == State.DRIVING || car.state == State.WRECKED) car.kick()
    }

    // ---------------------------------------------------------------- frame

    fun step(dt: Float) {
        if (derbyLeft > 0f) {
            derbyLeft -= dt
            if (derbyLeft <= 0f) for (car in cars) car.cruise = car.normalCruise
        }

        nextRampIn -= dt
        if (nextRampIn <= 0f) {
            nextRampIn = Random.nextFloat() * 8f + 7f
            if (ramps.size < 2) spawnRamp(force = false)
        }
        ramps.removeAll { it.age += dt; it.age > it.life }

        nextGateIn -= dt
        if (nextGateIn <= 0f) {
            nextGateIn = Random.nextFloat() * 6f + 6f
            if (gates.isEmpty()) spawnGate(force = false)
        }
        gates.removeAll { it.age += dt; it.age > it.life }
        for (car in cars) { car.lastX = car.x; car.lastZ = car.z }

        for (car in cars) car.think(dt)

        val sub = dt / SUBSTEPS
        repeat(SUBSTEPS) {
            for (car in cars) car.clearForces()
            for (car in cars) car.groundForces(sub)
            collideCars()
            for (car in cars) car.integrate(sub)
        }
        for (car in cars) car.afterStep(dt)
        for (gate in gates) for (car in cars) if (car.drawn) gate.check(car)
        for (car in cars) if (car.drawn) car.measure()

        val d = debris.iterator()
        while (d.hasNext()) if (!d.next().step(dt)) d.remove()
        val p = particles.iterator()
        while (p.hasNext()) if (!p.next().step(dt)) p.remove()
    }

    /** Everything, to [r]: the street, then ramps, the gate, cars and their pieces, then the effects. */
    fun draw(r: CarsRenderer) {
        identity(pm)
        streetGround?.let { r.mesh(it, street.atlas, pm, 0f, 0f, 0f) }
        streetHouses?.let { r.mesh(it, street.atlas, pm, 0f, 0f, 0f) }
        // Trees are crossed cards, seen from either side
        streetTrees?.let { r.mesh(it, street.atlas, pm, 0f, 0f, 0f, cull = false, cutout = true) }
        for (ramp in ramps) drawRamp(r, ramp)
        for (gate in gates) drawGate(r, gate)
        for (car in cars) if (car.drawn) car.draw(r)
        for (d in debris) d.draw(r)
        drawParticles(r)
    }

    // ---------------------------------------------------------------- ramps

    private fun spawnRamp(force: Boolean) {
        repeat(if (force) 20 else 6) {
            val lane = Random.nextInt(2)
            val dir = laneDir(lane)
            // Near the edge the lane's traffic comes in from, so they land on the screen
            val inset = 2f + Random.nextFloat() * max(0f, halfWorld - 20f)
            val ramp = Ramp(-dir * (halfWorld - inset), laneZ(lane), dir, Random.nextFloat() * 0.09f + 0.33f)
            val lo = min(ramp.start, ramp.end) - 2f
            val hi = max(ramp.start, ramp.end) + 2f
            val blocked = ramps.any { abs(it.z - ramp.z) < 1f && max(it.start, it.end) > lo && min(it.start, it.end) < hi } ||
                cars.any { it.state != State.WAITING && abs(it.z - ramp.z) < 1.6f && it.x > lo - 2f && it.x < hi + 2f }
            if (!blocked) {
                ramps.add(ramp)
                return
            }
        }
    }

    /**
     * MM2's jump trailer shrunk to a toy by [scale], its foot at [start], rising along [dir].
     * Cars drive on its real deck (the exporter's profile), and only off its lip count as
     * taking off. Tuned in scripts/mm2cars_sim, which has the same profile.
     */
    private inner class Ramp(val start: Float, val z: Float, val dir: Float, val scale: Float) {
        var age = 0f
        val life = Random.nextFloat() * 8f + 18f

        /** The deck, foot to lip, as x, height pairs - less the little bevel at the foot, which only kicks. */
        private val points: FloatArray = run {
            val p = trailerModel.profile
            val out = FloatArray(p.size * 2 - 2)
            for (i in 2 until p.size) { out[(i - 1) * 2] = p[i][0] * scale; out[(i - 1) * 2 + 1] = p[i][1] * scale }
            out
        }
        val length = points[points.size - 2]
        val end get() = start + dir * length
        private val footSlope = points[3] / points[2]

        /** Grows out of the road with a little overshoot, sinks back into it at the end. */
        val grow: Float
            get() {
                val g = (age / 0.6f).coerceIn(0f, 1f)
                val back = 1f + 1.8f * (g - 1f) * (g - 1f) * (g - 1f) + 0.8f * (g - 1f) * (g - 1f)
                return back * ((life - age) / 0.8f).coerceIn(0f, 1f)
            }

        private fun segment(along: Float): Int {
            var i = 1
            while (i < points.size / 2 - 1 && along > points[i * 2]) i++
            return i
        }

        /**
         * Where the road meets the foot, a curve tangent to both, RAMP_FILLET long and centred
         * on the foot (a few cm off the drawn deck at most): without it the front wheels hit a kink.
         */
        fun heightAt(along: Float): Float {
            val f = RAMP_FILLET / 2f
            if (along < f) { val u = max(0f, along + f); return grow * footSlope * u * u / (4f * f) }
            val i = segment(along)
            val ax = points[i * 2 - 2]; val ay = points[i * 2 - 1]; val bx = points[i * 2]; val by = points[i * 2 + 1]
            return grow * (ay + (by - ay) * (along - ax) / (bx - ax))
        }

        fun slopeAt(along: Float): Float {
            val f = RAMP_FILLET / 2f
            if (along < f) return grow * footSlope * max(0f, along + f) / (2f * f)
            val i = segment(along)
            return grow * (points[i * 2 + 1] - points[i * 2 - 1]) / (points[i * 2] - points[i * 2 - 2])
        }
    }

    // ---------------------------------------------------------------- checkpoints

    private fun spawnGate(force: Boolean) {
        repeat(if (force) 20 else 6) {
            // Often where cars come down off a ramp, so the jumps go through it
            val ramp = ramps.randomOrNull()
            val x = if (ramp != null && Random.nextFloat() < 0.5f) ramp.end + ramp.dir * (Random.nextFloat() * 4f + 5f)
            else (Random.nextFloat() * 2f - 1f) * (halfWorld - 3f).coerceAtLeast(1f)
            if (abs(x) > halfWorld - 1.5f) return@repeat
            if (ramps.any { x > min(it.start, it.end) - 2.5f && x < max(it.start, it.end) + 2.5f }) return@repeat
            gates.add(Gate(x))
            return
        }
    }

    /**
     * MM2's race checkpoint across both lanes. It stands at an angle to the road - square
     * across it, it would be edge on to the camera - so cars visibly go through it.
     */
    private inner class Gate(val x: Float) {
        var age = 0f
        val life = Random.nextFloat() * 10f + 20f
        var passes = 0

        /** Where a car is, either side of the gate's plane. */
        fun side(cx: Float, cz: Float) = (cx - x) * GATE_SPAN_Z - cz * GATE_SPAN_X

        fun check(car: Car) {
            if (abs(car.z) > GATE_SPAN_Z || car.y > GATE_HEIGHT * scale) return
            val before = side(car.lastX, car.lastZ)
            val after = side(car.x, car.z)
            if (before == 0f || (before > 0f) == (after > 0f)) return
            passes++
            sounds.play("checkpoint", ((x / halfWorld) + 1f) / 2f, 0.8f)
        }

        /** Grows out of the road like the ramps do, and sinks back into it. */
        val scale: Float
            get() {
                val grow = (age / 0.5f).coerceIn(0f, 1f)
                val back = 1f + 1.8f * (grow - 1f) * (grow - 1f) * (grow - 1f) + 0.8f * (grow - 1f) * (grow - 1f)
                return back * ((life - age) / 0.6f).coerceIn(0f, 1f)
            }
    }

    // Contact results
    private var hitNx = 0f
    private var hitNy = 1f
    /** The contact was on the top of a ramp's deck, where cars take off from. */
    private var hitOnRamp = false

    /**
     * How far the point is inside the road or a ramp, with the surface normal in hitN*.
     * A car only meets the ramps that rise the way it drives ([dir]); one coming at a ramp
     * from behind goes straight through it. Zero [dir] meets them all.
     */
    private fun penetration(x: Float, y: Float, z: Float, dir: Float): Float {
        var pen = -y
        hitNx = 0f; hitNy = 1f; hitOnRamp = false
        for (ramp in ramps) {
            if (dir != 0f && ramp.dir != dir) continue
            if (abs(z - ramp.z) > RAMP_HALF_WIDTH) continue
            val along = (x - ramp.start) * ramp.dir
            if (along < -RAMP_FILLET / 2f || along > ramp.length) continue
            val slope = ramp.slopeAt(along)
            val n = 1f / sqrt(1f + slope * slope)
            val p = (ramp.heightAt(along) - y) * n
            if (p <= pen) continue
            // Only the top of the deck counts towards taking off
            pen = p; hitNx = -ramp.dir * slope * n; hitNy = n; hitOnRamp = along > ramp.length * 0.75f
        }
        return pen
    }

    private fun groundHeight(x: Float, z: Float, dir: Float = 0f): Float {
        var g = 0f
        for (ramp in ramps) {
            if (dir != 0f && ramp.dir != dir) continue
            if (abs(z - ramp.z) > RAMP_HALF_WIDTH) continue
            val along = (x - ramp.start) * ramp.dir
            if (along < -RAMP_FILLET / 2f || along > ramp.length) continue
            g = max(g, ramp.heightAt(along))
        }
        return g
    }

    // ---------------------------------------------------------------- bodies

    private enum class State { WAITING, DRIVING, WRECKED, BURNING, GONE }

    /** Position, velocity, orientation and spin, with the maths they all share. */
    private open class Body {
        var x = 0f; var y = 0f; var z = 0f
        var vx = 0f; var vy = 0f; var vz = 0f
        var qw = 1f; var qx = 0f; var qy = 0f; var qz = 0f
        var wx = 0f; var wy = 0f; var wz = 0f
        /** Rotation, row major. Columns are the body's right, up and back. */
        val m = FloatArray(9)

        fun setYaw(angle: Float) {
            qw = cos(angle / 2f); qx = 0f; qy = sin(angle / 2f); qz = 0f
            updateMatrix()
        }

        fun spin(dt: Float) {
            val h = 0.5f * dt
            val nw = qw + h * (-wx * qx - wy * qy - wz * qz)
            val nx = qx + h * (wx * qw + wy * qz - wz * qy)
            val ny = qy + h * (wy * qw + wz * qx - wx * qz)
            val nz = qz + h * (wz * qw + wx * qy - wy * qx)
            val len = sqrt(nw * nw + nx * nx + ny * ny + nz * nz)
            qw = nw / len; qx = nx / len; qy = ny / len; qz = nz / len
            updateMatrix()
        }

        fun updateMatrix() {
            val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
            val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
            val wxq = qw * qx; val wyq = qw * qy; val wzq = qw * qz
            m[0] = 1 - 2 * (yy + zz); m[1] = 2 * (xy - wzq); m[2] = 2 * (xz + wyq)
            m[3] = 2 * (xy + wzq); m[4] = 1 - 2 * (xx + zz); m[5] = 2 * (yz - wxq)
            m[6] = 2 * (xz - wyq); m[7] = 2 * (yz + wxq); m[8] = 1 - 2 * (xx + yy)
        }

        /** Spin about a world axis, added to what it already has. */
        fun addSpin(ax: Float, ay: Float, az: Float, rate: Float) {
            wx += ax * rate; wy += ay * rate; wz += az * rate
        }
    }

    private inner class Car(val model: Mm2CarModel, val slot: Int) : Body() {
        var state = State.WAITING
        private var stateTime = 0f

        val normalCruise = CRUISE[slot % CRUISE.size]
        var cruise = normalCruise
        val jumpSpeed = 18f + slot * 0.8f
        private var dir = 1f
        private var lane = 1
        private var overtaking = 0f
        private var target = 0f

        var health = 100f
        private var charred = 1f
        private var alpha = 1f
        private val attached = BooleanArray(model.parts.size)
        private val wheelSpin = FloatArray(model.parts.size)

        // Centre of mass in model space; everything below is relative to it
        private val comX = (model.bounds[0] + model.bounds[3]) / 2f
        private val comY = model.bounds[1] + model.height * 0.38f
        private val comZ = (model.bounds[2] + model.bounds[5]) / 2f
        private val invIx: Float
        private val invIy: Float
        private val invIz: Float

        /** x, y, z per contact point: the four tyres' footprints, then the body's corners. */
        private val points: FloatArray
        private val pointWheel: IntArray
        private val inContact: BooleanArray
        /** Three spheres along the car, nose to tail, for bumping into the others. */
        val spheres: FloatArray
        val sphereRadius: Float

        // Force accumulators for the substep
        private var fx = 0f; private var fy = 0f; private var fz = 0f
        private var tx = 0f; private var ty = 0f; private var tz = 0f
        private var wheelsDown = 0
        private var bodyDown = 0
        private var onRamp = false
        private var wasOnRamp = false
        private var airTime = 0f
        /** Air time at which a stunt is through and the car levels out, and when it lands. */
        private var levelAt = -1f
        private var landAt = 0f
        private var wreckTimer = 0f
        /** Where the car last got somewhere from, and how long ago. */
        private var progressX = 0f
        private var stuckTimer = 0f
        private var lastSound = 0f
        private var burnEmit = 0f

        /** Where it was before this frame's step, for the checkpoints. */
        var lastX = 0f
        var lastZ = 0f

        // Where the car was drawn, for taps
        var screenLeft = 0f; var screenTop = 0f; var screenRight = 0f; var screenBottom = 0f

        init {
            val l = model.length; val w = model.width; val h = model.height
            invIx = 12f / ((h * h + l * l) * INERTIA_SCALE)
            invIy = 12f / ((w * w + l * l) * INERTIA_SCALE)
            invIz = 12f / ((w * w + h * h) * INERTIA_SCALE)

            val pts = ArrayList<Float>()
            val wheelOf = ArrayList<Int>()
            model.parts.forEachIndexed { i, part ->
                if (part.kind == Mm2CarModel.Kind.WHEEL) {
                    pts += part.pivot[0] - comX; pts += part.pivot[1] - part.radius - comY; pts += part.pivot[2] - comZ
                    wheelOf += i
                }
            }
            val b = model.bounds
            val sill = b[1] + 0.28f - comY
            val roof = b[4] - 0.02f - comY
            for (top in 0..1) for (side in 0..1) for (end in 0..1) {
                val sx = (if (side == 0) b[0] else b[3]) * (if (top == 1) 0.62f else 0.95f) - comX
                val sz = (if (end == 0) b[2] + 0.12f else b[5] - 0.12f) * (if (top == 1) 0.5f else 1f) - comZ
                pts += sx; pts += if (top == 1) roof else sill; pts += sz
                wheelOf += -1
            }
            points = pts.toFloatArray()
            pointWheel = wheelOf.toIntArray()
            inContact = BooleanArray(pointWheel.size)

            sphereRadius = min(w / 2f, h / 2f) * 0.95f
            val reach = l / 2f - sphereRadius
            spheres = floatArrayOf(0f, 0f, -reach, 0f, 0f, 0f, 0f, 0f, reach)
        }

        // ---- driving

        fun respawn(entering: Boolean, offset: Float = 0f) {
            dir = if (Random.nextBoolean()) 1f else -1f
            lane = homeLane(dir)
            overtaking = 0f
            // Either already on the road or just coming on from the edge it drives away from
            x = if (entering) -dir * (halfWorld + 4f + offset) else (Random.nextFloat() * 2f - 1f) * (halfWorld - 4f)
            y = comY - model.bounds[1] + 0.02f
            z = laneZ(lane)
            vx = dir * cruise; vy = 0f; vz = 0f
            wx = 0f; wy = 0f; wz = 0f
            setYaw(-dir * PI.toFloat() / 2f)
            health = 100f; charred = 1f; alpha = 1f
            attached.fill(true)
            inContact.fill(false)
            wreckTimer = 0f; stuckTimer = 0f; progressX = x; airTime = 0f
            state = State.DRIVING
            stateTime = 0f
        }

        fun think(dt: Float) {
            stateTime += dt
            lastSound -= dt
            sirenClock += dt
            when (state) {
                State.WAITING -> return
                State.GONE -> {
                    if (stateTime > RESPAWN_SECONDS) respawn(entering = true)
                    return
                }
                State.BURNING -> {
                    burn(dt)
                    return
                }
                else -> {}
            }
            if (state != State.DRIVING) return

            if (overtaking > 0f) {
                overtaking -= dt
                if (overtaking <= 0f) lane = homeLane(dir)
            }

            target = cruise
            val laneCentre = if (derbyLeft > 0f) 0f else laneZ(lane)
            for (ramp in ramps) {
                if (ramp.dir != dir || abs(ramp.z - laneCentre) > 1f) continue
                // From well before the foot until it is off the lip
                val toFoot = (ramp.start - x) * dir
                val toLip = (ramp.end - x) * dir
                if (toFoot < 18f && toLip > -2f) target = max(target, jumpSpeed)
            }
            for (other in cars) {
                if (other === this || other.state == State.WAITING || other.state == State.GONE) continue
                if (abs(other.z - z) > 1.5f) continue
                val ahead = (other.x - x) * dir
                if (ahead <= 0f || ahead > 4f + model.length) continue
                val theirSpeed = other.vx * dir
                if (theirSpeed > target) continue
                if (overtaking <= 0f && derbyLeft <= 0f && (other.state != State.DRIVING || Random.nextFloat() < OVERTAKE_CHANCE * dt)) {
                    // Out into the other lane, oncoming traffic or not
                    lane = 1 - lane
                    overtaking = 3f
                } else if (overtaking <= 0f && other.state == State.DRIVING) {
                    target = max(0f, theirSpeed)
                }
            }

            // Off one edge, back on the same edge the other way round
            if ((x - dir * halfWorld) * dir > 6f) {
                dir = -dir
                lane = homeLane(dir)
                overtaking = 0f
                x = -dir * (halfWorld + 5f)
                z = laneZ(lane)
                y = comY - model.bounds[1] + 0.05f
                vx = dir * cruise; vy = 0f; vz = 0f
                wx = 0f; wy = 0f; wz = 0f
                setYaw(-dir * PI.toFloat() / 2f)
            }
        }

        private fun laneTarget() = if (derbyLeft > 0f) 0f else laneZ(lane)

        fun kick() {
            vy += 5.5f + Random.nextFloat() * 2f
            vx += dir * 1.5f
            val (ax, ay, az) = randomStuntAxis()
            addSpin(ax, ay, az, (Random.nextFloat() * 4f + 5f) * if (Random.nextBoolean()) 1f else -1f)
            play("bump", 0.6f)
        }

        private fun randomStuntAxis(): Triple<Float, Float, Float> = when (Random.nextInt(3)) {
            0 -> Triple(m[0], m[3], m[6])   // flip: about the car's right
            1 -> Triple(-m[2], -m[5], -m[8]) // barrel roll: about its nose
            else -> Triple(0f, 1f, 0f)      // helicopter
        }

        // ---- physics

        fun clearForces() {
            fx = 0f; fy = -GRAVITY; fz = 0f
            tx = 0f; ty = 0f; tz = 0f
            wheelsDown = 0; bodyDown = 0
        }

        fun groundForces(dt: Float) {
            if (state == State.WAITING || state == State.GONE) return
            val upright = m[4] > 0.5f
            val fwdX = -m[2]; val fwdY = -m[5]; val fwdZ = -m[8]
            var rampNow = false
            for (i in pointWheel.indices) {
                val wheel = pointWheel[i]
                if (wheel >= 0 && !attached[wheel]) { inContact[i] = false; continue }
                val lx = points[i * 3]; val ly = points[i * 3 + 1]; val lz = points[i * 3 + 2]
                val rx = m[0] * lx + m[1] * ly + m[2] * lz
                val ry = m[3] * lx + m[4] * ly + m[5] * lz
                val rz = m[6] * lx + m[7] * ly + m[8] * lz
                val pen = penetration(x + rx, y + ry, z + rz, dir)
                if (pen <= 0f) { inContact[i] = false; continue }
                val nx = hitNx; val ny = hitNy
                if (hitOnRamp) rampNow = true
                // Velocity of the point: v + w x r
                val pvx = vx + wy * rz - wz * ry
                val pvy = vy + wz * rx - wx * rz
                val pvz = vz + wx * ry - wy * rx
                val vn = pvx * nx + pvy * ny
                if (!inContact[i]) {
                    inContact[i] = true
                    impact(-vn, wheel >= 0, x + rx, y + ry, z + rz)
                }
                val fn = max(0f, CONTACT_K * pen - CONTACT_C * vn)
                var tvx = pvx - vn * nx; val tvy = pvy - vn * ny; var tvz = pvz
                var ffx: Float; var ffy: Float; var ffz: Float
                if (wheel >= 0 && upright) {
                    wheelsDown++
                    // Tyres grip across the car - along its right, not just off its nose, or
                    // climbing a ramp counts as sliding sideways - and roll along the rest
                    val side = tvx * m[0] + tvy * m[3] + tvz * m[6]
                    val latX = side * m[0]; val latY = side * m[3]; val latZ = side * m[6]
                    ffx = -latX * TYRE_GRIP - (tvx - latX) * ROLLING
                    ffy = -latY * TYRE_GRIP - (tvy - latY) * ROLLING
                    ffz = -latZ * TYRE_GRIP - (tvz - latZ) * ROLLING
                } else {
                    bodyDown++
                    // A bumper touching a ramp on the way up slides; anything else drags
                    val friction = if (hitOnRamp && upright) BUMPER_FRICTION else BODY_FRICTION
                    ffx = -tvx * friction; ffy = -tvy * friction; ffz = -tvz * friction
                    val speed = sqrt(tvx * tvx + tvz * tvz)
                    if (speed > 3f && Random.nextFloat() < 0.04f) spark(x + rx, y + ry, z + rz, speed)
                }
                val cap = (if (wheel >= 0) 1.1f else 0.6f) * fn
                val ft = sqrt(ffx * ffx + ffy * ffy + ffz * ffz)
                if (ft > cap && ft > 0f) { val s = cap / ft; ffx *= s; ffy *= s; ffz *= s }
                val fpx = nx * fn + ffx; val fpy = ny * fn + ffy; val fpz = ffz
                fx += fpx; fy += fpy; fz += fpz
                tx += ry * fpz - rz * fpy
                ty += rz * fpx - rx * fpz
                tz += rx * fpy - ry * fpx
            }
            onRamp = onRamp || rampNow

            if (state == State.DRIVING && wheelsDown > 0 && upright) {
                val speed = vx * fwdX + vy * fwdY + vz * fwdZ
                val accel = ((target - speed) * ACCEL_GAIN).coerceIn(-BRAKE_MAX, ACCEL_MAX) * wheelsDown / 4f
                fx += fwdX * accel; fy += fwdY * accel; fz += fwdZ * accel
                if (wheelsDown >= 3) {
                    // Steer for the lane: point at a spot a little way up the road in it
                    val hx = dir; val hz = ((laneTarget() - z) * 0.35f).coerceIn(-0.6f, 0.6f)
                    val err = atan2(fwdZ * hx - fwdX * hz, fwdX * hx + fwdZ * hz)
                    wy += (err * 10f - wy * 5f) * dt
                }
            }
        }

        fun integrate(dt: Float) {
            if (state == State.WAITING || state == State.GONE) return
            vx += fx * dt; vy += fy * dt; vz += fz * dt
            // Torque into the body frame, through the inertia, and back out
            val lx = (m[0] * tx + m[3] * ty + m[6] * tz) * invIx
            val ly = (m[1] * tx + m[4] * ty + m[7] * tz) * invIy
            val lz = (m[2] * tx + m[5] * ty + m[8] * tz) * invIz
            wx += (m[0] * lx + m[1] * ly + m[2] * lz) * dt
            wy += (m[3] * lx + m[4] * ly + m[5] * lz) * dt
            wz += (m[6] * lx + m[7] * ly + m[8] * lz) * dt
            val spinDrag = 1f - SPIN_DRAG * dt
            wx *= spinDrag; wy *= spinDrag; wz *= spinDrag
            x += vx * dt; y += vy * dt; z += vz * dt
            spin(dt)
        }

        fun applyAt(rx: Float, ry: Float, rz: Float, ffx: Float, ffy: Float, ffz: Float) {
            fx += ffx; fy += ffy; fz += ffz
            tx += ry * ffz - rz * ffy
            ty += rz * ffx - rx * ffz
            tz += rx * ffy - ry * ffx
        }

        fun afterStep(dt: Float) {
            if (state == State.WAITING || state == State.GONE) return
            // A car the physics has lost (it shouldn't happen, but if it does it must not end
            // up filling the screen) is quietly taken off and comes back later
            if (!(x.isFinite() && y.isFinite() && z.isFinite() && qw.isFinite()) || vx * vx + vy * vy + vz * vz > 80f * 80f) {
                state = State.GONE
                stateTime = 0f
                return
            }
            if (abs(z) > ROAD_HALF_WIDTH) {
                z = ROAD_HALF_WIDTH * kotlin.math.sign(z)
                if (vz * z > 0f) vz = -vz * 0.3f
            }
            val forward = vx * -m[2] + vz * -m[8]
            model.parts.forEachIndexed { i, part ->
                if (part.kind == Mm2CarModel.Kind.WHEEL) wheelSpin[i] -= forward / part.radius * dt
            }

            val grounded = inContact.any { it }
            if (!grounded) {
                airTime += dt
                if (wasOnRamp && airTime > 0.05f && vy > 1.5f && state == State.DRIVING) {
                    wasOnRamp = false
                    takeOff()
                } else if (levelAt >= 0f && airTime >= levelAt) {
                    levelAt = -1f
                    level()
                }
            } else {
                airTime = 0f
                levelAt = -1f
                wasOnRamp = onRamp
            }
            onRamp = false

            // Anything that hasn't got anywhere for a while - wedged on another car, hung up
            // on a ramp, rocking on its side - is done for
            if (abs(x - progressX) > 2f) {
                progressX = x
                stuckTimer = 0f
            } else {
                stuckTimer += dt
            }

            if (state == State.DRIVING) {
                val speed = sqrt(vx * vx + vz * vz)
                if (m[4] < 0.35f && speed < 2f) wreckTimer += dt else wreckTimer = 0f
                if (health <= 0f || wreckTimer > 1.2f || stuckTimer > STUCK_SECONDS) destroy()
                else if (m[4] < 0.35f && grounded) state = State.WRECKED.also { stateTime = 0f }
            } else if (state == State.WRECKED) {
                // On its side or its roof. It might still rock back onto its wheels.
                if (m[4] > 0.8f && stateTime > 0.3f) state = State.DRIVING
                else if (health <= 0f || stateTime > 1.5f || stuckTimer > STUCK_SECONDS) destroy()
            }
        }

        /**
         * Off the end of a ramp: a toy ramp's kick into the air and maybe a stunt, spun
         * through most of the flight; then [level] has what is left of it to come down on
         * its wheels. Mirrored in scripts/mm2cars_sim/sim.js, where it is tuned.
         */
        private fun takeOff() {
            val stunt = Random.nextInt(10)
            val ground = sqrt(vx * vx + vz * vz)
            vy = max(vy, max(ground * LAUNCH_LIFT + 1.5f, if (stunt < 3) 0f else STUNT_LIFT))
            // Time until it is back down on the road, which is lower than the lip it left
            val drop = max(0f, y - (comY - model.bounds[1]))
            val air = (vy + sqrt(vy * vy + 2f * GRAVITY * drop)) / GRAVITY
            val stuntTime = if (stunt < 3) 0f else air * STUNT_SHARE
            levelAt = airTime + stuntTime
            landAt = airTime + air
            // Not every stunt comes all the way round
            val w = spinRate(2f * PI.toFloat() * (0.85f + Random.nextFloat() * 0.3f), stuntTime)
            val way = if (Random.nextBoolean()) 1f else -1f
            wx = 0f; wy = 0f; wz = 0f
            when (stunt) {
                0, 1, 2 -> {}                                   // just the jump
                3, 4 -> addSpin(m[0], m[3], m[6], w)            // back flip, about its right
                5 -> addSpin(m[0], m[3], m[6], -w)              // front flip
                6, 7, 8 -> addSpin(-m[2], -m[5], -m[8], w * way) // barrel roll, about its nose
                else -> wy = w * way                            // helicopter
            }
        }

        /** The spin that turns through [angle] in [time], spin drag and all. */
        private fun spinRate(angle: Float, time: Float): Float =
            if (time <= 0f) 0f else angle * SPIN_DRAG / (1f - kotlin.math.exp(-SPIN_DRAG * time))

        /**
         * Once the stunt is through: turn the shortest way to wheels down and a little nose
         * down - if the stunt came round far enough to make it. A botched one tumbles on.
         */
        private fun level() {
            val ux = m[1]; val uy = m[4]; val uz = m[7]
            val fx = -m[2]; val fz = -m[8]
            val fl = sqrt(fx * fx + fz * fz).coerceAtLeast(1e-4f)
            val s = sin(LANDING_NOSE_DOWN); val c = cos(LANDING_NOSE_DOWN)
            val tx = fx / fl * s; val ty = c; val tz = fz / fl * s
            val dot = ux * tx + uy * ty + uz * tz
            if (dot < cos(SAVE_ANGLE)) return
            val ax = uy * tz - uz * ty; val ay = uz * tx - ux * tz; val az = ux * ty - uy * tx
            val len = sqrt(ax * ax + ay * ay + az * az)
            val w = if (len < 1e-5f) 0f else spinRate(kotlin.math.acos(min(1f, dot)), max(0.1f, landAt - airTime)) / len
            wx = ax * w; wy = ay * w; wz = az * w
        }

        // ---- damage

        fun impact(speed: Float, wheel: Boolean, ix: Float, iy: Float, iz: Float) {
            if (state == State.BURNING) return
            val damage = if (wheel) (speed - WHEEL_HURT_SPEED) * 5f else (speed - 2.5f) * 7f
            if (wheel && speed > 3.5f) {
                play("land", (speed / 10f).coerceIn(0.2f, 0.8f))
                dust(ix, iy, iz, speed)
            }
            if (damage <= 0f) return
            hurt(damage, ix, iy, iz)
        }

        fun hurt(damage: Float, ix: Float, iy: Float, iz: Float) {
            val wasClean = health > DAMAGED_PAINT_AT
            health -= damage
            if (lastSound <= 0f) {
                play(if (damage > 25f) "crash" else "bump", (damage / 30f).coerceIn(0.25f, 1f))
                lastSound = 0.15f
            }
            repeat((damage / 3f).toInt().coerceAtMost(12)) { spark(ix, iy, iz, 6f) }
            if (wasClean && health <= DAMAGED_PAINT_AT) play("glass", 0.7f)
            if (damage > 14f) shed(Mm2CarModel.Kind.BREAK, 1)
        }

        /** Throws off up to [count] attached parts of [kind]. */
        private fun shed(kind: Mm2CarModel.Kind, count: Int) {
            var left = count
            for (i in model.parts.indices.shuffled()) {
                if (left == 0) return
                val part = model.parts[i]
                if (part.kind != kind || !attached[i]) continue
                attached[i] = false
                left--
                debris.add(Debris(this, part, wheelSpin[i]))
            }
        }

        /** Hands a coming-off part its place and its fling. */
        fun launch(d: Debris, part: Mm2CarModel.Part, spinAngle: Float) {
            val lx = part.pivot[0] - comX; val ly = part.pivot[1] - comY; val lz = part.pivot[2] - comZ
            val rx = m[0] * lx + m[1] * ly + m[2] * lz
            val ry = m[3] * lx + m[4] * ly + m[5] * lz
            val rz = m[6] * lx + m[7] * ly + m[8] * lz
            d.x = x + rx; d.y = y + ry; d.z = z + rz
            val kick = if (state == State.BURNING) 5f else 2.5f
            d.vx = vx + wy * rz - wz * ry + (rx * 2f + Random.nextFloat() * 2f - 1f) * kick
            d.vy = vy + wz * rx - wx * rz + (Random.nextFloat() + 0.6f) * kick * 1.2f
            d.vz = vz + wx * ry - wy * rx + (rz * 2f + Random.nextFloat() * 2f - 1f) * kick * 0.5f
            // Its orientation is the car's, turned by its own spin if it is a wheel
            val c = cos(spinAngle / 2f); val s = sin(spinAngle / 2f)
            d.qw = qw * c - qx * s; d.qx = qw * s + qx * c; d.qy = qy * c + qz * s; d.qz = qz * c - qy * s
            d.updateMatrix()
            d.wx = (Random.nextFloat() * 2f - 1f) * 12f
            d.wy = (Random.nextFloat() * 2f - 1f) * 8f
            d.wz = (Random.nextFloat() * 2f - 1f) * 12f
        }

        private fun destroy() {
            state = State.BURNING
            stateTime = 0f
            health = 0f
            play("boom", 1f)
            shed(Mm2CarModel.Kind.WHEEL, 4)
            shed(Mm2CarModel.Kind.BREAK, 99)
            // The toy pops
            vy += 4.5f
            addSpin(Random.nextFloat() - 0.5f, Random.nextFloat() - 0.5f, Random.nextFloat() - 0.5f, 9f)
            repeat(14) { fire(x, y, z, burst = true) }
            repeat(18) { spark(x, y, z, 9f) }
        }

        private fun burn(dt: Float) {
            charred = max(0.28f, charred - dt * 1.5f)
            burnEmit -= dt
            if (burnEmit <= 0f && stateTime < BURN_SECONDS) {
                burnEmit = 0.06f
                fire(x, y + 0.3f, z, burst = false)
                smoke(x, y + 0.5f, z)
            }
            if (stateTime > BURN_SECONDS) alpha = max(0f, 1f - (stateTime - BURN_SECONDS) / 0.8f)
            if (alpha <= 0f) {
                state = State.GONE
                stateTime = 0f
            }
        }

        private fun play(name: String, volume: Float) = sounds.play(name, ((x / halfWorld) + 1f) / 2f, volume)

        // ---- drawing

        val drawn get() = state != State.WAITING && state != State.GONE

        fun draw(r: CarsRenderer) {
            val atlas = if (health <= DAMAGED_PAINT_AT) model.atlasDamaged else model.atlas
            model.parts.forEachIndexed { i, part ->
                if (!attached[i]) return@forEachIndexed
                // The part's rotation is the car's, after the wheel's roll about its axle
                if (part.kind == Mm2CarModel.Kind.WHEEL) {
                    val c = cos(wheelSpin[i]); val s = sin(wheelSpin[i])
                    for (r3 in 0..2) {
                        val a = m[r3 * 3 + 1]; val b = m[r3 * 3 + 2]
                        pm[r3 * 3] = m[r3 * 3]
                        pm[r3 * 3 + 1] = a * c + b * s
                        pm[r3 * 3 + 2] = -a * s + b * c
                    }
                } else {
                    System.arraycopy(m, 0, pm, 0, 9)
                }
                val lx = part.pivot[0] - comX; val ly = part.pivot[1] - comY; val lz = part.pivot[2] - comZ
                r.mesh(
                    part, atlas, pm,
                    x + m[0] * lx + m[1] * ly + m[2] * lz,
                    y + m[3] * lx + m[4] * ly + m[5] * lz,
                    z + m[6] * lx + m[7] * ly + m[8] * lz,
                    alpha = alpha, shade = charred
                )
            }
            if (state == State.DRIVING || state == State.WRECKED) drawSiren(r)
            drawShadow(r)
        }

        /** Where the car is on screen, from its bounding box's corners: for taps. */
        fun measure() {
            val b = model.bounds
            screenLeft = Float.MAX_VALUE; screenTop = Float.MAX_VALUE
            screenRight = -Float.MAX_VALUE; screenBottom = -Float.MAX_VALUE
            for (k in 0 until 8) {
                val lx = (if (k and 1 == 0) b[0] else b[3]) - comX
                val ly = (if (k and 2 == 0) b[1] else b[4]) - comY
                val lz = (if (k and 4 == 0) b[2] else b[5]) - comZ
                camera.project(
                    x + m[0] * lx + m[1] * ly + m[2] * lz,
                    y + m[3] * lx + m[4] * ly + m[5] * lz,
                    z + m[6] * lx + m[7] * ly + m[8] * lz
                )
                screenLeft = min(screenLeft, camera.px); screenRight = max(screenRight, camera.px)
                screenTop = min(screenTop, camera.py); screenBottom = max(screenBottom, camera.py)
            }
        }

        private var sirenClock = Random.nextFloat()

        /** The light bar, red and blue by turns, as the web port's cop-lights.js flashes it. */
        private fun drawSiren(r: CarsRenderer) {
            val siren = model.siren ?: return
            val side = (sirenClock * SIREN_FLASHES).toInt() % 2
            val lx = siren[side * 3] - comX; val ly = siren[side * 3 + 1] - comY; val lz = siren[side * 3 + 2] - comZ
            r.glow(
                x + m[0] * lx + m[1] * ly + m[2] * lz,
                y + m[3] * lx + m[4] * ly + m[5] * lz,
                z + m[6] * lx + m[7] * ly + m[8] * lz,
                SIREN_GLOW, if (side == 0) GLOW_RED else GLOW_BLUE
            )
        }

        private fun drawShadow(r: CarsRenderer) {
            val g = groundHeight(x, z, dir)
            val lift = (y - comY + model.bounds[1] - g).coerceAtLeast(0f)
            val a = (0.35f * (1f - lift / 4f) * alpha).coerceIn(0f, 1f)
            if (a <= 0.01f) return
            // Along the car's heading, flattened onto the road
            val fx = -m[2]; val fz = -m[8]
            val fl = sqrt(fx * fx + fz * fz).coerceAtLeast(1e-4f)
            r.shadow(x, g, z, model.length / 2f, model.width / 2f, fx / fl, fz / fl, a)
        }

        val isDamaged get() = health <= DAMAGED_PAINT_AT
        val shade get() = charred
    }

    private inner class Debris(val car: Car, val part: Mm2CarModel.Part, spinAngle: Float) : Body() {
        private var life = DEBRIS_SECONDS + Random.nextFloat() * 2f
        private var alpha = 1f
        private val atlas = if (car.isDamaged || car.state == State.BURNING) car.model.atlasDamaged else car.model.atlas
        private val shade = car.shade
        private val rest = if (part.kind == Mm2CarModel.Kind.WHEEL) part.radius else part.extent * 0.4f

        init {
            car.launch(this, part, spinAngle)
        }

        fun step(dt: Float): Boolean {
            vy -= GRAVITY * dt
            x += vx * dt; y += vy * dt; z += vz * dt
            spin(dt)
            // Pieces stay on the road, never flying out at the camera
            if (abs(z) > ROAD_HALF_WIDTH) {
                z = ROAD_HALF_WIDTH * kotlin.math.sign(z)
                if (vz * z > 0f) vz = -vz * 0.3f
            }
            val g = groundHeight(x, z) + rest
            if (y < g) {
                y = g
                if (vy < -1f) {
                    vy = -vy * 0.35f
                    vx *= 0.7f; vz *= 0.7f
                    wx *= 0.6f; wy *= 0.6f; wz *= 0.6f
                } else {
                    vy = 0f
                    val drag = 1f - min(1f, 4f * dt)
                    vx *= drag; vz *= drag; wx *= drag; wy *= drag; wz *= drag
                }
            }
            life -= dt
            alpha = (life / 0.8f).coerceIn(0f, 1f)
            return life > 0f && abs(x) < halfWorld + 20f
        }

        fun draw(r: CarsRenderer) {
            r.mesh(part, atlas, m, x, y, z, alpha = alpha, shade = shade)
        }
    }

    // ---------------------------------------------------------------- car-to-car

    private val touching = HashSet<Int>()

    private fun collideCars() {
        for (i in cars.indices) for (j in i + 1 until cars.size) {
            val a = cars[i]; val b = cars[j]
            if (!a.drawn || !b.drawn) continue
            if (abs(a.x - b.x) > 7f || abs(a.z - b.z) > 3f || abs(a.y - b.y) > 3f) continue
            sphereContacts(a, b, i * 8 + j)
        }
    }

    private val sa = FloatArray(9)
    private val sb = FloatArray(9)

    private fun sphereContacts(a: Car, b: Car, pair: Int) {
        sphereCentres(a, sa); sphereCentres(b, sb)
        val r = a.sphereRadius + b.sphereRadius
        var hit = false
        for (i in 0..2) for (j in 0..2) {
            val dx = (a.x + sa[i * 3]) - (b.x + sb[j * 3])
            val dy = (a.y + sa[i * 3 + 1]) - (b.y + sb[j * 3 + 1])
            val dz = (a.z + sa[i * 3 + 2]) - (b.z + sb[j * 3 + 2])
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 >= r * r || d2 < 1e-6f) continue
            hit = true
            val d = sqrt(d2)
            val nx = dx / d; val ny = dy / d; val nz = dz / d
            val rax = sa[i * 3]; val ray = sa[i * 3 + 1]; val raz = sa[i * 3 + 2]
            val rbx = sb[j * 3]; val rby = sb[j * 3 + 1]; val rbz = sb[j * 3 + 2]
            val vax = a.vx + a.wy * raz - a.wz * ray
            val vay = a.vy + a.wz * rax - a.wx * raz
            val vaz = a.vz + a.wx * ray - a.wy * rax
            val vbx = b.vx + b.wy * rbz - b.wz * rby
            val vby = b.vy + b.wz * rbx - b.wx * rbz
            val vbz = b.vz + b.wx * rby - b.wy * rbx
            val vn = (vax - vbx) * nx + (vay - vby) * ny + (vaz - vbz) * nz
            if (pair !in touching) {
                touching += pair
                val speed = -vn
                if (speed > 2f) {
                    val damage = (speed - 2f) * 8f
                    val hx = (a.x + b.x) / 2f; val hy = (a.y + b.y) / 2f; val hz = (a.z + b.z) / 2f
                    a.hurt(damage, hx, hy, hz)
                    b.hurt(damage, hx, hy, hz)
                    // Toys bounce
                    a.vy += speed * 0.3f; b.vy += speed * 0.3f
                    a.addSpin(nz, 0f, -nx, speed * 0.5f * (Random.nextFloat() + 0.5f))
                    b.addSpin(-nz, 0f, nx, speed * 0.5f * (Random.nextFloat() + 0.5f))
                }
            }
            val f = max(0f, CAR_K * (r - d) - CAR_C * vn)
            a.applyAt(rax, ray, raz, nx * f, ny * f, nz * f)
            b.applyAt(rbx, rby, rbz, -nx * f, -ny * f, -nz * f)
        }
        if (!hit) touching -= pair
    }

    private fun sphereCentres(car: Car, out: FloatArray) {
        val m = car.m
        val s = car.spheres
        for (k in 0..2) {
            val lx = s[k * 3]; val ly = s[k * 3 + 1]; val lz = s[k * 3 + 2]
            out[k * 3] = m[0] * lx + m[1] * ly + m[2] * lz
            out[k * 3 + 1] = m[3] * lx + m[4] * ly + m[5] * lz
            out[k * 3 + 2] = m[6] * lx + m[7] * ly + m[8] * lz
        }
    }

    // ---------------------------------------------------------------- effects

    private enum class Puff { SMOKE, FIRE, SPARK, DUST }

    private inner class Particle(
        val kind: Puff,
        var x: Float, var y: Float, var z: Float,
        var vx: Float, var vy: Float, var vz: Float,
        val maxLife: Float,
        var size: Float
    ) {
        var life = maxLife

        fun step(dt: Float): Boolean {
            life -= dt
            x += vx * dt; y += vy * dt; z += vz * dt
            when (kind) {
                Puff.SPARK -> {
                    vy -= GRAVITY * dt
                    if (y < 0f) { y = 0f; vy = -vy * 0.4f }
                }
                Puff.SMOKE -> { size += dt * 0.9f; vy *= 1f - dt * 0.3f }
                Puff.FIRE -> size *= 1f - dt * 1.2f
                Puff.DUST -> { size += dt * 1.2f; vy *= 1f - dt * 2f }
            }
            return life > 0f
        }
    }

    private fun addParticle(p: Particle) {
        if (particles.size < MAX_PARTICLES) particles.add(p)
    }

    private fun rnd(spread: Float) = (Random.nextFloat() * 2f - 1f) * spread

    private fun spark(x: Float, y: Float, z: Float, speed: Float) = addParticle(
        Particle(Puff.SPARK, x, y, z, rnd(speed * 0.5f), Random.nextFloat() * speed * 0.6f + 1f, rnd(1f), 0.35f + Random.nextFloat() * 0.3f, 0.06f)
    )

    private fun fire(x: Float, y: Float, z: Float, burst: Boolean) = addParticle(
        if (burst) Particle(Puff.FIRE, x + rnd(0.5f), y + rnd(0.3f), z + rnd(0.5f), rnd(4f), Random.nextFloat() * 4f + 1f, rnd(2f), 0.6f, 0.9f + Random.nextFloat() * 0.5f)
        else Particle(Puff.FIRE, x + rnd(0.9f), y, z + rnd(0.4f), rnd(0.3f), Random.nextFloat() * 1.5f + 1.2f, 0f, 0.5f, 0.45f + Random.nextFloat() * 0.25f)
    )

    private fun smoke(x: Float, y: Float, z: Float) = addParticle(
        Particle(Puff.SMOKE, x + rnd(0.6f), y, z + rnd(0.3f), rnd(0.3f) + 0.4f, Random.nextFloat() * 0.8f + 1.4f, 0f, 2.2f, 0.4f)
    )

    private fun dust(x: Float, y: Float, z: Float, speed: Float) {
        repeat(3) {
            addParticle(Particle(Puff.DUST, x + rnd(0.3f), y, z + rnd(0.3f), rnd(speed * 0.2f), Random.nextFloat() * 0.6f, 0f, 0.5f, 0.25f))
        }
    }

    // ---------------------------------------------------------------- props

    private fun identity(m: FloatArray) {
        m.fill(0f); m[0] = 1f; m[4] = 1f; m[8] = 1f
    }

    /** Sets [pm] to a turn of [yaw] about Y after scaling by (sx, sy, sz). */
    private fun yawScale(yaw: Float, sx: Float, sy: Float, sz: Float) {
        val c = cos(yaw); val s = sin(yaw)
        pm[0] = c * sx; pm[1] = 0f; pm[2] = s * sz
        pm[3] = 0f; pm[4] = sy; pm[5] = 0f
        pm[6] = -s * sx; pm[7] = 0f; pm[8] = c * sz
    }

    private fun drawRamp(r: CarsRenderer, ramp: Ramp) {
        val k = ramp.grow
        if (k <= 0.01f) return
        // The trailer rises along its +X; turned round for the lane that drives the other way
        yawScale(
            if (ramp.dir > 0f) 0f else PI.toFloat(),
            ramp.scale, ramp.scale * k, RAMP_HALF_WIDTH * 2f / trailerModel.width
        )
        for ((part, bitmap) in trailerModel.parts) r.mesh(part, bitmap, pm, ramp.start, 0f, ramp.z)
    }

    private fun drawGate(r: CarsRenderer, gate: Gate) {
        val k = gate.scale
        if (k <= 0.01f) return
        val radius = sqrt(GATE_SPAN_X * GATE_SPAN_X + GATE_SPAN_Z * GATE_SPAN_Z)
        val height = GATE_HEIGHT * k
        // Model x across the gate, onto the slant; model y -0.5..0.5 up to its full height
        yawScale(atan2(-GATE_SPAN_Z, GATE_SPAN_X), radius, height, radius)
        for ((part, bitmap) in gateModel.parts) r.mesh(part, bitmap, pm, gate.x, height / 2f, 0f, lit = false, cutout = true)
    }

    private fun drawParticles(r: CarsRenderer) {
        for (p in particles) {
            val t = p.life / p.maxLife
            when (p.kind) {
                Puff.SPARK -> r.puff(p.x, p.y, p.z, p.size, 1f, 0.86f, 0.35f, t)
                Puff.FIRE -> r.puff(p.x, p.y, p.z, p.size, 1f, (120 + 110 * t) / 255f, 40 * t / 255f, 0.9f * t)
                Puff.SMOKE -> { val c = (70 + 40 * t) / 255f; r.puff(p.x, p.y, p.z, p.size, c, c, c, 0.6f * t) }
                Puff.DUST -> r.puff(p.x, p.y, p.z, p.size, 0.82f, 0.78f, 0.72f, 0.47f * t)
            }
        }
    }

    // ---------------------------------------------------------------- the street

    private var streetGround: Mm2CarModel.Part? = null
    private var streetHouses: Mm2CarModel.Part? = null
    private var streetTrees: Mm2CarModel.Part? = null
    private var builtHalf = 0f

    /**
     * Lays the street out in world space, wide enough for the screen: the two lanes (MM2's
     * r2_f, mirrored about the yellow centre line) and sidewalks, then behind the far
     * sidewalk a row of Victorians - a garage or door or stairs at street level, one or two
     * floors of bays, the pediment cornice and a tar roof, each house in one colour - with
     * street trees along the kerb. Random each time it is built.
     */
    private fun buildStreet() {
        val half = halfWorld + 12f
        val gPos = ArrayList<Float>(); val gUv = ArrayList<Float>()
        val bPos = ArrayList<Float>(); val bUv = ArrayList<Float>()
        val tPos = ArrayList<Float>(); val tUv = ArrayList<Float>()

        // A quad from four corners (counter-clockwise seen from its front) and an atlas tile,
        // the tile's top edge on corners 3-2 and its bottom on 0-1
        fun quad(pos: ArrayList<Float>, uv: ArrayList<Float>, c: FloatArray, tile: FloatArray, flipV: Boolean = false) {
            val l = tile[0]; val r = tile[2]
            val t = if (flipV) tile[3] else tile[1]
            val b = if (flipV) tile[1] else tile[3]
            val uvs = floatArrayOf(l, b, r, b, r, t, l, t)
            for (k in intArrayOf(0, 1, 2, 0, 2, 3)) {
                pos += c[k * 3]; pos += c[k * 3 + 1]; pos += c[k * 3 + 2]
                uv += uvs[k * 2]; uv += uvs[k * 2 + 1]
            }
        }
        // A ground quad, x0..x1 by z0..z1 (z0 nearer the houses), facing up
        fun flat(x0: Float, x1: Float, z0: Float, z1: Float, tile: FloatArray, flipV: Boolean) =
            quad(gPos, gUv, floatArrayOf(x0, 0f, z1, x1, 0f, z1, x1, 0f, z0, x0, 0f, z0), tile, flipV)

        val road = street.tiles.getValue("road")
        val walk = street.tiles.getValue("sidewalk")
        var x = -half
        while (x < half) {
            val x1 = x + STREET_TILE
            // Far lane: centre line (the tile's top) at z = 0, edge line out at -ROAD
            flat(x, x1, -STREET_ROAD, 0f, road, flipV = true)
            flat(x, x1, 0f, STREET_ROAD, road, flipV = false)
            flat(x, x1, -STREET_ROAD - STREET_WALK, -STREET_ROAD, walk, flipV = false)
            flat(x, x1, STREET_ROAD, STREET_ROAD + STREET_WALK, walk, flipV = false)
            x = x1
        }

        // The row houses
        val ground = street.pieces.getValue("ground")
        val floor = street.pieces.getValue("floor")
        val top = street.pieces.getValue("top")
        val roof = street.tiles.getValue("roof")
        x = -half
        while (x < half) {
            val colour = street.colours.random()
            val bay = floor.random()
            val storeys = listOf(listOf(ground.random(), ground.random())) +
                List(1 + Random.nextInt(2)) { listOf(bay, bay) } + listOf(listOf(top.random(), top.random()))
            storeys.forEachIndexed { row, pieces ->
                pieces.forEachIndexed { col, piece ->
                    val x0 = x + col * HOUSE_TILE; val x1 = x0 + HOUSE_TILE
                    val y0 = row * HOUSE_TILE; val y1 = y0 + HOUSE_TILE
                    quad(bPos, bUv, floatArrayOf(x0, y0, HOUSE_Z, x1, y0, HOUSE_Z, x1, y1, HOUSE_Z, x0, y1, HOUSE_Z), street.tiles.getValue("${colour}_$piece"))
                }
            }
            val roofY = storeys.size * HOUSE_TILE
            for (col in 0..1) {
                val x0 = x + col * HOUSE_TILE; val x1 = x0 + HOUSE_TILE
                quad(bPos, bUv, floatArrayOf(x0, roofY, HOUSE_Z, x1, roofY, HOUSE_Z, x1, roofY, HOUSE_Z - HOUSE_TILE, x0, roofY, HOUSE_Z - HOUSE_TILE), roof)
            }
            x += HOUSE_TILE * 2f
        }

        // Street trees along the far kerb, each turned its own way
        x = -half + Random.nextFloat() * 4f
        while (x < half) {
            val tree = street.trees.random()
            val yaw = Random.nextFloat() * 2f * PI.toFloat()
            val c = cos(yaw) * TREE_SCALE; val s = sin(yaw) * TREE_SCALE
            val p = tree.positions
            for (i in 0 until p.size / 3) {
                val lx = p[i * 3]; val ly = p[i * 3 + 1]; val lz = p[i * 3 + 2]
                tPos += x + c * lx + s * lz; tPos += ly * TREE_SCALE; tPos += TREE_Z - s * lx + c * lz
            }
            for (v in tree.uvs) tUv += v
            x += 6f + Random.nextFloat() * 6f
        }

        streetGround = Mm2CarModel.Part("street", Mm2CarModel.Kind.BODY, FloatArray(3), gPos.toFloatArray(), gUv.toFloatArray(), 0f)
        streetHouses = Mm2CarModel.Part("houses", Mm2CarModel.Kind.BODY, FloatArray(3), bPos.toFloatArray(), bUv.toFloatArray(), 0f)
        streetTrees = Mm2CarModel.Part("trees", Mm2CarModel.Kind.BODY, FloatArray(3), tPos.toFloatArray(), tUv.toFloatArray(), 0f)
        builtHalf = halfWorld
    }

    // ---------------------------------------------------------------- lanes

    private fun laneDir(lane: Int) = if (lane == 1) 1f else -1f
    private fun homeLane(dir: Float) = if (dir > 0f) 1 else 0
    private fun laneZ(lane: Int) = if (lane == 1) -Camera.LANE_Z else Camera.LANE_Z

    companion object {
        private const val RAMP_HALF_WIDTH = 1.15f
        /** The street, metres: half the road's width, a sidewalk's, a road tile's length. */
        private const val STREET_ROAD = 2.4f
        private const val STREET_WALK = 2.6f
        private const val STREET_TILE = 2.4f
        /** The row houses' facade line, behind the far sidewalk, and one facade tile's size. */
        private const val HOUSE_Z = -(STREET_ROAD + STREET_WALK) - 0.4f
        private const val HOUSE_TILE = 2.8f
        /** Street trees: along the far sidewalk, and how big against MM2's own. */
        private const val TREE_Z = -(STREET_ROAD + STREET_WALK * 0.55f)
        private const val TREE_SCALE = 0.8f
        /** Kerbs either side of the two lanes: nothing goes past them, towards the camera or away. */
        private const val ROAD_HALF_WIDTH = 3f
        /** The curve where the road meets a trailer's foot. */
        private const val RAMP_FILLET = 1.6f
        /** A gate's half span: across the road (both lanes) and, slanted, along it. */
        private const val GATE_SPAN_Z = 2.6f
        private const val GATE_SPAN_X = 4.2f
        /** MM2's are 7.5 m; on the toy road, a little less. */
        private const val GATE_HEIGHT = 5f

        /** The road's far and near edges, for the view to know where to take hold of it. */
        const val ROAD_FAR_Z = -(STREET_ROAD + STREET_WALK)
        const val ROAD_NEAR_Z = STREET_ROAD

        private const val SUBSTEPS = 6
        private const val GRAVITY = 18f
        private const val CONTACT_K = 2000f
        private const val CONTACT_C = 26f
        private const val CAR_K = 900f
        private const val CAR_C = 12f
        private const val TYRE_GRIP = 25f
        private const val ROLLING = 0.02f
        private const val BODY_FRICTION = 8f
        private const val BUMPER_FRICTION = 0.5f
        private const val WHEEL_HURT_SPEED = 13f
        private const val SPIN_DRAG = 0.3f
        private const val ACCEL_GAIN = 3f
        private const val ACCEL_MAX = 16f
        private const val BRAKE_MAX = 12f
        private const val INERTIA_SCALE = 1.4f

        private val CRUISE = floatArrayOf(12f, 10f, 9f, 13.5f, 12.5f)
        private const val OVERTAKE_CHANCE = 0.6f
        private const val DERBY_SECONDS = 12f
        private const val STUCK_SECONDS = 2.5f
        /** Upward launch off a ramp's lip, per m/s of road speed. */
        private const val LAUNCH_LIFT = 0.2f
        /** The least upward speed a stunt gets, so there is time to come round. */
        private const val STUNT_LIFT = 8f
        /** How much of the flight a stunt spins for, before levelling out to land. */
        private const val STUNT_SHARE = 0.65f
        /** A stunt that ends further than this from wheels-down can't be saved. */
        private const val SAVE_ANGLE = 50f * PI.toFloat() / 180f
        private const val LANDING_NOSE_DOWN = 0.08f
        private const val DAMAGED_PAINT_AT = 60f
        private const val BURN_SECONDS = 4f
        private const val RESPAWN_SECONDS = 2.5f
        private const val DEBRIS_SECONDS = 6f

        private const val MAX_PARTICLES = 320

        /** Light-bar flashes a second, and how far a glow reaches, metres. */
        private const val SIREN_FLASHES = 6f
        private const val SIREN_GLOW = 0.9f
        private const val GLOW_RED = 0xFFFF2828.toInt()
        private const val GLOW_BLUE = 0xFF285AFF.toInt()
    }
}
