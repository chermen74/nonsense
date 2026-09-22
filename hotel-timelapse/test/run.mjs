/**
 * Runs the TypeScript test files without adding a test framework.
 *
 * esbuild already ships inside vite, so each test is bundled to a temp file and
 * run in its own process -- a failing test exits non-zero without taking the
 * rest of the suite with it.
 */

import { build } from 'esbuild'
import { spawnSync } from 'node:child_process'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, join } from 'node:path'

const TESTS = ['test/accrue.test.ts', 'test/layout.test.ts', 'test/movement.test.ts']

const outDir = mkdtempSync(join(tmpdir(), 'hotel-timelapse-test-'))
let failed = 0

for (const entry of TESTS) {
  const outfile = join(outDir, basename(entry).replace(/\.ts$/, '.mjs'))
  await build({
    entryPoints: [entry],
    bundle: true,
    platform: 'node',
    format: 'esm',
    outfile,
    logLevel: 'warning',
  })

  console.log(`\n=== ${entry} ===`)
  // cwd stays at the project root so tests can read public/*.json by path.
  const run = spawnSync(process.execPath, [outfile], { stdio: 'inherit' })
  if (run.status !== 0) failed++
}

if (failed > 0) {
  console.error(`\n${failed} test file(s) failed`)
  process.exit(1)
}
console.log('\nall test files passed')
