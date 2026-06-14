import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const sandbox = { globalThis: {} };
runInNewContext(readFileSync(join(root, 'chipset.js'), 'utf8'), sandbox);
export const CS = sandbox.globalThis.AmbientChipSet;
