export type Policy = 'FCFS' | 'Priority' | 'Round Robin' | 'MLFQ' | 'Critical Path';
export type Status = 'Pending' | 'Ready' | 'Running' | 'Done' | 'Failed';
export type TaskDef = { id: string; burst: number; priority: number; deps: string[]; fail?: boolean };
export type SimEvent = { time: number; type: 'ready' | 'start' | 'yield' | 'done' | 'failed' | 'demote' | 'boost'; task: string; worker?: number; level?: number; duration?: number; note?: string };
export type Slice = { task: string; worker: number; start: number; end: number; level: number };
export type Run = { events: SimEvent[]; slices: Slice[]; duration: number; firstWait: number; turnaround: number; failed: string[] };

export const presets: Record<string, TaskDef[]> = {
  'Build pipeline': [
    { id: 'repository-check', burst: 14, priority: 2, deps: [] }, { id: 'prepare-assets', burst: 11, priority: 2, deps: [] },
    { id: 'compile-api', burst: 27, priority: 3, deps: ['repository-check'] }, { id: 'compile-web', burst: 23, priority: 3, deps: ['repository-check', 'prepare-assets'] },
    { id: 'test-api', burst: 20, priority: 2, deps: ['compile-api'] }, { id: 'test-web', burst: 17, priority: 2, deps: ['compile-web'] },
    { id: 'assemble-release', burst: 28, priority: 2, deps: ['test-api', 'test-web'] }, { id: 'stage-release', burst: 14, priority: 2, deps: ['assemble-release'] }, { id: 'publish-release', burst: 9, priority: 2, deps: ['stage-release'] }
  ],
  'Diamond': [{ id: 'source', burst: 18, priority: 2, deps: [] }, { id: 'unit-tests', burst: 28, priority: 3, deps: ['source'] }, { id: 'lint', burst: 15, priority: 2, deps: ['source'] }, { id: 'package', burst: 17, priority: 2, deps: ['unit-tests', 'lint'] }],
  'Wide fan-out': [{ id: 'checkout', burst: 12, priority: 1, deps: [] }, ...Array.from({ length: 7 }, (_, i) => ({ id: ['compile-api', 'compile-web', 'unit-tests', 'lint', 'assets', 'docs', 'security-scan'][i], burst: 16 + (i % 3) * 7, priority: i % 3 + 1, deps: ['checkout'] })), { id: 'package', burst: 20, priority: 2, deps: ['compile-api', 'compile-web', 'assets'] }],
  'Failure cascade': [{ id: 'compile-api', burst: 18, priority: 2, deps: [], fail: true }, { id: 'unit-tests', burst: 20, priority: 2, deps: ['compile-api'] }, { id: 'package', burst: 14, priority: 1, deps: ['unit-tests'] }, { id: 'deploy-staging', burst: 12, priority: 1, deps: ['package'] }, { id: 'lint', burst: 10, priority: 2, deps: [] }],
  'Cycle demo': [{ id: 'cycle-a', burst: 10, priority: 1, deps: ['cycle-c'] }, { id: 'cycle-b', burst: 10, priority: 1, deps: ['cycle-a'] }, { id: 'cycle-c', burst: 10, priority: 1, deps: ['cycle-b'] }]
};

export function cyclePath(tasks: TaskDef[]): string[] | null {
  const ids = new Set(tasks.map(t => t.id));
  const color = new Map<string, number>(); const stack: string[] = [];
  const visit = (id: string): string[] | null => {
    color.set(id, 1); stack.push(id);
    for (const dep of tasks.find(t => t.id === id)?.deps ?? []) {
      if (!ids.has(dep)) continue;
      if (color.get(dep) === 1) return [...stack.slice(stack.indexOf(dep)), dep];
      if (!color.has(dep)) { const found = visit(dep); if (found) return found; }
    }
    stack.pop(); color.set(id, 2); return null;
  };
  for (const task of tasks) { if (!color.has(task.id)) { const found = visit(task.id); if (found) return found; } }
  return null;
}

export function runSimulation(tasks: TaskDef[], policy: Policy, workerCount: number, quantum: number): Run {
  const cycle = cyclePath(tasks);
  if (cycle) throw new Error(`Dependency cycle detected: ${cycle.join(' -> ')}`);
  for (const task of tasks) for (const dep of task.deps) if (!tasks.some(candidate => candidate.id === dep)) throw new Error(`Task ${task.id} depends on unknown task ${dep}`);
  const events: SimEvent[] = []; const slices: Slice[] = []; const pending = new Map(tasks.map(t => [t.id, { remaining: t.burst, level: 0, first: -1, finished: false, failed: false }]));
  const ready: string[] = []; const busy = Array(workerCount).fill(0).map(() => ({ until: 0, task: '' })); let clock = 0; let finished = 0; let guard = 0;
  const unresolved = (id: string) => tasks.find(t => t.id === id)!.deps.filter(dep => !pending.get(dep)?.finished && !pending.get(dep)?.failed).length;
  const release = () => { for (const task of tasks) { const state = pending.get(task.id)!; if (!state.finished && !state.failed && state.first === -1 && unresolved(task.id) === 0) { state.first = clock; ready.push(task.id); events.push({ time: clock, type: 'ready', task: task.id }); } } };
  const failDescendants = (id: string) => { const queue = [id]; while (queue.length) { const current = queue.shift()!; for (const task of tasks) if (task.deps.includes(current)) { const state = pending.get(task.id)!; if (!state.finished && !state.failed) { state.failed = true; finished++; events.push({ time: clock, type: 'failed', task: task.id, note: 'failure cascade' }); queue.push(task.id); } } } };
  release();
  while (finished < tasks.length && guard++ < 10000) {
    for (let w = 0; w < busy.length; w++) if (busy[w].task && busy[w].until <= clock) {
      const id = busy[w].task; const state = pending.get(id)!; const task = tasks.find(t => t.id === id)!; busy[w].task = '';
      if (task.fail && state.remaining <= 0) { state.failed = true; finished++; events.push({ time: clock, type: 'failed', task: id, worker: w, note: 'task failed' }); failDescendants(id); }
      else if (state.remaining <= 0) { state.finished = true; finished++; events.push({ time: clock, type: 'done', task: id, worker: w }); }
      else { if (policy === 'MLFQ' && state.level < 2) { state.level++; events.push({ time: clock, type: 'demote', task: id, level: state.level }); } ready.push(id); events.push({ time: clock, type: 'yield', task: id, worker: w, level: state.level }); }
      release();
    }
    let launched = false;
    for (let w = 0; w < busy.length; w++) if (!busy[w].task && ready.length) {
      let chosen = 0;
      if (policy === 'Priority') chosen = ready.reduce((best, id, i) => tasks.find(t => t.id === id)!.priority + (clock - (pending.get(id)!.first || clock)) / 30 > tasks.find(t => t.id === ready[best])!.priority + (clock - (pending.get(ready[best])!.first || clock)) / 30 ? i : best, 0);
      if (policy === 'MLFQ') chosen = ready.reduce((best, id, i) => pending.get(id)!.level < pending.get(ready[best])!.level ? i : best, 0);
      if (policy === 'Critical Path') chosen = ready.reduce((best, id, i) => criticalWeight(id, tasks) > criticalWeight(ready[best], tasks) ? i : best, 0);
      const [id] = ready.splice(chosen, 1); const state = pending.get(id)!; const slice = policy === 'MLFQ' ? [10, 25, 50][state.level] : quantum;
      const duration = Math.min(state.remaining, slice); state.remaining -= duration;
      busy[w] = { until: clock + duration, task: id }; slices.push({ task: id, worker: w, start: clock, end: clock + duration, level: state.level }); events.push({ time: clock, type: 'start', task: id, worker: w, level: state.level, duration }); launched = true;
    }
    if (launched) continue;
    const next = Math.min(...busy.filter(b => b.task).map(b => b.until));
    if (!Number.isFinite(next)) break;
    clock = next;
  }
  const failed = tasks.filter(t => pending.get(t.id)!.failed).map(t => t.id); const completed = tasks.filter(t => pending.get(t.id)!.finished);
  return { events: events.sort((a, b) => a.time - b.time), slices, duration: clock, firstWait: completed.length ? completed.reduce((a, t) => a + pending.get(t.id)!.first, 0) / completed.length : 0, turnaround: completed.length ? completed.reduce((a, t) => a + slices.filter(s => s.task === t.id).at(-1)!.end, 0) / completed.length : 0, failed };
}

function criticalWeight(id: string, tasks: TaskDef[], memo = new Map<string, number>()): number {
  if (memo.has(id)) return memo.get(id)!;
  const task = tasks.find(t => t.id === id)!; const downstream = tasks.filter(t => t.deps.includes(id));
  const value = task.burst + Math.max(0, ...downstream.map(t => criticalWeight(t.id, tasks, memo))); memo.set(id, value); return value;
}

export function statusAt(id: string, time: number, tasks: TaskDef[], events: SimEvent[]): Status {
  const relevant = events.filter(e => e.task === id && e.time <= time); const last = relevant.at(-1);
  if (!last) return tasks.find(t => t.id === id)!.deps.length ? 'Pending' : 'Ready';
  if (last.type === 'done') return 'Done'; if (last.type === 'failed') return 'Failed'; if (last.type === 'start') return 'Running'; return 'Ready';
}

export function layoutTasks(tasks: TaskDef[]) {
  const byId = new Map(tasks.map(t => [t.id, t])); const layers = new Map<string, number>();
  const visiting = new Set<string>();
  const layer = (id: string): number => { if (layers.has(id)) return layers.get(id)!; if (visiting.has(id)) return 0; visiting.add(id); const value = Math.max(-1, ...(byId.get(id)?.deps ?? []).filter(d => byId.has(d)).map(layer)) + 1; visiting.delete(id); layers.set(id, value); return value; };
  tasks.forEach(t => layer(t.id)); const groups = new Map<number, TaskDef[]>(); tasks.forEach(t => groups.set(layers.get(t.id)!, [...(groups.get(layers.get(t.id)!) ?? []), t]));
  const positions = new Map<string, { x: number; y: number }>(); const maxRows = Math.max(...[...groups.values()].map(g => g.length), 1);
  for (const [col, group] of groups) group.forEach((task, row) => positions.set(task.id, { x: 70 + col * 250, y: 60 + row * 104 + (maxRows - group.length) * 52 }));
  return { positions, width: 180 + Math.max(...layers.values(), 0) * 250, height: 140 + maxRows * 104 };
}
