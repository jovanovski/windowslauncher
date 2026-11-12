// A line-for-line port of the car physics in
// app/src/main/java/rocks/gorjan/gokixp/apps/mm2cars/TaskbarCarsView.kt, one car at a time,
// so ramp jumps can be tuned here (node run.mjs, or index.html) before going back into Kotlin.
// Keep the two in step: P below mirrors the companion object's constants.

export const P = {
  SUBSTEPS: 6,
  GRAVITY: 18,
  CONTACT_K: 2000,
  CONTACT_C: 26,
  TYRE_GRIP: 25,
  ROLLING: 0.02,
  BODY_FRICTION: 8,
  BUMPER_FRICTION: 0.5,
  WHEEL_HURT_SPEED: 13,
  INERTIA_SCALE: 1.4,
  LAUNCH_LIFT: 0.2,
  LANDING_NOSE_DOWN: 0.08,
  STUNT_LIFT: 8,
  SPIN_DRAG: 0.3,
  STUNT_SHARE: 0.65,
  SAVE_ANGLE: 50 * Math.PI / 180,
  RAMP_HALF_WIDTH: 1.15,
  RAMP_FILLET: 1.6,
  ACCEL_GAIN: 3,
  ACCEL_MAX: 16,
  BRAKE_MAX: 12,
};

// MM2's jump trailer (sp_jumptrailer_f), shrunk to a toy by `scale`: its deck profile, foot to lip
export class Ramp {
  constructor(start, z, dir, scale, profile) {
    // The deck from the foot to the lip, less the little bevel at the foot, which only kicks
    this.points = [[0, 0], ...profile.slice(2)].map(([x, y]) => [x * scale, y * scale]);
    Object.assign(this, { start, z, dir, scale });
    this.length = this.points[this.points.length - 1][0];
    this.height = this.points[this.points.length - 1][1];
  }
  get end() { return this.start + this.dir * this.length; }
  segment(along) {
    const p = this.points;
    let i = 1;
    while (i < p.length - 1 && along > p[i][0]) i++;
    return [p[i - 1], p[i]];
  }
  // Where the road meets the foot, a curve tangent to both, P.RAMP_FILLET long and centred on
  // the foot (a few cm off the drawn deck at most): without it the front wheels hit a kink
  get footSlope() { const [a, b] = this.points; return (b[1] - a[1]) / (b[0] - a[0]); }
  heightAt(along) {
    const f = P.RAMP_FILLET / 2;
    if (along < f) { const u = Math.max(0, along + f); return this.footSlope * u * u / (4 * f); }
    const [a, b] = this.segment(along);
    return a[1] + (b[1] - a[1]) * (along - a[0]) / (b[0] - a[0]);
  }
  slopeAt(along) {
    const f = P.RAMP_FILLET / 2;
    if (along < f) return this.footSlope * Math.max(0, along + f) / (2 * f);
    const [a, b] = this.segment(along);
    return (b[1] - a[1]) / (b[0] - a[0]);
  }
}

export class World {
  constructor() { this.ramps = []; this.events = []; this.t = 0; }

  // How far the point is inside the road or a ramp rising the car's way; normal in this.n*
  penetration(x, y, z, dir) {
    let pen = -y;
    this.nx = 0; this.ny = 1; this.onRamp = false;
    for (const r of this.ramps) {
      if (dir !== 0 && r.dir !== dir) continue;
      if (Math.abs(z - r.z) > P.RAMP_HALF_WIDTH) continue;
      const along = (x - r.start) * r.dir;
      if (along < -P.RAMP_FILLET / 2 || along > r.length) continue;
      const slope = r.slopeAt(along);
      const n = 1 / Math.sqrt(1 + slope * slope);
      const p = (r.heightAt(along) - y) * n;
      if (p <= pen) continue;
      pen = p; this.nx = -r.dir * slope * n; this.ny = n; this.onRamp = along > r.length * 0.75;
    }
    return pen;
  }
}

export class Car {
  constructor(world, data, { dir = 1, x = -15, z = -1.1, cruise = 12, jumpSpeed = 18, rng = Math.random } = {}) {
    this.world = world; this.rng = rng;
    const b = data.bounds;
    this.bounds = b;
    const l = b[5] - b[2], w = b[3] - b[0], h = b[4] - b[1];
    this.length = l; this.width = w; this.height = h;
    this.invI = [12 / ((h * h + l * l) * P.INERTIA_SCALE), 12 / ((w * w + l * l) * P.INERTIA_SCALE), 12 / ((w * w + h * h) * P.INERTIA_SCALE)];
    this.com = [(b[0] + b[3]) / 2, b[1] + h * 0.38, (b[2] + b[5]) / 2];
    const [cx, cy, cz] = this.com;
    const pts = [], wheelOf = [];
    data.wheels.forEach((wh, i) => { pts.push(wh.pivot[0] - cx, wh.pivot[1] - wh.radius - cy, wh.pivot[2] - cz); wheelOf.push(i); });
    this.wheels = data.wheels;
    const sill = b[1] + 0.28 - cy, roof = b[4] - 0.02 - cy;
    for (let top = 0; top <= 1; top++) for (let side = 0; side <= 1; side++) for (let end = 0; end <= 1; end++) {
      const sx = (side === 0 ? b[0] : b[3]) * (top ? 0.62 : 0.95) - cx;
      const sz = (end === 0 ? b[2] + 0.12 : b[5] - 0.12) * (top ? 0.5 : 1) - cz;
      pts.push(sx, top ? roof : sill, sz); wheelOf.push(-1);
    }
    this.points = pts; this.pointWheel = wheelOf; this.inContact = wheelOf.map(() => false);
    this.dir = dir; this.cruise = cruise; this.jumpSpeed = jumpSpeed; this.target = cruise;
    this.state = 'DRIVING'; this.health = 100;
    this.x = x; this.y = cy - b[1] + 0.02; this.z = z;
    this.vx = dir * cruise; this.vy = 0; this.vz = 0;
    this.wx = 0; this.wy = 0; this.wz = 0;
    this.m = new Array(9).fill(0);
    this.setYaw(-dir * Math.PI / 2);
    this.onRamp = false; this.wasOnRamp = false; this.airTime = 0;
  }

  setYaw(a) { this.qw = Math.cos(a / 2); this.qx = 0; this.qy = Math.sin(a / 2); this.qz = 0; this.updateMatrix(); }

  updateMatrix() {
    const { qw, qx, qy, qz } = this, m = this.m;
    const xx = qx * qx, yy = qy * qy, zz = qz * qz, xy = qx * qy, xz = qx * qz, yz = qy * qz, wx = qw * qx, wy = qw * qy, wz = qw * qz;
    m[0] = 1 - 2 * (yy + zz); m[1] = 2 * (xy - wz); m[2] = 2 * (xz + wy);
    m[3] = 2 * (xy + wz); m[4] = 1 - 2 * (xx + zz); m[5] = 2 * (yz - wx);
    m[6] = 2 * (xz - wy); m[7] = 2 * (yz + wx); m[8] = 1 - 2 * (xx + yy);
  }

  spin(dt) {
    const h = 0.5 * dt, { wx, wy, wz, qw, qx, qy, qz } = this;
    const nw = qw + h * (-wx * qx - wy * qy - wz * qz);
    const nx = qx + h * (wx * qw + wy * qz - wz * qy);
    const ny = qy + h * (wy * qw + wz * qx - wx * qz);
    const nz = qz + h * (wz * qw + wx * qy - wy * qx);
    const len = Math.hypot(nw, nx, ny, nz);
    this.qw = nw / len; this.qx = nx / len; this.qy = ny / len; this.qz = nz / len;
    this.updateMatrix();
  }

  addSpin(ax, ay, az, rate) { this.wx += ax * rate; this.wy += ay * rate; this.wz += az * rate; }

  // The AI's speed choice, cut down to what a ramp jump needs
  think() {
    this.target = this.cruise;
    for (const r of this.world.ramps) {
      if (r.dir !== this.dir || Math.abs(r.z - this.z) > 1) continue;
      // From well before the foot until it is off the lip
      const toFoot = (r.start - this.x) * this.dir, toLip = (r.end - this.x) * this.dir;
      if (toFoot < 18 && toLip > -2) this.target = Math.max(this.target, this.jumpSpeed);
    }
  }

  step(dt) {
    this.think();
    const sub = dt / P.SUBSTEPS;
    for (let i = 0; i < P.SUBSTEPS; i++) { this.forces(sub); this.integrate(sub); }
    this.afterStep(dt);
  }

  forces(dt) {
    const m = this.m, W = this.world;
    this.fx = 0; this.fy = -P.GRAVITY; this.fz = 0; this.tx = 0; this.ty = 0; this.tz = 0;
    this.wheelsDown = 0;
    const upright = m[4] > 0.5;
    const fwdX = -m[2], fwdY = -m[5], fwdZ = -m[8];
    let rampNow = false;
    for (let i = 0; i < this.pointWheel.length; i++) {
      const wheel = this.pointWheel[i];
      const lx = this.points[i * 3], ly = this.points[i * 3 + 1], lz = this.points[i * 3 + 2];
      const rx = m[0] * lx + m[1] * ly + m[2] * lz, ry = m[3] * lx + m[4] * ly + m[5] * lz, rz = m[6] * lx + m[7] * ly + m[8] * lz;
      const pen = W.penetration(this.x + rx, this.y + ry, this.z + rz, this.dir);
      if (pen <= 0) { this.inContact[i] = false; continue; }
      const nx = W.nx, ny = W.ny;
      if (W.onRamp) rampNow = true;
      const pvx = this.vx + this.wy * rz - this.wz * ry, pvy = this.vy + this.wz * rx - this.wx * rz, pvz = this.vz + this.wx * ry - this.wy * rx;
      const vn = pvx * nx + pvy * ny;
      if (!this.inContact[i]) { this.inContact[i] = true; this.impact(-vn, wheel >= 0); }
      const fn = Math.max(0, P.CONTACT_K * pen - P.CONTACT_C * vn);
      const tvx = pvx - vn * nx, tvy = pvy - vn * ny, tvz = pvz;
      let ffx, ffy, ffz;
      if (wheel >= 0 && upright) {
        this.wheelsDown++;
        // Grip across the car (its right axis), roll along the rest
        const side = tvx * m[0] + tvy * m[3] + tvz * m[6];
        const latX = side * m[0], latY = side * m[3], latZ = side * m[6];
        ffx = -latX * P.TYRE_GRIP - (tvx - latX) * P.ROLLING;
        ffy = -latY * P.TYRE_GRIP - (tvy - latY) * P.ROLLING;
        ffz = -latZ * P.TYRE_GRIP - (tvz - latZ) * P.ROLLING;
      } else {
        // A bumper touching a ramp on the way up slides; anything else drags
        const f = W.onRamp && upright ? P.BUMPER_FRICTION : P.BODY_FRICTION;
        ffx = -tvx * f; ffy = -tvy * f; ffz = -tvz * f;
        if (W.onRamp) W.events.push({ t: W.t, kind: 'scrape' });
      }
      const cap = (wheel >= 0 ? 1.1 : 0.6) * fn;
      const ft = Math.hypot(ffx, ffy, ffz);
      if (ft > cap && ft > 0) { const s = cap / ft; ffx *= s; ffy *= s; ffz *= s; }
      const fpx = nx * fn + ffx, fpy = ny * fn + ffy, fpz = ffz;
      this.fx += fpx; this.fy += fpy; this.fz += fpz;
      this.tx += ry * fpz - rz * fpy; this.ty += rz * fpx - rx * fpz; this.tz += rx * fpy - ry * fpx;
    }
    this.onRamp = this.onRamp || rampNow;
    if (this.state === 'DRIVING' && this.wheelsDown > 0 && upright) {
      const speed = this.vx * fwdX + this.vy * fwdY + this.vz * fwdZ;
      const accel = Math.min(P.ACCEL_MAX, Math.max(-P.BRAKE_MAX, (this.target - speed) * P.ACCEL_GAIN)) * this.wheelsDown / 4;
      this.fx += fwdX * accel; this.fy += fwdY * accel; this.fz += fwdZ * accel;
      if (this.wheelsDown >= 3) {
        const hx = this.dir, hz = Math.min(0.6, Math.max(-0.6, (-1.1 * this.dir - this.z) * 0.35));
        const err = Math.atan2(fwdZ * hx - fwdX * hz, fwdX * hx + fwdZ * hz);
        this.wy += (err * 10 - this.wy * 5) * dt;
      }
    }
  }

  integrate(dt) {
    const m = this.m;
    this.vx += this.fx * dt; this.vy += this.fy * dt; this.vz += this.fz * dt;
    const lx = (m[0] * this.tx + m[3] * this.ty + m[6] * this.tz) * this.invI[0];
    const ly = (m[1] * this.tx + m[4] * this.ty + m[7] * this.tz) * this.invI[1];
    const lz = (m[2] * this.tx + m[5] * this.ty + m[8] * this.tz) * this.invI[2];
    this.wx += (m[0] * lx + m[1] * ly + m[2] * lz) * dt;
    this.wy += (m[3] * lx + m[4] * ly + m[5] * lz) * dt;
    this.wz += (m[6] * lx + m[7] * ly + m[8] * lz) * dt;
    const drag = 1 - P.SPIN_DRAG * dt;
    this.wx *= drag; this.wy *= drag; this.wz *= drag;
    this.x += this.vx * dt; this.y += this.vy * dt; this.z += this.vz * dt;
    this.spin(dt);
  }

  afterStep(dt) {
    const grounded = this.inContact.some(Boolean);
    if (!grounded) {
      this.airTime += dt;
      if (this.wasOnRamp && this.airTime > 0.05 && this.vy > 1.5 && this.state === 'DRIVING') {
        this.wasOnRamp = false;
        this.takeOff();
      } else if (this.levelAt != null && this.airTime >= this.levelAt) {
        this.levelAt = null;
        this.level();
      }
    } else {
      this.airTime = 0;
      this.levelAt = null;
      this.wasOnRamp = this.onRamp;
    }
    this.onRamp = false;
  }

  // Off the end of a ramp: a toy ramp's kick into the air and maybe a stunt, spun through
  // most of the flight; what is left of it goes on levelling out for the landing (see
  // level()). this.forceStunt picks the stunt for tests.
  takeOff() {
    const m = this.m, rng = this.rng;
    const kinds = ['none', 'none', 'none', 'backflip', 'backflip', 'frontflip', 'roll', 'roll', 'roll', 'helicopter'];
    const { kind, factor } = this.forceStunt ?? { kind: kinds[Math.floor(rng() * kinds.length)], factor: 0.85 + rng() * 0.3 };
    const ground = Math.hypot(this.vx, this.vz);
    this.vy = Math.max(this.vy, ground * P.LAUNCH_LIFT + 1.5, kind === 'none' ? 0 : P.STUNT_LIFT);
    // Time until it is back down on the road, which is lower than the lip it left
    const drop = Math.max(0, this.y - (this.com[1] - this.bounds[1]));
    const air = (this.vy + Math.sqrt(this.vy * this.vy + 2 * P.GRAVITY * drop)) / P.GRAVITY;
    const stuntTime = kind === 'none' ? 0 : air * P.STUNT_SHARE;
    this.levelAt = this.airTime + stuntTime;
    this.landAt = this.airTime + air;
    const w = this.spinRate(2 * Math.PI * factor, stuntTime);
    const way = rng() < 0.5 ? 1 : -1;
    const set = (ax, ay, az, k) => { this.wx = ax * k; this.wy = ay * k; this.wz = az * k; };
    switch (kind) {
      case 'none': set(0, 0, 0, 0); break;
      case 'backflip': set(m[0], m[3], m[6], w); break;             // nose up and over, about its right
      case 'frontflip': set(m[0], m[3], m[6], -w); break;
      case 'roll': set(-m[2], -m[5], -m[8], w * way); break;        // about its nose
      case 'helicopter': set(0, 1, 0, w * way); break;
    }
    this.world.events.push({ t: this.world.t, kind: 'takeoff', stunt: kind, vy: this.vy, speed: ground });
  }

  // The spin that turns through an angle in a time, spin drag and all
  spinRate(angle, time) {
    return time <= 0 ? 0 : angle * P.SPIN_DRAG / (1 - Math.exp(-P.SPIN_DRAG * time));
  }

  // Once the stunt is through: turn the shortest way to wheels-down and a little nose down,
  // if the stunt came round far enough to make it; a botched one just keeps tumbling
  level() {
    const m = this.m;
    const ux = m[1], uy = m[4], uz = m[7];
    const fx = -m[2], fz = -m[8], fl = Math.hypot(fx, fz) || 1;
    const s = Math.sin(P.LANDING_NOSE_DOWN), c = Math.cos(P.LANDING_NOSE_DOWN);
    const tx = fx / fl * s, ty = c, tz = fz / fl * s;
    const dot = ux * tx + uy * ty + uz * tz;
    if (dot < Math.cos(P.SAVE_ANGLE)) return;
    let ax = uy * tz - uz * ty, ay = uz * tx - ux * tz, az = ux * ty - uy * tx;
    const len = Math.hypot(ax, ay, az);
    const w = len < 1e-5 ? 0 : this.spinRate(Math.acos(Math.min(1, dot)), Math.max(0.1, this.landAt - this.airTime)) / len;
    this.wx = ax * w; this.wy = ay * w; this.wz = az * w;
  }

  impact(speed, wheel) {
    const damage = wheel ? (speed - P.WHEEL_HURT_SPEED) * 5 : (speed - 2.5) * 7;
    if (damage > 0) { this.health -= damage; this.world.events.push({ t: this.world.t, kind: 'hit', wheel, speed, damage }); }
  }

  // Pitch of the nose above the horizontal, degrees
  get pitch() { return Math.asin(Math.max(-1, Math.min(1, -this.m[5]))) * 180 / Math.PI; }
  get up() { return this.m[4]; }
}

// One car at one ramp. Returns the run and a verdict.
export function rampRun(data, { scale = 0.38, profile, cruise = 12, jumpSpeed = 18, seconds = 5, stunt = { kind: 'none', factor: 1 } } = {}) {
  const world = new World();
  const ramp = new Ramp(-2, -1.1, 1, scale, profile);
  world.ramps.push(ramp);
  const length = ramp.length;
  const car = new Car(world, data, { cruise, jumpSpeed });
  car.forceStunt = stunt;
  const frames = [];
  const dt = 1 / 60;
  for (let t = 0; t < seconds; t += dt) {
    world.t = t;
    car.step(dt);
    frames.push({ t, x: car.x, y: car.y, vx: car.vx, vy: car.vy, pitch: car.pitch, up: car.up, m: car.m.slice(), contact: car.inContact.slice() });
  }
  const takeoff = world.events.find(e => e.kind === 'takeoff');
  const onRampFrames = frames.filter(f => f.x > -2 && f.x < -2 + length);
  const minRampSpeed = Math.min(...onRampFrames.map(f => f.vx), Infinity);
  const peak = Math.max(...frames.map(f => f.y)) - frames[0].y;
  const t0 = takeoff ? takeoff.t : 0;
  const landing = takeoff ? frames.find(f => f.t > t0 + 0.1 && f.contact.some(Boolean)) : null;
  const last = frames[frames.length - 1];
  return {
    car, frames, world, takeoff,
    verdict: {
      tookOff: !!takeoff,
      takeoffSpeed: takeoff ? +takeoff.speed.toFixed(1) : null,
      minRampSpeed: +minRampSpeed.toFixed(1),
      scrapes: world.events.filter(e => e.kind === 'scrape').length,
      peak: +peak.toFixed(2),
      airtime: landing ? +(landing.t - t0).toFixed(2) : null,
      jump: landing ? +(landing.x - (-2 + length)).toFixed(1) : null,
      distance: +(last.x - (-2 + length)).toFixed(1),
      finalUp: +last.up.toFixed(2),
      health: Math.round(car.health),
    },
  };
}
