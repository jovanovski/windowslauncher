// node scripts/mm2cars_sim/run.mjs - every car over the smallest, middling and steepest ramp
import { CARS, TRAILER_PROFILE } from './cars.js';
import { rampRun } from './sim.js';

// The smallest and biggest the trailer is shrunk to (TaskbarCarsView.spawnRamp)
const RAMPS = [{ scale: 0.33 }, { scale: 0.42 }];
const SPEEDS = { bullet: 18, beetle: 18.8, cab: 19.6, panoz: 20.4, cop: 21.2 };
const CRUISE = { bullet: 12, beetle: 10, cab: 9, panoz: 13.5, cop: 12.5 };

let failed = 0;
for (const [id, data] of Object.entries(CARS)) {
  for (const ramp of RAMPS) {
    const { verdict: v } = rampRun(data, { ...ramp, profile: TRAILER_PROFILE, cruise: CRUISE[id], jumpSpeed: SPEEDS[id] });
    const ok = v.tookOff && v.peak > 1.5 && v.peak < 4.5 && v.finalUp > 0.8 && v.health > 90 && v.scrapes < 20 && v.jump > 7 && v.jump < 18;
    if (!ok) failed++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${id.padEnd(6)} trailer x${ramp.scale}`, JSON.stringify(v));
  }
}
// Stunts with exactly one turn should come down on their wheels (TaskbarCarsView.takeOff
// varies the turn 0.85-1.15x so some don't)
const STUNTS = ['backflip', 'frontflip', 'roll', 'helicopter'];
for (const [id, data] of Object.entries(CARS)) {
  for (const name of STUNTS) {
    const { verdict: v } = rampRun(data, { scale: 0.38, profile: TRAILER_PROFILE, cruise: CRUISE[id], jumpSpeed: SPEEDS[id], stunt: { kind: name, factor: 1 } });
    const ok = v.finalUp > 0.8 && v.health > 60;
    if (!ok) failed++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${id.padEnd(6)} ${name.padEnd(10)}`, JSON.stringify(v));
  }
}
console.log(failed ? `${failed} failed` : 'all clean');
